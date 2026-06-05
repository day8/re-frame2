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

(deftest parse-definition-region-qualifies-cross-region-same-named-states
  ;; rf2-uo0rc.4: BEFORE the fix, both regions' states were projected at
  ;; parent-path [] with the region-id discarded, so a `:idle` in region
  ;; :a and a `:idle` in region :b both got path [:idle] and the SAME
  ;; node-id (chart-layout/node-id is injective on the PATH, but the path
  ;; lacked the region prefix) — the xyflow graph merged/mis-targeted the
  ;; two nodes. AFTER the fix each region's states are region-qualified
  ;; ([:a :idle] / [:b :idle]) so the ids are distinct.
  (testing "same-named cross-region states get region-qualified paths + distinct node-ids"
    (let [par {:type    :parallel
               :regions {:a {:initial :idle
                             :states  {:idle {:on {:go :busy}}
                                       :busy {}}}
                         :b {:initial :idle
                             :states  {:idle {:on {:go :done}}
                                       :done {:final? true}}}}}
          g     (topology/parse-definition par)
          paths (set (map :path (:nodes g)))
          ids   (map (comp chart-layout/node-id :path) (:nodes g))]
      (testing "region-qualified paths are present + collision-free"
        (is (contains? paths [:a :idle]) "region :a's :idle is path [:a :idle]")
        (is (contains? paths [:b :idle]) "region :b's :idle is path [:b :idle]")
        (is (contains? paths [:a :busy]))
        (is (contains? paths [:b :done]))
        (is (= 4 (count paths)) "four distinct state paths across the two regions"))
      (testing "no two nodes collide on node-id"
        (is (= (count ids) (count (set ids)))
            "every projected node mints a DISTINCT node-id"))
      (testing "edges resolve targets within the right region"
        (let [edges    (:edges g)
              by-from  (group-by :from edges)]
          (is (= [:a :busy] (-> by-from (get [:a :idle]) first :to))
              "region :a's :idle --:go--> :busy resolves inside region :a")
          (is (= [:b :done] (-> by-from (get [:b :idle]) first :to))
              "region :b's :idle --:go--> :done resolves inside region :b"))))))

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

;; ---- rf2-ezqpm: :always + :after transition coverage --------------------
;;
;; Pre-fix, `collect-edges` walked only `(:on state-node)`. `:always`
;; (eventless / transient) and `:after` (delay-fired) transitions are
;; first-class in Spec 005 — the chart was therefore showing a partial
;; topology when a machine used either slot. These tests pin the edges'
;; presence, labels (Stately glyph convention per rf2-a2b55), and the
;; `:always?` / `:after` discriminators on the projected edges.

(deftest parse-definition-emits-edge-for-always-transition
  (testing "rf2-ezqpm — `:always` (eventless transient) emits an edge"
    (let [def {:initial :checking
               :states  {:checking {:always {:target :ready :guard :ok?}}
                         :ready    {:final? true}}}
          {:keys [edges]} (topology/parse-definition def)]
      (is (= 1 (count edges)))
      (let [e (first edges)]
        (is (= [:checking] (:from e)))
        (is (= [:ready]    (:to e)))
        (is (true? (:always? e)))
        (is (= :always (:event e)))
        (is (= :ok? (:guard e)))
        (is (= "∞ [ok?]" (:label e))
            "label uses the Stately infinity glyph (rf2-a2b55)")))))

(deftest parse-definition-emits-edge-for-bare-keyword-always
  (testing "rf2-ezqpm — `:always :ready` (the bare-keyword shorthand)
            is also charted (matches the `:on` shorthand grammar)"
    (let [def {:initial :checking
               :states  {:checking {:always :ready}
                         :ready    {}}}
          {:keys [edges]} (topology/parse-definition def)]
      (is (= 1 (count edges)))
      (is (= [:ready] (-> edges first :to)))
      (is (true? (-> edges first :always?))))))

(deftest parse-definition-emits-edge-for-always-vector-fork
  (testing "rf2-ezqpm — `:always [{:target … :guard …} …]` (the guarded
            fork grammar) emits one edge per candidate; ids are distinct
            so xyflow keeps every branch"
    (let [def {:initial :checking
               :states  {:checking {:always [{:target :winner  :guard :enough-correct?}
                                             {:target :loser   :guard :enough-wrong?}]}
                         :winner   {}
                         :loser    {}}}
          {:keys [edges]} (topology/parse-definition def)]
      (is (= 2 (count edges)))
      (is (= #{[:winner] [:loser]} (set (map :to edges))))
      (is (every? :always? edges))
      (is (= 2 (count (set (map :id edges))))
          "candidates mint DISTINCT edge ids (per-candidate ordinal)"))))

(deftest parse-definition-emits-edge-for-after-transition
  (testing "rf2-ezqpm — `:after {<ms> {:target …}}` emits one edge per
            delay entry"
    (let [def {:initial :loading
               :states  {:loading {:after {5000 {:target :timeout}}}
                         :timeout {:final? true}}}
          {:keys [edges]} (topology/parse-definition def)]
      (is (= 1 (count edges)))
      (let [e (first edges)]
        (is (= [:loading] (:from e)))
        (is (= [:timeout] (:to e)))
        (is (= 5000 (:after e)))
        (is (= "⌚ 5000ms" (:label e))
            "label uses the Stately clock glyph + `<ms>ms` (rf2-a2b55)")))))

(deftest parse-definition-emits-edge-for-after-bare-keyword
  (testing "rf2-ezqpm — `:after {<ms> :target-state}` shorthand"
    (let [def {:initial :loading
               :states  {:loading {:after {500 :ready}}
                         :ready   {}}}
          {:keys [edges]} (topology/parse-definition def)]
      (is (= 1 (count edges)))
      (is (= [:ready] (-> edges first :to)))
      (is (= 500 (-> edges first :after))))))

(deftest parse-definition-emits-one-edge-per-after-delay
  (testing "rf2-ezqpm — each `:after` delay entry is INDEPENDENT and
            emits its own edge (Spec 005 — one timer per entry)"
    (let [def {:initial :idle
               :states  {:idle {:after {500  {:target :short}
                                        5000 {:target :long}}}
                         :short {}
                         :long  {}}}
          {:keys [edges]} (topology/parse-definition def)]
      (is (= 2 (count edges)))
      (is (= #{500 5000} (set (map :after edges))))
      (is (= 2 (count (set (map :id edges))))
          "the two `:after` delays mint DISTINCT edge ids"))))

(deftest parse-definition-after-edge-carries-guard-and-action
  (testing "rf2-ezqpm — `:after` map spec surfaces guard + action into the label"
    (let [def {:initial :loading
               :states  {:loading {:after {1500 {:target :timeout
                                                 :guard  :still-pending?
                                                 :action :cleanup}}}
                         :timeout {}}}
          {:keys [edges]} (topology/parse-definition def)]
      (is (= 1 (count edges)))
      (is (= "⌚ 1500ms [still-pending?] / cleanup"
             (-> edges first :label))))))

(deftest parse-definition-mixes-on-always-after-on-one-state
  (testing "rf2-ezqpm — all three transition slots co-exist on a state;
            each emits its own edge(s) and the on-edges keep their old
            shape (back-compat for the `:on` projection)"
    (let [def {:initial :loading
               :states  {:loading {:on     {:cancel :idle}
                                   :after  {3000 {:target :timeout}}
                                   :always {:target :ready :guard :data-loaded?}}
                         :idle    {}
                         :timeout {}
                         :ready   {}}}
          {:keys [edges]} (topology/parse-definition def)
          by-target (group-by :to edges)]
      (is (= 3 (count edges)))
      ;; :on edge (unchanged)
      (let [e (first (get by-target [:idle]))]
        (is (= :cancel (:event e)))
        (is (= "cancel" (:label e)))
        (is (not (contains? e :after)))
        (is (not (contains? e :always?))))
      ;; :after edge
      (let [e (first (get by-target [:timeout]))]
        (is (= 3000 (:after e)))
        (is (= "⌚ 3000ms" (:label e))))
      ;; :always edge
      (let [e (first (get by-target [:ready]))]
        (is (true? (:always? e)))
        (is (= "∞ [data-loaded?]" (:label e)))))))

(deftest parse-definition-always-after-recurse-into-compound
  (testing "rf2-ezqpm — `:always` / `:after` on a compound substate are
            walked (recursion already covered `:on`; the new slots ride
            the same recursion)"
    (let [def {:initial :authed
               :states  {:authed {:initial :browsing
                                  :states  {:browsing {:after {1000 {:target :idle}}}
                                            :idle     {:always {:target :browsing}}}}}}
          {:keys [edges]} (topology/parse-definition def)
          paths (set (map (juxt :from :to) edges))]
      (is (contains? paths [[:authed :browsing] [:authed :idle]])
          "nested :after edge surfaces")
      (is (contains? paths [[:authed :idle] [:authed :browsing]])
          "nested :always edge surfaces"))))

(deftest project-surfaces-always-and-after-on-data
  (testing "rf2-ezqpm — the projected xyflow `:data` carries `:always?`
            / `:after` so the downstream renderer can split by kind
            without re-parsing the label"
    (let [def {:initial :loading
               :states  {:loading {:after  {2000 {:target :timeout}}
                                   :always {:target :ready :guard :ok?}}
                         :timeout {}
                         :ready   {}}}
          out (topology/project {:definition def})
          by-target (into {} (map (juxt :target identity) (:edges out)))
          to-timeout (get by-target (chart-layout/node-id [:timeout]))
          to-ready   (get by-target (chart-layout/node-id [:ready]))]
      (is (= 2000 (-> to-timeout :data :after)))
      (is (not (contains? (:data to-timeout) :always?))
          "`:after` edge does not also carry `:always?`")
      (is (true? (-> to-ready :data :always?)))
      (is (not (contains? (:data to-ready) :after))
          "`:always` edge does not also carry `:after`"))))

;; ---- rf2-2678t: :* wildcard label parity with machines-viz canonical ----
;;
;; Pre-rf2-2678t xray's local `event-segment-str` was a strict subset of
;; the canonical `machines-viz/chart.layout/event-segment` — it lacked
;; the `:*` → `\"* (any)\"` case. Post-fix the local helper delegates to
;; the canonical fn, so wildcard edges label identically in xray and
;; machines-viz (no glyph-convention drift).

(deftest parse-definition-emits-edge-for-wildcard-event
  (testing "rf2-2678t — `:on {:* :elsewhere}` (Spec 005 §Wildcard) emits
            an edge labelled `\"* (any)\"`, matching the canonical
            machines-viz layout helper. Pre-fix the label was bare
            `\"*\"` — a strict subset of the canonical."
    (let [def {:initial :a
               :states  {:a {:on {:* :fallback}}
                         :fallback {}}}
          {:keys [edges]} (topology/parse-definition def)]
      (is (= 1 (count edges)))
      (let [e (first edges)]
        (is (= :* (:event e))
            "the wildcard event is recorded verbatim on the edge")
        (is (= "* (any)" (:label e))
            "label matches machines-viz convention (NOT bare `\"*\"`)")))))

(deftest wildcard-label-matches-machines-viz-canonical
  (testing "rf2-2678t — xray's edge label for `:*` must equal what
            machines-viz produces for the same input. Single source of
            truth lives in machines-viz/chart.layout/event-segment."
    (let [def {:initial :a
               :states  {:a {:on {:* :fallback}}
                         :fallback {}}}
          {:keys [edges]} (topology/parse-definition def)
          xray-label    (-> edges first :label)
          mviz-label    (chart-layout/event-segment {:event :*})]
      (is (= xray-label mviz-label)
          "xray + machines-viz agree on the wildcard edge label"))))

(deftest project-includes-arrowhead-for-always-and-after
  (testing "rf2-ezqpm — every projected edge (including `:always` /
            `:after`) requests an arrowclosed markerEnd (rf2-5qsxo)"
    (let [def {:initial :loading
               :states  {:loading {:after  {2000 {:target :timeout}}
                                   :always {:target :ready :guard :ok?}}
                         :timeout {}
                         :ready   {}}}
          out (topology/project {:definition def})]
      (is (= 2 (count (:edges out))))
      (doseq [e (:edges out)]
        (is (= "arrowclosed" (-> e :markerEnd :type)))))))

(deftest project-applies-edge-style-fn-to-always-and-after
  (testing "rf2-ezqpm — `:always` and `:after` edges run through the
            same kind-resolution + style-fn pipeline as `:on` edges"
    (let [def {:initial :loading
               :states  {:loading {:after  {2000 {:target :timeout}}
                                   :always {:target :ready}}
                         :timeout {}
                         :ready   {}}}
          out (topology/project {:definition def
                                 :edge-style-fn (fn [k]
                                                  {:test-marker (str "edge-" (name k))})})
          markers (set (map #(-> % :style :test-marker) (:edges out)))]
      ;; Both edges resolve to :registered (no fired/traversed sets).
      (is (= #{"edge-registered"} markers)
          ":always + :after edges both go through edge-style-fn"))))
