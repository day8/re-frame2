(ns re-frame2-pair-mcp.closed-world-test
  "`get-re-frame2-pair-instructions` reads only server-local state, so the
  server answers it before `ensure-connection!`: it works with no runtime
  at all (spec/003)."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.server :as server]
            [re-frame2-pair-mcp.tools.registry :as registry]))

(use-fixtures :each
  {:before (fn [] (server/reset-session-state-for-tests!))
   :after  (fn [] (server/reset-session-state-for-tests!))})

(deftest closed-world-tool?-flags-exactly-the-server-local-reads
  ;; A false positive would hand a runtime-dependent tool a nil conn.
  (is (= ["get-re-frame2-pair-instructions"]
         (filterv registry/closed-world-tool? registry/tool-names))))

(deftest instructions-answered-before-connection
  ;; No port is configured here, so reaching `ensure-connection!` would
  ;; answer :nrepl-port-not-found.
  (async done
    (-> (server/handle-call-for-tests {} "get-re-frame2-pair-instructions" #js {} nil)
        (.then (fn [result]
                 (let [snap (server/session-state-snapshot)]
                   (is (true? (:ok? (tu/extract-edn result))))
                   (is (false? (:discovered? snap))))))
        (.catch (fn [e] (is false (str "handle-call rejected: " (.-message e))) nil))
        (.then (fn [_] (done))))))
