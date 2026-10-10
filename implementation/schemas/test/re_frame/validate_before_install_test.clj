(ns re-frame.validate-before-install-test
  "The router validates the complete candidate frame transition BEFORE it
  installs: a schema rejection never writes the frame-state container, emits
  no change trace, and reports the event's outcome as `:rolled-back`.
  Validation reads one schema-registry snapshot per candidate.
  Spec anchors: 010 §Per-step recovery row 4, 009 §Canonical per-event trace
  sequence."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.storage :as rf.schemas.storage]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(def ^:private watched-ops
  #{:rf.error/schema-validation-failure
    :rf.event/db-changed :rf.event/db-noop :rf.event/frame-state-changed})

(deftest rejected-candidate-never-writes-the-container
  ;; A commit-then-rollback pair would write the container twice and emit
  ;; change traces; a rejected candidate does neither.
  (rf/reg-app-schema [:n] [:int])
  (rf/reg-event :n/init  (fn [_ _] {:db {:n 0}}))
  (rf/reg-event :n/ok    (fn [{:keys [db]} _] {:db (assoc db :n 42)}))
  (rf/reg-event :n/break (fn [{:keys [db]} _] {:db (assoc db :n "boom")}))
  (rf/dispatch-sync [:n/init])
  (let [container (rf.frame/frame-state-container :rf/default)
        writes    (atom 0)
        outcomes  (atom [])]
    (add-watch container ::probe (fn [& _] (swap! writes inc)))
    (rf.event-emit/register-event-listener! ::probe #(swap! outcomes conj (:outcome %)))
    (try
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:n/break])
        (is (= [0 [:rolled-back] [[:rf.error/schema-validation-failure true]] {:n 0}]
               [@writes @outcomes
                (keep #(when (watched-ops (:operation %))
                         [(:operation %) (-> % :tags :rollback?)])
                      @traces)
                (rf/app-db-value :rf/default)])))
      (rf/dispatch-sync [:n/ok])
      (is (= 1 @writes) "the probe is live: a valid commit is one write")
      (finally
        (remove-watch container ::probe)
        (rf.event-emit/unregister-event-listener! ::probe)))))

(deftest a-commit-checks-only-the-registered-slices-it-changes-or-lacks
  ;; A slice the candidate leaves `identical?` to the installed value was
  ;; checked when it was written, so it is not checked again. A parent path
  ;; sees a write beneath it, and an absent slice is checked on every commit.
  (let [checked (atom [])
        malli   (:validate rf.schemas/default-schema-fns)
        commit! (fn [event]
                  (reset! checked [])
                  (with-trace-recorder! [traces]
                    (rf/dispatch-sync event)
                    [(frequencies @checked)
                     (into [] (keep #(when (= :rf.error/schema-validation-failure (:operation %))
                                       [(-> % :tags :registered-path) (-> % :tags :failing-id)]))
                           @traces)]))]
    (rf/reg-event :seed  (fn [_ _] {:db {:a {:b 1} :c 1}}))
    (rf/reg-event :a/b   (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:a :b] v)}))
    (rf/reg-event :n/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/dispatch-sync [:seed])
    (rf/reg-app-schema [:a] [:map [:b :int]])
    (rf/reg-app-schema [:c] :int)
    (rf/reg-app-schema [:d] [:maybe :int])
    (rf.schemas/set-schema-fns! {:validate (fn [schema value]
                                             (swap! checked conj schema)
                                             (malli schema value))})
    (is (= [{[:map [:b :int]] 1 [:maybe :int] 1} []] (commit! [:a/b 2])))
    (is (= [{[:map [:b :int]] 1 [:maybe :int] 1} [[[:a] :a/b]]] (commit! [:a/b "x"])))
    (is (= [{[:maybe :int] 1} []] (commit! [:n/inc])))
    (rf/reg-app-schema [:e] [:int {:min 0}])
    (is (= [{[:maybe :int] 1 [:int {:min 0}] 1} [[[:e] :n/inc]]] (commit! [:n/inc])))
    (is (= {:a {:b 2} :c 1 :n 1} (rf/app-db-value :rf/default)))))

(deftest registry-snapshot-is-generation-coherent-under-concurrent-flips
  ;; Two schemas flip together between an all-pass and an all-fail
  ;; generation on another thread, so a dispatch fails 0 or 2 entries,
  ;; never 1.
  (rf/reg-event :race/seed  (fn [_ _] {:db {:a 0 :b 0}}))
  (rf/reg-event :race/write (fn [_ [_ i]] {:db {:a i :b i}}))
  (rf/dispatch-sync [:race/seed])
  (rf/reg-app-schema [:a] [:int])
  (rf/reg-app-schema [:b] [:int])
  (let [gen-pass (rf.schemas.storage/snapshot-schemas-by-frame)]
    (rf.schemas.storage/clear-schemas-by-frame!)
    (rf/reg-app-schema [:a] [:string])
    (rf/reg-app-schema [:b] [:string])
    (let [gen-fail (rf.schemas.storage/snapshot-schemas-by-frame)
          stop?    (atom false)
          flipper  (future
                     (loop [pass? true]
                       (when-not @stop?
                         (rf.schemas.storage/restore-schemas-by-frame!
                           (if pass? gen-pass gen-fail))
                         (recur (not pass?)))))]
      (try
        (let [torn (into []
                         (keep (fn [i]
                                 (with-trace-recorder! [traces]
                                   (rf/dispatch-sync [:race/write (inc i)])
                                   (let [failures (count (filter #(= :rf.error/schema-validation-failure
                                                                     (:operation %))
                                                                 @traces))]
                                     (when-not (contains? #{0 2} failures)
                                       {:dispatch i :failures failures})))))
                         (range 100))]
          (is (empty? torn) (str "these dispatches tore: " (pr-str torn))))
        (finally
          (reset! stop? true)
          @flipper
          (rf.schemas.storage/restore-schemas-by-frame! gen-pass))))))
