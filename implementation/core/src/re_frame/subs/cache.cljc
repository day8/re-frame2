(ns re-frame.subs.cache
  "Sub-cache state, ref-counting, synchronous disposal, hot-reload
  invalidation, and the test-fixture cache clear.

  Per Spec 006 §Subscription cache and §Reference counting and disposal.
  This ns owns the per-frame `:sub-cache` shape:

    {<cache-key> {:reaction r :inputs [...] :ref-count n}}

  The cached value is NOT a stored slot — it lives on the reaction, read
  via deref. Disposal is wired on the reaction (rf.interop/add-on-dispose!),
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

  **The one thrash that is NOT accepted (rf2-2rtt6.25).** A React-hook
  render and the commit that owns it are two moments, and a first-mount
  read used to build a reaction in the first, drop it to zero on the way
  out, and rebuild it in the second — TWO constructions, and for a
  layer-2+ sub a second walk of the whole input chain, on every cold
  read. That was not a re-mount; it was ONE mount paying twice. The
  React-hook spine carries its render-phase +1 across that gap in a
  hook-scoped escrow so the commit can ADOPT the same reaction (Spec 006
  §Render-phase provisional acquisition and commit adoption); the release
  is `unsubscribe-if-reaction!` below. Nothing here changes: the +1 is an
  ordinary ref-count held by an ordinary owner, the cache never holds a
  ref-count-0 entry, and 1 → 0 still disposes in-tick with no grace
  period. What moves is only WHO holds the reference during the gap.

  **Still paid, on the mount path that ships (rf2-2rtt6.25, audit of
  #7305).** Measured through the public adapter render slot with no
  `act` / `flushSync`: the escrow's macrotask reaper fires before React's
  passive `useSyncExternalStore` subscribe, so the gap is crossed by
  nobody, this eviction runs, and the commit rebuilds — two constructions
  per cold read, as before. The cache side is unaffected either way (it
  sees an ordinary release and an ordinary 1 → 0), and the horizon that
  would change the outcome is an operator decision on rf2-2rtt6.14. Read
  the paragraph above as the mechanism, not as a claim about what a
  shipped mount currently costs.

  The `swap-vals!`-after-CAS patterns (in `dispose-entry-now!`,
  `unsubscribe!`, and `invalidate-sub-on-replace!`) all encode the same
  concurrency-safety property: any side-effect (`rf.interop/dispose!`)
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
  `rf.interop/debug-enabled?` so production CLJS bundles DCE them with the
  rest of the trace surface."
  (:require [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.trace :as rf.trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- intrinsic disposal cause (late-bound out to a node-disposed hook) ----
;;
;; NO READER TODAY (rf2-63t1i). The internal observation port's node-disposed
;; hook was the only one, and the port was retired on 2026-08-21. The var and
;; its bindings are RETAINED for the reason Spec 009 gives for
;; `rf.frame/guard-open-drain!` at zero call sites: the CAUSE is knowable only
;; here, so a hook that ever needs it can only be served from here. Removing
;; the bindings would make the information unrecoverable rather than merely
;; unused.
;;
;; rf2-r8jmdb / rf2-x76af2.34 FINDING 1: a former-owner disposal notification
;; must be tagged with the cause the node ACTUALLY
;; died of (`:hmr` = re-registered, will rebuild → re-acquire; `:disposed` =
;; gone), NOT with whichever drain boundary happens to fire first. That cause is
;; known ONLY here, at the eviction site — HMR re-registration and a cache clear
;; both leave the frame live, so nothing downstream can recover which one ran.
;; Each site binds this var to its INTRINSIC reason around its `rf.interop/dispose!`
;; call(s); a node-disposed hook — which fires SYNCHRONOUSLY inside
;; `rf.interop/dispose!` — reads it as a plain deref, and maps
;; `:hot-reload` → `:hmr`, every other reason → `:disposed`. nil outside any
;; eviction extent (an `acquire!`-stack re-check enqueue defaults `:disposed` —
;; never the re-acquire-signalling `:hmr`). Nested binding is correct: a
;; cascade that drives another site (e.g. an HMR eviction whose reaction dispose
;; drops a downstream input's last derefer → `dispose-entry-now!`) shadows the
;; cause for exactly that inner dispose, so a genuinely gone input node reports
;; `:disposed` even mid-HMR. One of the `:rf.sub/dispose` reason enum values.

(def ^:dynamic *disposal-cause*
  "The INTRINSIC `:rf.sub/dispose` reason for the reaction(s) being disposed in
  the current synchronous `rf.interop/dispose!` extent — bound by each eviction
  site, read late-bound by a node-disposed hook. ZERO READERS TODAY; see the
  section comment above for why it is retained. nil outside any eviction
  extent."
  nil)

;; ---- dispose trace emit ---------------------------------------------------
;;
;; Per Spec 009 §:op-type vocabulary §`:rf.sub/dispose` — every cache
;; eviction site funnels through this helper so the emit tag-shape is
;; single-sourced. `k` is the cache-key (currently the query-vector
;; itself, per `re-frame.subs/cache-key`); `query-id` is `(first k)`.
;; The whole call sits behind `rf.interop/debug-enabled?` so production
;; CLJS bundles DCE the tag-map allocation + the emit call along with
;; the rest of the trace surface.

(defn- emit-dispose!
  [frame-id k reason]
  (when rf.interop/debug-enabled?
    (rf.trace/emit! :rf.sub :rf.sub/dispose
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
  (`rf.interop/dispose!`) inside the swap-fn could fire 2+ times under
  concurrent invalidate + dispose race.

  Emits `:rf.sub/dispose` with `:rf.sub/reason
  :no-more-derefers` after the CAS commits, for the call that actually
  drove the eviction (read off the `old` / `new` snapshot diff — the
  same single-fire discipline that gates `rf.interop/dispose!`). `frame-id`
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
       ;; `rf.interop/dispose!`) so we never double-emit under contention.
       (emit-dispose! frame-id k :no-more-derefers)
       (when-let [r (get-in old [k :reaction])]
         ;; Tag a synchronous node-disposed notification
         ;; with the INTRINSIC cause (→ :disposed) so it can never be mislabelled
         ;; :hmr by a co-pending HMR drain (rf2-r8jmdb).
         (binding [*disposal-cause* :no-more-derefers]
           (try (rf.interop/dispose! r)
                (catch #?(:clj Throwable :cljs :default) _ nil)))))
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

  That churn is accepted between two DIFFERENT owners. It was never
  meant to be paid inside ONE mount, and rf2-2rtt6.25 gave the React-hook
  spine a way to avoid it: hold the render-phase reference until the
  commit adopts it, so a cold first mount need not drive 1 → 0 between its
  own render and its own commit (see `unsubscribe-if-reaction!` below and
  Spec 006 §Render-phase provisional acquisition and commit adoption).
  On the mount path consumers actually use, the escrow's macrotask reaper
  beats React's passive subscribe and that release lands here anyway
  (measured — the audit of #7305), so the cold first mount still reaches
  this edge and still rebuilds. The rule this docstring states is
  unchanged either way; only who reaches the edge, and how often, is at
  issue, and the horizon that decides it is an operator call.

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

(defn ^:no-doc unsubscribe-if-reaction!
  "INTERNAL (rf2-2rtt6.25). `unsubscribe!` with an IDENTITY GUARD: decrement
  the ref-count for `k` **only while the slot still holds `reaction`**, then
  take the ordinary 1 → 0 in-tick disposal. Not part of the public API —
  `re-frame.subs/unsubscribe` remains the teardown every consumer calls.

  The guard exists for **holders that may outlive their slot**. The React-hook
  spine's render-phase provisional acquisition (Spec 006 §Render-phase
  provisional acquisition and commit adoption) takes a +1 during render and
  releases it either at the commit that adopts it or from a host-macrotask
  reaper — and in the window between, hot reload, `clear-sub-cache!`, or
  `destroy-frame!` may have evicted the slot and disposed the reaction. That
  eviction already took the +1 with it, so an unguarded decrement would
  either underflow a successor entry rebuilt under the same key or steal a
  ref another subscriber owns. Guarded, a stale release is a clean no-op: its
  reference died with the eviction.

  Everything else is `unsubscribe!`'s: the same `swap-vals!`-after-CAS
  discipline (the swap-fn body is pure; the drop-to-zero signal is read from
  the pre/post snapshots so a retried `swap!` cannot fire a spurious
  dispose), the same `dispose-entry-now!` eviction, the same
  `:no-more-derefers` emit. The cache's shape, its algorithm, and Spec 006's
  no-grace-period rule are untouched — this fn only narrows WHEN the
  decrement applies, never what a decrement does.

  `frame-id` is threaded through to `dispose-entry-now!` so the eviction
  site's `:rf.sub/dispose` emit carries the right `:frame` tag."
  [cache k reaction frame-id]
  (let [[old new] (swap-vals! cache
                              (fn [m]
                                (if-let [entry (get m k)]
                                  (if (identical? reaction (:reaction entry))
                                    (let [old-n (or (:ref-count entry) 1)
                                          n     (max 0 (dec old-n))]
                                      (assoc-in m [k :ref-count] n))
                                    m)
                                  m)))
        ;; This swap drove the 1 → 0 transition iff the guard admitted it —
        ;; the slot still holds OUR reaction in the post-swap snapshot — and
        ;; the count went 1 → 0. Same snapshot-diff reasoning as
        ;; `unsubscribe!`.
        dropped-to-zero? (and (identical? reaction (get-in new [k :reaction]))
                              (= 1 (or (get-in old [k :ref-count]) 1))
                              (zero? (or (get-in new [k :ref-count]) 0)))]
    (when dropped-to-zero?
      (dispose-entry-now! cache k frame-id))
    nil))

;; ---- hot-reload invalidation ---------------------------------------------
;;
;; Per Spec 001 §Hot-reload semantics + Cross-Spec-Interactions §18: when a
;; :sub re-registers, every cached reaction whose query-id is that sub MUST
;; be disposed and evicted across every frame's cache — AND so must every
;; cached DOWNSTREAM sub that depends on it (directly or transitively) via
;; declared inputs. Cached reactions hold the OLD body via closure (and downstream
;; reactions hold the OLD input reaction); without invalidating the whole
;; transitive dependent closure, a downstream slot like `[:sum]` over `[:a]`
;; keeps its stale input reaction and serves the old `:a` body's value.

(defn- transitive-dependent-closure
  "Given a cache map `m` and a re-registered sub `id`, return the set of
  cache keys to evict: the re-registered sub's own slots PLUS every slot
  that depends on it transitively through the declared-input topology recorded in
  each entry's `:inputs` (the vector of input query-vectors).

  A slot is a dependent iff any of its `:inputs` query-vectors either
  (a) has head = `id` — a DIRECT declared input on the re-registered sub — or
  (b) equals a key already in the evict set — a TRANSITIVE dependency on
  an already-condemned slot. The fixpoint loop grows the set until no new
  key is added; it only ever ADDS keys not already present, so a cyclic
  declared-input graph cannot loop forever (each key is admitted at most once)."
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
    (doseq [frame-id (rf.frame/frame-ids)]
      (when-let [cache (:sub-cache (rf.frame/frame frame-id))]
        ;; The swap-fn body is pure — it returns only the new cache map.
        ;; Reactions to dispose are read from the diff between `old` and
        ;; `new` AFTER the CAS commits (so a retried `swap!` can't fire
        ;; dispose 2+ times). The condemned set is the transitive declared-input
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
          ;; per-reaction `rf.interop/dispose!` teardown. The reason
          ;; `:hot-reload` discriminates this path from sync 1 → 0 fires
          ;; (`:no-more-derefers`) and explicit `clear-sub-cache!`
          ;; (`:cache-clear`).
          (doseq [k evicted-keys]
            (emit-dispose! frame-id k :hot-reload))
          ;; Tag the observation port's synchronous node-disposed notifications
          ;; with the INTRINSIC :hot-reload cause (→ :hmr) so a former owner is
          ;; told the node WILL rebuild (re-acquire), not that it is gone
          ;; (rf2-r8jmdb). Nested `dispose-entry-now!` cascades (a downstream
          ;; input losing its last derefer) correctly shadow this to :disposed.
          (binding [*disposal-cause* :hot-reload]
            (doseq [k evicted-keys]
              (when-let [r (get-in old [k :reaction])]
                (try (rf.interop/dispose! r)
                     (catch #?(:clj Throwable :cljs :default) _ nil))))))))))

(defonce ^:private _hot-reload-hook
  (do (rf.registrar/add-replacement-hook! invalidate-sub-on-replace!)
      :installed))

(defn clear-sub-cache!
  "Dispose every cached entry in a frame's runtime sub-cache and clear
  the cache.

  Test fixtures and REPL-driven reloads call this between scenarios
  to ensure the cache is empty before re-subscribing. Test code
  generally prefers `make-reset-runtime-fixture` (per `test_support`) which
  bundles cache-clearing with registrar / frame state reset.

  Zero-arity resolves the scope/hold stamp via
  `rf.frame/require-current-frame!` (EP-0002) — called under no established
  scope it raises `:rf.error/no-frame-context` rather than clearing an
  invented default. One-arity targets the named frame (the right shape
  for fixtures / tools outside any scope). Returns nil. See also:
  `re-frame.subs/clear-sub` (registrar-side counterpart).

  Per rf2-awhtpc: the cache atom is reset to `{}` BEFORE any
  `rf.interop/dispose!` call, not after. A layer-2+ slot's on-dispose
  callback releases its declared-input refs via `unsubscribe!`, which — if
  the input's slot were still present in the cache atom mid-walk — could
  drive its ref-count to 0 and fire `dispose-entry-now!`, re-emitting a
  SECOND `:rf.sub/dispose` (reason `:no-more-derefers`) for a slot this
  same walk is about to visit with reason `:cache-clear`; the resulting
  double-emit's ORDER (and thus which reason lands first) depended on
  hash-map iteration order over the cache. Clearing the atom first means
  every cascade-driven `unsubscribe!` finds nothing to evict, so it can
  never re-fire — every slot in the pre-clear snapshot gets exactly one
  emit, deterministically reasoned `:cache-clear`."
  ([] (clear-sub-cache! (rf.frame/require-current-frame!
                          :clear-sub-cache!
                          {:where 're-frame.subs.cache/clear-sub-cache!})))
  ([frame-id]
   (when-let [cache (:sub-cache (rf.frame/frame frame-id))]
     (let [snapshot @cache]
       ;; Evict the whole cache BEFORE any dispose! call — see the
       ;; rf2-awhtpc note above.
       (reset! cache {})
       ;; Tag the observation port's synchronous node-disposed notifications
       ;; with the INTRINSIC :cache-clear cause (→ :disposed) so an explicit
       ;; teardown is never mislabelled :hmr by a co-pending HMR drain
       ;; (rf2-r8jmdb).
       (binding [*disposal-cause* :cache-clear]
         (doseq [[k entry] snapshot]
           ;; Emit dispose per evicted key BEFORE the per-
           ;; reaction `rf.interop/dispose!`. Reason `:cache-clear`
           ;; discriminates the explicit-teardown path from sync 1 → 0
           ;; fires (`:no-more-derefers`) and hot-reload re-registration
           ;; (`:hot-reload`).
           (emit-dispose! frame-id k :cache-clear)
           (when-let [r (:reaction entry)]
             (try (rf.interop/dispose! r)
                  (catch #?(:clj Throwable :cljs :default) _ nil)))))))))

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
  (doseq [frame-id (rf.frame/frame-ids)]
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
  destroy.

  Per rf2-awhtpc: the cache atom is reset to `{}` BEFORE any
  `rf.interop/dispose!` call — same rationale as `clear-sub-cache!` above.
  Without pre-clearing, disposing a layer-2+ slot cascades (via its
  on-dispose callback) into `unsubscribe!` on its declared inputs; if an
  input's slot were still live in the cache mid-walk, that could drive
  its ref-count to 0 and fire a SECOND `:rf.sub/dispose` (reason
  `:no-more-derefers`) for a slot this walk is about to visit with
  reason `:frame-destroy` — nondeterministic by hash-map iteration
  order. Pre-clearing means the cascade always finds nothing to evict,
  so every slot in the pre-clear snapshot gets exactly one emit,
  deterministically reasoned `:frame-destroy`. Returns nil."
  [cache frame-id]
  (when cache
    (let [snapshot @cache]
      (reset! cache {})
      ;; Tag the observation port's synchronous node-disposed notifications with
      ;; the INTRINSIC :frame-destroy cause (→ :disposed) so a frame teardown is
      ;; never mislabelled :hmr by a co-pending HMR drain (rf2-r8jmdb).
      (binding [*disposal-cause* :frame-destroy]
        (doseq [[k entry] snapshot]
          (emit-dispose! frame-id k :frame-destroy)
          (when-let [r (:reaction entry)]
            (try (rf.interop/dispose! r)
                 (catch #?(:clj Throwable :cljs :default) _ nil)))))))
  nil)

(rf.late-bind/set-fn! :subs.cache/dispose-all-for-frame-destroy!
                   dispose-all-for-frame-destroy!)
