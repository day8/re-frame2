(ns re-frame.story.golden-test
  "Tests for golden slices — curated canonicalized run regression artifacts
  (rf2-5x1wt.32, spec/017-Testing-Story.md §Golden slices).

  Two layers, both under `clojure -M:test` (JVM) + the node-runtime CLJS
  build:

  - PURE: capture / match / readable-report over HAND-BUILT run-results —
    the §Golden-slice acceptance bullets:
      • a golden slice round-trips (canonicalizing the same run twice is
        equal);
      • `golden-match?` is true for a behaviourally-equivalent run and
        false for a real semantic difference;
      • volatile fields (frame / epoch / trace ids, timestamps) do NOT
        cause a false mismatch — because the slice is compared via
        `canonicalize`, reusing `.8`'s strip rules;
      • a mismatch report DELEGATES to `re-frame.story.diff/diff-runs` (the
        readable facet diff), not a reinvented diff.
  - HEADLESS (against a live frame): `capture-golden` / `golden-match?` over
    a real replay — a golden captured from an artifact matches a re-replay
    of the same program, and a divergent program does not."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core      :as rf]
            [re-frame.epoch     :as epoch]
            [re-frame.frame     :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story.artifact    :as artifact]
            [re-frame.story.fingerprint :as fingerprint]
            [re-frame.story.golden      :as golden]))

;; ===========================================================================
;; FIXTURES — hand-built run-results
;; ===========================================================================

(defn- tape-with-ops
  "A minimal epoch tape whose single epoch carries trace events with the
  given `:operation`s, in order."
  [ops]
  [{:epoch-id 1
    :trace-events (mapv (fn [op] {:operation op :op-type :event}) ops)}])

(defn- run-result
  "A minimal hand-built run-result over the behavioural slice
  (`fingerprint/run-hash-input-keys`)."
  [{:keys [status app-db effects ops]
    :or   {status :pass app-db {} effects [] ops [:rf.event/run-start]}}]
  {:status status
   :app-db app-db
   :effects effects
   :sub-runs []
   :assertions []
   :checks []
   :schema-violations []
   :warnings []
   :epoch-tape (tape-with-ops ops)})

(defn- noisy-run
  "A run-result whose epoch tape + app-db carry per-run stamps a fresh-frame
  replay would write differently every time (frame id, epoch id,
  committed-at, trace :id / :time), wrapping the same semantic `db-after`."
  [{:keys [frame epoch-id committed-at trace-id db-after status]
    :or   {status :pass}}]
  {:status status
   :app-db db-after
   :frame  frame
   :effects [] :sub-runs [] :assertions [] :checks []
   :schema-violations [] :warnings []
   :epoch-tape
   [{:epoch-id epoch-id :frame frame :committed-at committed-at
     :outcome :ok :db-before {} :db-after db-after
     :effects [] :sub-runs [] :renders []
     :trace-events [{:operation :rf.event/run-start :op-type :event
                     :id trace-id :time committed-at
                     :tags {:rf.trace/event-id :app/go}}]}]})

;; ===========================================================================
;; PURE: capture + round-trip  (a golden round-trips; capture is idempotent)
;; ===========================================================================

(deftest make-golden-shape
  (testing "a golden carries the kind tag, a frozen :canonical, the run-hash,
            and the slice keys it was taken over"
    (let [g (golden/make-golden (run-result {:app-db {:n 1}}))]
      (is (golden/golden? g))
      (is (= :rf.test/golden (:golden/kind g)))
      (is (contains? g :canonical))
      (is (= (fingerprint/run-hash (run-result {:app-db {:n 1}})) (:run-hash g)))
      (is (= fingerprint/run-hash-input-keys (:slice-keys g)))
      (is (not (contains? g :golden/meta)) "no meta unless supplied")
      (is (not (contains? g :run-result)) "no run-result unless :keep-run-result"))))

(deftest make-golden-meta-and-keep-run-result
  (testing ":meta rides under :golden/meta and is NOT part of :canonical"
    (let [run (run-result {:app-db {:n 1}})
          g1  (golden/make-golden run)
          g2  (golden/make-golden run {:meta {:variant/id :story.checkout/ok
                                              :doc "checkout happy path"}})]
      (is (= {:variant/id :story.checkout/ok :doc "checkout happy path"}
             (:golden/meta g2)))
      (is (= (:canonical g1) (:canonical g2))
          "curation provenance never perturbs the regression baseline")))

  (testing ":keep-run-result retains the behavioural slice for the readable diff"
    (let [run (run-result {:app-db {:n 1}})
          g   (golden/make-golden run {:keep-run-result true})]
      (is (= (golden/behavioural-slice run) (:run-result g))))))

(deftest golden-round-trips
  (testing "canonicalizing the same run twice yields the SAME golden :canonical
            — the round-trip property the golden contract rests on"
    (let [run (run-result {:app-db {:user {:name "ada"} :n 3}
                           :effects [{:fx :http/get :args {:url "/a"}}]})
          g1  (golden/make-golden run)
          g2  (golden/make-golden run)]
      (is (= (:canonical g1) (:canonical g2)))
      (is (= (:run-hash g1) (:run-hash g2)))
      (is (golden/golden-match? g1 run)
          "a golden matches the very run it was captured from"))))

;; ===========================================================================
;; PURE: golden-match?  (equivalent ⇒ true, real difference ⇒ false)
;; ===========================================================================

(deftest golden-match-true-for-equivalent-run
  (testing "a behaviourally-equivalent run (same slice) matches"
    (let [g (golden/make-golden (run-result {:app-db {:n 1} :ops [:a :b]}))]
      (is (true? (golden/golden-match? g (run-result {:app-db {:n 1} :ops [:a :b]}))))))

  (testing "map key ORDER in app-db does not affect the match (canonical)"
    (let [g (golden/make-golden (run-result {:app-db {:a 1 :b 2}}))]
      (is (true? (golden/golden-match? g (run-result {:app-db (sorted-map :b 2 :a 1)})))))))

(deftest golden-match-false-for-semantic-difference
  (testing "an app-db change fails the match"
    (let [g (golden/make-golden (run-result {:app-db {:n 1}}))]
      (is (false? (golden/golden-match? g (run-result {:app-db {:n 2}}))))))

  (testing "an effect-only change fails the match"
    (let [g (golden/make-golden (run-result {:effects [{:fx :db/save}]}))]
      (is (false? (golden/golden-match? g (run-result {:effects []}))))))

  (testing "a status flip fails the match"
    (let [g (golden/make-golden (run-result {:status :pass}))]
      (is (false? (golden/golden-match? g (run-result {:status :fail}))))))

  (testing "a diverging trace op spine fails the match"
    (let [g (golden/make-golden (run-result {:ops [:a :b :c]}))]
      (is (false? (golden/golden-match? g (run-result {:ops [:a :x :c]})))))))

;; ===========================================================================
;; PURE: the load-bearing property — volatile fields do NOT cause a false miss
;; ===========================================================================

(deftest golden-match-ignores-volatile-fields
  (testing "two runs differing ONLY in per-run stamps (frame / epoch / trace
            ids, timestamps) still MATCH — the golden compares via canonicalize,
            reusing .8's strip rules"
    (let [captured (noisy-run {:frame :rf.test.replay/frame-aaa :epoch-id 5
                               :committed-at 1000 :trace-id 17 :db-after {:n 1}})
          g        (golden/make-golden captured)
          rerun    (noisy-run {:frame :rf.test.replay/frame-zzz :epoch-id 99
                               :committed-at 2000 :trace-id 88 :db-after {:n 1}})]
      (is (true? (golden/golden-match? g rerun))
          "frame ids, epoch ids, timestamps, trace ids are NOT mismatches")))

  (testing "a real app-db change is STILL caught amid the per-run noise"
    (let [captured (noisy-run {:frame :rf.test.replay/frame-aaa :epoch-id 5
                               :committed-at 1000 :trace-id 17 :db-after {:n 1}})
          g        (golden/make-golden captured)
          changed  (noisy-run {:frame :rf.test.replay/frame-zzz :epoch-id 99
                               :committed-at 2000 :trace-id 88 :db-after {:n 2}})]
      (is (false? (golden/golden-match? g changed))
          "the canonical compare surfaces the SEMANTIC change, not volatile drift"))))

;; ===========================================================================
;; PURE: compare-golden — the readable report delegates to diff/diff-runs
;; ===========================================================================

(deftest compare-golden-match-report
  (testing "a match reports {:match? true} with the run-hash"
    (let [run (run-result {:app-db {:n 1}})
          g   (golden/make-golden run)
          r   (golden/compare-golden g run)]
      (is (true? (:match? r)))
      (is (= (fingerprint/run-hash run) (:run-hash r)))
      (is (not (contains? r :diff)) "a match carries no diff"))))

(deftest compare-golden-mismatch-delegates-to-diff
  (testing "a mismatch with a kept run-result DELEGATES to diff/diff-runs for a
            localised, readable report"
    (let [captured (run-result {:app-db {:n 1}})
          g        (golden/make-golden captured {:keep-run-result true})
          changed  (run-result {:app-db {:n 2}})
          r        (golden/compare-golden g changed)]
      (is (false? (:match? r)))
      (is (= (:run-hash g) (:golden-run-hash r)))
      (is (= (fingerprint/run-hash changed) (:run-hash r)))
      ;; The diff is the diff/diff-runs facet shape — NOT a reinvented diff.
      (is (false? (get-in r [:diff :same?])))
      (is (= #{:app-db} (:facets (:diff r))))
      (is (= [{:path [:n] :baseline 1 :current 2}]
             (get-in r [:diff :app-db :changed]))
          "the readable report localises the one-key app-db drift")))

  (testing "an effect-only mismatch surfaces ONLY the :effects facet"
    (let [captured (run-result {:effects [{:fx :db/save}]})
          g        (golden/make-golden captured {:keep-run-result true})
          changed  (run-result {:effects []})
          r        (golden/compare-golden g changed)]
      (is (false? (:match? r)))
      (is (= #{:effects} (:facets (:diff r))))))

  (testing "a mismatch with NO retained run-result still states the mismatch FACT"
    (let [g       (golden/make-golden (run-result {:app-db {:n 1}}))
          changed (run-result {:app-db {:n 2}})
          r       (golden/compare-golden g changed)]
      (is (false? (:match? r)))
      (is (= :unavailable-no-run-result (:diff r))
          "without :keep-run-result the readable diff is unavailable, but the
           hash mismatch is reported")))

  (testing "a supplied :golden-run-result drives the diff even when the golden
            did not retain one"
    (let [captured (run-result {:app-db {:n 1}})
          g        (golden/make-golden captured)
          changed  (run-result {:app-db {:n 2}})
          r        (golden/compare-golden g changed
                                          {:golden-run-result captured})]
      (is (false? (:match? r)))
      (is (= #{:app-db} (:facets (:diff r)))))))

;; ===========================================================================
;; HEADLESS: capture / compare over real replays  (live frame)
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

(deftest capture-golden-from-artifact-matches-rerun
  (testing "a golden captured from an artifact (replayed into a fresh frame)
            matches a re-replay of the same program — fresh-frame volatile
            drift causes no false mismatch"
    (rf/reg-event-db :golden/inc (fn [db _] (update db :n (fnil inc 0))))
    (let [art (artifact/make-run-artifact
                {:event-program [[:dispatch [:golden/inc]] [:dispatch [:golden/inc]]]})
          g   (golden/capture-golden art {:keep-run-result true})]
      (is (golden/golden? g))
      (is (true? (golden/golden-match? g art))
          "the same program replayed again matches the captured golden")
      (is (true? (:match? (golden/compare-golden g art)))))))

(deftest compare-golden-divergent-program-surfaces-app-db-facet
  (testing "a golden captured from one program does NOT match a divergent
            program, and the report surfaces a readable :app-db facet"
    (rf/reg-event-db :golden/set (fn [db [_ v]] (assoc db :v v)))
    (let [a (artifact/make-run-artifact {:event-program [[:dispatch [:golden/set 1]]]})
          b (artifact/make-run-artifact {:event-program [[:dispatch [:golden/set 2]]]})
          g (golden/capture-golden a {:keep-run-result true})
          r (golden/compare-golden g b)]
      (is (false? (golden/golden-match? g b)))
      (is (false? (:match? r)))
      (is (contains? (:facets (:diff r)) :app-db))
      (is (= [{:path [:v] :baseline 1 :current 2}]
             (get-in r [:diff :app-db :changed]))))))
