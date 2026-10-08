(ns re-frame.story.generate-test
  "Tests for generated / property-style Story runs that emit seed-bearing run
  artifacts + shrink data, and the fault-lattice sweep
  (spec/017-Testing-Story.md §Generated runs and artifacts + §Fault lattice
  sweep): the pure PRNG, failure predicate, shrink candidates and artifact
  construction, then `check-property!` and `sweep-faults!` against a live
  frame."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core   :as rf]
            [re-frame.epoch  :as rf.epoch]
            [re-frame.frame  :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story.generate  :as rf.story.generate]
            [re-frame.story.generate.test-check :as rf.story.generate.test-check]
            #?(:clj [clojure.test.check.generators :as tcgen])))

;; ===========================================================================
;; PURE
;; ===========================================================================

;; Seed reproducibility is within-host: `next-seed`'s bit-pattern differs
;; JVM↔CLJS, so cross-host replay rides the artifact's concrete :event-program.
(deftest seed-sequence-is-reproducible
  (is (= (rf.story.generate/seed-seq 12345 8) (rf.story.generate/seed-seq 12345 8))
      "the same root seed yields the same sequence")
  (is (not= (rf.story.generate/seed-seq 1 8) (rf.story.generate/seed-seq 2 8))
      "different root seeds diverge"))

(deftest failing-predicate
  (is (rf.story.generate/failing? {:status :error}))
  (is (not (rf.story.generate/failing? {:status :cannot-run}))
      ":cannot-run is inconclusive, not a falsification"))

(deftest drop-candidates-enumeration
  (is (= [[:c :d] [:a :b]] (rf.story.generate/drop-candidates [:a :b :c :d] 2))
      "one contiguous chunk-size window removed per candidate, left to right")
  (is (= [[]] (rf.story.generate/drop-candidates [:a :b] 2))
      "a chunk >= the length yields the empty program")
  (is (= [] (rf.story.generate/drop-candidates [:a :b] 0))
      "a chunk <= 0 yields none"))

(deftest generated-artifact-carries-seed-and-shrink
  (is (= {:artifact/kind :rf.test/run-artifact
          :seed          42
          :event-program [[:dispatch [:counter/inc]]]
          :fx-decisions  {}
          :source        {:tool :rf.story/property-run :seed 42}}
         (rf.story.generate/generated-artifact {:seed 42 :event-program [[:counter/inc]]}))
      "an unshrunk run carries its seed and no :shrink-path")
  (is (= [[[:a] [:b]]]
         (:shrink-path (rf.story.generate/generated-artifact
                         {:seed 1 :event-program [[:a]] :shrink-path [[[:a] [:b]]]})))))

;; ===========================================================================
;; HEADLESS: against a live frame
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

(deftest check-property-passes-and-records-num-tests
  (rf/reg-event :gen/ok (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (is (= {:status :pass :num-tests 5 :seed 7}
         (rf.story.generate/check-property! (fn [_seed] [[:dispatch [:gen/ok]]])
                                            {:seed 7 :num-tests 5}))))

;; A throwing fx lands an `:outcome :error` effect row in the tape, which the
;; agreement floor reads as `:fail`. `:platforms #{:client :server}` so it
;; fires on the JVM.
(defn- reg-boom! []
  (rf/reg-fx :gen.fx/boom {:platforms #{:client :server}}
             (fn [_ _] (throw (ex-info "fault: boom" {}))))
  (rf/reg-event :gen/boom (fn [_ _] {:fx [[:gen.fx/boom {}]]})))

(deftest check-property-shrinks-toward-a-minimal-failing-program
  (rf/reg-event :gen/noise (fn [{:keys [db]} _] {:db (update db :noise (fnil inc 0))}))
  (reg-boom!)
  (let [program [[:dispatch [:gen/noise]] [:dispatch [:gen/noise]]
                 [:dispatch [:gen/boom]]
                 [:dispatch [:gen/noise]] [:dispatch [:gen/noise]]]
        res     (rf.story.generate/check-property! (fn [_seed] program)
                                                   {:seed 1 :num-tests 1 :shrink? true})]
    (is (= [[:dispatch [:gen/boom]]] (:smallest-program res))
        "shrinking drops every irrelevant step")
    (is (= :fail (:status (:result res))) "the shrunk program still fails")
    (is (seq (:shrink-path res)) "the shrink-path records the kept reductions")
    (is (= (:smallest-program res) (:event-program (:artifact res)))
        "the artifact carries the shrunk, host-portable program")
    (is (some? (:seed res)) "the falsifying seed is recorded")
    (is (= (:seed res) (:seed (:artifact res))) "the artifact carries the falsifying seed")))

;; ===========================================================================
;; HEADLESS: fault lattice sweep
;; ===========================================================================

(deftest sweep-faults-collects-one-artifact-per-cell
  (rf/reg-fx :app.fx/save {:platforms #{:client :server}}
             (fn [_ _] :ok))
  (rf/reg-fx :app.fx/save-broken {:platforms #{:client :server}}
             (fn [_ _] (throw (ex-info "fault: save failed" {}))))
  (rf/reg-event :app/save (fn [_ _] {:fx [[:app.fx/save {}]]}))
  (let [res     (rf.story.generate/sweep-faults!
                  [[:dispatch [:app/save]]]
                  {:healthy {} :save-fails {:app.fx/save :app.fx/save-broken}}
                  {:seed 11})
        by-cell (into {} (map (juxt :cell :artifact)) (:cells res))]
    (is (= {:app.fx/save :app.fx/save-broken} (:fx-decisions (:save-fails by-cell)))
        "the faulted cell's artifact carries its fault overrides for replay")
    (is (= 11 (:seed (:healthy by-cell))) "each cell's artifact carries the sweep seed")
    (is (= [:save-fails] (:failing res)) "only the faulted cell falsifies")))

;; ===========================================================================
;; OPTIONAL test.check adapter — JVM-only
;; ===========================================================================
;;
;; The adapter late-binds test.check via `requiring-resolve`, which CLJS
;; cannot do, and test.check is on tools/story's :test alias only.

#?(:clj
   (deftest test-check-gen-fn-is-deterministic-in-the-seed
     (let [program-gen (tcgen/vector (tcgen/return [:dispatch [:gen/ok]]) 1 4)
           gen-fn      (rf.story.generate.test-check/gen->gen-fn program-gen {:size 20})]
       (is (= (gen-fn 12345) (gen-fn 12345)) "same seed → identical program")
       (is (> (count (distinct (map gen-fn (range 0 12)))) 1)
           "the draw varies with the seed"))))

#?(:clj
   (deftest test-check-driven-property-passes-and-emits-seed-bearing-artifacts
     (rf/reg-event :gen/ok (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
     (is (= {:status :pass :num-tests 5 :seed 7}
            (rf.story.generate.test-check/check-property-gen!
              rf.story.generate/check-property!
              (tcgen/vector (tcgen/return [:dispatch [:gen/ok]]) 1 3)
              {:seed 7 :num-tests 5 :size 25})))))
