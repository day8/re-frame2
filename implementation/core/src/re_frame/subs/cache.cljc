(ns re-frame.subs.cache
  "Sub-cache state, ref-counting, grace-period disposal, hot-reload
  invalidation, and the test-fixture cache clear. Extracted from
  `re-frame.subs` per rf2-0ytl4 Phase-2 seam S-A (fold-in of seam S-E
  for the registrar-replacement hook).

  Per Spec 006 §Subscription cache and §Reference counting and disposal.
  This ns owns the per-frame `:sub-cache` shape:

    {<cache-key> {:value v :reaction r :inputs [...] :ref-count n
                  :on-dispose [...]
                  :pending-dispose <timer-handle-or-nil>}}

  Disposal is **deferred ref-counting with a grace-period** (rf2-s9dn,
  per Spec 006 §Reference counting and disposal). When the last
  subscriber drops, the cache entry is scheduled for disposal after the
  configured grace-period (default 50ms — see `grace-period-ms` /
  `configure!`). If a new subscriber arrives within that window,
  disposal is cancelled and the cached value is reused.

  The two `swap-vals!`-after-CAS patterns (in `dispose-entry-now!`,
  `unsubscribe!`, and `invalidate-sub-on-replace!`) all encode the same
  concurrency-safety property: any side-effect (`interop/dispose!`,
  `interop/clear-timeout!`) reads from the PRE-swap snapshot and runs
  AFTER the CAS commits. `swap!` is allowed to retry on JVM contention,
  so a side-effecting body could fire 2+ times under concurrent
  invalidate + grace-fire.

  `cache-key` STAYS on the `re-frame.subs` facade ns — it's a one-liner
  on the per-subscribe hit path and Closure inlines it across nss only
  if it stays trivial. Keeping the constant chokepoint co-located with
  `subscribe` preserves the hot-path lookup."
  (:require [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.registrar :as registrar]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- grace-period configuration -------------------------------------------
;;
;; Per Spec 006 §Reference counting and disposal. When the last subscriber
;; detaches, we don't dispose immediately — we wait grace-period-ms in case
;; a new subscriber arrives (e.g. across a React re-render). The default
;; is short enough not to leak under genuine disposal but long enough to
;; bridge typical React render churn. Tests that want to assert on disposal
;; configure a short or zero value via configure!.

(def ^:private default-grace-period-ms
  ;; Long enough to bridge typical React render churn; short enough not
  ;; to leak under genuine disposal.
  50)

(defonce ^:private config
  ;; Map shape so future :sub-cache configure-keys land additively.
  (atom {:grace-period-ms default-grace-period-ms}))

(defn configure!
  "Update the sub-cache configuration. Currently supports
  `{:grace-period-ms N}` — a non-negative integer (or 0 to dispose
  synchronously when ref-count drops to zero). Per Spec 006."
  [opts]
  (when (map? opts)
    (swap! config merge (select-keys opts [:grace-period-ms])))
  nil)

(defn current-config
  "Return the current sub-cache configuration map. Public for tests
  and tools that want to display the current grace-period."
  []
  @config)

(defn- grace-period-ms
  []
  (or (:grace-period-ms @config) 0))

;; ---- disposal ------------------------------------------------------------

(defn dispose-entry-now!
  "Synchronous disposal: remove the cache slot for k iff its ref-count
  is still <= 0 (no resubscribe arrived) and dispose the reaction.
  Idempotent — a second call is a no-op because the slot is gone.

  The swap-fn body is pure — it returns the new cache map and nothing
  else; the reaction to dispose is read from the PRE-swap snapshot
  returned by `swap-vals!` and acted on AFTER the CAS commits. `swap!`
  is allowed to retry on contention on the JVM, so any side-effect
  (`interop/dispose!`) inside the swap-fn could fire 2+ times under
  concurrent invalidate + grace-fire."
  [cache k]
  (let [[old new] (swap-vals! cache
                              (fn [m]
                                (if-let [entry (get m k)]
                                  (if (<= (or (:ref-count entry) 0) 0)
                                    (dissoc m k)
                                    ;; Resubscribe arrived between schedule
                                    ;; and fire — keep entry.
                                    m)
                                  m)))]
    ;; The slot was evicted by THIS call iff it was present in `old` and
    ;; absent in `new`. A concurrent evictor (e.g. invalidate-sub-on-
    ;; replace! or clear-sub-cache!) that won the CAS race would
    ;; have left the slot absent in `old` too, so we don't double-dispose.
    (when (and (contains? old k) (not (contains? new k)))
      (when-let [r (get-in old [k :reaction])]
        (try (interop/dispose! r)
             (catch #?(:clj Throwable :cljs :default) _ nil))))
    nil))

(defn unsubscribe!
  "Decrement the ref-count on the cached subscription for `k`. When
  ref-count reaches 0, schedule the entry for disposal after the
  configured grace-period (default 50ms; see `configure!`). If a new
  subscriber arrives within the window, disposal is cancelled and the
  cached value is reused. Per Spec 006 §Reference counting and disposal.

  When grace-period is 0, disposal is synchronous — useful for tests.

  `opts` accepts:

      {:grace N}   — override the configured grace-period for THIS
                     call only. `{:grace 0}` forces synchronous
                     disposal on the 1→0 transition. When `:grace` is
                     absent, the configured per-runtime grace-period
                     is used.

  Called from the public `re-frame.subs/unsubscribe` after `cache-key`
  + `cache` resolution; the facade fn holds the public API shape."
  [cache k opts]
  (let [;; An explicit `:grace` in opts overrides the per-runtime
        ;; configured grace-period. `contains?` (not `(:grace opts)`)
        ;; so `{:grace 0}` is honoured.
        grace (if (and (map? opts) (contains? opts :grace))
                (:grace opts)
                (grace-period-ms))
        ;; The swap-fn body is pure — it returns only the new cache
        ;; map. The drop-to-zero signal is read from the diff between
        ;; `old` and `new` AFTER the CAS commits. `swap!` is allowed
        ;; to retry on JVM contention, so a side-effecting
        ;; `(reset! dropped-to-zero? true)` inside the swap-fn body
        ;; could fire on a discarded retry whose CAS lost — leading
        ;; to a spurious dispose schedule.
        [old new] (swap-vals! cache
                              (fn [m]
                                (if-let [entry (get m k)]
                                  (let [old-n (or (:ref-count entry) 1)
                                        n     (max 0 (dec old-n))]
                                    ;; Only trigger drop-to-zero on the 1→0
                                    ;; transition AND only when no grace-period
                                    ;; timer is already in flight. Calling
                                    ;; `unsubscribe` past zero (idempotent
                                    ;; misuse — e.g. cleanup in both a
                                    ;; teardown hook and a `finally`) must not
                                    ;; stack new `pending-dispose` timers on
                                    ;; top of the prior handle.
                                    (if (and (= 1 old-n)
                                             (zero? n)
                                             (nil? (:pending-dispose entry)))
                                      (assoc m k (assoc entry :ref-count 0))
                                      (assoc-in m [k :ref-count] n)))
                                  m)))
        ;; This swap drove the 1→0 transition (under no pending-dispose)
        ;; iff the entry was present in both old and new AND old's
        ;; ref-count was 1 AND new's ref-count is 0 AND old had no
        ;; pending-dispose timer. Reading from the snapshots avoids
        ;; the side-effect-in-swap-fn race.
        dropped-to-zero? (and (contains? new k)
                              (= 1 (or (get-in old [k :ref-count]) 1))
                              (zero? (or (get-in new [k :ref-count]) 0))
                              (nil? (get-in old [k :pending-dispose])))]
    (when dropped-to-zero?
      (if (zero? grace)
        ;; Grace = 0: dispose synchronously (the test/explicit-tear-down path).
        (dispose-entry-now! cache k)
        ;; Grace > 0: schedule deferred disposal. Stash the timer handle
        ;; so a re-subscribe inside the window can cancel it.
        (let [handle (interop/set-timeout!
                       (fn []
                         (dispose-entry-now! cache k))
                       grace)
              ;; Pure swap-fn: return the new map and a flag indicating
              ;; whether the handle was actually stashed. The clear-
              ;; timeout! side-effect runs AFTER the CAS commits so
              ;; a discarded retry can't double-clear a live timer.
              [_ new2] (swap-vals! cache
                                   (fn [m]
                                     (if-let [entry (get m k)]
                                       (if (<= (or (:ref-count entry) 0) 0)
                                         (assoc m k (assoc entry :pending-dispose handle))
                                         ;; Subscriber arrived between our swap!
                                         ;; above and set-timeout! returning —
                                         ;; do NOT stash; we'll cancel post-swap.
                                         m)
                                       m)))]
          ;; If the handle did NOT land on the entry, cancel it once,
          ;; outside the swap. Reading from the post-swap snapshot
          ;; (`new2`) means we make this decision exactly once.
          (when-not (identical? handle (get-in new2 [k :pending-dispose]))
            (try (interop/clear-timeout! handle)
                 (catch #?(:clj Throwable :cljs :default) _ nil))))))
    nil))

;; ---- hot-reload invalidation ---------------------------------------------
;;
;; Per Spec 001 §Hot-reload semantics: when a :sub re-registers, every
;; cached reaction whose query-id is that sub MUST be disposed and
;; evicted across every frame's cache. Cached reactions hold the OLD
;; body via closure; without explicit invalidation, they'd silently
;; serve stale values.

(defn- invalidate-sub-on-replace!
  [{:keys [kind id]}]
  (when (= kind :sub)
    (doseq [frame-id (frame/frame-ids)]
      (when-let [cache (:sub-cache (frame/frame frame-id))]
        ;; The swap-fn body is pure — it returns only the new cache map.
        ;; Reactions to dispose and timers to cancel are read from the
        ;; diff between `old` and `new` AFTER the CAS commits (so a
        ;; retried `swap!` can't fire dispose 2+ times).
        (let [[old new] (swap-vals! cache
                                    (fn [m]
                                      (let [hit-keys (->> (keys m)
                                                          (filter #(= id (first %))))]
                                        (apply dissoc m hit-keys))))
              ;; The keys actually evicted by THIS swap are those present
              ;; in `old` but absent in `new`. A concurrent evictor that
              ;; won the CAS race would have removed its keys before our
              ;; swap saw them, so the diff names ONLY the keys we own.
              evicted-keys (filterv #(not (contains? new %))
                                    (keys old))]
          ;; Cancel any pending grace-period timers for the evicted slots —
          ;; the reaction is being disposed now, so the deferred path
          ;; would fire against a stale closure.
          (doseq [k evicted-keys]
            (when-let [h (get-in old [k :pending-dispose])]
              (try (interop/clear-timeout! h)
                   (catch #?(:clj Throwable :cljs :default) _ nil))))
          (doseq [k evicted-keys]
            (when-let [r (get-in old [k :reaction])]
              (try (interop/dispose! r)
                   (catch #?(:clj Throwable :cljs :default) _ nil)))))))))

(defonce ^:private _hot-reload-hook
  (do (registrar/add-replacement-hook! invalidate-sub-on-replace!)
      :installed))

(defn clear-sub-cache!
  "Dispose every cached entry in a frame's runtime sub-cache and clear
  the cache. Cancels any pending grace-period timers before disposing —
  a deferred disposal landing after this fn returned would close over
  a stale reaction.

  Test fixtures and REPL-driven reloads call this between scenarios
  to ensure the cache is empty before re-subscribing. Test code
  generally prefers `reset-runtime-fixture` (per `test_support`) which
  bundles cache-clearing with registrar / frame state reset.

  Zero-arity targets `:rf/default`; one-arity targets the named frame.
  Returns nil. See also: `re-frame.subs/clear-sub` (registrar-side
  counterpart)."
  ([] (clear-sub-cache! :rf/default))
  ([frame-id]
   (when-let [cache (:sub-cache (frame/frame frame-id))]
     (doseq [[_k entry] @cache]
       (when-let [h (:pending-dispose entry)]
         (try (interop/clear-timeout! h)
              (catch #?(:clj Throwable :cljs :default) _ nil)))
       (when-let [r (:reaction entry)]
         (try (interop/dispose! r)
              (catch #?(:clj Throwable :cljs :default) _ nil))))
     (reset! cache {}))))
