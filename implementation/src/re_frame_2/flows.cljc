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
;;
;; Per Spec 013, flows are frame-scoped — same flow-id can register
;; against two frames with different :inputs / :output / :path, and
;; undo / time-travel semantics belong to a specific frame's history.
;; The registry shape is {frame-id {flow-id flow-map}}.

(defonce
  ^{:doc "frame-id → flow-id → flow-map. Per-frame so undo / time-travel
          / clear semantics are unambiguous."}
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
  "Register a flow against a frame. Per Spec 013 — flows are frame-
  scoped: their lifecycle, evaluation, undo / time-travel semantics
  all belong to one frame.

  Required keys on the flow map: :id :inputs :output :path.
  Optional: :doc :spec.

  The frame to register against comes from the optional :frame opt;
  default is (frame/current-frame) — usually :rf/default unless
  called inside a (with-frame ...) wrapper or under a frame-provider."
  ([flow] (reg-flow flow {}))
  ([flow {:keys [frame] :as _opts}]
   (validate-flow flow)
   (let [frame-id (or frame (frame/current-frame))
         flow-id  (:id flow)]
     ;; The :flow registrar slot keys on flow-id only — but stamp :frame
     ;; into the metadata so introspection / hot-reload hooks can read
     ;; the owning frame.
     (registrar/register! :flow flow-id (assoc flow :frame frame-id))
     (swap! flows assoc-in [frame-id flow-id] flow)
     ;; Cycle detection on this frame's flows only.
     (try
       (topo-sort (get @flows frame-id))
       (catch #?(:clj Throwable :cljs :default) e
         (swap! flows update frame-id dissoc flow-id)
         (registrar/unregister! :flow flow-id)
         (throw e)))
     (invalidate-topo!)
     flow-id)))

(defn clear-flow
  "Deregister a flow from a frame; dissoc its output path from that
  frame's app-db (only that frame). Frame defaults to (current-frame)."
  ([id] (clear-flow id {}))
  ([id {:keys [frame] :as _opts}]
   (let [frame-id (or frame (frame/current-frame))]
     (when-let [flow (get-in @flows [frame-id id])]
       (let [path (:path flow)]
         (when-let [container (frame/get-frame-db frame-id)]
           (let [cur    (adapter/read-container container)
                 new-db (if (and (vector? path) (seq path))
                          (update-in cur (vec (butlast path)) dissoc (last path))
                          (dissoc cur path))]
             (adapter/replace-container! container new-db)))
         (swap! flows update frame-id dissoc id)
         (swap! last-inputs dissoc [frame-id id])
         ;; Only unregister from the registrar if this was the LAST
         ;; frame holding the flow id — otherwise other frames still
         ;; need the registry slot for hot-reload tracking.
         (when (every? (fn [[_ frame-flows]] (not (contains? frame-flows id)))
                       @flows)
           (registrar/unregister! :flow id))
         (invalidate-topo!)))
     nil)))

;; ---- fx hooks (called from re-frame-2.fx) --------------------------------
;;
;; The :rf.fx/reg-flow / :rf.fx/clear-flow runtime fx receive a {:frame ...}
;; cofx via fx.cljc. Thread the frame through.

(defn reg-flow-fx!
  ([flow]      (reg-flow flow))
  ([flow opts] (reg-flow flow opts)))

(defn clear-flow-fx!
  ([id]      (clear-flow id))
  ([id opts] (clear-flow id opts)))

;; ---- hot-reload invalidation ---------------------------------------------
;;
;; Per Spec 001 §Hot-reload semantics: when a flow re-registers, the
;; per-frame :last-inputs entry MUST clear so the new flow re-evaluates
;; on the next drain regardless of whether inputs changed. Without this,
;; a hot-reloaded flow with a different :output fn but identical recent
;; inputs would silently keep serving the previous result.

(defn- invalidate-flow-on-replace!
  [{:keys [kind id]}]
  (when (= kind :flow)
    (swap! last-inputs
           (fn [m]
             (into {} (remove (fn [[[_ flow-id] _]] (= flow-id id))) m)))))

(defonce ^:private _hot-reload-hook
  (do (registrar/add-replacement-hook! invalidate-flow-on-replace!)
      :installed))

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
  "Per Spec 013 §Drain integration: walk THIS FRAME'S registered flows
  in topological order, dirty-check each one, recompute and write
  if inputs changed. Called from the per-event drain after :db commits.

  Flows are frame-scoped — only flows registered against frame-id run
  here, leaving sibling frames' flows untouched."
  [frame-id]
  (let [container (frame/get-frame-db frame-id)
        flow-map  (get @flows frame-id)]
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
