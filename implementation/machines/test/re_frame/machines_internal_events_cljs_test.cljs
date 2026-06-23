(ns re-frame.machines-internal-events-cljs-test
  "CLJS-side coverage for EP-0029 A6 public / private `:internal-events`
  under the Reagent reactive substrate.

  Confirms cross-host parity for:
    - the declaration shape — a Clojure SET of keywords (the re-frame2
      set-form divergence from XState's array);
    - registration fail-loud validation (a VECTOR / non-keyword / non-set
      member; a reserved `:rf/*` framework event declared internal);
    - the dispatch boundary — an EXTERNAL dispatch of a declared internal
      event is REJECTED (no state change), while an internal `:raise` of the
      SAME event drives a transition within the macrostep.

  Mirrors the JVM internal_events_test.clj."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.internal-events :as internal-events]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.machines.test-support :as mtest]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(def ^:private snapshot mtest/snapshot)

(deftest boundary-predicate-cljs
  (testing "internal-event-external? recognises declared internal events cross-host"
    (let [m {:internal-events #{:tick :retry/internal}}]
      (is (true?  (internal-events/internal-event-external? m [:tick])))
      (is (true?  (internal-events/internal-event-external? m [:retry/internal])))
      (is (false? (internal-events/internal-event-external? m [:public-event])))
      (is (false? (internal-events/internal-event-external? {:initial :a} [:tick]))))))

(defn- reg-error-id [machine]
  (try (rf/reg-machine (keyword "iet" (str (gensym))) machine) nil
       (catch :default e (:rf.error/id (ex-data e)))))

(deftest registration-validation-cljs
  (testing "a well-formed :internal-events SET registers cleanly cross-host"
    (is (nil? (reg-error-id {:initial :waiting
                             :internal-events #{:tick}
                             :states {:waiting {:on {:tick {:target :checking}}}
                                      :checking {}}}))))
  (testing "a VECTOR :internal-events (the XState array form) is rejected cross-host"
    (is (= :rf.error/machine-bad-internal-events
           (reg-error-id {:initial :a :internal-events [:tick] :states {:a {}}}))))
  (testing "a non-keyword member is rejected cross-host"
    (is (= :rf.error/machine-bad-internal-events
           (reg-error-id {:initial :a :internal-events #{:tick "tock"} :states {:a {}}}))))
  (testing "a reserved :rf/* framework event in :internal-events is rejected cross-host"
    (is (= :rf.error/machine-internal-event-reserved
           (reg-error-id {:initial :a
                          :internal-events #{:rf.machine/start}
                          :states {:a {}}})))))

(deftest external-dispatch-rejected-cljs
  (testing "an EXTERNAL dispatch of a declared internal event is refused cross-host
            (no state change)"
    (let [m {:initial :waiting
             :internal-events #{:tick}
             :states {:waiting {:on {:tick {:target :checking}}}
                      :checking {}}}]
      (rf/reg-machine :iet/reject m)
      (rf/dispatch-sync [:iet/reject [:rf.machine/start]])
      (is (= :waiting (:state (snapshot :iet/reject))) "booted at :waiting")
      (rf/dispatch-sync [:iet/reject [:tick]])
      (is (= :waiting (:state (snapshot :iet/reject)))
          "the external :tick was rejected — the machine stayed at :waiting"))))

(deftest internal-raise-handled-cljs
  (testing "an INTERNAL :raise of the SAME event IS handled cross-host"
    (let [m {:initial :waiting
             :internal-events #{:tick}
             :actions {:kick (fn [_] {:fx [[:raise [:tick]]]})}
             :states {:waiting {:on {:go {:target :armed :action :kick}}}
                      :armed {:on {:tick {:target :checking}}}
                      :checking {}}}]
      (rf/reg-machine :iet/raise m)
      (rf/dispatch-sync [:iet/raise [:go]])
      (is (= :checking (:state (snapshot :iet/raise)))
          "the internally-raised :tick drove :armed → :checking"))))
