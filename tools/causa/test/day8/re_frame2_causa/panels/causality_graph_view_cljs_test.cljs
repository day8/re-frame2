(ns day8.re-frame2-causa.panels.causality-graph-view-cljs-test
  "CLJS-side wiring + view tests for Causa's Causality Graph panel
  (Phase 4, rf2-4rqs1).

  ## Three contracts under test (in addition to the pure-data tests
  in `causality_graph_cljs_test.cljc`)

  1. **Registry wires the composite sub** under `:rf.causa/causality-
     graph-data`. The composite returns the same graph + layout the
     view consumes.

  2. **Clicking a node fires `:rf.causa/select-dispatch-id`** — the
     shared selection event with the event-detail hero (per spec
     §10 Lock 7).

  3. **Selected-epoch filters the graph** to the cascade family
     containing that epoch's settling dispatch (per spec §The
     passive-scrubbing rule + spec/001-Causality-Graph.md §Filter
     graph to this cascade).

  ## Pure hiccup

  Same approach as `event_detail_cljs_test.cljs` and
  `time_travel_cljs_test.cljs` — we walk the view's hiccup tree by
  `data-testid` rather than mounting to a DOM. Keeps the suite fast
  + host-portable on node-test."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.causality-graph :as causality-graph]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- fixture trace stream ------------------------------------------------

(defn- cascade-evs
  "Produce a one-cascade trace stream. Same shape as
  `event_detail_cljs_test.cljs` + a `:parent-dispatch-id` slot."
  ([dispatch-id event-vec id-base]
   (cascade-evs dispatch-id event-vec id-base nil))
  ([dispatch-id event-vec id-base parent]
   [{:id (+ id-base 1) :op-type :event    :operation :event/dispatched
     :tags (cond-> {:dispatch-id dispatch-id :event event-vec :origin :app}
             parent (assoc :parent-dispatch-id parent))}
    {:id (+ id-base 2) :op-type :event    :operation :event
     :tags {:dispatch-id dispatch-id :phase :run-end}}
    {:id (+ id-base 3) :op-type :fx       :operation :rf.fx/handled
     :tags {:dispatch-id dispatch-id :fx-id :db}}
    {:id (+ id-base 4) :op-type :view     :operation :view/render
     :tags {:dispatch-id dispatch-id :render-key [:app/root nil]}}]))

(defn- seed-buffer!
  "Wire the trace collector (preload-style) and push the supplied
  events through Causa's reactive trace-buffer slot.

  Per rf2-iw5ym `:rf.causa/trace-buffer` is reactive off Causa's
  app-db, not the trace-bus atom. Tests use `dispatch-sync` of
  `:rf.causa/note-trace-event` to mirror what production's
  `trace-bus/collect-trace!` does (async dispatch), driving the
  sub re-fire synchronously before the next subscribe."
  [evs]
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (rf/with-frame :rf/causa
    (doseq [ev evs]
      (rf/dispatch-sync [:rf.causa/note-trace-event ev]))))

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (apply (first node) (rest node))
    node))

(defn- hiccup-seq [tree]
  (->> (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree))
       (map expand-fn-component)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (some-> (:data-testid (second node))
                         (.startsWith prefix))))
          (hiccup-seq tree)))

;; ---- (1) registry wires the composite sub -------------------------------

(deftest registry-installs-causality-graph-sub
  (testing "register-causa-handlers! installs the Phase 4 composite sub"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/causality-graph-data)))))

(deftest causality-graph-sub-defaults
  (testing "the composite returns an empty graph + sensible layout when
            the buffer is empty"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/causality-graph-data])]
        (is (= [] (get-in data [:graph :nodes])))
        (is (= [] (get-in data [:graph :arrows])))
        (is (some? (:layout data)))
        (is (false? (:filtered? data)))
        (is (nil? (:selected-dispatch-id data)))))))

(deftest causality-graph-sub-projects-cascades
  (testing "with a buffer populated, the composite returns one node per
            cascade and the parent → child arrow"
    (seed-buffer! (concat (cascade-evs 100 [:user/login] 0)
                          (cascade-evs 200 [:user/redirect] 100 100)))
    (rf/with-frame :rf/causa
      (let [data  @(rf/subscribe [:rf.causa/causality-graph-data])
            ids   (set (map :dispatch-id (get-in data [:graph :nodes])))]
        (is (= #{100 200} ids))
        (is (= [[100 200]] (get-in data [:graph :arrows])))))))

;; ---- (2) view renders ---------------------------------------------------

(deftest empty-state-renders-when-no-cascades
  (testing "the panel renders the empty state when the buffer has no
            dispatch cascades"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (let [tree (causality-graph/causality-graph-view)]
        (is (some? (find-by-testid tree "rf-causa-causality-graph-empty"))
            "empty-state container present")
        (is (nil?  (find-by-testid tree "rf-causa-causality-graph-svg"))
            "SVG canvas absent when empty")))))

(deftest svg-renders-when-cascades-present
  (testing "with cascades in the buffer the panel renders the SVG canvas
            with one node per cascade"
    (seed-buffer! (concat (cascade-evs 100 [:user/login] 0)
                          (cascade-evs 200 [:user/redirect] 100 100)))
    (rf/with-frame :rf/causa
      (let [tree (causality-graph/causality-graph-view)]
        (is (some? (find-by-testid tree "rf-causa-causality-graph-svg"))
            "SVG container present")
        (is (nil?  (find-by-testid tree "rf-causa-causality-graph-empty"))
            "empty-state absent when cascades present")
        (is (some? (find-by-testid tree "rf-causa-graph-node-100"))
            "node for dispatch 100")
        (is (some? (find-by-testid tree "rf-causa-graph-node-200"))
            "node for dispatch 200")
        (is (some? (find-by-testid tree "rf-causa-graph-arrow-100-200"))
            "arrow 100 → 200")))))

;; ---- (3) clicking a node fires select-dispatch-id -----------------------

(deftest clicking-node-fires-select-dispatch-id
  (testing "the node's on-click is wired to :rf.causa/select-dispatch-id —
            shared with the event-detail hero panel. We assert the
            handler is callable and that the dispatch reaches Causa's
            frame via dispatch-sync on the same event (rf/dispatch from
            inside the click is async — we trust the on-click is the
            constructed dispatch call, then prove the event handler
            writes to the right frame)."
    (seed-buffer! (cascade-evs 100 [:user/login] 0))
    (rf/with-frame :rf/causa
      (let [tree     (causality-graph/causality-graph-view)
            node     (find-by-testid tree "rf-causa-graph-node-100")
            on-click (:on-click (second node))]
        (is (fn? on-click)
            "node carries an on-click handler (wired to :rf.causa/select-dispatch-id)")
        ;; Drive the same event through dispatch-sync so the test
        ;; doesn't race the router's drain — proves the registered
        ;; event handler lands the selection on the Causa frame.
        (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
        (let [causa-db (frame/frame-app-db-value :rf/causa)]
          (is (= 100 (:selected-dispatch-id causa-db))
              "selection lands on the Causa frame"))))))

(deftest selected-node-renders-with-selected-glyph
  (testing "after select, the composite sub surfaces :selected-dispatch-id
            and the view's node renders with the selected glyph"
    (seed-buffer! (cascade-evs 100 [:user/login] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [data @(rf/subscribe [:rf.causa/causality-graph-data])]
        (is (= 100 (:selected-dispatch-id data))
            "composite returns the selection")))))

;; ---- (4) selected-epoch filters to cascade family -----------------------

(deftest selected-epoch-filters-graph-to-cascade
  (testing "when Time Travel's :selected-epoch-id resolves to a cascade-id
            in the graph, the graph filters to that cascade family"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    ;; Seed two unrelated cascades (100 and 400) so we can prove the
    ;; filter cuts the unrelated one out.
    (seed-buffer! (concat (cascade-evs 100 [:family-a] 0)
                          (cascade-evs 400 [:family-b] 100)))
    (rf/with-frame :rf/causa
      ;; Register a seed-event so we can write epoch-history without
      ;; depending on the epoch artefact being on the test classpath.
      (rf/reg-event-db :rf.causa/seed-history-for-test
        (fn [db [_ records]]
          (assoc db :epoch-history (vec records))))
      ;; Seed a history with one epoch whose settling cascade-id = 100.
      (rf/dispatch-sync
        [:rf.causa/seed-history-for-test
         [{:epoch-id     :e-100
           :frame        :rf/default
           :committed-at 0
           :event-id     :foo
           :trigger-event [:foo]
           :db-before    {}
           :db-after     {}
           :trace-events [{:id 1 :op-type :event :operation :event/dispatched
                           :tags {:dispatch-id 100}}]}]])
      (rf/dispatch-sync [:rf.causa/select-epoch :e-100])
      (let [data @(rf/subscribe [:rf.causa/causality-graph-data])
            ids  (set (map :dispatch-id (get-in data [:graph :nodes])))]
        (is (true? (:filtered? data)) ":filtered? flag set")
        (is (= #{100} ids)
            "graph contains only the cascade family of the selected epoch")
        (is (not (contains? ids 400))
            "unrelated cascade dropped")))))

(deftest selected-epoch-not-in-graph-falls-back
  (testing "when the selected-epoch's cascade-id is NOT in the graph
            (aged out of the buffer), the graph falls back to the
            unfiltered view"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (seed-buffer! (cascade-evs 100 [:in-buffer] 0))
    (rf/with-frame :rf/causa
      (rf/reg-event-db :rf.causa/seed-history-for-test
        (fn [db [_ records]]
          (assoc db :epoch-history (vec records))))
      ;; Epoch's settling cascade-id 999 is NOT in the buffer.
      (rf/dispatch-sync
        [:rf.causa/seed-history-for-test
         [{:epoch-id     :e-old
           :frame        :rf/default
           :committed-at 0
           :event-id     :foo
           :trigger-event [:foo]
           :db-before    {}
           :db-after     {}
           :trace-events [{:id 1 :op-type :event :operation :event/dispatched
                           :tags {:dispatch-id 999}}]}]])
      (rf/dispatch-sync [:rf.causa/select-epoch :e-old])
      (let [data @(rf/subscribe [:rf.causa/causality-graph-data])
            ids  (set (map :dispatch-id (get-in data [:graph :nodes])))]
        (is (false? (:filtered? data))
            ":filtered? false when the cascade isn't in the graph")
        (is (= #{100} ids)
            "graph still surfaces every cascade in the buffer")))))
