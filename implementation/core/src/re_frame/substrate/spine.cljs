(ns re-frame.substrate.spine
  "Shared substrate-spine helpers for React-shaped adapters that lack a
  native reactive-atom primitive (UIx, Helix, and any future minimal-
  React-wrapper substrate). UIx and Helix duplicated this body byte-
  for-byte modulo gensym prefixes, hook ns, and substrate-name strings;
  per-adapter wiring goes through `make-react-spine`.

  Scope. This ns provides:

    * The plain-`atom` container quartet (make / read / replace / subscribe).
    * `make-derived-value` (one watch per source; reifies the
      re-frame-owned `re-frame.disposable/IDisposable`).
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
  shape-compliant with the 9-fn substrate contract."
  (:require ["react"             :as React]
            ["react-dom/client"  :as react-dom-client]
            [re-frame.disposable :as rf-disposable]
            [re-frame.frame      :as frame]
            [re-frame.interop    :as interop]
            [re-frame.late-bind  :as late-bind]
            [re-frame.subs       :as subs]
            [re-frame.adapter.context :as adapter-context]))

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

(defn make-state-container [initial-value]
  (atom initial-value))

(defn read-container [container]
  @container)

(defn replace-container! [container new-value]
  (reset! container new-value)
  nil)

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

  Single source of truth (rf2-eoy63 lockstep): the Reagent,
  reagent-slim, UIx, and Helix adapters all build their recompute
  closure through this fn — pre-rf2-eoy63 the arity-spec lived only in
  the Reagent adapter and the spine + reagent-slim had naive
  `(apply compute-fn (map deref ...))` shapes that paid the apply +
  lazy-seq cost on every sub recompute × every dispatch. Lifting the
  arity-spec into the spine matches the rf2-jcjul `make-dispose-adapter!`
  shape: one implementation, four adapters, zero drift. Sourced from
  the rf2-fzrav perf-sweep findings."
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
  the given `gensym-prefix`. The fn signature matches the substrate
  contract: `(sources compute-fn) -> derived-container`."
  [gensym-prefix]
  (fn make-derived-value [source-containers compute-fn]
    (let [recompute      (build-recompute-fn source-containers compute-fn)
          watchers       (atom {})           ;; user-key → wrapper-fn
          on-dispose-fns (atom [])
          ;; Per-source wrapper keys we own so dispose can unwire them.
          own-keys       (atom {})           ;; source → key
          ;; Iterate via `run!` over `vals` rather than `doseq` over
          ;; map-entries — skips one map-entry seq allocation per
          ;; source-change notification.
          notify         (fn [prev nu]
                           (when (not= prev nu)
                             (run! (fn [w] (w prev nu)) (vals @watchers))))]
      ;; Wire one watch per source so the listener registry surface
      ;; (subscribe-container) on the derived container fires whenever
      ;; any source changes.
      ;; Seed the baseline from the *derived* value at construction time,
      ;; not the raw source. The watch callback compares prev-derived
      ;; against the recomputed derived value; if we seeded from
      ;; `(deref s)` (the raw source), the first source change would
      ;; spuriously notify whenever the derived projection differs in
      ;; identity from the raw source — e.g. `(odd? x)`, counts, `:k`
      ;; lookups, projections — even when the derived value itself is `=`.
      ;;
      ;; `prev-state` is written and read only inside the watch callback
      ;; (single-threaded JS / Reagent reactivity) and never escapes
      ;; this closure — `volatile!` is the right primitive, no CAS cost.
      (let [prev-state (volatile! (recompute))]
        (doseq [s source-containers]
          (let [k (gensym gensym-prefix)]
            (swap! own-keys assoc s k)
            (add-watch s k
              (fn [_ _ _ _]
                (let [new-derived  (recompute)
                      prev-derived @prev-state]
                  (vreset! prev-state new-derived)
                  (notify prev-derived new-derived)))))))
      (reify
        IDeref
        (-deref [_] (recompute))
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
        ;; hooks (per Spec 006 §subscription-cache). Pre-rf2-jicu2 the
        ;; spine reified `reagent.ratom/IDisposable` here, which forced
        ;; every UIx/Helix bundle to pay ~9KB optimised / 2-3KB gzipped
        ;; of `reagent.ratom` + `reagent.impl.batching` for one
        ;; protocol — the new `re-frame.disposable/IDisposable` is
        ;; re-frame-owned and carries no Reagent dependency.
        rf-disposable/IDisposable
        (-dispose [_]
          (doseq [[s k] @own-keys] (remove-watch s k))
          (reset! own-keys {})
          (reset! watchers {})
          (doseq [f @on-dispose-fns] (f))
          (reset! on-dispose-fns []))
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
      (swap! active-roots-cell conj root)
      (fn unmount []
        (swap! active-roots-cell disj root)
        (.unmount root)))))

;; ---- after-render --------------------------------------------------------
;;
;; `:adapter/after-render` for React-only substrates (UIx, Helix) per
;; rf2-334d9 (Mike decision rf2-neiqf 2026-05-19: publish via
;; useLayoutEffect). Pre-rf2-334d9 the UIx and Helix adapters did NOT
;; publish `:adapter/after-render`, so `(rf/after-render f)` under those
;; adapters was a silent no-op — a correctness bug under the pre-alpha
;; masterpiece posture.
;;
;; Architecture. Per-adapter queue cell + a sentinel function component
;; injected at the root of every mounted tree (via `make-render`'s
;; Fragment wrap). The sentinel uses `React.useLayoutEffect` to drain
;; the queue after each commit — same DOM-mutations-applied / pre-paint
;; timing semantics as Reagent's `r/after-render`. When `after-render`
;; is called, the sentinel's stashed `setState` bumps a tick to force a
;; commit so its `useLayoutEffect` fires and drains the queue.
;;
;; No-sentinel fallback. If `after-render` is invoked before any render
;; has mounted (or after every root has unmounted), there is no stashed
;; setter to drive a commit — fall through to `queueMicrotask` so `f`
;; still fires once the current microtask boundary completes. Honest
;; under both the "user dispatched a scroll-restore from a one-shot
;; bootstrap event" path AND the "tests poke `interop/after-render`
;; without mounting anything" path.

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
    2. On every commit, fires `React.useLayoutEffect` to drain
       `queue-cell` — same timing as `r/after-render`'s post-commit
       run.

  The sentinel uses raw React hooks (`React/useState`,
  `React/useEffect`, `React/useLayoutEffect`) rather than the
  substrate's hook ns so the same impl works for UIx, Helix, and any
  future React-shaped substrate using this spine.

  Returned value is the bare function component, suitable for
  `(React/createElement sentinel-cmp nil)`."
  [queue-cell set-tick-ref]
  (fn after-render-sentinel [_props]
    (let [tick+setter (React/useState 0)
          set-tick    (aget tick+setter 1)]
      (React/useEffect
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

(defn make-after-render-hook
  "Build the `:adapter/after-render` impl fn. The returned fn:

    1. Enqueues `f` on `queue-cell`.
    2. If the sentinel is mounted (`set-tick-ref` is non-nil), bumps
       its tick — React schedules a commit, the sentinel's
       `useLayoutEffect` fires, and the queue drains in
       post-commit / pre-paint order.
    3. Otherwise schedules a `queueMicrotask` drain so `f` still fires
       once the current microtask boundary completes (covers the
       pre-mount / post-unmount call paths)."
  [queue-cell set-tick-ref]
  (fn after-render-hook [f]
    (swap! queue-cell conj f)
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

    1. If `entry` carries a `:pending-dispose` timer handle (the
       sub-cache's grace-period reaper), cancel it via
       `interop/clear-timeout!` — otherwise a fired timer would
       attempt to touch a torn-down adapter slot.
    2. Dispose the cached `:reaction` via `interop/dispose!`. This
       routes through `:adapter/dispose!`, which is still wired at
       this point in the teardown sequence (the substrate-adapter
       clears the install slot AFTER calling the adapter's
       `dispose-adapter!`).
    3. After draining each frame's entries, `reset!` its sub-cache
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
        (when-let [h (:pending-dispose entry)]
          (try (interop/clear-timeout! h)
               (catch :default _ nil)))
        (when-let [r (:reaction entry)]
          (try (interop/dispose! r)
               (catch :default _ nil))))
      (reset! cache {}))))

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
  `dispose-frame-sub-caches!`)."
  [{:keys [active-roots-cell warn-cache emitter-cell]}]
  (fn dispose-adapter! []
    (dispose-frame-sub-caches!)
    (doseq [root @active-roots-cell]
      (try (.unmount root)
           (catch :default _ nil)))
    (reset! active-roots-cell #{})
    (when warn-cache   (reset! warn-cache #{}))
    (when emitter-cell (reset! emitter-cell nil))
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
      (throw (ex-info ":rf.error/no-hiccup-emitter-bound"
                      {:reason      "require re-frame.ssr (the SSR ns-load resolves the :reagent/set-hiccup-emitter! late-bind hook automatically), or call set-hiccup-emitter! directly"
                       :render-tree render-tree})))))

;; ---- context provider -----------------------------------------------------
;;
;; Every React-shaped adapter shares the same React.createContext object
;; (in re-frame.adapter.context). The provider component is identical
;; across substrates — only `use-current-frame` (the read-side hook)
;; varies, and only by which hook ns supplies `use-context`.

(defn frame-provider
  "User-facing component scoping `frame-kw` to its subtree. Wraps
  children in the shared frame Context Provider — inside the subtree,
  `(rf/dispatcher)` / `(rf/subscriber)` / `reg-view`-registered
  descendants resolve to the named frame. Per Spec 002 §What
  `frame-provider` is.

  Reads `:frame` from props. When missing or `nil`, falls through to
  `:rf/default` — defensive default that matches the no-provider
  behaviour and avoids breaking tooling-generated trees that elide the
  prop."
  [{:keys [frame children]}]
  (let [frame-kw (or frame :rf/default)]
    (apply adapter-context/provider-element frame-kw
           (if (sequential? children) children [children]))))

(defn register-context-provider
  "Substrate `:register-context-provider` slot. Same shape across all
  React-shaped adapters: returns the `frame-provider` fn unchanged.
  The frame-keyword arg is ignored because the frame keyword lives in
  the Provider's `:value` at render time, not in a build-time closure."
  [_frame-keyword]
  frame-provider)

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
  Mirrors the Reagent test harness's act-fn at
  `adapters/reagent/test/re_frame/frame_provider_context_cljs_test.cljs:467`."
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

(defn format-source-coord
  "Render captured registry coords as the attribute value shape
  `<ns>:<sym>:<line>:<col>`. Mirrors re-frame.views/format-source-coord
  so DOM output is identical across substrates."
  [id coords]
  (let [ns-part  (or (namespace id) "?")
        sym-part (name id)
        line     (:line coords)
        col      (:column coords)]
    (str ns-part ":" sym-part ":"
         (if line (str line) "?")
         ":"
         (if col (str col) "?"))))

(defn format-view-id
  "Render the registry id keyword as the `:data-rf-view` attribute
  value. Returns `(str id)` so `:rf.foo/bar` → `\":rf.foo/bar\"`. The
  walker reads it back via `(keyword (subs s 1))` when the leading `:`
  is present. Per Spec 006 §View tagging contract (rf2-01il5).
  Mirrors re-frame.views.source-coord-annotation/format-view-id so
  the attribute value is identical across substrates."
  [id]
  (str id))

(defn make-warn-once-cache
  "Return an `(atom #{})` for tracking per-id warn-once emission. Each
  adapter owns its own cache so multiple adapters can coexist in test
  bundles without clobbering each other's warn-once state."
  []
  (atom #{}))

(defn make-clear-warned-fn
  "Return a thunk that resets `cache-atom` to `#{}` and returns nil.
  Tests use this between cases (via `reset-runtime-fixture-factory` and the
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
        (str "[re-frame] reg-view " id " — root element is "
             (pr-str type-tag) " (" substrate-name "); "
             "data-rf2-source-coord skipped "
             "(Spec 006 §Source-coord annotation: pair tools fall back to "
             ":rf/id for non-DOM roots).")))))

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
  cloneElement call that adds both `data-rf2-source-coord` (Spec 006
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
  contexts / CSS containment."
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

(defn make-wrap-view
  "Return a `wrap-view` fn parameterised on the substrate's per-adapter
  `warn-fn` (typically built via `make-warn-non-dom-root-fn`). The
  returned fn has the standard 3-arg shape `(id metadata user-fn) ->
  wrapped-user-fn` and produces a function component that injects
  both `data-rf2-source-coord` (Spec 006 §Source-coord annotation) and
  `data-rf-view` (Spec 006 §View tagging contract) on the rendered
  root DOM element when `interop/debug-enabled?` is true. Production
  builds elide via `interop/debug-enabled?` per Spec 009 §Production
  builds."
  [warn-fn]
  (fn wrap-view [id metadata user-fn]
    (if interop/debug-enabled?
      (let [coord-attr (format-source-coord id metadata)
            view-attr  (format-view-id id)]
        (fn wrapped-user-fn [& args]
          (let [out (apply user-fn args)]
            (inject-source-coord-attr warn-fn id coord-attr view-attr out))))
      user-fn)))

(defn install-clear-warn-once-step!
  "Wire `clear-fn` into the chained `:adapter/clear-warn-once-caches!`
  late-bind hook. The hook is chained — each adapter and re-frame.views
  contribute a clear-step; `reset-runtime-fixture-factory` invokes the top of
  the chain and every contributor's reset runs. Thin wrapper around
  `late-bind/chain-fn!` so callers don't need to know the chain key."
  [clear-fn]
  (late-bind/chain-fn! :adapter/clear-warn-once-caches! clear-fn))

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
;;      decrements through the cache's deferred-dispose grace-period
;;      (per Spec 006 §reference-counting-and-disposal).
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
       :register-context-provider  …
       :dispose-adapter!           …
       :set-hiccup-emitter!        …
       :use-current-frame          …
       :use-subscribe              …
       :frame-provider             …
       :flush-views!               …
       :wrap-view                  …
       :clear-warned-non-dom-roots! …}"
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
        after-render-queue-cell    (make-after-render-queue-cell)
        after-render-set-tick-ref  (make-after-render-set-tick-ref)
        after-render-sentinel      (make-after-render-sentinel
                                     after-render-queue-cell
                                     after-render-set-tick-ref)
        after-render-hook          (make-after-render-hook
                                     after-render-queue-cell
                                     after-render-set-tick-ref)
        subscribe-cont     (make-subscribe-container gensym-prefix-sub)
        make-derived       (make-derived-value-fn gensym-prefix-derived)
        ;; Precompute the `use-subscribe` watch-key keyword namespace
        ;; once (outside the render hot path). The per-reaction key
        ;; derives from `(hash reaction)` so the subscribe-fn closure
        ;; pays one hash + one keyword intern per reaction-identity
        ;; change — no process-wide gensym counter tick per render.
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
                              :emitter-cell      emitter-cell})
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
                reaction
                (use-memo (fn []
                            (subs/subscribe stable-frame-kw stable-query-v))
                          #js [stable-key])
                ;; The store-snapshot fn React calls on every render to
                ;; detect tearing. Pure deref of the reaction.
                get-snap
                (use-callback (fn [] (when reaction @reaction))
                              #js [reaction])
                ;; The store-subscribe fn — React calls it once with a
                ;; force-update callback; we wire that up to add-watch
                ;; on the reaction's underlying container.
                subscribe-fn
                (use-callback
                  (fn [on-change]
                    ;; Hash-of-reaction-identity keyword rather than a
                    ;; fresh `gensym` per call — stable for the
                    ;; reaction's lifetime, sidesteps the process-wide
                    ;; gensym counter, unique across distinct reactions.
                    (let [k (keyword use-sub-watch-ns (str (hash reaction)))]
                      (when reaction
                        (add-watch reaction k (fn [_ _ _ _] (on-change))))
                      (fn unsubscribe []
                        (when reaction (remove-watch reaction k)))))
                  #js [reaction])]
            ;; Pair the memoised `subs/subscribe` (above) with an
            ;; explicit `subs/unsubscribe` on unmount / key-change so
            ;; the sub-cache's ref-count is decremented when the
            ;; component drops the reaction. Pre-rf2-7g959 the cleanup
            ;; was implicit only via the useSyncExternalStore
            ;; subscribe-fn's `remove-watch` — which freed the React
            ;; listener but left the sub-cache entry pinned at
            ;; ref-count 1 for the rest of the process. Per
            ;; Spec 006 §Reference counting and disposal.
            ;;
            ;; Memo can rebuild on key change; pairing the subscribe
            ;; with a useEffect keyed on the same deps means React
            ;; fires the previous deps' cleanup before the new effect,
            ;; so dec-then-inc happens in order and the sub-cache
            ;; grace-period rules absorb any momentary 0 ref-count.
            (React/useEffect
              (fn use-subscribe-effect []
                (fn cleanup []
                  (subs/unsubscribe stable-frame-kw stable-query-v)))
              #js [stable-key])
            (React/useSyncExternalStore subscribe-fn get-snap get-snap)))
        use-subscribe
        (fn use-subscribe
          ([query-v] (use-subscribe-2 (use-current-frame) query-v))
          ([frame-kw query-v] (use-subscribe-2 frame-kw query-v)))]
    {:emitter-cell                emitter-cell
     :warn-cache                  warn-cache
     :active-roots-cell           active-roots-cell
     :make-state-container        make-state-container
     :read-container              read-container
     :replace-container!          replace-container!
     :subscribe-container         subscribe-cont
     :make-derived-value          make-derived
     :render                      render-fn
     :render-to-string            (make-render-to-string emitter-cell)
     :register-context-provider   register-context-provider
     :dispose-adapter!            dispose-fn
     :set-hiccup-emitter!         (fn set-it! [f]
                                    (set-hiccup-emitter! emitter-cell f))
     :use-current-frame           use-current-frame
     :use-subscribe               use-subscribe
     :frame-provider              frame-provider
     :flush-views!                flush-views!
     :wrap-view                   wrap-view-fn
     :clear-warned-non-dom-roots! clear-warned
     ;; rf2-334d9 — :adapter/after-render impl. Each adapter publishes
     ;; this via substrate-adapter/route-hook!.
     :after-render-hook           after-render-hook}))
