(ns re-frame.after-delay-validation-test
  "`:after` delay KEYS are validated at registration. An `:after` map key
  takes one of four closed forms: a positive integer (literal ms), an
  ISO-8601 duration string (`\"PT5S\"`, the `:timeout` duration grammar), a
  non-empty subscription vector (`[sub-id & args]`), or a function. An
  invalid STATIC key (`-1`, `0`, `\"soon\"`, the `\"5s\"` shorthand, `nil`, `[]`) is
  rejected by `validate-machine!` with `:rf.error/machine-bad-after-delay`,
  giving authoring-time feedback rather than letting tools/conformance treat
  an invalid machine as valid.

  DYNAMIC delays (a subscription vector / fn that RESOLVES to an invalid ms
  at runtime) report `:rf.error/machine-bad-after-delay` at fx time —
  only the static key shape is gated here (covered by
  `re-frame.after-test`)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

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

(deftest invalid-static-delay-keys-are-rejected-at-registration
  (doseq [[label machine-id machine delay-key]
          [["-1: a negative integer" :adv/neg (mk-machine -1) -1]
           ["0: pos-int? excludes zero" :adv/zero (mk-machine 0) 0]
           ["\"soon\": not a duration" :adv/str (mk-machine "soon") "soon"]
           ["\"5s\": the shorthand :timeout refuses too" :adv/shorthand (mk-machine "5s") "5s"]
           ["nil" :adv/nil (mk-machine nil) nil]
           ["[]: an empty subscription vector" :adv/empty-vec (mk-machine []) []]
           ["-5 on a NESTED compound child" :adv/nested
            {:initial :outer
             :data    {}
             :states  {:outer {:initial :inner
                               :states  {:inner {:after {-5 :inner}}}}}}
            -5]
           ["0 on a :type :parallel root's :after, the supported root :after"
            :adv/parallel-root
            {:type    :parallel
             :after   {0 {:target [:a :two]}}
             :regions {:a {:initial :one :states {:one {} :two {}}}}}
            0]]]
    (is (= {:rf.error/id :rf.error/machine-bad-after-delay :slot :after :delay-key delay-key}
           (select-keys (ex-data (registration-throws? machine-id machine))
                        [:rf.error/id :slot :delay-key]))
        (str label " throws :rf.error/machine-bad-after-delay naming the :after slot and the key"))))

;; ---- valid STATIC delay keys register cleanly ----------------------------

(deftest valid-static-delay-keys-register
  (rf/reg-sub :adv/timeout-cfg (fn [_ _] 5000))
  (doseq [[label machine-id delay-key]
          [["a positive integer (literal ms)" :adv/pos 5000]
           ["an ISO-8601 duration string, as on :timeout" :adv/iso "PT1S"]
           ["an ISO-8601 duration string with hours and minutes" :adv/iso-hm "PT1H30M"]
           ["a non-empty subscription vector" :adv/sub [:adv/timeout-cfg]]
           ["a function" :adv/fn (fn [{snap :snapshot}] (* 1000 (:n (:data snap))))]]]
    (is (nil? (registration-throws? machine-id (mk-machine delay-key)))
        (str label " is a valid :after delay key"))))

(deftest iso-8601-delay-key-arms-its-ms
  (testing "entering the state arms the timer at the string's milliseconds —
            the parser :timeout uses, applied where the key lowers to ms"
    (rf/reg-machine :adv/iso-run (mk-machine "PT1H"))
    (rf/dispatch-sync [:adv/iso-run [:go]])
    (let [entries (vals (get @rf.machines.timer/after-timers :rf/default))
          entry   (first (filter #(= :running (:state %)) entries))]
      (is (= 1 (count entries)) "one timer is armed")
      (is (= 3600000 (:resolved-ms entry)) "\"PT1H\" arms at 3600000 ms")
      (is (= #{"PT1H"} (set (map :delay (keys (get @rf.machines.timer/after-timers :rf/default)))))
          "the timer stays keyed by the key the author wrote"))))

;; ---- a non-parallel root :after is refused before its delay key is read --

(deftest invalid-delay-key-on-non-parallel-root-after-rejected-categorically
  (testing "a non-parallel root :after fails registration regardless of delay-key validity"
    ;; A non-parallel (flat/compound) machine root's :after has no runtime
    ;; scheduling / resolution path at ALL, so
    ;; `validate-non-parallel-root-after!` — which runs BEFORE
    ;; `validate-after-delays!` — rejects it CATEGORICALLY, regardless of
    ;; whether its delay key would otherwise be well-formed. The machine
    ;; shape itself is rejected first, with the more specific diagnostic,
    ;; so an invalid root :after key never reaches
    ;; :rf.error/machine-bad-after-delay.
    (let [m {:initial :idle
             :data    {}
             :after   {0 :idle}
             :states  {:idle {}}}
          thrown (registration-throws? :adv/root m)]
      (is (some? thrown) "root-level :after (invalid delay key or not) SHOULD throw")
      (is (= :rf.error/machine-non-parallel-root-after-not-supported
             (:rf.error/id (ex-data thrown)))
          "the categorical non-parallel-root-:after rejection wins over the delay-key shape check"))))
