(ns re-frame.ui.reactive-commit-destroy-race-jvm-test
  "rf2-vxgfnd.61 — linearize ViewCell commit against frame-destroy teardown.

  THE RACE (JVM-only; CLJS is single-threaded and runs destroy to completion
  without yielding to a commit). #5731 wires `frame/destroy-frame!` to fire the
  `:ui/on-frame-destroyed!` sweep, which snapshots the live-cell registry and
  tears down its victims — WHILE the frame is intentionally still live — and
  only AFTERWARDS flips the liveness bit (`mark-frame-destroyed!`) and tears
  down the sub-cache. A ViewCell commit that acquires leases and enrols a cell
  in the window BETWEEN the sweep's snapshot and the liveness flip is absent
  from the sweep's victim vector, publishes `:connected`, and then loses its
  cache underneath it during the remaining destroy recipe — the exact
  live-cell/port-throw state #5731 meant to eliminate.

  This fixture reproduces the window with a deterministic `CountDownLatch`
  handoff (NO sleeps): the destroy pauses inside a wrapped hook after the sweep
  and before the liveness flip; a racing thread commits a fresh cell into that
  window; the destroy then resumes. The fix — a commit-side revalidation against
  `frame/frame-closing?` after the enrolment publish — makes the racing cell
  JOIN the teardown (`:dead`, leases released against the still-live cache)
  instead of stranding `:connected`. Without the fix the racing cell is left
  `:connected` on the dying frame, so this test FAILS on pre-fix code.

  `.clj` (JVM-only) — the race is a genuine two-thread interleaving; the barrier
  handoff means only one thread mutates the substrate at a time, so this is a
  controlled linearization probe, not a stress test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core                  :as rf]
            [re-frame.frame                 :as frame]
            [re-frame.late-bind             :as late-bind]
            [re-frame.live-frame            :as live-frame]
            [re-frame.substrate.plain-atom  :as plain-atom]
            [re-frame.test-support          :as test-support]
            ;; Load-bearing: publishes the :ui/on-frame-destroyed! hook.
            [re-frame.ui.frames]
            [re-frame.ui.reactive           :as reactive])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(defn- make-frame! [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
  id)

(defn- render+commit! [cell fid queries]
  (let [[_ capture] (rf/with-frame fid
                      (reactive/with-capture
                       cell (fn [] (mapv (fn [i q]
                                          (reactive/sub-read [:race/site i] q))
                                        (range) queries))))]
    (reactive/commit! cell capture))
  cell)

;; ===========================================================================
;; The window: a commit that enrols AFTER the sweep snapshot, BEFORE the flip
;; ===========================================================================

(deftest commit-in-destroy-window-joins-teardown-not-stranded-connected
  (rf/reg-sub :race/a (fn [db _] (:a db)))
  (let [fid       (make-frame! :race/frame {:a 1})
        ;; A cell committed BEFORE destroy — the sweep's ordinary victim.
        old-cell  (render+commit! (reactive/make-cell ::old) fid [[:race/a]])
        ;; The cell that races IN during the destroy window.
        new-cell  (reactive/make-cell ::new)
        swept     (CountDownLatch. 1)   ;; the sweep snapshot has run
        committed (CountDownLatch. 1)   ;; the racing commit has finished
        orig-hook (late-bind/get-fn :ui/on-frame-destroyed!)]
    (is (= :connected (reactive/lifecycle old-cell)) "precondition: connected")
    (try
      ;; Wrap the real destroy hook so the destroy PAUSES between the sweep
      ;; (which snapshots live-cells and tears down its victims) and the next
      ;; step of destroy-frame! (mark-frame-destroyed! — the liveness flip).
      ;; `destroying-frames` already contains fid here (populated at the top of
      ;; destroy-frame!, before this hook), and the frame is still live.
      (late-bind/set-fn! :ui/on-frame-destroyed!
                         (fn [id]
                           (orig-hook id)                        ;; real sweep
                           (.countDown swept)                    ;; sweep done
                           (.await committed 5 TimeUnit/SECONDS))) ;; hold the window open
      (let [race (future
                   (.await swept 5 TimeUnit/SECONDS)
                   ;; Commit the fresh cell against the STILL-LIVE frame — it
                   ;; acquires a lease and enrols into the live-cell registry
                   ;; AFTER the sweep already snapshotted.
                   (let [[_ capture] (rf/with-frame fid
                                       (reactive/with-capture new-cell
                                         (fn [] (reactive/sub-read ::site [:race/a]))))]
                     (reactive/commit! new-cell capture))
                   (.countDown committed))]
        ;; Drive the destroy on this thread — it blocks in the wrapped hook
        ;; until the racing commit releases `committed`, then resumes (flip +
        ;; sub-cache teardown) and returns.
        (is (nil? (frame/destroy-frame! fid)) "destroy completes cleanly")
        (is (nil? (deref race 5000 ::timeout)) "racing commit finished"))

      (testing "the racing cell is NOT stranded :connected on the dying frame"
        (is (= :dead (reactive/lifecycle new-cell))
            "a cell enrolling during the destroy window joined the teardown via
             the frame-closing? revalidation — not left :connected on a frame
             whose cache is being torn down")
        (is (empty? (reactive/committed-target-keys new-cell))
            "its lease was released against the still-live cache — no port throw
             on a later probe of the destroyed frame"))

      (testing "the frame is genuinely destroyed"
        (is (nil? (frame/frame fid)) "destroy-frame! forgot the frame record"))

      (testing "the pre-existing cell was swept normally"
        (is (= :dead (reactive/lifecycle old-cell))))
      (finally
        (late-bind/set-fn! :ui/on-frame-destroyed! orig-hook)))))

;; ===========================================================================
;; Disjoint frames still commit/destroy concurrently — no global serialization
;; ===========================================================================

(deftest disjoint-frame-commit-unaffected-by-another-frames-destroy
  (rf/reg-sub :race/a (fn [db _] (:a db)))
  (let [fa        (make-frame! :race/frame-a {:a 1})
        fb        (make-frame! :race/frame-b {:a 2})
        cell-a    (render+commit! (reactive/make-cell ::a) fa [[:race/a]])
        cell-b    (reactive/make-cell ::b)
        swept     (CountDownLatch. 1)
        committed (CountDownLatch. 1)
        orig-hook (late-bind/get-fn :ui/on-frame-destroyed!)]
    (try
      (late-bind/set-fn! :ui/on-frame-destroyed!
                         (fn [id]
                           (orig-hook id)
                           (.countDown swept)
                           (.await committed 5 TimeUnit/SECONDS)))
      (let [race (future
                   (.await swept 5 TimeUnit/SECONDS)
                   ;; A commit onto the UNRELATED live frame B, mid-destroy of A.
                   (let [[_ capture] (rf/with-frame fb
                                       (reactive/with-capture cell-b
                                         (fn [] (reactive/sub-read ::site [:race/a]))))]
                     (reactive/commit! cell-b capture))
                   (.countDown committed))]
        (frame/destroy-frame! fa)
        (deref race 5000 ::timeout))
      (testing "frame B's cell commits normally — B's destroy is not in flight"
        (is (= :connected (reactive/lifecycle cell-b))
            "a commit onto a disjoint live frame is untouched by frame A's
             teardown — frame-closing? is false for B, so no global serialization")
        (is (reactive/cell-observes-frame? cell-b fb))
        (is (= 2 (first (rf/with-frame fb
                          (reactive/with-capture cell-b
                            (fn [] (reactive/sub-read ::site [:race/a]))))))
            "frame B reads live after A was destroyed"))
      (testing "frame A's own cell died with A"
        (is (= :dead (reactive/lifecycle cell-a))))
      (finally
        (late-bind/set-fn! :ui/on-frame-destroyed! orig-hook)))))
