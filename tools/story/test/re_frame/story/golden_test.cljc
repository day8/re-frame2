(ns re-frame.story.golden-test
  "Tests for golden slices — curated canonicalized run regression artifacts
  (spec/017-Testing-Story.md §Golden slices): capture / match / readable
  report over hand-built run-results, then capture and compare over real
  replays."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core      :as rf]
            [re-frame.epoch     :as rf.epoch]
            [re-frame.frame     :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story.artifact    :as rf.story.artifact]
            [re-frame.story.fingerprint :as rf.story.fingerprint]
            [re-frame.story.golden      :as rf.story.golden]))

;; ===========================================================================
;; FIXTURES — hand-built run-results
;; ===========================================================================

(defn- run-result
  "A minimal hand-built run-result over the behavioural slice."
  [app-db]
  {:status :pass
   :app-db app-db
   :effects []
   :sub-runs []
   :assertions []
   :checks []
   :schema-violations []
   :warnings []
   :epoch-tape [{:epoch-id 1
                 :trace-events [{:operation :rf.event/run-start :op-type :event}]}]})

(defn- noisy-run
  "A run-result whose epoch tape + app-db carry per-run stamps a fresh-frame
  replay would write differently every time (frame id, epoch id,
  committed-at, trace :id / :time), wrapping the same semantic `db-after`."
  [{:keys [frame epoch-id committed-at trace-id db-after]}]
  {:status :pass
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
;; PURE: capture
;; ===========================================================================

(deftest make-golden-shape
  (let [run (run-result {:n 1})
        m   {:variant/id :story.checkout/ok :doc "checkout happy path"}
        g   (rf.story.golden/make-golden run {:meta m})]
    (is (= {:golden/kind :rf.test/golden
            :slice-keys  rf.story.fingerprint/run-hash-input-keys
            :golden/meta m}
           (select-keys g [:golden/kind :slice-keys :golden/meta])))
    (is (= (:canonical (rf.story.golden/make-golden run)) (:canonical g))
        "curation provenance never perturbs the regression baseline")))

;; ===========================================================================
;; PURE: golden-match? — volatile fields do NOT cause a false miss
;; ===========================================================================

(deftest golden-match-ignores-volatile-fields
  (let [g (rf.story.golden/make-golden
            (noisy-run {:frame :rf.test.replay/frame-aaa :epoch-id 5
                        :committed-at 1000 :trace-id 17 :db-after {:n 1}}))
        rerun (fn [db] (noisy-run {:frame :rf.test.replay/frame-zzz :epoch-id 99
                                   :committed-at 2000 :trace-id 88 :db-after db}))]
    (is (true? (rf.story.golden/golden-match? g (rerun {:n 1})))
        "frame ids, epoch ids, timestamps, trace ids are NOT mismatches")
    (is (false? (rf.story.golden/golden-match? g (rerun {:n 2})))
        "a real app-db change is still caught amid the per-run noise")))

;; ===========================================================================
;; PURE: compare-golden — the readable report delegates to diff/diff-runs
;; ===========================================================================

(deftest compare-golden-match-report
  (let [run (run-result {:n 1})]
    (is (= {:match? true :run-hash (rf.story.fingerprint/run-hash run)}
           (rf.story.golden/compare-golden (rf.story.golden/make-golden run) run)))))

(deftest compare-golden-mismatch-delegates-to-diff
  (let [captured (run-result {:n 1})
        changed  (run-result {:n 2})
        bare     (rf.story.golden/make-golden captured)]
    (testing "a kept run-result drives a localised diff/diff-runs report"
      (let [g (rf.story.golden/make-golden captured {:keep-run-result true})]
        (is (= {:match?          false
                :run-hash        (rf.story.fingerprint/run-hash changed)
                :golden-run-hash (:run-hash g)
                :diff            {:same? false :facets #{:app-db}
                                  :app-db {:changed [{:path [:n] :baseline 1 :current 2}]}}}
               (rf.story.golden/compare-golden g changed)))))
    (testing "with no retained run-result the mismatch fact is still reported"
      (is (= :unavailable-no-run-result
             (:diff (rf.story.golden/compare-golden bare changed)))))
    (testing "a supplied :golden-run-result drives the diff"
      (is (= #{:app-db}
             (:facets (:diff (rf.story.golden/compare-golden
                               bare changed {:golden-run-result captured}))))))))

;; ===========================================================================
;; PURE: the frozen :slice-keys DRIVE compare (drift-detection)
;; ===========================================================================

(deftest compare-golden-stale-slice-keys-distinct-verdict
  (let [run   (run-result {:n 1})
        drift (conj rf.story.fingerprint/run-hash-input-keys :some-future-slot)
        stale (assoc (rf.story.golden/make-golden run {:keep-run-result true})
                     :slice-keys drift)]
    (testing "compare-golden returns the distinct :stale-slice-keys verdict, no diff"
      (is (= {:match?             false
              :stale-slice-keys   true
              :golden-slice-keys  drift
              :current-slice-keys rf.story.fingerprint/run-hash-input-keys}
             (rf.story.golden/compare-golden stale run))))
    (testing "golden-match? is false even for the very run the golden was captured from"
      (is (false? (rf.story.golden/golden-match? stale run))))))

;; ===========================================================================
;; PURE: capture FAILS CLOSED on an unrecognized target
;; ===========================================================================

(deftest capture-golden-rejects-bad-target
  (let [bad {:event-program [[:dispatch [:x]]]} ; no :artifact/kind, no :status
        e   (try (rf.story.golden/capture-golden bad)
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))]
    (is (= {:rf.error/id :rf.error/golden-bad-target :target bad}
           (select-keys (ex-data e) [:rf.error/id :target])))))

;; ===========================================================================
;; HEADLESS: capture / compare over real replays  (live frame)
;; ===========================================================================

(defn- reset-rf! [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.epoch/clear-history!)
  (rf.epoch/clear-epoch-listeners!)
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (rf.frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-rf!)

(deftest capture-golden-from-artifact-matches-rerun
  (rf/reg-event :golden/set (fn [{:keys [db]} [_ v]] {:db (assoc db :v v)}))
  (let [a (rf.story.artifact/make-run-artifact {:event-program [[:dispatch [:golden/set 1]]]})
        b (rf.story.artifact/make-run-artifact {:event-program [[:dispatch [:golden/set 2]]]})
        g (rf.story.golden/capture-golden a {:keep-run-result true})]
    (is (true? (rf.story.golden/golden-match? g a))
        "a re-replay of the captured program matches despite fresh-frame drift")
    (is (= [{:path [:v] :baseline 1 :current 2}]
           (get-in (rf.story.golden/compare-golden g b) [:diff :app-db :changed]))
        "a divergent program reports a readable :app-db facet")))

;; An artifact built from a plan drops the plan's decorator stubs, :db-seed,
;; frame-setup, loaders, terminal expectations and extra plays, so a variant's
;; golden is captured from the run-result of running the variant instead.
(deftest capture-golden-refuses-a-normalized-plan
  (let [plan {:variant/id :story.golden/plan
              :world  {:setup [[:dispatch [:golden/seed 10]]]}
              :script [[:dispatch [:golden/seed 11]]]}
        g    (rf.story.golden/make-golden (run-result {:v 11}))]
    (doseq [[label f] [["capture-golden" #(rf.story.golden/capture-golden plan)]
                       ["golden-match?"  #(rf.story.golden/golden-match? g plan)]
                       ["compare-golden" #(rf.story.golden/compare-golden g plan)]]]
      (let [e (try (f) nil
                   (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))]
        (is (= :rf.error/golden-bad-target (:rf.error/id (ex-data e)))
            (str label " must refuse a plan"))))))
