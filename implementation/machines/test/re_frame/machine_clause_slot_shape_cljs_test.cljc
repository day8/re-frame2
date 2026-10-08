(ns re-frame.machine-clause-slot-shape-cljs-test
  "`reg-machine` REFUSES an `:on` / `:after` clause that is neither nil nor a
  map, at every position the clause can take, rather than throwing a host
  exception at it: `:rf.error/machine-bad-on-clause` for `:on`,
  `:rf.error/machine-bad-after-spec` for `:after`, each naming the `:slot`. A
  nil clause is absent and registers. Both hosts run it, because the host
  exception a non-map clause would otherwise raise differs per host."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; Load the machines facade so `rf/reg-machine` routes through its
   ;; late-bind hook (`:machines/reg-machine`).
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(defn- registration
  "Register `machine` under a fresh id. Returns `:registered`, the refusal's
  ex-data, or `{:host-throw <message>}` when the throw carries no
  `:rf.error/id`."
  [machine]
  (try (rf/reg-machine (keyword "clause-shape" (str (gensym))) machine) :registered
       (catch #?(:clj Throwable :cljs :default) t
         (let [data (ex-data t)]
           (if (:rf.error/id data)
             data
             {:host-throw #?(:clj (.getMessage ^Throwable t) :cljs (str t))})))))

(def ^:private positions
  "Position → a machine declaring `clause` in `slot` there. `:b` is a state the
  clause's transitions can target from each position."
  {:leaf          (fn [slot clause] {:initial :a :states {:a {slot clause} :b {}}})
   :compound      (fn [slot clause] {:initial :o
                                     :states  {:o {:initial :a slot clause :states {:a {}}} :b {}}})
   :region-state  (fn [slot clause] {:type    :parallel
                                     :regions {:r {:initial :a :states {:a {slot clause} :b {}}}}})
   :region-body   (fn [slot clause] {:type    :parallel
                                     :regions {:r {:initial :a slot clause :states {:a {} :b {}}}}})
   :flat-root     (fn [slot clause] {:initial :a slot clause :states {:a {} :b {}}})
   :parallel-root (fn [slot clause] {:type    :parallel slot clause
                                     :regions {:r {:initial :a :states {:a {} :b {}}}}})})

(def ^:private categories
  {:on    :rf.error/machine-bad-on-clause
   :after :rf.error/machine-bad-after-spec})

(deftest a-malformed-clause-is-refused-at-every-position
  (doseq [slot            [:on :after]
          [position make] positions
          clause          [:b [1000 :b]]]
    (testing (str slot " " (pr-str clause) " on the " position)
      (is (= {:rf.error/id (categories slot) :slot slot}
             (select-keys (registration (make slot clause)) [:rf.error/id :slot]))))))

(deftest a-nil-clause-is-absent
  (doseq [slot            [:on :after]
          [position make] positions]
    (testing (str slot " nil on the " position)
      (is (= :registered (registration (make slot nil)))))))
