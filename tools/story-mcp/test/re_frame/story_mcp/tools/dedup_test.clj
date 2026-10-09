(ns re-frame.story-mcp.tools.dedup-test
  "Story-mcp's integration of the structural-dedup wire-boundary transform:
  the dual-slot rewrite and sibling-slot preservation of `apply-dedup`, and
  the `:dedup-eligible?` gate through `invoke-tool`.

  The canonical dedup behaviour (wrap, passthrough, marker shape,
  round-trip exactness) is asserted once, cross-host, in
  `re-frame.mcp-base.dedup-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story-mcp.test-support :as rf.story-mcp.test-support]
            [re-frame.story-mcp.tools.wire-pipeline :as rf.story-mcp.tools.wire-pipeline]
            [re-frame.story-mcp.tools.result :as rf.story-mcp.tools.result]
            [re-frame.story-mcp.tools.registry :as rf.story-mcp.tools.registry]))

(deftest apply-dedup-rewrites-both-slots-consistently
  ;; Both slots carry the deduped payload, so the cap step sizes the
  ;; post-dedup wire on both.
  (let [shared  {:big "value" :tags [:a :b :c]}
        payload [{:id 1 :data shared} {:id 2 :data shared} {:id 3 :data shared}]
        out     (rf.story-mcp.tools.wire-pipeline/apply-dedup
                  (rf.story-mcp.tools.result/text-result (rf.story-mcp.tools.result/pr-edn payload) payload)
                  true)
        sc      (:structuredContent out)]
    (is (contains? sc :rf.mcp/dedup-table))
    (is (= payload (rf.story-mcp.test-support/dedup-expand sc)) "round-trip restores the payload")
    (is (= (rf.story-mcp.tools.result/pr-edn sc) (-> out :content first :text))
        "the text slot is re-stringified from the deduped structured payload")))

(deftest apply-dedup-preserves-sibling-slots
  (let [payload [{:k :v} {:k :v}]
        result  (assoc (rf.story-mcp.tools.result/text-result (rf.story-mcp.tools.result/pr-edn payload) payload)
                       :_sibling :passes-through)]
    (is (= :passes-through (:_sibling (rf.story-mcp.tools.wire-pipeline/apply-dedup result true))))))

(deftest descriptor-dedup-eligibility-matches-the-documented-set
  ;; tools/story-mcp/spec/Principles.md §Structural dedup at the wire
  ;; boundary names the set. Eligibility and the advertised `:dedup` input
  ;; slot move together: eligibility without the slot is invisible to
  ;; agents, the slot without eligibility is silently ignored.
  (let [names-where (fn [pred] (->> rf.story-mcp.tools.registry/tool-registry (filter pred) (map :name) set))]
    (is (= #{"preview-variant" "run-variant"}
           (names-where :dedup-eligible?)
           (names-where #(contains? (-> % :inputSchema :properties) :dedup))))))

(defn- mock-handler [_args]
  (let [payload [{:id 1 :data {:k :shared-value}}
                 {:id 2 :data {:k :shared-value}}
                 {:id 3 :data {:k :shared-value}}]]
    (rf.story-mcp.tools.result/text-result (rf.story-mcp.tools.result/pr-edn payload) payload)))

(deftest invoke-tool-fires-dedup-on-eligible-descriptors
  (doseq [[eligible? args wrapped?] [[true  {}             true]
                                     [false {}             false]
                                     [true  {:dedup false} false]]]
    (testing (str "eligible " eligible? " args " args)
      (with-redefs [rf.story-mcp.tools.registry/tool-by-name
                    (fn [_] {:name "t" :dedup-eligible? eligible? :handler mock-handler})]
        (is (= wrapped? (contains? (:structuredContent (rf.story-mcp.tools.wire-pipeline/invoke-tool "t" args))
                                   :rf.mcp/dedup-table)))))))
