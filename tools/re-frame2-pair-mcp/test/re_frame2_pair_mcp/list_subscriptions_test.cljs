(ns re-frame2-pair-mcp.list-subscriptions-test
  "The `list-subscriptions` tool's refusal path. Its descriptor is pinned
  by the `tool-descriptors.edn` drift check, and its blank-eval and empty
  envelopes by the conformance corpus."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.list-subscriptions :as lsub]))

(deftest list-subscriptions-runtime-ok-false-is-iserror
  ;; A runtime refusal (multi-frame session, no selection) rides isError,
  ;; never a success-shaped listing.
  (async done
    (let [conn (nrepl/make-conn 0 "127.0.0.1")]
      (swap! conn assoc :probed-builds #{:app})
      (-> (tu/with-stubbed-eval! {:ok? false :reason :ambiguous-frame}
            (fn [] (lsub/list-subscriptions-tool conn #js {})))
          (.then (fn [r]
                   (is (true? (tu/error? r)))
                   (is (= {:ok? false :reason :ambiguous-frame}
                          (select-keys (tu/extract-edn r) [:ok? :reason])))
                   (done)))))))
