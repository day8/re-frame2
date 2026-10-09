(ns re-frame.flows-topo-test
  "The pure topology checks in `re-frame.flows.topo`: cycle rejection with its
  closing-repeat path, the extractor's dead-end guard, and output-overlap
  rejection. Registration and drain tests exercise the same functions through
  `reg-flow`."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.flows.topo :as rf.flows.topo]))

(defn- thrown [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

(deftest extract-cycle-path-defends-against-dead-end
  ;; Unreachable through Kahn's algorithm (every stuck node has a stuck
  ;; dependency), so the guard is reached directly: a cycle path built from a
  ;; dead end would misreport the offending chain to tools.
  (let [ex (thrown #(#'rf.flows.topo/extract-cycle-path {:a #{}} #{:a}))]
    (is (re-find #"\[:rf\.error/flow-cycle-extract-invariant\]" (ex-message ex)))
    (is (= {:rf.error/id :rf.error/flow-cycle-extract-invariant :recovery :no-recovery
            :node :a :stack [:a] :seen #{:a} :remaining #{:a}}
           (dissoc (ex-data ex) :reason :where)))))

(deftest topo-sort-detects-cycle
  ;; Spec 013 §Cycle detection: `:cycle` is the ordered chain with a closing
  ;; repeat; the starting node is implementation-defined.
  (let [ex   (thrown #(rf.flows.topo/topo-sort
                        {:a {:id :a :inputs [[:b]] :derive identity :output-path [:a]}
                         :b {:id :b :inputs [[:a]] :derive identity :output-path [:b]}}))
        data (ex-data ex)]
    (is (re-find #"\[:rf\.error/flow-cycle\]" (ex-message ex)))
    (is (contains? #{[:a :b :a] [:b :a :b]} (:cycle data)))
    (is (= {:rf.error/id :rf.error/flow-cycle :where 'rf/reg-flow :recovery :fix-registration}
           (dissoc data :reason :cycle)))
    (is (string? (:reason data)))))

(deftest detect-output-path-overlap!-throws-on-identical-paths
  ;; Output/output overlap creates no dependency edge, so the shared slot's
  ;; write order would be undefined (Spec 013 §Disjoint output paths).
  (let [ex   (thrown #(rf.flows.topo/detect-output-path-overlap!
                        {:a {:id :a :inputs [[:w]] :derive identity :output-path [:x]}
                         :b {:id :b :inputs [[:h]] :derive identity :output-path [:x]}}))
        data (ex-data ex)]
    (is (re-find #"\[:rf\.error/flow-path-overlap\]" (ex-message ex)))
    (is (= {:rf.error/id :rf.error/flow-path-overlap :where 'rf/reg-flow
            :recovery :fix-registration :overlap {:flow-ids #{:a :b} :paths [[:x] [:x]]}}
           (-> data (dissoc :reason) (update-in [:overlap :flow-ids] set))))
    (is (string? (:reason data)))))
