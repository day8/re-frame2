(ns re-frame.flows
  "Flows — registered, runtime-toggleable computed-state declarations.
  Per Spec 013.

  A flow says: 'when these app-db paths change, run this pure function
  and write the result to that app-db path.' Flows evaluate after every
  event drain in topological order over their static dependency graph.

  Flows are deliberately a NICHE convenience — not a sub replacement,
  not a new dataflow paradigm. Use a sub if the value is consumed by
  views; use a flow only if it must live in app-db for SSR / time-travel
  / inspector reasons.

  ## Artefact (rf2-tfw3, fourth per-feature split per rf2-5vjj Strategy B)

  This namespace ships in `day8/re-frame2-flows`, separate from the
  core artefact (`day8/re-frame2`). The core artefact's `re-frame.core`
  re-exports of `reg-flow` / `clear-flow`, and the `:rf.fx/reg-flow` /
  `:rf.fx/clear-flow` runtime fxs in `re-frame.fx`, look this
  namespace's entry points up via the `re-frame.late-bind` hook table —
  loading this namespace publishes the hooks. Apps that don't register
  any flows don't drag the per-frame flow registry, the topological-
  sort engine, the dirty-check `last-inputs` map, or the post-drain
  `run-flows!` walker onto the classpath."
  (:require [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

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

;; ---- topological sort -----------------------------------------------------

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
  "Kahn's algorithm — pure `loop`/`recur` over immutable state. Returns
  flows in evaluation order; throws `:rf.error/flow-cycle` if the graph
  is cyclic. `ready` is a vector used as a LIFO stack
  (`peek`/`pop`/`conj`); `remaining` is the live id→dep-set map; `order`
  is the accumulating result."
  [flow-map]
  (let [ids   (vec (keys flow-map))
        graph (into {}
                    (map (fn [id]
                           (let [flow (flow-map id)]
                             [id (into #{}
                                       (filter #(and (not= id %)
                                                     (depends-on? flow (flow-map %))))
                                       ids)])))
                    ids)]
    (loop [ready     (filterv #(empty? (graph %)) ids)
           remaining graph
           order     []]
      (if-let [n (peek ready)]
        (let [rem0 (dissoc remaining n)
              [remaining' ready']
              (reduce-kv (fn [[rem rdy] m m-deps]
                           (if-not (contains? m-deps n)
                             [rem rdy]
                             (let [m-deps' (disj m-deps n)]
                               [(assoc rem m m-deps')
                                (cond-> rdy (empty? m-deps') (conj m))])))
                         [rem0 (pop ready)]
                         rem0)]
          (recur ready' remaining' (conj order n)))
        (if (seq remaining)
          (throw (ex-info ":rf.error/flow-cycle"
                          {:cycle (vec (keys remaining))}))
          order)))))

;; Note: `topo-sort` runs on every drain via `run-flows!`. A memo was
;; trialled here and removed (rf2-cd00): the per-frame flow map is tiny
;; (Kahn over a handful of nodes), and a memo whose key is the flow map
;; needs explicit invalidation on every reg-flow / clear-flow anyway.
;; The unmemoised call is the cheapest correct option.

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
   (let [frame-id     (or frame (frame/current-frame))
         flow-id      (:id flow)
         prior-frame  (get @flows frame-id)
         ;; Per rf2-7csri: detect cycles on a PROSPECTIVE flow-map
         ;; BEFORE mutating the atom or the registrar. The earlier
         ;; write-then-rollback path silently deleted the prior
         ;; registration along with the rejected one when a REPLACEMENT
         ;; introduced a cycle — the rollback dissoc'd by flow-id,
         ;; vacating the slot the prior entry was sharing. Now we run
         ;; topo-sort on (prior-frame `assoc` new-entry) up-front; if it
         ;; throws, nothing has been written and the prior registration
         ;; stays intact.
         prospective  (assoc prior-frame flow-id flow)]
     (topo-sort prospective)
     ;; Cycle check passed — commit. The :flow registrar slot keys on
     ;; flow-id only; stamp :frame into the metadata so introspection
     ;; / hot-reload hooks can read the owning frame.
     (registrar/register! :flow flow-id
                          (source-coords/merge-coords
                            (assoc flow :frame frame-id)))
     (swap! flows assoc-in [frame-id flow-id] flow)
     ;; Per Spec 009 §:op-type vocabulary: :rf.flow/registered fires after
     ;; reg-flow successfully completes (including post-cycle-detection).
     ;; Tools observe this to track the flow population over hot reloads /
     ;; toggles. Op-type :flow is the discriminator for the whole flow
     ;; trace stream (per Spec 009 §:op-type vocabulary, §Flow tracing).
     (trace/emit! :flow :rf.flow/registered
                  {:flow-id flow-id
                   :inputs  (:inputs flow)
                   :path    (:path flow)
                   :frame   frame-id})
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
                 ;; rf2-aqt7: when :path is a single-element vector [:k],
                 ;; (butlast [:k]) is () and (update-in cur [] dissoc :k)
                 ;; does NOT dissoc — Clojure's update-in on the empty
                 ;; path falls into (assoc {} nil (apply f val args)),
                 ;; producing {... nil nil}. Special-case length 1 so
                 ;; the leaf is dissoc'd directly.
                 new-db (cond
                          (not (vector? path))         (dissoc cur path)
                          (empty? path)                cur
                          (= 1 (count path))           (dissoc cur (first path))
                          :else                        (update-in cur
                                                                  (vec (butlast path))
                                                                  dissoc
                                                                  (last path)))]
             (adapter/replace-container! container new-db)))
         (swap! flows update frame-id dissoc id)
         (swap! last-inputs dissoc [frame-id id])
         ;; Only unregister from the registrar if this was the LAST
         ;; frame holding the flow id — otherwise other frames still
         ;; need the registry slot for hot-reload tracking.
         (when (every? (fn [[_ frame-flows]] (not (contains? frame-flows id)))
                       @flows)
           (registrar/unregister! :flow id))
         ;; Per Spec 009 §:op-type vocabulary: :rf.flow/cleared fires after
         ;; clear-flow has removed the flow from the per-frame registry
         ;; and dissoc-in'd its output path. Tools observe this to drop
         ;; their per-flow display state.
         (trace/emit! :flow :rf.flow/cleared
                      {:flow-id id
                       :path    path
                       :frame   frame-id})))
     nil)))

;; ---- fx hooks (called from re-frame.fx) --------------------------------
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
  "Evaluate one flow against the given db. Returns the [new-db dirty?] tuple.

  Emits one of the per-flow `:rf.flow/*` traces per call (per Spec 009
  §:op-type vocabulary, §Flow tracing): `:rf.flow/skip` when value-equal
  recompute suppression triggers, `:rf.flow/computed` on a successful
  recompute, or `:rf.flow/failed` when the flow's `:output` fn throws.
  On the failure path the in-flight db is returned unchanged and `dirty?`
  is `false` so downstream flows still walk."
  [frame-id db flow]
  (let [flow-id    (:id flow)
        k          [frame-id flow-id]
        new-inputs (read-inputs db flow)
        old-inputs (get @last-inputs k)]
    (if (= new-inputs old-inputs)
      (do
        ;; Per Spec 009 §:op-type vocabulary: :rf.flow/skip records the
        ;; suppressed recompute (per rf2-719e value-equal recompute
        ;; suppression). Tools use this to surface "flow ran but inputs
        ;; were stable" — distinct from "flow didn't fire at all because
        ;; nothing wrote".
        (trace/emit! :flow :rf.flow/skip
                     {:flow-id flow-id
                      :reason  :inputs-value-equal
                      :frame   frame-id})
        [db false])
      (try
        (let [new-output (apply (:output flow) new-inputs)
              new-db     (assoc-in db (:path flow) new-output)]
          (swap! last-inputs assoc k new-inputs)
          ;; Per Spec 009 §:op-type vocabulary: :rf.flow/computed records
          ;; a successful recompute. :input-values are raw values (not
          ;; hashed) — the trace surface is dev-only and elided in
          ;; production, and downstream tools (10x flow panel) display
          ;; them. Per rf2-719e the dirty-check is =-equality so this
          ;; only fires when inputs actually changed.
          (trace/emit! :flow :rf.flow/computed
                       {:flow-id      flow-id
                        :input-values new-inputs
                        :result       new-output
                        :path         (:path flow)
                        :frame        frame-id})
          [new-db true])
        (catch #?(:clj Throwable :cljs :default) e
          ;; Per Spec 009 §:op-type vocabulary: :rf.flow/failed fires
          ;; when the flow's :output fn throws. last-inputs is NOT
          ;; advanced — so the flow will retry on the next drain rather
          ;; than silently caching a stale-or-missing output. We re-
          ;; throw so the router's outer catch (router.cljc) emits the
          ;; cascade-level :rf.error/flow-eval-exception per Spec 009
          ;; §Error contract; the per-flow `:rf.flow/failed` trace
          ;; emitted here adds the flow-attributed detail tools (10x
          ;; flow panel) consume.
          (trace/emit! :flow :rf.flow/failed
                       {:flow-id flow-id
                        :ex      e
                        :inputs  new-inputs
                        :frame   frame-id})
          (throw e))))))

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

;; ---- last-inputs reset (test-fixture support) ----------------------------

(defn reset-last-inputs!
  "Test-only: clear the dirty-check `last-inputs` map. The flows
  reset-runtime fixture uses this to drop stale per-flow state between
  tests so re-registration does not silently no-op when new-inputs
  =-equal a stale entry from a sibling test. Per rf2-tfw3 (the fourth
  per-feature split): this is published through the late-bind hook
  table so `re-frame.test-support`'s reset-runtime fixture can call it
  without statically requiring `re-frame.flows`."
  []
  (reset! last-inputs {})
  nil)

(defn reset-flows!
  "Test-only: clear the per-frame flow registry. Pairs with
  `reset-last-inputs!` for the test-fixture reset bracket. Per
  rf2-tfw3 — exposed via the late-bind hook table so
  `re-frame.test-support` can reset state without a static require on
  this namespace."
  []
  (reset! flows {})
  nil)

;; ---- late-bind hook registration ------------------------------------------
;;
;; re-frame.core, re-frame.fx, re-frame.router and re-frame.test-support
;; need to call into flows but per rf2-tfw3 ship in the core artefact
;; — they cannot `:require` this namespace because the flows artefact
;; is optional (apps that don't register flows don't carry it). Publish
;; entry points through the late-bind hook registry; consumers look the
;; fns up at call time. See re-frame.late-bind.

(late-bind/set-fn! :flows/reg-flow         reg-flow)
(late-bind/set-fn! :flows/clear-flow       clear-flow)
(late-bind/set-fn! :flows/reg-flow-fx!     reg-flow-fx!)
(late-bind/set-fn! :flows/clear-flow-fx!   clear-flow-fx!)
(late-bind/set-fn! :flows/run-flows!       run-flows!)
(late-bind/set-fn! :flows/reset-last-inputs! reset-last-inputs!)
(late-bind/set-fn! :flows/reset-flows!     reset-flows!)
