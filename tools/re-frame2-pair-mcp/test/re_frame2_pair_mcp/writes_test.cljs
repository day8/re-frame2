(ns re-frame2-pair-mcp.writes-test
  "The server boundary refuses a disabled write tool BEFORE
  `ensure-connection!`, so a stock install with no nREPL port answers
  `:rf.error/writes-disabled` rather than `:nrepl-port-not-found`; and the
  discovery-error envelope keeps its namespaced reason in
  `structuredContent`."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.server :as server]
            [re-frame2-pair-mcp.tools.writes :as writes]))

(use-fixtures :each
  {:before (fn []
             (server/reset-session-state-for-tests!)
             (writes/set-allow-writes! false))
   :after  (fn []
             (server/reset-session-state-for-tests!)
             (writes/set-allow-writes! false))})

(deftest write-tools-refused-before-connection
  ;; No port is configured here, so reaching `ensure-connection!` would
  ;; answer a discovery error and mark the session discovered.
  (async done
    (-> (reduce (fn [p tool-name]
                  (.then p (fn [_]
                             (.then (server/handle-call-for-tests {} tool-name #js {} nil)
                                    (fn [result]
                                      (is (tu/error? result))
                                      (is (= :rf.error/writes-disabled (:reason (tu/extract-edn result)))
                                          tool-name)
                                      (is (false? (:discovered? (server/session-state-snapshot)))
                                          (str tool-name " never ran discovery")))))))
                (js/Promise.resolve nil)
                ["restore-epoch" "replace-app-db"])
        (.catch (fn [e] (is false (str "handle-call rejected: " (.-message e))) nil))
        (.then (fn [_] (done))))))

(deftest discovery-error-structured-content-preserves-reason-namespace
  ;; A raw `clj->js` would truncate a `:rf.error/*` reason to its name.
  (async done
    (-> (server/handle-call-for-tests {} "snapshot" #js {} nil)
        (.then (fn [result]
                 (is (tu/error? result) "discovery failure rides as isError")
                 (let [reason (:reason (tu/extract-edn result))]
                   (is (keyword? reason))
                   (is (= (str (symbol reason))
                          (j/get-in result [:structuredContent "reason"]))))))
        (.catch (fn [e] (is false (str "handle-call rejected: " (.-message e))) nil))
        (.then (fn [_] (done))))))
