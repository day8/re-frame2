(ns day8.re-frame2-xray.panels.machines.topology-cljs-test
  "Pure-data tests for the xyflow topology projector (rf2-uwvyj ·
  spec/021 §6 + §17.4). The projector is JS/React-free; tests run
  under :node-test with zero DOM harness."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-machines-viz.chart.layout :as chart-layout]
            [day8.re-frame2-xray.panels.machines.topology :as topology]))

(defn- toy-definition
  "Minimal machine with three states + two transitions:

    :empty -- :populate --> :populated -- :submit --> :submitting
                                                            (final)"
  []
  {:initial :empty
   :states  {:empty      {:on {:populate :populated}}
             :populated  {:on {:submit :submitting}}
             :submitting {:final? true}}})

;; ---- parse-definition ---------------------------------------------------

(deftest parse-definition-returns-empty-on-nil
  (testing "nil definition → empty graph"
    (let [g (topology/parse-definition nil)]
      (is (= [] (:nodes g)))
      (is (= [] (:edges g)))
      (is (nil? (:initial-path g))))))

(deftest parse-definition-emits-one-node-per-state
  (let [g (topology/parse-definition (toy-definition))]
    (testing "node count matches state count"
      (is (= 3 (count (:nodes g)))))
    (testing "each node carries label + final? + path"
      (let [by-label (into {} (map (juxt :label identity) (:nodes g)))]
        (is (= [:empty]      (-> by-label (get "empty") :path)))
        (is (= [:populated]  (-> by-label (get "populated") :path)))
        (is (= [:submitting] (-> by-label (get "submitting") :path)))
        (is (true? (-> by-label (get "submitting") :final?))
            ":final? carries through")
        (is (false? (-> by-label (get "empty") :final?))
            "non-final states have :final? false")))
    (testing "initial path is captured"
      (is (= [:empty] (:initial-path g))))))

(deftest parse-definition-emits-edges-from-on-clauses
  (let [g     (topology/parse-definition (toy-definition))
        edges (:edges g)]
    (testing "two edges (populate + submit)"
      (is (= 2 (count edges))))
    (testing "each edge carries from/to/label"
      (let [by-label (into {} (map (juxt :label identity) edges))]
        (is (= [:empty]     (-> by-label (get "populate") :from)))
        (is (= [:populated] (-> by-label (get "populate") :to)))
        (is (= [:populated]  (-> by-label (get "submit") :from)))
        (is (= [:submitting] (-> by-label (get "submit") :to)))))))

(deftest parse-definition-handles-map-target-spec
  (testing "transition value may be a map {:target ... :guards ...}"
    (let [def {:initial :a
               :states  {:a {:on {:go {:target :b :guards [:always]}}}
                         :b {:final? true}}}
          g   (topology/parse-definition def)]
      (is (= 1 (count (:edges g))))
      (is (= [:a] (-> g :edges first :from)))
      (is (= [:b] (-> g :edges first :to))))))

(deftest parse-definition-projects-all-regions
  (testing "parallel definitions project EVERY region (rf2-54s5a)"
    (let [par {:type    :parallel
               :regions {:r1 {:initial :a :states {:a {} :b {}}}
                         :r2 {:initial :c :states {:c {}}}}}
          g   (topology/parse-definition par)]
      (is (= 3 (count (:nodes g)))
          "both regions' states flatten (a + b + c)"))))

(deftest parse-definition-recurses-compound-substates
  (testing "compound substates are flattened, not left invisible (rf2-54s5a)"
    (let [def {:initial :unauth
               :states  {:unauth {:on {:login :authed}}
                         :authed {:initial :browsing
                                  :states  {:browsing {:on {:checkout :paying}}
                                            :paying   {:on {:done :browsing}}}}}}
          g     (topology/parse-definition def)
          paths (set (map :path (:nodes g)))]
      (is (contains? paths [:authed :browsing]))
      (is (contains? paths [:authed :paying]))
      (is (= 4 (count (:nodes g)))
          "unauth + authed + browsing + paying")
      (is (true? (-> (filter #(= [:authed :browsing] (:path %)) (:nodes g))
                     first :initial?))
          "the compound's :initial substate is flagged"))))

(deftest parse-definition-edge-label-includes-guard
  (testing "map target-spec :guard surfaces into the xstate label (rf2-54s5a)"
    (let [def {:initial :a
               :states  {:a {:on {:go {:target :b :guard :ready?}}}
                         :b {}}}
          g   (topology/parse-definition def)
          e   (first (:edges g))]
      (is (= "go [ready?]" (:label e)))
      (is (= :ready? (:guard e))))))

;; ---- node-kind ----------------------------------------------------------

(deftest node-kind-precedence
  (testing ":current wins over :final"
    (is (= :current
           (topology/node-kind {:path [:foo] :final? true} [:foo]))))
  (testing ":final when not current"
    (is (= :final
           (topology/node-kind {:path [:foo] :final? true} [:bar]))))
  (testing ":standard otherwise"
    (is (= :standard
           (topology/node-kind {:path [:foo] :final? false} nil)))
    (is (= :standard
           (topology/node-kind {:path [:foo] :final? false} [:bar])))))

(deftest node-kind-accepts-keyword-or-vector-current
  (testing "current-state-path may be a bare keyword"
    (is (= :current
           (topology/node-kind {:path [:foo] :final? false} :foo)))))

;; rf2-ad7zx.10 — :from node kind (Figma §6.2 Case C). The source state
;; of the focused fired transition renders as the dashed/dim :from circle.

(deftest node-kind-from-precedence
  (testing ":current wins over :from (self-transition reads as active)"
    (is (= :current
           (topology/node-kind {:path [:foo] :final? false} [:foo] [:foo]))))
  (testing ":from when it is the transition source + not current"
    (is (= :from
           (topology/node-kind {:path [:foo] :final? false} [:bar] [:foo]))))
  (testing ":from wins over :final"
    (is (= :from
           (topology/node-kind {:path [:foo] :final? true} [:bar] [:foo]))))
  (testing ":final when neither current nor from"
    (is (= :final
           (topology/node-kind {:path [:foo] :final? true} [:bar] [:baz]))))
  (testing ":standard otherwise"
    (is (= :standard
           (topology/node-kind {:path [:foo] :final? false} [:bar] [:baz])))))

(deftest node-kind-2-arity-unchanged
  (testing "the 2-arity keeps the pre-Case-C precedence (no :from)"
    (is (= :current (topology/node-kind {:path [:foo] :final? true} [:foo])))
    (is (= :final   (topology/node-kind {:path [:foo] :final? true} [:bar])))
    (is (= :standard (topology/node-kind {:path [:foo] :final? false} nil)))))

(deftest node-kind-from-accepts-keyword
  (testing "from-state-path may be a bare keyword"
    (is (= :from
           (topology/node-kind {:path [:foo] :final? false} nil :foo)))))

;; ---- edge-kind ----------------------------------------------------------

(deftest edge-kind-precedence
  (testing ":fired-this-epoch when id in fired set"
    (is (= :fired-this-epoch
           (topology/edge-kind {:id "abc"} #{"abc"} #{}))))
  (testing ":registered-traversed when id in traversed set"
    (is (= :registered-traversed
           (topology/edge-kind {:id "abc"} #{} #{"abc"}))))
  (testing ":registered otherwise"
    (is (= :registered
           (topology/edge-kind {:id "abc"} #{} #{})))
    (is (= :registered
           (topology/edge-kind {:id "abc"} nil nil)))))

;; ---- project ------------------------------------------------------------

(deftest project-shape
  (let [out (topology/project {:definition (toy-definition)})]
    (testing "returns {:nodes :edges}"
      (is (vector? (:nodes out)))
      (is (vector? (:edges out)))
      (is (= 3 (count (:nodes out))))
      (is (= 2 (count (:edges out)))))
    (testing "each xyflow node carries :id :position :data :type"
      (let [n (first (:nodes out))]
        (is (string? (:id n)))
        (is (map? (:position n)))
        (is (number? (-> n :position :x)))
        (is (number? (-> n :position :y)))
        (is (string? (-> n :data :label)))
        (is (keyword? (-> n :data :kind)))))
    (testing "each xyflow edge carries :id :source :target :label"
      (let [e (first (:edges out))]
        (is (string? (:id e)))
        (is (string? (:source e)))
        (is (string? (:target e)))
        (is (string? (:label e)))
        (is (keyword? (-> e :data :kind)))))
    (testing "rf2-5qsxo — each edge requests an arrowclosed markerEnd so
              React Flow draws an arrowhead at the target end"
      (doseq [e (:edges out)]
        (is (= "arrowclosed" (-> e :markerEnd :type)))
        (is (string? (-> e :markerEnd :color))
            "marker colour resolves (falls back to currentColor when the
             style fn omits a stroke)")))))

(deftest project-surfaces-initial-flag
  (testing ":data :initial is true for the initial-state node (rf2-54s5a)"
    (let [out      (topology/project {:definition (toy-definition)})
          by-label (into {} (map (juxt #(-> % :data :label) identity)
                                 (:nodes out)))]
      (is (true?  (-> by-label (get "empty") :data :initial)))
      (is (false? (-> by-label (get "populated") :data :initial))))))

(deftest project-applies-current-state-overlay
  (testing "current-state-path marks the matching node as :current"
    (let [out      (topology/project
                     {:definition         (toy-definition)
                      :current-state-path [:populated]})
          by-label (into {} (map (juxt #(-> % :data :label) identity)
                                 (:nodes out)))]
      (is (= :current  (-> by-label (get "populated") :data :kind)))
      (is (= :standard (-> by-label (get "empty") :data :kind)))
      (is (= :final    (-> by-label (get "submitting") :data :kind))
          "final state stays final when not current"))))

(deftest project-applies-from-state-overlay
  (testing "from-state-path marks the source node as :from (Figma §6.2 Case C)"
    (let [out      (topology/project
                     {:definition         (toy-definition)
                      :current-state-path [:populated]
                      :from-state-path    [:empty]})
          by-label (into {} (map (juxt #(-> % :data :label) identity)
                                 (:nodes out)))]
      (is (= :from    (-> by-label (get "empty") :data :kind))
          "the transition source renders as :from")
      (is (= :current (-> by-label (get "populated") :data :kind))
          "the TO / current node stays :current")
      (is (= :final   (-> by-label (get "submitting") :data :kind))
          "untouched final stays final")))
  (testing "no from-state-path → no :from nodes (back-compat)"
    (let [out   (topology/project {:definition (toy-definition)
                                   :current-state-path [:populated]})
          kinds (set (map #(-> % :data :kind) (:nodes out)))]
      (is (not (contains? kinds :from))))))

(deftest project-applies-fired-edge-overlay
  (testing "fired-edge-ids set marks matching edges :fired-this-epoch"
    (let [out         (topology/project {:definition (toy-definition)})
          populate-id (some (fn [e]
                              (when (= "populate" (:label e)) (:id e)))
                            (:edges out))
          out2        (topology/project
                        {:definition     (toy-definition)
                         :fired-edge-ids #{populate-id}})
          edges-by-id (into {} (map (juxt :id identity) (:edges out2)))]
      (is (string? populate-id))
      (is (= :fired-this-epoch
             (-> edges-by-id (get populate-id) :data :kind)))
      ;; The other edge stays :registered.
      (let [other-edge (some #(when (not= (:id %) populate-id) %)
                             (:edges out2))]
        (is (= :registered (-> other-edge :data :kind)))))))

(deftest project-invokes-injected-style-fns
  (testing "style fns are called per node/edge with the resolved kind"
    (let [seen-node-kinds (atom [])
          seen-edge-kinds (atom [])
          out (topology/project
                {:definition         (toy-definition)
                 :current-state-path [:populated]
                 :node-style-fn      (fn [k]
                                       (swap! seen-node-kinds conj k)
                                       {:test-marker (str "node-" (name k))})
                 :edge-style-fn      (fn [k]
                                       (swap! seen-edge-kinds conj k)
                                       {:test-marker (str "edge-" (name k))})
                 :edge-animated-fn   (fn [k] (= k :fired-this-epoch))})]
      ;; Node style fn invoked once per node (3 nodes).
      (is (= 3 (count @seen-node-kinds)))
      (is (= #{:current :final :standard} (set @seen-node-kinds)))
      ;; Edge style fn invoked once per edge (2 edges).
      (is (= 2 (count @seen-edge-kinds)))
      ;; Per-node :style carries the marker from the injected fn.
      (let [marker-set (into #{} (map #(get-in % [:style :test-marker])
                                      (:nodes out)))]
        (is (contains? marker-set "node-current"))
        (is (contains? marker-set "node-standard"))
        (is (contains? marker-set "node-final"))))))

(deftest project-handles-nil-definition
  (testing "nil definition → empty graph (no exceptions)"
    (let [out (topology/project {:definition nil})]
      (is (= [] (:nodes out)))
      (is (= [] (:edges out))))))

;; ---- rf2-dbi87: always-visible empty-state (Case B) --------------------
;;
;; Per spec/021 §6.2 Case B + §17.4.1 — when the focused epoch has NO
;; machine transition, the topology MUST still render with the most-
;; recent-known state annotated as :current. The projector keeps emitting
;; the full topology unchanged (Case B is a render-layer concern); these
;; tests pin the helpers that resolve "most-recent-known" from sources
;; OUTSIDE the focused epoch (epoch-history walk-back + runtime trace
;; shapes).

(deftest project-emits-full-graph-with-no-fired-edges
  (testing "case-B render: full topology + current-state overlay, no fired"
    (let [out         (topology/project
                        {:definition         (toy-definition)
                         :current-state-path [:populated]
                         :fired-edge-ids     #{}})
          edges-kinds (into #{} (map #(-> % :data :kind) (:edges out)))
          nodes-by-lb (into {} (map (juxt #(-> % :data :label) identity)
                                    (:nodes out)))]
      (is (= 3 (count (:nodes out)))
          "all states still emit")
      (is (= 2 (count (:edges out)))
          "all transitions still emit (no overlay arrows added)")
      (is (= #{:registered} edges-kinds)
          "no edge is :fired-this-epoch when fired-edge-ids is empty")
      (is (= :current (-> nodes-by-lb (get "populated") :data :kind))
          "current-state overlay still annotates the matching node"))))

;; ---- rf2-m8kod: node-id scheme parity with machines-viz ----------------
;;
;; The Xray topology overlay and the live MachineChart MUST address nodes
;; with the SAME string ids — otherwise any future "highlight fired edges
;; on the live chart" wiring (rf2-qeemm/B8) silently mis-targets. The old
;; Xray-local `node-id-for-path` used the non-injective `[^a-zA-Z0-9_] → _`
;; collapse, which MERGED `:a/b`, `:a-b`, `:a_b` onto one id; it now
;; delegates to the canonical injective hex-escape scheme
;; `chart.layout/node-id`. These tests pin that the two schemes agree id-
;; for-id (driven through the public `project` API, which uses the private
;; `node-id-for-path` for every node `:id`) AND that the collision triples
;; mint DISTINCT ids.

(defn- machine-from-state-ids
  "Build a flat single-level machine whose top-level state-ids are
  `state-ids`, so projecting it exercises `node-id-for-path` over each
  `[state-id]` path. The first state is the `:initial`."
  [state-ids]
  {:initial (first state-ids)
   :states  (into {} (map (fn [id] [id {}]) state-ids))})

(deftest node-id-parity-flat-paths
  (testing "every projected node :id matches chart-layout/node-id for its path"
    (let [state-ids [:empty :populated :logged-in :rate-limited
                     :a/b :a-b :a_b :foo.bar/baz :x+y :state?]
          out       (topology/project
                      {:definition (machine-from-state-ids state-ids)})
          ;; project carries the source path on :data :path — pair it
          ;; with the minted :id so we can compare against the canonical fn.
          id-by-path (into {} (map (juxt #(-> % :data :path) :id)
                                   (:nodes out)))]
      (doseq [id state-ids]
        (let [path [id]]
          (is (= (chart-layout/node-id path) (get id-by-path path))
              (str "node-id parity for path " (pr-str path))))))))

(deftest node-id-parity-nested-paths
  (testing "compound (nested-vector) paths match chart-layout/node-id"
    (let [def {:initial :unauth
               :states  {:unauth {:on {:login :authed}}
                         :authed {:initial :browsing
                                  :states  {:browsing {:on {:checkout :rate-limited}}
                                            :rate-limited {:on {:retry :browsing}}}}}}
          out (topology/project {:definition def})
          id-by-path (into {} (map (juxt #(-> % :data :path) :id)
                                   (:nodes out)))]
      (doseq [[path id] id-by-path]
        (is (= (chart-layout/node-id path) id)
            (str "nested node-id parity for path " (pr-str path)))))))

(deftest node-id-collision-triples-mint-distinct-ids
  (testing ":a/b vs :a-b vs :a_b project to THREE distinct node ids"
    (let [state-ids [:a/b :a-b :a_b]
          out       (topology/project
                      {:definition (machine-from-state-ids state-ids)})
          ids       (mapv :id (:nodes out))]
      (is (= 3 (count (:nodes out)))
          "all three states survive (no id-collision drop)")
      (is (= 3 (count (set ids)))
          "the three ids are pairwise distinct (injective scheme)")
      ;; And the canonical scheme is itself injective for the triple.
      (is (apply distinct?
                 [(chart-layout/node-id [:a/b])
                  (chart-layout/node-id [:a-b])
                  (chart-layout/node-id [:a_b])])
          "canonical scheme is itself injective for the triple"))))

(deftest edge-ids-built-from-canonical-node-ids
  (testing "topology edge :source / :target are the canonical node ids"
    (let [def {:initial :a-b
               :states  {:a-b {:on {:go :a/b}}
                         :a/b {}}}
          out (topology/project {:definition def})
          e   (first (:edges out))]
      (is (= (chart-layout/node-id [:a-b]) (:source e))
          "edge :source uses the canonical from-node id")
      (is (= (chart-layout/node-id [:a/b]) (:target e))
          "edge :target uses the canonical to-node id")
      ;; The collision triple no longer fuses source + target into one id.
      (is (not= (:source e) (:target e))
          ":a-b and :a/b stay distinct across an edge"))))
