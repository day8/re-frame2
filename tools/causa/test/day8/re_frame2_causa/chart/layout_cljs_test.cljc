(ns day8.re-frame2-causa.chart.layout-cljs-test
  "Pure-data tests for the chart-layout primitive (rf2-2tkza Phase 1).

  Dual-target convention: `.cljc` + `_cljs_test` ns name so the
  Cognitect runner (CLJ) and Shadow's `:node-test` build both pick
  it up via their default suffix regex."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.chart.layout :as layout]))

;; ---- fixtures ----------------------------------------------------------

(def idle-loading-success
  "Canonical small machine: idle → loading → success/failed."
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :success :err :failed}}
             :success {:final? true}
             :failed  {:final? true}}})

(def compound-machine
  "One compound state with a nested region."
  {:initial :unauth
   :states  {:unauth        {:on {:login :authenticated}}
             :authenticated {:initial :browsing
                             :states  {:browsing {:on {:checkout :paying}}
                                       :paying   {:on {:done :browsing}}}
                             :on      {:logout :unauth}}}})

;; ---- parse-definition ---------------------------------------------------

(deftest parse-definition-empty-for-nil
  (let [g (layout/parse-definition nil)]
    (is (= [] (:nodes g)))
    (is (= [] (:edges g)))))

(deftest parse-definition-extracts-flat-machine-nodes
  (let [{:keys [nodes initial-path]} (layout/parse-definition idle-loading-success)
        paths (set (map :path nodes))]
    (is (= [:idle] initial-path))
    (is (= #{[:idle] [:loading] [:success] [:failed]} paths))
    (is (some :initial? nodes) "the initial state is flagged")
    (is (= 2 (count (filter :final? nodes)))
        "final states are flagged")))

(deftest parse-definition-extracts-edges
  (let [{:keys [edges]} (layout/parse-definition idle-loading-success)
        edge-pairs (set (map (juxt :from :to :event) edges))]
    (is (contains? edge-pairs [[:idle] [:loading] :start]))
    (is (contains? edge-pairs [[:loading] [:success] :ok]))
    (is (contains? edge-pairs [[:loading] [:failed] :err]))))

(deftest parse-definition-extracts-compound-nodes
  (let [{:keys [nodes]} (layout/parse-definition compound-machine)
        paths (set (map :path nodes))
        top   (filter #(= 1 (count (:path %))) nodes)]
    (is (contains? paths [:unauth]))
    (is (contains? paths [:authenticated]))
    (is (contains? paths [:authenticated :browsing]))
    (is (contains? paths [:authenticated :paying]))
    (is (= 2 (count top))
        "two top-level states (:unauth + :authenticated)")
    (is (some :compound? top)
        "the compound parent carries the :compound? flag")))

(deftest parse-definition-supports-parallel-projects-first-region
  (let [parallel {:type :parallel
                  :regions {:r1 {:initial :a :states {:a {:on {:go :b}}
                                                      :b {}}}
                            :r2 {:initial :x :states {:x {:on {:go :y}}
                                                      :y {}}}}}
        {:keys [nodes]} (layout/parse-definition parallel)
        paths (set (map :path nodes))]
    (is (contains? paths [:a]))
    (is (contains? paths [:b]))
    (is (not (contains? paths [:x]))
        "v1 projects only the first region — :r2's nodes do not surface")))

;; ---- layout ------------------------------------------------------------

(deftest layout-empty-for-nil-definition
  (let [g (layout/layout nil)]
    (is (= [] (:nodes g)))
    (is (= [] (:edges g)))
    (is (pos? (:width g)))
    (is (pos? (:height g)))))

(deftest layout-positions-every-node
  (let [g (layout/layout idle-loading-success)]
    (is (= 4 (count (:nodes g))))
    (doseq [n (:nodes g)]
      (is (integer? (:x n)) (str "node " (:path n) " missing :x"))
      (is (integer? (:y n)) (str "node " (:path n) " missing :y"))
      (is (pos? (:width n)))
      (is (pos? (:height n)))
      (is (string? (:node-id n))))))

(deftest layout-stratifies-by-rank-from-initial
  (testing "initial state sits at rank 0 (top row); BFS ranks grow downward"
    (let [{:keys [nodes]} (layout/layout idle-loading-success)
          rank-of (fn [path]
                    (some (fn [n] (when (= (:path n) path) (:rank n)))
                          nodes))]
      (is (= 0 (rank-of [:idle])))
      (is (= 1 (rank-of [:loading])))
      (is (= 2 (rank-of [:success])))
      (is (= 2 (rank-of [:failed]))))))

(deftest layout-routes-edges-with-points
  (let [{:keys [edges]} (layout/layout idle-loading-success)]
    (is (every? :from-id edges))
    (is (every? :to-id edges))
    (is (every? (comp #(= 2 (count %)) :points) edges)
        "straight-line edges carry two points each")
    (is (every? :event-label edges)
        "every edge has a human-readable event-label")))

(deftest layout-unreached-nodes-still-render-at-rank-zero
  (testing "a state with no inbound edges still appears in the layout"
    (let [orphan {:initial :a
                  :states  {:a       {}
                            :orphan  {:on {:noop :a}}}}
          {:keys [nodes]} (layout/layout orphan)]
      (is (= 2 (count nodes)))
      (is (some #(= [:orphan] (:path %)) nodes)))))

;; ---- highlight-id ------------------------------------------------------

(deftest highlight-id-handles-flat-state
  (is (some? (layout/highlight-id :authing)))
  (is (= (layout/highlight-id :authing)
         (layout/highlight-id [:authing]))
      "flat keyword and 1-element vector resolve to the same id"))

(deftest highlight-id-handles-hierarchical-path
  (let [id (layout/highlight-id [:authenticated :browsing])]
    (is (string? id))
    (is (not= id (layout/highlight-id [:authenticated])))))

(deftest highlight-id-nil-for-nil-state
  (is (nil? (layout/highlight-id nil))))
