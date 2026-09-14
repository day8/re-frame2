(require '[clojure.test :as t] '[clojure.java.io :as io])
(def research-root "<HOME>/code/re-frame2")
(println "gate root:" research-root)
(def test-namespaces
  '[re-frame.story.promotion-cljs-test
    re-frame.story.ui.promotion-cljs-test
    re-frame.story.ui.view-state-upgrade-test
    re-frame.story.ui.view-state-test
    re-frame.story.run-result-hashes-test
    re-frame.story.ui.explain-panel-test])
(doseq [n test-namespaces] (require n))
(doseq [n test-namespaces
        :let [v (first (vals (ns-publics n)))
              resource (io/resource (:file (meta v)))]]
  (println "namespace source:" n (str resource))
  (assert (.startsWith (.getCanonicalPath (io/file resource))
                       (.getCanonicalPath (io/file research-root "tools/story/test")))
          (str "Wrong checkout for " n)))
(let [result (apply t/run-tests test-namespaces)]
  (spit (str research-root "/ai/findings/Story/astra/evidence/second-sibling-review/current-regressions-result.edn")
        (pr-str result))
  (shutdown-agents)
  (System/exit (if (and (pos? (:test result)) (zero? (+ (:fail result) (:error result)))) 0 1)))
