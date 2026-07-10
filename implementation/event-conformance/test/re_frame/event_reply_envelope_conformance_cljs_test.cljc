(ns re-frame.event-reply-envelope-conformance-cljs-test
  "Conformance for delivering managed-effect replies through the event model.

  `reply-conformance` owns the pure reply vocabulary and laws; family suites own
  their lowering. This integration suite proves the remaining boundary: completing
  a `:rf/reply-to` target appends one canonical reply map to the event vector, and
  the ordinary `reg-event` pipeline receives it with normal coeffects/effects
  semantics.

  The stale case covers framework/tool delivery authority. App-target stale
  suppression remains the responsibility of the managed-effect runtime."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.reply :as reply]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private completed-at-ms 1781078400456)

(def ^:private canonical-reply
  {:status       :ok
   :value        {:article {:id 42 :title "Welcome"}}
   :work/id      [:rf.work/resource [:rf.scope/global :article/by-id {:id 42}] 1]
   :work/kind    :resource
   :rf.reply/work-status  :completed
   :rf.frame/id  :rf/default
   :completed-at completed-at-ms})

(deftest reply-completion-is-an-ordinary-reg-event-dispatch
  (testing "a completed reply target is an ordinary event with the reply last"
    (is (reply/valid-reply? canonical-reply)
        (str "the fixture reply must be a canonical uniform envelope: "
             (reply/validate-reply canonical-reply)))
    (let [seen-event (atom ::unset)
          seen-cofx  (atom ::unset)
          target     [:article/loaded {:id 42}]]
      (rf/reg-sub :evt-reply/last-title (fn [db _] (:title db)))
      (rf/reg-event :article/loaded
        (fn [coeffects event]
          (reset! seen-event event)
          (reset! seen-cofx coeffects)
          (let [reply (peek event)]
            {:db (assoc (:db coeffects) :title (get-in reply [:value :article :title]))})))

      (let [completed (reply/complete target canonical-reply)]
        (is (= [:article/loaded {:id 42} canonical-reply] completed)
            "complete appends the reply map as the FINAL event argument")
        (rf/dispatch-sync completed))

      (testing "the reg-event handler saw the FULL event vector with the reply in final position"
        (is (= [:article/loaded {:id 42} canonical-reply] @seen-event)
            "the handler's event arg is the completed vector, reply map last")
        (let [delivered (peek @seen-event)]
          (testing "the canonical reply facts are preserved on the delivered event arg"
            (is (= :ok (:status delivered))             ":status preserved")
            (is (= {:article {:id 42 :title "Welcome"}} (:value delivered)) ":value preserved")
            (is (nil? (:error delivered))               "no :error on an :ok reply")
            (is (= [:rf.work/resource [:rf.scope/global :article/by-id {:id 42}] 1]
                   (:work/id delivered))                ":work/id tuple preserved")
            (is (= :rf.work/resource (first (:work/id delivered)))
                ":work/id head is the family head")
            (is (= :resource (:work/kind delivered))    ":work/kind preserved")
            (is (= :completed (:rf.reply/work-status delivered)) ":rf.reply/work-status preserved")
            (is (= :rf/default (:rf.frame/id delivered)) ":rf.frame/id preserved")
            (is (= completed-at-ms (:completed-at delivered)) ":completed-at (EP-0017) preserved")
            (is (contains? reply/statuses (:status delivered))
                ":status is in the ONE closed status vocabulary")
            (is (contains? reply/work-statuses (:rf.reply/work-status delivered))
                ":rf.reply/work-status is in the ONE closed work-status vocabulary"))))

      (testing "the reply event got ORDINARY event-model semantics — a coeffects
                map IN (the reg-event-fx shape), not a bare db, and the {:db …}
                effect committed"
        (is (map? @seen-cofx)         "the handler received the coeffects MAP")
        (is (contains? @seen-cofx :db) "`:db` delivered IN the coeffects map")
        (is (= :rf/default (:rf.frame/id @seen-cofx))
            "the ambient frame id is delivered as :rf.frame/id")
        (is (= "Welcome" @(rf/subscribe [:evt-reply/last-title]))
            "the {:db …} effect derived from the reply value committed")))))

(deftest a-stale-reply-envelope-is-also-an-ordinary-event
  (testing "an authorised stale reply is delivered as an ordinary event"
    (let [target  (reply/with-stale-authority [:article/loaded {:id 42}])
          carried {:work/id [:rf.work/resource [:rf.scope/global :r {}] 4] :generation 4}
          current {:work/id [:rf.work/resource [:rf.scope/global :r {}] 5] :generation 5}
          {:keys [reply]} (reply/suppress
                            (assoc target :dispatch-stale? true)
                            carried current
                            {:work/id     (:work/id carried)
                             :work/kind   :resource
                             :rf.frame/id :rf/default})
          seen-event (atom ::unset)]
      (is (reply/valid-reply? reply)
          (str "the suppressed reply must be a canonical stale envelope: "
               (reply/validate-reply reply)))
      (rf/reg-event :article/loaded
        (fn [coeffects event]
          (reset! seen-event event)
          {:db (:db coeffects)}))
      (let [completed (reply/complete (assoc target :dispatch-stale? true) reply)]
        (rf/dispatch-sync completed))
      (let [delivered (peek @seen-event)]
        (is (= :stale (:status delivered))    "the delivered envelope is :status :stale")
        (is (= :suppressed (:rf.reply/work-status delivered)) ":rf.reply/work-status :suppressed")
        (is (true? (:stale? delivered))       "the :stale? marker rides")
        (is (some? (:rf.reply/stale-reason delivered)) "a :rf.reply/stale-reason rides")
        (is (not (contains? delivered :value))
            "a stale reply carries NO :value — it mutates no app state")
        (is (contains? reply/statuses (:status delivered))
            ":stale is in the ONE closed status vocabulary")))))
