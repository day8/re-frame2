(ns re-frame.mcp-base.sensitive-test
  "Tests for the spec/009 §Privacy default-suppress filter shared
  across the MCP pair (rf2-vw4sq). The predicate and the filter
  must stay byte-identical across re-frame2-pair-mcp and story-mcp
  — these tests pin the contract."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-base.sensitive :as sensitive]))

;; ---------------------------------------------------------------------------
;; sensitive-event? — the boolean predicate.
;; ---------------------------------------------------------------------------

(deftest sensitive-event?-true-stamp-detected
  (is (sensitive/sensitive-event? {:operation :event/dispatched :sensitive? true})))

(deftest sensitive-event?-false-stamp-passes
  (is (not (sensitive/sensitive-event? {:operation :event/dispatched :sensitive? false}))))

(deftest sensitive-event?-absent-stamp-passes
  ;; Per spec/009: "Consumers treat absent as `false`."
  (is (not (sensitive/sensitive-event? {:operation :event/dispatched}))))

(deftest sensitive-event?-non-true-truthy-drops-fail-closed
  ;; Fail-closed (rf2-ih7g4): the literal `true` drops AND any
  ;; non-boolean truthy value drops too. The `:rf/trace-event` schema
  ;; types `:sensitive?` as a boolean; a string `"true"` or keyword
  ;; `:yes` is a contract violation that means an upstream
  ;; serialisation bug has coerced the boolean into the wrong shape.
  ;; The previous fail-OPEN posture silently leaked sensitive events
  ;; on such drift. The fix is fail-CLOSED: drop AND log.
  (binding [*err* (java.io.StringWriter.)] ; absorb the contract-drift warning
    (is (sensitive/sensitive-event? {:operation :event/dispatched :sensitive? "true"}))
    (is (sensitive/sensitive-event? {:operation :event/dispatched :sensitive? :yes}))
    (is (sensitive/sensitive-event? {:operation :event/dispatched :sensitive? 1}))
    (is (sensitive/sensitive-event? {:operation :event/dispatched :sensitive? ["any" "truthy"]}))))

(deftest sensitive-event?-non-map-input-passes
  (is (not (sensitive/sensitive-event? nil)))
  (is (not (sensitive/sensitive-event? [:sensitive? true])))
  (is (not (sensitive/sensitive-event? "anything"))))

(deftest sensitive-event?-explicit-false-passes
  ;; Fail-closed posture (rf2-ih7g4) does NOT change the explicit-false
  ;; / nil path — those remain non-sensitive. Only truthy non-boolean
  ;; values get the new fail-closed drop.
  (is (not (sensitive/sensitive-event? {:operation :event/dispatched :sensitive? false})))
  (is (not (sensitive/sensitive-event? {:operation :event/dispatched :sensitive? nil}))))

(deftest strip-sensitive-fail-closed-drops-malformed-truthy
  ;; rf2-ih7g4: a transport bug that coerces `:sensitive? true` into
  ;; `:sensitive? "true"` (string) or `:sensitive? :yes` (keyword) MUST
  ;; NOT silently leak the event. The fail-closed posture drops the
  ;; malformed-truthy event (with a stderr warning) so the contract
  ;; drift is visible to operators.
  (binding [*err* (java.io.StringWriter.)] ; absorb the warning
    (let [evts [{:id 1 :sensitive? false}
                {:id 2 :sensitive? "true"} ; malformed-truthy → drop
                {:id 3}
                {:id 4 :sensitive? :yes}]  ; malformed-truthy → drop
          [kept dropped] (sensitive/strip-sensitive evts false)]
      (is (= [{:id 1 :sensitive? false} {:id 3}] kept))
      (is (= 2 dropped)))))

;; ---------------------------------------------------------------------------
;; strip-sensitive — the default-suppress filter applied per batch.
;; ---------------------------------------------------------------------------

(deftest strip-sensitive-default-drops-true-stamps
  (let [evts [{:id 1 :sensitive? false}
              {:id 2 :sensitive? true}
              {:id 3}
              {:id 4 :sensitive? true}]
        [kept dropped] (sensitive/strip-sensitive evts false)]
    (is (= [{:id 1 :sensitive? false} {:id 3}] kept))
    (is (= 2 dropped))))

(deftest strip-sensitive-include-opt-in-passes-everything
  (let [evts [{:id 1 :sensitive? true}
              {:id 2 :sensitive? false}
              {:id 3 :sensitive? true}]
        [kept dropped] (sensitive/strip-sensitive evts true)]
    (is (= evts kept))
    (is (zero? dropped))))

(deftest strip-sensitive-empty-batch-zero-overhead
  (let [[kept dropped] (sensitive/strip-sensitive [] false)]
    (is (= [] kept))
    (is (zero? dropped))))

(deftest strip-sensitive-no-sensitive-events-zero-drop
  (let [evts [{:id 1} {:id 2 :sensitive? false} {:id 3}]
        [kept dropped] (sensitive/strip-sensitive evts false)]
    (is (= evts kept))
    (is (zero? dropped))))

(deftest strip-sensitive-all-sensitive-drops-all
  (let [evts [{:id 1 :sensitive? true}
              {:id 2 :sensitive? true}
              {:id 3 :sensitive? true}]
        [kept dropped] (sensitive/strip-sensitive evts false)]
    (is (= [] kept))
    (is (= 3 dropped))))

;; ---------------------------------------------------------------------------
;; Default posture — the load-bearing assertion for the spec/009 MUST.
;; ---------------------------------------------------------------------------

(deftest spec-009-default-posture-is-suppress
  (testing "the default (include-sensitive? omitted ⇒ false) suppresses"
    (let [sensitive-batch [{:operation :event/dispatched
                            :tags      {:event-id :auth/sign-in}
                            :sensitive? true}]
          [kept dropped] (sensitive/strip-sensitive sensitive-batch false)]
      (is (= [] kept) "sensitive event must NOT reach the agent surface by default")
      (is (= 1 dropped))))
  (testing "include-sensitive? true is the documented opt-in"
    (let [sensitive-batch [{:operation :event/dispatched
                            :tags      {:event-id :auth/sign-in}
                            :sensitive? true}]
          [kept dropped] (sensitive/strip-sensitive sensitive-batch true)]
      (is (= sensitive-batch kept))
      (is (zero? dropped)))))

;; ---------------------------------------------------------------------------
;; scrub-snapshot — strip sensitive trace events from per-frame slices.
;; ---------------------------------------------------------------------------

(deftest scrub-snapshot-strips-sensitive-from-traces
  (let [snap {:rf/default
              {:app-db  {:user/name "ada" :password "secret"}
               :traces  [{:id 1 :sensitive? false}
                         {:id 2 :sensitive? true}
                         {:id 3}]
               :epochs  [{:event-id :foo} {:event-id :auth/sign-in :sensitive? true}]
               :machines {}}
              :stories
              {:app-db {} :traces [{:id 10 :sensitive? true}]}}
        [out dropped] (sensitive/scrub-snapshot snap false)]
    (is (= 3 dropped))
    (is (= [{:id 1 :sensitive? false} {:id 3}]
           (get-in out [:rf/default :traces])))
    (is (= [{:event-id :foo}]
           (get-in out [:rf/default :epochs])))
    (is (= [] (get-in out [:stories :traces])))))

(deftest scrub-snapshot-leaves-non-trace-slices-alone
  ;; App-db payload redaction is `with-redacted`'s job, not the
  ;; forwarder's. The scrubber must NOT touch :app-db / :sub-cache /
  ;; :machines even when they carry literal "sensitive"-looking shapes.
  (let [snap {:rf/default
              {:app-db    {:password "still-here" :sensitive? true}
               :sub-cache {:user/profile {:sensitive? true :data "x"}}
               :machines  {:auth {:state :idle}}
               :traces    [{:id 1}]}}
        [out _] (sensitive/scrub-snapshot snap false)]
    (is (= {:password "still-here" :sensitive? true}
           (get-in out [:rf/default :app-db])))
    (is (= {:user/profile {:sensitive? true :data "x"}}
           (get-in out [:rf/default :sub-cache])))
    (is (= {:auth {:state :idle}}
           (get-in out [:rf/default :machines])))))

(deftest scrub-snapshot-include-opt-in-passes-everything
  (let [snap {:rf/default {:traces [{:id 1 :sensitive? true}
                                    {:id 2 :sensitive? true}]}}
        [out dropped] (sensitive/scrub-snapshot snap true)]
    (is (= snap out))
    (is (zero? dropped))))

(deftest scrub-snapshot-non-map-input-passes-through
  (let [[out dropped] (sensitive/scrub-snapshot nil false)]
    (is (nil? out))
    (is (zero? dropped))))

(deftest scrub-snapshot-handles-lazy-seq-slice-values
  ;; Regression pin (rf2-cwqc8 / round-2 F17): `:traces` and `:epochs`
  ;; arrive as vectors in the typical runtime emission, but the
  ;; instrumentation API doesn't guarantee that — a slice composed via
  ;; `concat` / `map` / `filter` produces a lazy seq. The `(vec items)`
  ;; wrap inside `scrub-slice` normalises before `strip-sensitive`
  ;; runs; this test pins the contract so a future refactor that
  ;; removes the wrap doesn't silently break the lazy-input case.
  (let [snap   {:rf/default
                {:traces (map identity [{:id 1 :sensitive? false}
                                        {:id 2 :sensitive? true}
                                        {:id 3}])
                 :epochs (filter (constantly true)
                                 [{:event-id :foo}
                                  {:event-id :auth/sign-in :sensitive? true}])}}
        [out dropped] (sensitive/scrub-snapshot snap false)]
    (is (= 2 dropped))
    (is (= [{:id 1 :sensitive? false} {:id 3}]
           (get-in out [:rf/default :traces])))
    (is (= [{:event-id :foo}]
           (get-in out [:rf/default :epochs])))))

(deftest scrub-snapshot-strip-fn-arity-delegates-to-custom-predicate
  ;; rf2-zpmmr: the three-arity form admits a caller-supplied strip-fn
  ;; matching the `[items include?] => [kept dropped]` contract. Pin
  ;; the contract so a future refactor of the helper can't silently
  ;; drop the delegation.
  (let [strip-by-id-2 (fn [items _include?]
                        (let [kept (filterv #(not= 2 (:id %)) items)
                              n    (- (count items) (count kept))]
                          [kept n]))
        snap          {:rf/default {:traces [{:id 1} {:id 2} {:id 3} {:id 2}]}}
        [out dropped] (sensitive/scrub-snapshot snap false strip-by-id-2)]
    (is (= 2 dropped))
    (is (= [{:id 1} {:id 3}] (get-in out [:rf/default :traces])))))

;; ---------------------------------------------------------------------------
;; Malformed counter — operator-surface observability for the fail-
;; closed gate (rf2-8cpsg / F19).
;; ---------------------------------------------------------------------------

(deftest malformed-count-increments-on-fail-closed-drop
  ;; The fail-closed posture (rf2-ih7g4) drops a non-boolean truthy
  ;; `:sensitive?` stamp AND increments a process-wide counter so
  ;; operator surfaces can see the contract drift. Pre-fix, the
  ;; counter was private and untestable; now `malformed-count` /
  ;; `reset-malformed-count!` are public so this regression pin
  ;; exists.
  (sensitive/reset-malformed-count!)
  (is (zero? (sensitive/malformed-count))
      "precondition: counter starts at zero after reset")
  (binding [*err* (java.io.StringWriter.)] ; absorb the warning
    (sensitive/sensitive-event? {:sensitive? "true"})
    (sensitive/sensitive-event? {:sensitive? :yes})
    (sensitive/sensitive-event? {:sensitive? 1}))
  (is (= 3 (sensitive/malformed-count))
      "every fail-closed drop bumps the counter once")
  ;; Well-formed stamps don't bump the counter.
  (sensitive/sensitive-event? {:sensitive? true})
  (sensitive/sensitive-event? {:sensitive? false})
  (sensitive/sensitive-event? {:sensitive? nil})
  (sensitive/sensitive-event? {})
  (is (= 3 (sensitive/malformed-count))
      "true / false / nil / absent stamps do NOT bump the counter")
  (sensitive/reset-malformed-count!))

(deftest reset-malformed-count!-zeroes-the-counter
  (binding [*err* (java.io.StringWriter.)]
    (sensitive/sensitive-event? {:sensitive? "true"}))
  (is (pos? (sensitive/malformed-count))
      "precondition: a fail-closed drop has bumped the counter")
  (is (zero? (sensitive/reset-malformed-count!))
      "reset returns the new value (zero)")
  (is (zero? (sensitive/malformed-count))
      "reset zeroes the counter for the next test"))

(deftest scrub-snapshot-2-arity-delegates-to-strip-sensitive
  ;; The 2-arity form is the default-suppress shape used by story-mcp;
  ;; its contract that it delegates to `strip-sensitive` is
  ;; the load-bearing spec/009 §Privacy MUST. A regression that flipped
  ;; the default to a no-op (or any other predicate) wouldn't trip the
  ;; existing tests — those only exercise the 2-arity form's outputs
  ;; against trace-event-stamp inputs, never the parity-with-3-arity
  ;; contract directly. Pin it: the 2-arity output MUST equal the
  ;; 3-arity call with `strip-sensitive` explicit (rf2-jj46n / F21).
  (let [snap {:rf/default
              {:traces [{:id 1 :sensitive? false}
                        {:id 2 :sensitive? true}
                        {:id 3}
                        {:id 4 :sensitive? true}]
               :epochs [{:event-id :foo}
                        {:event-id :auth/sign-in :sensitive? true}]
               :machines {}}}
        two-arity   (sensitive/scrub-snapshot snap false)
        three-arity (sensitive/scrub-snapshot snap false sensitive/strip-sensitive)]
    (is (= two-arity three-arity)
        "2-arity MUST delegate to strip-sensitive — spec/009 §Privacy default")))
