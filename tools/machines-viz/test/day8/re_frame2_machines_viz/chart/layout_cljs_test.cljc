(ns day8.re-frame2-machines-viz.chart.layout-cljs-test
  "Pure-data tests for the chart-layout graph projector.

  Post rf2-gpzb4 (2026-05-21 xyflow migration) — the SVG-side
  positioning primitives (`layout`, `layered-fallback`, `:x`/`:y`/
  `:rank` on nodes, `:points` on edges) are gone; xyflow + elkjs own
  positioning. This suite pins the substrate-agnostic graph parse
  surface that survived the migration."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-machines-viz.chart.layout :as layout]))

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

(deftest parse-definition-emits-xyflow-shaped-edges
  (testing "rf2-gpzb4 xyflow migration — every edge has :id, :source,
            :target string ids (xyflow contract) AND :from-path,
            :to-path vectors (substrate-side contract)"
    (let [{:keys [edges]} (layout/parse-definition idle-loading-success)]
      (is (every? :id edges)         "every edge has a stable string id")
      (is (every? :source edges)     "every edge has :source (string)")
      (is (every? :target edges)     "every edge has :target (string)")
      (is (every? :from-path edges)  "every edge has :from-path (vector)")
      (is (every? :to-path edges)    "every edge has :to-path (vector)")
      (is (every? :event-label edges) "every edge has the xstate label"))))

(deftest parse-definition-emits-xyflow-shaped-nodes
  (testing "rf2-gpzb4 xyflow migration — every node has :id string
            (xyflow contract) alongside :path (vector)"
    (let [{:keys [nodes]} (layout/parse-definition idle-loading-success)]
      (is (every? :id nodes))
      (is (every? string? (map :id nodes)))
      (is (every? :path nodes)))))

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

;; ---- node-id ----------------------------------------------------------

(deftest node-id-is-public-fn
  (testing "node-id is exported so xyflow + SCXML + Mermaid emitters
            address nodes the same way"
    (is (string? (layout/node-id [:idle])))
    (is (= (layout/node-id [:idle])
           (layout/node-id [:idle]))
        "deterministic")))

(deftest node-id-distinct-for-distinct-paths
  (is (not= (layout/node-id [:authenticated])
            (layout/node-id [:authenticated :browsing]))))

;; ---- edge-label xstate-stately convention -----------------------------

;; Shape: `event [guard] / action`. Brackets + slash appear ONLY when
;; their segment is present, per xstate-stately.

(deftest edge-label-event-only
  (is (= "submit"
         (layout/edge-label {:event :submit}))))

(deftest edge-label-event-with-guard
  (is (= "submit [authed?]"
         (layout/edge-label {:event :submit :guard :authed?}))))

(deftest edge-label-event-with-action
  (is (= "submit / log-it"
         (layout/edge-label {:event :submit :action :log-it}))))

(deftest edge-label-event-with-guard-and-action
  (is (= "submit [authed?] / log-it"
         (layout/edge-label {:event :submit
                             :guard :authed?
                             :action :log-it}))))

(deftest edge-label-after-with-guard-and-action
  (is (= "after(1500) [timeout?] / cleanup"
         (layout/edge-label {:event  :after-1500
                             :after  1500
                             :guard  :timeout?
                             :action :cleanup}))))

(deftest edge-label-always-with-guard
  (is (= "always [ready?]"
         (layout/edge-label {:event   :always
                             :always? true
                             :guard   :ready?}))))

(deftest edge-label-namespaced-event-with-guard
  (is (= "auth/submit [authed?] / log-it"
         (layout/edge-label {:event  :auth/submit
                             :guard  :authed?
                             :action :log-it}))))

(deftest edge-label-namespaced-guard-renders-ns
  (testing "guards may be namespaced; the label preserves the namespace"
    (is (= "submit [auth/authed?]"
           (layout/edge-label {:event :submit
                               :guard :auth/authed?})))))

(deftest parse-definition-emits-event-label-with-guard-and-action
  (testing "parse-definition emits the full xstate label on every edge"
    (let [m {:initial :idle
             :states  {:idle {:on {:submit [{:target :loading
                                             :guard  :authed?
                                             :action :log-it}
                                            {:target :failed
                                             :guard  :anon?}]}}
                       :loading {}
                       :failed  {}}}
          {:keys [edges]} (layout/parse-definition m)
          labels (set (map :event-label edges))]
      (is (contains? labels "submit [authed?] / log-it"))
      (is (contains? labels "submit [anon?]")))))
