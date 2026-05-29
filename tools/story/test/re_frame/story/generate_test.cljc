(ns re-frame.story.generate-test
  "Tests for generated / property-style Story runs that emit seed-bearing run
  artifacts + shrink data, and the fault-lattice sweep (rf2-5x1wt.31,
  spec/017-Testing-Story.md §Generated runs and artifacts + §Fault lattice
  sweep).

  Two layers, both under `clojure -M:test` (JVM) + the node-runtime CLJS
  build:

  - PURE: the seedable PRNG (`next-seed` / `seed-seq` reproducible from a
    root seed), the failure predicate, the shrink candidate enumeration, and
    seed-bearing artifact construction.
  - HEADLESS (against a live frame): `check-property!` generates programs,
    replays them into FRESH frames, and on falsification returns a SHRUNK,
    seed-bearing failing `:rf.test/run-artifact` that promotes through the
    existing bridge; `sweep-faults!` collects one artifact per fault cell."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core   :as rf]
            [re-frame.epoch  :as epoch]
            [re-frame.frame  :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story.artifact  :as artifact]
            [re-frame.story.generate  :as generate]
            [re-frame.story.promotion :as promotion]))

;; ===========================================================================
;; PURE: the seedable PRNG
;; ===========================================================================

(deftest seed-sequence-is-reproducible
  ;; rf2-f3ofr — seed-reproducibility is WITHIN-host: `next-seed`'s bit-pattern
  ;; differs JVM↔CLJS (CLJS truncates the splitmix64 mix into MAX_SAFE_INTEGER),
  ;; so a recorded bare `:seed` reproduces a run only on the host that produced
  ;; it. Within a host the sequence is fully deterministic (asserted here);
  ;; cross-host replay rides the recorded concrete `:event-program` (asserted in
  ;; `generated-failure-promotes-through-the-existing-bridge`). The spec
  ;; (spec/017 §Generator-agnostic) now qualifies the claim as within-host.
  (testing "WITHIN a host the same root seed yields the SAME sequence (reproducibility)"
    (is (= (generate/seed-seq 12345 8) (generate/seed-seq 12345 8))
        "a property run replays identically from its recorded :seed on the same host"))
  (testing "different root seeds diverge"
    (is (not= (generate/seed-seq 1 8) (generate/seed-seq 2 8))))
  (testing "seed-seq length + the root seed is never reused as a program seed"
    (let [s (generate/seed-seq 99 5)]
      (is (= 5 (count s)))
      (is (not (some #{99} s)) "root seed itself is not in the program-seed sequence")))
  (testing "next-seed is a pure, deterministic successor"
    (is (= (generate/next-seed 7) (generate/next-seed 7)))))

;; ===========================================================================
;; PURE: failure predicate + shrink candidate enumeration
;; ===========================================================================

(deftest failing-predicate
  (testing ":fail / :error falsify; :pass / :cannot-run do not"
    (is (generate/failing? {:status :fail}))
    (is (generate/failing? {:status :error}))
    (is (not (generate/failing? {:status :pass})))
    (is (not (generate/failing? {:status :cannot-run}))
        ":cannot-run is inconclusive, not a falsification")))

(deftest drop-candidates-enumeration
  (testing "drop-candidates removes one contiguous chunk-size window, L→R"
    (is (= [[:b :c :d] [:a :c :d] [:a :b :d] [:a :b :c]]
           (generate/drop-candidates [:a :b :c :d] 1))
        "chunk-size 1: drop each single step in turn (one candidate per step)")
    (is (= [[:c :d] [:a :b]]
           (generate/drop-candidates [:a :b :c :d] 2))
        "chunk-size 2: drop the first pair, then the second pair"))
  (testing "a chunk >= length yields the empty program; <= 0 yields none"
    (is (= [[]] (generate/drop-candidates [:a :b] 2)))
    (is (= []  (generate/drop-candidates [:a :b] 0)))
    (is (= []  (generate/drop-candidates [] 3)))))

;; ===========================================================================
;; PURE: seed-bearing artifact construction
;; ===========================================================================

(deftest generated-artifact-carries-seed-and-shrink
  (testing "a generated artifact is a :rf.test/run-artifact carrying its :seed"
    (let [a (generate/generated-artifact
              {:seed 42 :event-program [[:counter/inc]]})]
      (is (artifact/run-artifact? a))
      (is (= 42 (:seed a)))
      (is (= [[:dispatch [:counter/inc]]] (:event-program a))
          "a bare event vector lifts to a tagged dispatch program")
      (is (= :rf.story/property-run (get-in a [:source :tool])))))
  (testing "a shrunk failure carries its :shrink-path; absent when not shrunk"
    (let [shrunk (generate/generated-artifact
                   {:seed 1 :event-program [[:a]] :shrink-path [[[:a] [:b]]]})
          plain  (generate/generated-artifact {:seed 1 :event-program [[:a]]})]
      (is (= [[[:a] [:b]]] (:shrink-path shrunk)))
      (is (not (contains? plain :shrink-path))))))

;; ===========================================================================
;; HEADLESS: against a live frame
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

(deftest check-property-passes-and-records-num-tests
  (testing "a property that always holds passes over N seeds"
    ;; A handler that always succeeds — every generated program passes.
    (rf/reg-event-db :gen/ok (fn [db _] (update db :n (fnil inc 0))))
    (let [res (generate/check-property!
                (fn [_seed] [[:dispatch [:gen/ok]]])
                {:seed 7 :num-tests 5})]
      (is (= :pass (:status res)))
      (is (= 5 (:num-tests res)))
      (is (= 7 (:seed res)) "the reproducible root seed is recorded"))))

;; A throwing fx + the handler that emits it — an fx-handler exception lands
;; an `:outcome :error` effect row in the tape, which the agreement floor
;; (`evidence/tape-shows-failure?` via `error-effect?`) reads as `:fail`.
;; `:platforms #{:client :server}` so it fires on the JVM (`:server`) gate.
(defn- reg-boom! []
  (rf/reg-fx :gen.fx/boom {:platforms #{:client :server}}
             (fn [_ _] (throw (ex-info "fault: boom" {}))))
  (rf/reg-event-fx :gen/boom (fn [_ _] {:fx [[:gen.fx/boom {}]]})))

(deftest check-property-falsifies-and-emits-seed-bearing-artifact
  (testing "a property that fails yields a seed-bearing failing artifact"
    (reg-boom!)
    (let [res (generate/check-property!
                (fn [_seed] [[:dispatch [:gen/boom]]])
                {:seed 3 :num-tests 4 :shrink? false})]
      (is (= :fail (:status res)))
      (is (some? (:seed res)) "the failing program's seed is recorded")
      (is (artifact/run-artifact? (:artifact res)))
      (is (= (:seed res) (:seed (:artifact res)))
          "the artifact carries the falsifying seed")
      (is (= :fail (:status (:result res)))))))

(deftest check-property-shrinks-toward-a-minimal-failing-program
  (testing "shrinking drops irrelevant steps, keeping the minimal failing case"
    ;; :gen/noise is harmless; :gen/boom emits the failing effect. A program
    ;; with many noise steps + one boom should shrink toward just the boom.
    (rf/reg-event-db :gen/noise (fn [db _] (update db :noise (fnil inc 0))))
    (reg-boom!)
    (let [program [[:dispatch [:gen/noise]] [:dispatch [:gen/noise]]
                   [:dispatch [:gen/boom]]
                   [:dispatch [:gen/noise]] [:dispatch [:gen/noise]]]
          res (generate/check-property!
                (fn [_seed] program)
                {:seed 1 :num-tests 1 :shrink? true})]
      (is (= :fail (:status res)))
      (is (<= (count (:smallest-program res)) (count program))
          "the shrunk program is no larger than the original")
      (is (some #(= [:dispatch [:gen/boom]] %) (:smallest-program res))
          "the minimal failing program retains the boom step")
      (is (= :fail (:status (:result res))) "the shrunk program still fails")
      (is (seq (:shrink-path res)) "the shrink-path records the kept reductions")
      (is (= (:smallest-program res) (:event-program (:artifact res)))
          "the artifact carries the shrunk program"))))

(deftest generated-failure-promotes-through-the-existing-bridge
  (testing "a generated failure's artifact promotes into a curated variant,
            preserving the source link + the seed/shrink provenance"
    (reg-boom!)
    (let [res      (generate/check-property!
                     (fn [_seed] [[:dispatch [:gen/boom]]])
                     {:seed 5 :num-tests 2 :shrink? true})
          artifact (:artifact res)
          ;; materialize-variant-plan is PURE — registers nothing; it proves
          ;; the generated failure flows through the promotion bridge with
          ;; its seed-bearing source link intact.
          plan     (promotion/materialize-variant-plan
                     artifact {:variant/id :story.gen/regression-5})]
      (is (= :fail (:status res)))
      (is (= :rf.test/run-artifact (get-in plan [:run-artifact :artifact/kind]))
          "the materialized plan preserves the source-artifact link")
      (is (= (:seed artifact) (get-in plan [:run-artifact :seed]))
          "the curated plan's provenance carries the falsifying seed")
      (is (contains? (:run-artifact plan) :event-program)
          "the source link is replayable (carries the event program)")
      ;; rf2-f3ofr — the CONCRETE :event-program (host-portable tagged-step
      ;; data) is the cross-host reproducer, NOT the within-host-only :seed.
      ;; The promoted plan must carry enough program to replay the failure on
      ;; ANY host (CLJS author → JVM CI), so a non-empty tagged-step vector is
      ;; the genuine cross-host bridge.
      (is (let [prog (get-in plan [:run-artifact :event-program])]
            (and (vector? prog) (seq prog) (every? vector? prog)))
          "the cross-host reproducer is the concrete tagged-step program, not the seed"))))

;; ===========================================================================
;; HEADLESS: fault lattice sweep
;; ===========================================================================

(deftest sweep-faults-collects-one-artifact-per-cell
  (testing "a fault sweep replays the base program across fx-override cells,
            collecting one seed-bearing artifact per cell + the failing ids"
    ;; :app.fx/save is the 'real' effect. The :fail fault stub raises; the
    ;; :ok cell uses no override (the real fx records cleanly).
    (rf/reg-fx :app.fx/save {:platforms #{:client :server}}
               (fn [_ _] :ok))
    (rf/reg-fx :app.fx/save-broken {:platforms #{:client :server}}
               (fn [_ _]
                 (throw (ex-info "fault: save failed" {}))))
    (rf/reg-event-fx :app/save (fn [_ _] {:fx [[:app.fx/save {}]]}))
    (let [base   [[:dispatch [:app/save]]]
          lattice {:healthy {}
                   :save-fails {:app.fx/save :app.fx/save-broken}}
          res    (generate/sweep-faults! base lattice {:seed 11})]
      (is (= 2 (count (:cells res))) "one cell per lattice entry")
      (let [by-cell (into {} (map (juxt :cell identity)) (:cells res))]
        (is (artifact/run-artifact? (:artifact (:healthy by-cell))))
        (is (artifact/run-artifact? (:artifact (:save-fails by-cell))))
        (is (= {:app.fx/save :app.fx/save-broken}
               (:fx-decisions (:artifact (:save-fails by-cell))))
            "the faulted cell's artifact carries its fault overrides for replay")
        (is (= 11 (:seed (:artifact (:healthy by-cell))))
            "each cell's artifact carries the sweep seed for provenance"))
      ;; The :save-fails cell errored → it is in :failing.
      (is (some #{:save-fails} (:failing res))
          "the faulted cell falsifies and is reported in :failing"))))

;; rf2-4cdhy — sweep-faults! accepts a fault-lattice as EITHER a map
;; {cell-id fx-decisions} OR a seq of [cell-id fx-decisions] pairs, and both
;; produce identical sweeps cell-for-cell (the collapsed `(seq fault-lattice)`
;; handles both — a map seqs to its entry pairs, a pair-seq seqs to its pairs).
(deftest sweep-faults-accepts-map-and-pair-seq-equivalently
  (testing "a map lattice and the same lattice as a [cell-id fx] pair-seq sweep identically"
    (rf/reg-fx :app.fx/save {:platforms #{:client :server}} (fn [_ _] :ok))
    (rf/reg-fx :app.fx/save-broken {:platforms #{:client :server}}
               (fn [_ _] (throw (ex-info "fault: save failed" {}))))
    (rf/reg-event-fx :app/save (fn [_ _] {:fx [[:app.fx/save {}]]}))
    (let [base        [[:dispatch [:app/save]]]
          ;; The SAME lattice in both shapes, in the same cell order.
          as-map      {:healthy {} :save-fails {:app.fx/save :app.fx/save-broken}}
          as-pair-seq [[:healthy {}] [:save-fails {:app.fx/save :app.fx/save-broken}]]
          from-map    (generate/sweep-faults! base as-map      {:seed 7})
          from-seq    (generate/sweep-faults! base as-pair-seq {:seed 7})
          cell-shape  (fn [c] {:cell (:cell c)
                               :status (:status c)
                               :fx-decisions (:fx-decisions (:artifact c))})]
      (is (= (mapv cell-shape (:cells from-map))
             (mapv cell-shape (:cells from-seq)))
          "both shapes sweep the same cells, in the same order, with the same faults")
      (is (= (:failing from-map) (:failing from-seq))
          "both shapes report the same failing cell ids")
      (is (= [:healthy :save-fails] (mapv :cell (:cells from-seq)))
          "the pair-seq preserves authored cell order")
      (is (some #{:save-fails} (:failing from-seq))
          "the faulted cell falsifies under the pair-seq shape too"))))
