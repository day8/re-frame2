(ns re-frame.sub-cache-concurrency-test
  "JVM-only concurrency tests for the sub-cache disposal path (rf2-3mww7).

  The audit (rf2-spr6q, findings SU2 / SU6) identified a swap-fn
  side-effect race in `subs/dispose-entry-now!`,
  `subs/invalidate-sub-on-replace!`, and the `unsubscribe` 1→0
  transition. Each placed side-effecting operations (collecting
  reactions for disposal, resetting a `dropped-to-zero?` flag,
  cancelling timers) **inside** the swap-fn body. `clojure.core/swap!`
  is allowed to retry on CAS contention; under JVM concurrency a
  retried swap-fn would replay those side-effects, leading to
  double-dispose (and a potential NPE when the second `dispose!`
  closed over a reaction already torn down).

  CLJS is single-threaded so the race is invisible there. These tests
  live in `.clj` (not `.cljc`) and target the JVM only.

  The fix (in subs.cljc): the swap-fn body is pure — it returns only
  the new cache map. Side-effects (disposal, timer cancel) run AFTER
  the CAS commits, computed from the diff between the pre/post
  snapshots returned by `swap-vals!`.

  Each test stresses contention by driving thousands of iterations of
  the contended path; failures accumulate into a counter and the test
  asserts zero. The deterministic single-thread tests in
  `sub_cache_test.clj` continue to pin the happy-path contract; this
  namespace pins the contention contract.

  Pattern follows `router_drain_race_test.clj` /
  `concurrency_stress_test.clj`: per-scenario iteration count
  (env-overridable), fixture as elsewhere, latched start so threads
  race from the same gun."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.subs.cache :as subs-cache]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.registrar :as registrar]
            [re-frame.interop :as interop]
            [re-frame.substrate.plain-atom :as plain-atom])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (try (test-fn)
       (finally
         (subs-cache/configure! {:grace-period-ms 50}))))

(use-fixtures :each reset-runtime)

;; Test instrumentation: a counting `dispose!` proxy.
;;
;; We can't rely on `interop/dispose!`'s own dissoc-then-fire to detect
;; double-`dispose!` calls — the second call finds no callbacks and is
;; a silent no-op. Instead, the test wraps `interop/dispose!` with
;; `with-redefs` to a per-reaction counter that bumps every time the
;; sub-cache calls `dispose!`. A `counter > 1` for any reaction proves
;; the swap-fn-side-effects race fired the dispose path more than once.
;;
;; A reaction-shaped object is just a deref-able; the sub-cache only
;; calls `dispose!` on it (it doesn't deref, it doesn't read structure).
(defn- bare-reaction []
  (reify clojure.lang.IDeref (deref [_] nil)))

;; ---- 1. Concurrent invalidate-sub-on-replace! does not double-dispose -----

;; The race: N threads concurrently fire the replacement hook for the
;; SAME sub id (representing N near-simultaneous re-registrations during
;; hot-reload). Pre-fix, the swap-fn body's `(swap! evictions conj r)`
;; could fire on a retried, discarded CAS attempt; the post-swap
;; `dispose!` loop would then call `dispose!` twice on the same
;; reaction. Post-fix, side-effects are derived from the diff between
;; pre/post swap snapshots — only the CAS winner sees the slot
;; transition from present to absent, so dispose fires exactly once.
;;
;; The replacement-hook fn is private; we drive it through the public
;; `rf/reg-sub` path (re-registration fires the hook).
(deftest invalidate-sub-on-replace-no-double-dispose
  (testing "concurrent hot-reload of the same sub id disposes each cached reaction exactly once"
    (let [dispose-calls   (atom {})         ;; reaction → call count
          orig-dispose!   interop/dispose!
          n-keys          50
          n-replacers     8
          cache           (:sub-cache (frame/frame :rf/default))
          ks              (vec (for [i (range n-keys)] [:contended i]))
          reactions-by-k  (atom {})]
      ;; Register the sub once so subsequent re-registrations fire the
      ;; replacement hook. The body is irrelevant; we never deref.
      (rf/reg-sub :contended (fn [_db _q] nil))

      ;; Manually populate cached entries with bare reactions we control,
      ;; so we can count dispose! calls against each one.
      (doseq [k ks]
        (let [r (bare-reaction)]
          (swap! reactions-by-k assoc k r)
          (swap! dispose-calls assoc r 0)
          (swap! cache assoc k {:reaction r
                                :inputs   []
                                :ref-count 0
                                :on-dispose []
                                :pending-dispose nil})))
      (is (= n-keys (count @cache))
          "all contended slots populated")

      ;; Redef dispose! to count calls per reaction. Each call still
      ;; delegates to the original (so on-dispose-callbacks state is
      ;; maintained). The counter atom is read post-race; any reaction
      ;; with count > 1 is a swap-fn-retry-double-dispose failure.
      (with-redefs [interop/dispose!
                    (fn [r]
                      (swap! dispose-calls update r (fnil inc 0))
                      (orig-dispose! r))]
        (let [latch (CountDownLatch. 1)
              threads (mapv (fn [_]
                              (Thread.
                                ^Runnable
                                (fn []
                                  (.await latch 5 TimeUnit/SECONDS)
                                  ;; Re-register fires the replacement
                                  ;; hook, which walks every frame's
                                  ;; cache and evicts each [:contended _]
                                  ;; slot.
                                  (rf/reg-sub :contended (fn [_db _q] nil)))))
                            (range n-replacers))]
          (doseq [t threads] (.start t))
          (.countDown latch)
          (doseq [t threads] (.join t 10000))))

      (let [counts (mapv #(get @dispose-calls (get @reactions-by-k %)) ks)
            over   (filter #(> % 1) counts)
            under  (filter #(< % 1) counts)]
        (is (empty? over)
            (str "no reaction may have dispose! called more than once across "
                 n-replacers " concurrent hot-reloads; got over-dispose counts: "
                 (vec over)))
        (is (empty? under)
            (str "every cached reaction must have dispose! called at least once; "
                 "got " (count under) " slot(s) never disposed"))))))

;; ---- 2. Concurrent dispose-entry-now! does not double-dispose -------------

;; The race: M threads schedule grace-period disposal for the same set
;; of keys (e.g. unsubscribe storms from React unmount churn). Each
;; deferred timer fires `dispose-entry-now!` for its key. Pre-fix, the
;; swap-fn body's `(reset! reaction-to-dispose ...)` could fire on a
;; retried-and-discarded CAS attempt, after which the post-swap
;; `dispose!` would call dispose on a reaction the WINNING swap had
;; already cleared. Post-fix, we read the reaction-to-dispose from the
;; pre-swap snapshot and only act when the slot transitioned from
;; present to absent — i.e. when our CAS actually won.
(deftest dispose-entry-now-no-double-dispose-under-contention
  (testing "concurrent dispose-entry-now! calls dispose each reaction exactly once"
    (let [n-keys          50
          n-threads-per-k 6   ;; threads per key, ALL trying to dispose the same slot
          cache           (:sub-cache (frame/frame :rf/default))
          dispose-calls   (atom {})
          orig-dispose!   interop/dispose!
          ks              (vec (for [i (range n-keys)] [:contended i]))
          reactions-by-k  (atom {})]
      ;; Seed each slot with ref-count 0 (the precondition for
      ;; dispose-entry-now! to actually evict). One CAS winner per key
      ;; will succeed; n-threads-per-k - 1 losers must observe the slot
      ;; already gone and NOT call dispose!.
      (doseq [k ks]
        (let [r (bare-reaction)]
          (swap! reactions-by-k assoc k r)
          (swap! dispose-calls assoc r 0)
          (swap! cache assoc k {:reaction r
                                :inputs   []
                                :ref-count 0
                                :on-dispose []
                                :pending-dispose nil})))

      ;; The fn lives in re-frame.subs.cache (post rf2-0ytl4 seam S-A).
      (let [dispose-fn subs-cache/dispose-entry-now!]
        (with-redefs [interop/dispose!
                      (fn [r]
                        (swap! dispose-calls update r (fnil inc 0))
                        (orig-dispose! r))]
          (let [latch  (CountDownLatch. 1)
                threads (vec
                          (mapcat
                            (fn [k]
                              (mapv (fn [_]
                                      (Thread.
                                        ^Runnable
                                        (fn []
                                          (.await latch 5 TimeUnit/SECONDS)
                                          (dispose-fn cache k))))
                                    (range n-threads-per-k)))
                            ks))]
            (doseq [t threads] (.start t))
            (.countDown latch)
            (doseq [t threads] (.join t 10000)))))

      (let [counts (mapv #(get @dispose-calls (get @reactions-by-k %)) ks)
            over   (filter #(> % 1) counts)
            under  (filter #(< % 1) counts)]
        (is (empty? over)
            (str "no reaction may have dispose! called more than once under "
                 n-threads-per-k "-thread contention per key; got over-dispose "
                 "counts: " (vec over)))
        (is (empty? under)
            (str "every slot must have dispose! called exactly once across the "
                 "n-thread race; got " (count under) " slot(s) never disposed"))))))

;; ---- 3. unsubscribe drop-to-zero is not spuriously triggered --------------

;; The race: a single subscriber refs a sub, and N threads all call
;; `unsubscribe` concurrently. Exactly one CAS winner drives the 1→0
;; transition and disposes the slot (grace=0 sync path); the losers
;; (who see ref-count already 0) must NOT dispose a second time.
;; Pre-fix, the swap-fn body's `(reset! dropped-to-zero? true)` could
;; fire on a discarded retry attempt, causing a second
;; `dispose-entry-now!` to be invoked against an already-evicted slot
;; — observable as a double dispose! call against the same reaction.
;;
;; This scenario is correctness-equivalent to the existing idempotent-
;; unsubscribe contract pinned in sub_cache_test.clj, but here we
;; assert it under CAS contention rather than serialised calls.
(deftest unsubscribe-drop-to-zero-no-spurious-fire-under-contention
  (testing "concurrent unsubscribe calls dispose exactly once per slot under contention"
    (subs-cache/configure! {:grace-period-ms 0})  ;; sync dispose so we can observe
    (rf/reg-event-db :seed (fn [_ _] {:n 7}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])

    (let [n-trials      200
          n-threads     6
          orig-dispose! interop/dispose!
          per-trial     (atom [])]  ;; vec of dispose-call-count per trial
      ;; Per-trial counter, fresh each iteration. Each trial:
      ;;   - subscribe once → ref-count 1
      ;;   - n-threads race to unsubscribe
      ;;   - exactly one dispose! call must result
      (dotimes [_ n-trials]
        (let [trial-counter (atom 0)
              dispose-proxy (fn [r]
                              (swap! trial-counter inc)
                              (orig-dispose! r))]
          (with-redefs [interop/dispose! dispose-proxy]
            (rf/subscribe [:n])  ;; ref-count 1
            (let [latch (CountDownLatch. 1)
                  threads (mapv (fn [_]
                                  (Thread.
                                    ^Runnable
                                    (fn []
                                      (.await latch 5 TimeUnit/SECONDS)
                                      (rf/unsubscribe [:n]))))
                                (range n-threads))]
              (doseq [t threads] (.start t))
              (.countDown latch)
              (doseq [t threads] (.join t 5000))))
          (swap! per-trial conj @trial-counter)))

      (let [counts @per-trial
            over   (filter #(> % 1) counts)
            under  (filter #(< % 1) counts)]
        (is (empty? over)
            (str "no trial may dispose the cached reaction more than once "
                 "across " n-threads " concurrent unsubscribes; got "
                 (count over) " over-dispose trials. Sample counts: "
                 (vec (take 20 over))))
        (is (empty? under)
            (str "every trial must dispose the cached reaction exactly once "
                 "(grace=0); got " (count under) " trial(s) with zero "
                 "disposes. Sample: " (vec (take 20 under)))))
      (is (= n-trials (count @per-trial))
          "all trials accounted for"))))
