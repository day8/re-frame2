(ns re-frame2-pair-mcp.unknown-tool-test
  "An unknown tool name is refused before `ensure-connection!`, so a typo
  on an install with no nREPL port gets the `:unknown-tool` recovery
  envelope rather than a discovery error that masks it."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.server :as server]))

(use-fixtures :each
  {:before (fn [] (server/reset-session-state-for-tests!))
   :after  (fn [] (server/reset-session-state-for-tests!))})

(deftest unknown-tool-refused-before-connection
  ;; No port is configured here, so reaching `ensure-connection!` would
  ;; answer :nrepl-port-not-found.
  (async done
    (-> (server/handle-call-for-tests {} "snapsho" #js {} nil)
        (.then (fn [result]
                 (let [edn  (tu/extract-edn result)
                       snap (server/session-state-snapshot)]
                   (is (tu/error? result))
                   (is (= {:ok? false :reason :unknown-tool :tool "snapsho" :did-you-mean "snapshot"}
                          (select-keys edn [:ok? :reason :tool :did-you-mean])))
                   (is (re-find #"did you mean .*tools/list" (:hint edn))
                       "the hint inlines the suggestion and points at tools/list")
                   (is (some #{"snapshot"} (:available-tools edn)) "the hint carries the live catalogue")
                   (is (false? (:discovered? snap))))))
        (.catch (fn [e] (is false (str "handle-call rejected: " (.-message e))) nil))
        (.then (fn [_] (done))))))
