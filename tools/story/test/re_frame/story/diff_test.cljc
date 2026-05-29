(ns re-frame.story.diff-test
  "Tests for the semantic diff over canonical run artifacts —
  `diff-run-artifacts` + the pure `diff-runs` core (rf2-5x1wt.9,
  spec/017-Testing-Story.md §Semantic diff).

  Two layers, both under `clojure -M:test` (JVM) + the node-runtime CLJS
  build:

  - PURE: the facet diffs (`diff-app-db`, `diff-effects`,
    `diff-schema-violations`, `diff-trace-ops`, `diff-sub-runs`) and the
    assembler (`diff-runs`) over HAND-BUILT run-results — the §A5 acceptance
    bullets:
      • a small readable diff for an app-db change;
      • an effect-only diff;
      • a schema-error diff;
    plus the load-bearing correctness property — volatile per-run noise
    (frame ids, timestamps, epoch / dispatch / trace ids) is stripped FIRST,
    so noise never reads as a difference while a real change always does.
  - HEADLESS (against a live frame): `diff-run-artifacts` replays two
    artifacts into fresh frames and diffs the results — two equal programs
    diff `{:same? true}` (no volatile drift), a divergent program surfaces a
    readable app-db facet."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core      :as rf]
            [re-frame.epoch     :as epoch]
            [re-frame.frame     :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story.artifact :as artifact]
            [re-frame.story.diff     :as diff]))

;; ===========================================================================
;; PURE: app-db delta  (§A5 — a small readable diff for an app-db change)
;; ===========================================================================

(deftest diff-app-db-readable-delta
  (testing "an unchanged app-db has no delta"
    (is (nil? (diff/diff-app-db {:n 1 :user {:name "ada"}}
                                {:n 1 :user {:name "ada"}}))))

  (testing "a single changed leaf yields a one-entry :changed at its path"
    (let [d (diff/diff-app-db {:n 1 :user {:name "ada"}}
                              {:n 2 :user {:name "ada"}})]
      (is (= [{:path [:n] :baseline 1 :current 2}] (:changed d)))
      (is (nil? (:added d)))
      (is (nil? (:removed d)))
      (is (= #{:changed} (set (keys d)))
          "ONLY the differing slot appears — the unchanged :user path is silent")))

  (testing "an added and a removed leaf are localised to their paths"
    (let [d (diff/diff-app-db {:a 1 :gone 9}
                              {:a 1 :new 7})]
      (is (= [{:path [:new] :current 7}] (:added d)))
      (is (= [{:path [:gone] :baseline 9}] (:removed d)))
      (is (nil? (:changed d)))))

  (testing "nested leaf changes report the full key path"
    (let [d (diff/diff-app-db {:user {:profile {:age 30}}}
                              {:user {:profile {:age 31}}})]
      (is (= [{:path [:user :profile :age] :baseline 30 :current 31}]
             (:changed d))))))

;; ===========================================================================
;; PURE: effect-only diff  (§A5 — an effect-only diff)
;; ===========================================================================

(deftest diff-effects-multiset-delta
  (testing "identical effect rows produce no delta"
    (is (nil? (diff/diff-effects {:effects [{:fx :http/get :args {:url "/a"}}]}
                                 {:effects [{:fx :http/get :args {:url "/a"}}]}))))

  (testing "an effect emitted only by current is :only-current"
    (let [d (diff/diff-effects
              {:effects [{:fx :http/get :args {:url "/a"}}]}
              {:effects [{:fx :http/get :args {:url "/a"}}
                         {:fx :analytics/track :args {:event :viewed}}]})]
      (is (= [{:fx :analytics/track :args {:event :viewed}}] (:only-current d)))
      (is (nil? (:only-baseline d)))))

  (testing "the SAME effect twice vs once is a multiset difference (the surplus)"
    (let [d (diff/diff-effects
              {:effects [{:fx :db/save} {:fx :db/save}]}
              {:effects [{:fx :db/save}]})]
      (is (= [{:fx :db/save}] (:only-baseline d))
          "baseline emitted it twice, current once — one surplus row")
      (is (nil? (:only-current d))))))

;; ===========================================================================
;; PURE: schema-error diff  (§A5 — a schema-error diff)
;; ===========================================================================

(deftest diff-schema-violations-by-selector
  (testing "the same violation surface on both sides is no diff"
    (let [v {:where :event :failing-id :checkout/submit
             :selector [:event :checkout/submit]}]
      (is (nil? (diff/diff-schema-violations {:schema-violations [v]}
                                             {:schema-violations [v]})))))

  (testing "a violation surface only current failed on is :only-current"
    (let [base    {:schema-violations []}
          current {:schema-violations
                   [{:where :event :failing-id :checkout/submit
                     :selector [:event :checkout/submit]}]}
          d       (diff/diff-schema-violations base current)]
      (is (= [[:event :checkout/submit]] (:only-current d))
          "current introduced a schema failure at [:event :checkout/submit]")
      (is (nil? (:only-baseline d)))))

  (testing "the selector is recomputed when a record omits it (no :selector slot)"
    (let [current {:schema-violations
                   [{:where :app-db :registered-path [:user] :path [:user :age]}]}
          d       (diff/diff-schema-violations {:schema-violations []} current)]
      (is (= [[:app-db [:user] [:user :age]]] (:only-current d))))))

;; ===========================================================================
;; PURE: trace-op spine + sub-runs + status
;; ===========================================================================

(defn- tape-with-ops
  "A minimal epoch tape whose single epoch carries trace events with the
  given `:operation`s, in order."
  [ops]
  [{:epoch-id 1
    :trace-events (mapv (fn [op] {:operation op :op-type :event}) ops)}])

(deftest diff-trace-ops-causal-spine
  (testing "identical op sequences are no diff"
    (is (nil? (diff/diff-trace-ops
                {:epoch-tape (tape-with-ops [:rf.event/run-start :rf.event/run-end])}
                {:epoch-tape (tape-with-ops [:rf.event/run-start :rf.event/run-end])}))))

  (testing "a diverging op sequence reports both spines + the first divergence"
    (let [d (diff/diff-trace-ops
              {:epoch-tape (tape-with-ops [:a :b :c])}
              {:epoch-tape (tape-with-ops [:a :x :c])})]
      (is (= [:a :b :c] (:baseline d)))
      (is (= [:a :x :c] (:current d)))
      (is (= 1 (:first-divergence d)) "index 1 (:b vs :x) is the first divergence")))

  (testing "a dropped trailing op is a diff with no in-range first-divergence"
    (let [d (diff/diff-trace-ops
              {:epoch-tape (tape-with-ops [:a :b :c])}
              {:epoch-tape (tape-with-ops [:a :b])})]
      (is (= [:a :b :c] (:baseline d)))
      (is (= [:a :b] (:current d)))
      (is (nil? (:first-divergence d))
          "current is a proper prefix — only the length differs"))))

(deftest diff-sub-runs-multiset-delta
  (testing "a sub-run only one side produced surfaces as a view fact diff"
    (let [d (diff/diff-sub-runs
              {:sub-runs [{:query [:visible-todos] :value 3}]}
              {:sub-runs [{:query [:visible-todos] :value 5}]})]
      (is (= [{:query [:visible-todos] :value 3}] (:only-baseline d)))
      (is (= [{:query [:visible-todos] :value 5}] (:only-current d))))))

(deftest diff-status-headline
  (testing "a pass → fail flip is reported"
    (is (= {:baseline :pass :current :fail}
           (diff/diff-status {:status :pass} {:status :fail}))))
  (testing "a shared status is no diff"
    (is (nil? (diff/diff-status {:status :pass} {:status :pass})))))

;; ===========================================================================
;; PURE: the assembler — :same? and the multi-facet readable diff
;; ===========================================================================

(deftest diff-runs-same-when-behaviourally-identical
  (testing "two runs equal under canonicalize diff to {:same? true}"
    (let [r {:status :pass :app-db {:n 1} :effects [] :sub-runs []
             :epoch-tape (tape-with-ops [:rf.event/run-start])}]
      (is (= {:same? true} (diff/diff-runs r r))))))

(deftest diff-runs-collects-only-differing-facets
  (testing "an app-db-only change surfaces ONLY the :app-db facet"
    (let [base {:status :pass :app-db {:n 1} :effects [] :sub-runs []
                :epoch-tape (tape-with-ops [:rf.event/run-start])}
          cur  (assoc base :app-db {:n 2})
          d    (diff/diff-runs base cur)]
      (is (false? (:same? d)))
      (is (= #{:app-db} (:facets d)))
      (is (= [{:path [:n] :baseline 1 :current 2}] (get-in d [:app-db :changed])))
      (is (not (contains? d :effects)))))

  (testing "a status + effect change surfaces BOTH facets, named in :facets"
    (let [base {:status :pass :app-db {} :effects [] :sub-runs []
                :epoch-tape (tape-with-ops [:e])}
          cur  {:status :fail :app-db {} :sub-runs []
                :effects [{:fx :http/get :outcome :error}]
                :epoch-tape (tape-with-ops [:e])}
          d    (diff/diff-runs base cur)]
      (is (= #{:status :effects} (:facets d)))
      (is (= {:baseline :pass :current :fail} (:status d)))
      (is (= [{:fx :http/get :outcome :error}] (get-in d [:effects :only-current]))))))

;; ===========================================================================
;; PURE: the load-bearing property — volatile noise is stripped FIRST
;; ===========================================================================

(defn- noisy-run
  "A run-result whose epoch tape + app-db carry per-run stamps a fresh-frame
  replay would write differently every time (frame id, epoch id, committed-at,
  trace :id / :time), wrapping the same semantic `db-after`."
  [{:keys [frame epoch-id committed-at trace-id db-after]}]
  {:status :pass
   :app-db db-after
   :frame  frame
   :epoch-tape
   [{:epoch-id epoch-id :frame frame :committed-at committed-at
     :outcome :ok :db-before {} :db-after db-after
     :effects [] :sub-runs [] :renders []
     :trace-events [{:operation :rf.event/run-start :op-type :event
                     :id trace-id :time committed-at
                     :tags {:rf.trace/event-id :app/go}}]}]})

(deftest diff-runs-strips-volatile-noise
  (testing "two runs differing ONLY in per-run stamps diff to {:same? true}"
    (let [a (noisy-run {:frame :rf.test.replay/frame-aaa :epoch-id 5
                        :committed-at 1000 :trace-id 17 :db-after {:n 1}})
          b (noisy-run {:frame :rf.test.replay/frame-zzz :epoch-id 99
                        :committed-at 2000 :trace-id 88 :db-after {:n 1}})]
      (is (= {:same? true} (diff/diff-runs a b))
          "frame ids, epoch ids, timestamps, trace ids are NOT differences")))

  (testing "a real app-db change IS detected even amid the per-run noise"
    (let [a (noisy-run {:frame :rf.test.replay/frame-aaa :epoch-id 5
                        :committed-at 1000 :trace-id 17 :db-after {:n 1}})
          c (noisy-run {:frame :rf.test.replay/frame-zzz :epoch-id 99
                        :committed-at 2000 :trace-id 88 :db-after {:n 2}})
          d (diff/diff-runs a c)]
      (is (false? (:same? d)))
      (is (contains? (:facets d) :app-db))
      (is (= [{:path [:n] :baseline 1 :current 2}] (get-in d [:app-db :changed]))
          "the strip surfaces the SEMANTIC change, not the volatile drift"))))

(deftest diff-runs-strips-story-accumulator-keys
  (testing ":rf.story/* accumulator keys in app-db are stripped before diffing"
    (let [a {:status :pass :app-db {:n 1 :rf.story/probe :a}}
          b {:status :pass :app-db {:n 1 :rf.story/probe :b}}]
      (is (= {:same? true} (diff/diff-runs a b))
          "the accumulator key differs but is not semantic — stripped first"))))

;; ===========================================================================
;; HEADLESS: diff-run-artifacts over real replays  (live frame)
;; ===========================================================================

(defn- reset-rf! [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (epoch/clear-history!)
  (epoch/clear-epoch-listeners!)
  (try (rf/init! plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-rf!)

(deftest diff-run-artifacts-equal-programs-are-same
  (testing "replaying the SAME program twice into fresh frames diffs {:same? true}
            — fresh-frame volatile drift causes no false difference"
    (rf/reg-event-db :diff/inc (fn [db _] (update db :n (fnil inc 0))))
    (let [a (artifact/make-run-artifact
              {:event-program [[:dispatch [:diff/inc]] [:dispatch [:diff/inc]]]})]
      (is (= {:same? true} (diff/diff-run-artifacts a a))
          "two fresh-frame replays of one program are behaviourally identical"))))

(deftest diff-run-artifacts-divergent-program-surfaces-app-db-facet
  (testing "two programs producing different final app-db surface a readable
            :app-db facet"
    (rf/reg-event-db :diff/set (fn [db [_ v]] (assoc db :v v)))
    (let [a (artifact/make-run-artifact {:event-program [[:dispatch [:diff/set 1]]]})
          b (artifact/make-run-artifact {:event-program [[:dispatch [:diff/set 2]]]})
          d (diff/diff-run-artifacts a b)]
      (is (false? (:same? d)))
      (is (contains? (:facets d) :app-db))
      (is (= [{:path [:v] :baseline 1 :current 2}]
             (get-in d [:app-db :changed]))))))

(deftest diff-run-artifacts-accepts-a-run-result-directly
  (testing "a run-result side is used as-is (the pure path) — diffing an
            artifact replay against a hand-built result still works"
    (rf/reg-event-db :diff/set (fn [db [_ v]] (assoc db :v v)))
    (let [art    (artifact/make-run-artifact {:event-program [[:dispatch [:diff/set 9]]]})
          result {:status :pass :app-db {:v 9} :effects [] :sub-runs []
                  :epoch-tape []}
          d      (diff/diff-run-artifacts art result)]
      ;; The artifact replay's app-db {:v 9} matches the hand-built result's
      ;; {:v 9}; the trace-op spine differs (the replay has trace events, the
      ;; hand-built result none), so the diff is NOT :same? but the app-db
      ;; facet is absent.
      (is (or (:same? d)
              (not (contains? (:facets d) :app-db)))
          "app-db agrees across the artifact-vs-result diff"))))
