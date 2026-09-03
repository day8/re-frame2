(ns re-frame.machines.classification
  "Projection-relative classification for durable machine `:data`.

  A machine definition declares `:sensitive` and `:large` paths relative to
  one actor snapshot. The runtime lowers them to absolute runtime-db paths for
  each singleton or spawned actor, and removes the machine-owned declarations
  when that actor is destroyed:

  ```clojure
  (rf/reg-machine :checkout/payment
    {:sensitive [[:data :payment :token]]
     :large     [[:data :payment :receipt-pdf]]
     :schemas {:data …}, :initial …, :states …})
  ```

  Lowered declarations use `:source :machine` in the frame's elision registry,
  so existing trace, SSR, and projection readers need no machine-specific path.
  A malformed declaration fails at registration. Omitting classification leaves
  the data unclassified."
  (:require [re-frame.elision :as rf.elision]
            [re-frame.error :as rf.error]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.path :as rf.path]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private classification-axis-keys
  "The projection-relative declaration axes accepted by `reg-machine`."
  #{:sensitive :large})

(defn- declaration-defect
  "PURE fail-loud-INPUT validator for one machine classification axis
  payload. Returns a human reason string for the FIRST defect, or nil when
  the payload is well-shaped. A payload is well-shaped iff it is a vector of
  valid concrete `:rf/path` vectors, the same shape accepted by the four
  commit-plane classification effects
  (`re-frame.elision/classification-effect-defect`).
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
                (rf.path/normalize-concrete p)
                nil
                (catch #?(:clj Throwable :cljs :default) e
                  (str "an invalid path in the machine `" k "` classification "
                       "declaration: " (or (ex-message e) (str e)))))))
          payload)))

(defn validate-machine-classification!
  "Fail LOUD at the `reg-machine` boundary when a machine spec's
  projection-relative `:sensitive` / `:large` declaration is malformed (a
  non-vector axis, a non-path entry, an invalid `:rf/path` segment) — the
  `:rf.error/invalid-machine-classification` at registration like every other
  registration-shape fault. A spec that declares NEITHER axis is a clean
  no-op (the common fail-open case — an undeclared machine ships its `:data`
  raw). Pure / side-effect-free except the throw."
  [machine-id spec]
  (doseq [k classification-axis-keys]
    (when (contains? spec k)
      (when-let [reason (declaration-defect k (get spec k))]
        (rf.error/throw-error!
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
  (let [norm (fn [k] (into [] (map #(vec (rf.path/normalize-concrete %))) (get spec k)))
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
  (into (rf.machines.paths/snapshot-path actor-id) path))

(defn- machine-owner
  "The multi-owner elision-registry owner identity a machine actor's lowered
  `:data` classification claims under. The `:actor-id` is carried so an actor's
  spawn / destroy scopes to THAT instance — removing one actor's claims never
  disturbs a sibling actor's or another source's claim on the same absolute
  path."
  [actor-id]
  {:source :machine :actor-id actor-id})

(defn- apply-declarations
  "Pure registry transform: ADD (`add?` true) or DROP (`add?` false) the
  absolute snapshot-rooted declarations for `actor-id`'s `declarations`
  (`{:sensitive [paths] :large [paths]}`) on a base elision-registry value
  `registry`, delegating to the core multi-owner ops. SET UNIONS this actor's owner
  (`{:source :machine :actor-id …}`) into each axis slot
  (`:sensitive-declarations` / `:declarations` — the SAME slots the four
  commit-plane effects and `reg-flow` outputs populate) ALONGSIDE any foreign
  owner on the same absolute path (Spec 015 L149 explicitly permits an app to
  additionally classify a subsystem absolute path from a handler effect,
  `:source :effect`); DROP removes ONLY this actor's own owner
  (`rf.elision/remove-claims`) at the named absolute paths — a path ALSO claimed
  by another owner is LEFT INTACT, so an actor teardown never silently
  un-redacts a path another owner still classifies (a privacy fail-open,
  rf2-wdm1vg). An emptied axis slot is pruned by the core ops. Returns the new
  registry value."
  [registry actor-id declarations add?]
  (let [owner (machine-owner actor-id)]
    (reduce
      (fn [registry [axis slot]]
        (let [paths (get declarations axis)]
          (if-not (seq paths)
            registry
            (let [absolute-paths (map #(absolute-snapshot-path actor-id %) paths)]
              (if add?
                (rf.elision/add-claims registry slot owner absolute-paths)
                (rf.elision/remove-claims registry slot owner absolute-paths))))))
      (or registry {})
      {:sensitive :sensitive-declarations
       :large     :declarations})))

(defn lower-at-spawn!
  "LOWER the machine `spec`'s projection-relative `:sensitive` / `:large`
  declarations into `frame-id`'s per-frame elision registry, PER ACTOR
  INSTANCE — re-rooting each snapshot-relative `:data` path to the absolute
  snapshot path for `actor-id` and writing it `{:source :machine}` into the
  registry. A no-op when the spec declares no
  classification (the common fail-open case) or `actor-id` is nil. Runs at
  spawn / singleton first-boot, the instance-birth point — so a
  `:spawn`-generated `<type>#n` is classified the moment its snapshot
  lands, with no per-instance author code.

  Writes through `re-frame.elision/swap-elision-slot!` — the SAME registry
  write surface the four commit-plane effects and the marks API share — so
  the lowered declaration unions with every other source at egress-lookup
  time and the existing readers (`frame-snapshot-classification`, the SSR / trace
  boundaries) redact with no change. Returns nil.

  rf2-i4aj9c — the 4-arity threads A's exact-incarnation `owner-token` through
  `swap-elision-slot!`'s EXACT arity (the same exact-write the flow lifecycle
  ops issue, rf2-vxgfnd.155): a synchronous container watch that destroys A and
  publishes same-id B DURING this durable-registry write neither redirects the
  write into B nor bumps B's commit epoch — on mid-write loss it writes nothing.
  The 3-arity is the historical bare-id write (no event owner — conformance /
  pure-fn callers)."
  ([frame-id actor-id spec] (lower-at-spawn! frame-id actor-id spec nil))
  ([frame-id actor-id spec owner-token]
   (when actor-id
     (when-let [declarations (machine-declarations spec)]
       (let [transform (fn [registry]
                         (apply-declarations registry actor-id declarations true))]
         (if owner-token
           (rf.elision/swap-elision-slot! frame-id owner-token transform)
           (rf.elision/swap-elision-slot! frame-id transform)))))
   nil))

(defn drop-at-destroy!
  "DROP the machine `spec`'s per-instance classification declarations for
  `actor-id` from `frame-id`'s elision registry — the teardown half of
  `lower-at-spawn!`. Dissocs exactly the
  absolute snapshot-rooted paths the spawn lowered (other-sourced entries
  for the same path survive); an emptied axis slot is pruned so a frame that
  destroys its last classified actor is left without a stray registry
  sub-tree (no leak). A no-op when the spec declared no classification or
  `actor-id` is nil. Returns nil.

  rf2-i4aj9c — the 4-arity threads A's exact-incarnation `owner-token` through
  `swap-elision-slot!`'s EXACT arity so a container watch that destroys A and
  publishes same-id B mid-write cannot re-root the drop onto B's registry or
  bump B's commit epoch (the drop tail of the incarnation-fence family). The
  3-arity is the historical bare-id write (no event owner)."
  ([frame-id actor-id spec] (drop-at-destroy! frame-id actor-id spec nil))
  ([frame-id actor-id spec owner-token]
   (when actor-id
     (when-let [declarations (machine-declarations spec)]
       (let [transform (fn [registry]
                         (apply-declarations registry actor-id declarations false))]
         (if owner-token
           (rf.elision/swap-elision-slot! frame-id owner-token transform)
           (rf.elision/swap-elision-slot! frame-id transform)))))
   nil))
