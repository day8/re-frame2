(ns re-frame.machines.classification
  "Machine-owned, PROJECTION-RELATIVE durable `:data` classification —
  the EP-0025 §subsystems reversal of the rf2-398kql frame-owned model.

  ## The reversal (EP-0025 B5, rf2-h3d8tf — authorised by Mike's B0 ruling)

  rf2-398kql made the FRAME the sole owner of a machine's durable `:data`
  classification: an app declared the ABSOLUTE runtime-db snapshot path
  (`[:rf.runtime/machines :snapshots <actor-id> :data :token]`) on
  `reg-frame` `:sensitive` / `:large {:app-db …}`. That is the
  storage-position problem EP-0025 names: the author had to know the
  framework's runtime-db storage shape AND name each spawned instance id by
  hand, fail-open under refactor and on every generated `<type>#<n>`.

  EP-0025 reverses it: the machine **definition** declares its sensitive /
  large `:data` slots PROJECTION-RELATIVE to one actor snapshot's `:data`
  (the matrix projection root), and the runtime lowers them PER ACTOR
  INSTANCE — at spawn / singleton first-boot — into the SAME per-frame
  elision registry (`[:rf.runtime/elision …]`, EP-0025 B3), dropping them on
  destroy (by any cause). The declaration travels with the machine def and
  applies to every instance, so a `:spawn`-generated `<type>#42` is
  classified with zero per-instance author code — XState-v5-conformant
  semantics (the classification rides the machine def like `context` shape,
  applied per actor).

  ## Projection root + lowering

  The matrix projection root is **one actor snapshot's `:data`**, so an
  author writes snapshot-relative `:data`-rooted paths:

  ```clojure
  (rf/reg-machine :checkout/payment
    {:sensitive [[:data :payment :token]]
     :large     [[:data :payment :receipt-pdf]]
     :schemas {:data …}, :initial …, :states …})
  ```

  At spawn / first-boot the runtime LOWERS each declared path per actor by
  prefixing the instance's absolute snapshot prefix
  `[:rf.runtime/machines :snapshots <actor-id>]`, producing the absolute
  runtime-db declaration the egress read path already consumes
  (`re-frame.classification/frame-snapshot-classification` re-roots it snapshot-relative; the
  SSR / trace / projection boundaries are UNCHANGED — the SOURCE of the
  registry entry is `:source :machine`, a peer of the general commit-plane
  `:source :effect` route).

  ## Read path is untouched

  The registry is the single source of truth for snapshot-`:data` egress
  classification (`frame-snapshot-classification` reads `(keys decls)` from
  `re-frame.elision/sensitive-declarations` / `declarations`, unioning ALL
  sources). Lowering a machine declaration there at the absolute snapshot
  path makes the existing trace-egress / SSR-hydration redaction fire with
  NO change to those readers — the reversal is a write-source flip, not a
  new read mechanism.

  ## Failure posture (EP-0025)

  Fail-LOUD on a malformed declaration AT REGISTRATION (a non-vector
  `:sensitive` / `:large`, a non-path entry) — a hygiene helper declared
  wrong is an author bug, surfaced at `reg-machine` like every other
  registration-shape fault. Fail-OPEN on omission (an undeclared slot ships
  raw — the hygiene bargain). The lowering itself is value-INDEPENDENT: the
  declaration redacts whatever later occupies the snapshot `:data` slot, a
  harmless no-op over an absent value."
  (:require [re-frame.elision :as elision]
            [re-frame.error :as error]
            [re-frame.machines.paths :as paths]
            [re-frame.path :as path]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private classification-axis-keys
  "The two EP-0025 projection-relative declaration axes a `reg-machine`
  spec may carry — `:sensitive` (redact at egress) and `:large` (size
  marker at egress). The CLEAR tails (`:clear-sensitive` / `:clear-large`)
  are handler-effect verbs, not registration declarations — a machine
  declares standing classification, it does not un-classify at definition
  time."
  #{:sensitive :large})

(defn- declaration-defect
  "PURE fail-loud-INPUT validator for one machine classification axis
  payload. Returns a human reason string for the FIRST defect, or nil when
  the payload is well-shaped. A payload is well-shaped iff it is a vector of
  valid concrete `:rf/path` vectors (EP-0012 — the same shape the four
  commit-plane effects validate, `re-frame.elision/classification-effect-defect`).
  Value-INDEPENDENT: validates the SHAPE of the declaration, never a runtime
  value."
  [k payload]
  (cond
    (not (vector? payload))
    (str "the machine `" k "` classification declaration must be a vector of "
         "snapshot-relative `:data`-rooted paths (e.g. [[:data :token]]); got a "
         (pr-str (type payload)))
    :else
    (some (fn [p]
            (cond
              (not (sequential? p))
              (str "each path in the machine `" k "` classification declaration "
                   "must be a path vector; got " (pr-str p))
              :else
              (try
                (path/normalize-concrete p)
                nil
                (catch #?(:clj Throwable :cljs :default) e
                  (str "an invalid path in the machine `" k "` classification "
                       "declaration: " (or (ex-message e) (str e)))))))
          payload)))

(defn validate-machine-classification!
  "Fail LOUD at the `reg-machine` boundary when a machine spec's
  projection-relative `:sensitive` / `:large` declaration is malformed (a
  non-vector axis, a non-path entry, an invalid `:rf/path` segment) — the
  EP-0025 fail-loud-input posture, surfaced as
  `:rf.error/invalid-machine-classification` at registration like every other
  registration-shape fault. A spec that declares NEITHER axis is a clean
  no-op (the common fail-open case — an undeclared machine ships its `:data`
  raw). Pure / side-effect-free except the throw."
  [machine-id spec]
  (doseq [k classification-axis-keys]
    (when (contains? spec k)
      (when-let [reason (declaration-defect k (get spec k))]
        (error/throw-error!
          :rf.error/invalid-machine-classification
          'rf-machines/reg-machine
          (str "reg-machine " machine-id " declares a malformed " k
               " data-classification: " reason ". Per EP-0025 a machine "
               "declares :sensitive / :large as a vector of snapshot-relative "
               ":data-rooted `:rf/path` vectors (e.g. {:sensitive [[:data "
               ":token]]}), lowered per actor instance at spawn.")
          {:recovery :fix-registration
           :extra    {:machine-id machine-id
                      :axis       k
                      :value      (get spec k)}}))))
  nil)

(defn machine-declarations
  "Extract the machine `spec`'s projection-relative classification
  declarations as `{:sensitive [paths] :large [paths]}` — each path a
  normalized concrete snapshot-relative `:data`-rooted vector (e.g.
  `[:data :token]`). A slot is omitted when the axis is absent / empty.
  Returns nil when the spec declares NEITHER axis (the common no-op).
  Assumes the spec already passed `validate-machine-classification!`. Pure."
  [spec]
  (let [norm (fn [k] (into [] (map #(vec (path/normalize-concrete %))) (get spec k)))
        sens (when (seq (get spec :sensitive)) (norm :sensitive))
        larg (when (seq (get spec :large))     (norm :large))]
    (when (or (seq sens) (seq larg))
      (cond-> {}
        (seq sens) (assoc :sensitive sens)
        (seq larg) (assoc :large larg)))))

(defn- absolute-snapshot-path
  "Re-root a snapshot-relative `:data`-rooted declaration `path` to the
  ABSOLUTE per-frame elision-registry path for the actor snapshot keyed
  under `actor-id` — `[:rf.runtime/machines :snapshots <actor-id> :data …]`
  — the exact shape `re-frame.classification/frame-snapshot-classification` re-roots back
  snapshot-relative at egress. Pure."
  [actor-id path]
  (into (paths/snapshot-path actor-id) path))

(defn- apply-decls
  "Pure registry transform: ADD (`set?` true) or DROP (`set?` false) the
  absolute snapshot-rooted declarations for `actor-id`'s `decls`
  (`{:sensitive [paths] :large [paths]}`) on a base elision-registry value
  `reg`. SET writes `{:source :machine}` at each axis slot
  (`:sensitive-declarations` / `:declarations` — the SAME slots the four
  commit-plane effects and `reg-flow` outputs populate) WITHOUT clobbering a
  foreign-source standing entry (the registry is one-entry-per-path-per-axis;
  an app-effect / flow / route claim on the SAME absolute path is left
  standing — same posture as the commit-plane SET, rf2-p4spo4); DROP is
  SOURCE-SCOPED — it dissocs a named absolute path ONLY when its standing entry
  is the machine's own (`:source :machine`). A path ALSO claimed by another
  source (Spec 015 L149 explicitly permits an app to additionally classify a
  subsystem absolute path from a handler effect, `:source :effect`) is LEFT
  INTACT, so an actor teardown never silently un-redacts a path another owner
  still classifies (a privacy fail-open). Mirrors the effect clear
  (`re-frame.elision/apply-classification-effects`, source-scoped) and the
  route lowering (`re-frame.routing.classification/without-route-sourced`,
  which filters `:source :route`) — the sibling subsystems' source-scoped
  drops (rf2-7bsyza). An emptied axis slot is pruned. Returns the new registry
  value."
  [reg actor-id decls set?]
  (reduce
    (fn [reg [axis slot]]
      (let [paths (get decls axis)]
        (if-not (seq paths)
          reg
          (let [abs (map #(absolute-snapshot-path actor-id %) paths)
                cur (get reg slot)
                updated (if set?
                          (reduce (fn [m p]
                                    ;; Do NOT overwrite a foreign-source entry —
                                    ;; only write the machine claim into a free
                                    ;; or already-machine-owned slot.
                                    (let [src (:source (get m p))]
                                      (if (or (nil? src) (= :machine src))
                                        (assoc m p {:source :machine})
                                        m)))
                                  (or cur {}) abs)
                          ;; SOURCE-SCOPED drop: remove a path only when its
                          ;; standing entry is the machine's own.
                          (reduce (fn [m p]
                                    (if (= :machine (:source (get m p)))
                                      (dissoc m p)
                                      m))
                                  (or cur {}) abs))]
            (if (seq updated)
              (assoc reg slot updated)
              (dissoc reg slot))))))
    (or reg {})
    {:sensitive :sensitive-declarations
     :large     :declarations}))

(defn lower-at-spawn!
  "LOWER the machine `spec`'s projection-relative `:sensitive` / `:large`
  declarations into `frame-id`'s per-frame elision registry, PER ACTOR
  INSTANCE — re-rooting each snapshot-relative `:data` path to the absolute
  snapshot path for `actor-id` and writing it `{:source :machine}` into the
  registry (EP-0025 §subsystems). A no-op when the spec declares no
  classification (the common fail-open case) or `actor-id` is nil. Runs at
  spawn / singleton first-boot, the instance-birth point — so a
  `:spawn`-generated `<type>#n` is classified the moment its snapshot
  lands, with no per-instance author code.

  Writes through `re-frame.elision/swap-elision-slot!` — the SAME registry
  write surface the four commit-plane effects and the marks API share — so
  the lowered declaration unions with every other source at egress-lookup
  time and the existing readers (`frame-snapshot-classification`, the SSR / trace
  boundaries) redact with no change. Returns nil."
  [frame-id actor-id spec]
  (when actor-id
    (when-let [decls (machine-declarations spec)]
      (elision/swap-elision-slot! frame-id
                                  (fn [reg] (apply-decls reg actor-id decls true)))))
  nil)

(defn drop-at-destroy!
  "DROP the machine `spec`'s per-instance classification declarations for
  `actor-id` from `frame-id`'s elision registry — the teardown half of
  `lower-at-spawn!` (EP-0025 §subsystems: a subsystem instance's
  classification lives and dies with the instance). Dissocs exactly the
  absolute snapshot-rooted paths the spawn lowered (other-sourced entries
  for the same path survive); an emptied axis slot is pruned so a frame that
  destroys its last classified actor is left without a stray registry
  sub-tree (no leak). A no-op when the spec declared no classification or
  `actor-id` is nil. Returns nil."
  [frame-id actor-id spec]
  (when actor-id
    (when-let [decls (machine-declarations spec)]
      (elision/swap-elision-slot! frame-id
                                  (fn [reg] (apply-decls reg actor-id decls false)))))
  nil)
