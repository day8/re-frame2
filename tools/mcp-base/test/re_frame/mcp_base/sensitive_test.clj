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
;; scrub-snapshot — strip sensitive events from per-frame slices.
;; ---------------------------------------------------------------------------

(deftest scrub-snapshot-strips-sensitive-from-traces
  ;; Only the :traces / :epochs slices are filtered; every other slice and
  ;; any non-map frame value pass through untouched, because projecting
  ;; them is the caller's egress pipeline's job.
  (let [snap {:rf/default {:app-db   {:user/name "ada" :password "secret"}
                           :traces   [{:id 1 :sensitive? false} {:id 2 :sensitive? true} {:id 3}]
                           :epochs   [{:event-id :foo} {:event-id :auth/sign-in :sensitive? true}]
                           :machines {}}
              :stories    {:app-db {} :traces [{:id 10 :sensitive? true}]}
              :meta-count 7}]
    (is (= [{:rf/default {:app-db   {:user/name "ada" :password "secret"}
                          :traces   [{:id 1 :sensitive? false} {:id 3}]
                          :epochs   [{:event-id :foo}]
                          :machines {}}
             :stories    {:app-db {} :traces []}
             :meta-count 7}
            3]
           (rf.mcp-base.sensitive/scrub-snapshot snap false)))))

(deftest scrub-snapshot-include-opt-in-passes-everything
  (let [snap {:rf/default {:traces [{:id 1 :sensitive? true}]}}]
    (is (= [snap 0] (rf.mcp-base.sensitive/scrub-snapshot snap true)))))

(deftest scrub-snapshot-non-map-input-passes-through
  (is (= [nil 0] (rf.mcp-base.sensitive/scrub-snapshot nil false))))

(deftest scrub-snapshot-handles-lazy-seq-slice-values
  ;; A slice composed with `concat` / `map` / `filter` arrives as a lazy
  ;; seq and is still a batch.
  (is (= [{:rf/default {:traces [{:id 1}]}} 1]
         (rf.mcp-base.sensitive/scrub-snapshot
           {:rf/default {:traces (map identity [{:id 1} {:id 2 :sensitive? true}])}} false))))

(deftest scrub-snapshot-strip-fn-arity-delegates-to-custom-predicate
  (let [strip-by-id-2 (fn [items _include?]
                        (let [kept (filterv #(not= 2 (:id %)) items)]
                          [kept (- (count items) (count kept))]))]
    (is (= [{:rf/default {:traces [{:id 1} {:id 3}]}} 2]
           (rf.mcp-base.sensitive/scrub-snapshot
             {:rf/default {:traces [{:id 1} {:id 2} {:id 3} {:id 2}]}} false strip-by-id-2)))))

(deftest scrub-snapshot-non-batch-slices-pass-through-unchanged
  ;; Only a sequential slice is a batch; an unconditional `vec` would turn
  ;; nil into [] and a string into a vector of chars.
  (let [snap {:rf/default {:traces nil :epochs "oops"}}]
    (is (= [snap 0] (rf.mcp-base.sensitive/scrub-snapshot snap false)))))

;; pair-mcp's union strip-fn: the event stamp OR the epoch-level
;; `:rf.epoch/sensitive?` rollup, both through the fail-closed classifier.
(defn- union-strip [items _include?]
  (let [drop? (fn [x] (or (rf.mcp-base.sensitive/sensitive-event? x)
                          (and (map? x)
                               (rf.mcp-base.sensitive/sensitive-stamp? (:rf.epoch/sensitive? x)))))
        kept  (filterv (complement drop?) items)]
    [kept (- (count items) (count kept))]))

(deftest scrub-snapshot-sensitive-single-map-slice-dropped-fail-closed
  ;; A single event map is not a batch, but shipping a sensitive one raw
  ;; would leak it, so it is dropped and counted. It is classified by the
  ;; caller's strip-fn, not the base predicate, and a benign one passes.
  (is (= [{:rf/default {:traces []}} 1]
         (rf.mcp-base.sensitive/scrub-snapshot
           {:rf/default {:traces {:id 1 :sensitive? true :payload "SECRET"}}} false)))
  (is (= [{:rf/default {:epochs []}} 1]
         (rf.mcp-base.sensitive/scrub-snapshot
           {:rf/default {:epochs {:rf.epoch/sensitive? true :secret "TOKEN_LEAK"}}} false union-strip)))
  (let [snap {:rf/default {:epochs {:event-id :ui/click :data 42}}}]
    (is (= [snap 0] (rf.mcp-base.sensitive/scrub-snapshot snap false union-strip)))))

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
