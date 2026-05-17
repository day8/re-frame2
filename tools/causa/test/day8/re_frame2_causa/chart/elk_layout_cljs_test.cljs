(ns day8.re-frame2-causa.chart.elk-layout-cljs-test
  "CLJS tests for the ELK adapter (rf2-m7co9 Phase 4).

  Two surfaces under test:

    1. **Pure adapters** — `->elk-graph` (definition → ELK JSON) and
       `elk-result->chart-layout` (ELK output → chart-layout shape).
       Both run without ELK present.

    2. **Lazy loader fallback** — `ensure-elk!` rolls into the
       `:failed` state when no `js/window.ELK` stub is present and
       `js/import` is unavailable (the node-test rig). The
       `layout-or-fallback` fn then returns the layered fallback so
       the panel always has a renderable graph.

  Tests are CLJS-only (`.cljs`, `_cljs_test`) because the loader uses
  `js/window` and `js/import`. The pure adapter helpers happen to be
  CLJC-friendly but the loader pulls them into a CLJS-only consumer
  so we colocate the test."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-causa.chart.layout :as layout]
            [day8.re-frame2-causa.chart.elk-layout :as elk-layout]))

;; ---- fixtures -----------------------------------------------------------

(def small-machine
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :success :err :failed}}
             :success {:final? true}
             :failed  {:final? true}}})

(def compound-machine
  {:initial :unauth
   :states  {:unauth        {:on {:login :authenticated}}
             :authenticated {:initial :browsing
                             :states  {:browsing {:on {:checkout :paying}}
                                       :paying   {:on {:done :browsing}}}
                             :on      {:logout :unauth}}}})

(use-fixtures :each
  (fn [test-fn]
    (elk-layout/reset-elk-state-for-test!)
    (elk-layout/reset-cache-for-test!)
    (test-fn)
    (elk-layout/reset-elk-state-for-test!)
    (elk-layout/reset-cache-for-test!)))

;; ---- ->elk-graph (pure) ------------------------------------------------

(deftest ->elk-graph-shape
  (let [g (elk-layout/->elk-graph small-machine :tb)]
    (is (= "root" (:id g)))
    (is (= "layered" (get-in g [:layoutOptions "elk.algorithm"])))
    (is (= "DOWN"    (get-in g [:layoutOptions "elk.direction"])))
    (is (vector? (:children g)))
    (is (vector? (:edges g)))))

(deftest ->elk-graph-direction-switches-on-axis
  (is (= "DOWN"  (get-in (elk-layout/->elk-graph small-machine :tb)
                         [:layoutOptions "elk.direction"])))
  (is (= "RIGHT" (get-in (elk-layout/->elk-graph small-machine :lr)
                         [:layoutOptions "elk.direction"]))))

(deftest ->elk-graph-one-child-per-state
  (let [g (elk-layout/->elk-graph small-machine :tb)]
    (is (= 4 (count (:children g)))
        "four states → four ELK children")
    (is (every? string? (map :id (:children g))))
    (is (every? pos? (map :width  (:children g))))
    (is (every? pos? (map :height (:children g))))))

(deftest ->elk-graph-edge-per-transition
  (let [g (elk-layout/->elk-graph small-machine :tb)]
    (is (= 3 (count (:edges g)))
        "three transitions: :start :ok :err")
    (doseq [e (:edges g)]
      (is (string? (:id e)))
      (is (= 1 (count (:sources e))))
      (is (= 1 (count (:targets e))))
      (is (every? string? (:sources e)))
      (is (every? string? (:targets e))))))

(deftest ->elk-graph-edge-source-target-ids-match-children
  (let [g          (elk-layout/->elk-graph small-machine :tb)
        child-ids  (set (map :id (:children g)))
        edge-ends  (set (mapcat (fn [e] (concat (:sources e) (:targets e)))
                                (:edges g)))]
    (is (every? child-ids edge-ends)
        "every edge end-point is a child id")))

(deftest ->elk-graph-compound-children-flat-in-v1
  (let [g     (elk-layout/->elk-graph compound-machine :tb)
        ids   (set (map :id (:children g)))]
    (is (contains? ids (layout/node-id [:unauth])))
    (is (contains? ids (layout/node-id [:authenticated])))
    (is (contains? ids (layout/node-id [:authenticated :browsing])))
    (is (contains? ids (layout/node-id [:authenticated :paying]))
        "v1 projects compound children flat; hierarchical containment
         is a follow-on")))

(deftest ->elk-graph-edge-labels-include-event
  (let [g     (elk-layout/->elk-graph small-machine :tb)
        labels (set (mapcat (fn [e] (map :text (:labels e))) (:edges g)))]
    (is (contains? labels "start"))
    (is (contains? labels "ok"))
    (is (contains? labels "err"))))

;; ---- elk-result->chart-layout (pure) -----------------------------------

(deftest elk-result->chart-layout-uses-positions-from-elk
  (testing "ELK's per-child x/y/width/height land on the corresponding
            chart-layout node"
    (let [;; Hand-craft an ELK result for the small-machine. The id
          ;; shape mirrors what ->elk-graph would have minted.
          id-idle    (layout/node-id [:idle])
          id-loading (layout/node-id [:loading])
          id-success (layout/node-id [:success])
          id-failed  (layout/node-id [:failed])
          elk-result {:id "root"
                      :children [{:id id-idle    :x 100 :y 50  :width 140 :height 48}
                                 {:id id-loading :x 100 :y 200 :width 140 :height 48}
                                 {:id id-success :x 30  :y 350 :width 140 :height 48}
                                 {:id id-failed  :x 170 :y 350 :width 140 :height 48}]
                      :edges []
                      :width 400
                      :height 500}
          chart      (elk-layout/elk-result->chart-layout small-machine elk-result)
          by-path    (into {} (map (juxt :path identity)) (:nodes chart))]
      (is (= 100 (-> by-path (get [:idle]) :x)))
      (is (= 200 (-> by-path (get [:loading]) :y)))
      (is (= 400 (:width chart)))
      (is (= 500 (:height chart)))
      (is (string? (:initial-id chart))
          "initial-id is set when :initial is declared"))))

(deftest elk-result->chart-layout-uses-elk-section-bend-points
  (testing "ELK edge sections produce a multi-point :points vector so
            orthogonal routing renders through chart/svg's polyline path"
    (let [id-idle    (layout/node-id [:idle])
          id-loading (layout/node-id [:loading])
          elk-result {:id "root"
                      :children [{:id id-idle    :x 100 :y 50  :width 140 :height 48}
                                 {:id id-loading :x 100 :y 200 :width 140 :height 48}
                                 {:id (layout/node-id [:success]) :x 30  :y 350 :width 140 :height 48}
                                 {:id (layout/node-id [:failed])  :x 170 :y 350 :width 140 :height 48}]
                      :edges [{:id (str "e0-" id-idle "-" id-loading)
                               :sources [id-idle]
                               :targets [id-loading]
                               :sections [{:startPoint {:x 170 :y 98}
                                           :endPoint   {:x 170 :y 200}
                                           :bendPoints [{:x 170 :y 150}]}]}]
                      :width 400
                      :height 500}
          chart      (elk-layout/elk-result->chart-layout small-machine elk-result)
          edge       (first (filter (fn [e] (and (= [:idle]    (:from e))
                                                 (= [:loading] (:to e))))
                                    (:edges chart)))]
      (is (some? edge))
      (is (= 3 (count (:points edge)))
          "section with one bend point → three points (start, bend, end)")
      (is (= [170 98]  (first (:points edge))))
      (is (= [170 150] (second (:points edge))))
      (is (= [170 200] (last (:points edge)))))))

(deftest elk-result->chart-layout-preserves-node-metadata
  (testing "compound? / final? / label / path / tags survive the
            adapter so chart/svg renders the same affordances"
    (let [id-unauth (layout/node-id [:unauth])
          id-auth   (layout/node-id [:authenticated])
          id-browse (layout/node-id [:authenticated :browsing])
          id-pay    (layout/node-id [:authenticated :paying])
          elk-result {:id "root"
                      :children [{:id id-unauth :x 0 :y 0   :width 140 :height 48}
                                 {:id id-auth   :x 0 :y 100 :width 140 :height 48}
                                 {:id id-browse :x 0 :y 200 :width 140 :height 48}
                                 {:id id-pay    :x 0 :y 300 :width 140 :height 48}]
                      :edges []
                      :width 200
                      :height 400}
          chart      (elk-layout/elk-result->chart-layout compound-machine elk-result)
          by-path    (into {} (map (juxt :path identity)) (:nodes chart))]
      (is (true? (-> by-path (get [:authenticated]) :compound?))
          ":authenticated retains its :compound? flag")
      (is (= "browsing" (-> by-path (get [:authenticated :browsing]) :label))))))

(deftest elk-result->chart-layout-fallback-when-elk-skips-a-child
  (testing "if ELK omits a child for any reason the adapter still
            renders the node at (0,0) so the SVG never panics"
    (let [elk-result {:id "root"
                      :children []
                      :edges []
                      :width 200
                      :height 200}
          chart      (elk-layout/elk-result->chart-layout small-machine elk-result)]
      (is (= 4 (count (:nodes chart))))
      (doseq [n (:nodes chart)]
        (is (integer? (:x n)))
        (is (integer? (:y n)))
        (is (string? (:node-id n)))))))

;; ---- loader fallback ----------------------------------------------------

(deftest ensure-elk-rolls-to-failed-without-stub-or-import
  (testing "with no js/window.ELK stub and no working js/import the
            loader rolls into :failed state and the callback fires
            with nil — the panel then falls back to layered"
    (elk-layout/reset-elk-state-for-test!)
    (let [called (atom nil)
          ran?   (atom false)]
      (elk-layout/ensure-elk! (fn [inst]
                                (reset! called inst)
                                (reset! ran? true)))
      (is (= :failed (elk-layout/elk-status)))
      (is (true? @ran?))
      (is (nil? @called)))))

(deftest ensure-elk-idempotent-after-failed
  (testing "second ensure-elk! fast-paths the callback via the :failed
            cache — no second import attempt"
    (elk-layout/reset-elk-state-for-test!)
    (elk-layout/ensure-elk! (fn [_] nil))
    (let [count2 (atom 0)]
      (elk-layout/ensure-elk! (fn [_] (swap! count2 inc)))
      (is (= 1 @count2)))))

(deftest layout-or-fallback-returns-layered-when-elk-unavailable
  (testing "when ELK isn't ready, layout-or-fallback returns the layered
            engine's output — the panel always has a renderable graph"
    (elk-layout/reset-elk-state-for-test!)
    (elk-layout/reset-cache-for-test!)
    (let [got (elk-layout/layout-or-fallback small-machine :tb)]
      ;; Same shape the layered fallback emits — coll/seq, not strictly
      ;; vector (`place-nodes` builds via mapcat).
      (is (sequential? (:nodes got)))
      (is (sequential? (:edges got)))
      (is (= 4 (count (:nodes got))))
      (is (every? integer? (map :x (:nodes got))))
      (is (every? integer? (map :y (:nodes got)))))))

(deftest cached-layout-returns-nil-on-miss
  (elk-layout/reset-cache-for-test!)
  (is (nil? (elk-layout/cached-layout small-machine :tb))))

(deftest compute-layout-fires-nil-when-elk-not-ready
  (testing "compute-layout! delivers nil sync when ELK isn't loaded so
            the caller falls through to the layered fallback"
    (elk-layout/reset-elk-state-for-test!)
    (elk-layout/reset-cache-for-test!)
    (let [called (atom :unset)]
      (elk-layout/compute-layout! small-machine :tb
                                  (fn [x] (reset! called x)))
      (is (nil? @called)))))

;; ---- loaded-path with a synchronous stub --------------------------------
;;
;; A minimal `js/window.ELK` stub exercises the ready-path adapter
;; without bundling ELK into the test rig. The stub's .layout returns
;; a thenable that resolves to a deterministic positioned graph.

(defn- mk-elk-stub
  "Construct an ELK-shaped object whose .layout resolves the input
  graph by assigning incremental coordinates per child. Suitable as a
  drop-in for the lazy loader's `js/window.ELK` short-circuit path."
  []
  (let [Ctor (fn []
               (this-as this
                 (set! (.-layout this)
                       (fn [graph]
                         (let [graph-clj   (js->clj graph :keywordize-keys true)
                               children    (:children graph-clj)
                               positioned  (vec
                                             (map-indexed
                                               (fn [idx c]
                                                 (assoc c
                                                   :x (* (mod idx 3) 200)
                                                   :y (* (quot idx 3) 100)))
                                               children))
                               edges       (mapv (fn [e]
                                                   (assoc e :sections
                                                          [{:startPoint {:x 100 :y 100}
                                                            :endPoint   {:x 200 :y 200}}]))
                                                 (:edges graph-clj))
                               result      (clj->js
                                             (assoc graph-clj
                                               :children positioned
                                               :edges    edges
                                               :width    800
                                               :height   400))]
                           (js/Promise.resolve result))))
                 this))]
    Ctor))

(deftest ensure-elk-ready-when-window-stub-present
  (testing "with a js/window.ELK stub, ensure-elk! flips into :ready
            and the callback fires with the constructed instance.

            Browser-only: node-test rigs don't define js/window so the
            stub path is unreachable; the test is a no-op there. The
            real ready-path is exercised by the elk_integration browser
            test."
    (when (exists? js/window)
      (elk-layout/reset-elk-state-for-test!)
      (set! (.-ELK js/window) (mk-elk-stub))
      (let [called (atom nil)]
        (try
          (elk-layout/ensure-elk! (fn [inst] (reset! called inst)))
          (is (= :ready (elk-layout/elk-status)))
          (is (some? @called))
          (finally
            (set! (.-ELK js/window) js/undefined)
            (elk-layout/reset-elk-state-for-test!)))))))
