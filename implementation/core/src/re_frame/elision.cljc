(ns re-frame.elision
  "Frame-owned wire-boundary elision.

  Canonical durable app-db classification comes from the EP-0025
  commit-plane classification effects: a `reg-event` handler returns
  `:large [[:docs :csv]]` alongside `:db` to hydrate
  `[:rf.runtime/elision :declarations]`, and `:sensitive [[…]]` to hydrate
  `[:rf.runtime/elision :sensitive-declarations]` (written by
  `apply-classification-effects` under `:source :effect`). EP-0025: the
  durable `:sensitive` / `:large {:app-db …}` *frame annotation* is REMOVED
  — a frame is not app-db's definition site. The other sources that populate
  these slots are `reg-flow` output declarations (`:source :flow`) and
  subsystem projection-relative declarations (resources / routing); all
  sources union at egress-lookup time. EP-0015 §8
  (Schemas Describe Shape, Not Public Egress Policy): schemas describe
  shape and validation, NOT durable app-db egress policy — a
  `reg-app-schema` `{:sensitive? true}` / `{:large? true}` slot prop is
  not a route into this registry. Schema `:sensitive?`
  drives schema-validation-failure-trace redaction (the schema's own
  egress product — `re-frame.schemas`), and machine `:data-schema` per-slot
  props classify machine `:data` (EP-0005). There are no
  imperative large-path APIs.

  EP-0001: the elision declaration registry is DURABLE,
  serializable framework state (it must survive epoch-restore / SSR-
  hydration so an off-box projection redacts consistently), so it lives in
  the frame's **runtime-db** partition at `[:rf.runtime/elision …]`, not in
  app-db. Per Conventions §Reserved runtime-db keys. Reads come off the
  runtime-db projection; writes go through `frame/swap-runtime-db!` (the
  runtime-db partition write surface)."
  (:require [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.path :as path]
            [re-frame.privacy :as privacy]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- runtime size threshold ----------------------------------------------
;;
;; Per API.md §Size-elision wire-boundary walker and §Configure keys, the
;; runtime auto-detect threshold for the `:rf.warning/large-value-unschema'd`
;; advisory is configurable. Precedence (normative, API.md L507):
;;
;;   explicit `:rf.size/threshold-bytes` opt  >  `(rf/configure! {:elision …})`  >  default
;;
;; A threshold of 0 disables runtime auto-detect entirely (only
;; frame-declared `:large` entries elide); the unschema'd-large warning
;; never fires.
;; The default mirrors the documented `{:rf.size/threshold-bytes 16384}`.

(def ^:private default-threshold-bytes 16384)

(defonce ^:private config
  ;; Map shape so future :elision configure-keys land additively, matching
  ;; `re-frame.subs.cache/config`.
  (atom {:rf.size/threshold-bytes default-threshold-bytes}))

(defn configure!
  "Update the elision configuration. Supports
  `{:rf.size/threshold-bytes N}` — a non-negative integer runtime
  auto-detect size threshold for the `:rf.warning/large-value-unschema'd`
  advisory (0 disables runtime auto-detect; only frame-declared `:large`
  entries elide). Per API.md §Configure keys (`:elision`) and Spec 009
  §Size elision in traces. Routed from `re-frame.core/configure!`."
  [opts]
  (when (map? opts)
    (swap! config merge (select-keys opts [:rf.size/threshold-bytes])))
  nil)

(defn current-config
  "Return the current elision configuration map. Public for tests and
  tools that want to display the configured runtime size threshold."
  []
  @config)

(defn- configured-threshold-bytes
  "The configured runtime size threshold, falling back to the documented
  default when no `configure` call has set it."
  []
  (let [v (:rf.size/threshold-bytes @config)]
    (if (some? v) v default-threshold-bytes)))

(defonce ^:private warned-unschema'd
  (atom #{}))

(defn- registry-of
  [frame-id]
  (when-let [container (frame/runtime-db-container frame-id)]
    (get-in (adapter/read-container container) [:rf.runtime/elision])))

(defn ^:no-doc write-elision-slot
  "Set or clear the per-frame elision registry inside `runtime-db`. When
  `new-reg` is non-empty it lands at `[:rf.runtime/elision]`. When empty,
  the `:rf.runtime/elision` key is removed so a frame that never used
  elision doesn't manifest a stray nil sub-tree.

  Internal helper; exposed (with `^:no-doc`) so the EP-0025 commit-plane
  classification effects (`apply-classification-effects`) and
  `re-frame.flows.registry` share a single source of truth for the prune
  logic. Not part of the public API.

  EP-0001: operates on the runtime-db partition value (the
  elision registry is durable framework state — Conventions §Reserved
  runtime-db keys)."
  [runtime-db new-reg]
  (cond
    (seq new-reg)
    (assoc runtime-db :rf.runtime/elision new-reg)

    ;; Clearing — only mutate when there's actually an :rf.runtime/elision
    ;; slot to clear, so a frame that never used elision doesn't get a
    ;; stray nil entry.
    (contains? runtime-db :rf.runtime/elision)
    (dissoc runtime-db :rf.runtime/elision)

    :else
    runtime-db))

(defn ^:no-doc restore-elision-slot
  "EP-0025 SOURCE-AWARE rollback restore for the per-frame elision registry.

  Used by the router's post-commit schema-rollback arm. A rejected `:db`
  unwinds with the WHOLE frame-state transition (both partitions back to the
  pre-handler value). Per Spec 015 §84 a classification `walks back atomically
  with the frame on a revert` — but only the IN-BAND classification this event
  produced: the four commit-plane classification effects write
  `{:source :effect}` marks (`apply-classification-effects`), and those MUST
  unwind. The OTHER sources are written OUT-OF-BAND by long-lived subsystems —
  `reg-flow` outputs (`:source :flow`), machine actor spawns
  (`:source :machine`), route activation (`:source :route`) — and a
  declaration one of those LOWERED during the rejected event may LEGITIMATELY
  survive the rollback (the actor / route registration is not the
  schema-rejected `:db` effect).

  The base is `pre-reg` — the per-frame elision registry as it was at
  chain-start, BEFORE the cascade ran (the `[:rf.runtime/elision]` sub-tree of
  the chain-start `runtime-before` runtime-db, captured by reference in the
  router's `assemble-initial-ctx`, so it is a true PRE-CASCADE snapshot at no
  copy cost). Restoring to that base alone already:

    - UNWINDS in-band `:source :effect` marks the rejected event added
      (rf2-5lo1fk) — they were not in the pre-cascade snapshot, so they are
      gone; and
    - RESTORES an out-of-band `:source :flow` mark a mid-drain reentrant
      `reg-flow` output-path MOVE dropped (rf2-o6rsi2) — the OLD path's
      `:source :flow` entry is still in the pre-cascade snapshot, so it comes
      back (the live registry had already dropped it; carrying live forward
      would leak — a privacy fail-open on revert).

  But the base alone would also drop an out-of-band declaration LOWERED
  DURING the event (a `:source :machine` / `:source :route` mark from an actor
  spawn / route activation inside the rejected event, or the NEW-path
  `:source :flow` mark from a reentrant move) — those are absent from the
  pre-cascade snapshot yet legitimately survive. So OVERLAY the LIVE
  out-of-band entries (`:source` ≠ `:effect`) onto the pre-cascade base: this
  re-adds the during-event subsystem-lowered marks WITHOUT re-adding the
  in-band `:source :effect` marks (the only sourced kind that must unwind).
  Privacy posture stays fail-safe-toward-redaction: a kept mark over a value
  the rolled-back db no longer holds redacts nothing (harmless); a dropped one
  risks raw egress.

  `pre-reg` / `live-reg` are the `[:rf.runtime/elision]` registry maps (each
  may be nil). Returns the restored registry map (or nil when both axes empty)
  — the caller folds it onto `runtime-before` via `write-elision-slot`.
  Pure."
  [pre-reg live-reg]
  (let [;; Per axis slot (`:sensitive-declarations` / `:declarations`): start
        ;; from the pre-cascade entries, then overlay the LIVE OUT-OF-BAND
        ;; (`:source` ≠ `:effect`) entries. The pre-cascade base unwinds in-band
        ;; `:source :effect` and restores a dropped old-path `:source :flow`;
        ;; the overlay re-adds during-event subsystem-lowered survivors.
        restore-axis
        (fn [slot]
          (let [pre  (get pre-reg slot)
                live (get live-reg slot)
                out-of-band-live (reduce-kv
                                   (fn [m path decl]
                                     (if (= :effect (:source decl))
                                       m
                                       (assoc m path decl)))
                                   {}
                                   (or live {}))
                merged (merge (or pre {}) out-of-band-live)]
            (not-empty merged)))
        s (restore-axis :sensitive-declarations)
        l (restore-axis :declarations)]
    (cond-> nil
      s (assoc :sensitive-declarations s)
      l (assoc :declarations l))))

(defn ^:no-doc reconcile-runtime-db-effect
  "Reconcile the durable elision registry across a whole-value
  `:rf.db/runtime` partition commit.

  A framework-authority event may return a `:rf.db/runtime` effect whose
  value WHOLE-VALUE-REPLACES the runtime-db partition (Spec 002 §A runtime
  effect replaces only runtime-db; router commit decision #5). But the
  elision declaration registry at `[:rf.runtime/elision]` is a CROSS-CUTTING
  durable subsystem child mutated OUT-OF-BAND by `reg-flow` / the EP-0025
  classification effects / subsystem projection-relative declarations — NOT by
  the event returning the effect.
  A whole-value runtime-db effect that seeds an UNRELATED subsystem (e.g.
  `:rf.runtime/routing`) and does not itself carry `:rf.runtime/elision`
  would otherwise DROP the registry on commit, silently losing every
  flow-sourced / effect-sourced elision declaration — so the
  post-commit frame would emit raw values that were correctly redacted
  pre-commit (the durable privacy/trace contract breaks after any
  runtime-db-writing event).

  This preserves the existing `:rf.runtime/elision` sub-tree from
  `prev-runtime-db` into `new-runtime-db` UNLESS the committing effect
  ITSELF carries an `:rf.runtime/elision` key (an explicit full-frame
  install — epoch-restore / SSR-hydration — legitimately replaces the
  registry, so its decision is honoured verbatim, including an intentional
  clear). When the effect omits the key, the prior registry is carried
  forward; when there was no prior registry, nothing is added (a frame that
  never used elision stays clean — `runtime-db-effect-whole-value-commit`).

  Returns the reconciled runtime-db value. `new-runtime-db` is the
  whole-value effect payload; `prev-runtime-db` is the pre-commit
  runtime-db partition value (may be nil for a fresh frame)."
  [new-runtime-db prev-runtime-db]
  (let [new-runtime-db (or new-runtime-db {})]
    (if (contains? new-runtime-db :rf.runtime/elision)
      ;; The effect speaks about elision explicitly (full-frame install /
      ;; deliberate clear) — honour it verbatim.
      new-runtime-db
      ;; The effect is silent about elision — carry the prior registry
      ;; forward through the same prune logic so a frame that never used
      ;; elision is left without a stray nil sub-tree.
      (write-elision-slot new-runtime-db
                          (get prev-runtime-db :rf.runtime/elision)))))

(defn ^:no-doc swap-elision-slot!
  "Read-transform-write helper for the per-frame elision registry.

  Reads the registry at `[:rf.runtime/elision]` from `frame-id`'s
  runtime-db, applies `(f reg) -> new-reg`, and writes the result back
  through `write-elision-slot` (which removes a stranded
  `:rf.runtime/elision` when the slot clears). No-op when the frame's
  container does not exist. Returns nil.

  Internal helper; exposed (with `^:no-doc`) so the EP-0025 classification
  effects and `re-frame.flows.registry` share a single source of truth for the
  read-transform-write skeleton. Not part of the public API.

  EP-0001: writes through `frame/swap-runtime-db!` (the
  runtime-db partition of the one physical frame-state container) — the
  elision registry is durable framework state and lives in runtime-db."
  [frame-id f]
  (frame/swap-runtime-db! frame-id
                          (fn [old-runtime-db]
                            (let [old-runtime-db (or old-runtime-db {})]
                              (write-elision-slot
                                old-runtime-db
                                (f (get-in old-runtime-db [:rf.runtime/elision]))))))
  nil)

;; ---- EP-0025 commit-plane classification effects -------------------------
;;
;; EP-0025 §How it works: a `reg-event` handler may return the four
;; commit-plane classification effects alongside `:db` —
;;
;;   :sensitive       [[path] …]   ; classify path sensitive (redact at egress)
;;   :large           [[path] …]   ; classify path large    (size marker at egress)
;;   :clear-sensitive [[path] …]   ; un-classify sensitive
;;   :clear-large     [[path] …]   ; un-classify large
;;
;; They are applied WITH the `:db` write (a frame-state transform at the
;; commit point — `re-frame.router/commit-frame-effects!`), NOT as a later
;; post-commit `:fx`, so a path classified in the SAME event is redacted from
;; its first egress. They write into the per-frame elision registry's
;; `:sensitive-declarations` (sensitive) / `:declarations` (large) sub-maps —
;; the SAME slot `re-frame.flows.registry` (`:source :flow`) and the subsystem
;; projection-relative declarations populate — tagged
;; `:source :effect`, unioned with the other sources at egress-lookup time
;; (`elide-against-frame` reads both sub-maps verbatim, so no change to the wire
;; walker is needed). Classification is VALUE-INDEPENDENT (classify a path before
;; any value lands there) and read ONLY at egress.
;;
;; The two axes (`:sensitive` / `:large`) are INDEPENDENT — a
;; `:clear-sensitive` never touches `:declarations`, and `:clear-large` never
;; touches `:sensitive-declarations`. This mirrors the registration-layer
;; `:sensitive` / `:large` reserved in Spec-Schemas, applied here against an
;; arbitrary base runtime-db value (the router folds the result into the atomic
;; `:db`-write transition, not a live container swap).

(def ^:private classification-effect-keys
  "The four EP-0025 commit-plane classification effect keys a `reg-event`
  handler may return alongside `:db`. Two SET axes (`:sensitive` / `:large`)
  and their two CLEAR tails — ordinary set/unset symmetry per axis. These are
  COMMIT-PLANE effects applied with the `:db` write, NOT `do-fx` reserved
  fx-ids."
  #{:sensitive :large :clear-sensitive :clear-large})

(def ^:private classification-effect-axis
  "Map each classification effect key to the registry slot it writes and
  whether it SETS or CLEARS. `:slot` is the `[:rf.runtime/elision …]`
  declaration sub-map; `:set?` true adds `{:source :effect}` decls, false
  removes the named paths. The two axes are independent — a clear on one axis
  never touches the other's slot."
  {:sensitive       {:slot :sensitive-declarations :set? true}
   :large           {:slot :declarations           :set? true}
   :clear-sensitive {:slot :sensitive-declarations :set? false}
   :clear-large     {:slot :declarations           :set? false}})

(defn classification-effect?
  "True when `effects` carries at least one of the four EP-0025 commit-plane
  classification effect keys. A cheap pre-check so the common no-classification
  cascade allocates nothing on the commit path."
  [effects]
  (boolean (some #(contains? effects %) classification-effect-keys)))

(defn classification-effect-defect
  "PURE fail-loud-INPUT validator for the four EP-0025 classification effect
  payloads in `effects`. Returns the FIRST defect as a map
  `{:offending-key <k> :value <bad> :reason <string>}` — or nil when every
  present classification payload is well-shaped. Does NOT throw; the caller
  (the router's FINAL-effects boundary) emits
  `:rf.error/classification-effect-shape` in-band and aborts the event
  pre-commit, mirroring the `legacy-runtime-root` rejection (so the abort never
  escapes the drain).

  A payload is well-shaped iff its value is a vector (`[[path] …]`) AND every
  entry is a valid concrete `:rf/path` (EP-0012 — sequential and
  segment-validated). A non-EDN-identity segment makes
  `re-frame.path/normalize-concrete` throw `:rf.error/bad-path`; that is caught
  here and reported as a defect (the fail-closed `:rf/path` boundary surfaced
  as a classification-effect defect rather than a separate bad-path throw, so
  the rejection routes through ONE error id). Value-INDEPENDENT: validates the
  SHAPE of the declaration, never the runtime value at the path (which need not
  yet exist)."
  [effects]
  (letfn [(path-defect [k p]
            (cond
              (not (sequential? p))
              {:offending-key k
               :value         p
               :reason        (str "each path in the `" k "` classification effect "
                                   "must be a path vector; got " (pr-str p))}
              :else
              (try
                (path/normalize-concrete p)
                nil
                (catch #?(:clj Throwable :cljs :default) e
                  {:offending-key k
                   :value         p
                   :reason        (str "an invalid path in the `" k
                                       "` classification effect: "
                                       (or (ex-message e) (str e)))}))))
          (payload-defect [k payload]
            (if-not (vector? payload)
              {:offending-key k
               :value         payload
               :reason        (str "the `" k "` classification effect must be a vector "
                                   "of paths; got a " (pr-str (type payload)))}
              (some #(path-defect k %) payload)))]
    (some (fn [k]
            (when (contains? effects k)
              (payload-defect k (get effects k))))
          classification-effect-keys)))

(defn apply-classification-effects
  "Apply the four EP-0025 classification effects in `effects` onto a base
  `runtime-db` value, returning the new runtime-db with its
  `[:rf.runtime/elision …]` registry updated. PURE — operates on a value, not
  a live container, so the router can fold the result into the atomic `:db`
  commit transition (a partition-write alongside `:db`).

  Each SET key (`:sensitive` / `:large`) adds `{:source :effect}` declarations
  for its paths to the axis slot (`:sensitive-declarations` / `:declarations`)
  WITHOUT clobbering a foreign-source standing entry: the registry is
  one-entry-per-path-per-axis, so a SET on a path another owner already claims
  (`:source :flow` / `:machine` / `:route` / a subsystem declaration) leaves
  that owner standing rather than overwriting it to `:source :effect`
  (rf2-p4spo4). Were it to overwrite, the later source-scoped CLEAR would
  dissoc the entry and silently un-redact a path the other owner still
  classifies — a privacy fail-open. The egress read needs only presence, so a
  doubly-claimed path stays redacted and the SET is a no-op on it; only a free
  or already-effect-owned slot is (re)stamped `:source :effect`.
  Each CLEAR key (`:clear-sensitive` / `:clear-large`) removes ONLY the
  effect's OWN contribution to its paths from the axis slot. A clear is
  SOURCE-SCOPED: it dissocs a path only when the standing entry is
  `:source :effect` (the effect's own declaration); a path also claimed by
  another source (`:source :flow` from a `reg-flow` output, `:source :machine`,
  `:source :route`, or a subsystem projection declaration) is LEFT INTACT, so
  the clear can never silently un-redact a path another owner still classifies
  (a privacy fail-open). This mirrors `re-frame.flows.registry/drop-flow-sourced`,
  which likewise removes only its own `:source :flow` entries. The two axes are
  INDEPENDENT — clearing one never touches the other's slot — and clearing
  removes only the named paths (other-sourced entries for an unnamed path
  survive). Within one effect map, a SET on an axis applies before its own
  CLEAR (a handler returns at most one of each axis's set/clear), so if both an
  axis SET and that axis's CLEAR name the same path the CLEAR wins (the SET
  stamped it `:source :effect`, so the source-scoped clear removes the
  effect's own just-written entry). An empty/absent axis slot is pruned
  (dissoc'd) rather than left as `{}` (matching
  `write-elision-slot`). Assumes `effects` already passed
  `validate-classification-effects!` (paths are valid concrete `:rf/path`s);
  normalizes each to its canonical vector form here so the stored decl key
  matches the wire walker's `(vec path)` lookup.

  Pre-commit FAIL-OPEN posture: a path classified here is value-independent —
  it redacts whatever later occupies the path, and is a harmless no-op over an
  absent / differently-shaped value."
  [runtime-db effects]
  (let [base (or runtime-db {})
        reg  (get base :rf.runtime/elision)
        ;; Reduce the four effects (set axes first, clear axes second) so a
        ;; same-axis set+clear on one path resolves to cleared. Order within a
        ;; single map is fixed by the key sequence below.
        new-reg
        (reduce
          (fn [reg k]
            (if-not (contains? effects k)
              reg
              (let [{:keys [slot set?]} (classification-effect-axis k)
                    paths   (map #(vec (path/normalize-concrete %)) (get effects k))
                    cur     (get reg slot)
                    updated (if set?
                              ;; NON-CLOBBERING set (rf2-p4spo4): the registry is
                              ;; one-entry-per-path-per-axis, so an effect SET on a
                              ;; path ALSO claimed by another owner (`:source :flow`
                              ;; from a reg-flow output, `:source :machine`,
                              ;; `:source :route`, or a subsystem declaration) must
                              ;; NOT overwrite that owner's provenance. If it did,
                              ;; the later SOURCE-SCOPED clear below would see
                              ;; `:source :effect`, dissoc the entry, and silently
                              ;; un-redact a path the other owner still classifies —
                              ;; a privacy fail-open. The egress read needs only
                              ;; PRESENCE, so leaving the foreign owner standing
                              ;; keeps the path redacted while preserving the source
                              ;; the clear must honour. Only a free or already-
                              ;; effect-owned slot is (re)stamped `:source :effect`.
                              (reduce (fn [m p]
                                        (let [src (:source (get m p))]
                                          (if (or (nil? src) (= :effect src))
                                            (assoc m p {:source :effect})
                                            m)))
                                      (or cur {}) paths)
                              ;; SOURCE-SCOPED clear (rf2-34jrb6): remove a path
                              ;; only when ITS standing entry is the effect's own
                              ;; (`:source :effect`). A path also claimed by a
                              ;; flow / machine / route / subsystem source is
                              ;; left intact, so a clear never un-redacts a path
                              ;; another owner still classifies (privacy fail-open).
                              (reduce (fn [m p]
                                        (if (= :effect (:source (get m p)))
                                          (dissoc m p)
                                          m))
                                      (or cur {})
                                      paths))]
                (if (seq updated)
                  (assoc reg slot updated)
                  (dissoc reg slot)))))
          (or reg {})
          ;; SET axes before CLEAR axes — a same-event set+clear of one path on
          ;; an axis resolves to cleared (the clear is the later write).
          [:sensitive :large :clear-sensitive :clear-large])]
    (write-elision-slot base new-reg)))

(defn declarations
  "Return the `:large` `:app-db` declarations for `frame-id` (the union of
  every source that populates the slot — EP-0025 commit-plane effects under
  `:source :effect`, `reg-flow` outputs under `:source :flow`, subsystem
  projection-relative declarations).
  EP-0002 — the zero-arity ambient form resolves the frame through the
  carried-invariant scope chain (`frame/require-current-frame!`); under no
  established scope it raises `:rf.error/no-frame-context` rather than
  reading a synthesised `:rf/default` registry."
  ([] (declarations (frame/require-current-frame!
                      :elision-declarations
                      {:where 're-frame.elision/declarations})))
  ([frame-id]
   (or (get (registry-of frame-id) :declarations) {})))

(defn sensitive-declarations
  "Return the `:sensitive` `:app-db` declarations for `frame-id` (the union
  of every source that populates the slot — EP-0025 commit-plane effects
  under `:source :effect`, `reg-flow` outputs under `:source :flow`,
  subsystem projection-relative declarations).
  EP-0002 — the zero-arity ambient form resolves the frame through the
  carried-invariant scope chain (`frame/require-current-frame!`); under no
  established scope it raises `:rf.error/no-frame-context` rather than
  reading a synthesised `:rf/default` registry."
  ([] (sensitive-declarations (frame/require-current-frame!
                                :elision-sensitive-declarations
                                {:where 're-frame.elision/sensitive-declarations})))
  ([frame-id]
   (or (get (registry-of frame-id) :sensitive-declarations) {})))

;; EP-0015 §8: the schema-attached `{:sensitive? true}` /
;; `{:large? true}` slot props are NOT a route into this app-db
;; egress registry. Durable app-db classification rides the EP-0025
;; commit-plane classification effects — a `reg-event` returns `:sensitive` /
;; `:large` alongside `:db`, written `:source :effect` at the commit point.
;; Schemas describe shape, not durable app-db egress policy. Schema
;; `:sensitive?` drives
;; schema-validation-failure-trace redaction (`re-frame.schemas`), and
;; machine `:data-schema` props classify machine `:data` (EP-0005) —
;; both consult the schema directly, not this registry.

(defn clear-warning-cache!
  []
  (reset! warned-unschema'd #{})
  nil)

(defn ^:no-doc pr-str-bytes
  "Return a byte-count for a value's printed representation. Used by the
  `:rf.size/large-elided` marker payload. `^:no-doc` public so
  `re-frame.classification` reuses it rather than re-inlining the same `pr-str`
  byte-count a second time."
  [v]
  #?(:clj  (count (.getBytes ^String (pr-str v) "UTF-8"))
     :cljs (count (pr-str v))))

(defn ^:no-doc value-type
  "Coarse value-type tag for the `:rf.size/large-elided` marker payload.
  `^:no-doc` public so `re-frame.classification` reuses it rather than
  re-inlining the same closed `cond` a second time."
  [v]
  (cond
    (map? v)    :map
    (vector? v) :vector
    (set? v)    :set
    (string? v) :string
    :else       :scalar))

(defn- sha256-hex
  [v]
  #?(:clj
     (let [bytes (.getBytes ^String (pr-str v) "UTF-8")
           md    (doto (java.security.MessageDigest/getInstance "SHA-256")
                   (.update bytes))]
       (str "sha256:"
            (format "%064x" (java.math.BigInteger. 1 (.digest md)))))
     :cljs
     nil))

(defn- handle-of
  [path as-of-epoch]
  (if as-of-epoch
    [:rf.elision/at path :as-of-epoch as-of-epoch]
    [:rf.elision/at path]))

(defn ^:no-doc ->marker
  "Build the `:rf.size/large-elided` marker map for value `v` at `path`.
  `^:no-doc` public so `re-frame.classification/large-marker` builds the marker
  here (with `{:reason :classification}`) rather than re-inlining the shape a
  second time."
  [v path {:keys [hint as-of-epoch include-digests? reason]}]
  (let [body (cond-> {:path   (vec path)
                      :bytes  (pr-str-bytes v)
                      :type   (value-type v)
                      ;; Carry the declaration's source provenance in
                      ;; `:reason` (EP-0025: the canonical source is
                      ;; `:source :effect`, the commit-plane classification
                      ;; effect — defaulted here when the decl omits an
                      ;; explicit source).
                      :reason (or reason :effect)
                      :hint   hint
                      :handle (handle-of (vec path) as-of-epoch)}
               include-digests? (assoc :digest (sha256-hex v)))]
    {:rf.size/large-elided body}))

(defn- marker-opts
  "The `->marker` option map for a declared-`:large` node — derived from the
  matched `large-decl` (its `:hint` / `:source`) and the walk `ctx` (its
  `:as-of-epoch` / `:include-digests?`). The 4-key option map the path-based
  wire walker (`walk-decider`) passes to `->marker`. The `:source` / `:hint`
  stay genuinely multi-valued across the flow / effect / schema declaration
  sources, so the lookup is real even with a single caller."
  [large-decl ctx]
  {:hint             (:hint large-decl)
   :reason           (:source large-decl)
   :as-of-epoch      (:as-of-epoch ctx)
   :include-digests? (:include-digests? ctx)})

(defn- warn-large-unschema'd!
  [frame-id path bytes]
  (when interop/debug-enabled?
    (let [k [frame-id (vec path)]]
      (when-not (contains? @warned-unschema'd k)
        (swap! warned-unschema'd conj k)
        (trace/emit! :warning :rf.warning/large-value-unschema'd
                     {:frame    frame-id
                      :path     (vec path)
                      :bytes    bytes
                      :hint     "Classify this path large by returning `:large [[...]]` from the event that writes it (EP-0025 commit-plane classification effect, alongside `:db`)."
                      :recovery :no-recovery})))))

;; ---- collection-coordinate declaration matching --------------------------
;;
;; Schema-derived declarations are INDEX-FREE: the schema walker descends
;; positional/keyed containers (`:vector` / `:sequential` / `:set` /
;; `:map-of`) at the SAME base-path (walker.cljc ~L145), because a vector
;; index or a `:map-of` key is not a declarable app-db slot. So a schema
;; `[:items] [:vector [:map [:token {:sensitive? true} :string]]]` declares
;; the index-free path `[:items :token]`, while the runtime value lives at
;; the INDEXED path `[:items 0 :token]` (and a `:map-of` value lives at the
;; KEYED path `[:by-id "a" :secret]` against decl `[:by-id :secret]`).
;;
;; The wire-elision walker walks a RUNTIME value, so it sees the indexed /
;; keyed paths. To match the index-free declarations without re-walking the
;; schema (the walker doesn't carry it), we thread a SET of candidate
;; declaration-coordinate paths alongside the concrete runtime path:
;;
;;   - Descending a VECTOR / SEQ element at index `i`: the index is
;;     UNAMBIGUOUSLY a collection coordinate (never a named slot), so each
;;     candidate forks into the INDEX-FREE interpretation `c` (unchanged —
;;     matches schema decls that descend positional containers at the same
;;     base-path) AND the LITERAL-INDEX interpretation `(conj c i)` (matches
;;     a declaration that itself pins a concrete index, e.g. `[:tokens 0]`),
;;     the latter pruned by `decl-prefixes` (see `fork-index-paths`).
;;     Riding an index never floats a declaration past a NAMED slot, so the
;;     index-free interpretation is kept for ALL candidates (incl. the empty
;;     seed) without the map-key skip's position guard. A SET element has no
;;     stable index ⇒ index-free pass-through only.
;;   - Descending a MAP key `k`: a runtime map is structurally ambiguous —
;;     `k` may be a NAMED `:map` slot (a real segment ⇒ `(conj c k)`) OR a
;;     `:map-of` key (a collection coordinate ⇒ `c` unchanged). We can't tell
;;     which from the value alone, so we fork each candidate into BOTH
;;     interpretations and let the declaration table disambiguate at the leaf
;;     (only the interpretation that actually matches a declared path fires).
;;     The `:map-of`-key (skip) fork is POSITION-PRECISE: only a NON-EMPTY
;;     candidate — one that has already consumed ≥1 declared segment — may
;;     skip a key. The empty seed `[]` advances only via the named fork (see
;;     `fork-decl-paths`), so a declaration cannot FLOAT past leading named
;;     map slots and falsely match the same key-sequence at a deeper,
;;     non-declared position.
;;
;; The fork would grow combinatorially with map depth, so we PRUNE: a
;; candidate that is not a prefix of ANY declared path can never match and is
;; dropped. `decl-prefixes` (all prefixes of every sensitive ∪ large decl
;; path) bounds the live candidate set to the declaration cardinality — tiny
;; in practice, and empty (so zero overhead) when nothing is declared.
;;
;; Precision: the runtime path `[:by-id "a" :secret]` matches decl
;; `[:by-id :secret]` only because `:secret` is a real map slot under the
;; `:map-of` value (`[:by-id]` is a non-empty partial match, so it legally
;; skips the map-of key `"a"`); a sibling NON-sensitive leaf
;; `[:by-id "a" :other]` never matches (its decl-coordinate candidates
;; `[:by-id :other]` / `[:by-id "a" :other]` aren't declared). And decl
;; `[:auth :password]` does NOT match the deeper `[:tags :some-other-slot
;; :auth :password]`: the `[]` seed cannot skip the leading `:tags` /
;; `:some-other-slot` named slots, so it never reaches the same-named `:auth`
;; at that non-declared position. The concrete runtime `path` still drives
;; marker `:path` / `:handle` and the unschema'd-large warning, so an agent's
;; follow-up `get-path` lands on the exact indexed location.

(declare walk marker?)

;; ---- shared map/vec/set/seq path-walk skeleton ---------------------------
;;
;; Both the schema-first wire walker (`walk` / `walk-decider`) and the
;; ad-hoc-paths classification walker (`re-frame.classification/walk-with-paths`)
;; descend the SAME node domain — map → reduce-kv, vector|seq → positional
;; reduce with an integer index, set → element-wise pass-through — and differ
;; ONLY in the per-node DECIDER and how each forks its traversal STATE through
;; a descent. `walk-tree` is that divergence-free kernel, hoisted here
;; (classification already requires this ns, so no new coupling): one skeleton,
;; the two distinct deciders stay at their call sites.
;;
;; The skeleton is parameterized by an opaque per-walker `state` (the wire
;; walker threads `[path decl-paths]`; classification threads a bare `path`) and
;; four functions:
;;   :decide  (state v) -> a replacement value, OR the `::recur` sentinel to
;;            descend structurally. A matched node is substituted wholesale and
;;            NOT descended (sensitive → sentinel; large → marker).
;;   :map-key (state k)  -> the descended state for map key `k`.
;;   :index   (state i)  -> the descended state for positional index `i`.
;;   :leaf    (state v)  -> the `:else` (scalar) result. The wire walker runs
;;            its runtime-size-threshold warning side-effect here and returns
;;            `v`; classification returns `v` verbatim.
;; Set elements carry `state` UNCHANGED in BOTH walkers (a set element is a
;; nameless collection coordinate — neither the path nor the decl-paths
;; advance), so that arm is fixed in the skeleton, not parameterized.
;;
;; Seq is normalised to a persistent vector (via the transient accumulator) —
;; both prior walkers did this; the skeleton preserves it.

(def ^:no-doc walk-recur
  "Distinguished `walk-tree` `:decide` sentinel meaning 'descend structurally'
  (vs return a replacement). `^:no-doc` public so the sibling
  `re-frame.classification` decider returns the SAME sentinel `walk-tree` tests
  with `identical?` — a replacement value can never collide with this namespaced
  keyword."
  ::recur)

(defn ^:no-doc walk-tree
  "The shared map/vec/set/seq path-walk skeleton (see the section comment).
  `state` is the per-walker traversal state; `decider` is the
  `{:decide :map-key :index :leaf}` map. `^:no-doc` public so the sibling
  `re-frame.classification` walker shares this one skeleton rather than
  re-inlining it."
  [v state {:keys [decide map-key index leaf] :as decider}]
  (let [r (decide state v)]
    (if-not (identical? r walk-recur)
      r
      (cond
        (map? v)
        (reduce-kv (fn [acc k vv]
                     (assoc acc k (walk-tree vv (map-key state k) decider)))
                   (empty v) v)

        (or (vector? v) (seq? v))
        (let [idx (volatile! -1)]
          (persistent!
            (reduce (fn [acc vv]
                      (conj! acc (walk-tree vv (index state (vswap! idx inc)) decider)))
                    (transient [])
                    v)))

        (set? v)
        (into #{} (map #(walk-tree % state decider)) v)

        :else
        (leaf state v)))))

(defn- prefixes
  "All non-empty prefixes (including the full path) of `path`, as a set.
  Used to seed `decl-prefixes` so the candidate decl-path fork can be
  pruned to paths that could still reach a declaration."
  [path]
  (into #{} (map #(subvec path 0 %)) (range 1 (inc (count path)))))

(defn- decl-prefix-set
  "Build the set of every prefix of every declared path (sensitive ∪
  large). A candidate declaration-coordinate path is kept alive during the
  walk only while it is a member of this set — anything outside it can
  never reach a declaration, so dropping it is matching-safe and keeps the
  forked candidate set bounded by the declaration cardinality."
  [ctx]
  (into #{}
        (mapcat prefixes)
        (concat (keys (:sensitive ctx)) (keys (:large ctx)))))

(defn- fork-decl-paths
  "Descend the candidate declaration-coordinate set through a MAP key `k`.
  Each candidate forks into the NAMED-slot interpretation `(conj c k)` and
  the `:map-of`-key interpretation `c` (key is a collection coordinate, no
  segment). Both are retained only when they remain a prefix of some
  declared path (`decl-prefixes`), bounding the set.

  POSITION-PRECISE skip: the `:map-of`-key
  interpretation — keeping `c` unchanged so the key is treated as a
  collection coordinate — is only legitimate for a candidate that has
  ALREADY consumed at least one declared segment (`(seq c)`). A non-empty
  `c` is a partial declaration-prefix match in progress, so a map key at
  that position can plausibly be a `:map-of` value key inside that
  declared subtree (e.g. `[:by-id]` skips the map-of key `\"a\"` so the
  subsequent `:secret` forms `[:by-id :secret]`). The EMPTY seed `[]` has
  matched nothing yet: letting it skip a key would let a declaration
  FLOAT past arbitrary leading NAMED map slots — matching decl
  `[:auth :password]` against the deeper `[:tags :some-other-slot :auth
  :password]` where that `:auth`/`:password` is a DIFFERENT position, not
  the declared one. So `[]` advances only via the named-slot fork; it
  never survives as a free-floating skip. This keeps the headline goal
  green (`[:items 0 :token]` matches `[:items :token]`; `[:by-id \"a\"
  :secret]` matches `[:by-id :secret]`) while sealing the over-redaction
  of same-named slots at non-declared positions."
  [decl-paths k decl-prefixes]
  (persistent!
    (reduce (fn [acc c]
              (let [named (conj c k)
                    acc   (if (contains? decl-prefixes named) (conj! acc named) acc)]
                ;; `:map-of`-key interpretation: `c` stays a live candidate
                ;; ONLY when it is a non-empty partial match — see the
                ;; position-precise rationale in the docstring. `c` is
                ;; already a known decl-prefix (it survived the round that
                ;; produced it), so retaining it keeps the set bounded by
                ;; `decl-prefixes`.
                (if (seq c) (conj! acc c) acc)))
            (transient #{})
            decl-paths)))

(defn- fork-index-paths
  "Descend the candidate declaration-coordinate set through a VECTOR / SEQ
  element at integer index `i`.

  An element index is UNAMBIGUOUSLY a collection coordinate — unlike a map
  key, it can never be a named app-db slot. So a vector/seq descent forks
  each candidate into:

    - the INDEX-FREE interpretation `c` (unchanged): a schema-derived decl
      descends positional containers at the same base-path, so the
      index-free `[:items :token]` matches the runtime `[:items 0 :token]`.
      Retained for ALL candidates (including the empty seed `[]`): riding
      an index through never floats a declaration past a NAMED slot, so it
      cannot over-redact the way the map-key skip could.

    - the LITERAL-INDEX interpretation `(conj c i)`: a declaration MAY
      itself carry a concrete index (`[:tokens 0]`, declared directly
      against the indexed runtime position rather than schema-derived).
      Retained only while it remains a prefix of some declared path
      (`decl-prefixes`), so it is dropped immediately when no decl pins
      that index — keeping the set bounded.

  Both interpretations are matching-safe: the index-free one matches
  schema decls, the literal-index one matches directly-declared indexed
  paths, and only the interpretation actually present in the declaration
  table fires at the leaf."
  [decl-paths i decl-prefixes]
  (persistent!
    (reduce (fn [acc c]
              (let [indexed (conj c i)
                    acc     (if (contains? decl-prefixes indexed)
                              (conj! acc indexed) acc)]
                ;; Index-free interpretation: pass `c` through unchanged.
                (conj! acc c)))
            (transient #{})
            decl-paths)))

(defn- decl-match
  "Test the candidate declaration-coordinate set against a declaration
  table `tbl` (`:sensitive` or `:large`). Returns the matched declaration
  value (truthy) for the FIRST candidate present in `tbl`, or nil. For the
  sensitive table any present entry suffices (membership); for the large
  table the entry is the marker-hint declaration map."
  [decl-paths tbl]
  (some #(get tbl %) decl-paths))

(defn- decl-sensitive?
  [decl-paths sensitive-tbl]
  (boolean (some #(contains? sensitive-tbl %) decl-paths)))

(defn- strict-prefix?
  "True when declaration-coordinate path `a` is a STRICT prefix of `b` — `b`
  is `a` extended by at least one further segment. Both are full declaration
  coordinates in the SAME space (`:large` and `:sensitive` decls share it),
  so a strict-prefix test answers \"is `b` a descendant slot under `a`?\"."
  [a b]
  (let [na (count a)]
    (and (> (count b) na)
         (= a (subvec b 0 na)))))

(defn- large-keys-shadowing-sensitive
  "The subset of `:large` declaration keys that have a `:sensitive` declaration
  strictly BELOW them — a `:large`-marked subtree that contains a `:sensitive`
  descendant. Per Spec 015 §No-propagation (L338) + EP-0025 §Egress-rules
  (a normative MUST): such a subtree REDACTS rather than showing a size preview
  (the `:rf.size/large-elided` marker leaks `:bytes` / `:type` and — off-box,
  digests on — a SHA-256 digest computed over a subtree that contains the
  secret, a brute-force oracle). The walker uses this set to look ahead at a
  large-matched node: if the matched large coordinate shadows a sensitive
  descendant, sensitive DOMINATES and the walker descends-and-redacts instead
  of emitting the marker. Pure function of the two declaration tables; computed
  once per walk and carried in `ctx`."
  [large-tbl sensitive-tbl]
  (let [s-keys (keys sensitive-tbl)]
    (into #{}
          (filter (fn [lk] (some #(strict-prefix? lk %) s-keys)))
          (keys large-tbl))))

;; The wire walker's traversal state is the pair `[path decl-paths]` — the
;; concrete runtime path (drives marker `:path` / `:handle` + the unschema'd-
;; large warning) and the forked candidate declaration-coordinate set (drives
;; the per-node sensitive / large match). Both fork on descent: a map key
;; advances the path by the key and forks decl-paths via `fork-decl-paths`; a
;; positional index advances the path by the index and forks via
;; `fork-index-paths`. Set elements carry the state unchanged (handled by the
;; shared `walk-tree` skeleton).

(defn- walk-decider
  "Build the `walk-tree` decider for the schema-first wire walk against `ctx`.
  The per-node decision (sensitive → sentinel, declared-large → idempotent
  marker, else descend), the map-key / index state forks, and the scalar leaf
  (the runtime-size-threshold warning side-effect) — the elision-specific arms
  the shared skeleton parameterises over."
  [ctx]
  (let [large-tbl     (:large ctx)
        sensitive-tbl (:sensitive ctx)
        shadow-set    (:large-shadows-s ctx)
        decl-prefixes (:decl-prefixes ctx)
        include-lg?   (:include-large? ctx)
        include-s?    (:include-sensitive? ctx)
        threshold     (:threshold-bytes ctx)
        frame-id      (:frame-id ctx)]
    {:decide
     (fn [[path decl-paths] v]
       (let [large-decl (decl-match decl-paths large-tbl)
             sensitive? (decl-sensitive? decl-paths sensitive-tbl)]
         (cond
           (and sensitive? (not include-s?))
           privacy/redacted-sentinel

           ;; NESTED-AXIS SUPPRESSION (rf2-izlr7f): a `:large`-matched node
           ;; whose matched large coordinate shadows a `:sensitive` descendant
           ;; must NOT emit the size marker — sensitive DOMINATES. Fall through
           ;; to `walk-recur` so the walker descends and redacts the sensitive
           ;; descendant in place (Spec 015 §No-propagation L338, a normative
           ;; MUST). Emitting the marker here would leak `:bytes` / `:type` and
           ;; (off-box, digests on) a SHA-256 digest over a subtree that
           ;; contains the secret — a brute-force oracle. Gated on
           ;; `(not include-s?)`: the suppression exists to let the sensitive
           ;; descendant redact on descent, so it only applies when sensitive
           ;; redaction is in force (a caller that opted OUT of sensitive
           ;; redaction has waived it; the large axis is then orthogonal). Only
           ;; fires when a sensitive descendant actually exists under a large
           ;; match, so the ordinary same-path / no-nesting large case is
           ;; unchanged.
           (and large-decl (not include-lg?) (not include-s?)
                (seq shadow-set)
                (some #(contains? shadow-set %) decl-paths))
           walk-recur

           (and large-decl (not include-lg?))
           (if (marker? v)
             ;; Idempotence under double-projection: a value at a `:large?`-
             ;; declared path that already carries the `:rf.size/large-elided`
             ;; marker shape is passed through unchanged. A forwarder pipeline
             ;; that accidentally double-projects (middleware composition,
             ;; tool-then-watcher fan-out) MUST NOT re-mark the marker — the
             ;; second pass's `:bytes` would otherwise reflect the printed
             ;; length of the prior marker, not the original payload. Mirrors
             ;; the sensitive-case idempotence (the `:rf/redacted` scalar
             ;; sentinel is non-matchable so the walker descends into nothing
             ;; on a re-projection pass).
             v
             (->marker v path (marker-opts large-decl ctx)))

           :else
           walk-recur)))

     :map-key
     (fn [[path decl-paths] k]
       [(conj path k) (fork-decl-paths decl-paths k decl-prefixes)])

     :index
     (fn [[path decl-paths] i]
       [(conj path i) (fork-index-paths decl-paths i decl-prefixes)])

     :leaf
     (fn [[path decl-paths] v]
       (let [large-decl (decl-match decl-paths large-tbl)
             sensitive? (decl-sensitive? decl-paths sensitive-tbl)]
         (when (and (string? v)
                    (not large-decl)
                    (not sensitive?))
           ;; A threshold of 0 disables runtime auto-detect entirely —
           ;; no `pr-str-bytes` walk, no warning. Per API.md §Configure
           ;; keys (`:elision` — "0 disables runtime auto-detect").
           (when (pos? threshold)
             (let [bytes (pr-str-bytes v)]
               (when (> bytes threshold)
                 (warn-large-unschema'd! frame-id path bytes))))))
       v)}))

(defn- walk
  [v path decl-paths ctx]
  (walk-tree v [(vec path) decl-paths] (walk-decider ctx)))

(defn- elide-against-frame
  "Inner walk for `elide-wire-value` against a KNOWN carried frame.
  `frame-id` is the resolved frame whose elision registry supplies the
  sensitive / large declaration tables. Pure walk — no frame resolution
  happens here (the caller has already resolved + validated the stamp)."
  [v opts frame-id]
  (let [reg       (registry-of frame-id)
        ;; Precedence (API.md L507): explicit opt > configured > default.
        threshold (let [opt (:rf.size/threshold-bytes opts)]
                    (if (some? opt) opt (configured-threshold-bytes)))
        large     (or (:declarations reg) {})
        sensitive (or (:sensitive-declarations reg) {})
        ctx       {:frame-id           frame-id
                   :large              large
                   :sensitive          sensitive
                   ;; The subset of `:large` keys that shadow a `:sensitive`
                   ;; descendant — drives the nested-axis suppression look-ahead
                   ;; (Spec 015 §No-propagation L338, a normative MUST). Empty in
                   ;; the common case (no nested classification).
                   :large-shadows-s    (large-keys-shadowing-sensitive large sensitive)
                   ;; Prefix set of every declared path — bounds the forked
                   ;; candidate decl-path set as the walker descends maps.
                   ;; Empty when nothing is declared ⇒ the fork
                   ;; prunes to {} immediately and the walker is identity.
                   :decl-prefixes      (decl-prefix-set {:large large :sensitive sensitive})
                   :include-large?     (true? (:rf.size/include-large? opts))
                   :include-sensitive? (true? (:rf.size/include-sensitive? opts))
                   :include-digests?   (true? (:rf.size/include-digests? opts))
                   :threshold-bytes    threshold
                   :as-of-epoch        (:as-of-epoch opts)}
        seed-path (vec (:path opts))]
    ;; Seed the candidate declaration-coordinate set with the offset path
    ;; (`#{[]}` for the common no-offset call). The walk forks/prunes it
    ;; against `:decl-prefixes` on every map-key descent.
    (walk v seed-path #{seed-path} ctx)))

(defn elide-wire-value
  "Walk `v` and substitute the frame's declared sensitive or large paths for
  wire egress (the durable declarations live in `[:rf.runtime/elision …]`,
  written by the EP-0025 commit-plane classification effects under
  `:source :effect`, plus `reg-flow` outputs and subsystem
  projection-relative declarations — EP-0015 §8). Sensitive wins over large
  when both declarations match.

  EP-0002 — the wire-egress frame resolves from the CARRIED
  stamp: the explicit `:frame` opt (*override*) wins, else the in-effect
  carried-invariant scope (`frame/resolve-current-frame` — a `with-frame`
  binding or an enclosing frame-provider). There is NO `:rf/default` floor:
  a frame's elision policy may be applied only when that frame is KNOWN
  (Spec 002 §Frame target resolution; EP-0002 §Trace, Projection, And
  Elision / §Privacy And Egress).

  A resolved frame-id is not enough — it must RESOLVE to a live frame.
  An explicit `:frame` opt (or a stale carried scope) may name a frame that
  was never registered or has since been destroyed. EP-0015 issue 1: an
  unresolvable frame's per-frame elision registry is unreachable
  (`registry-of` returns nil), so feeding it through the policy walk would
  be an EMPTY-policy identity transform — shipping every value verbatim under
  NO policy, a silent leak. An unknown / destroyed frame therefore FAILS
  CLOSED here too, identically to the frameless case: it is validated against
  the live registry (`frame/frame`) before being treated as policy-bearing.
  Spec 015
  §Direct reads and fail-closed frame resolution: an unresolved frame must
  fail closed — it must not fall through to a permissive walk, and never
  synthesizes `:rf/default`.

  Frameless / unresolvable-frame egress FAILS CLOSED. When no LIVE frame is
  carried, the per-frame elision registry is unreachable, so a permissive
  identity walk would ship every value verbatim under NO policy. Rather than
  borrow another frame's classification, the whole value is conservatively redacted
  to the `:rf/redacted` sentinel. `:rf.size/include-sensitive? true` is the
  deliberate opt-out: a caller that has explicitly waived sensitive
  redaction gets the value walked with an empty policy (the identity
  transform), so an inspector that genuinely wants the raw, policy-free
  value asks for it on purpose."
  ([v] (elide-wire-value v nil))
  ([v opts]
   (let [;; rf2-mtzv5m — route-sub egress re-seeding. A direct-read off-box
         ;; surface (Pair MCP read-sub / list-subscriptions :include-values /
         ;; snapshot :sub-cache / Xray) walks a route read sub's BARE value but
         ;; the route's classification is re-rooted ABSOLUTE under
         ;; `[:rf.runtime/routing :current …]` in the registry. When the caller
         ;; names the sub via `:query-v`, consult the routing-owned seed table
         ;; (late-bound — core stays decoupled from routing, rf2-k682) and
         ;; OVERLAY the slice's runtime-db storage position as `:path` so the
         ;; candidate declaration-coordinate set starts where the re-rooted
         ;; decls live (mirroring the SSR `project-routing-egress` offset). A
         ;; non-route sub-id resolves nil and `opts` is untouched.
         opts       (if-let [qv (:query-v opts)]
                      (if-let [seed-fn (late-bind/get-fn :routing/route-sub-egress-path)]
                        (if-let [seed (seed-fn (when (sequential? qv) (first qv)))]
                          (assoc opts :path seed)
                          opts)
                        opts)
                      opts)
         frame-id   (or (:frame opts) (frame/resolve-current-frame))
         ;; A frame-id alone is not policy-bearing — it must resolve to a
         ;; LIVE frame. `frame/frame` returns nil for an unknown /
         ;; never-registered / destroyed id, so an explicit `:frame` opt or
         ;; a stale carried scope that names a dead frame is treated exactly
         ;; like the frameless case below (fail closed).
         live-frame? (and (some? frame-id) (some? (frame/frame frame-id)))]
     (cond
       ;; Known + LIVE carried frame ⇒ apply that frame's elision policy.
       live-frame?
       (elide-against-frame v opts frame-id)

       ;; Frameless / unresolvable-frame + the caller waived sensitive
       ;; redaction ⇒ identity walk against an empty (no-frame) policy. The
       ;; walker is the identity transform when no declarations are
       ;; reachable.
       (true? (:rf.size/include-sensitive? opts))
       (elide-against-frame v opts ::no-frame)

       ;; Frameless / unresolvable-frame egress, no opt-out ⇒ fail closed:
       ;; no policy is available, so conservatively redact the whole value
       ;; rather than borrow another frame's classification or pass it through under
       ;; an empty policy.
       :else
       privacy/redacted-sentinel))))

(defn marker?
  [v]
  (and (map? v) (contains? v :rf.size/large-elided)))

;; ---------------------------------------------------------------------------
;; EP-0025: the derived-tree VALUE-MATCH redaction engine is REMOVED.
;;
;; `collect-sensitive-values` / `sensitive-value-set` / `redact-derived-values`
;; (+ the large-axis dual `large-value-marker-map` / `redact-derived-large-
;; values`, the `redact-matching-*` walkers, the non-unique-secret guard, and
;; the composed `redact-derived-slots`) were the value-based dual of the
;; path-based `elide-wire-value` above: they collected the live values at a
;; frame's classified `:sensitive` / `:large` app-db paths and substituted any
;; EQUAL leaf in a derived tree (rendered hiccup, `:effective-args`, a snapshot
;; body). EP-0025 §"What is removed" disclaims this as "propagation / taint by
;; another name" — a universal "same value redacted everywhere" backstop that a
;; HYGIENE helper does not earn. Classification no longer propagates: you redact
;; exactly the app-db PATHS you classify (`elide-wire-value`), and a sensitive
;; value re-keyed into a derived tree ships raw unless its app-db PATH is
;; classified. This is INTENDED fail-open — hygiene, not a guarantee.
;;
;; `project-egress`'s `:rf.observe/derived-tree` record kind (B4) no longer
;; delegates here; it now path-walks the tree slot(s) through `elide-wire-value`
;; (a no-op for re-keyed values — the documented fail-open posture).
;; ---------------------------------------------------------------------------

;; EP-0015 §8: there is no `:elision/populate-from-schemas!` hook — the
;; commit-plane classification effects own the app-db egress registry;
;; schemas describe shape only.
(late-bind/set-fn! :elision/sensitive-declarations sensitive-declarations)
(late-bind/set-fn! :elision/clear-warning-cache! clear-warning-cache!)
