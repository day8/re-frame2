(ns re-frame.substrate.spine
  "Shared substrate-spine helpers for React-shaped adapters that lack a
  native reactive-atom primitive (UIx, Helix, and any future minimal-
  React-wrapper substrate). UIx and Helix duplicated this body byte-
  for-byte modulo gensym prefixes, hook ns, and substrate-name strings;
  per-adapter wiring goes through `make-react-spine`.

  Scope. This ns provides:

    * The plain-`atom` container quartet (make / read / replace / subscribe).
    * `make-derived-value` (one watch per source, coalesced through a
      shared per-adapter epoch scheduler so a multi-input derived value
      recomputes glitch-free and notifies once per coherent input epoch;
      reifies the re-frame-owned `re-frame.disposable/IDisposable`).
    * React 18+ root renderer (createRoot + render, hydrateRoot for
      hydrate).
    * Late-bind hiccup-emitter atom + `render-to-string` thrower.
    * The chained source-coord wrapper (`format-source-coord`,
      `dom-element?`, `inject-source-coord-attr`, `warn-non-dom-root!`,
      `clear-warned-non-dom-roots!`, `wrap-view`) parameterised on the
      substrate-name string for the warning text.
    * `flush-views!` with a correct `react-dom/test-utils` fallback
      (subsumes rf2-jk7hr).
    * A factory `make-react-spine` that produces the per-substrate
      hook-based surfaces (`use-current-frame`, `use-subscribe`,
      `frame-provider`, `register-context-provider`) given the
      substrate's hook fns.

  Reagent and reagent-slim do NOT use this ns: they have a native
  reactive-atom primitive (`r/atom` + `ratom/make-reaction`) and a
  Reagent-component-shaped frame-provider in `re-frame.views`, so the
  shapes diverge from the React-hook-shaped contract here.

  Per Spec 006 §CLJS reference — adapters using this spine remain
  shape-compliant with the ten-fn substrate contract (six required +
  three optional + one lifecycle)."
  (:require ["react"             :as React]
            ["react-dom"         :as react-dom]
            ["react-dom/client"  :as react-dom-client]
            [re-frame.disposable :as rf-disposable]
            [re-frame.error      :as rf-error]
            [re-frame.frame      :as frame]
            [re-frame.interop    :as interop]
            [re-frame.late-bind  :as late-bind]
            [re-frame.subs       :as subs]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.adapter.context :as adapter-context]))

;; ---- epoch scheduler (glitch-freedom; Spec 006 §Invalidation algorithm) ----
;;
;; Reagent realises the Phase 1/2/3 invalidation contract automatically:
;; each `Reaction` re-runs once per `r/flush!` against settled inputs, so
;; a multi-input layer-2+ reaction never sees a half-updated input set and
;; never notifies more than once per app-db change. The spine has no
;; reaction primitive, so it must satisfy the contract explicitly
;; (Spec 006: "Non-CLJS implementations must satisfy the contract
;; explicitly").
;;
;; The bug a naive spine has (rf2-i21f5): wiring one `add-watch` per
;; source that recomputes-and-notifies INLINE means a layer-2+ sub with N
;; changed inputs recomputes once per changed input. The first source's
;; notify drives the downstream recompute; the second source's notify
;; drives it AGAIN, and so on — N recomputes per app-db change instead of
;; one. Each redundant recompute re-runs the user's sub body and emits a
;; `:sub/run` trace, and a downstream layer-3 sub re-fires per redundant
;; layer-2 notification, fanning the waste out across the whole `:<-`
;; graph. (The spine's derived value is pull-based — `-deref` recomputes
;; fresh from current sources — so each recompute reads settled source
;; state and the final notified VALUE is correct; the defect is the
;; redundant recompute storm + over-notification, not a wrong value. The
;; downstream `notify` only fires on a `=`-change, which masks the storm
;; for `=`-stable bodies but not for the recompute work, the trace
;; emissions, or any body with observable per-call cost.) Reagent is
;; immune (native batched `r/flush!`); the spine must satisfy the Phase
;; 1/2/3 contract explicitly.
;;
;; The fix mirrors Reagent's batched flush. A single per-adapter
;; scheduler is shared by `replace-container!` (the only app-db mutation
;; entry point, Spec 006 §revertibility) and every `make-derived-value`.
;; `replace-container!` brackets its `reset!` in an epoch: source watches
;; fired by the `reset!` only MARK their derived value dirty (enqueue a
;; recompute-and-notify thunk) instead of recomputing inline. When the
;; outermost epoch closes, the scheduler drains the queue. A derived
;; value's recompute, on changing by `=`, notifies its watchers — which
;; for a downstream derived value's source-watch marks THAT derived dirty
;; and enqueues it in turn. Because a dirty-flag dedups re-marks within an
;; epoch, every derived value recomputes EXACTLY ONCE against fully
;; settled inputs and notifies its subscribers at most once — exactly the
;; Phase 1/2/3 ordering (one Phase-3 notification per dirty entry).
;;
;; Outside an epoch (a direct `reset!`/`swap!` on a source by test code or
;; tooling, rather than through `replace-container!`) the mark falls
;; through to an immediate single-derived flush so the contract still
;; holds for the one-source-changed case.
;;
;; `depth` / `flushing?` / `queue` are written and read only on the
;; single-threaded JS event loop and never escape the adapter closure —
;; `volatile!` is the right primitive, no CAS cost.

(defn make-scheduler
  "Return a fresh per-adapter epoch scheduler cell. Each adapter owns its
  own so multiple React-shaped adapters can coexist in a test bundle
  without sharing an epoch queue."
  []
  {:depth     (volatile! 0)   ;; open-epoch nesting depth
   :flushing? (volatile! false)
   :queue     (volatile! [])  ;; ordered queue of pending flush thunks
   :queued    (volatile! #{})}) ;; identity set guarding double-enqueue

(defn- drain-scheduler!
  "Drain the scheduler's pending flush thunks in enqueue order until the
  queue is empty. Re-entrant-safe: an in-progress drain swallows nested
  drain requests (the running loop already observes newly-enqueued
  thunks). Each thunk recomputes its derived value and, on a `=`-change,
  notifies its watchers — which may enqueue downstream thunks that the
  same loop then drains, preserving topological order."
  [{:keys [flushing? queue queued] :as _scheduler}]
  (when-not @flushing?
    (vreset! flushing? true)
    ;; Walk the live `@queue` by index rather than re-slicing the head: a
    ;; thunk may `schedule-flush!` downstream thunks, which `conj` onto the
    ;; same vector, so re-reading `(count @queue)` each step keeps the
    ;; running loop observing newly-enqueued thunks in enqueue order —
    ;; identical to the previous incremental-`subvec` drain, but without
    ;; building a chain of nested subvec views per cascade step. The
    ;; cursor advances and the entry leaves `queued` BEFORE the thunk runs,
    ;; so a thunk that throws is already considered consumed (matching the
    ;; old head-pop-before-call ordering); the `finally` then compacts
    ;; `@queue` to exactly the not-yet-drained tail on every exit path.
    (let [cursor (volatile! 0)]
      (try
        (loop []
          (when (< @cursor (count @queue))
            (let [thunk (nth @queue @cursor)]
              (vswap! queued disj thunk)
              (vswap! cursor inc)
              (thunk))
            (recur)))
        (finally
          ;; Drop the drained prefix in ONE pass (a single fresh vector,
          ;; not a per-step subvec chain): normally the tail is empty so
          ;; this releases the backing vector for the next epoch; on a
          ;; thunk throw it leaves the un-drained remainder, exactly as the
          ;; incremental-subvec drain did.
          (let [q @queue]
            (vreset! queue (if (< @cursor (count q))
                             (into [] (subvec q @cursor))
                             [])))
          (vreset! flushing? false))))))

(defn- schedule-flush!
  "Enqueue `thunk` on the scheduler (dedup by identity within the current
  epoch). When no epoch is open, drain immediately so a direct source
  mutation outside `replace-container!` still flushes synchronously."
  [{:keys [depth queue queued] :as scheduler} thunk]
  (when-not (contains? @queued thunk)
    (vswap! queued conj thunk)
    (vswap! queue conj thunk))
  (when (zero? @depth)
    (drain-scheduler! scheduler)))

(defn- with-epoch
  "Run `body-thunk` inside an open epoch on `scheduler`; the outermost
  close drains the pending flush queue. Nested epochs (a re-entrant
  `replace-container!` during a flush) only drain at the outermost
  boundary so coalescing spans the whole synchronous cascade."
  [{:keys [depth] :as scheduler} body-thunk]
  (vswap! depth inc)
  (try
    (body-thunk)
    (finally
      (vswap! depth dec)
      (when (zero? @depth)
        (drain-scheduler! scheduler)))))

;; ---- container ------------------------------------------------------------
;;
;; Per Spec 006 §revertibility-constraints the container holds the
;; frame's app-db value and *only* the frame's app-db value. React-only
;; substrates (UIx, Helix) don't ship a reactive atom primitive (their
;; hook substrate is React state) so we lean on a plain
;; `clojure.core/atom` and broadcast changes via `add-watch` — observably
;; equivalent to the Reagent adapter's r/atom for the substrate contract
;; surface (read, replace, subscribe). Reactive view-side hookup happens
;; through `useSyncExternalStore` in the spine's `use-subscribe` factory,
;; not through Reagent reactions.

;; rf2-w1g0d2: `make-state-container` is the only point of the container
;; quartet that genuinely differs between the React-hook spine and the
;; ratom family — the React-only substrates seed a plain
;; `clojure.core/atom`, the ratom family seeds the substrate's reactive
;; atom (`r/atom` / `reagent2.*`). The ctor is the ONLY variable, so a
;; single ctor-parameterised factory serves BOTH spines: the React spine
;; binds the plain-`atom` arity below, the ratom spine passes its injected
;; `r-atom`. `read-container`, `replace-container!`, and `make-derived-value`
;; do NOT collapse the same way — see `make-ratom-spine` for why
;; `replace-container!` (epoch-bracketed react vs bare `reset!` ratom) and
;; `make-derived-value` (explicit reify vs native reaction) legitimately
;; diverge; `read-container` IS identical and the ratom spine reuses this
;; Var directly.
(defn make-state-container-fn
  "Return a `make-state-container` fn that seeds its container with the
  given `ctor` (a 1-arg `initial-value -> container` constructor). The
  React-hook spine passes `clojure.core/atom`; the ratom family passes its
  injected reactive-atom ctor (`r-atom`)."
  [ctor]
  (fn make-state-container [initial-value]
    (ctor initial-value)))

(def make-state-container
  "React-hook spine `make-state-container`: seeds a plain `clojure.core/atom`
  (React-only substrates ship no reactive-atom primitive). The ratom family
  builds its own via `make-state-container-fn` with the substrate's `r-atom`."
  (make-state-container-fn atom))

(defn read-container [container]
  @container)

(defn make-replace-container-fn
  "Return a `replace-container!` fn that brackets its `reset!` in an epoch
  on `scheduler`. The `reset!` fires source watches synchronously; those
  watches only MARK their derived values dirty (see
  `make-derived-value-fn`). The epoch close drains the coalesced flush
  queue so each affected derived value recomputes glitch-free against the
  settled app-db and notifies its subscribers exactly once (Spec 006
  §Invalidation algorithm)."
  [scheduler]
  (fn replace-container! [container new-value]
    (with-epoch scheduler (fn epoch-body [] (reset! container new-value)))
    nil))

(defn make-subscribe-container
  "Return a `subscribe-container` fn that gensyms watch keys with the
  given `gensym-prefix`. Parameterised on prefix only so warning logs /
  test inspectors can attribute the watch back to its host substrate."
  [gensym-prefix]
  (fn subscribe-container [container on-change]
    (let [k (gensym gensym-prefix)]
      (add-watch container k (fn [_ _ prev nu] (on-change prev nu)))
      (fn unsubscribe [] (remove-watch container k)))))

;; ---- derived value --------------------------------------------------------
;;
;; React-only substrates have no reaction primitive. The substrate
;; contract requires that (read-container) on a derived container deref
;; a fresh value computed from the sources; subscribe-container on a
;; derived container fires when any source changes. We satisfy both via
;; a thin IDeref wrapper whose change-broadcasting fan-out is one watch
;; per source.
;;
;; Equality-on-= is preserved by the core's sub-cache invalidation
;; algorithm (Spec 006 §Invalidation algorithm Phase 2): the sub-cache
;; only re-emits when the recomputed value differs from the cached one
;; by =. The derived container itself does not memoise; per the same
;; spec section that's the cache's job, not the substrate's.
;;
;; Laziness (rf2-ee38b.1 P2). The derived value MUST NOT run `compute-fn`
;; at construction time. `compute-fn` here is the core's memo wrapper —
;; it runs the user sub body and (in dev) emits `:rf.sub/run` via
;; `validate-and-trace`, and for a layer-2+ sub eagerly derefs the whole
;; `:<-` input chain. Reagent (`ratom/make-reaction`, lazy until first
;; deref) and the plain-atom adapter (recompute-on-deref) both defer the
;; first body invocation to first read; the spine matches by seeding
;; `prev-state` with the `unset` sentinel rather than with `(recompute)`.
;; The first flush (or deref) is then the first real recompute — a
;; subscribe-time `(recompute)` would emit `:rf.sub/run` and side-effect
;; before any render reads the reaction, an observable cross-adapter
;; divergence (extra body invocations, different trace timing) that
;; contradicts Spec 006 §No-op via value equality's "body runs on demand"
;; intent. The sentinel `not=` any real value, so the first post-
;; construction change always notifies — the same first-change-notifies
;; semantics Reagent gives.

(def ^:private unset
  "Sentinel for a derived value whose baseline has not yet been computed.
  Distinct object identity so it can never `=` a real derived value (incl.
  `nil`/`false`), making the first flush after construction always notify."
  (js-obj))

(defn build-recompute-fn
  "Arity-specialised recompute-closure factory for a derived value.

  Returns a 0-arg thunk that derefs `source-containers` and calls
  `compute-fn` with the deref'd values. The hottest path in the
  artefact is `derived-recompute × dispatch × subscriber`; subs
  typically chain off 1 input (layer-1 always; layer-n usually 1–2).
  Specialising 0/1/2 sidesteps the `apply` + lazy-`map` cost on the
  dominant arities; ≥3 falls back to `mapv` (eager, vector-backed)
  + `apply`.

  `count` is captured once at construction (per Spec 006 §CLJS reference
  + `re-frame.subs`, `source-containers` is a vector) so the recompute
  closure pays no per-tick `count`.

  Single source of truth: the Reagent, reagent-slim, UIx, and Helix
  adapters all build their recompute closure through this fn — one
  implementation, four adapters, zero drift. The arity-spec lifted
  into the spine matches the `make-dispose-adapter!` shape
  (rf2-jcjul); sourced from the rf2-fzrav perf-sweep findings."
  [source-containers compute-fn]
  (let [n (count source-containers)]
    (case n
      0 (fn recompute-0 [] (compute-fn))
      1 (let [s0 (nth source-containers 0)]
          (fn recompute-1 [] (compute-fn @s0)))
      2 (let [s0 (nth source-containers 0)
              s1 (nth source-containers 1)]
          (fn recompute-2 [] (compute-fn @s0 @s1)))
      (fn recompute-n [] (apply compute-fn (mapv deref source-containers))))))

(defn make-derived-value-fn
  "Return a `make-derived-value` fn that tags per-source watch keys with
  the given `gensym-prefix` and coalesces source-change notifications
  through `scheduler` (see the epoch-scheduler section above). The fn
  signature matches the substrate contract:
  `(sources compute-fn) -> derived-container`.

  Single-recompute / single-notification (rf2-i21f5): a source-change
  watch does NOT recompute or notify inline. It marks this derived value
  dirty (enqueues a single recompute-and-notify thunk on the scheduler).
  The epoch open by `replace-container!` defers the drain until the whole
  synchronous app-db cascade has settled, so a multi-input derived value
  recomputes EXACTLY ONCE against the coherent input set and notifies its
  subscribers at most once — never N times (one recompute + one Phase-3
  notification per dirty entry per app-db change)."
  [gensym-prefix scheduler]
  (fn make-derived-value [source-containers compute-fn]
    (let [recompute      (build-recompute-fn source-containers compute-fn)
          watchers       (atom {})           ;; user-key → wrapper-fn
          on-dispose-fns (atom [])
          ;; Per-source wrapper keys we own so dispose can unwire them.
          ;; A VECTOR of `[source key]` pairs, NOT a `source→key` map
          ;; (rf2-he7se finding 2): `source-containers` is a vector with
          ;; no uniqueness precondition (spec/006 §154-170), so the SAME
          ;; source object may appear more than once. Each occurrence
          ;; installs its own gensym-keyed watch; a `source→key` map would
          ;; overwrite earlier keys, so dispose would release only the
          ;; LAST watch per source and leak the rest. Tracking every
          ;; `[source key]` pair lets dispose release ALL held inputs
          ;; (spec/006 §600-613).
          own-keys       (atom [])           ;; vector of [source key]
          ;; Disposed guard (rf2-1bzlai). `-dispose` MUST be idempotent and
          ;; re-entrant safe: a second `-dispose`, or a re-entrant
          ;; `interop/dispose!` fired from inside an on-dispose callback
          ;; (e.g. a cleanup path that defensively disposes the same derived
          ;; value), must be a no-op after the first pass. The flag flips
          ;; true on the first call BEFORE callbacks run, so a re-entrant
          ;; dispose short-circuits rather than re-firing the callback set or
          ;; double-releasing layer-2 input watches. Single-threaded JS event
          ;; loop, never escapes this closure — `volatile!` is the right
          ;; primitive (matches `prev-state` / `dirty?` above).
          disposed?      (volatile! false)
          ;; Iterate via `run!` over `vals` rather than `doseq` over
          ;; map-entries — skips one map-entry seq allocation per
          ;; source-change notification.
          notify         (fn [prev nu]
                           (when (not= prev nu)
                             (run! (fn [w] (w prev nu)) (vals @watchers))))
          ;; Baseline derived value. LAZY (rf2-ee38b.1 P2): seeded with the
          ;; `unset` sentinel rather than `(recompute)`, so `compute-fn`
          ;; (the memo wrapper running the user sub body) is NOT invoked at
          ;; construction/subscribe time — matching Reagent's lazy
          ;; `make-reaction` and the plain-atom recompute-on-deref adapters.
          ;; The body runs on demand: the FIRST `-deref` (which the sub-
          ;; cache performs to read the subscription's value) establishes
          ;; the baseline, and a `replace-container!` change after that
          ;; notifies `[prev-derived new-derived]` exactly as before. If a
          ;; change flushes before any deref ever happened (no reader),
          ;; `prev-state` is still `unset`; `unset` `not=` any real value
          ;; (incl. nil/false) so the first flush still notifies — the same
          ;; first-change-notifies semantics Reagent gives. (Seeding from
          ;; the *derived* value, never the raw source, still holds: the
          ;; flush thunk compares the recomputed derived value against the
          ;; prior derived value / sentinel, so a projection like
          ;; `(odd? x)` / counts / `:k` lookups never spuriously notifies on
          ;; a same-`=` re-derive.)
          ;;
          ;; `prev-state` / `dirty?` are written and read only on the
          ;; single-threaded JS event loop and never escape this closure —
          ;; `volatile!` is the right primitive, no CAS cost. `dirty?`
          ;; dedups re-marks within an epoch: a multi-input derived value
          ;; whose N sources all fire enqueues exactly one flush thunk.
          prev-state     (volatile! unset)
          dirty?         (volatile! false)
          ;; First-deref baseline seed (rf2-ee38b.1 P2). Pure pull-based
          ;; recompute, but on the FIRST deref it also records the value as
          ;; `prev-state` so the next change's notification carries the real
          ;; prior derived value (not the `unset` sentinel). Subsequent
          ;; derefs do not touch `prev-state` — the flush path owns it.
          deref-derived  (fn deref-derived []
                           (let [v (recompute)]
                             (when (identical? unset @prev-state)
                               (vreset! prev-state v))
                             v))
          flush!         (fn flush! []
                           ;; Disposed-tombstone guard (rf2-jgzica). A
                           ;; derived value's `mark-dirty!` enqueues this
                           ;; thunk on the SHARED epoch scheduler; the drain
                           ;; runs at epoch close. If the reaction is disposed
                           ;; BETWEEN mark-dirty and the drain (a cascade where
                           ;; a downstream unsubscribe drives a sibling
                           ;; ref-count to 0 and disposes a reaction whose
                           ;; flush is already queued), the queued thunk still
                           ;; fires. `-dispose` clears `watchers`, so the
                           ;; `notify` fan-out is already a no-op — but
                           ;; `recompute` is the memo wrapper, so without this
                           ;; guard it re-runs the user sub body and (in dev)
                           ;; emits a spurious `:rf.sub/run`: the exact
                           ;; redundant-recompute the epoch scheduler exists to
                           ;; prevent (rf2-i21f5). The scheduler cannot dequeue
                           ;; a single thunk, so the disposed reaction skips it
                           ;; here instead. Also reset `dirty?` so a re-marked-
                           ;; then-disposed entry leaves a clean guard.
                           (vreset! dirty? false)
                           (when-not @disposed?
                             (let [new-derived  (recompute)
                                   prev-derived @prev-state]
                               (vreset! prev-state new-derived)
                               (notify prev-derived new-derived))))
          mark-dirty!    (fn mark-dirty! []
                           (when-not @dirty?
                             (vreset! dirty? true)
                             (schedule-flush! scheduler flush!)))]
      ;; Wire one watch per source so the listener registry surface
      ;; (subscribe-container) on the derived container fires whenever any
      ;; source changes. The watch only MARKS dirty — the actual recompute
      ;; + notify is deferred to the scheduler drain so it runs once
      ;; against settled inputs (glitch-free, single notification).
      (doseq [s source-containers]
        (let [k (gensym gensym-prefix)]
          (swap! own-keys conj [s k])
          (add-watch s k (fn [_ _ _ _] (mark-dirty!)))))
      (reify
        IDeref
        (-deref [_] (deref-derived))
        ;; Watch surface — `(subscribe-container derived on-change)` rides
        ;; on this through the standard core helper, and the sub-cache's
        ;; per-entry recompute layer keys watches by gensym so the
        ;; remove-watch path below stays clean.
        IWatchable
        (-add-watch [this k f]
          (swap! watchers assoc k (fn [prev nu] (f k this prev nu)))
          this)
        (-remove-watch [_this k]
          (swap! watchers dissoc k)
          nil)
        ;; Re-frame-owned IDisposable — `interop/add-on-dispose!` /
        ;; `interop/dispose!` route into this protocol via the
        ;; adapter's `:adapter/add-on-dispose!` / `:adapter/dispose!`
        ;; hooks (per Spec 006 §subscription-cache). The spine
        ;; deliberately uses `re-frame.disposable/IDisposable` (re-
        ;; frame-owned, no Reagent dependency) rather than
        ;; `reagent.ratom/IDisposable` so UIx/Helix bundles don't pay
        ;; ~9KB optimised / 2-3KB gzipped of `reagent.ratom` +
        ;; `reagent.impl.batching` for one protocol.
        rf-disposable/IDisposable
        (-dispose [_]
          ;; Idempotent + re-entrant safe (rf2-1bzlai). Flip the guard
          ;; FIRST so a re-entrant `-dispose` from inside a callback (or a
          ;; plain second call) short-circuits before any teardown re-runs.
          (when-not @disposed?
            (vreset! disposed? true)
            (doseq [[s k] @own-keys] (remove-watch s k))
            (reset! own-keys [])
            (reset! watchers {})
            ;; Snapshot-and-clear callbacks before firing: a callback that
            ;; re-enters `interop/dispose!` on this same object hits the
            ;; guard above (no-op) and never sees the callbacks again, so
            ;; the set fires exactly once in registration order.
            (let [fns @on-dispose-fns]
              (reset! on-dispose-fns [])
              (doseq [f fns] (f)))))
        (-add-on-dispose [_ f]
          (swap! on-dispose-fns conj f))))))

;; ---- render ---------------------------------------------------------------
;;
;; React-only substrates call react-dom/client directly (UIx's uix.dom
;; doesn't expose hydrate-root in every version; Helix ships no DOM
;; wrapper at all). createRoot + .render for fresh mounts; hydrateRoot
;; for the SSR-hydrate path. Both shapes return an unmount thunk.
;;
;; Active roots are tracked in a per-spine atom (rf2-9fdkb). Each mount
;; adds the React root to the active set; the returned unmount thunk
;; removes itself from the set and calls `.unmount` on the root. The
;; spine's `dispose-adapter!` drains the set so torn-down adapters
;; release every root they spun up — Spec 006 §Adapter disposal
;; lifecycle requires browser adapters to unmount active roots.

(defn make-active-roots-cell
  "Return a fresh `(atom #{})` cell holding React roots the spine
  currently keeps mounted. Each adapter owns its own cell so multiple
  React-shaped adapters can coexist in a test bundle without
  clobbering each other's tracking."
  []
  (atom #{}))

(defn track-active-root!
  "Register an already-built React `root` in `active-roots-cell` and return
  a self-removing unmount thunk: it drops `root` from the cell BEFORE
  calling `(unmount-op root)`. Shared by the React-hook `make-render`
  (`unmount-op` = `.unmount`) and the ratom-family render (`unmount-op` =
  the injected `unmount-root`), so the `dispose-adapter!` active-roots drain
  always sees the live set (rf2-w1g0d2). The root constructor / tree-wrap
  (Fragment+sentinel vs none) differs per spine and stays in each render;
  only this tracking tail is shared."
  [active-roots-cell unmount-op root]
  (swap! active-roots-cell conj root)
  (fn unmount []
    (swap! active-roots-cell disj root)
    (unmount-op root)))

(defn make-render
  "Build a `render` fn that registers every mounted React root in
  `active-roots-cell` and returns an unmount thunk that removes the
  root from the cell before calling `.unmount`.

  The user's `render-tree` is wrapped in a Fragment alongside an
  `after-render-sentinel` element (rf2-334d9). The sentinel is a bare
  React function component that fires `React.useLayoutEffect` on every
  commit and drains the per-adapter after-render queue; it renders no
  DOM. See `make-after-render-machinery` for the queue / sentinel
  factory."
  [active-roots-cell after-render-sentinel-cmp]
  (fn render [render-tree mount-point opts]
    ;; Spec 006 §`render` types `:hydrate?` as a boolean; non-bool
    ;; truthy values are undefined-behaviour (no defensive coercion).
    (let [hydrate?     (:hydrate? opts)
          wrapped-tree (React/createElement
                         (.-Fragment React)
                         nil
                         (React/createElement after-render-sentinel-cmp nil)
                         render-tree)
          root         (if hydrate?
                         (react-dom-client/hydrateRoot mount-point wrapped-tree)
                         (let [r (react-dom-client/createRoot mount-point)]
                           (.render r wrapped-tree)
                           r))]
      ;; rf2-w1g0d2: shared track-and-unmount tail (unmount-op = .unmount).
      (track-active-root! active-roots-cell (fn [r] (.unmount r)) root))))

;; ---- after-render --------------------------------------------------------
;;
;; `:adapter/after-render` for React-only substrates (UIx, Helix) per
;; rf2-334d9 (Mike decision rf2-neiqf 2026-05-19: publish via
;; useLayoutEffect) — without this `(rf/after-render f)` under those
;; adapters would be a silent no-op.
;;
;; Architecture. Per-adapter queue cell + a sentinel function component
;; injected at the root of every mounted tree (via `make-render`'s
;; Fragment wrap). The sentinel uses `React.useLayoutEffect` to drain
;; the queue after each commit — same DOM-mutations-applied / pre-paint
;; timing semantics as Reagent's `r/after-render`. When `after-render`
;; is called, the sentinel's stashed `setState` bumps a tick to force a
;; commit so its `useLayoutEffect` fires and drains the queue.
;;
;; Native-mount parity (rf2-t0x90). The Fragment-wrap sentinel only
;; enters the tree when an app mounts through the adapter's `:render`
;; slot. But the documented boot idiom (and all three adapter testbeds)
;; mounts via the substrate-native renderer directly (`uix-dom/render-
;; root`, Helix's `(.render root …)`), which bypasses `make-render` —
;; so a natively-mounted UIx/Helix app NEVER has a sentinel in its tree.
;; Reagent's `r/after-render` is a global post-flush hook that works
;; regardless of mount path; without parity, the SAME `(rf/after-render
;; f)` call has correct post-commit timing on Reagent but degraded
;; microtask timing on natively-mounted UIx/Helix — a silent substrate
;; divergence in a public primitive.
;;
;; The fix: a per-adapter SINGLETON DRIVER ROOT, mounted lazily the
;; first time `after-render` is called with no app-tree sentinel
;; present. The hook mounts the sentinel component into a detached
;; (never-attached-to-the-document) React root via `createRoot`; the
;; sentinel's mount LAYOUT effect stashes its `set-tick` setter into
;; `set-tick-ref` exactly as the Fragment-wrap sentinel does, so the
;; same `set-tick` → commit → `useLayoutEffect`-drain machinery now
;; drives post-commit timing on the native-mount path too. The driver
;; root is created once per adapter and reused for the process lifetime
;; (it renders no DOM — the sentinel returns nil — so a detached host
;; node is sufficient and never touches the document). An app-tree
;; sentinel, when present, still wins: it claims `set-tick-ref` and the
;; driver root simply sits idle.
;;
;; Headless / no-DOM fallback. `createRoot` needs `document`; under a
;; pure-node runner (no jsdom) there is no DOM to mount into. In that
;; case — and in the historical pre-DOM-API path — fall through to
;; `queueMicrotask` so `f` still fires once the current microtask
;; boundary completes. Honest under the "tests poke `interop/after-
;; render` without a DOM" path.

(defn make-after-render-queue-cell
  "Return a fresh `(atom [])` queue of pending after-render callbacks.
  Each adapter owns its own cell so multiple React-shaped adapters can
  coexist in a test bundle without clobbering each other's queue."
  []
  (atom []))

(defn make-after-render-set-tick-ref
  "Return a fresh `(atom nil)` slot the sentinel writes its `setState`
  setter into on mount and clears on unmount. Each adapter owns its
  own so the after-render hook below can route to the right adapter's
  sentinel."
  []
  (atom nil))

(defn make-after-render-driver-root-cell
  "Return a fresh `(atom nil)` slot holding the per-adapter SINGLETON
  DRIVER ROOT — the detached React root the after-render hook mounts
  the sentinel into the first time `after-render` is called with no
  app-tree sentinel present (rf2-t0x90 native-mount parity). Lazily
  populated and reused for the adapter's lifetime; each adapter owns
  its own so multiple React-shaped adapters in a test bundle don't
  share a driver root. Drained on `dispose-adapter!`."
  []
  (atom nil))

(defn- drain-after-render-queue!
  "Atomically swap the pending-callbacks vector with empty and invoke
  each in order. Per-fn throws are swallowed so one misbehaving callback
  cannot strand the rest of the drain."
  [queue-cell]
  (let [[pending] (reset-vals! queue-cell [])]
    (doseq [f pending]
      (try (f) (catch :default _ nil)))))

(defn make-after-render-sentinel
  "Build the sentinel React function component for an adapter. The
  sentinel returns nil (no DOM impact) and:

    1. On mount, stashes its `setState` setter in `set-tick-ref` so
       `:adapter/after-render` can trigger a commit. Cleared on unmount.
       Installed from a LAYOUT effect (rf2-he7se finding 3) so the
       singleton-driver-root setup's `flushSync` arms the slot
       synchronously before it decides setter-present vs. microtask
       fallback. `flushSync` ALWAYS flushes layout effects synchronously
       (a documented guarantee); its flushing of PASSIVE `useEffect`s is a
       React-19 implementation detail, not a contract — so the prior
       passive install was not robust across React versions/configs.
    2. On every commit, fires `React.useLayoutEffect` to drain
       `queue-cell` — same timing as `r/after-render`'s post-commit
       run.

  The sentinel uses raw React hooks (`React/useState`,
  `React/useLayoutEffect`) rather than the substrate's hook ns so the
  same impl works for UIx, Helix, and any future React-shaped substrate
  using this spine.

  Returned value is the bare function component, suitable for
  `(React/createElement sentinel-cmp nil)`."
  [queue-cell set-tick-ref]
  (fn after-render-sentinel [_props]
    (let [tick+setter (React/useState 0)
          set-tick    (aget tick+setter 1)]
      ;; Install the setter from a LAYOUT effect, not a passive useEffect
      ;; (rf2-he7se finding 3). `ensure-after-render-driver-root!` renders
      ;; this sentinel inside `react-dom/flushSync` and EXPECTS the setter
      ;; present in `set-tick-ref` the instant flushSync returns, so it can
      ;; bump the tick rather than falling through to the microtask drain.
      ;; `flushSync` ALWAYS flushes layout effects synchronously during the
      ;; commit; whether it flushes passive (`useEffect`) effects is a
      ;; React-19 implementation detail, NOT a documented guarantee. Where
      ;; passives are deferred (older React / future configs) a passive
      ;; setter-install would leave the slot nil on flushSync's return, so
      ;; the hook would take the `queueMicrotask` fallback and the queue
      ;; could drain BEFORE the app commit after-render is meant to
      ;; observe. A layout mount effect makes the arm-before-decide
      ;; ordering version-INDEPENDENT.
      (React/useLayoutEffect
        (fn mount-effect []
          (reset! set-tick-ref set-tick)
          (fn cleanup []
            ;; Only clear if it's still us — guards against a sentinel
            ;; from a sibling root having claimed the slot in between.
            (compare-and-set! set-tick-ref set-tick nil)))
        #js [set-tick])
      ;; No deps array — fires every commit, which is the contract
      ;; (rf/after-render bumps the tick to force a commit, so the
      ;; useLayoutEffect fires and drains).
      (React/useLayoutEffect
        (fn layout-effect []
          (drain-after-render-queue! queue-cell)
          js/undefined))
      nil)))

(defn- dom-available?
  "True when a `document` capable of creating elements is reachable —
  the precondition for mounting the singleton driver root. False under
  a pure-node runner (no jsdom), where the after-render hook falls
  through to the microtask drain."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- ensure-after-render-driver-root!
  "Lazily mount the per-adapter SINGLETON DRIVER ROOT (rf2-t0x90). If
  `driver-root-cell` is empty, create a detached host node + React root,
  render `sentinel-cmp` into it inside `react-dom/flushSync` so the
  sentinel's mount LAYOUT effect runs SYNCHRONOUSLY and stashes its
  `set-tick` setter into `set-tick-ref` before this fn returns. The host
  node is never attached to the document — the sentinel renders nil, so
  no DOM is produced. Idempotent: a populated cell is left untouched.
  Returns nil."
  [driver-root-cell sentinel-cmp]
  (when (nil? @driver-root-cell)
    (let [host (.createElement js/document "div")
          root (react-dom-client/createRoot host)]
      (reset! driver-root-cell root)
      ;; flushSync so the sentinel's mount LAYOUT effect (which stashes
      ;; set-tick into set-tick-ref) runs synchronously — the caller
      ;; bumps the tick immediately after, expecting the setter present.
      ;; flushSync ALWAYS runs layout effects synchronously; passive
      ;; `useEffect`s are not contractually flushed by it (React-19 detail
      ;; only), so the layout-effect install keeps the slot armed on return
      ;; regardless of React version — without it the slot could be nil and
      ;; force the microtask fallback that drains before the pending app
      ;; commit (rf2-he7se finding 3).
      (react-dom/flushSync
        (fn [] (.render root (React/createElement sentinel-cmp nil))))))
  nil)

(defn make-after-render-hook
  "Build the `:adapter/after-render` impl fn. The returned fn:

    1. Enqueues `f` on `queue-cell`.
    2. If an app-tree sentinel is mounted (`set-tick-ref` is non-nil —
       the app mounted through the adapter's `:render` slot), bumps its
       tick — React schedules a commit, the sentinel's `useLayoutEffect`
       fires, and the queue drains in post-commit / pre-paint order.
    3. Otherwise (the documented native-mount path, rf2-t0x90, where the
       app mounted via the substrate-native renderer and no Fragment-wrap
       sentinel is in the tree) lazily mounts the per-adapter SINGLETON
       DRIVER ROOT — a detached React root carrying the same sentinel —
       and bumps its now-stashed tick, giving the native-mount path the
       SAME post-commit timing as Reagent's global `r/after-render`.
    4. If no DOM is reachable (pure-node runner, no jsdom), falls through
       to a `queueMicrotask` drain so `f` still fires once the current
       microtask boundary completes."
  [queue-cell set-tick-ref sentinel-cmp driver-root-cell]
  (fn after-render-hook [f]
    (swap! queue-cell conj f)
    (when (and (nil? @set-tick-ref) (dom-available?))
      ;; Native-mount path: no app-tree sentinel claimed the slot — arm
      ;; the singleton driver root, whose sentinel stashes set-tick.
      (ensure-after-render-driver-root! driver-root-cell sentinel-cmp))
    (if-let [set-tick @set-tick-ref]
      (set-tick inc)
      (if (exists? js/queueMicrotask)
        (js/queueMicrotask #(drain-after-render-queue! queue-cell))
        (.then (js/Promise.resolve) #(drain-after-render-queue! queue-cell))))
    nil))

(defn dispose-frame-sub-caches!
  "Walk every live frame's per-frame sub-cache and dispose each cached
  Reaction (Spec 006 §Adapter disposal lifecycle MUST 1; rf2-9fdkb,
  rf2-a47kq, rf2-jcjul).

  Why the walk exists at all. Component-unmount-driven disposal handles
  the mounted case — the reactive substrate reaps a derived value once
  its last watcher drops. This walk covers the test-fixture / headless
  path where no component unmount fires before the adapter goes away,
  AND the SSR / server-render path where the rendered tree was string-
  serialised without ever being mounted. Without the walk, a long-lived
  process driving sequential `init! → dispose-adapter!` cycles (test
  bundles, hot-reload, multi-adapter integration tests) accumulates
  cached Reactions closed over stale frames forever.

  Per-entry contract. For every `[k entry]` in every live frame's
  `:sub-cache` atom:

    1. Dispose the cached `:reaction` via `interop/dispose!`. This
       routes through `:adapter/dispose!`, which is still wired at
       this point in the teardown sequence (the substrate-adapter
       clears the install slot AFTER calling the adapter's
       `dispose-adapter!`).
    2. After draining each frame's entries, `reset!` its sub-cache
       atom to `{}`.

  The walk is best-effort: a throwing per-entry dispose (e.g. a
  misbehaving user `:on-dispose` hook, or a poison entry inserted by
  tests) does NOT abort the rest of the walk — every other cached
  Reaction in the same cache AND every cache in subsequent frames
  still gets disposed and cleared. Per-entry throws are swallowed.

  Used by every React-shaped adapter's `dispose-adapter!` — wired into
  the `make-dispose-adapter!` factory for UIx / Helix and called
  directly from the Reagent / reagent-slim adapters' dispose paths.
  Centralising the walk here is the rf2-jcjul lockstep: one
  implementation, three adapters, zero drift."
  []
  (doseq [[_ frame-record] @frame/frames]
    (when-let [cache (:sub-cache frame-record)]
      (doseq [[_k entry] @cache]
        (when-let [r (:reaction entry)]
          (try (interop/dispose! r)
               (catch :default _ nil))))
      (reset! cache {}))))

(defn dispose-active-roots-and-caches!
  "Core dispose-drain shared by BOTH spines' `dispose-adapter!`
  (rf2-w1g0d2). Satisfies the substrate-common subset of the Spec 006
  §Adapter disposal lifecycle four-MUST list:

    1. Cancel in-flight reactive subscriptions — `dispose-frame-sub-caches!`.
    2. Release host-specific resources — drain `active-roots-cell`, calling
       `(unmount-op root)` on every tracked root and SWALLOWING per-root
       throws so one misbehaving root cannot strand the rest of the drain;
       then reset the cell to `#{}`.
    3. Discard internal caches — clear the hiccup `emitter-cell`.

  `unmount-op` is the per-spine root-unmount fn (`.unmount` for the
  React-hook spine, the injected `unmount-root` for the ratom family); it
  is the ONLY substrate-varying step in this subset, so parameterising it
  lets both spines reuse the identical drain loop. The React-hook spine
  layers its extra teardown (warn-cache + after-render driver-root +
  set-tick slot) AFTER calling this; the ratom family's dispose IS exactly
  this subset (no warn-cache, no driver-root)."
  [unmount-op active-roots-cell emitter-cell]
  (dispose-frame-sub-caches!)
  (doseq [root @active-roots-cell]
    (try (unmount-op root)
         (catch :default _ nil)))
  (reset! active-roots-cell #{})
  (when emitter-cell (reset! emitter-cell nil))
  nil)

(defn make-dispose-adapter!
  "Build a `dispose-adapter!` fn satisfying Spec 006 §Adapter disposal
  lifecycle (rf2-9fdkb). The returned fn:

    1. Walks every live frame's per-frame sub-cache and disposes each
       cached Reaction (`dispose-frame-sub-caches!`), satisfying MUST
       (1): cancel all in-flight reactive subscriptions.
    2. Drains `active-roots-cell` by calling `.unmount` on every
       tracked React root, satisfying MUST (2): release host-specific
       resources.
    3. Clears the spine's per-adapter caches — `active-roots-cell`,
       `warn-cache`, `emitter-cell` — satisfying MUST (3): discard
       internal caches.

  MUST (4) (subsequent calls return `:rf.error/adapter-disposed`) is
  enforced one level up by `substrate-adapter/dispose-adapter!` via
  the `disposed?` breadcrumb (rf2-6wxys).

  Best-effort drains. React's `.unmount` is idempotent / no-op on
  already-unmounted roots; we swallow any unmount throw so one
  misbehaving root does not strand the rest of the drain. The
  sub-cache walk has its own per-entry try/catch (see
  `dispose-frame-sub-caches!`).

  rf2-t0x90: also unmounts the singleton after-render DRIVER ROOT (if
  one was lazily armed) and clears its `set-tick` slot, so a torn-down
  adapter releases it and a subsequent `init!` re-arms a fresh one
  against the new adapter rather than bumping a stale setter."
  [{:keys [active-roots-cell warn-cache emitter-cell
           after-render-driver-root-cell after-render-set-tick-ref]}]
  (fn dispose-adapter! []
    ;; rf2-w1g0d2: the substrate-common subset (sub-cache walk + active-roots
    ;; drain-with-swallow + emitter clear) is the shared core; the React-hook
    ;; spine layers warn-cache + driver-root + set-tick teardown on top.
    (dispose-active-roots-and-caches! (fn [r] (.unmount r))
                                      active-roots-cell emitter-cell)
    (when warn-cache (reset! warn-cache #{}))
    (when after-render-driver-root-cell
      (when-let [root @after-render-driver-root-cell]
        (try (.unmount root) (catch :default _ nil)))
      (reset! after-render-driver-root-cell nil))
    (when after-render-set-tick-ref
      (reset! after-render-set-tick-ref nil))
    nil))

(defn make-hiccup-emitter-cell
  "Return a fresh `(atom nil)` cell that will hold the substrate's
  late-bound hiccup-emitter fn. Each adapter owns its own cell so
  multiple adapters can coexist in a test bundle without clobbering each
  other's emitter."
  []
  (atom nil))

(defn set-hiccup-emitter!
  "Install a render-tree → HTML fn into `emitter-cell`. Idempotent."
  [emitter-cell f]
  (reset! emitter-cell f))

(defn make-render-to-string
  "Return a `render-to-string` fn that reads its emitter from
  `emitter-cell`. Throws `:rf.error/no-hiccup-emitter-bound` if no
  emitter has been installed (the SSR artefact resolves the
  `:reagent/set-hiccup-emitter!` late-bind hook to install one)."
  [emitter-cell]
  (fn render-to-string [render-tree opts]
    (if-let [emit @emitter-cell]
      (emit render-tree opts)
      ;; EP-0015 (rf2-uwqale): carry an EP-0015-safe SUMMARY of the
      ;; render-tree, never the raw tree — a thrown render-to-string
      ;; ex-data is captured by SSR/static-export error handlers and
      ;; host logs before the record projector can classify it, and a
      ;; hiccup tree can carry app-owned sensitive/large values.
      (rf-error/throw-error!
        :rf.error/no-hiccup-emitter-bound
        'rf/render-to-string
        "require re-frame.ssr (the SSR ns-load resolves the :reagent/set-hiccup-emitter! late-bind hook automatically), or call set-hiccup-emitter! directly"
        {:extra {:render-tree/summary (rf-error/diag-value-summary render-tree)}}))))

;; ---- context provider — substrate-agnostic CORE ---------------------------
;;
;; Every React-shaped adapter shares the same React.createContext object
;; (in re-frame.adapter.context). The substrate-agnostic CORE is the
;; frame-resolution + element-build below; the user-facing COMPONENT
;; SHELL is NATIVE to each substrate (UIx `defui`, Helix `defnc`,
;; Reagent hiccup) and lives in the adapter ns.
;;
;; Seam placement (rf2-z7hfp). Earlier this ns shipped `frame-provider`
;; as a plain CLJS fn that destructured `{:keys [frame children]}`, and
;; each React-hook adapter RE-EXPORTED it as the component a user hands to
;; `$`. That put the abstraction seam BELOW the layer where each
;; substrate's element macro (`$` in Helix/UIx, hiccup in Reagent)
;; marshals props: Helix's `$` handed the fn a raw JS object with string
;; keys; UIx's `$` ALSO stringified keyword prop values (dropping the
;; namespace), so `:frame` silently fell to `:rf/default`. Each adapter
;; then carried a bespoke un-mangling wrapper to repair the props before
;; they reached the shared fn (helix rf2-9ok1s, uix rf2-8svnm) — a
;; standing per-substrate-patch hazard: a new substrate, or a new prop,
;; reopens the same class of bug.
;;
;; Move the seam UP (Mike-ruled C, rf2-z7hfp). The spine now provides
;; ONLY the substrate-agnostic core — `build-frame-provider-element`
;; (frame-resolution + element-build, touching no substrate prop-
;; marshalling). The COMPONENT SHELL sits ABOVE where `$` marshals: each
;; React-hook adapter defines its `frame-provider` as a NATIVE
;; substrate component (`defui` / `defnc`) that reads its props in that
;; substrate's OWN lossless idiom (UIx's `argv` channel, Helix's
;; `extract-cljs-props`), then hands a clean frame-kw + children to this
;; core. The prop-mangling class is impossible by construction — there is
;; no plain fn under `$` for the element macro to mangle, and no per-
;; substrate un-mangling patch to drift.

(defn build-frame-provider-element
  "Substrate-agnostic CORE of the frame-provider (rf2-z7hfp). Given a
  resolved frame keyword and a children value, returns the shared frame
  Context Provider React element scoping that frame to its subtree —
  inside the subtree, `(rf/frame-handle)` / `reg-view`-registered
  descendants resolve to the named frame. Per Spec 002 §What
  `frame-provider` is (CLJS reference).

  Frame-resolution: `frame-kw` is REQUIRED (EP-0002 carried invariant).
  There is NO `(or frame-kw :rf/default)` floor — per Spec 002 §Frame
  target resolution the runtime never synthesises a frame from absence.
  A native frame-provider shell (UIx `defui` / Helix `defnc`) that
  delegates here with a missing or `nil` `:frame` is a CONFIGURATION
  ERROR: this fn emits `:rf.error/no-frame-context` through the always-on
  error axis and throws, so a tooling-generated or hand-authored tree
  that elides the frame fails loudly at the provider rather than silently
  scoping every descendant call to a conventional default. This mirrors
  the Reagent-side `re-frame.views.provider/frame-provider` contract.

  Children-normalisation: the native trailing-`$`-children idiom
  (rf2-7kii2) hands this core whatever shape each substrate's element
  macro stashes on `:children` — a JS ARRAY for multiple trailing
  children (UIx's `(cljs.core/array …)`, Helix's `(into-array …)`), a
  SINGLE element for one trailing child (Helix), a CLJS vector/seq, or
  `nil` (no children). All four collapse to a flat positional arg list
  for `provider-element`: a JS array is spread via `array-seq`, an
  existing CLJS sequential is passed through, `nil` becomes no children,
  and any lone non-collection child is wrapped. React keys multi-child
  arrays correctly because they reach `createElement` as distinct
  positional args, not a single array child.

  This fn touches NO substrate prop-marshalling: the native component
  shell in each adapter has already read its props in the substrate's
  idiom and hands this core a clean CLJS frame-kw + children. That is the
  whole point of the moved seam — the marshalling-sensitive surface
  (`$`/hiccup → component) lives ABOVE this core, in substrate-native
  code, so a keyword frame-id survives intact on every substrate by
  construction."
  [frame-kw children]
  ;; rf2-9kpigo: reject a non-keyword, non-nil `:frame` BEFORE it reaches
  ;; React Context. A nil routes to `:rf.error/no-frame-context` (absence);
  ;; a non-keyword routes to the distinct `:rf.error/bad-frame-provider-arg`.
  ;; The native UIx/Helix shells read their props in the substrate idiom and
  ;; delegate the clean frame-kw here, so this is the single validating seam
  ;; for both function-component substrates (mirrors the Reagent-side
  ;; `re-frame.views.provider/frame-provider` contract).
  (frame/require-keyword-frame-provider-arg!
    frame-kw
    're-frame.substrate.spine/build-frame-provider-element)
  (apply adapter-context/provider-element
         frame-kw
         (adapter-context/normalize-children children)))

;; ---- render flush for tests ----------------------------------------------
;;
;; `flush-views!` wraps React's `act()` so test code can drive a
;; subscribe → re-render cycle synchronously. React 18 ships `act` in
;; `react-dom/test-utils`; React 19 promotes it onto the React namespace
;; proper. Probe both — without the fallback, users on React 18.x get a
;; silent no-op (subsumes rf2-jk7hr).

(defn- resolve-act-fn
  "Return React's act() if available, else nil. React 19 hosts act on
  the React namespace directly; React 18 hosts it on react-dom/test-utils.
  Mirrors the Reagent test harness's act-fn in
  `adapters/reagent/test/re_frame/frame_provider_context_cljs_test.cljs`."
  []
  (or (when (exists? (.-act React)) (.-act React))
      (try
        (let [tu (js/require "react-dom/test-utils")]
          (.-act tu))
        (catch :default _ nil))))

(defn flush-views!
  "Flush pending substrate renders synchronously. Wraps React's act() —
  intended for test code only. Calls (act f); with no arg, calls (act
  (fn [] nil)) to flush pending effects. Returns nil. No-op when act() is
  not reachable in the current React build."
  ([] (flush-views! (fn [] nil)))
  ([f]
   (when-let [act (resolve-act-fn)]
     (act f))
   nil))

;; ---- synchronous render flush (rf2-40a84) ---------------------------------
;;
;; `flush-render!` is the PRODUCTION-grade synchronous render-commit fn for
;; the substrate-adapter contract (distinct from `flush-views!`, which is a
;; test-only `act()` wrapper). It exists because the React-shaped substrates
;; schedule their re-renders through React's normal lane scheduler, whose
;; commit lands on a `requestAnimationFrame`-style tick that (a) fires AFTER
;; an eval'd `dispatch` returns and (b) is throttled to ~never in a
;; backgrounded / unfocused tab. So tooling that drives the view lifecycle
;; headless — the re-frame2-pair MCP `dispatch` → observe-the-DOM loop
;; (rf2-40a84 / consumed by rf2-vk79g's dispatch-and-settle) — cannot rely on
;; the scheduled commit ever arriving.
;;
;; `react-dom/flushSync` runs its callback and SYNCHRONOUSLY flushes every
;; React update scheduled inside it (and any already-pending work) to the
;; DOM before returning — it is NOT rAF-scheduled, so it is immune to the
;; backgrounded-tab throttle and fires even headless. After
;; `(flush-render! f)` returns, any state change `f` triggered (or any render
;; already pending) is committed; a caller can then read the settled DOM /
;; epoch. The 0-arity form flushes already-pending work with an empty
;; callback.
;;
;; This is the React-hook (UIx / Helix) spine impl; the Reagent / reagent-
;; slim family realises the same contract through `reagent.core/flush` (its
;; render-queue drain forces the component re-renders synchronously and, on
;; React 19, commits them via `flushSync`), wired in the ratom adapter.

(defn flush-render!
  "Synchronously flush pending React renders to the DOM via
  `react-dom/flushSync` (Spec 006 §`flush-render!`). The 1-arity form runs
  `f` inside `flushSync` so any state change `f` schedules commits before
  the call returns; the 0-arity form flushes already-pending work. Unlike
  `flush-views!` (a test-only `act()` wrapper) this is production-grade and
  NOT rAF-scheduled, so headless tooling can drive a `dispatch → flush-render!
  → observe-settled-DOM` loop even in a backgrounded tab (rf2-40a84). Returns
  nil. No-op-safe when there is nothing pending — `flushSync` with an empty
  callback is a cheap no-op."
  ([] (flush-render! (fn [] nil)))
  ([f]
   (react-dom/flushSync f)
   nil))

;; ---- source-coord wrapper (Spec 006 §Source-coord; rf2-z7f7 / rf2-z9n1) --
;;
;; Every React-shaped substrate adapter MUST inject
;; `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on each registered
;; view's root DOM element when `interop/debug-enabled?` is true. The
;; React-element-walking path uses `React.cloneElement` (rather than the
;; hiccup-walk path views.cljs takes) because React elements are opaque
;; — we can clone the root with the extra prop, but we cannot peek
;; inside a fragment / function-component head.
;;
;; Production-elision contract (rf2-z7f7 / Spec 009): the entire branch
;; sits inside `(when interop/debug-enabled? ...)` so the closure
;; compiler constant-folds the wrapper away under :advanced +
;; goog.DEBUG=false. Each adapter ships a bundle-grep elision test that
;; confirms the `data-rf2-source-coord` literal is absent from
;; production builds.

;; `format-source-coord` / `format-view-id` are the pure string projections
;; of the annotation attribute VALUES — shared with the Reagent hiccup walk
;; through the leaf `re-frame.adapter.context` so the React-element-clone
;; path here and the hiccup path in `re-frame.views.source-coord-annotation`
;; emit byte-identical `data-rf2-source-coord` / `data-rf-view` values
;; across substrates (rf2-t9s6p6). Aliased to the spine's historical names
;; so call sites in `make-wrap-view` are unchanged.
(def format-source-coord adapter-context/format-source-coord)
(def format-view-id       adapter-context/format-view-id)

(defn make-warn-once-cache
  "Return an `(atom #{})` for tracking per-id warn-once emission. Each
  adapter owns its own cache so multiple adapters can coexist in test
  bundles without clobbering each other's warn-once state."
  []
  (atom #{}))

(defn make-clear-warned-fn
  "Return a thunk that resets `cache-atom` to `#{}` and returns nil.
  Tests use this between cases (via `make-reset-runtime-fixture` and the
  chained `:adapter/clear-warn-once-caches!` hook) so a sibling test's
  first-encounter warning cannot silently swallow a later test's same-
  id warning."
  [cache-atom]
  (fn clear-warned-non-dom-roots! []
    (reset! cache-atom #{})
    nil))

(defn make-warn-non-dom-root-fn
  "Return a warn-once fn for use inside `inject-source-coord-attr`.
  Parameterised on the substrate-name string so the warning text
  attributes the host substrate. `cache-atom` is the per-adapter
  warn-once set."
  [cache-atom substrate-name]
  (fn warn-non-dom-root! [id type-tag]
    (when-not (contains? @cache-atom id)
      (swap! cache-atom conj id)
      (.warn js/console
        (adapter-context/non-dom-root-warning id type-tag substrate-name)))))

(defn- dom-element?
  "True if the React element's `type` is a string (a DOM tag like
  \"div\"). Function/class components and Fragments have non-string
  `type`s and are exempt per Spec 006."
  [react-element]
  (and react-element
       (some? (.-type react-element))
       (string? (.-type react-element))))

(defn- inject-source-coord-attr
  "Wrap `out` (the user component's React element output) with a
  cloneElement call that adds `data-rf2-source-coord` (Spec 006
  §Source-coord annotation, rf2-z7f7) and `data-rf-view` (Spec 006
  §View tagging contract, rf2-01il5). Non-element outputs (nil,
  fragment, function-component head) emit a one-shot warning per id
  and pass through unchanged — pair tools fall back to `:rf/id` for
  source-coord; the view-walker falls back to the Fiber-walker primary
  path for hierarchy capture.

  CRITICAL: cloneElement returns a new element with the SAME `type` and
  `key` slots — it does NOT wrap the original. Wrapping with a
  synthetic host element (the `[:div]` shape rejected by Spec 006
  §View tagging contract) would break flexbox / CSS Grid / table
  layouts / `:nth-child` selectors / positioning ancestors / stacking
  contexts / CSS containment.

  History: an earlier version also patched the JSX-shaped source-coord
  props (`_jsxFileName` / `_jsxLineNumber` / `_jsxColumnNumber`)
  intended for React DevTools' \"View source\" gesture (rf2-fa4ly).
  The feature never worked — DevTools reads `__source` from
  `React.createElement`'s third arg, not from element props — and the
  props leaked to the DOM as attributes, triggering React's
  \"unrecognised prop\" warnings. rf2-rohdn dropped the injection."
  [warn-fn id coord-attr view-attr out]
  (cond
    (dom-element? out)
    (let [props             (.-props out)
          existing-coord    (when props (aget props "data-rf2-source-coord"))
          existing-view     (when props (aget props "data-rf-view"))
          patch             #js {}]
      (when-not existing-coord
        (aset patch "data-rf2-source-coord" coord-attr))
      (when-not existing-view
        (aset patch "data-rf-view" view-attr))
      (if (and existing-coord existing-view)
        out
        (React/cloneElement out patch)))

    :else
    (do
      (when (some? out)
        (warn-fn id (some-> out .-type)))
      out)))

;; ---- view-unmount parity (rf2-te71r; follow-on from rf2-9hoos) ------------
;;
;; Phase-A (rf2-9hoos) added `:rf.view/unmounted`, fired via a per-render-
;; instance reaction-dispose hook armed in `re-frame.views`. That path
;; rides the Reagent family's tracked render reaction
;; (`componentWillUnmount` disposes the instance's tracked deps). The
;; React-hook substrates (UIx / Helix) run the same `views.cljs`
;; frame-aware-view wrapper inside a function component with NO tracked
;; render reaction (they intentionally don't publish
;; `:adapter/make-reaction`, so `interop/make-reaction` returns nil and
;; the views-side arm no-ops). This spine seam restores parity: the
;; React-hook wrap-view arms a `React.useEffect` empty-deps cleanup that
;; emits `:rf.view/unmounted` on instance teardown.
;;
;; Render-key threading. On the Reagent family the instance-token is
;; cached on the component object so the render-key
;; (`[view-id instance-token]`) is stable across re-renders. The React-
;; hook spine has no such per-instance object the views-side token-mint
;; can latch onto (`provider/reagent-component-token` mints a FRESH token
;; every render under UIx/Helix because no `:adapter/current-component` is
;; published), so this seam mints its OWN stable per-instance token into a
;; `useRef` — `[view-id <stable-token>]` is a well-formed render-key tuple
;; whose token survives re-renders and matches the render whose teardown
;; it marks. The required `:rf.view/unmounted` tags (`:view-id`, `:frame`)
;; carry the values resolved in-render; `:render-key` carries the stable
;; per-instance tuple.
;;
;; Production elision. The whole arm sits inside `interop/debug-enabled?`
;; — under :advanced + goog.DEBUG=false the wrap collapses to the bare
;; `user-fn` (no hooks, no emit), so the `rf.view/unmounted` sentinel
;; (already present from phase-A) stays absent in prod bundles.

(def ^:private unmount-instance-counter
  "Process-wide monotonic counter for stable per-instance render-key
  tokens minted by the React-hook wrap-view's unmount sentinel
  (rf2-te71r). Dev-only — the only call site sits inside the sentinel
  component, which only runs when React renders it under
  `interop/debug-enabled?`, so this and its `swap!` DCE in production
  builds. Distinct from the views-side `provider/instance-counter` (the
  spine carries no spine→views dependency edge); both are in-run
  discriminators with no cross-run correlation guarantee."
  (atom 0))

(defn- emit-view-unmounted-via-hook!
  "Fire `:rf.view/unmounted` for `render-key` in `frame-id` through the
  `:views/emit-view-unmounted!` late-bind hook (published by
  `re-frame.views`, rf2-te71r). Reaching the emit through late-bind keeps
  the spine (core/substrate) free of a static require on the CLJS-only
  views ns. No-op when the hook is unresolved (views not on the
  classpath) or when `interop/debug-enabled?` is false. The views-side
  impl is itself gated on `interop/debug-enabled?`."
  [view-id render-key frame-id]
  (when interop/debug-enabled?
    (when-let [emit! (late-bind/get-fn-cached :views/emit-view-unmounted!)]
      (emit! view-id render-key frame-id))))

(defn make-unmount-sentinel
  "Build the per-view unmount-sentinel React function component
  (rf2-te71r). The sentinel renders no DOM (returns nil) and arms a
  `React.useEffect` empty-deps cleanup that emits `:rf.view/unmounted` on
  its instance teardown — the React-hook parity for the Reagent family's
  phase-A (rf2-9hoos) reaction-dispose unmount hook.

  Why a sibling SENTINEL rather than hooks inline in wrap-view's wrapped
  fn. A registered view's wrapper (`(rf/view id)`) is also INVOKED
  DIRECTLY (headless, no React render) — the suite's render-trace tests do
  `((rf/view id))`. Calling `React.useRef` / `useEffect` there throws
  ('hooks can only be called inside a function component'). Routing the
  hooks through a sentinel that wrap-view emits as a sibling ELEMENT means
  a direct invocation merely builds an element object (no hook execution),
  while a real React mount renders the sentinel and runs its hooks — the
  same safety the after-render sentinel relies on.

  Props (passed by wrap-view via `React/createElement`):
    :view-id  the registered view id (the `:view-id` tag + render-key head)
    :frame    the frame resolved at wrap-view render time (the `:frame`
              tag; captured in a ref so the cleanup, which runs outside
              React render, reports the frame the instance rendered under)

  Stable per-instance token: minted once into a `useRef` so the
  `:render-key` tuple `[view-id <token>]` survives re-renders and matches
  the render whose teardown it marks. Empty-deps `#js []` → the effect's
  cleanup runs exactly once on unmount (one-shot, matching the Reagent
  path's reaction-dispose semantics)."
  []
  (fn unmount-sentinel [^js props]
    (let [view-id    (.-viewId props)
          frame-id   (.-frame props)
          token-ref  (React/useRef nil)
          token      (or (.-current token-ref)
                         (let [t (swap! unmount-instance-counter inc)]
                           (set! (.-current token-ref) t)
                           t))
          render-key [view-id token]
          ;; Capture the frame in a ref so a frame change across re-renders
          ;; (rare for a mounted instance) still has the cleanup report the
          ;; last-rendered frame rather than a stale closure value.
          frame-ref  (React/useRef nil)]
      (set! (.-current frame-ref) frame-id)
      (React/useEffect
        (fn unmount-arm-effect []
          (fn cleanup []
            (emit-view-unmounted-via-hook! view-id render-key
                                           (.-current frame-ref))))
        #js [])
      nil)))

(def ^:private void-dom-tags
  "HTML5 void elements — self-closing, MUST NOT receive children. React
  raises a void-element error (and SSR/hydration breaks) if `input`,
  `img`, `br`, … are given a child. The set is fixed in HTML5 (no
  maintenance burden).

  Lockstep with `reagent2.impl.template/void-tags` and
  `re-frame.ssr.emit/void-elements` (same membership, keyword vs string
  shapes). Bundle isolation forbids `:require` across artefacts (core
  must not reach into the adapters or the SSR artefact), so the set is
  duplicated by intent. If HTML5 ever extends the void-element list
  (extraordinarily unlikely), update every copy."
  #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link"
    "meta" "param" "source" "track" "wbr"})

(defn- void-dom-root?
  "True when `react-element` is a void HTML DOM element (string `type`
  in `void-dom-tags`) — React rejects children on such roots, so the
  unmount sentinel must ride as a SIBLING (in a Fragment) rather than a
  child. Function/class components and Fragments have non-string types
  and are never void."
  [react-element]
  (let [t (some-> ^js react-element .-type)]
    (and (string? t) (contains? void-dom-tags t))))

(defn- append-unmount-sentinel
  "Attach an unmount-sentinel to `annotated` (the source-coord /
  view-id-annotated user output) so the view instance fires
  `:rf.view/unmounted` on teardown (rf2-te71r). Two shapes, keyed on
  whether the user's root is a VOID DOM element:

  - Non-void root (the dominant case): `cloneElement` with a trailing
    extra CHILD. This preserves the root's `type`, `key`, and existing
    props/children — the inspected output is still the user's annotated
    root element, NOT a Fragment wrapper, so the source-coord + view-id
    contract and the layout-critical no-wrapper guarantee both hold.

  - Void root (`input` / `img` / `br` / …, rf2-ghfkkk): React rejects
    children on void elements (it raises a void-element error and breaks
    hydration), so the sentinel CANNOT be a child. Instead, return a
    `React.Fragment` holding the user's annotated root element UNCHANGED
    (its `data-rf2-source-coord` / `data-rf-view` attrs intact) plus the
    sentinel as a SIBLING. A Fragment renders no wrapper DOM node, so the
    committed tree's DOM semantics are stable — the void element stays a
    direct child of its real parent, with no synthetic host element. The
    inspected root is still the user's annotated void element; only the
    sentinel's sibling position changes. This keeps a valid registered
    view returning a void root valid under dev-mode instrumentation
    (pre-fix it became invalid, producing React void-element errors that
    vanished in production).

  The sentinel renders no DOM in BOTH shapes. On a headless direct
  invocation of the wrapped fn (the suite's render-trace tests call
  `((rf/view id))`) this merely builds an element object whose sentinel
  hooks never run; only a real React mount renders the sentinel and arms
  its useEffect cleanup.

  Non-element / nil output (a view returning a string or nil) has no
  mountable root instance — pass it through unchanged (no unmount arm,
  consistent with such a view having nothing to tear down)."
  [unmount-sentinel id frame-id annotated]
  (cond
    (or (nil? annotated) (nil? (.-type ^js annotated)))
    annotated

    (void-dom-root? annotated)
    ;; Void root — sentinel rides as a SIBLING inside a Fragment so the
    ;; user's void element receives NO children. The annotated root is
    ;; forwarded unchanged (source-coord / data-rf-view attrs intact).
    (let [sentinel-el (React/createElement unmount-sentinel
                                           #js {:viewId id :frame frame-id})]
      (React/createElement (.-Fragment React) nil annotated sentinel-el))

    :else
    ;; Non-void root — append the sentinel as a CHILD (no Fragment wrap,
    ;; so layout-critical positioning / flexbox / grid / :nth-child stay
    ;; intact).
    (let [sentinel-el (React/createElement unmount-sentinel
                                           #js {:viewId id :frame frame-id})
          existing    (some-> ^js annotated .-props .-children)
          ;; cloneElement's variadic children REPLACE the original
          ;; `props.children`, so the original children must be carried
          ;; forward explicitly. `existing` is nil (no children), a single
          ;; child, or a JS array — normalise to a flat arg list with the
          ;; sentinel appended last.
          children    (cond
                        (nil? existing)   #js [sentinel-el]
                        (array? existing) (.concat existing #js [sentinel-el])
                        :else             #js [existing sentinel-el])]
      (.apply React/cloneElement nil
              (.concat #js [annotated nil] children)))))

(defn make-wrap-view
  "Return a `wrap-view` fn parameterised on the substrate's per-adapter
  `warn-fn` (typically built via `make-warn-non-dom-root-fn`). The
  returned fn has the standard 3-arg shape `(id metadata user-fn) ->
  wrapped-user-fn` and produces a function component that injects
  both `data-rf2-source-coord` (Spec 006 §Source-coord annotation) and
  `data-rf-view` (Spec 006 §View tagging contract) on the rendered
  root DOM element, AND appends a no-DOM unmount-sentinel child so the
  view instance fires `:rf.view/unmounted` on teardown (rf2-te71r —
  React-hook parity for the phase-A reaction-dispose unmount hook), all
  when `interop/debug-enabled?` is true. Production builds elide via
  `interop/debug-enabled?` per Spec 009 §Production builds.

  The sentinel is appended as a CHILD (via `cloneElement`) rather than
  hooks called inline in the wrapped fn: the wrapped fn is also INVOKED
  HEADLESS (`((rf/view id))` in the render-trace tests), where calling
  React hooks throws. Building the sentinel as an element defers its hook
  execution to a real React render — the same safety the after-render
  sentinel relies on. Appending preserves the root's `type` / `key` /
  props, so the source-coord + view-id annotation contract is unchanged."
  [warn-fn]
  (let [unmount-sentinel (make-unmount-sentinel)]
    (fn wrap-view [id metadata user-fn]
      (if interop/debug-enabled?
        (let [coord-attr (format-source-coord id metadata)
              view-attr  (format-view-id id)
              wrapped    (fn wrapped-user-fn [& args]
                           ;; rf2-te71r: resolve the frame in-render (the substrate-
                           ;; portable React-context read works inside this wrapped fn's
                           ;; render). The sentinel's cleanup runs OUTSIDE render where
                           ;; the read would be wrong, so the frame is threaded as a prop
                           ;; and the sentinel stashes it in a ref. On a headless direct
                           ;; invocation this read falls through the dynamic-var /
                           ;; :rf/default chain — harmless; the sentinel ELEMENT is built
                           ;; but its hooks only run if React actually renders it.
                           (let [frame-id  (adapter-context/function-component-current-frame)
                                 out       (apply user-fn args)
                                 annotated (inject-source-coord-attr warn-fn id coord-attr
                                                                     view-attr out)]
                             (append-unmount-sentinel unmount-sentinel id frame-id annotated)))]
          ;; rf2-fa4ly: stamp the React `displayName` to the registered view-id
          ;; so React DevTools shows `<:cart/total-line>` in the component tree
          ;; rather than the CLJS-munged fn name or an anonymous wrapper. The
          ;; assignment sits inside the `interop/debug-enabled?` arm so the
          ;; string-literal id `(str id)` and the assignment itself elide in
          ;; production builds.
          (set! (.-displayName ^js wrapped) (str id))
          wrapped)
        user-fn))))

(defn install-clear-warn-once-step!
  "Wire `clear-fn` into the chained `:adapter/clear-warn-once-caches!`
  late-bind hook. The hook is chained — each adapter and re-frame.views
  contribute a clear-step; `make-reset-runtime-fixture` invokes the top of
  the chain and every contributor's reset runs.

  Delegates to the canonical governance chokepoint
  `late-bind/register-warn-once-clear-fn!` (rf2-z79p8) so the cache is
  BOTH chained AND enrolled in the warn-once-clear governance registry the
  governance assertion checks. Callers don't need to know the chain key.

  Two arities:
    [clear-fn]            — enrol with a default label and no arm/armed?
                            probes (the empirical arm/fire assertion skips
                            it; the source-enumeration assertion still
                            covers it).
    [clear-fn governance] — pass `{:label :arm :armed?}` so the empirical
                            governance assertion can arm the cache, fire
                            the chain, and prove the cache was wiped. The
                            React-hook spine threads its `warn-cache` atom
                            in via this arity."
  ([clear-fn]
   (install-clear-warn-once-step! clear-fn {:label :adapter/warned-non-dom-roots}))
  ([clear-fn governance]
   (late-bind/register-warn-once-clear-fn!
     (assoc governance :clear-fn clear-fn))))

;; ---- subscription hook ----------------------------------------------------
;;
;; `use-subscribe` is the substrate-idiomatic hook surface for reading
;; a sub. It wraps React.useSyncExternalStore so updates are scheduled
;; by React's concurrent renderer rather than a per-component scheduler.
;;
;; The hook:
;;   1. Resolves the active frame via use-context (Decision 2).
;;   2. Calls re-frame.subs/subscribe to build/cache the reaction.
;;   3. Wires useSyncExternalStore — the snapshot is the deref of the
;;      reaction; subscribe is add-watch on the underlying container.
;;   4. On unmount the watch is removed and the sub's ref-count
;;      decrements; ref-count → 0 disposes synchronously
;;      (per Spec 006 §reference-counting-and-disposal, rf2-cmfln).
;;
;; Hook fns (`use-memo`, `use-callback`, `use-context`) differ between
;; substrates by their deps-array convention — UIx accepts CLJS vectors,
;; Helix wants JS arrays via `helix-hooks/use-memo*`. The factory below
;; takes the hook fns as args so each adapter can supply the right pair.
;; The hook fns supplied MUST already be the "wants-JS-array" variants
;; for substrates that need them (e.g. `helix-hooks/use-memo*`); the
;; spine passes the deps as a JS array always.

(defn make-react-spine
  "Build the per-substrate hook-shaped surfaces given the substrate's
  config:

      :substrate-name  — string used in warn-non-dom-root text (\"UIx\",
                         \"Helix\", …)
      :gensym-prefix-sub
      :gensym-prefix-derived
      :gensym-prefix-use-sub
                       — gensym prefix strings per surface
      :use-memo        — (fn [thunk js-deps]) returning the memoised
                         value
      :use-callback    — (fn [thunk js-deps]) returning the memoised
                         fn
      :use-context     — (fn [context]) returning the context value

  Returns a map of surfaces:

      {:make-state-container       …
       :read-container             …
       :replace-container!         …
       :subscribe-container        …
       :make-derived-value         …
       :render                     …
       :render-to-string           …
       :dispose-adapter!           …
       :set-hiccup-emitter!        …
       :use-current-frame          …
       :use-subscribe              …
       :flush-views!               …
       :flush-render!              …
       :wrap-view                  …
       :clear-warned-non-dom-roots! …}

  Note (rf2-z7hfp): the spine no longer produces `:frame-provider` or
  `:register-context-provider`. The user-facing frame-provider is a
  NATIVE substrate component (`defui` / `defnc`) defined in the adapter
  ns above where each substrate's element macro marshals props; the
  adapter passes that component into `make-react-adapter` as
  `:frame-provider`, and the spine wires it into the
  `:register-context-provider` substrate slot. The shared substrate-
  agnostic core is `build-frame-provider-element`, which the native
  component shell calls with clean props."
  [{:keys [substrate-name
           gensym-prefix-sub
           gensym-prefix-derived
           gensym-prefix-use-sub
           use-memo
           use-callback
           use-context]}]
  (let [warn-cache         (make-warn-once-cache)
        clear-warned       (make-clear-warned-fn warn-cache)
        warn-fn            (make-warn-non-dom-root-fn warn-cache substrate-name)
        emitter-cell       (make-hiccup-emitter-cell)
        active-roots-cell  (make-active-roots-cell)
        ;; rf2-334d9: after-render queue + sentinel component + the
        ;; routed-hook impl. The adapter publishes the hook by passing
        ;; `:after-render-hook` to `substrate-adapter/route-hook!`.
        after-render-queue-cell       (make-after-render-queue-cell)
        after-render-set-tick-ref     (make-after-render-set-tick-ref)
        ;; rf2-t0x90: holds the lazily-mounted singleton driver root so
        ;; native-mount apps still get post-commit after-render timing.
        after-render-driver-root-cell (make-after-render-driver-root-cell)
        after-render-sentinel      (make-after-render-sentinel
                                     after-render-queue-cell
                                     after-render-set-tick-ref)
        after-render-hook          (make-after-render-hook
                                     after-render-queue-cell
                                     after-render-set-tick-ref
                                     after-render-sentinel
                                     after-render-driver-root-cell)
        subscribe-cont     (make-subscribe-container gensym-prefix-sub)
        ;; rf2-i21f5: one epoch scheduler per adapter, shared by
        ;; `replace-container!` and every `make-derived-value`, so a
        ;; multi-input derived value recomputes glitch-free and notifies
        ;; once per coherent app-db epoch (Spec 006 §Invalidation
        ;; algorithm). See the epoch-scheduler section above.
        scheduler          (make-scheduler)
        replace-cont!      (make-replace-container-fn scheduler)
        make-derived       (make-derived-value-fn gensym-prefix-derived scheduler)
        ;; Precompute the `use-subscribe` watch-key keyword namespace
        ;; once (outside the render hot path). Each `subscribe-fn`
        ;; invocation mints a UNIQUE per-call suffix under this namespace
        ;; (see the subscribe-fn closure below) so sibling subscribers to
        ;; the same cached reaction never collide on the same `add-watch`
        ;; key. The closure runs once per reaction-identity change (it is
        ;; `use-callback`-memoized on `[reaction]`), so the per-call
        ;; keyword is not paid per render.
        use-sub-watch-ns   (let [s gensym-prefix-use-sub
                                 n (count s)]
                             ;; Strip the trailing "-" the gensym prefix
                             ;; carried so the keyword namespace reads
                             ;; cleanly (`:rf-uix-use-sub/<hash>`).
                             (if (and (pos? n) (= "-" (subs s (dec n))))
                               (subs s 0 (dec n))
                               s))
        wrap-view-fn       (make-wrap-view warn-fn)
        render-fn          (make-render active-roots-cell after-render-sentinel)
        dispose-fn         (make-dispose-adapter!
                             {:active-roots-cell active-roots-cell
                              :warn-cache        warn-cache
                              :emitter-cell      emitter-cell
                              ;; rf2-t0x90: release the singleton
                              ;; after-render driver root + clear its
                              ;; set-tick slot so a fresh init! re-arms.
                              :after-render-driver-root-cell after-render-driver-root-cell
                              :after-render-set-tick-ref     after-render-set-tick-ref})
        use-current-frame
        (fn use-current-frame []
          (use-context adapter-context/frame-context))
        ;; Two-arity body extracted so the 1-arg arm can call into it
        ;; without a self-reference on the let-bound `use-subscribe`
        ;; (CLJS let-bound fns cannot name themselves).
        ;;
        ;; ---- stable-key derivation (rf2-mwft2) -------------------------------
        ;;
        ;; React's deps comparison is `Object.is` (≈ `===`). Both
        ;; `frame-kw` (a CLJS keyword) and `query-v` (a CLJS persistent
        ;; vector) are value-equal across renders for the same logical
        ;; subscribe call but produce *fresh JS objects* per render —
        ;; keyword literals compile to `new cljs.core.Keyword(...)` in
        ;; the render body, vector literals to `new
        ;; cljs.core.PersistentVector(...)`, so neither survives the
        ;; render boundary by identity even though both survive by `=`.
        ;; The deps array `#js [frame-kw query-v]` therefore mismatches
        ;; every render and useMemo / useCallback / useEffect re-fire
        ;; their factories — driving cache-hit `subs/subscribe`,
        ;; watch add/remove, and cache-entry ref-count churn even when
        ;; the subscription is unchanged.
        ;;
        ;; Fix: hold the previous `[frame-kw query-v]` tuple in a
        ;; `useRef`. Each render we compare the incoming tuple to
        ;; `ref.current` by CLJS `=`. If equal, we read the stored
        ;; tuple's components back, returning JS-ref-stable elements
        ;; for the deps array. If not equal, we update the ref to the
        ;; new tuple. Writing to a ref during render is sanctioned by
        ;; React for exactly this memo-by-value pattern — the write is
        ;; idempotent given identical inputs and never mutates after a
        ;; commit.
        ;;
        ;; The bead (rf2-mwft2) flagged `(hash [frame-kw query-v])` as
        ;; the simpler candidate. We chose `useRef` + `=` over `hash`
        ;; because Murmur3 collisions, however rare, would have
        ;; useMemo return the wrong reaction for a colliding (frame,
        ;; query) pair — a silent correctness bug. The `useRef` path
        ;; has no false-positive equality and stays cheap (one extra
        ;; ref, one allocation-free `=` compare per render).
        use-subscribe-2
        (fn use-subscribe-2 [frame-kw query-v]
          (let [key-ref (React/useRef nil)
                ;; Holds the DURABLE committed reaction (rf2-sqhjtu). The
                ;; render-phase `reaction` handle below is a balanced,
                ;; net-zero subscribe/unsubscribe round-trip whose value
                ;; equals the committed one but whose IDENTITY can differ on
                ;; first mount: with no prior cache entry the round-trip's
                ;; 1 → 0 unsubscribe DISPOSES that handle (evicts it, removes
                ;; its source watches), and `subscribe-fn` then rebuilds a
                ;; fresh committed reaction post-commit. A disposed reaction
                ;; still recomputes on `-deref` (pull-based), so reading it
                ;; from `get-snap` LOOKS correct for ordinary app-db updates
                ;; — but it no longer holds source watches and, crucially,
                ;; still closes over the OLD sub body. On sub re-registration
                ;; / hot-reload the cache rebuilds the committed reaction with
                ;; the v2 body while a `get-snap` pinned to the disposed v1
                ;; handle keeps rendering v1 output. React's
                ;; `useSyncExternalStore` contract requires `getSnapshot` to
                ;; read a stable, LIVE source — so `get-snap` reads the
                ;; committed reaction stored here once `subscribe-fn` has run
                ;; (post-commit), falling back to the render-phase handle only
                ;; for the very first pre-commit snapshot.
                committed-ref (React/useRef nil)
                stable-key
                (let [prev (.-current key-ref)
                      new-key #js [frame-kw query-v]]
                  (if (and prev
                           (= (aget prev 0) frame-kw)
                           (= (aget prev 1) query-v))
                    prev
                    (do (set! (.-current key-ref) new-key)
                        new-key)))
                ;; Destructure the stable tuple's components so the
                ;; downstream call sites see JS-ref-stable values for
                ;; same-by-= subsequent renders.
                stable-frame-kw (aget stable-key 0)
                stable-query-v  (aget stable-key 1)
                ;; ---- commit-deferred ref-count acquisition (rf2-es09qq) -------
                ;;
                ;; THE INVARIANT: a render that never commits MUST NOT retain a
                ;; sub-cache ref-count. The earlier design (rf2-879fe ledger) put
                ;; the durable `subs/subscribe` (+1) in the render-phase `useMemo`
                ;; factory and reclaimed it from commit-owned effects. That is
                ;; unsound for a FIRST-MOUNT render abandoned before commit
                ;; (Suspense / concurrent interrupt): React discards the whole
                ;; fiber — its `useRef` ledger AND its never-run effects — so the
                ;; render-phase +1 is pinned in the GLOBAL sub-cache forever with
                ;; no owning component. The ledger only ever healed a render whose
                ;; fiber LATER committed; a discarded first-mount fiber never gets
                ;; that reconcile pass.
                ;;
                ;; FIX — the durable acquire/release lives ONLY in commit-owned
                ;; hooks (`useSyncExternalStore`'s subscribe callback, run after
                ;; commit; its cleanup run on unmount / subscribe-identity change
                ;; / teardown). React NEVER calls that callback for a render that
                ;; doesn't commit, so an abandoned render acquires NOTHING — the
                ;; leak is gone BY CONSTRUCTION, independent of fiber discard.
                ;;
                ;; The render phase still needs a reaction HANDLE to read a
                ;; snapshot for `useSyncExternalStore`. It obtains one with a
                ;; BALANCED, net-zero round-trip — `subs/subscribe` immediately
                ;; followed by `subs/unsubscribe` — so the render contributes ZERO
                ;; outstanding ref-count whether or not it commits.
                ;;
                ;; In the COMMITTED steady state this round-trip is free of
                ;; dispose churn: the `subscribe-fn` below holds a durable +1, so
                ;; the render-phase subscribe bumps to 2 and the immediate
                ;; unsubscribe drops back to 1 — never crossing the 1 → 0 disposal
                ;; edge. The handle is the live cached reaction. The ONLY render
                ;; with no durable backing is the FIRST mount before commit (and
                ;; any never-committed render): there the round-trip disposes +
                ;; rebuilds, but per rf2-cmfln the rebuilt value `=` the disposed
                ;; one so `useSyncExternalStore` observes no tear — and that
                ;; render leaves the cache exactly as it found it.
                ;;
                ;; This subsumes the two prior leak triggers without a ledger or
                ;; any reconcile/release effects:
                ;;   • rf2-879fe (abandoned-before-commit) — render is net-zero;
                ;;     the commit-owned acquire never ran. No leak.
                ;;   • rf2-8u8tx.2 (useMemo perf-discard recompute on unchanged
                ;;     deps) — each factory re-run is its own balanced round-trip,
                ;;     so a discarded+rebuilt memo nets zero regardless of how many
                ;;     times React re-runs it. No climb.
                ;; Per Spec 006 §Reference counting and disposal (rf2-cmfln).
                reaction
                (use-memo (fn []
                            ;; Net-zero render-phase fetch of the reaction handle:
                            ;; subscribe to read the cached reaction, then release
                            ;; immediately so the render retains no ref-count. An
                            ;; abandoned render leaks nothing; a committed render's
                            ;; durable ref is taken later in `subscribe-fn`.
                            (let [r (subs/subscribe stable-frame-kw stable-query-v)]
                              (subs/unsubscribe stable-frame-kw stable-query-v)
                              r))
                          #js [stable-key])
                ;; The store-snapshot fn React calls on every render to
                ;; detect tearing. Pure deref of the LIVE committed reaction.
                ;;
                ;; rf2-sqhjtu: prefer the durable committed reaction stored in
                ;; `committed-ref` by `subscribe-fn` (set post-commit, cleared
                ;; on teardown). The render-phase `reaction` handle is only the
                ;; fallback for the FIRST pre-commit snapshot — before React has
                ;; run `subscribe-fn`, `committed-ref` is still nil and the
                ;; balanced render-phase handle is the only thing to read. Once
                ;; committed, `get-snap` tracks the live cached reaction (the
                ;; one carrying source watches and the current sub body), never
                ;; a disposed first-render handle. The committed reaction's
                ;; value `=` the render-phase one (rf2-cmfln), so the source
                ;; swap is tear-free.
                ;;
                ;; Deps include `reaction` so the pre-commit fallback never
                ;; closes over a stale (perf-discarded) render-phase handle;
                ;; once `committed-ref` is populated post-commit, `get-snap`'s
                ;; identity is irrelevant to correctness — React drives tear
                ;; detection off the returned VALUE, and the committed source
                ;; is read through the ref on every call.
                get-snap
                (use-callback (fn []
                                (let [r (or (.-current committed-ref) reaction)]
                                  (when r @r)))
                              #js [stable-key reaction])
                ;; The store-subscribe fn — React's COMMIT-OWNED acquire/release
                ;; pair. React calls it (once) only AFTER a commit, passing a
                ;; force-update callback; its returned cleanup runs on unmount,
                ;; on a subscribe-identity change, and on teardown. This is where
                ;; the DURABLE sub-cache ref-count is taken (`subs/subscribe`) and
                ;; released (`subs/unsubscribe`) — never in render — so a render
                ;; abandoned before commit acquires no ref. We re-subscribe by
                ;; (frame, query) here rather than trust the render-phase handle's
                ;; ref-count (which was balanced to zero).
                ;;
                ;; MEMOIZED ON `[stable-key]`, NOT `[reaction]` (rf2-es09qq). The
                ;; render-phase handle object can differ from the committed one
                ;; on first mount (the balanced round-trip may dispose + rebuild
                ;; the reaction before `subscribe-fn` re-acquires it), so keying
                ;; on `reaction` would change subscribe-fn identity right after
                ;; the first commit — forcing React to release (dispose) and
                ;; re-acquire the durable ref every time the handle churned.
                ;; `stable-key` is identity-stable for a fixed (frame, query), so
                ;; React calls subscribe-fn exactly ONCE per subscription target:
                ;; one durable acquire, one release, no churn. The watch is added
                ;; on the freshly-acquired `committed` reaction inside, so it
                ;; always tracks the live cached reaction regardless of the
                ;; render-phase handle's identity.
                subscribe-fn
                (use-callback
                  (fn [on-change]
                    ;; Take the durable committed ref now (post-commit). This is
                    ;; the ONLY place a lasting +1 is acquired. The returned
                    ;; reaction is the live cached one and `=` (often identical)
                    ;; to the render-phase handle `reaction`.
                    (let [committed (subs/subscribe stable-frame-kw stable-query-v)]
                      ;; rf2-sqhjtu: publish the durable committed reaction so
                      ;; `get-snap` derefs THIS live handle (source watches +
                      ;; current sub body) rather than the disposed render-phase
                      ;; one. Set post-commit (here), cleared on teardown below.
                      (set! (.-current committed-ref) committed)
                      ;; UNIQUE watch key per `subscribe-fn` INVOCATION,
                      ;; closed over by the returned cleanup. The key MUST
                      ;; NOT derive from `(hash reaction)`: subscriptions are
                      ;; cached/deduped by query, so sibling UIx/Helix
                      ;; components reading the SAME query share the SAME
                      ;; cached reaction. A hash-of-reaction key would be
                      ;; IDENTICAL across those siblings, and `add-watch`
                      ;; replaces an existing watcher with the same key — so
                      ;; the last-mounted sibling's `on-change` would silently
                      ;; overwrite every earlier sibling's `useSyncExternalStore`
                      ;; callback, leaving the earlier ones rendering stale UI
                      ;; until an unrelated parent render refreshed them.
                      ;; `subscribe-fn` is `use-callback`-memoized on
                      ;; `[stable-key]`, so React calls it once per subscription
                      ;; target (NOT per render); a fresh keyword per call is
                      ;; cheap and collision-free.
                      (let [k (keyword use-sub-watch-ns (str (gensym "watch-")))]
                        (when committed
                          (add-watch committed k (fn [_ _ _ _] (on-change))))
                        (fn unsubscribe []
                          (when committed (remove-watch committed k))
                          ;; rf2-sqhjtu: clear the published committed reaction,
                          ;; but ONLY if it still points at THIS invocation's
                          ;; handle. A later `subscribe-fn` re-acquire (e.g. a
                          ;; subscribe-identity change) may have already
                          ;; overwritten `committed-ref` with the NEW committed
                          ;; reaction before this older cleanup runs; clobbering
                          ;; it to nil would strand `get-snap` on the fallback.
                          (when (identical? (.-current committed-ref) committed)
                            (set! (.-current committed-ref) nil))
                          ;; Release the durable committed ref — symmetric with
                          ;; the `subs/subscribe` above. Runs on unmount /
                          ;; key change / teardown.
                          (subs/unsubscribe stable-frame-kw stable-query-v)))))
                  #js [stable-key])]
            (React/useSyncExternalStore subscribe-fn get-snap get-snap)))
        use-subscribe
        (fn use-subscribe
          ;; ---- 1-arg ambient form — full frame-resolution chain (rf2-4mi2zj) ----
          ;;
          ;; The ambient `(use-subscribe [:q …])` form MUST resolve the
          ;; frame through the SAME carried-invariant chain `subs/subscribe`'s
          ;; own 1-arity uses (Spec 006 §Frame resolution (1-arg form), :734,
          ;; :1058; EP-0002): dynamic-var tier (`frame/*current-frame*`, set by
          ;; `with-frame` / `frame-bound-fn`) FIRST, the React-context tier
          ;; (the surrounding `frame-provider`) SECOND, and **nil → a loud
          ;; `:rf.error/no-frame-context`** with NO `:rf/default` floor.
          ;;
          ;; The earlier shortcut `(use-subscribe-2 (use-current-frame) …)`
          ;; bypassed that chain in two correctness-breaking ways:
          ;;
          ;;   1. `use-current-frame` is the NARROW raw `use-context` read
          ;;      (React-context tier ONLY — it never consults the dynamic
          ;;      var). Passing its result straight into the 2-arg EXPLICIT
          ;;      path let a surrounding provider beat a `with-frame` /
          ;;      `frame-bound-fn` dynamic scope — inverting the spec's tier
          ;;      precedence (dynamic-var MUST win).
          ;;   2. With no enclosing provider, `use-context` returns the
          ;;      no-provider sentinel (`:rf.frame/no-provider`), NOT nil. The
          ;;      explicit 2-arg path then subscribed against that sentinel as
          ;;      a literal frame id — surfacing a bad-/destroyed-frame path
          ;;      instead of the specified `:rf.error/no-frame-context`.
          ;;
          ;; Fix: still CALL `use-current-frame` (the `use-context` hook) so
          ;; the component stays subscribed to provider-value changes and
          ;; re-renders when the surrounding `frame-provider` swaps frames —
          ;; a hook-safe, unconditional top-of-body call — but DISCARD its raw
          ;; value for resolution. Resolve the real frame via
          ;; `frame/require-current-frame!`, which delegates to
          ;; `resolve-current-frame` → the live `:adapter/current-frame`
          ;; late-bind hook (`function-component-current-frame`: dynamic-var →
          ;; `_currentValue` with sentinel→nil and corrupted-value detection)
          ;; and emits + throws `:rf.error/no-frame-context` on nil. This
          ;; single-sources resolution with `subs/subscribe`'s 1-arity — the
          ;; hook and the imperative read can never diverge — and then hands
          ;; the now-EXPLICIT resolved frame to the 2-arg path. The 2-arg
          ;; EXPLICIT form is unchanged (it bypasses the chain by design).
          ([query-v]
           ;; Hook subscription to provider-value changes (re-render). The
           ;; returned sentinel/keyword is intentionally NOT used as the
           ;; frame — resolution runs through the chain below.
           (use-current-frame)
           (use-subscribe-2
             (frame/require-current-frame!
               :subscribe
               {:where    're-frame.substrate.spine/use-subscribe
                :event-id (first query-v)})
             query-v))
          ([frame-kw query-v] (use-subscribe-2 frame-kw query-v)))]
    ;; rf2-6id3el: the return map exposes ONLY the surfaces the adapter
    ;; assembler consumes. `:warn-cache` is read by `make-react-adapter`
    ;; (the governance arm/armed? probes, :1868). The `:emitter-cell` /
    ;; `:active-roots-cell` cells stay INTERNAL to this closure — they are
    ;; wired into the spine fns (`render`, `set-hiccup-emitter!`,
    ;; `dispose-fn`) here and read by NO assembler or production call site,
    ;; so leaking them through the contract map would be dead surface. The
    ;; dispose unit tests build their own cells via the `make-*-cell`
    ;; factories and feed `make-dispose-adapter!` directly, so narrowing the
    ;; map breaks no test.
    {:warn-cache                  warn-cache
     :make-state-container        make-state-container
     :read-container              read-container
     :replace-container!          replace-cont!
     :subscribe-container         subscribe-cont
     :make-derived-value          make-derived
     :render                      render-fn
     :render-to-string            (make-render-to-string emitter-cell)
     :dispose-adapter!            dispose-fn
     :set-hiccup-emitter!         (fn set-it! [f]
                                    (set-hiccup-emitter! emitter-cell f))
     :use-current-frame           use-current-frame
     :use-subscribe               use-subscribe
     :flush-views!                flush-views!
     ;; rf2-40a84 — production-grade synchronous render-commit (NOT the
     ;; test-only act() wrapper above). Wired into the adapter map's
     ;; :flush-render! contract slot by make-react-adapter.
     :flush-render!               flush-render!
     :wrap-view                   wrap-view-fn
     :clear-warned-non-dom-roots! clear-warned
     ;; rf2-334d9 — :adapter/after-render impl. Each adapter publishes
     ;; this via substrate-adapter/route-hook!.
     :after-render-hook           after-render-hook}))

;; ---- React-hook adapter assembly (UIx + Helix) ----------------------------
;;
;; rf2-ee38b.1 / rf2-ee38b.13 / rf2-ee38b.14. `make-react-spine` already
;; eliminated the substrate LOGIC drift (one factory, N adapters). The
;; per-adapter WIRING — the 9-key adapter map, the five `route-hook!`
;; calls, and the two chained installs — was still hand-copied byte-for-
;; byte between `uix.cljs` and `helix.cljs` (the clarity-lens twin
;; finding), carrying ~90 lines of identical rationale prose and a
;; standing drift hazard: any new routed hook had to be copied into both
;; files in lockstep (a Helix-only SSR-parity fix per rf2-y9spn already
;; showed the two drifting before being re-synced). `make-react-adapter`
;; folds that wiring here — the adapter file shrinks to "build spine-fns,
;; publish the public Vars, call make-react-adapter". The route-hook block
;; carries zero per-adapter variation; the ONLY input is the spine-fns map
;; (already built per-substrate) and the `:kind` discriminator keyword.
;;
;; Hook routing (per rf2-0d35 — see `substrate-adapter/route-hook!` for
;; the routing contract): each impl runs ONLY when this adapter is the
;; (rf/init!)-installed one; otherwise chains to the previously-registered
;; handler.
;;   :adapter/current-frame — rf2-d4sf. Function components have no
;;     class-component (.-context cmp) slot, so the shared impl in
;;     `re-frame.adapter.context` reads `_currentValue` directly. This is
;;     the WIDER surface — `(rf/current-frame-id)` reaches the dynamic-var-
;;     fallback chain via this hook; the per-adapter `use-current-frame`
;;     hook is the NARROWER React-context-tier-only read (rf2-84myk).
;;   :adapter/add-on-dispose! / :adapter/dispose! — rf2-jicu2. Spine-
;;     produced derived values reify the re-frame-owned
;;     `re-frame.disposable/IDisposable` (no Reagent coupling); the
;;     adapter wires straight to the protocol fns. The reactive-substrate
;;     hooks (`:adapter/ratom`, `:adapter/ratom?`, `:adapter/make-reaction`,
;;     `:adapter/reactive?`) are intentionally NOT published — the React-
;;     hook substrates ship no reactive-atom primitive (rf2-3yij / rf2-2qit)
;;     and `re-frame.interop`'s reactive-atom surfaces have zero production
;;     call sites under them; publishing those hooks would force the bundle
;;     to carry reagent.core (transitively reagent.ratom) for code it never
;;     executes.
;;   :adapter/after-render — rf2-334d9. Backed by `React.useLayoutEffect`
;;     via the spine's after-render machinery. `after-render` is a React-
;;     lifecycle question (when does the next commit complete?), not a
;;     reactive-atom one — so the "no reactive primitive" rationale that
;;     excludes the four hooks above does NOT apply. Without this hook
;;     `(rf/after-render f)` under these adapters would be a silent
;;     no-op.
;;   :adapter/wrap-view — rf2-00li. Substrate-side source-coord injection
;;     via React.cloneElement (the views.cljs inline hiccup-walk would
;;     mis-classify React-element output as a non-DOM root). Production-
;;     elided via `interop/debug-enabled?` per Spec 009 §Production builds.

(defn make-react-adapter
  "Assemble a React-hook adapter (UIx / Helix) from a `make-react-spine`
  result map plus the substrate's config:

      :kind           — the adapter's `:kind` discriminator keyword
      :frame-provider — the substrate's NATIVE frame-provider component
                        (`defui` for UIx, `defnc` for Helix), defined in
                        the adapter ns ABOVE where that substrate's `$`
                        marshals props (rf2-z7hfp — the moved seam). The
                        component reads its props in the substrate's
                        lossless idiom and delegates to the spine core
                        `build-frame-provider-element`. Passed in (NOT
                        spine-built) so the spine carries no substrate
                        element-macro dependency, mirroring how
                        `make-ratom-adapter` takes the Reagent-component
                        `register-context-provider` in.

  Builds the 9-key substrate adapter map, routes the five React-hook
  late-bind hooks against it (`substrate-adapter/route-hook!`), and wires
  the two chained installs (warn-once clear + SSR hiccup-emitter). The
  `:register-context-provider` substrate slot returns the native
  `frame-provider` component (the frame-keyword arg is ignored — the
  keyword lives in the Provider's `:value` at render time, not in a
  build-time closure). Returns the adapter map. SIDE-EFFECTING: the
  route-hook! / chain-fn! calls run at call time (the adapter ns
  evaluates `(make-react-adapter spine-fns {:kind :rf.adapter/uix
  :frame-provider …})` at load), exactly as the hand-written wiring did.

  Single source of truth (rf2-ee38b.1): UIx and Helix call this with the
  same shape — the only inputs are their already-substrate-specific
  `spine-fns` map, `:kind`, and native `:frame-provider`. The former
  hand-copied route-hook block + chained installs (byte-identical across
  the twins) now live once."
  [spine-fns {:keys [kind frame-provider]}]
  (let [adapter {:kind                      kind
                 :make-state-container      (:make-state-container      spine-fns)
                 :read-container            (:read-container            spine-fns)
                 :replace-container!        (:replace-container!        spine-fns)
                 :subscribe-container       (:subscribe-container       spine-fns)
                 :make-derived-value        (:make-derived-value        spine-fns)
                 :render                    (:render                    spine-fns)
                 :render-to-string          (:render-to-string          spine-fns)
                 ;; rf2-z7hfp: the native component IS the provider; the
                 ;; frame-keyword arg is ignored (frame lives in the
                 ;; Provider's `:value` at render time).
                 :register-context-provider (fn [_frame-keyword] frame-provider)
                 ;; rf2-40a84 — optional synchronous render-flush contract fn
                 ;; (react-dom/flushSync). Lets headless tooling commit pending
                 ;; renders without waiting on React's rAF-scheduled lane.
                 :flush-render!             (:flush-render! spine-fns)
                 :dispose-adapter!          (:dispose-adapter!          spine-fns)}]
    (substrate-adapter/route-hook! adapter :adapter/current-frame
      adapter-context/function-component-current-frame
      #(frame/current-frame))
    (substrate-adapter/route-hook! adapter :adapter/add-on-dispose!
      rf-disposable/-add-on-dispose)
    (substrate-adapter/route-hook! adapter :adapter/dispose!
      rf-disposable/-dispose)
    (substrate-adapter/route-hook! adapter :adapter/wrap-view
      (:wrap-view spine-fns))
    (substrate-adapter/route-hook! adapter :adapter/after-render
      (:after-render-hook spine-fns))
    ;; Chained warn-once clear (rf2-4edk): chained (NOT routed by installed-
    ;; adapter identity) — every loaded adapter's per-process defonce must
    ;; clear between tests because a bundle can mount different adapters
    ;; across tests. rf2-z79p8: routed through the governance chokepoint
    ;; with arm/armed? probes over the spine's `warn-cache` atom so the
    ;; warn-once-clear governance assertion proves the chain wipes it.
    (let [warn-cache (:warn-cache spine-fns)]
      (install-clear-warn-once-step!
        (:clear-warned-non-dom-roots! spine-fns)
        {:label  :adapter/warned-non-dom-roots
         :arm    (fn [] (swap! warn-cache conj ::governance-sentinel))
         :armed? (fn [] (contains? @warn-cache ::governance-sentinel))}))
    ;; Chained SSR emitter install (rf2-4z7bp): `re-frame.ssr.emit` invokes
    ;; `:reagent/set-hiccup-emitter!` at ns-load; every loaded React-shaped
    ;; adapter contributes its own install step so a single
    ;; `(require '[re-frame.ssr])` auto-wires every adapter's render-to-
    ;; string slot. Hook key is historical (Reagent published it first per
    ;; rf2-uo7v); behaviour is adapter-agnostic.
    (late-bind/chain-fn! :reagent/set-hiccup-emitter!
                         (:set-hiccup-emitter! spine-fns))
    adapter))

;; ---- ratom-family spine (Reagent + reagent-slim) --------------------------
;;
;; The Reagent and reagent-slim adapters are the SAME shape under a
;; different reactive-atom impl (stock `reagent.*` vs the `reagent2.*`
;; rewrite). `make-ratom-spine` factors the shared container quartet,
;; React-root renderer, and dispose body exactly as `make-react-spine`
;; factors the UIx/Helix hook family — one implementation, two adapters,
;; zero drift.
;;
;; CRITICAL — slim bundle isolation (IMPL-SPEC §1.8 / the
;; `test:reagent-slim:bundle-isolation` gate). This helper lives in
;; core/substrate and MUST NOT `:require` stock `reagent.*` — the day8/
;; reagent-slim adapter would otherwise drag the stock-Reagent impl tree
;; into every slim release bundle. The reactive-atom ops are therefore
;; INJECTED by each adapter as a flat set of BARE-FN config keys (the HOF
;; parameterisation): the Reagent adapter passes its stock `reagent.*`
;; impls, the slim adapter passes its `reagent2.*` impls. The spine never
;; names either ns. (The same isolation principle as `make-react-spine`,
;; which calls `react-dom/client` directly but never Reagent.)
;;
;; rf2-0u5em6: the per-substrate ops arrive as FLAT bare-fn config keys
;; (`:r-atom`, `:make-reaction`, `:create-root`, …) — mirroring how
;; `make-react-spine` takes its bare `:use-memo` / `:use-callback` /
;; `:use-context` hook fns — rather than as a hand-shaped `:ratom-ops`
;; keyword map literal. Earlier each adapter built a structurally-identical
;; 7-key `:ratom-ops` map differing only by ns-alias (`reagent.*` vs
;; `reagent2.*`), a "keep two maps in lockstep" hazard. The keyword-key
;; shape now lives ONCE here; each adapter passes ~7 bare fns.

(defn make-ratom-spine
  "Build the per-substrate ratom-family substrate surfaces given the
  substrate's gensym prefix and a FLAT set of injected reactive-atom BARE
  FNS (rf2-0u5em6 — mirroring how `make-react-spine` takes its bare
  `:use-memo` / `:use-callback` / `:use-context` hook fns, not a hand-
  shaped keyword map):

      :gensym-prefix-sub — gensym prefix for `subscribe-container` watch
                           keys (substrate-scoped per rf2-l4dmr so logs /
                           inspectors attribute a watch to its substrate)
      :r-atom        — (fn [v]) → reactive atom container
      :make-reaction — (fn [thunk]) → reaction over a thunk
      :create-root   — (fn [mount-point]) → React root
      :render-root   — (fn [root tree]) → render hiccup into root (the
                       substrate's hiccup→element walk + `.render`, NOT a
                       bare `.render`)
      :hydrate-root  — (fn [mount-point tree]) → React root
      :unmount-root  — (fn [root]) → unmount the root
      :flush-render! — (fn [f]) → run `f` then SYNCHRONOUSLY commit the
                       substrate's pending renders to the DOM (rf2-40a84;
                       stock Reagent passes `(fn [f] (f) (reagent.core/
                       flush))`, slim passes its `reagent2.*` synchronous
                       flush). NOT rAF-scheduled — immune to the
                       backgrounded-tab throttle, so headless tooling can
                       drive a `dispatch → flush-render! → observe-DOM` loop.

  The spine assembles its internal ratom-ops shape from these bare fns; it
  MUST NOT `:require` stock `reagent.*`; the fns above are the only path to
  the substrate's reactive primitive, so each adapter's own `reagent.*` /
  `reagent2.*` requires stay confined to the adapter ns (load-bearing for
  reagent-slim bundle isolation — see the section comment above).

  Returns a map of the substrate-contract surfaces (minus
  `register-context-provider`) plus the SSR helpers each adapter
  re-exports:

      {:make-state-container       …
       :read-container             …
       :replace-container!         …
       :subscribe-container        …
       :make-derived-value         …
       :render                     …
       :render-to-string           …
       :dispose-adapter!           …
       :flush-render!              …
       :flush-views!               …
       :set-hiccup-emitter!        …}

  rf2-6id3el: the internal `active-roots-cell` / `emitter-cell` are NOT
  exposed — they stay confined to this closure (wired into `render`,
  `dispose-adapter!`, `set-hiccup-emitter!`); no assembler or production
  call site read them.

  `:register-context-provider` is NOT produced here: for the ratom
  family it is the Reagent-component-shaped frame-provider from
  `re-frame.views` (`views/build-frame-provider`), which the React-hook
  spine's hook-shaped `frame-provider` is not. Keeping it as adapter-side
  wiring also keeps this core ns free of a spine→views dependency edge.

  Produces: container quartet incl. the substrate-scoped gensym; the
  create-root/hydrate-root render with active-roots tracking + an unmount
  thunk that drops itself from the set; and the four-MUST dispose body
  (`dispose-frame-sub-caches!` + active-roots drain w/ per-root throw-
  swallow + emitter clear)."
  [{:keys [gensym-prefix-sub r-atom make-reaction create-root render-root
           hydrate-root unmount-root]
    flush-render-op :flush-render!}]
  (let [active-roots-cell (make-active-roots-cell)
        emitter-cell      (make-hiccup-emitter-cell)
        ;; rf2-w1g0d2: reuse the shared container helpers where the ratom +
        ;; React-hook semantics are genuinely identical. `make-state-container`
        ;; differs ONLY in the ctor (substrate `r-atom` vs plain `atom`), so
        ;; it rides the shared `make-state-container-fn` factory. `read-container`
        ;; is BYTE-IDENTICAL (`@container`), so it reuses the top-level Var
        ;; directly. `replace-container!` (bare `reset!`, NO epoch scheduler)
        ;; and `make-derived-value` (native reaction, NOT the explicit reify)
        ;; legitimately DIFFER — see below — so they stay inline.
        ;; (`read-container` is not rebound here — the return map references
        ;; the top-level Var directly.)
        make-state-container (make-state-container-fn r-atom)
        ;; replace-container! is a BARE `reset!` — NO epoch scheduler. The
        ;; React-hook spine's `make-replace-container-fn` brackets its reset!
        ;; in a scheduler epoch (`with-epoch`) because it has no reaction
        ;; primitive and must coalesce multi-input derived recomputes glitch-
        ;; free explicitly (Spec 006 §Invalidation algorithm). The ratom
        ;; family is immune: Reagent's reactions are natively batched through
        ;; `r/flush!`, so a multi-input Reaction already recomputes once per
        ;; coherent input epoch. There is no scheduler in this spine to bracket
        ;; against, so this CANNOT consolidate with the React-hook version.
        replace-container!
        (fn replace-container! [container new-value]
          (reset! container new-value)
          nil)
        subscribe-container
        (make-subscribe-container gensym-prefix-sub)
        ;; Arity-specialised recompute closure via `build-recompute-fn`
        ;; (rf2-eoy63), wrapped in the substrate's own reaction primitive.
        make-derived-value
        (fn make-derived-value [source-containers compute-fn]
          (make-reaction (build-recompute-fn source-containers compute-fn)))
        ;; React 18+/19 Root API: create-root → render → unmount; the
        ;; hydrate branch on the SSR path returns its own Root. Active
        ;; roots are tracked so `dispose-adapter!` can drain them; the
        ;; unmount thunk removes itself from the set before unmounting.
        ;; Per rf2-gwkvr: Spec 006 §`render` types `:hydrate?` as a
        ;; boolean; no defensive coercion.
        render
        (fn render [render-tree mount-point opts]
          (let [hydrate? (:hydrate? opts)
                root     (if hydrate?
                           (hydrate-root mount-point render-tree)
                           (let [r (create-root mount-point)]
                             (render-root r render-tree)
                             r))]
            ;; rf2-w1g0d2: shared track-and-unmount tail (unmount-op =
            ;; the injected `unmount-root`).
            (track-active-root! active-roots-cell unmount-root root)))
        ;; Spec 006 §Adapter disposal lifecycle (rf2-9fdkb, rf2-a47kq,
        ;; rf2-jcjul, rf2-7v82h). The four-MUST list:
        ;;   1. Cancel in-flight reactive subscriptions — walk every live
        ;;      frame's per-frame sub-cache (`dispose-frame-sub-caches!`,
        ;;      shared with the React-hook spine for zero drift).
        ;;   2. Release host-specific resources — drain active-roots,
        ;;      swallowing per-root throws so one bad root cannot strand
        ;;      the rest of the drain.
        ;;   3. Discard internal caches — clear the hiccup-emitter cell.
        ;;   4. Subsequent calls return `:rf.error/adapter-disposed` —
        ;;      enforced one level up by substrate-adapter via the
        ;;      `disposed?` breadcrumb (rf2-6wxys).
        ;; rf2-w1g0d2: the ratom dispose IS exactly the shared core
        ;; (`dispose-active-roots-and-caches!`) — sub-cache walk + active-roots
        ;; drain-with-swallow + emitter clear — with `unmount-root` as the
        ;; unmount-op. No warn-cache / driver-root teardown (those are
        ;; React-hook-only), so unlike `make-dispose-adapter!` it layers
        ;; nothing on top.
        dispose-adapter!
        (fn dispose-adapter! []
          (dispose-active-roots-and-caches! unmount-root
                                            active-roots-cell emitter-cell))
        ;; rf2-40a84 — production synchronous render-flush. Delegates to the
        ;; injected `:rdc/flush-render!` op (stock `reagent.core/flush` /
        ;; slim's `reagent2.*` synchronous flush) so the spine never names a
        ;; reactive-atom ns (bundle isolation). The op runs `f` then drains
        ;; the substrate's component-render queue synchronously and (on React
        ;; 19) commits via `flushSync` — NOT rAF-scheduled, so it fires even
        ;; in a backgrounded tab. No-op-safe when nothing is pending.
        flush-render!
        (fn flush-render!
          ([] (flush-render! (fn [] nil)))
          ([f]
           (if flush-render-op
             (flush-render-op f)
             ;; Defensive: an adapter that injected no flush op still honours
             ;; the contract by at least running `f`. Reagent and reagent-slim
             ;; both inject one, so this branch is dead in the reference.
             (f))
           nil))
        ;; rf2-b6nm5 — CANONICAL test-flush hook, converged across all four
        ;; substrates (Decision 6 anointed `flush-views!`; previously stock
        ;; Reagent surfaced none, slim surfaced a Promise-returning one in a
        ;; SUBSTRATE ns). Same name, location (adapter ns, re-exported from
        ;; here), and SHAPE as the React-hook spine's `flush-views!`: wrap
        ;; React's `act()` so a subscribe → re-render cycle drives
        ;; synchronously in test code; with no arg, flushes pending effects;
        ;; returns nil. Inside `act` we drive the ratom-family synchronous
        ;; render drain (the injected `:rdc/flush-render!` op — stock
        ;; `reagent.core/flush` / slim's `reagent2.*` flush) so dirty
        ;; components forceUpdate and Reactions recompute before `act`
        ;; returns. No-op when act() is unreachable in the current React
        ;; build (mirrors the React-hook spine `flush-views!`).
        flush-views!
        (fn flush-views!
          ([] (flush-views! (fn [] nil)))
          ([f]
           (if-let [act (resolve-act-fn)]
             (act (fn act-body []
                    (if flush-render-op
                      (flush-render-op f)
                      (f))))
             ;; No act() — degrade to a plain synchronous flush so a
             ;; :node-test runner (no real React render path) still drains
             ;; the render queue + dirty-set.
             (if flush-render-op (flush-render-op f) (f)))
           nil))]
    {:make-state-container       make-state-container
     :read-container             read-container
     :replace-container!         replace-container!
     :subscribe-container        subscribe-container
     :make-derived-value         make-derived-value
     :render                     render
     :render-to-string           (make-render-to-string emitter-cell)
     :dispose-adapter!           dispose-adapter!
     ;; rf2-40a84 — production synchronous render-commit, wired into the
     ;; adapter map's :flush-render! contract slot by make-ratom-adapter.
     :flush-render!              flush-render!
     ;; rf2-b6nm5 — canonical nil-return test-flush hook (Decision 6),
     ;; re-exported by both ratom adapter namespaces so all four substrates
     ;; surface the SAME `flush-views!` Var with the SAME nil-return shape.
     :flush-views!               flush-views!
     :set-hiccup-emitter!        (fn set-it! [f]
                                   (set-hiccup-emitter! emitter-cell f))}))
;; rf2-6id3el: the ratom return map exposes ONLY the surfaces the adapter
;; assembler consumes. `make-ratom-adapter` reads none of the internal
;; cells; the `:active-roots-cell` / `:emitter-cell` cells stay INTERNAL to
;; this closure (wired into `render`, `dispose-adapter!`,
;; `set-hiccup-emitter!`) and were read by NO assembler or production call
;; site, so they were dead contract surface. (The ratom family has no
;; `:warn-cache` — its source-coord walk lives in `re-frame.views`, not the
;; spine — so unlike `make-react-spine` it exposes no internal cell at all.)

;; ---- ratom-family adapter assembly (Reagent + reagent-slim) ---------------
;;
;; rf2-ee38b.1 / rf2-ee38b.12 / rf2-ee38b.15. `make-ratom-spine` hoisted
;; the substrate-surface drift (container quartet, renderer, dispose body,
;; SSR emitter) but left the SECOND half — the `set-hiccup-emitter!` chain
;; install, the `register-context-provider` wiring, the 9-key adapter map,
;; and the entire nine-call `route-hook!` table — hand-copied byte-for-byte
;; between `reagent.cljs` and `reagent_slim.cljs` (the clarity-lens twin
;; finding across both ratom beads). The two `cond` dispatch closures
;; (`add-on-dispose!`/`dispose!`) carry zero substrate-specific text — only
;; which `ratom` ns binds the alias differs. `make-ratom-adapter` folds
;; that wiring here, mirroring `make-react-adapter`.
;;
;; CRITICAL — slim bundle isolation. As with `make-ratom-spine`, this
;; helper MUST NOT `:require` stock `reagent.*` (or `reagent2.*`). The
;; reactive-atom-family ops the hook table needs — `current-component`,
;; `atom`, `after-render`, `make-reaction`, the `ratom?`/`disposable?`
;; predicates, and the `add-on-dispose!`/`dispose!`/`reactive?` fns — are
;; INJECTED as a flat set of bare-fn config keys (predicate/dispatch
;; lambdas over the substrate's protocols), so the spine never names a
;; reactive-atom ns. Each adapter passes its `reagent.*` / `reagent2.*`
;; impls.
;;
;; rf2-0u5em6: as with `make-ratom-spine`, the hook ops arrive as FLAT
;; bare-fn config keys rather than a hand-shaped `:hook-ops` keyword map
;; literal. Earlier each adapter built a structurally-identical 10-key
;; `:hook-ops` map differing only by ns-alias (the two maps were byte-
;; identical modulo `reagent.*` vs `reagent2.*`) — a "keep two maps in
;; lockstep" hazard. The keyword-key shape now lives ONCE here; the spine
;; assembles the route-hook table from the bare fns each adapter passes.

(defn make-ratom-adapter
  "Assemble a ratom-family adapter (Reagent / reagent-slim) from a
  `make-ratom-spine` result map plus the substrate's config:

      :kind      — the adapter's `:kind` discriminator keyword
      :register-context-provider
                 — the views-backed (Reagent-component-shaped) provider fn
                   `(fn [_frame-keyword] (views/build-frame-provider))`.
                   Passed in (NOT spine-built) so the core spine carries no
                   spine→views dependency edge.

  …plus a FLAT set of the injected reactive-atom-family BARE FNS the
  late-bind hook table routes (rf2-0u5em6 — bundle-isolation: lambdas only,
  the spine names no reactive-atom ns; mirrors `make-react-spine`'s bare-
  hook-fn config rather than a hand-shaped keyword map):

      :current-frame      — (fn []) → React-context-tier current frame
                            (`views/current-frame`)
      :current-component  — (fn []) → the in-flight component
      :atom               — (fn [v]) → reactive atom
      :ratom?             — (fn [x]) → boolean (IReactiveAtom check)
      :make-reaction      — (fn [thunk]) → reaction
      :disposable?        — (fn [x]) → boolean (substrate IDisposable
                            check), used by the dual-protocol dispatch
      :add-on-dispose!    — (fn [a f]) → register a substrate-reaction
                            dispose hook
      :dispose!           — (fn [a]) → dispose a substrate reaction
      :reactive?          — (fn []) → boolean
      :after-render       — (fn [f]) → schedule post-render callback

  Builds the 9-key adapter map, wires the chained SSR emitter install, and
  routes the nine ratom-family late-bind hooks against the adapter. The two
  dual-protocol dispatch hooks (`:adapter/add-on-dispose!` / `:adapter/
  dispose!`) protocol-check the re-frame-owned
  `re-frame.disposable/IDisposable` FIRST (spine-produced derived values
  from a cross-substrate test bundle, rf2-jicu2) then fall through to the
  substrate's own disposable (`:disposable?` / `:add-on-dispose!` /
  `:dispose!`). Returns the adapter map. SIDE-EFFECTING at call time
  (chain-fn! / route-hook!), exactly as the hand-written wiring was.

  Single source of truth (rf2-ee38b.1): Reagent and reagent-slim call this
  with the same shape — only their injected bare hook fns and `:kind`
  differ. The former hand-copied route-hook block now lives once."
  [spine-fns {:keys [kind register-context-provider
                     current-frame current-component atom ratom? make-reaction
                     disposable? add-on-dispose! dispose! reactive? after-render]}]
  (let [adapter {:kind                      kind
                 :make-state-container      (:make-state-container spine-fns)
                 :read-container            (:read-container       spine-fns)
                 :replace-container!        (:replace-container!   spine-fns)
                 :subscribe-container       (:subscribe-container  spine-fns)
                 :make-derived-value        (:make-derived-value   spine-fns)
                 :render                    (:render               spine-fns)
                 :render-to-string          (:render-to-string     spine-fns)
                 :register-context-provider register-context-provider
                 ;; rf2-40a84 — optional synchronous render-flush contract fn
                 ;; (reagent.core/flush — drains the render queue + React-19
                 ;; flushSync commit). Lets headless tooling commit pending
                 ;; renders without waiting on Reagent's rAF-scheduled drain.
                 :flush-render!             (:flush-render! spine-fns)
                 :dispose-adapter!          (:dispose-adapter!     spine-fns)}]
    ;; Chained SSR emitter install (rf2-4z7bp / parity rf2-cl1qv): every
    ;; loaded React-shaped adapter contributes its install step so a single
    ;; `(require '[re-frame.ssr])` auto-wires every adapter's render-to-
    ;; string slot. `chain-fn!` (not `set-fn!`) is load-order-independent.
    (late-bind/chain-fn! :reagent/set-hiccup-emitter!
                         (:set-hiccup-emitter! spine-fns))
    ;; Each hook routes through `(substrate-adapter/current-adapter)` per
    ;; rf2-0d35 via `route-hook!`: this adapter's impl runs ONLY when it is
    ;; the (rf/init!)-installed one; otherwise it chains to the previously-
    ;; registered handler.
    ;;   :adapter/current-frame — rf2-d4sf. The React-context tier of the
    ;;     3-tier chain; the ratom family uses the class-component
    ;;     (.-context cmp) shape via `views/current-frame`. Chain-bottom
    ;;     fallback is `frame/current-frame` so headless / pre-init shape is
    ;;     preserved.
    ;;   :adapter/current-component — rf2-wbnl. Reads the substrate's
    ;;     in-flight component without hard-binding re-frame.views to it.
    ;;   :adapter/ratom etc. — rf2-s36l. The reactive-substrate surfaces
    ;;     consumed by `re-frame.interop`.
    ;;   :adapter/add-on-dispose! / :adapter/dispose! — rf2-jicu2. A
    ;;     ratom-installed app may still hold a spine-produced derived value
    ;;     (inherited through a cross-substrate test bundle). Dispatch
    ;;     handles BOTH shapes — the re-frame-owned IDisposable (spine
    ;;     derived values, checked first) and the substrate's own
    ;;     IDisposable.
    (substrate-adapter/route-hook! adapter :adapter/current-frame
      current-frame
      #(frame/current-frame))
    (substrate-adapter/route-hook! adapter :adapter/current-component
      current-component)
    (substrate-adapter/route-hook! adapter :adapter/ratom
      atom)
    (substrate-adapter/route-hook! adapter :adapter/ratom?
      ratom?
      (constantly false))
    (substrate-adapter/route-hook! adapter :adapter/make-reaction
      make-reaction)
    (substrate-adapter/route-hook! adapter :adapter/add-on-dispose!
      (fn add-on-dispose!-dispatch [a f]
        (cond
          (satisfies? rf-disposable/IDisposable a) (rf-disposable/-add-on-dispose a f)
          (disposable? a)                          (add-on-dispose! a f)
          :else                                    nil)))
    (substrate-adapter/route-hook! adapter :adapter/dispose!
      (fn dispose!-dispatch [a]
        (cond
          (satisfies? rf-disposable/IDisposable a) (rf-disposable/-dispose a)
          (disposable? a)                          (dispose! a)
          :else                                    nil)))
    (substrate-adapter/route-hook! adapter :adapter/reactive?
      reactive?
      (constantly false))
    (substrate-adapter/route-hook! adapter :adapter/after-render
      after-render)
    ;; rf2-8wrzz.3 — the derived-container discriminator the core's
    ;; `replace-container!` choke point consults to reject writes to a
    ;; `make-derived-value` result (Spec 006 §`make-derived-value`). The
    ;; ratom family CANNOT rely on the choke point's atom-marker fall-back:
    ;; a Reagent `Reaction` reifies `IAtom` exactly like a base `r/atom`, so
    ;; the heuristic would never fire. The disposal protocol IS the
    ;; discriminator — a derived value is disposable, a base `r/atom` /
    ;; `RAtom` is not. Dual-protocol like the dispose dispatch above: the
    ;; re-frame-owned IDisposable FIRST (a spine-produced derived value
    ;; inherited through a cross-substrate test bundle, rf2-jicu2) then the
    ;; substrate's own `:disposable?`. Routed (not an adapter-map key) so
    ;; the ten-fn adapter contract shape is preserved; the choke point reads
    ;; it via `late-bind/get-fn :adapter/derived-container?`. The ratom
    ;; impl is exhaustive over ratom containers — truthy for a `Reaction`
    ;; (derived), `false` for a base `r/atom` (the choke point trusts that
    ;; `false` and skips its atom-marker heuristic, rf2-oitw37). The
    ;; chain-bottom fallback returns the `container-class-unknown` sentinel
    ;; (NOT `false`): when a NON-ratom adapter is installed, this routed
    ;; closure has no opinion and the choke point must reach for the
    ;; atom-marker heuristic — a bare `false` would instead read as "this
    ;; ratom adapter classifies it as base", wrongly forcing the
    ;; non-ratom-adapter path through the ratom verdict (rf2-oitw37).
    (substrate-adapter/route-hook! adapter :adapter/derived-container?
      (fn derived-container?-dispatch [a]
        (or (satisfies? rf-disposable/IDisposable a)
            (boolean (disposable? a))))
      (constantly substrate-adapter/container-class-unknown))
    adapter))
