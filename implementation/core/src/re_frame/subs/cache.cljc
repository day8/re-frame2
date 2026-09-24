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

  **The one thrash that is NOT accepted.** A React-hook render and the
  commit that owns it are two moments, and a first-mount read that built
  a reaction in the first, dropped it to zero on the way out, and rebuilt
  it in the second would pay TWO constructions, and for a layer-2+ sub a
  second walk of the whole input chain, on every cold read. That is not a
  re-mount; it is ONE mount paying twice. The React-hook spine carries its
  render-phase +1 across that gap in a hook-scoped escrow so the commit
  can ADOPT the same reaction (Spec 006 §Render-phase provisional
  acquisition and commit adoption); the release is
  `unsubscribe-if-reaction!` below. The cache itself is indifferent: the
  +1 is an ordinary ref-count held by an ordinary owner, the cache never
  holds a ref-count-0 entry, and 1 → 0 disposes in-tick with no grace
  period. What moves is only WHO holds the reference during the gap.

  **Paid anyway, on the mount path that ships.** Through the public
  adapter render slot with no `act` / `flushSync`, the escrow's macrotask
  reaper fires before React's passive `useSyncExternalStore` subscribe, so
  the gap is crossed by nobody, this eviction runs, and the commit
  rebuilds — two constructions per cold read. The cache side is unaffected
  either way (it sees an ordinary release and an ordinary 1 → 0), and the
  reaper's horizon decides the outcome. Read the paragraph above as the
  mechanism, not as a claim about what a shipped mount costs.

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
;; NO READER. There is no node-disposed hook reading it. The var and
;; its bindings are RETAINED for the reason Spec 009 gives for
;; `rf.frame/guard-open-drain!` at zero call sites: the CAUSE is knowable only
;; here, so a hook that ever needs it can only be served from here. Removing
;; the bindings would make the information unrecoverable rather than merely
;; unused.
;;
;; A former-owner disposal notification
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
  site, read late-bound by a node-disposed hook. ZERO READERS; see the
  section comment above for why it is retained. nil outside any eviction
  extent."
  nil)

;; ---- dispose trace emit ---------------------------------------------------
;;
;; Per Spec 009 §:op-type vocabulary §`:rf.sub/dispose` — every cache
;; eviction site funnels through this helper so the emit tag-shape is
;; single-sourced. `k` is the cache-key (the query-vector itself, per
;; `re-frame.subs/cache-key`); `query-id` is `(first k)`.
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

(defn ^:no-doc emit-no-more-derefers!
  "INTERNAL. Emit the `:no-more-derefers` dispose trace for a slot
  evicted by the RATOM FAMILY'S OWN teardown route rather than by a ref-count
  decrement taken here.

  WHY A SECOND DOOR TO THE SAME EMIT EXISTS. On Reagent and reagent-slim a
  cached sub reaction can be torn down by the substrate instead of by the
  cache: `Reaction`'s `-remove-watch` disposes itself once its last watcher
  drops and it carries no `auto-run`, which is precisely what a view unmount
  does. That fires re-frame's own on-dispose callback, which then removes the
  slot — a genuine eviction, at a genuine `no-more-derefers` moment, reached
  without passing through `unsubscribe!`'s 1 → 0 edge. Spec 006 §Reference
  counting and disposal requires the emit AT THE EVICTION SITE, so the
  on-dispose callback needs a way to make it.

  DOUBLE-EMIT IS IMPOSSIBLE BY CONSTRUCTION, and that is why this is safe to
  call unconditionally from that callback — the CALLER gates on having
  actually removed the slot. Every other eviction path in this namespace
  (`dispose-entry-now!`, `invalidate-sub-on-replace!`, `clear-sub-cache!`,
  `invalidate-frame-subs!`) removes the slot from the cache atom BEFORE it
  disposes the reaction, so by the time the reaction's on-dispose callback
  runs, its identity-guarded removal finds nothing to remove and the caller
  never reaches this fn. It fires only when the reaction died while STILL
  cached, which is the ratom auto-dispose case and nothing else."
  [frame-id k]
  (emit-dispose! frame-id k :no-more-derefers))

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
         ;; :hmr by a co-pending HMR drain.
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

  That churn is accepted between two DIFFERENT owners. It is not meant to
  be paid inside ONE mount, and the React-hook spine has a way to avoid
  it: hold the render-phase reference until the commit adopts it, so a
  cold first mount need not drive 1 → 0 between its own render and its
  own commit (see `unsubscribe-if-reaction!` below and Spec 006
  §Render-phase provisional acquisition and commit adoption). On the
  mount path consumers actually use, the escrow's macrotask reaper beats
  React's passive subscribe and that release lands here anyway, so the
  cold first mount reaches this edge and rebuilds. The rule this
  docstring states holds either way; only who reaches the edge, and how
  often, is at issue, and the reaper's horizon decides it.

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
  "INTERNAL. `unsubscribe!` with an IDENTITY GUARD: decrement
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
;;
;; THE REGISTRAR IS NOT THE ONLY REGISTRY A SUB'S DEFINITION CAN
;; MOVE IN. An image-loaded frame resolves `(kind, id)` through its OWN sealed
;; generation (EP-0023), so a same-id `make-frame` re-construction or a
;; source-store reprojection changes what `[:some-sub]` MEANS in that frame
;; without any `register!` call firing — the replacement hook below never
;; runs, and the cache (durable frame state, deliberately preserved across the
;; swap) would keep serving the previous generation's body until the caller
;; thought to `clear-sub-cache!` by hand. Both triggers are one event seen from two
;; registries, so both funnel through the ONE primitive below
;; (`invalidate-frame-subs!`); the generation-side trigger lives in
;; `re-frame.live-frame`, which owns `generation-diff` and can therefore name
;; exactly the sub-ids that moved.

(defn- transitive-dependent-closure
  "Given a cache map `m` and a SET of affected sub `ids`, return the set of
  cache keys to evict: those subs' own slots PLUS every slot that depends on
  them transitively through the declared-input topology recorded in each
  entry's `:inputs` (the vector of input query-vectors).

  A slot is a dependent iff any of its `:inputs` query-vectors either
  (a) has head IN `ids` — a DIRECT declared input on an affected sub — or
  (b) equals a key already in the evict set — a TRANSITIVE dependency on
  an already-condemned slot. The fixpoint loop grows the set until no new
  key is added; it only ever ADDS keys not already present, so a cyclic
  declared-input graph cannot loop forever (each key is admitted at most once).

  Clause (a) is what reaches a cached PARENT whose declared input has NO slot
  of its own — the input was unregistered when the parent was built, so the
  miss was deliberately not cached while the parent WAS, holding
  the nil-yielding input reaction by closure. `ids` is a SET rather than one
  id because a frame-generation change condemns a whole BATCH of
  sub-ids at once — `:added` / `:changed` / `:removed` between two
  generations — and running the fixpoint once over the batch is not the same
  as running it per-id: a chain condemned through two different ids
  converges in one pass."
  [m ids]
  (let [;; Static index: cache-key → the seq of its input query-vectors.
        inputs-of  (fn [k] (:inputs (get m k)))
        depends-on (fn [k condemned]
                     ;; k depends on the change iff one of its inputs targets
                     ;; an affected id directly or a condemned slot.
                     (boolean
                       (some (fn [input-q]
                               (or (contains? ids (first input-q))
                                   (contains? condemned input-q)))
                             (inputs-of k))))
        seed       (into #{} (filter #(contains? ids (first %))) (keys m))]
    (loop [condemned seed]
      (let [next-set (into condemned
                           (filter #(depends-on % condemned))
                           (keys m))]
        (if (= next-set condemned)
          condemned
          (recur next-set))))))

(defn invalidate-frame-subs!
  "Evict + dispose every slot in frame `frame-id`'s sub-cache whose sub-id is
  in `sub-ids`, PLUS their transitive declared-input dependent closure. The
  cache ATOM is preserved (durable frame state survives); every slot outside
  the closure keeps its reaction identity and its ref-count.

  The ONE per-frame invalidation primitive, shared by the two eviction
  triggers that mean \"this sub's definition moved under a live cache\":

    * `reg-sub` REPLACEMENT — `invalidate-sub-on-replace!` below, over every
      frame, with a one-element `sub-ids` (Spec 001 §Hot-reload semantics);
    * a frame's resolved image GENERATION changing — `re-frame.live-frame`'s
      `invalidate-subs-for-generation-change!`, over the ONE frame that moved,
      with the `:sub` ids the two generations differ on (EP-0023 §Hot
      Reload).

  Both are the SAME event seen from two registries, so both emit
  `:rf.sub/dispose` with the closed-enum `:rf.sub/reason :hot-reload` (Spec
  009 §subs/cache.cljc) and bind the intrinsic `*disposal-cause* :hot-reload`
  (→ `:hmr`, \"the node WILL rebuild\") rather than minting a second spelling
  for one meaning.

  Returns the vector of evicted cache keys (empty when nothing matched)."
  [frame-id sub-ids]
  (if-let [cache (and (seq sub-ids) (:sub-cache (rf.frame/frame frame-id)))]
    ;; The swap-fn body is pure — it returns only the new cache map.
    ;; Reactions to dispose are read from the diff between `old` and
    ;; `new` AFTER the CAS commits (so a retried `swap!` can't fire
    ;; dispose 2+ times). The condemned set is the transitive declared-input
    ;; dependent closure, recomputed inside the swap-fn against the
    ;; map the CAS actually sees (a retry recomputes against fresh m).
    (let [ids       (set sub-ids)
          [old new] (swap-vals! cache
                                (fn [m]
                                  (apply dissoc m
                                         (transitive-dependent-closure m ids))))
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
      ;; Tag any synchronous node-disposed notification with the
      ;; INTRINSIC :hot-reload cause (→ :hmr) so a former owner is told
      ;; the node WILL rebuild (re-acquire), not that it is gone. Nested
      ;; `dispose-entry-now!` cascades (a downstream input losing its last
      ;; derefer) correctly shadow this to :disposed.
      (binding [*disposal-cause* :hot-reload]
        (doseq [k evicted-keys]
          (when-let [r (get-in old [k :reaction])]
            (try (rf.interop/dispose! r)
                 (catch #?(:clj Throwable :cljs :default) _ nil)))))
      evicted-keys)
    []))

(defn- invalidate-sub-on-replace!
  [{:keys [kind id]}]
  (when (= kind :sub)
    (doseq [frame-id (rf.frame/frame-ids)]
      (invalidate-frame-subs! frame-id #{id}))))

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
  `(rf/clear :sub id)` (registrar-side counterpart).

  The cache atom is reset to `{}` BEFORE any
  `rf.interop/dispose!` call, not after. A layer-2+ slot's on-dispose
  callback releases its declared-input refs via `unsubscribe!`, which — if
  the input's slot were still present in the cache atom mid-walk — could
  drive its ref-count to 0 and fire `dispose-entry-now!`, re-emitting a
  SECOND `:rf.sub/dispose` (reason `:no-more-derefers`) for a slot this
  same walk is about to visit with reason `:cache-clear`; the resulting
  double-emit's ORDER (and thus which reason lands first) would depend on
  hash-map iteration order over the cache. Clearing the atom first means
  every cascade-driven `unsubscribe!` finds nothing to evict, so it can
  never re-fire — every slot in the pre-clear snapshot gets exactly one
  emit, deterministically reasoned `:cache-clear`."
  ([] (clear-sub-cache! (rf.frame/require-current-frame!
                          :clear-sub-cache!
                          {:where 're-frame.subs.cache/clear-sub-cache!})))
  ([frame-id]
   (when-let [cache (:sub-cache (rf.frame/frame frame-id))]
     ;; Evict the whole cache BEFORE any dispose! call — see the docstring
     ;; note above — and do it in ONE atomic take, disposing exactly the map
     ;; it removed. A separate `@cache` read and `reset!` would leave a JVM
     ;; window in which an entry acquired between them is erased without
     ;; being in the batch, so it is never disposed, and two overlapping
     ;; clears could each condemn the same entry.
     (let [[snapshot _] (reset-vals! cache {})]
       ;; Tag any synchronous node-disposed notification with the
       ;; INTRINSIC :cache-clear cause (→ :disposed) so an explicit
       ;; teardown is never mislabelled :hmr by a co-pending HMR drain.
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

  The cache atom is reset to `{}` BEFORE any
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
    ;; One atomic take, as in `clear-sub-cache!`: the batch
    ;; disposed is exactly the map this call removed.
    (let [[snapshot _] (reset-vals! cache {})]
      ;; Tag any synchronous node-disposed notification with the
      ;; INTRINSIC :frame-destroy cause (→ :disposed) so a frame teardown is
      ;; never mislabelled :hmr by a co-pending HMR drain.
      (binding [*disposal-cause* :frame-destroy]
        (doseq [[k entry] snapshot]
          (emit-dispose! frame-id k :frame-destroy)
          (when-let [r (:reaction entry)]
            (try (rf.interop/dispose! r)
                 (catch #?(:clj Throwable :cljs :default) _ nil)))))))
  nil)

(rf.late-bind/set-fn! :subs.cache/dispose-all-for-frame-destroy!
                   dispose-all-for-frame-destroy!)
