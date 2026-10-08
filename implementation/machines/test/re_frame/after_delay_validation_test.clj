(ns re-frame.after-delay-validation-test
  "`:after` delay KEYS are validated at registration. A key is a positive
  integer (literal ms), an ISO-8601 duration string (`\"PT5S\"`, the `:timeout`
  duration grammar), a non-empty subscription vector, or a function; any other
  static key is rejected with `:rf.error/machine-bad-after-delay`. A dynamic
  key that resolves badly at runtime is reported at fx time instead
  (`after_dynamic_delay_report_test.clj`)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- registration-error
  "The ex-data of the error `machine` throws at registration, or nil when it
  registers cleanly."
  [machine]
  (try (rf/reg-machine (keyword "adv" (str (gensym "m"))) machine) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- mk-machine
  "A flat machine whose `:running` state declares one `:after` keyed by
  `delay-key`."
  [delay-key]
  {:initial :idle
   :data    {}
   :states  {:idle    {:on {:go :running}}
             :running {:after {delay-key :timeout}}
             :timeout {}}})

(deftest invalid-static-delay-keys-are-rejected-at-registration
  (doseq [[label machine delay-key]
          [["0: pos-int? excludes zero" (mk-machine 0) 0]
           ["\"5s\": the shorthand :timeout refuses too" (mk-machine "5s") "5s"]
           ["nil" (mk-machine nil) nil]
           ["[]: an empty subscription vector" (mk-machine []) []]
           ["-5 on a NESTED compound child"
            {:initial :outer
             :data    {}
             :states  {:outer {:initial :inner
                               :states  {:inner {:after {-5 :inner}}}}}}
            -5]
           ["0 on a :type :parallel root's :after, the supported root :after"
            {:type    :parallel
             :after   {0 {:target [:a :two]}}
             :regions {:a {:initial :one :states {:one {} :two {}}}}}
            0]]]
    (is (= {:rf.error/id :rf.error/machine-bad-after-delay :slot :after :delay-key delay-key}
           (select-keys (registration-error machine) [:rf.error/id :slot :delay-key]))
        label)))

(deftest iso-8601-delay-key-arms-its-ms
  (rf/reg-machine :adv/iso-run (mk-machine "PT1H"))
  (rf/dispatch-sync [:adv/iso-run [:go]])
  (is (= [["PT1H" 3600000]]
         (for [[k entry] (get @rf.machines.timer/after-timers :rf/default)]
           [(:delay k) (:resolved-ms entry)]))
      "one timer, keyed by the key the author wrote, armed at the string's milliseconds"))
