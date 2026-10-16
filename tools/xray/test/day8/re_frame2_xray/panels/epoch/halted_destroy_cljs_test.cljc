(ns day8.re-frame2-xray.panels.epoch.halted-destroy-cljs-test
  "Epoch records around a REAL self-destroying handler, projected by the Epoch
  panel's projection.

  A destroy requested during the frame's own drain waits for the event
  boundary (Spec 002 §Destroy): the destroying event finishes as an ordinary
  event, so it projects as an ordinary `:ok` epoch with every effect it ran.
  A `:halted-destroy` record exists only when the boundary dropped queued work;
  it names the event the drain would have run next, and the framework's
  consumer-facing mapping sends it to `:blocked` — a deliberate lifecycle stop,
  not an error, so no card.

  Every record here comes from the producer. A handler dispatched with
  `dispatch-sync` destroys its own frame, the epoch listener hands back the
  records, and `proj/project` projects them. Nothing is hand-authored.

  Named `*-cljs-test.cljc` so both cognitect.test-runner (JVM) and
  shadow-cljs's `cljs-test$` build discover it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [day8.re-frame2-xray.panels.epoch.projection :as proj]
   [re-frame.core :as rf]
   [re-frame.epoch]
   [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
   [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- capture-records!
  "Make `frame`, dispatch `event` into it, and return every epoch record the
  producer delivered to an epoch listener. A destroyed frame keeps no epoch
  history, so the listener is the only channel for its records."
  [frame event]
  (let [records (atom [])]
    (rf/register-listener! :epoch ::records (fn [r] (swap! records conj r)))
    (try
      (rf/make-frame {:id frame})
      (rf/dispatch-sync event {:frame frame})
      (finally
        (rf/unregister-listener! :epoch ::records)))
    @records))

(defn- by-step [steps]
  (into {} (map (juxt :step identity)) steps))

(defn- reg-log-fx!
  "`::log` is the plain effect the handlers below return, so SIDE EFFECTS
  has a real effect to list."
  []
  (rf/reg-fx ::log (fn [_ _] nil)))

(deftest destroy-in-the-handler-body
  (testing "the handler destroys its own frame and returns :db + :fx: the event
            commits as an ordinary :ok epoch, HANDLER's result is not
            discarded, and SIDE EFFECTS lists everything it ran"
    (reg-log-fx!)
    (rf/reg-event ::destroy-in-body
      (fn [{:keys [db]} _]
        (rf/destroy-frame! ::body-frame)
        {:db (assoc db :written? true)
         :fx [[::log :after-destroy]]}))
    (let [records  (capture-records! ::body-frame [::destroy-in-body])
          [record] records
          steps    (proj/project record)
          by       (by-step steps)]
      (is (= [:ok] (mapv :outcome records))
          "one ordinary record: nothing was queued, so the boundary dropped nothing and wrote no :halted-destroy")
      (is (= :ok (proj/epoch-outcome steps)))
      (is (nil? (:result-discarded? (:handler by)))
          "a committed result is not called discarded")
      (is (= [[:db nil] [::log :after-destroy]]
             (mapv (juxt :fx-id :args) (:rows (:side-effects by))))
          "SIDE EFFECTS lists the :db commit and the effect that ran after the destroy request"))))

(deftest destroy-from-an-effect
  (testing "an effect destroys the frame between two effects: every effect
            runs, because the destroy waits for the event boundary"
    (reg-log-fx!)
    (rf/reg-fx ::destroy-frame (fn [_ frame] (rf/destroy-frame! frame)))
    (rf/reg-event ::destroy-in-fx
      (fn [{:keys [db]} _]
        {:db (assoc db :written? true)
         :fx [[::log :before]
              [::destroy-frame ::fx-frame]
              [::log :after]]}))
    (let [[record] (capture-records! ::fx-frame [::destroy-in-fx])
          by       (by-step (proj/project record))]
      (is (nil? (:result-discarded? (:handler by)))
          "a committed result is not called discarded")
      (is (= [[:db nil] [::log :before] [::destroy-frame ::fx-frame] [::log :after]]
             (mapv (juxt :fx-id :args) (:rows (:side-effects by))))
          "SIDE EFFECTS lists every effect, the one after the destroy included"))))

(deftest queued-work-dropped-at-the-boundary
  (testing "the destroying handler queues a follow-up event: the boundary drops
            it, so the producer hands the panel the destroying event's :ok
            record and then a :halted-destroy record naming the dropped event,
            which carries no trace of its own because it never ran"
    (rf/reg-event ::never-runs (fn [{:keys [db]} _] {:db (assoc db :ran? true)}))
    (rf/reg-event ::destroy-with-queued-work
      (fn [_ _]
        (rf/destroy-frame! ::queued-frame)
        {:fx [[:dispatch [::never-runs]]]}))
    (let [records (capture-records! ::queued-frame [::destroy-with-queued-work])]
      (is (= [[::destroy-with-queued-work :ok] [::never-runs :halted-destroy]]
             (mapv (juxt :event-id :outcome) records)))
      (is (empty? (:trace-events (second records)))
          "the dropped event ran nothing, so its record carries no trace"))))
