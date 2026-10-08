(ns re-frame.effect-map-shape-record-cljs-test
  "What a PRODUCTION build does, and learns, when a malformed effect-map
  envelope refuses an event.

  The REFUSAL is posture-independent: a foreign top-level effect key, or a
  non-sequential `:fx` value, aborts the event with no commit in every build,
  so dev never aborts what production commits. Only the dev-trace narration
  elides in production.

  The always-on `:rf.error/effect-map-shape` RECORD reaches off-box shippers
  from a production build, so its key set is pinned rather than sampled.
  `:offending-key` ships because it is program structure — a keyword the
  programmer typed — and without it a production build cannot route the error
  back to its source. The rejected `:value` is handler-built and possibly
  user-private, so it is omitted outright, and so is the `:reason` that
  interpolates it."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf.error-emit/clear-error-listeners!))}))

(def ^:private record-keys
  "Every slot on the always-on record: the `dispatch-on-error!` spine (with
  `:exception` nil — an in-band refusal is not a throw) plus `:error` and the
  one lifted attribution slot, `:offending-key`."
  #{:error :event :event-id :frame :time :exception :elapsed-ms :source-coord
    :offending-key})

(defn- record-always-on-errors
  "The records the always-on `:errors` registry — what an off-box shipper
  receives — saw while `body-fn` ran."
  [body-fn]
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::rec (fn [r] (swap! seen conj r)))
    (try (body-fn)
         (finally (rf.error-emit/unregister-error-listener! ::rec)))
    @seen))

(defn- shape-records [records]
  (filterv #(= :rf.error/effect-map-shape (:error %)) records))

(defn- refuse!
  "Seed `:n` 1, dispatch an event returning `extra` beside a `:db` write, and
  return the effect-map-shape records it fanned. Asserts the abort happened
  (no `:db` commit), so no assertion on the records can be green over a
  refusal that never occurred."
  [extra ev-id]
  (rf/reg-event :seed (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
  (rf/dispatch-sync [:seed])
  (rf/reg-event ev-id
    (fn [{:keys [db]} _] (merge {:db (assoc db :n 2)} extra)))
  (let [records (record-always-on-errors #(rf/dispatch-sync [ev-id]))]
    (is (= 1 (:n (rf.frame/frame-app-db-value :rf/default)))
        "the event aborted pre-commit")
    (shape-records records)))

(deftest a-foreign-top-level-key-refuses-the-event-in-every-build
  (testing "case (a) — a foreign top-level key aborts pre-commit; one record names the key"
    (is (= [:acme.billing/charge-card]
           (map :offending-key (refuse! {:acme.billing/charge-card {:cents 100}} :bad/foreign)))))
  (testing "case (b) — a non-sequential :fx value is the same refusal"
    (is (= [:fx] (map :offending-key (refuse! {:fx :oops} :bad/fx-value))))))

(deftest the-refusal-does-not-halt-the-drain
  (testing "a refused event leaves the frame able to run the next event"
    (rf/reg-event :bad/refused (fn [_ _] {:db {:n 2} :legacy/dispatch [:x]}))
    (rf/reg-event :good/after (fn [{:keys [db]} _] {:db (assoc db :after true)}))
    (rf/dispatch-sync [:bad/refused])
    (rf/dispatch-sync [:good/after])
    (is (true? (:after (rf.frame/frame-app-db-value :rf/default))))))

(deftest the-refused-event-settles-error-on-the-events-stream
  (testing "a refused dispatch settles :error on the always-on :events stream, never a clean :ok"
    (let [seen (atom [])]
      (rf.event-emit/register-event-listener! ::outcome (fn [r] (swap! seen conj r)))
      (rf/reg-event :bad/outcome (fn [_ _] {:db {:n 2} :legacy/dispatch [:x]}))
      (rf/dispatch-sync [:bad/outcome])
      (rf.event-emit/unregister-event-listener! ::outcome)
      (is (= [:error] (mapv :outcome @seen))))))

(deftest the-always-on-record-key-set-is-closed
  (testing "widening the record is an egress decision; read
            `router/emit-effect-map-shape!`'s §Egress first"
    (let [secret "sentinel-secret-value"
          rec    (first (refuse! {:acme/foreign {:token secret}} :bad/closed))]
      (is (= record-keys (set (keys rec))))
      (is (not (str/includes? (pr-str rec) secret))
          "the refused payload reaches the record by no route — not :value, not a :reason"))))

(deftest a-malformed-fx-entry-fans-the-same-always-on-category
  (testing "a malformed ENTRY inside a well-shaped :fx vector is reported on the
            always-on axis too, but recovers per entry instead of refusing"
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
    (rf/dispatch-sync [:seed])
    (let [ran  (atom [])
          _    (rf/reg-fx :entry/good (fn [_ args] (swap! ran conj args)))
          _    (rf/reg-event :bad/entry
                 (fn [{:keys [db]} _]
                   {:db (assoc db :n 2)
                    :fx [[:entry/good :first] :oops [:entry/good :second]]}))
          recs (shape-records
                 (record-always-on-errors #(rf/dispatch-sync [:bad/entry])))]
      (is (= [:fx] (map :offending-key recs)) "one record, under the slot the entry sits in")
      (is (= 2 (:n (rf.frame/frame-app-db-value :rf/default))) "the :db committed")
      (is (= [:first :second] @ran) "both siblings still ran"))))
