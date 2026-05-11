(ns re-frame.adapter.helix
  "The Helix adapter — the third canonical browser substrate (rf2-2qit).
  Per Spec 006 §CLJS reference: Helix as alternative substrate.

  Ships in its own Maven artefact (day8/re-frame2-helix) per
  Spec 006 §Adapter shipping convention (rf2-0hxm). Apps that use Helix
  depend on both day8/re-frame2 (core) and this artefact; apps
  targeting Reagent depend on day8/re-frame2-reagent instead; apps
  targeting UIx depend on day8/re-frame2-uix instead. Core does
  *not* :require this ns — the dependency direction is adapter → core.

  Per rf2-2qit Decision 2 the React frame-context lives in
  `re-frame.adapter.context` (CLJS-only file in core); this adapter
  consumes the *same* createContext object the Reagent and UIx
  adapters consume, so a future mixed-substrate app's frame-provider
  chain composes across substrates.

  Per rf2-2qit Decision 8 we target the Helix 0.2.x line. The eight
  decisions transferred from rf2-3yij (the UIx adapter) carry over
  unchanged — Helix is structurally similar to UIx (React + hooks; no
  reactive-atom primitive)."
  (:require ["react"             :as React]
            ["react-dom/client"  :as react-dom-client]
            [reagent.core        :as r]
            [reagent.ratom       :as ratom]
            [helix.core          :as helix]
            [helix.hooks         :as helix-hooks]
            [re-frame.frame      :as frame]
            [re-frame.interop    :as interop]
            [re-frame.late-bind  :as late-bind]
            [re-frame.subs       :as subs]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.adapter.context :as adapter-context]))

;; ---- container ------------------------------------------------------------
;;
;; Per Spec 006 §revertibility-constraints the container holds the
;; frame's app-db value and *only* the frame's app-db value. Helix
;; itself doesn't ship a reactive atom primitive (it is the
;; minimal-React-wrapper niche per the rf2-2qit bead) so we lean on a
;; plain `clojure.core/atom` and broadcast changes via `add-watch` —
;; observably equivalent to the Reagent adapter's r/atom for the
;; substrate contract surface (read, replace, subscribe). Reactive
;; view-side hookup happens through `useSyncExternalStore` in
;; `use-subscribe` below, not through Reagent reactions.

(defn- make-state-container [initial-value]
  (atom initial-value))

(defn- read-container [container]
  @container)

(defn- replace-container! [container new-value]
  (reset! container new-value)
  nil)

(defn- subscribe-container [container on-change]
  (let [k (gensym "rf-helix-sub-")]
    (add-watch container k (fn [_ _ prev nu] (on-change prev nu)))
    (fn unsubscribe [] (remove-watch container k))))

;; ---- derived value --------------------------------------------------------
;;
;; Helix has no reaction primitive (same shape as UIx). The substrate
;; contract requires that (read-container) on a derived container
;; deref a fresh value computed from the sources; subscribe-container
;; on a derived container fires when any source changes. We satisfy
;; both via a thin IDeref wrapper whose change-broadcasting fan-out is
;; one watch per source.
;;
;; Equality-on-= is preserved by the core's sub-cache invalidation
;; algorithm (Spec 006 §Invalidation algorithm Phase 2): the sub-cache
;; only re-emits when the recomputed value differs from the cached one
;; by =. The derived container itself does not memoise; per the same
;; spec section that's the cache's job, not the substrate's.

(defn- make-derived-value [source-containers compute-fn]
  (let [recompute     (fn [] (apply compute-fn (map deref source-containers)))
        watchers      (atom {})            ;; user-key → wrapper-fn
        on-dispose-fns (atom [])
        ;; Per-source wrapper keys we own so dispose can unwire them.
        own-keys      (atom {})            ;; source → key
        notify        (fn [prev nu]
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
    (let [prev-state (atom (recompute))]
      (doseq [s source-containers]
        (let [k (gensym "rf-helix-derived-")]
          (swap! own-keys assoc s k)
          (add-watch s k
            (fn [_ _ _ _]
              (let [new-derived (recompute)
                    prev-derived @prev-state]
                (reset! prev-state new-derived)
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
        (swap! on-dispose-fns conj f)))))

;; ---- render ---------------------------------------------------------------
;;
;; Helix doesn't ship a `helix.dom/render-root` wrapper — Helix is the
;; minimal-React-wrapper substrate. We call react-dom/client directly,
;; which is also the path the UIx adapter takes for cross-version
;; stability. createRoot + .render for fresh mounts; hydrateRoot for
;; the SSR-hydrate path.

(defn- render [render-tree mount-point opts]
  (let [hydrate? (boolean (:hydrate? opts))
        root     (if hydrate?
                   (react-dom-client/hydrateRoot mount-point render-tree)
                   (let [r (react-dom-client/createRoot mount-point)]
                     (.render r render-tree)
                     r))]
    (fn unmount [] (.unmount root))))

(defonce ^:private hiccup-emitter (atom nil))

(defn set-hiccup-emitter!
  "Install a render-tree → HTML fn for use by render-to-string. Idempotent.
  Helix itself doesn't render to string in browser bundles; SSR consumers
  install the hiccup emitter explicitly (mirroring the Reagent and UIx
  adapters)."
  [f]
  (reset! hiccup-emitter f))

(defn- render-to-string [render-tree opts]
  (if-let [emit @hiccup-emitter]
    (emit render-tree opts)
    (throw (ex-info ":rf.error/no-hiccup-emitter-bound"
                    {:reason "use the plain-atom adapter on the JVM for SSR, or install via set-hiccup-emitter!"
                     :render-tree render-tree}))))

;; ---- context provider -----------------------------------------------------
;;
;; Per rf2-2qit Decision 2 we share the same React.createContext object
;; with the Reagent and UIx adapters (it lives in
;; re-frame.adapter.context). Helix components consume it via
;; `helix-hooks/use-context`; the Provider is the same React Provider
;; component the other adapters target.

(defn use-current-frame
  "Helix hook returning the current frame keyword from the surrounding
  React context, or `:rf/default` when no frame-provider sits above.

  This is the Helix counterpart of `re-frame.views/current-frame`'s
  React-context tier — Decision 2 mandates every React-shaped adapter
  resolves the *same* context, so a Helix subtree under a Reagent or
  UIx frame-provider sees the right frame and vice versa."
  []
  (helix-hooks/use-context adapter-context/frame-context))

(defn frame-provider
  "User-facing Helix component scoping `frame-kw` to its subtree.

  Returns a React element wrapping `children` in the shared frame
  Context Provider. Use:

      ($ frame-provider {:frame :session}
         ($ header)
         ($ main))

  Per Spec 002 §What `frame-provider` is — same surface as the Reagent
  and UIx variants, different rendering substrate."
  [{:keys [frame children]}]
  (let [frame-kw (or frame :rf/default)]
    (apply adapter-context/provider-element frame-kw
           (if (sequential? children) children [children]))))

(defn- register-context-provider [_frame-keyword]
  ;; Same shape as the Reagent and UIx adapters: returns a built
  ;; component (here, a React-element-producing fn). The arg is ignored
  ;; because the frame keyword lives in the Provider's :value at render
  ;; time (rf2-4y60), not in a build-time closure.
  frame-provider)

;; ---- subscription hook ----------------------------------------------------
;;
;; Per Spec 006 §subscription-cache and rf2-2qit Decision 1 (transferred
;; from rf2-3yij), `use-subscribe` is the Helix-idiomatic hook surface
;; for reading a sub. It wraps React.useSyncExternalStore so updates
;; are scheduled by React's concurrent renderer rather than Reagent's
;; per-component scheduler. Helix doesn't ship a use-syncExternalStore
;; wrapper of its own (it is the minimal-React-wrapper niche), so we
;; use React's hook directly — same path the UIx adapter takes.
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
;; Per rf2-2qit Decision 3 there is no auto-injection: components
;; written for Helix call `(use-subscribe [:foo])` directly, the way
;; they would call any other React hook.

(defn- subscribe-snapshot [reaction]
  (when reaction @reaction))

(defn use-subscribe
  "Helix hook that reads a re-frame subscription. Returns the current
  value; re-renders the calling component when the value changes.

  Frame resolution: reads the surrounding frame-provider's keyword via
  `use-context` (rf2-2qit Decision 2). Override via the 2-arg form to
  pin to an explicit frame-id.

  Per rf2-2qit Decision 1 the hook is named `use-subscribe` to match
  the React/Helix idiom — symmetric ergonomics to Reagent's
  `(rf/subscribe ...)` deref shape, asymmetric naming (hooks live in
  hook-named space)."
  ([query-v]
   (let [frame-kw (use-current-frame)]
     (use-subscribe frame-kw query-v)))
  ([frame-kw query-v]
   (let [reaction (helix-hooks/use-memo* (fn [] (subs/subscribe frame-kw query-v))
                                         #js [frame-kw query-v])
         ;; The store-snapshot fn React calls on every render to detect
         ;; tearing. Pure deref of the reaction.
         get-snap (helix-hooks/use-callback* (fn [] (subscribe-snapshot reaction))
                                             #js [reaction])
         ;; The store-subscribe fn — React calls it once with a
         ;; force-update callback; we wire that up to add-watch on the
         ;; reaction's underlying container.
         subscribe-fn (helix-hooks/use-callback*
                        (fn [on-change]
                          (let [k (gensym "rf-helix-use-sub-")]
                            (when reaction
                              (add-watch reaction k (fn [_ _ _ _] (on-change))))
                            (fn unsubscribe []
                              (when reaction (remove-watch reaction k)))))
                        #js [reaction])]
     (React/useSyncExternalStore subscribe-fn get-snap get-snap))))

;; ---- render flush for tests (rf2-2qit Decision 6) -------------------------
;;
;; `flush-views!` wraps React's `act()` so test code can drive a
;; subscribe → re-render cycle synchronously. Tests that drive Helix
;; components from a test runner call `(flush-views!)` after a dispatch
;; to flush pending React effects before reading the DOM.

(defn flush-views!
  "Flush pending Helix renders synchronously. Wraps React's act() —
  intended for test code only. Calls (act f) where f is the body
  thunk; with no arg, calls (act (fn [] nil)) to flush pending effects.

  Per rf2-2qit Decision 6: the canonical test-flush hook for
  Helix-based apps. Reagent has no analogous public surface — Reagent
  tests rely on r/flush; Helix tests rely on this."
  ([] (flush-views! (fn [] nil)))
  ([f]
   (when-let [act (or (.-act React)
                      ;; React 18 moved act into react-dom/test-utils;
                      ;; if not on React directly, swallow gracefully.
                      nil)]
     (act f))
   nil))

;; ---- source-coord wrapper (rf2-2qit Decision 5; Spec 006 §Source-coord) --
;;
;; Per Spec 006 §Source-coord annotation (rf2-z7f7 / rf2-z9n1) every
;; React-shaped substrate adapter MUST inject
;; `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on each registered
;; view's root DOM element when `interop/debug-enabled?` is true.
;;
;; The Reagent adapter walks hiccup output and merges the attr; the
;; Helix adapter (like the UIx adapter) walks Helix's `$` output (a
;; React element) and clones the root with the attr added —
;; React.cloneElement preserves the original component, props, and
;; children while letting us layer one extra prop on top.
;;
;; Production-elision contract (rf2-z7f7 / Spec 009): the entire branch
;; sits inside `(when interop/debug-enabled? ...)` so the closure
;; compiler constant-folds the wrapper away under :advanced +
;; goog.DEBUG=false. The bundle-grep test confirms the
;; `data-rf2-source-coord` literal is absent from production builds.

(defn- format-source-coord
  "Render captured registry coords as the attribute value shape
  `<ns>:<sym>:<line>:<col>`. Mirrors re-frame.views/format-source-coord
  so DOM output is identical across the three substrates."
  [id coords]
  (let [ns-part  (or (namespace id) "?")
        sym-part (name id)
        line     (:line coords)
        col      (:column coords)]
    (str ns-part ":" sym-part ":"
         (if line (str line) "?")
         ":"
         (if col (str col) "?"))))

(defonce ^:private warned-non-dom-roots (atom #{}))

(defn- warn-non-dom-root!
  "Emit a one-shot warning per id that a registered view's root is
  exempt from source-coord injection — a Fragment, a function/class
  component, or a non-element value. Pair tools fall back to the
  registry's `:rf/id` for these cases (Spec 006 §Source-coord
  annotation, Fragment exemption)."
  [id type-tag]
  (when-not (contains? @warned-non-dom-roots id)
    (swap! warned-non-dom-roots conj id)
    (when (exists? js/console)
      (.warn js/console
        (str "[re-frame] reg-view " id " — root element is "
             (pr-str type-tag) " (Helix); data-rf2-source-coord skipped "
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
  cloneElement call that adds `:data-rf2-source-coord`. Non-element
  outputs (nil, fragment, function-component head) emit a one-shot
  warning per id and pass through unchanged. Per Spec 006 §Source-coord
  annotation."
  [id coord-attr out]
  (cond
    (dom-element? out)
    (let [props        (.-props out)
          existing     (when props (aget props "data-rf2-source-coord"))
          merged-props (if existing
                         #js {}
                         #js {"data-rf2-source-coord" coord-attr})]
      (if existing
        out
        (React/cloneElement out merged-props)))

    :else
    (do
      (when (some? out)
        (warn-non-dom-root! id (some-> out .-type)))
      out)))

(defn wrap-view
  "Wrap a Helix-shape user component in a function component that
  injects `data-rf2-source-coord` on the rendered root DOM element
  (when `interop/debug-enabled?` is true). Returned fn has the same
  call signature as `user-fn` and is suitable for use as a Helix
  component head.

  Per rf2-2qit Decision 5 + Spec 006 §Source-coord annotation: this is
  the Helix-side equivalent of the Reagent adapter's
  `inject-source-coord-attr` walk and the UIx adapter's `wrap-view`.
  Production builds elide via `interop/debug-enabled?` per Spec 009
  §Production builds."
  [id metadata user-fn]
  (let [coord-attr (when interop/debug-enabled?
                     (format-source-coord id metadata))]
    (fn wrapped-user-fn [& args]
      (let [out (apply user-fn args)]
        (if interop/debug-enabled?
          (inject-source-coord-attr id coord-attr out)
          out)))))

;; ---- disposal -------------------------------------------------------------

(defn- dispose-adapter! []
  ;; Helix itself holds no reaction caches — view re-renders are React's
  ;; concern. Watches on derived containers GC with the containers
  ;; themselves. Nothing special to do here.
  nil)

(def adapter
  "The Helix adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.adapter.helix :as helix])
      (rf/init! helix/adapter)

  See Spec 006 §CLJS reference: Helix as alternative substrate.
  Implements the same nine-fn contract as re-frame.adapter.reagent
  and re-frame.adapter.uix. Per rf2-agql there is no default-adapter
  registry — adapter wiring is explicit at the call site."
  {:make-state-container      make-state-container
   :read-container            read-container
   :replace-container!        replace-container!
   :subscribe-container       subscribe-container
   :make-derived-value        make-derived-value
   :render                    render
   :render-to-string          render-to-string
   :register-context-provider register-context-provider
   :dispose-adapter!          dispose-adapter!})

;; Per rf2-d4sf: publish the function-component-shape React-context-
;; aware `current-frame` impl through the late-bind hook so
;; `re-frame.subs/subscribe` and the dispatch envelope's `:frame`
;; default consult the React-context tier of the 3-tier resolution
;; chain (dynamic var → React context → :rf/default). Helix renders
;; function components — they have no class-component-specific
;; `(.-context cmp)` slot, so the shared impl in
;; `re-frame.adapter.context` reads `_currentValue` directly. Helix's
;; own `use-current-frame` hook is sugar over the same read, so
;; subscribe / dispatch and `use-context` agree on the active frame.
;; Reagent registers a different impl (in `re-frame.adapter.reagent`)
;; that uses `(.-context cmp)` on the in-flight Reagent component.
;;
;; Per rf2-0d35: route `:adapter/current-frame` through the installed
;; adapter rather than blindly overwriting at ns-load time. In test
;; bundles that load multiple adapter ns's, a plain `set-fn!` would
;; mean only the last-loaded adapter's reader survives — see
;; reagent.cljs for the full rationale.
(let [previous (late-bind/get-fn :adapter/current-frame)]
  (late-bind/set-fn! :adapter/current-frame
    (fn helix-adapter-current-frame-routed []
      (if (identical? adapter (substrate-adapter/current-adapter))
        (adapter-context/function-component-current-frame)
        (if previous
          (previous)
          (frame/current-frame))))))

;; Per rf2-s36l: publish the reactive-substrate surfaces consumed by
;; `re-frame.interop` through the late-bind hook table. Helix's derived
;; values (this ns, ~line 124) reify stock `reagent.ratom/IDisposable`
;; — the cache wiring at `re-frame.subs` calls `interop/add-on-dispose!`
;; against them and the protocol dispatch finds the reify shape.
;; Accordingly Helix's hooks delegate to stock Reagent's `r/atom`,
;; `ratom/make-reaction`, etc. Helix itself ships no reactive-atom
;; primitive (it is the minimal-React-wrapper niche per rf2-2qit),
;; so `interop/make-reaction` under a Helix-installed app falls back
;; to stock Reagent's.
;;
;; Per rf2-0d35: route through the installed adapter, mirroring the
;; `:adapter/current-frame` pattern above. Helix's impl runs only when
;; the Helix adapter is the `(rf/init!)`-installed one; otherwise the
;; hook chains to a previously-registered reader.
(let [previous (late-bind/get-fn :adapter/ratom)]
  (late-bind/set-fn! :adapter/ratom
    (fn helix-adapter-ratom-routed [v]
      (if (identical? adapter (substrate-adapter/current-adapter))
        (r/atom v)
        (when previous (previous v))))))

(let [previous (late-bind/get-fn :adapter/ratom?)]
  (late-bind/set-fn! :adapter/ratom?
    (fn helix-adapter-ratom?-routed [x]
      (if (identical? adapter (substrate-adapter/current-adapter))
        (satisfies? ratom/IReactiveAtom ^js x)
        (if previous (previous x) false)))))

(let [previous (late-bind/get-fn :adapter/make-reaction)]
  (late-bind/set-fn! :adapter/make-reaction
    (fn helix-adapter-make-reaction-routed [f]
      (if (identical? adapter (substrate-adapter/current-adapter))
        (ratom/make-reaction f)
        (when previous (previous f))))))

(let [previous (late-bind/get-fn :adapter/add-on-dispose!)]
  (late-bind/set-fn! :adapter/add-on-dispose!
    (fn helix-adapter-add-on-dispose!-routed [a-ratom f]
      (if (identical? adapter (substrate-adapter/current-adapter))
        (ratom/add-on-dispose! a-ratom f)
        (when previous (previous a-ratom f))))))

(let [previous (late-bind/get-fn :adapter/dispose!)]
  (late-bind/set-fn! :adapter/dispose!
    (fn helix-adapter-dispose!-routed [a-ratom]
      (if (identical? adapter (substrate-adapter/current-adapter))
        (ratom/dispose! a-ratom)
        (when previous (previous a-ratom))))))

(let [previous (late-bind/get-fn :adapter/reactive?)]
  (late-bind/set-fn! :adapter/reactive?
    (fn helix-adapter-reactive?-routed []
      (if (identical? adapter (substrate-adapter/current-adapter))
        (ratom/reactive?)
        (if previous (previous) false)))))

(let [previous (late-bind/get-fn :adapter/after-render)]
  (late-bind/set-fn! :adapter/after-render
    (fn helix-adapter-after-render-routed [f]
      (if (identical? adapter (substrate-adapter/current-adapter))
        (r/after-render f)
        (when previous (previous f))))))
