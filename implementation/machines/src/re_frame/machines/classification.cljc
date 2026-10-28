(ns re-frame.machines.classification
  "Projection-relative classification for durable machine `:data`.

  A machine definition declares `:sensitive` and `:large` paths relative to
  one actor snapshot. Egress applies them to every actor snapshot present in
  the runtime-db being projected, re-rooted onto that actor's absolute
  snapshot path, so an actor is classified exactly while its snapshot is
  there:

  ```clojure
  (rf/reg-machine :checkout/payment
    {:sensitive [[:data :payment :token]]
     :large     [[:data :payment :receipt-pdf]]
     :schemas {:data …}, :initial …, :states …})
  ```

  This namespace publishes the `:machines/runtime-classification` hook for
  the `:rf.runtime/machines` root (`runtime-classification`); core claims what
  it returns under `:source :machine`, so the trace, SSR, and projection
  readers need no machine-specific path. A malformed declaration fails at
  registration. Omitting classification leaves the data unclassified. Spec 015
  §Subsystem projection-relative classification."
  (:require [re-frame.error :as rf.error]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.path :as rf.path]
            [re-frame.registrar :as rf.registrar]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private classification-axis-keys
  "The projection-relative declaration axes accepted by `reg-machine`."
  #{:sensitive :large})

(defn- declaration-defect
  "PURE fail-loud-INPUT validator for one machine classification axis
  payload. Returns a human reason string for the FIRST defect, or nil when
  the payload is well-shaped. A payload is well-shaped iff it is a vector of
  valid concrete `:rf/path` vectors, the same shape `reg-classification`
  accepts.
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
               ":token]]}), applied to each actor snapshot at egress.")
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
  ABSOLUTE runtime-db path of the actor snapshot keyed under `actor-id` —
  `[:rf.runtime/machines :snapshots <actor-id> :data …]` — the exact shape
  `re-frame.classification/frame-snapshot-classification` re-roots back
  snapshot-relative. Pure."
  [actor-id path]
  (into (rf.machines.paths/snapshot-path actor-id) path))

(defn runtime-classification
  "The `:machines/runtime-classification` hook for the `:rf.runtime/machines`
  root. Collect, `[]`: each registered machine's declarations
  (`machine-declarations`), keyed by machine id, through the bound frame
  view. Apply, `[machines decls]`: every actor snapshot under `machines` (the
  root's value) names its machine — its `[:rf/runtime :machine-type]`, or its
  own id for a singleton — and that machine's declaration is re-rooted onto
  the actor's absolute snapshot path. Pure. Spec 015 §Subsystem
  projection-relative classification."
  ([]
   (into {}
         (keep (fn [[id desc]]
                 (when (:rf/machine? desc)
                   (when-let [decl (machine-declarations (:rf/machine desc))]
                     [id decl]))))
         (rf.registrar/registrations :event)))
  ([machines decls]
   (reduce-kv
     (fn [acc actor-id snapshot]
       (let [decl (when (map? snapshot)
                    (get decls (or (get-in snapshot [:rf/runtime :machine-type]) actor-id)))
             abs  (fn [axis] (map #(absolute-snapshot-path actor-id %) (get decl axis)))]
         (-> acc
             (update :sensitive into (abs :sensitive))
             (update :large into (abs :large)))))
     {:sensitive [] :large []}
     (or (:snapshots machines) {}))))

