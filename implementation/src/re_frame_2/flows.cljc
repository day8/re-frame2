(ns re-frame-2.flows
  "Flows — registered, runtime-toggleable computed-state declarations.
  Per Spec 013.

  A flow says: 'when these app-db paths change, run this pure function
  and write the result to that app-db path.' Flows evaluate after every
  event drain in topological order over their static dependency graph.

  Flows are deliberately a NICHE convenience — not a sub replacement,
  not a new dataflow paradigm. Use a sub if the value is consumed by
  views; use a flow only if it must live in app-db for SSR / time-travel
  / inspector reasons."
  (:require [re-frame-2.registrar :as registrar]
            [re-frame-2.frame :as frame]
            [re-frame-2.substrate.adapter :as adapter]
            [re-frame-2.trace :as trace]))

;; ---- registry -------------------------------------------------------------

(defonce
  ^{:doc "id → flow-map. Each flow-map: {:id :inputs :output :path :doc?}."}
  flows
  (atom {}))

;; Per-frame last-inputs, keyed by [frame-id flow-id] → last seen input vec.
(defonce ^:private last-inputs (atom {}))

;; ---- topological sort (memoised) ------------------------------------------

(defn- prefix? [a b]
  (and (>= (count b) (count a))
       (= a (vec (take (count a) b)))))

(defn- depends-on?
  "Per Spec 013 §Topological sort: B depends on A iff A's :path and any
  of B's :inputs share a path prefix in either direction."
  [b-flow a-flow]
  (let [a-path (:path a-flow)]
    (boolean
      (some (fn [b-input]
              (or (prefix? a-path b-input)
                  (prefix? b-input a-path)))
            (:inputs b-flow)))))

(defn- topo-sort
  "Kahn's algorithm. Returns flows in evaluation order. Throws on cycles."
  [flow-map]
  (let [ids   (vec (keys flow-map))
        graph (into {}
                    (map (fn [id]
                           (let [flow (flow-map id)
                                 deps (->> ids
                                           (filter (fn [other]
                                                     (and (not= id other)
                                                          (depends-on? flow (flow-map other)))))
                                           set)]
                             [id deps])))
                    ids)
        order (atom [])
        ready (atom (into [] (filter #(empty? (graph %))) ids))
        remaining (atom graph)]
    (while (seq @ready)
      (let [n (peek @ready)]
        (swap! ready pop)
        (swap! order conj n)
        (doseq [m (keys @remaining)]
          (when (contains? (@remaining m) n)
            (swap! remaining update m disj n)
            (when (empty? (@remaining m))
              (swap! ready conj m))))
        (swap! remaining dissoc n)))
    (when (seq @remaining)
      (throw (ex-info ":rf.error/flow-cycle"
                      {:cycle (vec (keys @remaining))})))
    @order))

(def ^:private topo-sort* (memoize topo-sort))

(defn- invalidate-topo! []
  ;; Cheap: rebuild memoise key by re-deref. Rather than tracking memo
  ;; cache, we rely on shape: callers call ordered-flows which dereferences
  ;; @flows; if @flows changes since last memoise call, the memoise misses.
  ;; (We could use core.memoize for explicit invalidation; for v1 this is fine.)
  nil)

;; ---- registration ---------------------------------------------------------

(defn- validate-flow [flow]
  (cond
    (nil? (:id flow))
    (throw (ex-info ":rf.error/flow-missing-id" {:flow flow}))

    (not (vector? (:inputs flow)))
    (throw (ex-info ":rf.error/flow-bad-inputs"
                    {:flow flow :reason ":inputs must be a vector of paths"}))

    (not (fn? (:output flow)))
    (throw (ex-info ":rf.error/flow-bad-output"
                    {:flow flow :reason ":output must be a fn"}))

    (not (vector? (:path flow)))
    (throw (ex-info ":rf.error/flow-bad-path"
                    {:flow flow :reason ":path must be a vector"}))))

(defn reg-flow
  "Register a flow.
   Required keys: :id :inputs :output :path
   Optional: :doc :spec"
  [flow]
  (validate-flow flow)
  (registrar/register! :flow (:id flow) flow)
  (swap! flows assoc (:id flow) flow)
  ;; Cycle detection: try a topo-sort; if it throws, abort the registration.
  (try
    (topo-sort @flows)
    (catch #?(:clj Throwable :cljs :default) e
      (swap! flows dissoc (:id flow))
      (registrar/unregister! :flow (:id flow))
      (throw e)))
  (invalidate-topo!)
  (:id flow))

(defn clear-flow
  "Deregister a flow and dissoc its output path from every frame's app-db."
  [id]
  (when-let [flow (get @flows id)]
    (let [path (:path flow)]
      ;; Dissoc the output from every live frame.
      (doseq [f-id (frame/frame-ids)]
        (let [container (frame/get-frame-db f-id)
              cur       (adapter/read-container container)
              new-db    (if (and (vector? path) (seq path))
                          (update-in cur (vec (butlast path)) dissoc (last path))
                          (dissoc cur path))]
          (adapter/replace-container! container new-db)))
      (swap! flows dissoc id)
      (swap! last-inputs (fn [m]
                           (into {} (remove (fn [[[_ flow-id] _]] (= flow-id id))) m)))
      (registrar/unregister! :flow id)
      (invalidate-topo!)))
  nil)

;; ---- fx hooks (called from re-frame-2.fx) --------------------------------

(defn reg-flow-fx! [flow] (reg-flow flow))
(defn clear-flow-fx! [id] (clear-flow id))

;; ---- evaluation -----------------------------------------------------------
;;
;; Called from the per-event drain after :db commits and before :fx runs.

(defn- read-inputs [db flow]
  (mapv (fn [path] (get-in db path)) (:inputs flow)))

(defn- evaluate-flow!
  "Evaluate one flow against the given db. Returns the [new-db dirty?] tuple."
  [frame-id db flow]
  (let [k         [frame-id (:id flow)]
        new-inputs (read-inputs db flow)
        old-inputs (get @last-inputs k)]
    (if (= new-inputs old-inputs)
      [db false]
      (let [new-output (apply (:output flow) new-inputs)
            new-db     (assoc-in db (:path flow) new-output)]
        (swap! last-inputs assoc k new-inputs)
        [new-db true]))))

(defn run-flows!
  "Per Spec 013 §Drain integration: walk all registered flows in topological
  order, dirty-check each one, recompute and write if inputs changed.
  Called from the per-event drain after :db commits."
  [frame-id]
  (let [container (frame/get-frame-db frame-id)
        flow-map  @flows]
    (when (seq flow-map)
      (let [ordered (topo-sort flow-map)]
        (loop [remaining ordered
               db       (adapter/read-container container)
               any-dirty? false]
          (if (empty? remaining)
            (when any-dirty?
              (adapter/replace-container! container db))
            (let [flow (flow-map (first remaining))
                  [new-db dirty?] (evaluate-flow! frame-id db flow)]
              (recur (rest remaining) new-db (or any-dirty? dirty?)))))))))
