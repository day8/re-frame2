(ns re-frame.story-assertions-cljs-test
  "CLJS smoke tests for re-frame2-story Stage 5 (rf2-h8et) —
  `:rf.assert/*` vocabulary + play sequence + assertions-passing?.

  The bulk of assertion coverage lives in the JVM test ns
  (`re-frame.story-assertions-test`); this namespace covers the
  CLJS-specific surface: that the seven assertion handlers register
  under CLJS, that `run-variant` resolves to a result map with the
  `:assertions` slot populated, and that `assertions-passing?` works
  from CLJS callers."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as frame]
            [re-frame.machines         :as machines]
            [re-frame.registrar        :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story            :as story]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.async      :as async-lib]
            [re-frame.story.loaders    :as loaders]))

(defn reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch :default _ nil))
  (rf/reg-sub :rf/machine
              (fn [db [_ machine-id]]
                (get-in db [:rf/machines machine-id])))
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (reset! assertions/trace-accumulators {})
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- the seven canonical assertions are registered on CLJS too -----------

(deftest cljs-canonical-seven-registered
  (testing "all seven :rf.assert/* event handlers register on CLJS"
    (let [events (registrar/registrations :event)]
      (is (contains? events :rf.assert/path-equals))
      (is (contains? events :rf.assert/path-matches))
      (is (contains? events :rf.assert/sub-equals))
      (is (contains? events :rf.assert/dispatched?))
      (is (contains? events :rf.assert/state-is))
      (is (contains? events :rf.assert/no-warnings))
      (is (contains? events :rf.assert/effect-emitted)))))

;; ---- :rf.assert/path-equals --------------------------------------------

(deftest cljs-path-equals-pass
  (testing ":rf.assert/path-equals against an event-mutated app-db"
    (rf/reg-event-db :test/set
      (fn [db _] (assoc-in db [:user :name] "alice")))
    (story/reg-variant :story.cljs.assert/pe
      {:events [[:test/set]]
       :play-script [[:dispatch-sync [:rf.assert/path-equals [:user :name] "alice"]]]})
    (async done
      (-> (story/run-variant :story.cljs.assert/pe)
          (async-lib/then
            (fn [r]
              (is (= 1 (count (:assertions r))))
              (is (true? (-> r :assertions first :passed?)))
              (story/destroy-variant! :story.cljs.assert/pe)
              (done)))))))

;; ---- assertions-passing? predicate --------------------------------------

(deftest cljs-assertions-passing?-roundtrip
  (testing "assertions-passing? returns true on all-pass, false on any-fail"
    (rf/reg-event-db :test/n2 (fn [db _] (assoc db :n 42)))
    (story/reg-variant :story.cljs.passing/ok
      {:events [[:test/n2]]
       :play-script [[:dispatch-sync [:rf.assert/path-equals [:n] 42]]]})
    (story/reg-variant :story.cljs.passing/bad
      {:events [[:test/n2]]
       :play-script [[:dispatch-sync [:rf.assert/path-equals [:n] 999]]]})
    (async done
      (-> (story/run-variant :story.cljs.passing/ok)
          (async-lib/then
            (fn [ok-r]
              (is (true? (story/assertions-passing? ok-r)))
              (-> (story/run-variant :story.cljs.passing/bad)
                  (async-lib/then
                    (fn [bad-r]
                      (is (false? (story/assertions-passing? bad-r)))
                      (story/destroy-variant! :story.cljs.passing/ok)
                      (story/destroy-variant! :story.cljs.passing/bad)
                      (done))))))))))

;; ---- record-don't-throw contract on CLJS --------------------------------

(deftest cljs-record-not-throw
  (testing "failing assertions never throw on CLJS; sequence runs to completion"
    (story/reg-variant :story.cljs.contract/v
      {:events []
       :play-script [[:dispatch-sync [:rf.assert/path-equals [:nope] :unexpected]]
                [:dispatch-sync [:rf.assert/path-equals [:also-nope] :other]]]})
    (async done
      (-> (story/run-variant :story.cljs.contract/v)
          (async-lib/then
            (fn [r]
              (is (= 2 (count (:assertions r))))
              (is (every? #(false? (:passed? %)) (:assertions r)))
              (is (false? (story/assertions-passing? r)))
              (story/destroy-variant! :story.cljs.contract/v)
              (done)))))))

;; ---- public API additions surface check ---------------------------------

(deftest cljs-public-api-surface
  (testing "Stage 5 public fns are present on the CLJS story ns"
    (is (fn? story/assertions-passing?))
    (is (fn? story/read-assertions))
    (is (fn? story/canonical-assertion-ids))
    (is (fn? story/execute-play!))
    (is (= :rf.story/force-fx-stub story/force-fx-stub-id))))
