(ns re-frame.adapter.uix
  "The UIx adapter — the second canonical browser substrate (rf2-3yij).
  Per Spec 006 §CLJS reference: UIx as alternative substrate.

  Ships in its own Maven artefact (day8/re-frame2-uix) per
  Spec 006 §Adapter shipping convention (rf2-0hxm). Apps that use UIx
  depend on both day8/re-frame2 (core) and this artefact; apps
  targeting Reagent depend on day8/re-frame2-reagent instead.
  Core does *not* :require this ns — the dependency direction is
  adapter → core.

  Per rf2-3yij Decision 2 the React frame-context lives in
  `re-frame.adapter.context` (CLJS-only file in core); this adapter
  consumes the *same* createContext object the Reagent adapter
  consumes, so a future mixed-substrate app's frame-provider chain
  composes across substrates.

  Per rf2-3yij Decision 8 we target UIx 2.x (hooks-based)."
  (:require ["react"          :as React]
            ["react-dom/client" :as react-dom-client]
            [reagent.core     :as r]
            [reagent.ratom    :as ratom]
            [uix.core         :as uix]
            [uix.dom          :as uix-dom]
            [re-frame.frame   :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.subs    :as subs]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.adapter.context :as adapter-context]))

;; ---- container ------------------------------------------------------------
;;
;; Per Spec 006 §revertibility-constraints the container holds the
;; frame's app-db value and *only* the frame's app-db value. UIx itself
;; doesn't ship a reactive atom primitive (its hook substrate is React
;; state) so we lean on a plain `clojure.core/atom` and broadcast
;; changes via `add-watch` — observably equivalent to the Reagent
;; adapter's r/atom for the substrate contract surface (read, replace,
;; subscribe). Reactive view-side hookup happens through
;; `useSyncExternalStore` in `use-subscribe` below, not through Reagent
;; reactions.

(defn- make-state-container [initial-value]
  (atom initial-value))

(defn- read-container [container]
  @container)

(defn- replace-container! [container new-value]
  (reset! container new-value)
  nil)

(defn- subscribe-container [container on-change]
  (let [k (gensym "rf-uix-sub-")]
    (add-watch container k (fn [_ _ prev nu] (on-change prev nu)))
    (fn unsubscribe [] (remove-watch container k))))

;; ---- derived value --------------------------------------------------------
;;
;; UIx has no reaction primitive. The substrate contract requires that
;; (read-container) on a derived container deref a fresh value computed
;; from the sources; subscribe-container on a derived container fires
;; when any source changes. We satisfy both via a thin IDeref wrapper
;; whose change-broadcasting fan-out is one watch per source.
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
        (let [k (gensym "rf-uix-derived-")]
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
      ;; (per Spec 006 §subscription-cache and the rf2-3yij
      ;; cross-substrate-cache decision). Adopting the Reagent
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
;; UIx 2.x ships uix.dom/render-root (a thin wrapper over React 18's
;; ReactDOM.createRoot + root.render). For interop with the existing
;; reagent.dom.client/hydrate-root path we use react-dom/client
;; directly — uix.dom doesn't expose hydrate-root in every version and
;; the lower-level call is stable across UIx 2.x point releases.

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
  UIx itself doesn't render to string in browser bundles; SSR consumers
  install the hiccup emitter explicitly (mirroring the Reagent adapter)."
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
;; Per rf2-3yij Decision 2 we share the same React.createContext object
;; with the Reagent adapter (it lives in re-frame.adapter.context).
;; UIx components consume it via `uix/use-context`; the Provider is the
;; same React Provider component the Reagent adapter targets.

(defn use-current-frame
  "UIx hook returning the current frame keyword from the surrounding
  React context, or `:rf/default` when no frame-provider sits above.

  This is the UIx counterpart of `re-frame.views/current-frame`'s
  React-context tier — Decision 2 mandates both adapters resolve the
  same context, so a UIx subtree under a Reagent frame-provider sees
  the right frame and vice versa."
  []
  (uix/use-context adapter-context/frame-context))

(defn frame-provider
  "User-facing UIx component scoping `frame-kw` to its subtree.

  Returns a React element wrapping `children` in the shared frame
  Context Provider. Use:

      ($ frame-provider {:frame :session}
         ($ header)
         ($ main))

  Per Spec 002 §What `frame-provider` is — same surface as the Reagent
  variant, different rendering substrate."
  [{:keys [frame children]}]
  (let [frame-kw (or frame :rf/default)]
    (apply adapter-context/provider-element frame-kw
           (if (sequential? children) children [children]))))

(defn- register-context-provider [_frame-keyword]
  ;; Same shape as the Reagent adapter: returns a built component
  ;; (here, a React-element-producing fn). The arg is ignored because
  ;; the frame keyword lives in the Provider's :value at render time
  ;; (rf2-4y60), not in a build-time closure.
  frame-provider)

;; ---- subscription hook ----------------------------------------------------
;;
;; Per Spec 006 §subscription-cache and rf2-3yij Decision 1, `use-subscribe`
;; is the UIx-idiomatic hook surface for reading a sub. It wraps
;; React.useSyncExternalStore so updates are scheduled by React's
;; concurrent renderer rather than Reagent's per-component scheduler.
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
;; Per rf2-3yij Decision 3 there is no auto-injection: components
;; written for UIx call `(use-subscribe [:foo])` directly, the way they
;; would call any other React hook.

(defn- subscribe-snapshot [reaction]
  (when reaction @reaction))

(defn use-subscribe
  "UIx hook that reads a re-frame subscription. Returns the current
  value; re-renders the calling component when the value changes.

  Frame resolution: reads the surrounding frame-provider's keyword via
  `use-context` (rf2-3yij Decision 2). Override via the 2-arg form to
  pin to an explicit frame-id.

  Per rf2-3yij Decision 1 the hook is named `use-subscribe` to match
  the React/UIx idiom — symmetric ergonomics to Reagent's
  `(rf/subscribe ...)` deref shape, asymmetric naming (hooks live in
  hook-named space)."
  ([query-v]
   (let [frame-kw (use-current-frame)]
     (use-subscribe frame-kw query-v)))
  ([frame-kw query-v]
   (let [reaction (uix/use-memo
                    (fn [] (subs/subscribe frame-kw query-v))
                    [frame-kw query-v])
         ;; The store-snapshot fn React calls on every render to detect
         ;; tearing. Pure deref of the reaction.
         get-snap (uix/use-callback
                    (fn [] (subscribe-snapshot reaction))
                    [reaction])
         ;; The store-subscribe fn — React calls it once with a
         ;; force-update callback; we wire that up to add-watch on the
         ;; reaction's underlying container.
         subscribe-fn (uix/use-callback
                        (fn [on-change]
                          (let [k (gensym "rf-uix-use-sub-")]
                            (when reaction
                              (add-watch reaction k (fn [_ _ _ _] (on-change))))
                            (fn unsubscribe []
                              (when reaction (remove-watch reaction k)))))
                        [reaction])]
     (React/useSyncExternalStore subscribe-fn get-snap get-snap))))

;; ---- render flush for tests (rf2-3yij Decision 6) -------------------------
;;
;; `flush-views!` wraps React's `act()` so test code can drive a
;; subscribe → re-render cycle synchronously. Tests that drive UIx
;; components from a test runner call `(flush-views!)` after a dispatch
;; to flush pending React effects before reading the DOM.

(defn flush-views!
  "Flush pending UIx renders synchronously. Wraps React's act() —
  intended for test code only. Calls (act f) where f is the body
  thunk; with no arg, calls (act (fn [] nil)) to flush pending effects.

  Per rf2-3yij Decision 6: the canonical test-flush hook for UIx-based
  apps. Reagent has no analogous public surface — Reagent tests rely
  on r/flush; UIx tests rely on this."
  ([] (flush-views! (fn [] nil)))
  ([f]
   (when-let [act (or (.-act React)
                      ;; React 18 moved act into react-dom/test-utils;
                      ;; if not on React directly, swallow gracefully.
                      nil)]
     (act f))
   nil))

;; ---- source-coord wrapper (rf2-3yij Decision 5; Spec 006 §Source-coord) --
;;
;; Per Spec 006 §Source-coord annotation (rf2-z7f7 / rf2-z9n1) every
;; React-shaped substrate adapter MUST inject
;; `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on each registered
;; view's root DOM element when `interop/debug-enabled?` is true.
;;
;; The Reagent adapter walks hiccup output and merges the attr; the
;; UIx adapter walks UIx's `$` output (a React element) and clones the
;; root with the attr added — React.cloneElement preserves the original
;; component, props, and children while letting us layer one extra prop
;; on top.
;;
;; Production-elision contract (rf2-z7f7 / Spec 009): the entire branch
;; sits inside `(when interop/debug-enabled? ...)` so the closure
;; compiler constant-folds the wrapper away under :advanced +
;; goog.DEBUG=false. The bundle-grep test confirms the
;; `data-rf2-source-coord` literal is absent from production builds.

(defn- format-source-coord
  "Render captured registry coords as the attribute value shape
  `<ns>:<sym>:<line>:<col>`. Mirrors re-frame.views/format-source-coord
  so DOM output is identical across the two substrates."
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

(defn clear-warned-non-dom-roots!
  "Reset the warn-once cache for non-DOM-root warnings. Tests use this
  between cases (via `reset-runtime-fixture` and the chained
  `:adapter/clear-warn-once-caches!` hook) so a sibling test's first-
  encounter warning cannot silently swallow a later test's same-id
  warning. Per rf2-4edk — the cache is a process-wide `defonce` so
  the user-facing warn-once UX is unchanged in production; test-time
  clearing is the only effect."
  []
  (reset! warned-non-dom-roots #{})
  nil)

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
             (pr-str type-tag) " (UIx); data-rf2-source-coord skipped "
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
  "Wrap a UIx-shape user component in a function component that
  injects `data-rf2-source-coord` on the rendered root DOM element
  (when `interop/debug-enabled?` is true). Returned fn has the same
  call signature as `user-fn` and is suitable for use as a UIx
  component head.

  Per rf2-3yij Decision 5 + Spec 006 §Source-coord annotation: this is
  the UIx-side equivalent of the Reagent adapter's
  `inject-source-coord-attr` walk. Production builds elide via
  `interop/debug-enabled?` per Spec 009 §Production builds."
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
  ;; UIx itself holds no reaction caches — view re-renders are React's
  ;; concern. Watches on derived containers GC with the containers
  ;; themselves. Nothing special to do here.
  nil)

(def adapter
  "The UIx adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.adapter.uix :as uix])
      (rf/init! uix/adapter)

  See Spec 006 §CLJS reference: UIx as alternative substrate.
  Implements the same nine-fn contract as re-frame.adapter.reagent.
  Per rf2-agql there is no default-adapter registry — adapter wiring
  is explicit at the call site."
  {:make-state-container      make-state-container
   :read-container            read-container
   :replace-container!        replace-container!
   :subscribe-container       subscribe-container
   :make-derived-value        make-derived-value
   :render                    render
   :render-to-string          render-to-string
   :register-context-provider register-context-provider
   :dispose-adapter!          dispose-adapter!})

;; Each late-bind hook below is routed through `(substrate-adapter/
;; current-adapter)` per rf2-0d35 via `substrate-adapter/route-hook!`
;; (see that fn's docstring for the routing contract). The wrapper runs
;; this adapter's impl ONLY when the UIx adapter is the (rf/init!)-
;; installed one; otherwise it chains to the previously-registered
;; handler.
;;
;; Hook-specific rationale (UIx-adapter notes):
;;   :adapter/current-frame  — rf2-d4sf. UIx renders function
;;     components — they have no class-component (.-context cmp) slot,
;;     so the shared impl in `re-frame.adapter.context` reads
;;     `_currentValue` directly. UIx's own `use-current-frame` hook is
;;     sugar over the same read, so subscribe / dispatch and
;;     `use-context` agree on the active frame. Chain-bottom fallback
;;     is `frame/current-frame`.
;;   :adapter/ratom etc. — rf2-s36l. UIx's derived values reify stock
;;     reagent.ratom/IDisposable directly, so these hooks delegate to
;;     stock Reagent's r/atom, ratom/make-reaction, etc. UIx itself
;;     ships no reactive-atom primitive (per the rf2-3yij design).
;;   :adapter/wrap-view — rf2-00li. Substrate-side source-coord
;;     injection via React.cloneElement (the inline hiccup-walk in
;;     views.cljs would mis-classify React-element output as a non-DOM
;;     root). Production-elision: `wrap-view`'s body sits inside
;;     `(when interop/debug-enabled? ...)` so closure-folds under
;;     :advanced + goog.DEBUG=false (per Spec 009 §Production builds).
(substrate-adapter/route-hook! adapter :adapter/current-frame
  adapter-context/function-component-current-frame
  #(frame/current-frame))
(substrate-adapter/route-hook! adapter :adapter/ratom
  r/atom)
(substrate-adapter/route-hook! adapter :adapter/ratom?
  (fn ratom?-impl [x] (satisfies? ratom/IReactiveAtom ^js x))
  (constantly false))
(substrate-adapter/route-hook! adapter :adapter/make-reaction
  ratom/make-reaction)
(substrate-adapter/route-hook! adapter :adapter/add-on-dispose!
  ratom/add-on-dispose!)
(substrate-adapter/route-hook! adapter :adapter/dispose!
  ratom/dispose!)
(substrate-adapter/route-hook! adapter :adapter/reactive?
  ratom/reactive?
  (constantly false))
(substrate-adapter/route-hook! adapter :adapter/after-render
  r/after-render)
(substrate-adapter/route-hook! adapter :adapter/wrap-view
  wrap-view)

;; Per rf2-4edk: contribute a clear of THIS adapter's `warned-non-dom-roots`
;; cache to the chained `:adapter/clear-warn-once-caches!` hook. The hook
;; is chained — each adapter (helix, uix) and re-frame.views all contribute
;; a clear-step; `reset-runtime-fixture` invokes the top of the chain and
;; every contributor's reset runs (unlike `:adapter/current-frame` etc.
;; this hook is NOT routed through the installed adapter — every loaded
;; adapter's cache must clear because test bundles can mount different
;; adapters across tests and each adapter's defonce persists). Production
;; behaviour is unchanged: the warn-once `defonce` is still per-process
;; for users; only test-time clearing is new.
(let [previous (late-bind/get-fn :adapter/clear-warn-once-caches!)]
  (late-bind/set-fn! :adapter/clear-warn-once-caches!
    (fn uix-adapter-clear-warn-once-caches! []
      (clear-warned-non-dom-roots!)
      (when previous (previous))
      nil)))
