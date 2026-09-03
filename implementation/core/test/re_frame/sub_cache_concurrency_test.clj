(ns re-frame.sub-cache-concurrency-test
  "JVM-only concurrency tests for the sub-cache disposal path (rf2-3mww7).

  The audit (rf2-spr6q, findings SU2 / SU6) identified a swap-fn
  side-effect race in `rf.subs/dispose-entry-now!`,
  `rf.subs/invalidate-sub-on-replace!`, and the `unsubscribe` 1→0
  transition. Each placed side-effecting operations (collecting
  reactions for disposal, resetting a `dropped-to-zero?` flag) **inside**
  the swap-fn body. `clojure.core/swap!` is allowed to retry on CAS
  contention; under JVM concurrency a retried swap-fn would replay those
  side-effects, leading to double-dispose (and a potential NPE when the
  second `dispose!` closed over a reaction already torn down).

  CLJS is single-threaded so the race is invisible there. These tests
  live in `.clj` (not `.cljc`) and target the JVM only.

  The fix (in subs.cache.cljc): the swap-fn body is pure — it returns
  only the new cache map. Side-effects (disposal) run AFTER the CAS
  commits, computed from the diff between the pre/post snapshots
  returned by `swap-vals!`.

  Each test stresses contention by driving thousands of iterations of
  the contended path; failures accumulate into a counter and the test
  asserts zero. The deterministic single-thread tests in
  `sub_cache_test.clj` continue to pin the happy-path contract; this
  namespace pins the contention contract.

  Per rf2-cmfln: the deferred-grace mechanism has been retired (sync
  dispose only). Tests that exercised the grace-timer-cancel race no
  longer have a code path to drive; the remaining contention tests
  (CAS race on `invalidate-sub-on-replace!`, `dispose-entry-now!`, and
  `unsubscribe` 1 → 0) cover the surface that still exists.

  Pattern follows `router_drain_race_test.clj` /
  `concurrency_stress_test.clj`: per-scenario iteration count
  (env-overridable), fixture as elsewhere, latched start so threads
  race from the same gun."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.subs :as rf.subs]
            [re-frame.subs.cache :as rf.subs.cache]
            [re-frame.frame :as rf.frame]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.registrar :as rf.registrar]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; EP-0002 (rf2-jue6sp): `init!` no longer synthesises `:rf/default`;
  ;; register it explicitly so these single-app-frame cache tests have a
  ;; conventional frame to read. NB the cross-thread subscribe/unsubscribe
  ;; calls below pass `:rf/default` EXPLICITLY (2-arity) — a `with-frame`
  ;; dynamic binding does not convey into the worker threads, so the
  ;; carried-invariant stamp must be passed as a value across the boundary.
  (rf.frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; Test instrumentation: a counting `dispose!` proxy.
;;
;; We can't rely on `rf.interop/dispose!`'s own dissoc-then-fire to detect
;; double-`dispose!` calls — the second call finds no callbacks and is
;; a silent no-op. Instead, the test wraps `rf.interop/dispose!` with
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
          orig-dispose!   rf.interop/dispose!
          n-keys          50
          n-replacers     8
          cache           (:sub-cache (rf.frame/frame :rf/default))
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
          (swap! cache assoc k {:reaction   r
                                :inputs     []
                                :ref-count  0})))
      (is (= n-keys (count @cache))
          "all contended slots populated")

      ;; Redef dispose! to count calls per reaction. Each call still
      ;; delegates to the original (so on-dispose-callbacks state is
      ;; maintained). The counter atom is read post-race; any reaction
      ;; with count > 1 is a swap-fn-retry-double-dispose failure.
      (with-redefs [rf.interop/dispose!
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

;; The race: M threads invoke `dispose-entry-now!` for the same set of
;; keys. Each call attempts to evict and dispose. Pre-fix, the swap-fn
;; body's `(reset! reaction-to-dispose ...)` could fire on a retried-and-
;; discarded CAS attempt, after which the post-swap `dispose!` would
;; call dispose on a reaction the WINNING swap had already cleared.
;; Post-fix, we read the reaction-to-dispose from the pre-swap snapshot
;; and only act when the slot transitioned from present to absent — i.e.
;; when our CAS actually won.
(deftest dispose-entry-now-no-double-dispose-under-contention
  (testing "concurrent dispose-entry-now! calls dispose each reaction exactly once"
    (let [n-keys          50
          n-threads-per-k 6   ;; threads per key, ALL trying to dispose the same slot
          cache           (:sub-cache (rf.frame/frame :rf/default))
          dispose-calls   (atom {})
          orig-dispose!   rf.interop/dispose!
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
          (swap! cache assoc k {:reaction   r
                                :inputs     []
                                :ref-count  0})))

      ;; The fn lives in re-frame.subs.cache (post rf2-0ytl4 seam S-A).
      (let [dispose-fn rf.subs.cache/dispose-entry-now!]
        (with-redefs [rf.interop/dispose!
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
;; `unsubscribe` concurrently. Exactly one CAS winner drives the 1 → 0
;; transition and disposes the slot (sync path); the losers (who see
;; ref-count already 0) must NOT dispose a second time. Pre-fix, the
;; swap-fn body's `(reset! dropped-to-zero? true)` could fire on a
;; discarded retry attempt, causing a second `dispose-entry-now!` to be
;; invoked against an already-evicted slot — observable as a double
;; dispose! call against the same reaction.
;;
;; This scenario is correctness-equivalent to the existing idempotent-
;; unsubscribe contract pinned in sub_cache_test.clj, but here we
;; assert it under CAS contention rather than serialised calls.
(deftest unsubscribe-drop-to-zero-no-spurious-fire-under-contention
  (testing "concurrent unsubscribe calls dispose exactly once per slot under contention"
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed] {:frame :rf/default})

    (let [n-trials      200
          n-threads     6
          orig-dispose! rf.interop/dispose!
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
          (with-redefs [rf.interop/dispose! dispose-proxy]
            ;; Explicit `:rf/default` (2-arity) — the dynamic-var scope
            ;; does not convey into the worker threads, so the frame stamp
            ;; rides as a value across the boundary (rf2-jue6sp / EP-0002).
            (rf/subscribe [:n] {:frame :rf/default})  ;; ref-count 1
            (let [latch (CountDownLatch. 1)
                  threads (mapv (fn [_]
                                  (Thread.
                                    ^Runnable
                                    (fn []
                                      (.await latch 5 TimeUnit/SECONDS)
                                      (rf/unsubscribe :rf/default [:n]))))
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
            (str "every trial must dispose the cached reaction exactly once; "
                 "got " (count under) " trial(s) with zero "
                 "disposes. Sample: " (vec (take 20 under)))))
      (is (= n-trials (count @per-trial))
          "all trials accounted for"))))

;; ---- 4. Concurrent cache-MISS install resolves to ONE reaction ------------

;; Per rf2-x76af2.23. The HIT path was already CAS-after-snapshot hardened;
;; the MISS path installed with an unconditional plain `(swap! cache assoc
;; k …)`. Two threads that both observe a miss for the SAME query-v both call
;; `compute-and-cache!`, both build a reaction, and the second assoc STOMPS the
;; first: reaction1 is orphaned (its on-dispose never fires → its layer-2 input
;; ref-count bumps leak), and `:ref-count` is reset to 1 while TWO callers hold
;; references — so a phantom holder's unsubscribe drives the CACHED reaction
;; 1→0 and disposes it while the other caller still uses it.
;;
;; The defect IS the unconditional overwrite, so two direct `compute-and-cache!`
;; calls model two racing misses deterministically (interleaving-injection, not
;; wall-clock): each call unconditionally rebuilds+installs pre-fix, so the
;; second call reproduces the exact double-build the CAS-less install could not
;; prevent. Post-fix, install-if-absent makes the second call adopt the winner.
(deftest concurrent-miss-install-resolves-to-one-reaction
  (testing "two racing cache-miss builds for the same query-v resolve to ONE cached reaction"
    (rf/reg-event :seed (fn [_ _] {:db {:a 1 :b 2}}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    ;; layer-2 sub over two layer-1 inputs, so we can observe input ref-counts.
    (rf/reg-sub :sum :<- [:a] :<- [:b] (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:seed] {:frame :rf/default})

    (let [frame-id :rf/default
          cache    (:sub-cache (rf.frame/frame frame-id))
          cc!      #'rf.subs/compute-and-cache!   ;; the private build path (cache-key = identity)
          ;; Two racing misses = two direct build calls.
          r1       (cc! frame-id [:sum])
          r2       (cc! frame-id [:sum])]

      (testing "exactly ONE reaction is cached and both builds resolve to it"
        (is (identical? r1 r2)
            "the loser adopts the winner — both builds resolve to the SAME reaction")
        (is (identical? r1 (get-in @cache [[:sum] :reaction]))
            "the cached slot holds the shared reaction (no stomp)"))

      (testing ":ref-count == subscriber count (2), not reset to 1"
        (is (= 2 (get-in @cache [[:sum] :ref-count]))
            "both callers are counted (pre-fix the second overwrite reset it to 1)"))

      (testing "no input-ref leak: each layer-1 input held once by the ONE cached reaction"
        ;; The loser's build bumped :a / :b then released them on dispose, so
        ;; each input is held only by the single cached :sum reaction.
        (is (= 1 (get-in @cache [[:a] :ref-count])) ":a input ref-count is 1, not leaked to 2")
        (is (= 1 (get-in @cache [[:b] :ref-count])) ":b input ref-count is 1, not leaked to 2"))

      (testing "after N unsubscribes the reaction disposes and all input refs release to zero"
        ;; ref-count 2 → two unsubscribes → 0 → sync dispose → on-dispose
        ;; releases the inputs symmetrically.
        (rf/unsubscribe frame-id [:sum])
        (is (some? (get @cache [:sum])) "one unsubscribe leaves the reaction live (ref-count 1)")
        (rf/unsubscribe frame-id [:sum])
        (is (nil? (get @cache [:sum])) ":sum evicted at ref-count 0")
        (is (nil? (get @cache [:a])) ":a input released to 0 and evicted — no orphan")
        (is (nil? (get @cache [:b])) ":b input released to 0 and evicted — no orphan")))))
