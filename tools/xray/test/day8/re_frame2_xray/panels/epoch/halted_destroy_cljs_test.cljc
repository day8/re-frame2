(ns day8.re-frame2-xray.panels.epoch.halted-destroy-cljs-test
  "rf2-v6ftp — a `:halted-destroy` epoch record, produced by a REAL
  self-destroying handler and projected by the Epoch panel's projection.

  A destroy is a deliberate lifecycle stop, not an error: the framework's
  consumer-facing mapping sends it to `:blocked`. So the projection shows no
  card, reads HANDLER as having run with its result discarded, and takes SIDE
  EFFECTS from what the producer actually traced rather than assuming any
  were skipped.

  Every record here comes from the producer. A handler dispatched with
  `dispatch-sync` destroys its own frame, the epoch listener hands back the
  `:halted-destroy` record, and `proj/project` projects it. Nothing is
  hand-authored.

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
  history, so the listener is the only channel for its terminal record."
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
  "`::log` appends its arg to `ran`, so the test can read which effects the
  producer really executed."
  [ran]
  (rf/reg-fx ::log (fn [_ v] (swap! ran conj v))))

(deftest destroy-in-the-handler-body
  (testing "the handler destroys its own frame and returns :db + :fx: outcome
            :blocked, no card, HANDLER ran with its result discarded, and no
            SIDE EFFECTS step because the producer ran none"
    (let [ran (atom [])]
      (reg-log-fx! ran)
      (rf/reg-event ::destroy-in-body
        (fn [{:keys [db]} _]
          (rf/destroy-frame! ::body-frame)
          {:db (assoc db :written? true)
           :fx [[::log :after-destroy]]}))
      (let [[record & more] (capture-records! ::body-frame [::destroy-in-body])
            steps           (proj/project record)
            by              (by-step steps)
            handler         (:handler by)]
        (is (nil? more) "exactly one record")
        (is (= :halted-destroy (:outcome record))
            "the producer committed a :halted-destroy record")
        (is (empty? @ran) "the producer ran none of the handler's effects")
        (is (= :blocked (proj/epoch-outcome steps))
            "outcome follows the framework's :blocked mapping, not :error")
        (is (every? #(empty? (:errors %)) steps)
            "no Exception card on any step")
        (is (= :ok (proj/step-status (:dispatch by))))
        (is (= :ok (proj/step-status handler))
            "HANDLER ran — it is not marked skipped")
        (is (true? (:result-discarded? handler))
            "HANDLER says its result was discarded, not that it returned no :db")
        (is (nil? (:side-effects by))
            "no SIDE EFFECTS step: exactly what the producer ran, which is nothing")))))

(deftest destroy-from-an-effect
  (testing "an effect destroys the frame between two effects: the :db and the
            first effect committed, so HANDLER shows its write and SIDE EFFECTS
            shows exactly what ran before the destroy"
    (let [ran (atom [])]
      (reg-log-fx! ran)
      (rf/reg-fx ::destroy-frame (fn [_ frame] (rf/destroy-frame! frame)))
      (rf/reg-event ::destroy-in-fx
        (fn [{:keys [db]} _]
          {:db (assoc db :written? true)
           :fx [[::log :before]
                [::destroy-frame ::fx-frame]
                [::log :after]]}))
      (let [[record & more] (capture-records! ::fx-frame [::destroy-in-fx])
            steps           (proj/project record)
            by              (by-step steps)
            handler         (:handler by)
            fx-rows         (:rows (:side-effects by))]
        (is (nil? more) "exactly one record")
        (is (= :halted-destroy (:outcome record)))
        (is (= [:before] @ran) "the producer ran the first effect only")
        (is (= :blocked (proj/epoch-outcome steps)))
        (is (every? #(empty? (:errors %)) steps) "no Exception card")
        (is (true? (:db-write? handler)) "the :db committed before the destroy")
        (is (nil? (:result-discarded? handler))
            "a committed result is not called discarded")
        (is (= [:db ::log] (mapv :fx-id fx-rows))
            "SIDE EFFECTS lists what ran and nothing it did not")
        (is (= :before (:args (second fx-rows))))
        (is (not= :skipped (proj/step-status (:side-effects by)))
            "SIDE EFFECTS is not assumed skipped")))))

(deftest control-without-a-destroy
  (testing "the same handler without the destroy settles :ok and reads clean"
    (let [ran (atom [])]
      (reg-log-fx! ran)
      (rf/reg-event ::no-destroy
        (fn [{:keys [db]} _]
          {:db (assoc db :written? true)
           :fx [[::log :ran]]}))
      (let [[record] (capture-records! ::control-frame [::no-destroy])
            steps    (proj/project record)
            handler  (:handler (by-step steps))]
        (is (= :ok (:outcome record)))
        (is (= [:ran] @ran))
        (is (= :ok (proj/epoch-outcome steps)))
        (is (nil? (:result-discarded? handler)))))))
