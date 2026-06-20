(ns re-frame.subs.cache
  "Sub-cache state, ref-counting, synchronous disposal, hot-reload
  invalidation, and the test-fixture cache clear.

  Per Spec 006 §Subscription cache and §Reference counting and disposal.
  This ns owns the per-frame `:sub-cache` shape:

    {<cache-key> {:reaction r :inputs [...] :ref-count n}}

  The cached value is NOT a stored slot — it lives on the reaction, read
  via deref. Disposal is wired on the reaction (interop/add-on-dispose!),
  not an entry-level callback slot.

  Disposal is **synchronous on derefer-count → 0** (per
  Spec 006 §Reference counting and disposal). When the last subscriber
  drops (`unsubscribe!` drives the 1 → 0 transition), the cache entry is
  evicted IN-TICK — the reaction is disposed, the on-dispose callback
  releases input refs (cascading down a layer-2+ chain), and the slot is
  dissoc'd from the cache. No deferred-grace timer, no batched dispose:
  a recompute landing AFTER the last derefer has dropped is a wasted
  cycle, and the elegant fix is to never schedule it.

  The shared-component-thrash scenario (a component unmounts and the
  same subscription remounts in the same tick) re-builds the slot on the
  new mount; this is acceptable by design — the
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

  Per Spec 009 §:op-type vocabulary §`:rf.sub/dispose`: every
  eviction site emits a `:rf.sub/dispose` trace event so consumers can
  observe the sub-cache lifecycle's terminal half — created / run / skip
  / **dispose**. The reason axis discriminates the eviction path:
  `:no-more-derefers` (synchronous fire on 1 → 0), `:hot-reload`
  (re-registration evicted), `:cache-clear` (explicit test/REPL
  teardown), `:frame-destroy` (the frame's cache was torn down by
  `destroy-frame!` — routed in via the
  `:subs.cache/dispose-all-for-frame-destroy!` late-bind hook so
  `frame.cljc` carries no static dep on this ns).
  Cache-key shape is the query-vector itself
  (`re-frame.subs/cache-key` is identity), so the emit derives
  `:rf.sub/id` and `:rf.sub/query-v` directly from `k`. The emits ride
  `interop/debug-enabled?` so production CLJS bundles DCE them with the
  rest of the trace surface."
  (:require [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- dispose trace emit ---------------------------------------------------
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

  Emits `:rf.sub/dispose` with `:rf.sub/reason
  :no-more-derefers` after the CAS commits, for the call that actually
  drove the eviction (read off the `old` / `new` snapshot diff — the
  same single-fire discipline that gates `interop/dispose!`). `frame-id`
  rides on the emit's `:frame` tag; the 2-arity form serves call sites
  that don't carry a frame-id (the emit fires with `:frame nil` and
  tools fall back to `:rf.sub/id` for grouping)."
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
       ;; Emit the dispose trace before tearing down the
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
  `:no-more-derefers`. Per Spec 006 §Reference counting and disposal.

  No grace-period: the 1 → 0 transition disposes in-tick. A resubscribe
  arriving AFTER `unsubscribe!` returns is treated as a fresh cache miss
  (`compute-and-cache!` builds a new reaction). For the React-render-
  churn case where a component briefly unmounts then remounts with the
  same subscription, the recomputed value is `=` to the disposed one
  so the new render observes no value change.

  Called from the public `re-frame.subs/unsubscribe` after `cache-key`
  + `cache` resolution; the facade fn holds the public API shape.

  `frame-id` is threaded through to `dispose-entry-now!`
  so the `:rf.sub/dispose` trace emit at the actual eviction site
  carries the right `:frame` tag. The 2-arity form serves callers that
  don't carry a frame-id; the emit falls back to `:frame nil` on that
  path."
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
;; Per Spec 001 §Hot-reload semantics + Cross-Spec-Interactions §18: when a
;; :sub re-registers, every cached reaction whose query-id is that sub MUST
;; be disposed and evicted across every frame's cache — AND so must every
;; cached DOWNSTREAM sub that depends on it (directly or transitively) via
;; `:<-`. Cached reactions hold the OLD body via closure (and downstream
;; reactions hold the OLD input reaction); without invalidating the whole
;; transitive dependent closure, a downstream slot like `[:sum] :<- [:a]`
;; keeps its stale input reaction and serves the old `:a` body's value.

(defn- transitive-dependent-closure
  "Given a cache map `m` and a re-registered sub `id`, return the set of
  cache keys to evict: the re-registered sub's own slots PLUS every slot
  that depends on it transitively through the `:<-` topology recorded in
  each entry's `:inputs` (the vector of input query-vectors).

  A slot is a dependent iff any of its `:inputs` query-vectors either
  (a) has head = `id` — a DIRECT `:<-` on the re-registered sub — or
  (b) equals a key already in the evict set — a TRANSITIVE dependency on
  an already-condemned slot. The fixpoint loop grows the set until no new
  key is added; it only ever ADDS keys not already present, so a cyclic
  `:<-` graph cannot loop forever (each key is admitted at most once)."
  [m id]
  (let [;; Static index: cache-key → the seq of its input query-vectors.
        inputs-of  (fn [k] (:inputs (get m k)))
        depends-on (fn [k condemned]
                     ;; k depends on the re-registration iff one of its
                     ;; inputs targets `id` directly or a condemned slot.
                     (boolean
                       (some (fn [input-q]
                               (or (= id (first input-q))
                                   (contains? condemned input-q)))
                             (inputs-of k))))
        seed       (into #{} (filter #(= id (first %))) (keys m))]
    (loop [condemned seed]
      (let [next-set (into condemned
                           (filter #(depends-on % condemned))
                           (keys m))]
        (if (= next-set condemned)
          condemned
          (recur next-set))))))

(defn- invalidate-sub-on-replace!
  [{:keys [kind id]}]
  (when (= kind :sub)
    (doseq [frame-id (frame/frame-ids)]
      (when-let [cache (:sub-cache (frame/frame frame-id))]
        ;; The swap-fn body is pure — it returns only the new cache map.
        ;; Reactions to dispose are read from the diff between `old` and
        ;; `new` AFTER the CAS commits (so a retried `swap!` can't fire
        ;; dispose 2+ times). The condemned set is the transitive `:<-`
        ;; dependent closure, recomputed inside the swap-fn against the
        ;; map the CAS actually sees (a retry recomputes against fresh m).
        (let [[old new] (swap-vals! cache
                                    (fn [m]
                                      (apply dissoc m
                                             (transitive-dependent-closure m id))))
              ;; The keys actually evicted by THIS swap are those present
              ;; in `old` but absent in `new`. A concurrent evictor that
              ;; won the CAS race would have removed its keys before our
              ;; swap saw them, so the diff names ONLY the keys we own.
              evicted-keys (filterv #(not (contains? new %))
                                    (keys old))]
          ;; Emit dispose per evicted key BEFORE running the
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

  Zero-arity resolves the scope/hold stamp via
  `frame/require-current-frame!` (EP-0002) — called under no established
  scope it raises `:rf.error/no-frame-context` rather than clearing an
  invented default. One-arity targets the named frame (the right shape
  for fixtures / tools outside any scope). Returns nil. See also:
  `re-frame.subs/clear-sub` (registrar-side counterpart)."
  ([] (clear-sub-cache! (frame/require-current-frame!
                          :clear-sub-cache!
                          {:where 're-frame.subs.cache/clear-sub-cache!})))
  ([frame-id]
   (when-let [cache (:sub-cache (frame/frame frame-id))]
     (doseq [[k entry] @cache]
       ;; Emit dispose per evicted key BEFORE the per-
       ;; reaction `interop/dispose!`. Reason `:cache-clear`
       ;; discriminates the explicit-teardown path from sync 1 → 0
       ;; fires (`:no-more-derefers`) and hot-reload re-registration
       ;; (`:hot-reload`).
       (emit-dispose! frame-id k :cache-clear)
       (when-let [r (:reaction entry)]
         (try (interop/dispose! r)
              (catch #?(:clj Throwable :cljs :default) _ nil))))
     (reset! cache {}))))

(defn clear-all-frame-sub-caches!
  "CLJC-safe adapter-disposal sub-cache walk: dispose every cached entry
  in EVERY live frame's runtime sub-cache and reset each cache to `{}`.

  This is the per-process counterpart to `clear-sub-cache!` (one frame) —
  the externally-visible equal of `re-frame.substrate.spine/dispose-frame-
  sub-caches!` (the CLJS-only walk wired into the React-shaped adapters'
  `dispose-adapter!`), lifted into this CLJC ns so the CLJC adapters
  (`test-react`, `plain-atom`) can satisfy Spec 006 §Adapter disposal
  lifecycle MUST 1 (`dispose-adapter!` cancels all in-flight reactive
  subscriptions across every live frame's sub-cache) without taking a
  static dependency on the CLJS-only spine. Per Spec 006 §Lifetime
  contract — frame disposal §Adapter symmetry: the adapter's
  `dispose-adapter!` disposes every frame's sub-cache as part of process
  teardown.

  Best-effort, per-frame: a throwing per-entry dispose does NOT abort the
  rest of the walk — every other cached entry in the same frame AND every
  subsequent frame's cache still gets disposed and reset (`clear-sub-cache!`
  already swallows per-entry throws). Emits `:rf.sub/dispose` with
  `:rf.sub/reason :cache-clear` per evicted slot (the closed-enum reason
  shared with explicit `clear-sub-cache!` test/REPL teardown). Returns nil."
  []
  (doseq [frame-id (frame/frame-ids)]
    (clear-sub-cache! frame-id))
  nil)

;; ---- frame-destroy eviction ----------------------------------------------
;;
;; `re-frame.frame/destroy-frame!` tears the destroyed frame's sub-cache
;; down as one of its ordered steps. It MUST funnel through this helper
;; (not dispose reactions directly) so frame-destroy evictions appear in
;; the `:rf.sub/dispose` lifecycle stream like every other eviction path
;; — otherwise a whole class of real evictions vanishes and tooling that
;; audits retained subs can't tell a clean teardown from missing data.
;;
;; `frame.cljc` requires THIS ns transitively (subs.cache → frame), so it
;; cannot statically require us back; the call is routed through the
;; `:subs.cache/dispose-all-for-frame-destroy!` late-bind hook published
;; below. Symmetric with `clear-sub-cache!` but stamps the dedicated
;; `:frame-destroy` reason so consumers discriminate frame teardown from
;; explicit test/REPL `:cache-clear`.

(defn dispose-all-for-frame-destroy!
  "Dispose every entry in `cache` (the destroyed frame's `:sub-cache`
  atom), emitting one `:rf.sub/dispose` per slot with `:rf.sub/reason
  :frame-destroy` and `:frame frame-id`, then empty the cache. Per Spec
  009 §`:rf.sub/dispose` reason enum + Spec 006 §Disposal on frame
  destroy. Returns nil."
  [cache frame-id]
  (when cache
    (doseq [[k entry] @cache]
      (emit-dispose! frame-id k :frame-destroy)
      (when-let [r (:reaction entry)]
        (try (interop/dispose! r)
             (catch #?(:clj Throwable :cljs :default) _ nil))))
    (reset! cache {}))
  nil)

(late-bind/set-fn! :subs.cache/dispose-all-for-frame-destroy!
                   dispose-all-for-frame-destroy!)
