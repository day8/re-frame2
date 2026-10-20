(ns day8.re-frame2-xray.panels.epoch.db-row-settle-runtime-test
  "The SIDE EFFECTS `:db` row, and the downstream mute, over the trace stream
  a real event records.

  An event whose `:fx` walk writes a flowed frame's state runs the flow pass
  again and installs its outputs inside the same event (Spec 013
  §Sequencing). That settle's candidate is validated like any other, so a
  rejected settle emits a `:rollback? true` app-db failure AFTER the event's
  own install and walk, which stood. Only a rejection that precedes both is
  the handler's write rolled back.

  The flow reads runtime-db `:probe/n` (nil reads as 0) into `[:derived]`,
  an `:int` slot; `:p/go` writes app-db and sets `:probe/n` mid-walk."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.event-emit :as rf.event-emit]
            ;; Loaded for their late-bind hooks: without them no flow runs,
            ;; no app schema validates and no epoch records.
            [re-frame.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- reg-settling-frame! []
  (rf/reg-flow :p/derived
    {:inputs [[:rf.db/runtime :probe/n]] :output-path [:derived]}
    (fn [n] (if (nil? n) 0 n)))
  (rf/make-frame {:id :rf/default :flows [:p/derived]})
  (rf/reg-app-schema [:derived] :int)
  (rf/reg-fx :p/set-n
    (fn [{:keys [frame]} n] (rf.frame/swap-runtime-db! frame assoc :probe/n n)))
  (rf/reg-event :p/go
    (fn [{:keys [db]} [_ n]]
      {:db (assoc db :handler-installed true)
       :fx [[:p/set-n n]]})))

(defn- dispatch-and-record!
  "Dispatch `event` and return the event-emit `:outcome`, the epoch record
  Xray projects, and the app-db the event left."
  [event]
  (let [outcomes (atom [])]
    (rf.event-emit/register-event-listener!
      ::outcome (fn [record] (swap! outcomes conj (:outcome record))))
    (try
      (rf/dispatch-sync event)
      (finally
        (rf.event-emit/unregister-event-listener! ::outcome)))
    {:outcomes @outcomes
     :record   (last (rf.epoch/epoch-history :rf/default))
     :db       (rf/app-db-value :rf/default)}))

(defn- ops [record]
  (mapv :operation (:trace-events record)))

(defn- count-op [record op]
  (count (filter #{op} (ops record))))

(deftest a-valid-post-walk-settle-leaves-the-db-row-applied
  (reg-settling-frame!)
  (let [{:keys [outcomes record db]} (dispatch-and-record! [:p/go 2])
        events (:trace-events record)]
    (testing "the scenario: the settle installed a second time"
      (is (= [:ok] outcomes))
      (is (= {:handler-installed true :derived 2} (select-keys db [:handler-installed :derived])))
      (is (= 2 (count-op record :rf.event/db-changed))))
    (is (= {:fx-id :db :status :ok} (proj/db-effect-row events)))
    (is (false? (proj/db-rolled-back? events)))))

(deftest a-rejected-post-walk-settle-leaves-the-db-row-applied
  (reg-settling-frame!)
  (let [{:keys [outcomes record db]} (dispatch-and-record! [:p/go "bad"])
        events (:trace-events record)]
    (testing "the scenario: the handler's install stood and the settle's
              candidate was rejected after it"
      (is (= [:ok] outcomes))
      (is (= {:handler-installed true :derived 0} (select-keys db [:handler-installed :derived])))
      (is (= 1 (count-op record :rf.event/db-changed)))
      (is (= 1 (count-op record :rf.error/schema-validation-failure))))
    (testing "the handler's write reads applied, not rolled back"
      (is (= {:fx-id :db :status :ok} (proj/db-effect-row events)))
      (is (false? (proj/db-rolled-back? events))))
    (testing "the settle's failure still surfaces, on the SIDE EFFECTS step"
      (let [se (some #(when (= :side-effects (:step %)) %) (proj/project record))]
        (is (= :error (proj/step-status se)))
        (is (= [:app-db]
               (mapv :where (concat (:violations se) (mapcat :violations (:rows se))))))))))

(deftest a-rejection-before-install-rolls-the-db-row-back
  (reg-settling-frame!)
  ;; The bad value is in place before the event, so its own flow pass
  ;; computes it and candidate validation refuses the whole transition.
  (rf.frame/swap-runtime-db! :rf/default assoc :probe/n "bad")
  (let [{:keys [outcomes record db]} (dispatch-and-record! [:p/go 3])
        events (:trace-events record)]
    (testing "the scenario: nothing installed and the walk never ran"
      (is (= [:rolled-back] outcomes))
      (is (not (contains? db :handler-installed)))
      (is (= 0 (count-op record :rf.event/db-changed))))
    (is (= {:fx-id :db :status :error} (proj/db-effect-row events)))
    (is (true? (proj/db-rolled-back? events)))))

(deftest a-malformed-app-schema-rolls-the-db-row-back
  ;; A schema whose check throws cannot prove the candidate conforms, so the
  ;; candidate is refused fail-closed with `:rf.error/malformed-schema`.
  (rf/make-frame {:id :rf/default})
  (rf/reg-app-schema [:m] [:multi {:dispatch (fn [_] (throw (ex-info "unevaluable" {})))}
                            [:a :int]])
  (rf/reg-event :p/set-m (fn [{:keys [db]} _] {:db (assoc db :m [1])}))
  (let [{:keys [outcomes record]} (dispatch-and-record! [:p/set-m])
        events (:trace-events record)]
    (testing "the scenario"
      (is (= [:rolled-back] outcomes))
      (is (= 1 (count-op record :rf.error/malformed-schema)))
      (is (= 0 (count-op record :rf.error/schema-validation-failure))))
    (is (= {:fx-id :db :status :error} (proj/db-effect-row events)))))
