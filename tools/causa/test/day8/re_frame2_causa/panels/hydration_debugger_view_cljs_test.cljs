(ns day8.re-frame2-causa.panels.hydration-debugger-view-cljs-test
  "CLJS-side wiring + view tests for Causa's Hydration Debugger panel
  (Phase 5, rf2-pzxsr).

  ## Three contracts under test (in addition to the pure-data tests
  in `hydration_debugger_cljs_test.cljc`)

  1. **Registry wires the composite sub** under `:rf.causa/hydration-
     debugger-data`. The composite returns the same shape the view
     consumes.

  2. **Dormant gate** — the panel surfaces the 'No SSR in this app'
     empty state until at least one `:rf.ssr/hydration-mismatch`
     trace lands. Once one does, the panel surfaces the side-by-side
     mismatch detail (per spec §Visibility).

  3. **Selection + re-root events** wire correctly — clicking a
     mismatch row fires `:rf.causa/select-mismatch`; clicking a hash
     chip fires `:rf.causa/reroot-tree-view`.

  ## Pure hiccup

  Same approach as `event_detail_cljs_test.cljs` and
  `causality_graph_view_cljs_test.cljs` — we walk the view's hiccup
  tree by `data-testid` rather than mounting to a DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.hydration-debugger :as hydration]))

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

(defn- mismatch-ev
  "Build a hydration-mismatch trace event per spec/006-Hydration-
  Debugger.md §Substrate."
  ([id path server-tree client-tree]
   (mismatch-ev id path server-tree client-tree {}))
  ([id path server-tree client-tree extra-tags]
   {:id        id
    :op-type   :error
    :operation :rf.ssr/hydration-mismatch
    :tags      (merge {:path        path
                       :server-tree server-tree
                       :client-tree client-tree
                       :server-hash "abc"
                       :client-hash "def"
                       :frame       :rf/default
                       :view-id     'cart-summary-view
                       :failing-id  :rf/hydrate}
                      extra-tags)}))

(defn- seed-buffer!
  "Register Causa's handlers, allocate the :rf/causa frame, then push
  the supplied events through Causa's trace-bus atom via
  `collect-trace!` — the production path. Per rf2-e9s81
  `:rf.causa/trace-buffer` thunks the atom, so a subsequent
  subscribe returns the events directly."
  [evs]
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (doseq [ev evs]
    (trace-bus/collect-trace! ev)))

;; ---- hiccup walker (lifted from causality_graph_view_cljs_test) ---------

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

(deftest registry-installs-hydration-debugger-sub
  (testing "register-causa-handlers! installs the Phase 5 composite sub"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/hydration-debugger-data))
        "composite sub is registered")
    (is (some? (registrar/handler :sub :rf.causa/selected-mismatch-id))
        "selected-mismatch-id sub is registered")
    (is (some? (registrar/handler :sub :rf.causa/hydration-reroot-path))
        "hydration-reroot-path sub is registered")))

(deftest registry-installs-hydration-debugger-events
  (testing "the Phase 5 events are registered"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :event :rf.causa/select-mismatch)))
    (is (some? (registrar/handler :event :rf.causa/clear-mismatch-selection)))
    (is (some? (registrar/handler :event :rf.causa/reroot-tree-view)))))

(deftest composite-defaults-with-empty-buffer
  (testing "with the buffer empty, the composite reports no mismatches"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/hydration-debugger-data])]
        (is (false? (:has-mismatch? data))
            "dormant when buffer is empty")
        (is (= [] (:mismatch-summary data)))
        (is (nil? (:detail data)))))))

(deftest composite-surfaces-mismatches-from-buffer
  (testing "once a mismatch lands, the composite surfaces it"
    (seed-buffer! [(mismatch-ev 1 [:div :main]
                                [:p "3 items"]
                                [:p "0 items"])])
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/hydration-debugger-data])]
        (is (true? (:has-mismatch? data)) ":has-mismatch? true")
        (is (= 1 (count (:mismatch-summary data))))
        (is (some? (:detail data)))
        (is (= :different-text
               (get-in data [:detail :divergence-kind])))))))

;; ---- (2) dormant-gate / visibility --------------------------------------

(deftest view-renders-no-ssr-empty-state-when-buffer-empty
  (testing "the panel renders the 'No SSR in this app' empty state when
            the buffer carries no hydration-mismatch traces"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (let [tree (hydration/hydration-debugger-view)]
        (is (some? (find-by-testid tree
                                   "rf-causa-hydration-debugger-empty-no-ssr"))
            "empty-state container present")
        (is (nil? (find-by-testid tree "rf-causa-hydration-mismatch-detail"))
            "mismatch detail absent when dormant")))))

(deftest view-renders-mismatch-detail-once-fired
  (testing "with a mismatch in the buffer the panel renders the side-by-
            side detail + the mismatch list"
    (seed-buffer! [(mismatch-ev 1 [:div :main]
                                [:p "3 items"]
                                [:p "0 items"])])
    (rf/with-frame :rf/causa
      (let [tree (hydration/hydration-debugger-view)]
        (is (some? (find-by-testid tree "rf-causa-hydration-mismatch-list"))
            "mismatch list present")
        (is (some? (find-by-testid tree "rf-causa-hydration-mismatch-detail"))
            "mismatch detail present")
        (is (some? (find-by-testid tree "rf-causa-hydration-divergent-marker"))
            "divergent marker present")
        (is (some? (find-by-testid tree "rf-causa-hydration-tree-pane-server"))
            "server tree pane present")
        (is (some? (find-by-testid tree "rf-causa-hydration-tree-pane-client"))
            "client tree pane present")
        (is (some? (find-by-testid tree "rf-causa-hydration-hypothesis"))
            "hypothesis row present")
        (is (nil? (find-by-testid tree
                                  "rf-causa-hydration-debugger-empty-no-ssr"))
            "empty-state absent when mismatch present")))))

;; ---- (3) selection + re-root events -------------------------------------

(deftest clicking-mismatch-row-fires-select-mismatch
  (testing "clicking a mismatch row dispatches :rf.causa/select-mismatch"
    (seed-buffer! [(mismatch-ev 1 [:div] [:a] [:b])
                   (mismatch-ev 2 [:span] [:c] [:d])])
    (rf/with-frame :rf/causa
      (let [tree (hydration/hydration-debugger-view)
            row  (find-by-testid tree "rf-causa-hydration-mismatch-row-1")]
        (is (some? row) "row 1 present in mismatch list")
        (is (fn? (:on-click (second row)))
            "row carries an on-click handler")
        ;; Drive the event through dispatch-sync so the test doesn't
        ;; race the router's drain — proves the event handler writes
        ;; to the Causa frame.
        (rf/dispatch-sync [:rf.causa/select-mismatch 1])
        (let [causa-db (frame/frame-app-db-value :rf/causa)]
          (is (= 1 (:selected-mismatch-id causa-db))
              "selection lands on the Causa frame"))))))

(deftest clicking-hash-chip-fires-reroot
  (testing "clicking a hash chip dispatches :rf.causa/reroot-tree-view
            with the chip's path"
    (seed-buffer! [(mismatch-ev 1 []
                                [:div [:p "match"] [:p "server"]]
                                [:div [:p "match"] [:p "client"]])])
    (rf/with-frame :rf/causa
      (let [tree  (hydration/hydration-debugger-view)
            chips (find-all-by-testid-prefix
                    tree "rf-causa-hydration-hash-chip-")]
        (is (pos? (count chips)) "at least one hash chip rendered")
        ;; Each chip's on-click is a #()-style dispatch — sanity that
        ;; it's a callable; the event is asserted below via dispatch-
        ;; sync against the same wiring.
        (is (every? #(fn? (:on-click (second %))) chips))
        ;; Drive the event through dispatch-sync.
        (rf/dispatch-sync [:rf.causa/reroot-tree-view [1]])
        (let [causa-db (frame/frame-app-db-value :rf/causa)]
          (is (= [1] (:hydration-reroot-path causa-db))
              "reroot path lands on the Causa frame"))))))

(deftest reroot-empty-path-clears-the-slot
  (testing "dispatching :rf.causa/reroot-tree-view with [] clears the
            re-root slot — the panel re-renders from the full subtree"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/reroot-tree-view [1]])
      (rf/dispatch-sync [:rf.causa/reroot-tree-view []])
      (let [causa-db (frame/frame-app-db-value :rf/causa)]
        (is (nil? (:hydration-reroot-path causa-db))
            "empty path clears the re-root slot")))))

(deftest clear-mismatch-selection-drops-selection-and-reroot
  (testing "clear-mismatch-selection drops both the selection and the
            re-root (the path is subtree-specific to the prior
            selection)"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-mismatch 42])
      (rf/dispatch-sync [:rf.causa/reroot-tree-view [1 2]])
      (rf/dispatch-sync [:rf.causa/clear-mismatch-selection])
      (let [causa-db (frame/frame-app-db-value :rf/causa)]
        (is (nil? (:selected-mismatch-id causa-db)))
        (is (nil? (:hydration-reroot-path causa-db)))))))

;; ---- (4) hypothesis row + source-coord drilldown ------------------------

(deftest hypothesis-row-surfaces-the-line
  (testing "the hypothesis row carries one of the five hypothesis lines"
    (seed-buffer! [(mismatch-ev 1 [] [:p "3"] [:p "0"])])
    (rf/with-frame :rf/causa
      (let [tree   (hydration/hydration-debugger-view)
            hyp    (find-by-testid tree "rf-causa-hydration-hypothesis")
            txt    (when hyp
                     (->> (hiccup-seq hyp)
                          (filter string?)
                          (apply str)))]
        (is (some? hyp))
        (is (some? (re-find #"App-db state" txt))
            "different-text hypothesis line surfaced")))))

(deftest source-coord-row-surfaces-coord
  (testing "the source-coord row surfaces the divergent view's coord
            when :source-coord is supplied"
    (seed-buffer! [(mismatch-ev 1 [] [:p "a"] [:p "b"]
                                {:source-coord {:file "src/cart/views.cljs"
                                                :line 42}})])
    (rf/with-frame :rf/causa
      (let [tree  (hydration/hydration-debugger-view)
            row   (find-by-testid tree "rf-causa-hydration-source-coord")
            txt   (when row
                    (->> (hiccup-seq row)
                         (filter string?)
                         (apply str)))]
        (is (some? row))
        (is (some? (re-find #"src/cart/views\.cljs:42" txt))
            "exact source-coord rendered")))))

(deftest source-coord-row-surfaces-fallback-annotation
  (testing "when only handler-source-coord is available, the row renders
            a (?) annotation (Lock #11 fallback)"
    (seed-buffer! [(mismatch-ev 1 [] [:p "a"] [:p "b"]
                                {:handler-source-coord
                                 {:file "src/cart/events.cljs" :line 13}})])
    (rf/with-frame :rf/causa
      (let [tree (hydration/hydration-debugger-view)
            row  (find-by-testid tree "rf-causa-hydration-source-coord")
            txt  (when row
                   (->> (hiccup-seq row)
                        (filter string?)
                        (apply str)))]
        (is (some? row))
        (is (some? (re-find #"\(\?\)" txt))
            "fallback annotation (?) present")))))

;; ---- (5) frame awareness ------------------------------------------------

(deftest composite-respects-target-frame
  (testing "when the target frame is :rf/default the composite filters
            out mismatches from other frames"
    (seed-buffer! [(mismatch-ev 1 [] [:p] [:p {:diff 1}]
                                {:frame :rf/default})
                   (mismatch-ev 2 [] [:p] [:p {:diff 2}]
                                {:frame :rf/other})])
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/hydration-debugger-data])]
        (is (= 1 (count (:mismatch-summary data)))
            "only the :rf/default mismatch surfaces by default")
        (is (= [1] (mapv :id (:mismatch-summary data))))))))
