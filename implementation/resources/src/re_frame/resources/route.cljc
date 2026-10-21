(ns re-frame.resources.route
  "The late-bound routing integration for resources. Neither artefact
  statically requires the other. Resources publishes two kinds of hook:

  - `:routing/extra-route-keys`, so routing accepts the `:resources`
    route-metadata key;
  - the plan hooks (`:routing/on-route-entry`, `-replan`, `-prefetch`), which
    turn a route's effective `:resources` into ensure / adopt / release fx
    and the blocking and plan slots routing commits.

  `reconcile-readiness` is the one reply-driven readiness projector.
  Spec 016 §Route integration"
  (:require [re-frame.error :as rf.error]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.resources.registry :as rf.resources.registry]
            [re-frame.resources.scope-registry :as rf.resources.scope-registry]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.work-ledger :as rf.resources.work-ledger]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

(def resources-route-key
  "The route-metadata key the Resources artefact owns."
  :resources)

(def extra-route-keys
  "The route-metadata keys this artefact adds to routing's accepted set,
  published under `:routing/extra-route-keys`."
  #{resources-route-key})

;; ---- routing-runtime blocking slot ----------------------------------------
;; Per nav-token `{<key-id> <scoped-key>}`, the carrier shape `:entries` uses.
;; Being per-token EDN in runtime-db, a stale token's drain is a structural
;; no-op and the slot rides restore and SSR with the rest of routing.

(def routing-key
  "The routing-runtime subtree key, duplicated rather than imported so
  resources never statically requires routing."
  :rf.runtime/routing)

(defn blocking-path
  "The runtime-db path of `nav-token`'s outstanding blocking map
  `{<key-id> <scoped-key>}`."
  [nav-token]
  [routing-key :resource-blocking nav-token])

(defn current-path
  "The runtime-db path of the live route slice."
  []
  [routing-key :current])

(defn blocking-slots-map-path
  "The runtime-db path of the whole per-nav-token blocking map."
  []
  [routing-key :resource-blocking])

(defn clear-blocking-slot
  "Returns `runtime-db` without `nav-token`'s blocking slot. A superseded
  token's slot must be cleared explicitly: readiness projects only the live
  token, and an orphaned in-flight request would otherwise leave its entry
  forever."
  [runtime-db nav-token]
  (if (contains? (get-in runtime-db (blocking-slots-map-path)) nav-token)
    (update-in runtime-db (blocking-slots-map-path) dissoc nav-token)
    runtime-db))

;; ---- routing-runtime plan-identity slot -----------------------------------
;; Every identity a nav-token's plan owns, blocking or not; the next
;; activation diffs against it. Spec 016 §Effective parent-chain resource plans

(defn plan-path
  "The runtime-db path of `nav-token`'s plan identity map
  `{<key-id> <scoped-key>}`."
  [nav-token]
  [routing-key :resource-plan nav-token])

(defn plan-slots-map-path
  "The runtime-db path of the whole per-nav-token plan identity map."
  []
  [routing-key :resource-plan])

(defn clear-plan-slot
  "Returns `runtime-db` without `nav-token`'s plan slot, symmetric with
  `clear-blocking-slot`."
  [runtime-db nav-token]
  (if (contains? (get-in runtime-db (plan-slots-map-path)) nav-token)
    (update-in runtime-db (plan-slots-map-path) dissoc nav-token)
    runtime-db))

;; ---- the reply-driven readiness projector ---------------------------------
;; Every path that changes a blocking requirement's facts after the commit
;; writes the entry, then hands runtime-db to `reconcile-readiness`, which
;; re-reads the facts; no caller decides what a settle meant.
;;
;; The activation commit is the exception: routing seeds the slice with its
;; own copy of the table (`re-frame.routing.readiness/project-at-commit`),
;; because routing is a test-only dep here and this namespace cannot require
;; it. Changing the precedence (error beats loading beats idle) means
;; changing both sites; `re-frame.readiness-projector-conformance-cljs-test`
;; fails on a divergence.
;;
;; Spec 012 §Route readiness is a resource projection

(defn requirement-state
  "Classifies one blocking requirement from its cache `entry` (nil when
  absent): `:ready` (its own usable data; `:keep-previous?` data never
  counts), `:failed` (first load failed), `:pending` (in flight or not yet
  started) or `:inert` (settled with no data and nothing left to settle it,
  e.g. an aborted first load). A refresh failure keeps the data and reads
  `:ready`. Pure."
  [entry]
  (cond
    (nil? entry)                            :pending
    (rf.resources.state/has-data? entry)                 :ready
    (= :error (:status entry))              :failed
    (contains? #{:loading :fetching} (:status entry)) :pending
    (zero? (:attempt entry 0))              :pending
    :else                                   :inert))

(defn requirement-met?
  "True iff the requirement can no longer hold the route (`:ready` or
  `:inert`), so it is pruned from the outstanding set. This is the
  post-commit question; at commit `requirement-ready?` asks instead, because
  there `:inert` means about to be reloaded."
  [entry]
  (contains? #{:ready :inert} (requirement-state entry)))

(defn requirement-ready?
  "True iff `entry` already has its own usable data, the one reason a
  blocking requirement is not recorded at commit. Every other state is
  recorded because the plan guarantees work for it."
  [entry]
  (= :ready (requirement-state entry)))

(defn adoptable?
  "True iff a retained plan identity can be adopted without a fetch: it is
  `:ready`, or genuinely live work will settle it. Adoption issues no request,
  so adopting anything else would leave a blocking slot nothing drains.
  Pure. Spec 016 §Plan diff and owner handoff"
  [entry]
  (case (requirement-state entry)
    :ready   true
    :pending (rf.resources.work-ledger/live-work? entry)
    false))

(defn- route-blocking-error
  "The `:rf.error/resource-route-blocking` error for a failed blocking first
  load. One source for both the route-slice `:error` and the trace tags,
  which must agree."
  [entry nav-token]
  {:rf.error/id :rf.error/resource-route-blocking
   :operation   :rf.error/resource-route-blocking
   :resource-id (:resource/id entry)
   :nav-token   nav-token
   :recovery    :no-recovery
   :error       (:error entry)
   :reason      "A blocking route resource failed its first load."})

(defn reconcile-readiness
  "Re-projects the current route's `:transition` / `:error` from the live
  resource facts, prunes met requirements, and returns runtime-db.

  | outstanding blocking map            | `:transition` | `:error`      |
  |-------------------------------------|---------------|---------------|
  | contains a `:failed` requirement    | `:error`      | first failure |
  | otherwise contains a `:pending` one | `:loading`    | nil           |
  | empty                               | `:idle`       | nil           |

  Pruning means a later invalidation never re-blocks a landed route. A
  `:failed` requirement is not pruned. No slot for the live token is a no-op.

  Edge-triggered: the slice is rewritten only when the projection differs,
  and the error trace fires only on entry into `:error`, so a later failure
  that becomes the canonical first moves the slice silently.
  `:emit-error? false` defers the trace for epoch restore.
  Spec 012 §Route readiness is a resource projection"
  ([runtime-db] (reconcile-readiness runtime-db nil))
  ([runtime-db {:keys [emit-error?] :or {emit-error? true}}]
   (let [slice-path (current-path)
         nav-token  (get-in runtime-db (conj slice-path :nav-token))
         blocking   (get-in runtime-db (blocking-path nav-token))]
     (if (empty? blocking)
       runtime-db
       (let [entry-of    (fn [k-id] (get-in runtime-db (rf.resources.state/entry-path-by-id k-id)))
             outstanding (into {} (remove (comp requirement-met? entry-of key)) blocking)
             ;; The slot promises no order, so pick the first failure by
             ;; byte key-id, stable across settles that prune siblings.
             failed      (->> (sort-by key outstanding)
                              (filter (comp #(= :failed (requirement-state %)) entry-of key))
                              first)
             transition  (cond failed :error (seq outstanding) :loading :else :idle)
             err         (when failed (route-blocking-error (entry-of (key failed)) nav-token))
             db'         (assoc-in runtime-db (blocking-path nav-token) outstanding)]
         (if (and (= transition (get-in db' (conj slice-path :transition)))
                  (= err        (get-in db' (conj slice-path :error))))
           db'
           (do
             (when (and err emit-error?
                        ;; `db'` still carries the previous transition here.
                        (not= :error (get-in db' (conj slice-path :transition))))
               (rf.trace/emit-error! :rf.error/resource-route-blocking
                                  (dissoc err :rf.error/id :operation)))
             (-> db'
                 (assoc-in (conj slice-path :transition) transition)
                 (assoc-in (conj slice-path :error) err)))))))))

;; ---- planning ---------------------------------------------------------------
;; A `:when` predicate is `(fn [route ctx] …)`; ctx is the reserved entry
;; context (currently `{}`). Db-derived scope comes only from `{:from-db …}`.

(defn- planning-error
  "The `:rf.error/resource-route-plan` ex-info for a fail-closed planning site.
  `extra` is the ex-data; its `:recovery` overrides `:fix-params`. The plan
  boundary catches it and surfaces it on the route slice."
  [reason extra]
  (rf.error/thrown-ex-info
    :rf.error/resource-route-plan
    'rf/route-resource-plan
    reason
    {:recovery (get extra :recovery :fix-params)
     :extra    (dissoc extra :recovery)}))

(defn- when-passes?
  "True when the entry has no `:when` or it returns truthy. A throwing `:when`
  is a planning error, never a silent admit or deny."
  [{when-fn :when :keys [resource]} route ctx]
  (if when-fn
    (try
      (boolean (when-fn route ctx))
      (catch #?(:clj Throwable :cljs :default) ex
        (throw (planning-error
                 (str "route resource " resource " :when predicate threw — "
                      "a route/resource planning error, not a silent gate. "
                      "Per Spec 016 §Route integration.")
                 {:resource-id resource :recovery :fix-when :cause (ex-data ex)}))))
    true))

(defn- resolve-entry-params
  "The entry's params from its `:params` `(fn [route] …)`, or `{}` when it has
  none. A resolver that returns nil is a planning error, because it would
  silently read a different cache key."
  [{params-fn :params :keys [resource]} route]
  (if params-fn
    (let [resolved-params (params-fn route)]
      (if (nil? resolved-params)
        (throw (planning-error
                 (str "route resource " resource " :params resolver returned "
                      "nil — a planning error, not a silent empty-param read. "
                      "Gate a conditional resource with :when (NOT nil params). "
                      "Per Spec 016 §Route integration.")
                 {:resource-id resource :recovery :fix-params}))
        resolved-params))
    {}))

(defn- resolve-entry-scope
  "The entry's `:scope` override as a canonical scope, or nil when the entry
  has no `:scope` key (the registration policy governs). A present `:scope` is
  a `{:from-db <id>}` reference resolved against `app-db`, or a concrete scope.
  A fn, a nil, or a reference that resolves nil is a planning error: the scope
  is the tenant boundary, so a wrong one fails closed.
  Spec 016 §Resolution precedence (for events; no global fallthrough)"
  [{scope :scope :keys [resource] :as entry} app-db]
  (cond
    (not (contains? entry :scope))
    nil

    (rf.resources.scope-registry/from-db-reference? scope)
    (let [resolved-scope (rf.resources.scope-registry/resolve-from-db-reference
                           scope (or app-db {}) 'rf.resource/route-entry)]
      (if (nil? resolved-scope)
        (throw (planning-error
                 (str "route resource " resource " :scope {:from-db "
                      (pr-str (:from-db scope)) "} resolved nil against the "
                      "route-entry app-db — a planning error, not a silent "
                      "fallback to the spec policy or a global read. The named "
                      "resolver's declared :inputs are absent (e.g. no "
                      "logged-in user). Per Spec 016 §Resolver references / "
                      "§Scope resolution.")
                 {:resource-id resource :recovery :fix-scope :from-db (:from-db scope)}))
        resolved-scope))

    (or (fn? scope) (nil? scope))
    (throw (planning-error
             (str "route resource " resource " :scope is " (pr-str scope)
                  " — not a route-entry scope override. A route entry's :scope "
                  "is EXACTLY one of: a {:from-db <scope-resolver-id>} named-"
                  "resolver reference, or a concrete scope value. The anonymous "
                  "(fn [route ctx] …) route-scope resolver tier is RETIRED — "
                  "name the derivation with reg-resource-scope and reference it "
                  "as {:from-db <id>}. Omit :scope entirely to inherit the "
                  "resource registration's policy. Per Spec 016 §Scope "
                  "resolution.")
             {:resource-id resource :recovery :fix-scope :scope scope}))

    :else
    (rf.resources.state/canonicalize-scope scope 'rf.resource/route-entry resource)))

;; ---- :after ---------------------------------------------------------------
;; `:after` orders ensure dispatch only. The planner is synchronous and
;; resolves every entry before anything settles, so params can never depend
;; on another entry's data.

(defn- order-by-after
  "Stable topological order of `entries` by their `:after` route-local `:id`s.
  A missing target or a cycle is a planning error, never silent declaration
  order."
  [entries]
  (let [entry-id           (fn [entry] (:id entry))
        entry-dependencies (fn [entry] (set (:after entry)))
        known-entry-ids     (into #{} (keep entry-id) entries)
        _ (doseq [entry entries]
            (when-let [missing (seq (remove known-entry-ids
                                            (entry-dependencies entry)))]
              (throw (planning-error
                       (str "route resource " (:resource entry) " (local id "
                            (pr-str (entry-id entry)) ") declares :after " (pr-str (set missing))
                            " — no route-local :id matches. `:after` MUST target a "
                            "route-local :id declared on another entry. A typo'd "
                            "dependency is a planning error, not silent ordering. "
                            "Per Spec 016 §Route integration.")
                       {:resource-id (:resource entry) :recovery :fix-after
                        :missing-after (vec missing) :local-id (entry-id entry)}))))
        ;; A pass that emits nothing while entries remain is a cycle.
        max-pass-count (count entries)]
    (loop [remaining (vec entries)
           emitted   []
           seen-ids  #{}
           pass-count 0]
      (cond
        (empty? remaining) emitted
        (> pass-count max-pass-count)
        (throw (planning-error
                 (str "route resources have a cyclic :after dependency among "
                      (pr-str (into #{} (keep entry-id) remaining))
                      " — a planning error, not silent declaration-order "
                      "fallthrough. Per Spec 016 §Route integration.")
                 {:recovery :fix-after
                  :cycle (into #{} (keep entry-id) remaining)}))
        :else
        (let [{ready true held false}
              (group-by (fn [entry]
                          (every? #(contains? seen-ids %)
                                  (entry-dependencies entry)))
                        remaining)]
          (if (empty? ready)
            (throw (planning-error
                     (str "route resources have a cyclic :after dependency among "
                          (pr-str (into #{} (keep entry-id) remaining))
                          " — a planning error, not silent declaration-order "
                          "fallthrough. Per Spec 016 §Route integration.")
                     {:recovery :fix-after
                      :cycle (into #{} (keep entry-id) remaining)}))
            (recur (vec held)
                   (into emitted ready)
                   (into seen-ids (keep entry-id ready))
                   (inc pass-count))))))))

;; ---- effective parent-chain composition -------------------------------------
;; `branch` is `[{:route-id :route-meta} …]`, parent-most first. Every
;; contributor's resolvers run against the leaf target.
;; Spec 016 §Effective parent-chain resource plans

(defn- with-contributor-attribution
  "`ex` with `:contributor` (the contributing route id, and the local id when
  entry-scoped) in its ex-data; the original is the cause. The leaf route id
  alone cannot say which declaration failed. The planner's value always wins
  over a resolver's own `:contributor`, which stays on the cause."
  [ex contributor]
  (ex-info (ex-message ex) (assoc (ex-data ex) :contributor contributor) ex))

(defn- materialize-occurrences
  "Resolves every `:when`-admitted occurrence across `branch` against the
  leaf `route`, in branch then local order: scope, canonical params, scoped
  key and byte `key-id`. A throw carries its contributor out."
  [branch route ctx app-db]
  (first
    (reduce
      (fn [occurrences-and-next-sequence
           [branch-index {:keys [route-meta]
                          contributor-id :route-id}]]
        (reduce
          (fn [[occurrences next-sequence] entry]
            (try
              (if-not (when-passes? entry route ctx)
                [occurrences next-sequence]
                (let [resource-id (:resource entry)
                      spec        (rf.resources.registry/require-resource-spec!
                                    resource-id 'rf.resource/route-entry)
                      raw-params  (resolve-entry-params entry route)
                      route-scope (resolve-entry-scope entry app-db)
                      scope       (rf.resources.registry/resolve-scope-for-event
                                    resource-id spec {:route-scope route-scope :db app-db}
                                    'rf.resource/route-entry)
                      canonical-params (rf.resources.registry/validate+canonicalize-params
                                         resource-id spec raw-params
                                         'rf.resource/route-entry)
                      scoped-key       (rf.resources.state/scoped-resource-key*
                                         scope resource-id canonical-params)]
                  [(conj occurrences
                         {:occ-id         [contributor-id (:id entry) next-sequence]
                          :seq            next-sequence
                          :branch-index   branch-index
                          :route-id       contributor-id
                          :local-id       (:id entry)
                          :after          (set (:after entry))
                          :resource       resource-id
                          :scope          scope
                          :cparams        canonical-params
                          :scoped-key     scoped-key
                          ;; The grouping key downstream. `=` is coarser
                          ;; (vector vs list params), so grouping on the
                          ;; scoped key would collapse two real identities.
                          :key-id         (rf.resources.state/key-id scoped-key)
                          :blocking?      (boolean (:blocking? entry))
                          :keep-previous? (boolean (:keep-previous? entry))})
                   (inc next-sequence)]))
              (catch #?(:clj Throwable :cljs :default) ex
                (throw (with-contributor-attribution
                         ex {:route-id contributor-id
                             :local-id (:id entry)})))))
          occurrences-and-next-sequence
          ;; Only this frame knows which contributor declared a bad `:after`.
          (try
            (order-by-after (:resources route-meta))
            (catch #?(:clj Throwable :cljs :default) ex
              (throw (with-contributor-attribution
                       ex (cond-> {:route-id contributor-id}
                            (:local-id (ex-data ex))
                            (assoc :local-id (:local-id (ex-data ex))))))))))
      [[] 0]
      (map-indexed vector branch))))

(defn- occurrence-edges
  "The must-precede edges `[from-occ-id to-occ-id]`: every ancestor occurrence
  precedes every descendant one, and a same-contributor `:after` dependency
  precedes its dependent."
  [occurrences]
  (into #{}
        (concat
          (for [ancestor-occurrence occurrences
                descendant-occurrence occurrences
                :when (< (:branch-index ancestor-occurrence)
                         (:branch-index descendant-occurrence))]
            [(:occ-id ancestor-occurrence) (:occ-id descendant-occurrence)])
          (for [dependent-occurrence occurrences
                dependency-occurrence occurrences
                :when (and (= (:route-id dependent-occurrence)
                              (:route-id dependency-occurrence))
                           (not= (:occ-id dependent-occurrence)
                                 (:occ-id dependency-occurrence))
                           (contains? (:after dependent-occurrence)
                                      (:local-id dependency-occurrence)))]
            [(:occ-id dependency-occurrence) (:occ-id dependent-occurrence)]))))

(defn- redundant-child-advisories
  "One advisory per descendant declaration that repeats an identity an
  ancestor already contributes, naming both so the child copy can be deleted."
  [groups]
  (into []
        (mapcat
          (fn [[_key-id occurrences]]
            (let [ordered-occurrences (sort-by :seq occurrences)]
              (when (> (count (into #{} (map :route-id) occurrences)) 1)
                (let [ancestor-occurrence (first ordered-occurrences)]
                  (for [child-occurrence (rest ordered-occurrences)
                        :when (not= (:route-id child-occurrence)
                                    (:route-id ancestor-occurrence))]
                    {:resource   (:resource ancestor-occurrence)
                     :scoped-key (:scoped-key ancestor-occurrence)
                     :ancestor   {:route-id (:route-id ancestor-occurrence)
                                  :local-id (:local-id ancestor-occurrence)}
                     :child      {:route-id (:route-id child-occurrence)
                                  :local-id (:local-id child-occurrence)}}))))))
        groups))

(defn- collapse-and-order
  "Groups occurrences by byte `key-id` and stable-topologically orders the
  groups. Returns `{:ordered [dedup-req …] :advisories […]}`, or
  `{:cycle [scoped-key …]}` when the collapse creates a cycle. A dedup-req is
  blocking or `:keep-previous?` when any contributor asks, keeps every
  contributor, and takes its rank from its earliest occurrence."
  [occurrences]
  (let [occurrence-by-id        (into {} (map (juxt :occ-id identity)) occurrences)
        group-id-for-occurrence (fn [occurrence-id]
                                  (:key-id (occurrence-by-id occurrence-id)))
        occurrences-by-group-id (group-by :key-id occurrences)
        group-ids               (set (keys occurrences-by-group-id))
        group-edges             (into #{}
                                      (comp
                                        (map (fn [[from-occurrence-id to-occurrence-id]]
                                               [(group-id-for-occurrence from-occurrence-id)
                                                (group-id-for-occurrence to-occurrence-id)]))
                                        (remove (fn [[from-group-id to-group-id]]
                                                  (= from-group-id to-group-id))))
                                      (occurrence-edges occurrences))
        rank-by-group-id        (into {}
                                      (map (fn [[group-id group-occurrences]]
                                             [group-id (reduce min (map :seq group-occurrences))]))
                                      occurrences-by-group-id)
        successors-by-group-id  (reduce
                                  (fn [successors [from-group-id to-group-id]]
                                    (update successors from-group-id (fnil conj #{}) to-group-id))
                                  {}
                                  group-edges)
        initial-in-degree-by-group-id
        (reduce (fn [in-degree-by-group-id [_from-group-id to-group-id]]
                  (update in-degree-by-group-id to-group-id inc))
                (zipmap group-ids (repeat 0))
                group-edges)]
    (loop [in-degree-by-group-id initial-in-degree-by-group-id
           ordered-group-ids    []]
      (if (empty? in-degree-by-group-id)
        {:ordered
         (mapv (fn [group-id]
                  (let [group-occurrences         (sort-by :seq (occurrences-by-group-id group-id))
                        representative-occurrence (first group-occurrences)]
                    {:scoped-key     (:scoped-key representative-occurrence)
                     :key-id         group-id
                     :resource       (:resource representative-occurrence)
                     :scope          (:scope representative-occurrence)
                     :cparams        (:cparams representative-occurrence)
                     :blocking?      (boolean (some :blocking? group-occurrences))
                     :keep-previous? (boolean (some :keep-previous? group-occurrences))
                     :contributors   (mapv
                                       (fn [occurrence]
                                         {:route-id     (:route-id occurrence)
                                          :local-id     (:local-id occurrence)
                                          :branch-index (:branch-index occurrence)})
                                       group-occurrences)}))
                ordered-group-ids)
         :advisories (redundant-child-advisories occurrences-by-group-id)}
        (let [ready-group-ids (keep (fn [[group-id in-degree]]
                                      (when (zero? in-degree) group-id))
                                    in-degree-by-group-id)]
          (if (empty? ready-group-ids)
            ;; Report scoped keys: a byte key-id names nothing an author can act on.
            {:cycle (mapv (comp :scoped-key first occurrences-by-group-id)
                          (keys in-degree-by-group-id))}
            (let [next-group-id (first (sort-by rank-by-group-id ready-group-ids))
                  next-in-degree-by-group-id
                  (reduce (fn [remaining-in-degrees successor-group-id]
                            (update remaining-in-degrees successor-group-id dec))
                          (dissoc in-degree-by-group-id next-group-id)
                          (successors-by-group-id next-group-id))]
              (recur next-in-degree-by-group-id
                     (conj ordered-group-ids next-group-id)))))))))

(defn- branch-plan-error
  "The planning error map for a failed branch walk (unknown or cyclic
  `:parent`), shaped like `plan-error`."
  [route-id nav-token {:keys [kind route-id* chain]}]
  {:rf.error/id :rf.error/resource-route-plan
   :route-id    route-id
   :nav-token   nav-token
   :recovery    :fix-parent
   :reason      (case kind
                  :unknown-parent
                  (str "route " route-id " declares a :parent chain naming the "
                       "unregistered route " (pr-str route-id*) " — branch "
                       "resolution is fail-loud, not silently truncated. Per Spec "
                       "016 §Effective parent-chain resource plans.")
                  :parent-cycle
                  (str "route " route-id " has a :parent cycle at " (pr-str route-id*)
                       " (chain " (pr-str chain) ") — branch resolution is fail-loud. "
                       "Per Spec 016 §Effective parent-chain resource plans.")
                  (str "route " route-id " branch resolution failed. Per Spec 016 "
                       "§Effective parent-chain resource plans."))
   :cause       {:branch-error kind :parent route-id*}})

(defn- collapse-cycle-error
  "The planning error map for a cycle created by identity collapse."
  [route-id nav-token cyclic]
  {:rf.error/id :rf.error/resource-route-plan
   :route-id    route-id
   :nav-token   nav-token
   :recovery    :fix-after
   :reason      (str "route " route-id " resource plan has a cyclic dependency "
                     "after identity collapse among " (pr-str cyclic) " — a later "
                     "occurrence's :after edge would contradict an earlier "
                     "occurrence's position once the shared identity is deduped. "
                     "The plan fails rather than silently dropping an edge. Per "
                     "Spec 016 §Effective parent-chain resource plans.")
   :cause       {:cyclic-identities (vec cyclic)}})

(defn- plan-error
  "The planning error map for a failure caught at the plan boundary, recorded
  on the route slice and traced. A `planning-error`'s own `:reason` and
  `:recovery` are surfaced. `:contributor` names the declaring route beside
  the leaf `:route-id`. There is no `:operation` slot: the error id already
  names the only operation."
  [route-id nav-token resource-id ex]
  (let [data (ex-data ex)]
    (cond-> {:rf.error/id :rf.error/resource-route-plan
             :route-id    route-id
             :resource-id resource-id
             :nav-token   nav-token
             :recovery    (get data :recovery :fix-params)
             :reason      (or (:reason data)
                              (str "route " route-id " resource " resource-id
                                   " failed planning (params / scope did not resolve) — a "
                                   "route/resource planning error, not a silent cache miss. "
                                   "Per Spec 016 §Route integration."))
             :cause       data}
      (:contributor data) (assoc :contributor (:contributor data)))))

(defn route-resource-plan
  "The pure ensure / adopt / release plan for `route`'s effective resources.
  Returns `{:fx [...] :blocking {<key-id> <scoped-key>} :identities {<key-id>
  <scoped-key>} :advisories [...] :plan-error err?}`; it also emits the
  `:rf.resource/route-plan` trace and any planning-error trace.

  `ctx` must be a map and `:nav-token` present: `[:route route-id nav-token]`
  is the owner, so both throw when missing. `:app-db` resolves `{:from-db …}`
  scopes; `:runtime-db` is read for at-commit facts (without it everything
  ensures and every blocking requirement records, the fail-safe direction).
  A planning failure commits a failed activation: no ensures, prior owner
  released.

  Fx order is attach then release, so a shared in-flight ancestor is never
  momentarily ownerless. `:plan-cause :replan` keeps the owner, threads
  `:replan-cause` onto every ensure, releases a subset of dropped identities,
  and on failure releases the owner wholesale.
  Spec 016 §Plan diff and owner handoff and Spec 016 §Route-plan replan — same-token reconciliation"
  [route ctx {:keys [nav-token prev-id prev-nav-token app-db runtime-db branch
                     branch-error prev-identities plan-cause replan-cause]}]
  (when (nil? ctx)
    (throw (planning-error
             (str "route-resource-plan was handed a nil ctx for route "
                  (pr-str (:id route)) " — the entry ctx seam MUST be a map "
                  "(empty is fine; nil is a routing↔resources seam bug). A "
                  ":scope / :when resolver reads it. Per Spec 016 §Route "
                  "integration.")
             {:route-id (:id route) :recovery :fix-route-integration})))
  (when (nil? nav-token)
    (throw (planning-error
             (str "route-resource-plan was handed no nav-token for route "
                  (pr-str (:id route)) " — the nav-token IS the route owner "
                  "identity ([:route route-id nav-token]); planning without "
                  "one would mint an unreleasable owner. Per Spec 016 §Route "
                  "integration.")
             {:route-id (:id route) :recovery :fix-route-integration})))
  (let [route-id  (:id route)
        owner     [:route route-id nav-token]
        replan?   (= :replan plan-cause)
        cause     (if replan? replan-cause [:route-entry route-id nav-token])
        stamp     (fn [err]
                    (if replan?
                      (assoc err :plan-cause :replan :replan-cause replan-cause)
                      err))
        ;; Without a routing-supplied `:branch`, plan the leaf alone.
        branch    (or branch [{:route-id route-id
                               :route-meta {:resources (:resources route)}}])
        {:keys [ordered advisories plan-error]}
        (cond
          branch-error
          (let [err (stamp (branch-plan-error route-id nav-token branch-error))]
            (rf.trace/emit-error! :rf.error/resource-route-plan err)
            {:plan-error err})
          :else
          (try
            (let [occurrences    (materialize-occurrences branch route ctx app-db)
                  collapse-result (collapse-and-order occurrences)]
              (if-let [cyclic (:cycle collapse-result)]
                (let [err (stamp (collapse-cycle-error route-id nav-token cyclic))]
                  (rf.trace/emit-error! :rf.error/resource-route-plan err)
                  {:plan-error err})
                collapse-result))
            (catch #?(:clj Throwable :cljs :default) ex
              (let [err (stamp (plan-error route-id nav-token
                                           (:resource-id (ex-data ex)) ex))]
                (rf.trace/emit-error! :rf.error/resource-route-plan err)
                {:plan-error err}))))
        ;; Both identity carriers are keyed by byte key-id, never Clojure `=`:
        ;; `{:p [1 2]}` and `{:p '(1 2)}` are `=` yet live at two entry paths,
        ;; and a set of scoped keys would collapse them.
        prev-by-id (if (map? prev-identities)
                     prev-identities
                     (into {} (map (juxt rf.resources.state/key-id identity)) prev-identities))
        next-by-id (into {} (map (juxt :key-id :scoped-key)) ordered)
        entry-of  (fn [k-id] (get-in runtime-db (rf.resources.state/entry-path-by-id k-id)))
        ;; Kept only when in the previous plan AND reusable now; anything else
        ;; takes the ordinary ensure path.
        adopted?  (fn [{:keys [key-id]}]
                    (and (contains? prev-by-id key-id)
                         (adoptable? (entry-of key-id))))
        req-fx    (mapv
                    (fn [{:keys [resource scope cparams keep-previous?] :as req}]
                      (let [base {:resource resource :scope scope :params cparams
                                  :owner owner :cause cause}]
                        (if (adopted? req)
                          [:dispatch [:rf.resource.internal/adopt-owner base]]
                          [:dispatch [:rf.resource/ensure
                                      (cond-> base
                                        keep-previous? (assoc :keep-previous? true))]])))
                    ordered)
        ;; Only a requirement that already has its own data goes unrecorded;
        ;; the commit then projects `:idle` with no transient `:loading`.
        blocking  (into {}
                        (comp (filter :blocking?)
                              (remove #(requirement-ready? (entry-of (:key-id %))))
                              (map (juxt :key-id :scoped-key)))
                        ordered)
        ;; Named for what the runtime did: a retained-but-unusable identity
        ;; was ensured, so it is counted there.
        ensured-identities (into [] (comp (remove adopted?) (map :scoped-key)) ordered)
        kept-identities    (into [] (comp (filter adopted?) (map :scoped-key)) ordered)
        removed-by-id      (into {}
                                 (remove (fn [[k-id _]] (contains? next-by-id k-id)))
                                 prev-by-id)
        ;; Removal is unordered, so the vector is in canonical key-id order: a
        ;; small CLJS set iterates in insertion order, which would leak the
        ;; caller's order and differ between hosts. Consumers read membership.
        removed-identities (into [] (map val) (sort-by key removed-by-id))
        release-fx (cond
                     ;; Replan keeps the owner: release only the dropped
                     ;; identities, or everything when the plan failed.
                     replan?
                     (cond
                       plan-error
                       [[:dispatch [:rf.resource/release-owner {:owner owner}]]]
                       (seq removed-by-id)
                       [[:dispatch [:rf.resource.internal/release-owner-identities
                                    {:owner owner :identities removed-by-id}]]]
                       :else nil)
                     ;; Activation releases the previous route's owner, never
                     ;; the committing one.
                     (and prev-id prev-nav-token
                          (not= [prev-id prev-nav-token] [route-id nav-token]))
                     [[:dispatch [:rf.resource/release-owner
                                  {:owner [:route prev-id prev-nav-token]}]]])
        added-count        (count ensured-identities)
        removed-count      (count removed-identities)]
    (rf.trace/emit! :rf.event :rf.resource/route-plan
                 (cond-> {:route-id           route-id
                          :nav-token          nav-token
                          :branch             (mapv :route-id branch)
                          :ensured            added-count
                          :kept               (- (count ordered) added-count)
                          :removed            removed-count
                          :blocking           (vec (vals blocking))
                          ;; Grouped plan order: the order the plan executes.
                          :identities         (mapv :scoped-key ordered)
                          :ensured-identities ensured-identities
                          :kept-identities    kept-identities
                          :removed-identities removed-identities}
                   (seq advisories) (assoc :redundant-children advisories)
                   plan-error       (assoc :plan-error true)
                   replan?          (assoc :plan-cause :replan :replan-cause replan-cause)))
    {:fx         (vec (concat req-fx release-fx))
     :blocking   blocking
     :identities next-by-id
     :advisories advisories
     :plan-error plan-error}))

(defn on-route-entry-fx
  "The `:routing/on-route-entry` hook body. `entry` is `{:route-meta :route-id
  :params :query :fragment :nav-token :prev-id :prev-nav-token :ctx :app-db
  :runtime-db :branch :branch-error :prev-identities}`. Returns the
  `route-resource-plan` result, or nil when there is nothing to do: no
  contributor declares `:resources` and there is no prior owner to release.
  `:ctx` passes through unchanged, so a missing one fails closed in the
  planner. Spec 016 §Route integration"
  [{:keys [route-meta route-id params query fragment nav-token
           prev-id prev-nav-token ctx app-db runtime-db branch branch-error
           prev-identities]}]
  (let [branch (or branch [{:route-id route-id :route-meta route-meta}])]
    (when (or branch-error prev-id
              (some (fn [c] (seq (:resources (:route-meta c)))) branch))
      (let [route {:id        route-id
                   :params    params
                   :query     query
                   :fragment  fragment
                   :resources (:resources route-meta)}]
        (route-resource-plan route ctx
                             {:nav-token       nav-token
                              :prev-id         prev-id
                              :prev-nav-token  prev-nav-token
                              :app-db          app-db
                              :runtime-db      runtime-db
                              :branch          branch
                              :branch-error    branch-error
                              :prev-identities prev-identities})))))

(defn on-route-replan-fx
  "The `:routing/on-route-replan` hook body for `:rf.route/replan-resources`:
  reruns the active route's plan under its unchanged token, with the
  caller's `:cause`. Returns the `on-route-entry-fx` shape, or nil when no
  contributor declares `:resources`, the token recorded no plan, and the
  branch walk did not fail. A formerly non-empty plan replans to empty.
  Spec 016 §Route-plan replan — same-token reconciliation"
  [{:keys [route-meta route-id params query fragment nav-token ctx app-db
           runtime-db branch branch-error prev-identities cause]}]
  (let [branch (or branch [{:route-id route-id :route-meta route-meta}])]
    (when (or branch-error
              (seq prev-identities)
              (some (fn [c] (seq (:resources (:route-meta c)))) branch))
      (route-resource-plan {:id        route-id
                            :params    params
                            :query     query
                            :fragment  fragment
                            :resources (:resources route-meta)}
                           ctx
                           {:nav-token       nav-token
                            :prev-id         route-id
                            :prev-nav-token  nav-token
                            :app-db          app-db
                            :runtime-db      runtime-db
                            :branch          branch
                            :branch-error    branch-error
                            :prev-identities prev-identities
                            :plan-cause      :replan
                            :replan-cause    cause}))))

;; ---- warm-mode prefetch -----------------------------------------------------
;; The same branch plan, run as ownerless ensures with cause
;; `[:route-prefetch route-id]`: no blocking slot, no plan diff, no route
;; state. The later activation's ensure joins the warm work.
;; Spec 016 §Route-plan prefetch — warm-mode intent preload

(defn route-resource-warm-plan
  "The warm-mode plan for prefetching `route`. Returns `{:fx [ensure …]
  :warmed <n> :plan-error err?}`. A planning failure emits
  `:rf.error/resource-route-plan` with `:plan-cause :prefetch` and no
  `:nav-token`, and dispatches no partial ensures.
  Spec 016 §Route-plan prefetch — warm-mode intent preload"
  [route {:keys [app-db branch branch-error]}]
  (let [route-id (:id route)
        cause    [:route-prefetch route-id]
        branch   (or branch [{:route-id   route-id
                              :route-meta {:resources (:resources route)}}])
        warm-err (fn [err] (-> err (dissoc :nav-token) (assoc :plan-cause :prefetch)))
        {:keys [ordered plan-error]}
        (cond
          branch-error
          (let [err (warm-err (branch-plan-error route-id nil branch-error))]
            (rf.trace/emit-error! :rf.error/resource-route-plan err)
            {:plan-error err})
          :else
          (try
            (let [occurrences     (materialize-occurrences branch route {} app-db)
                  collapse-result (collapse-and-order occurrences)]
              (if-let [cyclic (:cycle collapse-result)]
                (let [err (warm-err (collapse-cycle-error route-id nil cyclic))]
                  (rf.trace/emit-error! :rf.error/resource-route-plan err)
                  {:plan-error err})
                collapse-result))
            (catch #?(:clj Throwable :cljs :default) ex
              (let [err (warm-err (plan-error route-id nil
                                              (:resource-id (ex-data ex)) ex))]
                (rf.trace/emit-error! :rf.error/resource-route-plan err)
                {:plan-error err}))))
        req-fx (mapv (fn [{:keys [resource scope cparams]}]
                       [:dispatch [:rf.resource/ensure
                                   {:resource resource :scope scope
                                    :params   cparams  :cause  cause}]])
                     ordered)]
    (rf.trace/emit! :rf.event :rf.resource/route-plan
                 (cond-> {:route-id   route-id
                          :plan-cause :prefetch
                          :branch     (mapv :route-id branch)
                          :ensured    (count ordered)}
                   plan-error (assoc :plan-error true)))
    {:fx (vec req-fx) :warmed (count ordered) :plan-error plan-error}))

(defn on-route-prefetch-fx
  "The `:routing/on-route-prefetch` hook body for `:rf.route/prefetch`.
  `entry` is `{:route-id :params :query :fragment :branch :branch-error
  :app-db}`. Returns the warm plan, or nil when no contributor declares
  `:resources` and the branch walk did not fail."
  [{:keys [route-id params query fragment app-db branch branch-error]}]
  (let [branch (or branch [{:route-id route-id :route-meta nil}])]
    (when (or branch-error
              (some (fn [c] (seq (:resources (:route-meta c)))) branch))
      (route-resource-warm-plan
        {:id route-id :params params :query query :fragment fragment}
        {:app-db app-db :branch branch :branch-error branch-error}))))

(defn install-routing-integration!
  "Publishes the routing hooks: extra route keys, and the entry, prefetch and
  replan plans. They sit unread in an app without routing. Idempotent."
  []
  (rf.late-bind/set-fn! :routing/extra-route-keys
                     (fn extra-route-keys-thunk [] extra-route-keys))
  (rf.late-bind/set-fn! :routing/on-route-entry    on-route-entry-fx)
  (rf.late-bind/set-fn! :routing/on-route-prefetch on-route-prefetch-fx)
  (rf.late-bind/set-fn! :routing/on-route-replan   on-route-replan-fx)
  nil)
