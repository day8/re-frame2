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
            [re-frame.story.loaders    :as loaders]
            [re-frame.subs             :as subs]))

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

;; ===========================================================================
;; Internal assertion-helper branches (rf2-uhq5j)
;;
;; The seven evaluators are exercised end-to-end via run-variant above +
;; the JVM ns. The branches below were untested at any layer — reached
;; directly via var-quote (the established Story-test seam pattern).
;; ===========================================================================

;; ---- event-matches? — the fn? + bare-keyword? branches ------------------
;;
;; story_assertions_test.clj only passes literal event vectors, so the
;; predicate-needle (`fn?`) and bare-keyword (`keyword?`) branches of
;; `:rf.assert/dispatched?` (spec/007's `[event-or-pred]`) had no test.

(def ^:private event-matches? @#'assertions/event-matches?)

(deftest cljs-event-matches?-fn-predicate-branch
  (testing "a fn needle is applied to the observed event vector"
    (is (true?  (event-matches? [:user/click 7] (fn [ev] (= 7 (second ev)))))
        "predicate sees the whole observed vector and returns truthy → matched")
    (is (false? (event-matches? [:user/click 7] (fn [ev] (= 99 (second ev)))))
        "predicate returns falsey → not matched")
    (is (false? (event-matches? [:user/click] (constantly nil)))
        "a nil-returning predicate is coerced to false")))

(deftest cljs-event-matches?-bare-keyword-branch
  (testing "a bare keyword needle matches on the observed event's head id"
    (is (true?  (event-matches? [:user/click {:x 1}] :user/click))
        "head id equals the keyword → matched regardless of payload")
    (is (false? (event-matches? [:user/submit] :user/click))
        "different head id → not matched")))

(deftest cljs-event-matches?-vector-and-fallthrough
  (testing "the literal-vector branch still matches exactly; non-fn/
            vector/keyword needles fall through to false"
    (is (true?  (event-matches? [:user/click 1] [:user/click 1])))
    (is (false? (event-matches? [:user/click 1] [:user/click 2])))
    (is (false? (event-matches? [:user/click] "not-a-needle"))
        "a string needle hits the :else branch → false")
    (is (false? (event-matches? [:user/click] 42))
        "a number needle hits the :else branch → false")))

;; ---- evaluate-sub-equals — the sub-throws (::compute-error) arm ----------
;;
;; Only the clean pass/fail of a well-behaved sub was tested. The
;; evaluator wraps `subs/compute-sub` in its OWN try/catch and, when
;; that throws, records :passed? false with :actual :rf.assert/sub-threw
;; rather than propagating. Note `compute-sub` normally swallows a
;; throwing sub-body internally (it recovers to nil), so the evaluator's
;; catch is reachable only when compute-sub ITSELF throws — we force that
;; here with `with-redefs` so the ::compute-error arm is exercised
;; directly (the realistic case being a failure inside compute-sub's
;; input-resolution / registrar-lookup before its own try).

(def ^:private evaluate-sub-equals @#'assertions/evaluate-sub-equals)

(deftest cljs-evaluate-sub-equals-sub-throws
  (testing "when compute-sub throws, the evaluator records a fail with
            :actual :rf.assert/sub-threw — never propagates"
    (with-redefs [subs/compute-sub (fn [_query-v _db]
                                      (throw (ex-info "kaboom" {})))]
      (let [out (evaluate-sub-equals :rf/default {} [[:boom] :anything])]
        (is (false? (:passed? out)))
        (is (= :rf.assert/sub-threw (:actual out))
            ":actual is the sentinel, not the raw exception")
        (is (= :anything (:expected out)))
        (is (re-find #"threw" (:reason out))
            ":reason explains the sub threw")))))

;; ---- evaluate-effect-emitted — the pred-rejects-but-present arm ----------
;;
;; Only present/absent was tested. When the fx-id WAS emitted but the
;; optional predicate rejects it, the evaluator must record :passed?
;; false with the pred-reject reason (and a thrown predicate is treated
;; as a rejection, never propagated).

(def ^:private evaluate-effect-emitted @#'assertions/evaluate-effect-emitted)

(deftest cljs-evaluate-effect-emitted-pred-rejects-present-fx
  (testing "fx present but optional predicate returns false → fail with
            the pred-reject reason"
    (assertions/record-emitted-fx! :rf/default :http)
    (let [out (evaluate-effect-emitted :rf/default [:http (constantly false)])]
      (is (false? (:passed? out)))
      (is (contains? (:actual out) :http)
          ":actual still reports the emitted-fx set (the fx WAS present)")
      (is (re-find #"predicate rejected" (:reason out))))))

(deftest cljs-evaluate-effect-emitted-pred-accepts-present-fx
  (testing "fx present and predicate accepts → pass (the positive arm of
            the same branch, for contrast)"
    (assertions/record-emitted-fx! :rf/default :http)
    (let [out (evaluate-effect-emitted :rf/default [:http (constantly true)])]
      (is (true? (:passed? out)))
      (is (re-find #"emitted during play" (:reason out))))))

(deftest cljs-evaluate-effect-emitted-pred-throws-is-rejection
  (testing "a predicate that throws is swallowed and treated as a
            rejection — never propagates"
    (assertions/record-emitted-fx! :rf/default :http)
    (let [out (evaluate-effect-emitted :rf/default
                                       [:http (fn [_] (throw (ex-info "x" {})))])]
      (is (false? (:passed? out))
          "thrown predicate → not passed, no exception escapes"))))
