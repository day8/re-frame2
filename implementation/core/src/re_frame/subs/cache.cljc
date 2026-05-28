(ns re-frame.subs.cache
  "Sub-cache state, ref-counting, synchronous disposal, hot-reload
  invalidation, and the test-fixture cache clear. Extracted from
  `re-frame.subs` per rf2-0ytl4 Phase-2 seam S-A (fold-in of seam S-E
  for the registrar-replacement hook).

  Per Spec 006 §Subscription cache and §Reference counting and disposal.
  This ns owns the per-frame `:sub-cache` shape:

    {<cache-key> {:value v :reaction r :inputs [...] :ref-count n
                  :on-dispose [...]}}

  Disposal is **synchronous on derefer-count → 0** (rf2-cmfln, per
  Spec 006 §Reference counting and disposal). When the last subscriber
  drops (`unsubscribe!` drives the 1 → 0 transition), the cache entry is
  evicted IN-TICK — the reaction is disposed, the on-dispose callback
  releases input refs (cascading down a layer-2+ chain), and the slot is
  dissoc'd from the cache. No deferred-grace timer, no batched dispose:
  a recompute landing AFTER the last derefer has dropped is a wasted
  cycle, and the elegant fix is to never schedule it.

  The shared-component-thrash scenario (a component unmounts and the
  same subscription remounts in the same tick) re-builds the slot on the
  new mount; this is acceptable per the rf2-cmfln design — the
  recomputed value is `=` to the disposed one, so the post-mount render
  observes no value change, and the cache-miss path's cost is dominated
  by `compute-and-cache!`'s reaction construction (one allocation, no
  perf-hot work).

  The `swap-vals!`-after-CAS patterns (in `dispose-entry-now!`,
  `unsubscribe!`, and `invalidate-sub-on-replace!`) all encode the same
  concurrency-safety property: any side-effect (`interop/dispose!`)
  reads from the PRE-swap snapshot and runs AFTER the CAS commits.
  `swap!` is allowed to retry on JVM contention, so a side-effecting
  body could fire 2+ times under concurrent invalidate + sync-dispose.

  `cache-key` STAYS on the `re-frame.subs` facade ns — it's a one-liner
  on the per-subscribe hit path and Closure inlines it across nss only
  if it stays trivial. Keeping the constant chokepoint co-located with
  `subscribe` preserves the hot-path lookup.

  Per rf2-mrnur (Spec 009 §:op-type vocabulary §`:rf.sub/dispose`): every
  eviction site emits a `:rf.sub/dispose` trace event so consumers can
  observe the sub-cache lifecycle's terminal half — created / run / skip
  / **dispose**. The reason axis discriminates the eviction path:
  `:no-more-derefers` (synchronous fire on 1 → 0), `:hot-reload`
  (re-registration evicted), `:cache-clear` (explicit test/REPL
  teardown). Cache-key shape is the query-vector itself
  (`re-frame.subs/cache-key` is identity), so the emit derives
  `:rf.sub/id` and `:rf.sub/query-v` directly from `k`. The emits ride
  `interop/debug-enabled?` so production CLJS bundles DCE them with the
  rest of the trace surface."
  (:require [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.registrar :as registrar]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- dispose trace emit (rf2-mrnur) ---------------------------------------
;;
;; Per Spec 009 §:op-type vocabulary §`:rf.sub/dispose` — every cache
;; eviction site funnels through this helper so the emit tag-shape is
;; single-sourced. `k` is the cache-key (currently the query-vector
;; itself, per `re-frame.subs/cache-key`); `query-id` is `(first k)`.
;; The whole call sits behind `interop/debug-enabled?` so production
;; CLJS bundles DCE the tag-map allocation + the emit call along with
;; the rest of the trace surface.

(defn- emit-dispose!
  [frame-id k reason]
  (when interop/debug-enabled?
    (trace/emit! :rf.sub :rf.sub/dispose
                 {:frame          frame-id
                  :rf.sub/id      (first k)
                  :rf.sub/query-v k
                  :rf.sub/reason  reason})))

;; ---- disposal ------------------------------------------------------------

(defn dispose-entry-now!
  "Synchronous disposal: remove the cache slot for k iff its ref-count
  is still <= 0 and dispose the reaction. Idempotent — a second call is
  a no-op because the slot is gone.

  The swap-fn body is pure — it returns the new cache map and nothing
  else; the reaction to dispose is read from the PRE-swap snapshot
  returned by `swap-vals!` and acted on AFTER the CAS commits. `swap!`
  is allowed to retry on contention on the JVM, so any side-effect
  (`interop/dispose!`) inside the swap-fn could fire 2+ times under
  concurrent invalidate + dispose race.

  Per rf2-mrnur: emits `:rf.sub/dispose` with `:rf.sub/reason
  :no-more-derefers` after the CAS commits, for the call that actually
  drove the eviction (read off the `old` / `new` snapshot diff — the
  same single-fire discipline that gates `interop/dispose!`). `frame-id`
  rides on the emit's `:frame` tag; the 2-arity form is preserved for
  legacy call sites that don't carry a frame-id (the emit fires with
  `:frame nil` and tools fall back to `:rf.sub/id` for grouping)."
  ([cache k] (dispose-entry-now! cache k nil))
  ([cache k frame-id]
   (let [[old new] (swap-vals! cache
                               (fn [m]
                                 (if-let [entry (get m k)]
                                   (if (<= (or (:ref-count entry) 0) 0)
                                     (dissoc m k)
                                     m)
                                   m)))]
     ;; The slot was evicted by THIS call iff it was present in `old` and
     ;; absent in `new`. A concurrent evictor (e.g. invalidate-sub-on-
     ;; replace! or clear-sub-cache!) that won the CAS race would
     ;; have left the slot absent in `old` too, so we don't double-dispose.
     (when (and (contains? old k) (not (contains? new k)))
       ;; rf2-mrnur — emit the dispose trace before tearing down the
       ;; reaction. Single-fire (gated on the same CAS-winner check as
       ;; `interop/dispose!`) so we never double-emit under contention.
       (emit-dispose! frame-id k :no-more-derefers)
       (when-let [r (get-in old [k :reaction])]
         (try (interop/dispose! r)
              (catch #?(:clj Throwable :cljs :default) _ nil))))
     nil)))

(defn unsubscribe!
  "Decrement the ref-count on the cached subscription for `k`. When
  ref-count reaches 0, dispose the entry **synchronously** — evict the
  cache slot, run the reaction's on-dispose callback (which releases
  input refs symmetrically), and emit `:rf.sub/dispose` with reason
  `:no-more-derefers`. Per Spec 006 §Reference counting and disposal
  (rf2-cmfln).

  No grace-period: the 1 → 0 transition disposes in-tick. A resubscribe
  arriving AFTER `unsubscribe!` returns is treated as a fresh cache miss
  (`compute-and-cache!` builds a new reaction). For the React-render-
  churn case where a component briefly unmounts then remounts with the
  same subscription, the recomputed value is `=` to the disposed one
  so the new render observes no value change.

  Called from the public `re-frame.subs/unsubscribe` after `cache-key`
  + `cache` resolution; the facade fn holds the public API shape.

  Per rf2-mrnur: `frame-id` is threaded through to `dispose-entry-now!`
  so the `:rf.sub/dispose` trace emit at the actual eviction site
  carries the right `:frame` tag. The 2-arity form is preserved for
  legacy callers that don't carry a frame-id; the emit falls back to
  `:frame nil` on that path."
  ([cache k] (unsubscribe! cache k nil))
  ([cache k frame-id]
   (let [;; The swap-fn body is pure — it returns only the new cache
         ;; map. The drop-to-zero signal is read from the diff between
         ;; `old` and `new` AFTER the CAS commits. `swap!` is allowed
         ;; to retry on JVM contention, so a side-effecting
         ;; `(reset! dropped-to-zero? true)` inside the swap-fn body
         ;; could fire on a discarded retry whose CAS lost — leading
         ;; to a spurious dispose.
         [old new] (swap-vals! cache
                               (fn [m]
                                 (if-let [entry (get m k)]
                                   (let [old-n (or (:ref-count entry) 1)
                                         n     (max 0 (dec old-n))]
                                     (assoc-in m [k :ref-count] n))
                                   m)))
         ;; This swap drove the 1 → 0 transition iff the entry was
         ;; present in both old and new AND old's ref-count was 1 AND
         ;; new's ref-count is 0. Reading from the snapshots avoids the
         ;; side-effect-in-swap-fn race.
         dropped-to-zero? (and (contains? new k)
                               (= 1 (or (get-in old [k :ref-count]) 1))
                               (zero? (or (get-in new [k :ref-count]) 0)))]
     (when dropped-to-zero?
       (dispose-entry-now! cache k frame-id))
     nil)))

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
        ;; Reactions to dispose are read from the diff between `old` and
        ;; `new` AFTER the CAS commits (so a retried `swap!` can't fire
        ;; dispose 2+ times).
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
          ;; rf2-mrnur — emit dispose per evicted key BEFORE running the
          ;; per-reaction `interop/dispose!` teardown. The reason
          ;; `:hot-reload` discriminates this path from sync 1 → 0 fires
          ;; (`:no-more-derefers`) and explicit `clear-sub-cache!`
          ;; (`:cache-clear`).
          (doseq [k evicted-keys]
            (emit-dispose! frame-id k :hot-reload))
          (doseq [k evicted-keys]
            (when-let [r (get-in old [k :reaction])]
              (try (interop/dispose! r)
                   (catch #?(:clj Throwable :cljs :default) _ nil)))))))))

(defonce ^:private _hot-reload-hook
  (do (registrar/add-replacement-hook! invalidate-sub-on-replace!)
      :installed))

(defn clear-sub-cache!
  "Dispose every cached entry in a frame's runtime sub-cache and clear
  the cache.

  Test fixtures and REPL-driven reloads call this between scenarios
  to ensure the cache is empty before re-subscribing. Test code
  generally prefers `make-reset-runtime-fixture` (per `test_support`) which
  bundles cache-clearing with registrar / frame state reset.

  Zero-arity targets `:rf/default`; one-arity targets the named frame.
  Returns nil. See also: `re-frame.subs/clear-sub` (registrar-side
  counterpart)."
  ([] (clear-sub-cache! :rf/default))
  ([frame-id]
   (when-let [cache (:sub-cache (frame/frame frame-id))]
     (doseq [[k entry] @cache]
       ;; rf2-mrnur — emit dispose per evicted key BEFORE the per-
       ;; reaction `interop/dispose!`. Reason `:cache-clear`
       ;; discriminates the explicit-teardown path from sync 1 → 0
       ;; fires (`:no-more-derefers`) and hot-reload re-registration
       ;; (`:hot-reload`).
       (emit-dispose! frame-id k :cache-clear)
       (when-let [r (:reaction entry)]
         (try (interop/dispose! r)
              (catch #?(:clj Throwable :cljs :default) _ nil))))
     (reset! cache {}))))
