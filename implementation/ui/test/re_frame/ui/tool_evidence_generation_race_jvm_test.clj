(ns re-frame.ui.tool-evidence-generation-race-jvm-test
  "rf2-vxgfnd.147 — the two-thread JVM latch/barrier races over the
  generation-fenced evidence lifecycle (the deterministic single-thread
  replays live in `tool_evidence_generation_cljs_test.cljc`).

  The races the bead names, each opened deterministically with a
  `CountDownLatch` handoff (no sleeps — the barrier makes each a
  controlled linearization probe, not a stress test):

    1. PAUSED CALLBACK vs UNINSTALL — a delivery thread captures the
       armed sink (the `deliver-flush!` deref), parks, the owner
       uninstalls, the delivery resumes: the closed generation fences it
       out; the ownerless projection stays empty.
    2. A's LATE CALLBACK vs B's INSTALL — A's parked delivery resumes
       after B claimed and accreted fresh evidence: B's projection is
       uncontaminated.
    3. A-UNINSTALL vs B-INSTALL — raced from two threads under a start
       barrier, every observed outcome must be one of the two legal
       linearizations (A-close→B-claim, or B-reject→A-close); in
       particular an installed B always has a LIVE armed sink (a stale A
       teardown can never have disarmed it) and a refused B leaves a
       cleanly closed projection.
    4. CONCURRENT PROJECT BATCHES — parallel deliveries into own + shared
       cells commit exactly (occurrence counts, batch counts, distinct
       stable ordinals): publication is one atomic swap, so concurrency
       loses nothing and double-mints nothing.
    5. DELIVERY LOOP vs UNINSTALL — however the in-flight publication
       interleaves with the closing transition, the closed state retains
       zero entries (publication-then-clear or fence — both legal)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.ui.reactive          :as reactive]
            [re-frame.ui.tool.evidence     :as evidence])
  (:import [java.util.concurrent CountDownLatch CyclicBarrier TimeUnit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (evidence/force-release!)
    (try (f) (finally
               (reactive/reset-scheduler!)
               (evidence/force-release!)))))

(defn- entry-for [vid]
  (some #(when (= vid (:view-id %)) %) (evidence/projection)))

(def ^:private ev-1
  {:first-epoch 1 :latest-epoch 1 :count 1
   :causes #{} :targets [] :dropped #{} :dropped-exact? true})

;; ===========================================================================
;; Race 1 — paused callback vs uninstall
;; ===========================================================================

(deftest paused-delivery-resuming-after-uninstall-is-fenced-out
  (is (true? (evidence/install! ::tool-a)))
  (let [cell       (reactive/make-cell ::v)
        captured   (CountDownLatch. 1)   ;; T1 holds the armed sink
        released   (CountDownLatch. 1)   ;; main finished the uninstall
        delivered  (CountDownLatch. 1)
        delivery   (future
                     (let [sink (evidence/installed-sink)] ;; the flush's deref
                       (.countDown captured)
                       (.await released 5 TimeUnit/SECONDS)
                       ;; the delayed callback resumes AFTER the close
                       (sink cell ev-1)
                       (.countDown delivered)))]
    (.await captured 5 TimeUnit/SECONDS)
    (is (true? (evidence/uninstall! ::tool-a)))
    (.countDown released)
    (is (true? (.await delivered 5 TimeUnit/SECONDS)))
    (is (nil? (deref delivery 5000 ::timeout)))
    (is (nil? (evidence/installed-owner)))
    (is (= [] (evidence/projection))
        "closed-retains-zero: the paused delivery could not repopulate the
         ownerless projection (red under unchecked multi-atom publication)")))

;; ===========================================================================
;; Race 2 — A's late callback vs B's fresh projection
;; ===========================================================================

(deftest late-a-delivery-resuming-under-owner-b-is-fenced-out
  (is (true? (evidence/install! ::tool-a)))
  (let [cell-a     (reactive/make-cell ::va)
        captured   (CountDownLatch. 1)
        b-ready    (CountDownLatch. 1)   ;; B installed + accreted
        delivered  (CountDownLatch. 1)
        delivery   (future
                     (let [sink (evidence/installed-sink)]
                       (.countDown captured)
                       (.await b-ready 5 TimeUnit/SECONDS)
                       (sink cell-a ev-1)
                       (.countDown delivered)))]
    (.await captured 5 TimeUnit/SECONDS)
    (is (true? (evidence/uninstall! ::tool-a)))
    (is (true? (evidence/install! ::tool-b)))
    (let [cell-b (reactive/make-cell ::vb)]
      (reactive/mark-dirty! cell-b 9)
      (reactive/flush-pending!)
      (is (= 1 (count (evidence/projection))) "precondition: B's one row"))
    (.countDown b-ready)
    (is (true? (.await delivered 5 TimeUnit/SECONDS)))
    (is (nil? (deref delivery 5000 ::timeout)))
    (testing "B's supposedly-fresh projection is actually fresh"
      (let [rows (evidence/projection)]
        (is (= [::vb] (mapv :view-id rows))
            "A's late delivery did not contaminate B (no ::va row)")
        (is (= 1 (get-in (entry-for ::vb) [:evidence :count])))))))

;; ===========================================================================
;; Race 3 — A-uninstall vs B-install under a start barrier: every observed
;; outcome must be a legal linearization, and an installed B always has a
;; LIVE sink (the pre-fix failure: A's late teardown disarmed B's)
;; ===========================================================================

(deftest a-uninstall-racing-b-install-always-linearizes-legally
  (dotimes [round 50]
    (evidence/force-release!)
    (reactive/reset-scheduler!)
    (is (true? (evidence/install! ::tool-a)))
    (let [barrier   (CyclicBarrier. 2)
          uninstall (future (.await barrier 5 TimeUnit/SECONDS)
                            (evidence/uninstall! ::tool-a))
          install   (future (.await barrier 5 TimeUnit/SECONDS)
                            (evidence/install! ::tool-b))
          u-result  (deref uninstall 5000 ::timeout)
          i-result  (deref install 5000 ::timeout)
          owner     (evidence/installed-owner)]
      (is (true? u-result)
          (str "round " round ": A owned the projection, so A's uninstall
               released it under EVERY legal order"))
      (is (contains? #{true false} i-result))
      (case owner
        ::tool-b
        (do (is (true? i-result)
                (str "round " round ": owner B implies B's install succeeded"))
            ;; the pre-fix corruption: owner B but sink disarmed by A's
            ;; late teardown → delivery dead. Prove B's sink is LIVE.
            (let [cell (reactive/make-cell (keyword "vr" (str round)))]
              (reactive/mark-dirty! cell 1)
              (reactive/flush-pending!)
              (is (= 1 (count (evidence/projection)))
                  (str "round " round ": an installed B delivers — its sink
                       and state were never clobbered by the stale A close"))))

        nil
        (do (is (false? i-result)
                (str "round " round ": ownerless end-state implies B was
                     REJECTED while A still owned (legal linearization
                     B-reject → A-close)"))
            (is (= [] (evidence/projection))
                (str "round " round ": the closed projection retains zero"))
            ;; and delivery is fully disarmed
            (let [cell (reactive/make-cell (keyword "vr" (str round)))]
              (reactive/mark-dirty! cell 1)
              (reactive/flush-pending!)
              (is (= [] (evidence/projection)))))

        (is false (str "round " round ": ILLEGAL end-state owner " owner))))))

;; ===========================================================================
;; Race 4 — concurrent project batches commit exactly
;; ===========================================================================

(deftest concurrent-project-batches-commit-exactly
  (is (true? (evidence/install! ::tool-a)))
  (let [m       200
        cell-1  (reactive/make-cell ::v1)
        cell-2  (reactive/make-cell ::v2)
        shared  (reactive/make-cell ::shared)
        sink    (evidence/installed-sink)
        barrier (CyclicBarrier. 2)
        worker  (fn [own]
                  (future
                    (.await barrier 5 TimeUnit/SECONDS)
                    (dotimes [_ m]
                      (sink own ev-1)
                      (sink shared ev-1))
                    true))
          t1    (worker cell-1)
          t2    (worker cell-2)]
    (is (true? (deref t1 10000 ::timeout)))
    (is (true? (deref t2 10000 ::timeout)))
    (testing "occurrence + batch counts are exact under contention"
      (is (= m (get-in (entry-for ::v1) [:evidence :count])))
      (is (= m (get-in (entry-for ::v1) [:evidence :batches])))
      (is (= m (get-in (entry-for ::v2) [:evidence :count])))
      (is (= (* 2 m) (get-in (entry-for ::shared) [:evidence :count])))
      (is (= (* 2 m) (get-in (entry-for ::shared) [:evidence :batches]))))
    (testing "ordinals minted once per cell, all distinct and stable"
      (let [ids (mapv :cell-id (evidence/projection))]
        (is (= 3 (count ids)))
        (is (= 3 (count (distinct ids))))))))

;; ===========================================================================
;; Race 5 — a delivery loop racing the closing transition always ends closed
;; ===========================================================================

(deftest delivery-loop-racing-uninstall-ends-closed-and-empty
  (is (true? (evidence/install! ::tool-a)))
  (let [k        400
        cell     (reactive/make-cell ::v)
        sink     (evidence/installed-sink)
        halfway  (CountDownLatch. 1)
        delivery (future
                   (dotimes [i k]
                     (when (= i (quot k 2)) (.countDown halfway))
                     (sink cell ev-1))
                   true)]
    (.await halfway 5 TimeUnit/SECONDS)
    (is (true? (evidence/uninstall! ::tool-a)))
    (is (true? (deref delivery 10000 ::timeout)))
    (testing "whatever interleaving occurred, the closed state retains zero"
      (is (nil? (evidence/installed-owner)))
      (is (= [] (evidence/projection))
          "publication-then-clear or fence — both linearize to empty"))))
