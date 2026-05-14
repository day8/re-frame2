(ns re-frame.substrate.spine
  "Shared substrate-spine helpers for React-shaped adapters that lack a
  native reactive-atom primitive (UIx, Helix, and any future
  minimal-React-wrapper substrate). Per rf2-3vwbx — the duplication
  between UIx and Helix at the substrate-contract layer was byte-for-byte
  identical modulo gensym prefixes, hook ns, and substrate-name strings.
  This ns lifts the shared body into one place; the adapter ns wires its
  per-substrate hooks via `make-react-spine`.

  Scope. This ns provides:

    * The plain-`atom` container quartet (make / read / replace / subscribe).
    * `make-derived-value` (one watch per source; reifies Reagent's
      `IDisposable`).
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
            [reagent.ratom       :as ratom]
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

(defn make-derived-value-fn
  "Return a `make-derived-value` fn that tags per-source watch keys with
  the given `gensym-prefix`. The fn signature matches the substrate
  contract: `(sources compute-fn) -> derived-container`."
  [gensym-prefix]
  (fn make-derived-value [source-containers compute-fn]
    (let [recompute      (fn [] (apply compute-fn (map deref source-containers)))
          watchers       (atom {})           ;; user-key → wrapper-fn
          on-dispose-fns (atom [])
          ;; Per-source wrapper keys we own so dispose can unwire them.
          own-keys       (atom {})           ;; source → key
          notify         (fn [prev nu]
                           (when (not= prev nu)
                             (doseq [[_ w] @watchers] (w prev nu))))]
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
      ;; (per rf2-66hb)
      ;;
      ;; `prev-state` is written and read only inside the watch callback
      ;; (single-threaded JS / Reagent reactivity) and never escapes this
      ;; closure, so a `volatile!` is the right primitive — no CAS cost.
      ;; (per rf2-eiux0)
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
        ;; Reagent's IDisposable — `interop/add-on-dispose!` calls into
        ;; this protocol when wiring the sub-cache's slot teardown
        ;; (per Spec 006 §subscription-cache). Adopting the Reagent
        ;; protocol keeps the cache wiring substrate-agnostic at the
        ;; call site — core does not branch on adapter type.
        ratom/IDisposable
        (dispose! [_]
          (doseq [[s k] @own-keys] (remove-watch s k))
          (reset! own-keys {})
          (reset! watchers {})
          (doseq [f @on-dispose-fns] (f))
          (reset! on-dispose-fns []))
        (add-on-dispose! [_ f]
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
  clobbering each other's tracking. Per rf2-9fdkb."
  []
  (atom #{}))

(defn make-render
  "Build a `render` fn that registers every mounted React root in
  `active-roots-cell` and returns an unmount thunk that removes the
  root from the cell before calling `.unmount`. Per rf2-9fdkb."
  [active-roots-cell]
  (fn render [render-tree mount-point opts]
    (let [hydrate? (boolean (:hydrate? opts))
          root     (if hydrate?
                     (react-dom-client/hydrateRoot mount-point render-tree)
                     (let [r (react-dom-client/createRoot mount-point)]
                       (.render r render-tree)
                       r))]
      (swap! active-roots-cell conj root)
      (fn unmount []
        (swap! active-roots-cell disj root)
        (.unmount root)))))

(defn make-dispose-adapter!
  "Build a `dispose-adapter!` fn that drains `active-roots-cell` by
  calling `.unmount` on every tracked React root and clears the
  spine's per-adapter caches:

    * the active-roots set is emptied (post-unmount),
    * the warn-once cache is emptied (so a subsequent install does not
      inherit stale warn-once state from a prior lifecycle),
    * the hiccup-emitter cell is cleared.

  Per Spec 006 §Adapter disposal lifecycle (rf2-9fdkb). React's
  `.unmount` is idempotent / no-op on already-unmounted roots; we
  swallow any unmount throw so one misbehaving root does not strand
  the rest of the drain."
  [{:keys [active-roots-cell warn-cache emitter-cell]}]
  (fn dispose-adapter! []
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
                      {:reason      "use the plain-atom adapter on the JVM for SSR, or install via set-hiccup-emitter!"
                       :render-tree render-tree})))))

;; ---- context provider -----------------------------------------------------
;;
;; Per rf2-3yij / rf2-2qit Decision 2: every React-shaped adapter shares
;; the same React.createContext object (it lives in
;; re-frame.adapter.context). The provider component is identical across
;; substrates — only `use-current-frame` (the read-side hook) varies, and
;; only by which hook ns supplies `use-context`.

(defn frame-provider
  "User-facing component scoping `frame-kw` to its subtree. Wraps
  children in the shared frame Context Provider — inside the subtree,
  `(rf/dispatcher)` / `(rf/subscriber)` / `reg-view`-registered
  descendants resolve to the named frame. Per Spec 002 §What
  `frame-provider` is.

  Reads `:frame` from props. When missing or `nil`, falls through to
  `:rf/default` (per rf2-sixo — defensive default that matches the
  no-provider behaviour and avoids breaking tooling-generated trees that
  elide the prop)."
  [{:keys [frame children]}]
  (let [frame-kw (or frame :rf/default)]
    (apply adapter-context/provider-element frame-kw
           (if (sequential? children) children [children]))))

(defn register-context-provider
  "Substrate `:register-context-provider` slot. Same shape across all
  React-shaped adapters: returns the `frame-provider` fn unchanged. The
  frame-keyword arg is ignored because the frame keyword lives in the
  Provider's :value at render time (rf2-4y60), not in a build-time
  closure."
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

(defn make-warn-once-cache
  "Return an `(atom #{})` for tracking per-id warn-once emission. Each
  adapter owns its own cache so multiple adapters can coexist in test
  bundles without clobbering each other's warn-once state."
  []
  (atom #{}))

(defn make-clear-warned-fn
  "Return a thunk that resets `cache-atom` to `#{}` and returns nil.
  Tests use this between cases (via `reset-runtime-fixture` and the
  chained `:adapter/clear-warn-once-caches!` hook) so a sibling test's
  first-encounter warning cannot silently swallow a later test's same-id
  warning. Per rf2-4edk."
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
      (when (exists? js/console)
        (.warn js/console
          (str "[re-frame] reg-view " id " — root element is "
               (pr-str type-tag) " (" substrate-name "); "
               "data-rf2-source-coord skipped "
               "(Spec 006 §Source-coord annotation: pair tools fall back to "
               ":rf/id for non-DOM roots)."))))))

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
  cloneElement call that adds `:data-rf2-source-coord`. Non-element
  outputs (nil, fragment, function-component head) emit a one-shot
  warning per id and pass through unchanged. Per Spec 006 §Source-coord
  annotation."
  [warn-fn id coord-attr out]
  (cond
    (dom-element? out)
    (let [props    (.-props out)
          existing (when props (aget props "data-rf2-source-coord"))]
      (if existing
        out
        (React/cloneElement out #js {"data-rf2-source-coord" coord-attr})))

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
  `data-rf2-source-coord` on the rendered root DOM element when
  `interop/debug-enabled?` is true. Production builds elide via
  `interop/debug-enabled?` per Spec 009 §Production builds."
  [warn-fn]
  (fn wrap-view [id metadata user-fn]
    (if interop/debug-enabled?
      (let [coord-attr (format-source-coord id metadata)]
        (fn wrapped-user-fn [& args]
          (let [out (apply user-fn args)]
            (inject-source-coord-attr warn-fn id coord-attr out))))
      user-fn)))

(defn install-clear-warn-once-step!
  "Wire `clear-fn` into the chained `:adapter/clear-warn-once-caches!`
  late-bind hook. The hook is chained — each adapter and re-frame.views
  contribute a clear-step; `reset-runtime-fixture` invokes the top of
  the chain and every contributor's reset runs. Per rf2-4edk."
  [clear-fn]
  (let [previous (late-bind/get-fn :adapter/clear-warn-once-caches!)]
    (late-bind/set-fn! :adapter/clear-warn-once-caches!
      (fn chained-clear-warn-once-caches! []
        (clear-fn)
        (when previous (previous))
        nil))))

;; ---- subscription hook ----------------------------------------------------
;;
;; Per Spec 006 §subscription-cache (rf2-3yij Decision 1 / rf2-2qit
;; Decision 1) `use-subscribe` is the substrate-idiomatic hook surface
;; for reading a sub. It wraps React.useSyncExternalStore so updates are
;; scheduled by React's concurrent renderer rather than a per-component
;; scheduler.
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
        subscribe-cont     (make-subscribe-container gensym-prefix-sub)
        make-derived       (make-derived-value-fn gensym-prefix-derived)
        wrap-view-fn       (make-wrap-view warn-fn)
        render-fn          (make-render active-roots-cell)
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
        use-subscribe-2
        (fn use-subscribe-2 [frame-kw query-v]
          (let [reaction
                (use-memo (fn [] (subs/subscribe frame-kw query-v))
                          #js [frame-kw query-v])
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
                    (let [k (gensym gensym-prefix-use-sub)]
                      (when reaction
                        (add-watch reaction k (fn [_ _ _ _] (on-change))))
                      (fn unsubscribe []
                        (when reaction (remove-watch reaction k)))))
                  #js [reaction])]
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
     :clear-warned-non-dom-roots! clear-warned}))
