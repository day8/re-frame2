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

(deftest a-commit-checks-only-the-present-registered-slices-it-changes
  ;; A slice the candidate leaves `identical?` to the installed value was
  ;; checked when it was written, so it is not checked again, and a path the
  ;; candidate lacks is not checked at all. A parent path sees a write
  ;; beneath it, and a present `nil` is a value like any other.
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
    (rf/reg-event :e/set (fn [{:keys [db]} [_ v]] {:db (assoc db :e v)}))
    (rf/dispatch-sync [:seed])
    (rf/reg-app-schema [:a] [:map [:b :int]])
    (rf/reg-app-schema [:c] :int)
    (rf/reg-app-schema [:d] :int)
    (rf.schemas/set-schema-fns! {:validate (fn [schema value]
                                             (swap! checked conj schema)
                                             (malli schema value))})
    (is (= [{[:map [:b :int]] 1} []] (commit! [:a/b 2])))
    (is (= [{[:map [:b :int]] 1} [[[:a] :a/b]]] (commit! [:a/b "x"])))
    (is (= [{} []] (commit! [:n/inc])))
    (rf/reg-app-schema [:e] [:int {:min 0}])
    (is (= [{} []] (commit! [:n/inc])) "the absent [:d] and [:e] are not checked")
    (is (= [{[:int {:min 0}] 1} [[[:e] :e/set]]] (commit! [:e/set nil]))
        "a present nil is checked")
    (is (= {:a {:b 2} :c 1 :n 2} (rf/app-db-value :rf/default)))))

(deftest every-arity-skips-an-absent-path-and-checks-a-present-nil
  ;; The `{:k nil}` half is the control: it fails only if the frame's entries
  ;; were walked, so the `{:other 1}` half's pass is the skip, not an empty walk.
  (rf/reg-app-schema [:k] :int)
  (rf/with-frame :rf/default
    (doseq [[arity call] [[1 #(rf.schemas/validate-app-schema! %)]
                          [2 #(rf.schemas/validate-app-schema! % :e)]
                          [3 #(rf.schemas/validate-app-schema! % :e :rf/default)]
                          [4 #(rf.schemas/validate-app-schema! % :e :rf/default (constantly true))]
                          [5 #(rf.schemas/validate-app-schema! % :e :rf/default (constantly true)
                                                               {:k 1})]]]
      (is (= [true false] [(call {:other 1}) (call {:k nil})])
          (str arity "-arity")))))

(deftest bare-slices-seeded-by-separate-events-both-install
  ;; Each seed's candidate lacks the other slice, which is not checked, so
  ;; neither seed vetoes the other.
  (rf/reg-app-schema [:a] :int)
  (rf/reg-app-schema [:b] :int)
  (rf/reg-event :seed/a (fn [{:keys [db]} _] {:db (assoc db :a 1)}))
  (rf/reg-event :seed/b (fn [{:keys [db]} _] {:db (assoc db :b 2)}))
  (rf/dispatch-sync [:seed/a])
  (rf/dispatch-sync [:seed/b])
  (is (= {:a 1 :b 2} (rf/app-db-value :rf/default))))

(deftest a-dissoc-installs-unless-an-enclosing-schema-requires-the-key
  (rf/reg-event :seed (fn [_ _] {:db {:k 1 :j 1}}))
  (rf/reg-event :drop (fn [{:keys [db]} [_ k]] {:db (dissoc db k)}))
  (rf/dispatch-sync [:seed])
  (rf/reg-app-schema [:k] :int)
  (rf/reg-app-schema [:j] :int)
  (rf/dispatch-sync [:drop :j])
  (is (= {:k 1} (rf/app-db-value :rf/default))
      "a path schema alone does not require its key")
  (rf/reg-app-schema [] [:map [:k :int]])
  (with-trace-recorder! [traces]
    (rf/dispatch-sync [:drop :k])
    (is (= [[[] :drop]]
           (into [] (keep #(when (= :rf.error/schema-validation-failure (:operation %))
                             [(-> % :tags :registered-path) (-> % :tags :failing-id)]))
                 @traces))
        "a root schema with a required entry rejects the commit that removes it"))
  (is (= {:k 1} (rf/app-db-value :rf/default))))

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
