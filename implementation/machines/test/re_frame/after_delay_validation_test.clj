(ns re-frame.after-delay-validation-test
  "Per rf2-apfait (Finding 2) — `:after` delay KEYS are validated at
  registration. The schema (Spec-Schemas §`:rf/state-node` `:after`,
  1699-1705) constrains an `:after` map key to one of three closed forms:
  a positive integer (literal ms), a non-empty subscription vector
  (`[sub-id & args]`), or a function. Before this fix, an invalid STATIC
  key (`-1`, `0`, `\"soon\"`, `nil`, `[]`) passed `validate-machine!` and
  degraded to a runtime `:rf.warning/no-clock-configured` no-op at fx time
  — delaying authoring feedback and letting tools/conformance treat an
  invalid machine as valid.

  `validate-machine!` now rejects such keys with
  `:rf.error/machine-bad-after-delay`. DYNAMIC delays (a subscription
  vector / fn that RESOLVES to an invalid ms at runtime) keep their
  fx-time warning — only the static key shape is gated here (covered by
  `re-frame.after-test`)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- registration-throws?
  "Try registering `machine` under `machine-id`. Returns the
  ExceptionInfo if registration threw, else nil."
  [machine-id machine]
  (try (rf/reg-machine machine-id machine) nil
       (catch clojure.lang.ExceptionInfo e e)))

(defn- mk-machine
  "A flat machine whose `:running` state declares a single `:after` entry
  keyed by `delay-key`."
  [delay-key]
  {:initial :idle
   :data    {}
   :states  {:idle    {:on {:go :running}}
             :running {:after {delay-key :timeout}}
             :timeout {}}})

;; ---- invalid STATIC delay keys are rejected at registration --------------

(deftest negative-integer-delay-key-rejected
  (testing "an :after key of -1 fails registration with :rf.error/machine-bad-after-delay"
    (let [thrown (registration-throws? :adv/neg (mk-machine -1))]
      (is (some? thrown) "negative :after delay SHOULD throw at registration")
      (is (= :rf.error/machine-bad-after-delay (:rf.error/id (ex-data thrown)))
          "error category names the bad-after-delay contract")
      (is (= -1 (:delay-key (ex-data thrown)))
          "ex-data carries the offending delay key")
      (is (= :after (:slot (ex-data thrown)))
          "ex-data names the :after slot"))))

(deftest zero-delay-key-rejected
  (testing "an :after key of 0 fails registration — pos-int? excludes zero"
    (let [thrown (registration-throws? :adv/zero (mk-machine 0))]
      (is (some? thrown) "zero :after delay SHOULD throw at registration")
      (is (= :rf.error/machine-bad-after-delay (:rf.error/id (ex-data thrown)))))))

(deftest string-delay-key-rejected
  (testing "an :after key of \"soon\" fails registration"
    (let [thrown (registration-throws? :adv/str (mk-machine "soon"))]
      (is (some? thrown) "string :after delay SHOULD throw at registration")
      (is (= :rf.error/machine-bad-after-delay (:rf.error/id (ex-data thrown))))
      (is (= "soon" (:delay-key (ex-data thrown)))))))

(deftest nil-delay-key-rejected
  (testing "an :after key of nil fails registration"
    (let [thrown (registration-throws? :adv/nil (mk-machine nil))]
      (is (some? thrown) "nil :after delay SHOULD throw at registration")
      (is (= :rf.error/machine-bad-after-delay (:rf.error/id (ex-data thrown)))))))

(deftest empty-subscription-vector-delay-key-rejected
  (testing "an :after key of [] (empty subscription vector) fails registration"
    (let [thrown (registration-throws? :adv/empty-vec (mk-machine []))]
      (is (some? thrown) "empty-vector :after delay SHOULD throw at registration")
      (is (= :rf.error/machine-bad-after-delay (:rf.error/id (ex-data thrown)))))))

;; ---- valid STATIC delay keys register cleanly ----------------------------

(deftest positive-integer-delay-key-accepted
  (testing "a positive-integer :after key registers without error"
    (is (nil? (registration-throws? :adv/pos (mk-machine 5000)))
        "literal pos-int ms is a valid :after delay key")))

(deftest subscription-vector-delay-key-accepted
  (testing "a non-empty subscription-vector :after key registers without error"
    (rf/reg-sub :adv/timeout-cfg (fn [_ _] 5000))
    (is (nil? (registration-throws? :adv/sub (mk-machine [:adv/timeout-cfg])))
        "non-empty subscription-vector is a valid :after delay key")))

(deftest function-delay-key-accepted
  (testing "a function :after key registers without error"
    (is (nil? (registration-throws?
                :adv/fn (mk-machine (fn [{snap :snapshot}]
                                      (* 1000 (:n (:data snap)))))))
        "fn-valued delay is a valid :after delay key")))

;; ---- coverage: invalid key on a NESTED / ROOT :after also rejected -------

(deftest invalid-delay-key-on-nested-state-rejected
  (testing "an invalid :after key on a NESTED compound child fails registration"
    (let [m {:initial :outer
             :data    {}
             :states  {:outer {:initial :inner
                               :states  {:inner {:after {-5 :inner}}}}}}
          thrown (registration-throws? :adv/nested m)]
      (is (some? thrown) "nested invalid :after delay SHOULD throw")
      (is (= :rf.error/machine-bad-after-delay (:rf.error/id (ex-data thrown)))))))

(deftest invalid-delay-key-on-root-after-rejected
  (testing "an invalid :after key on the machine-root :after fallback fails registration"
    (let [m {:initial :idle
             :data    {}
             :after   {0 :idle}
             :states  {:idle {}}}
          thrown (registration-throws? :adv/root m)]
      (is (some? thrown) "root-level invalid :after delay SHOULD throw")
      (is (= :rf.error/machine-bad-after-delay (:rf.error/id (ex-data thrown)))))))
