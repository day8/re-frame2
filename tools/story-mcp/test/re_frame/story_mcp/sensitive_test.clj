(ns re-frame.story-mcp.sensitive-test
  "Unit tests for the spec/009 §Privacy default-suppress filter
  (rf2-zq0n1, follows rf2-a32kd).

  These tests pin the contract for the `re-frame.story-mcp.sensitive`
  helpers — the load-bearing piece is the *default posture* assertion:
  a batch with `:sensitive? true` events MUST be stripped unless the
  caller explicitly opts in."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story-mcp.sensitive :as sensitive]))

;; ---------------------------------------------------------------------------
;; sensitive-event? — the boolean predicate.
;; ---------------------------------------------------------------------------

(deftest sensitive-event-true-stamp-detected
  (is (sensitive/sensitive-event? {:operation :event/dispatched :sensitive? true})))

(deftest sensitive-event-false-stamp-passes
  (is (not (sensitive/sensitive-event? {:operation :event/dispatched :sensitive? false}))))

(deftest sensitive-event-absent-stamp-passes
  ;; Per spec/009: "Consumers treat absent as `false`."
  (is (not (sensitive/sensitive-event? {:operation :event/dispatched}))))

(deftest sensitive-event-non-true-truthy-passes
  ;; Conservative: only the literal `true` triggers the drop. A string
  ;; or non-boolean value passes through.
  (is (not (sensitive/sensitive-event? {:operation :event/dispatched :sensitive? "true"})))
  (is (not (sensitive/sensitive-event? {:operation :event/dispatched :sensitive? :yes}))))

(deftest sensitive-event-non-map-input-passes
  (is (not (sensitive/sensitive-event? nil)))
  (is (not (sensitive/sensitive-event? [:sensitive? true])))
  (is (not (sensitive/sensitive-event? "anything"))))

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
;; include-sensitive? — the per-call opt-in arg parser.
;; ---------------------------------------------------------------------------

(deftest include-sensitive-defaults-off
  (is (not (sensitive/include-sensitive? {})))
  (is (not (sensitive/include-sensitive? {:variant-id "x"})))
  (is (not (sensitive/include-sensitive? nil))))

(deftest include-sensitive-true-bool-enables
  (is (sensitive/include-sensitive? {:include-sensitive? true})))

(deftest include-sensitive-false-bool-disables
  (is (not (sensitive/include-sensitive? {:include-sensitive? false}))))

(deftest include-sensitive-truthy-strings-enable
  (testing "string-form booleans (parity with set-allow-writes! boot config)"
    (is (sensitive/include-sensitive? {:include-sensitive? "true"}))
    (is (sensitive/include-sensitive? {:include-sensitive? "1"}))
    (is (sensitive/include-sensitive? {:include-sensitive? "yes"}))
    (is (sensitive/include-sensitive? {:include-sensitive? "y"}))
    (is (sensitive/include-sensitive? {:include-sensitive? "on"}))
    (is (sensitive/include-sensitive? {:include-sensitive? "TRUE"}))))

(deftest include-sensitive-falsy-strings-disable
  (is (not (sensitive/include-sensitive? {:include-sensitive? "false"})))
  (is (not (sensitive/include-sensitive? {:include-sensitive? "0"})))
  (is (not (sensitive/include-sensitive? {:include-sensitive? ""}))))

;; ---------------------------------------------------------------------------
;; Default posture — the load-bearing assertion for the spec/009 MUST.
;; ---------------------------------------------------------------------------

(deftest spec-009-default-posture-is-suppress
  (testing "the default (no `include-sensitive?` arg) suppresses"
    (let [args  {:variant-id ":story.auth/sign-in"}
          batch [{:operation  :event/dispatched
                  :tags       {:event-id :auth/sign-in}
                  :sensitive? true}]
          [kept dropped] (sensitive/strip-sensitive batch
                                                    (sensitive/include-sensitive? args))]
      (is (= [] kept) "sensitive event must NOT reach the agent surface by default")
      (is (= 1 dropped))))
  (testing "`include-sensitive? true` is the documented opt-in"
    (let [args  {:variant-id ":story.auth/sign-in" :include-sensitive? true}
          batch [{:operation  :event/dispatched
                  :tags       {:event-id :auth/sign-in}
                  :sensitive? true}]
          [kept dropped] (sensitive/strip-sensitive batch
                                                    (sensitive/include-sensitive? args))]
      (is (= batch kept))
      (is (zero? dropped)))))

;; ---------------------------------------------------------------------------
;; Symmetry with pair2-mcp — the cross-server contract.
;; ---------------------------------------------------------------------------

(deftest cross-server-arg-name-is-include-sensitive?
  ;; The opt-in arg name is fixed cross-server. An agent that knows
  ;; the slot on pair2-mcp gets the same slot on story-mcp.
  ;;
  ;; If this test fails because someone renamed the arg, BREAK GLASS:
  ;; the rename has to land in pair2-mcp and causa-mcp simultaneously
  ;; or the agent surface fragments.
  (let [opted-in   (sensitive/include-sensitive? {:include-sensitive? true})
        opted-out  (sensitive/include-sensitive? {:include-sensitive? false})
        default    (sensitive/include-sensitive? {})]
    (is (true? opted-in))
    (is (false? opted-out))
    (is (false? default))))
