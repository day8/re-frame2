(ns day8.re-frame2-causa.panels.machines.topology-cljs-test
  "Pure-data tests for the xyflow topology projector (rf2-uwvyj ·
  spec/021 §6 + §17.4). The projector is JS/React-free; tests run
  under :node-test with zero DOM harness."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa.panels.machines.topology :as topology]))

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

(deftest parse-definition-handles-parallel-by-first-region
  (testing "parallel definitions project the first region only (v1)"
    (let [par {:type    :parallel
               :regions {:r1 {:initial :a :states {:a {} :b {}}}
                         :r2 {:initial :c :states {:c {}}}}}
          g   (topology/parse-definition par)]
      (is (= 2 (count (:nodes g)))
          "first region's 2 states; second region not flattened"))))

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
        (is (keyword? (-> e :data :kind)))))))

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

;; ---- focused-epoch helpers ---------------------------------------------

(deftest current-state-from-traces-resolves-latest
  (testing "picks the :to of the LAST matching :rf.machine/transition"
    (let [events [{:operation :rf.machine/transition
                   :tags      {:machine-id :foo}
                   :from      [:a] :to [:b] :event :go-b}
                  {:operation :rf.machine/transition
                   :tags      {:machine-id :foo}
                   :from      [:b] :to [:c] :event :go-c}
                  {:operation :something-else}]]
      (is (= [:c] (topology/current-state-from-traces events :foo))))))

(deftest current-state-from-traces-scopes-by-machine-id
  (testing "ignores trace events belonging to other machines"
    (let [events [{:operation :rf.machine/transition
                   :tags      {:machine-id :other}
                   :from      [:x] :to [:y] :event :wrong-machine}
                  {:operation :rf.machine/transition
                   :tags      {:machine-id :foo}
                   :from      [:a] :to [:b] :event :ours}]]
      (is (= [:b] (topology/current-state-from-traces events :foo))))))

(deftest current-state-from-traces-returns-nil-when-no-match
  (is (nil? (topology/current-state-from-traces [] :foo)))
  (is (nil? (topology/current-state-from-traces nil :foo))))

(deftest current-state-accepts-keyword-to
  (testing ":to may be a bare keyword (per the normalise-path branch)"
    (let [events [{:operation :rf.machine/transition
                   :tags      {:machine-id :foo}
                   :from      :a :to :b :event :go}]]
      (is (= [:b] (topology/current-state-from-traces events :foo))))))

(deftest extract-fired-edge-ids-shape
  (testing "extracts edge-ids matching the from→to via event triple"
    (let [graph       (topology/project {:definition (toy-definition)})
          populate-id (some (fn [e] (when (= "populate" (:label e)) (:id e)))
                            (:edges graph))
          events      [{:operation :rf.machine/transition
                        :tags      {:machine-id :cart}
                        :from      [:empty] :to [:populated]
                        :event     :populate}]
          fired       (topology/extract-fired-edge-ids events :cart)]
      (is (= #{populate-id} fired))))
  (testing "events without a matching from/to/event don't contribute"
    (is (= #{} (topology/extract-fired-edge-ids [] :cart)))
    (is (= #{} (topology/extract-fired-edge-ids nil :cart)))
    (is (= #{} (topology/extract-fired-edge-ids
                 [{:operation :something-else}] :cart)))))

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

(deftest current-state-from-traces-reads-tags-after-state
  (testing "modern runtime shape: :tags {:after {:state ...}}"
    ;; Per lifecycle_fx/registration the runtime stamps
    ;;   {:tags {:after  {:state <to-kw> ...}
    ;;           :before {:state <from-kw> ...}
    ;;           :machine-id <id>}}
    ;; The legacy top-level :to slot still works (existing tests pin it).
    (let [events [{:operation :rf.machine/transition
                   :tags      {:machine-id :cart
                               :after      {:state :populated}}}]]
      (is (= [:populated]
             (topology/current-state-from-traces events :cart))))))

(deftest current-state-from-traces-prefers-modern-shape-over-legacy
  (testing "when both :after :state AND legacy :to are present, modern wins"
    (let [events [{:operation :rf.machine/transition
                   :tags      {:machine-id :cart
                               :after      {:state :populated}
                               :to         :should-be-ignored}}]]
      ;; Per to-path-from-trace: `(or after-state to)` — `after-state`
      ;; wins when present.
      (is (= [:populated]
             (topology/current-state-from-traces events :cart))))))

(deftest current-state-from-epoch-history-walks-back
  (testing "walks epoch-history newest→oldest, returns most-recent :to"
    (let [history [{:epoch-id 1
                    :trace-events [{:operation :rf.machine/transition
                                    :tags {:machine-id :cart}
                                    :from [:empty] :to [:populated]
                                    :event :populate}]}
                   {:epoch-id 2
                    :trace-events [{:operation :rf.machine/transition
                                    :tags {:machine-id :cart}
                                    :from [:populated] :to [:submitting]
                                    :event :submit}]}
                   ;; Epoch 3 has no machine activity — the walk
                   ;; back skips it and picks epoch 2's :submitting.
                   {:epoch-id 3
                    :trace-events [{:operation :something-else}]}]]
      (is (= [:submitting]
             (topology/current-state-from-epoch-history history :cart))))))

(deftest current-state-from-epoch-history-empty-cases
  (testing "nil history → nil"
    (is (nil? (topology/current-state-from-epoch-history nil :cart))))
  (testing "empty history → nil"
    (is (nil? (topology/current-state-from-epoch-history [] :cart))))
  (testing "history with no transition for this machine → nil"
    (let [history [{:epoch-id 1 :trace-events []}
                   {:epoch-id 2 :trace-events [{:operation :something-else}]}]]
      (is (nil? (topology/current-state-from-epoch-history history :cart))))))

(deftest current-state-from-epoch-history-scopes-by-machine-id
  (testing "ignores transitions belonging to other machines"
    (let [history [{:epoch-id 1
                    :trace-events [{:operation :rf.machine/transition
                                    :tags {:machine-id :other}
                                    :from [:x] :to [:y]
                                    :event :wrong-machine}]}
                   {:epoch-id 2
                    :trace-events [{:operation :rf.machine/transition
                                    :tags {:machine-id :cart}
                                    :from [:empty] :to [:populated]
                                    :event :populate}]}]]
      (is (= [:populated]
             (topology/current-state-from-epoch-history history :cart))))))

(deftest current-state-from-epoch-history-reads-modern-shape
  (testing "epoch-history walk-back honours the modern :tags :after :state shape"
    (let [history [{:epoch-id 1
                    :trace-events [{:operation :rf.machine/transition
                                    :tags {:machine-id :cart
                                           :after {:state :authing}}}]}]]
      (is (= [:authing]
             (topology/current-state-from-epoch-history history :cart))))))

(deftest current-state-from-epoch-history-picks-latest-within-epoch
  (testing "within an epoch's trace-events, the LAST matching transition wins"
    (let [history [{:epoch-id 1
                    :trace-events [{:operation :rf.machine/transition
                                    :tags {:machine-id :cart}
                                    :from [:empty] :to [:populated]
                                    :event :populate}
                                   ;; Microstep after — should be picked.
                                   {:operation :rf.machine/transition
                                    :tags {:machine-id :cart}
                                    :from [:populated] :to [:submitting]
                                    :event :submit}]}]]
      (is (= [:submitting]
             (topology/current-state-from-epoch-history history :cart))))))
