(ns re-frame.internal-events-cljs-test
  "Public/private `:internal-events`: the registration-time declaration rules,
  and the dispatch boundary — an EXTERNAL dispatch of a declared internal event
  is refused (`:rf.error/machine-internal-event-external-dispatch`, no state
  change) while the machine's own `:raise` of it is handled normally."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; Installs the late-bind hooks `rf/reg-machine` routes through.
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   [re-frame.subs]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]]))
  ;; `with-trace-capture` is a `#?(:clj (defmacro …))` in a `.cljc` support ns,
  ;; so the CLJS analyzer needs it required as a MACRO ns under the same alias.
  #?(:cljs (:require-macros [re-frame.machines.test-support :as rf.machines.test-support])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(def ^:private snapshot rf.machines.test-support/snapshot)

;; ---- registration-time validation (fail-loud) -----------------------------

(defn- reg-error-id [machine]
  (try (rf/reg-machine (keyword "iet" (str (gensym))) machine) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (:rf.error/id (ex-data e)))))

(deftest malformed-internal-events-are-rejected-at-registration
  (doseq [[label declaration expected]
          [["a VECTOR (the rejected XState array form)"
            [:tick] :rf.error/machine-bad-internal-events]
           ["a SET with a non-keyword member"
            #{:tick "tock"} :rf.error/machine-bad-internal-events]
           ["a reserved :rf/* framework event, even beside a legal one — framework lifecycle traffic can't be made private"
            #{:tick :rf.machine.spawn/spawned} :rf.error/machine-internal-event-reserved]]]
    (is (= expected (reg-error-id {:initial :a
                                   :internal-events declaration
                                   :states {:a {}}}))
        (str label " fails loud"))))

;; ---- dispatch boundary -----------------------------------------------------

(deftest external-dispatch-of-internal-event-rejected
  (testing "an EXTERNAL dispatch of a declared internal event is refused —
            before boot it installs no snapshot; after boot it drives no
            transition and emits the boundary error"
    (rf/reg-machine :iet/reject
      {:initial :waiting
       :internal-events #{:tick}
       :states {:waiting {:on {:tick {:target :checking}}}
                :checking {}}})
    (rf/dispatch-sync [:iet/reject [:tick]])
    (is (nil? (snapshot :iet/reject)) "refused before boot — no snapshot installed")
    (rf/dispatch-sync [:iet/reject [:rf.machine/start]])
    (rf.machines.test-support/with-trace-capture captured
      (rf/dispatch-sync [:iet/reject [:tick]])
      (is (= :waiting (:state (snapshot :iet/reject))))
      (is (= [:error] (->> @captured
                           (filter #(= :rf.error/machine-internal-event-external-dispatch
                                       (:operation %)))
                           (mapv :op-type)))))))

(deftest internal-raise-of-internal-event-handled
  (testing "an INTERNAL :raise of the same event IS handled — the boundary
            refuses only the outside caller"
    (rf/reg-machine :iet/raise
      {:initial :waiting
       :internal-events #{:tick}
       :actions {:kick (fn [_] {:fx [[:raise [:tick]]]})}
       :states {:waiting {:on {:go {:target :armed :action :kick}}}
                :armed {:on {:tick {:target :checking}}}
                :checking {}}})
    (rf/dispatch-sync [:iet/raise [:go]])
    (is (= :checking (:state (snapshot :iet/raise))))))
