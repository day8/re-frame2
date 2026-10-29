(ns re-frame.mcp-base.sensitive-test
  "Tests for the spec/009 §Privacy default-suppress filter both MCP
  servers route trace-like data through."
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [re-frame.mcp-base.sensitive :as rf.mcp-base.sensitive]))

(deftest sensitive-event?-classifies-the-stamp
  ;; Fail-closed: the literal `true` drops, and so does any other truthy
  ;; stamp, because a non-boolean stamp means an upstream serialisation
  ;; bug. Explicit `false`, an absent stamp and non-map input pass.
  (testing "sensitive"
    (are [ev] (rf.mcp-base.sensitive/sensitive-event? ev)
      {:operation :rf.event/dispatched :sensitive? true}
      {:operation :rf.event/dispatched :sensitive? "true"}
      {:operation :rf.event/dispatched :sensitive? :yes}
      {:operation :rf.event/dispatched :sensitive? 1}
      {:operation :rf.event/dispatched :sensitive? ["any" "truthy"]}))
  (testing "not sensitive"
    (are [ev] (not (rf.mcp-base.sensitive/sensitive-event? ev))
      {:operation :rf.event/dispatched}
      {:operation :rf.event/dispatched :sensitive? false}
      "anything")))

(deftest spec-009-default-posture-is-suppress
  (let [batch [{:operation  :rf.event/dispatched
                :tags       {:rf.trace/event-id :auth/sign-in}
                :sensitive? true}]]
    (is (= [[] 1] (rf.mcp-base.sensitive/strip-sensitive batch false)) "suppressed by default")
    (is (= [batch 0] (rf.mcp-base.sensitive/strip-sensitive batch true)) "the documented opt-in")))

;; ---------------------------------------------------------------------------
;; The malformed-stamp diagnostic: never leaks, never overcounts.
;; ---------------------------------------------------------------------------

(deftest malformed-warning-redacts-raw-stamp-value
  ;; Logs are an egress boundary too: the contract-drift warning carries a
  ;; value-free type tag and the :rf/redacted sentinel, never the stamp.
  (doseq [[stamp secret] [["sk_live_SECRET_TOKEN" "sk_live_SECRET_TOKEN"]
                          [{:api_key "AKIA_LEAK"} "AKIA_LEAK"]]]
    (let [sw (java.io.StringWriter.)]
      (binding [*err* sw]
        (rf.mcp-base.sensitive/sensitive-event? {:sensitive? stamp}))
      (is (str/includes? (str sw) ":rf/redacted"))
      (is (not (str/includes? (str sw) secret))))))

(deftest strip-sensitive-malformed-count-is-exactly-one-per-event
  ;; Classifying a malformed stamp bumps the counter, so the single-pass
  ;; filter must classify each event exactly once: two malformed stamps,
  ;; two bumps, and the well-formed `true` none.
  (rf.mcp-base.sensitive/reset-malformed-count!)
  (is (= [[{:id 1 :sensitive? false} {:id 3}] 3]
         (rf.mcp-base.sensitive/strip-sensitive [{:id 1 :sensitive? false}
                                                 {:id 2 :sensitive? "true"}
                                                 {:id 3}
                                                 {:id 4 :sensitive? :yes}
                                                 {:id 5 :sensitive? true}]
                                                false)))
  (is (= 2 (rf.mcp-base.sensitive/malformed-count))))
