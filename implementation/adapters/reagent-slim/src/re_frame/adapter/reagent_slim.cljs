(ns re-frame.adapter.reagent-slim
  "The day8/reagent-slim adapter — drop-in replacement for the bridge
  adapter `re-frame.adapter.reagent` (rf2-6hyy).

  Per IMPL-SPEC §2.1 + §9 + §13.1: this adapter Var emits the same
  9-key map shape `re-frame.substrate.adapter` consumes; signatures
  match the bridge's signatures byte-for-byte. Internals swap from
  `(:require [reagent.core ...] [reagent.ratom ...] [reagent.dom.client ...])`
  to the rewrite's `reagent2.*` namespaces.

  Why a separate ns rather than overwriting `re-frame.adapter.reagent`:
  the in-tree shadow-cljs build adds both `adapters/reagent/src` and
  `adapters/reagent-slim/src` to the classpath at the same time. Two
  namespaces with the same name would clash. At artefact-publication
  time (per the deps.edn :main and IMPL-SPEC §13.1) the slim artefact
  ships its own `re-frame.adapter.reagent` — consumers depend on
  exactly one of {day8/re-frame2-reagent, day8/reagent-slim} so the
  ns is single-source per app. Until the artefact split is
  consummated, this `-slim` suffix lets both coexist in the monorepo.

  Public surface (signatures unchanged from the bridge):

    adapter           — the substrate spec map (9-key contract)
    set-hiccup-emitter! — wires SSR's render-to-string into :render-to-string

  Late-bind hooks installed at ns-load time (all routed through
  `(substrate-adapter/current-adapter)` per rf2-0d35 so the active
  adapter's impl wins in test bundles that load multiple adapter ns's):

    :reagent/set-hiccup-emitter!  — set-hiccup-emitter! (SSR seam, rf2-uo7v)
    :adapter/current-frame        — re-frame.views/current-frame (rf2-d4sf)
    :adapter/current-component    — reagent2.core/current-component (rf2-wbnl)
    :adapter/ratom                — reagent2.core/atom            (rf2-s36l)
    :adapter/ratom?               — reagent2.ratom/IReactiveAtom?  (rf2-s36l)
    :adapter/make-reaction        — reagent2.ratom/make-reaction   (rf2-s36l)
    :adapter/add-on-dispose!      — reagent2.ratom/add-on-dispose! (rf2-s36l)
    :adapter/dispose!             — reagent2.ratom/dispose!        (rf2-s36l)
    :adapter/reactive?            — reagent2.ratom/reactive?       (rf2-s36l)
    :adapter/after-render         — reagent2.core/after-render     (rf2-s36l)

  Interop is fully late-bound: this ns does NOT statically `:require`
  stock Reagent anywhere. `re-frame.interop` reads ratom / reaction /
  disposable surfaces through the hook table (per rf2-s36l), so the
  slim Maven artefact (`day8/reagent-slim`) can be published without
  a stock-Reagent dep — downstream consumers depending on
  `reagent-slim` alone gain the ~25 KB gz saving. The in-tree
  shadow-cljs build still pulls all adapter trees; that's the
  monorepo configuration, not a shape requirement.

  The Reagent-slim adapter targets the same React-context Provider /
  `(.-context cmp)` shape the bridge uses (per IMPL-SPEC §9.6),
  so the views.cljs wiring works unchanged.

  Render path (rf2-08t0 / rf2-s36l / rf2-u5p5):
    - `wrap-render` returns hiccup per IMPL-SPEC §5.1; the React
      `render` method converts that hiccup to a React element via
      `reagent2.impl.template/as-element`, registered into
      `reagent2.impl.component/set-as-element-fn!` at template's
      ns-load time. Without that conversion React would reject the
      CLJS vector with 'Objects are not valid as a React child'.
    - `make-render-method` mirrors stock Reagent's
      `reagent.impl.component` render path: the first render creates
      the per-component render Reaction with a custom auto-run that
      queues a React re-render (no synchronous recompute); each
      subsequent render calls `(._run rea false)` directly so
      deref-capture rewires the watching graph AND the user's render
      fn executes with the latest state. A plain `@rea` would return
      the cached prior state because `_handle-change` with a
      fn-valued auto-run doesn't set `dirty?`.

  Status: drop-in functional swap for the bridge. The
  counter-slim-and-fast Playwright smoke (rf2-5lbx) runs as part of
  the default suite — was `skip:`-parked behind rf2-s36l / rf2-u5p5
  and is GREEN as of those merges.

  Pre-release status: until the bridge is retired (post-1.0), apps
  that want the rewrite explicitly opt in by requiring this ns.

  Per rf2-uo7v the SSR surface ships in `day8/re-frame2-ssr` —
  this adapter MUST NOT statically `:require [re-frame.ssr]`. Instead
  it publishes its `set-hiccup-emitter!` callback through the
  late-bind hook table; if the SSR artefact is on the classpath, its
  ns-load resolves the hook and wires the emitter."
  (:require [reagent2.core             :as r]
            [reagent2.ratom            :as ratom]
            [reagent2.dom.client       :as rdc]
            [re-frame.frame            :as frame]
            [re-frame.late-bind        :as late-bind]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.views            :as views]))

;; ---- container ------------------------------------------------------------

(defn- make-state-container [initial-value]
  (r/atom initial-value))

(defn- read-container [container]
  @container)

(defn- replace-container! [container new-value]
  (reset! container new-value)
  nil)

(defn- subscribe-container [container on-change]
  (let [k (gensym "rf-sub-")]
    (add-watch container k (fn [_ _ prev nu] (on-change prev nu)))
    (fn unsubscribe [] (remove-watch container k))))

;; ---- derived (reactions) --------------------------------------------------

(defn- make-derived-value [source-containers compute-fn]
  (ratom/make-reaction
    (fn [] (apply compute-fn (map deref source-containers)))))

;; ---- render ---------------------------------------------------------------

(defn- render [render-tree mount-point opts]
  ;; The bridge mounted into a raw DOM container directly; under the
  ;; rewrite we explicitly create a React-19 root once and reuse it.
  ;; The unmount thunk closes over the root to call its .unmount.
  (let [hydrate? (boolean (:hydrate? opts))]
    (if hydrate?
      (let [root (rdc/hydrate-root mount-point render-tree)]
        (fn unmount [] (rdc/unmount root)))
      (let [root (rdc/create-root mount-point)]
        (rdc/render root render-tree)
        (fn unmount [] (rdc/unmount root))))))

(defonce ^:private hiccup-emitter (atom nil))

(defn set-hiccup-emitter!
  "Install the hiccup → HTML fn used by render-to-string. Idempotent.
  Per rf2-uo7v / IMPL-SPEC §2.1: published through the late-bind hook
  `:reagent/set-hiccup-emitter!` so the SSR seam at re-frame.ssr
  resolves it at load time without a static :require."
  [f]
  (reset! hiccup-emitter f))

(defn- render-to-string [render-tree opts]
  (if-let [emit @hiccup-emitter]
    (emit render-tree opts)
    (throw (ex-info ":rf.error/no-hiccup-emitter-bound"
                    {:reason      "use the plain-atom adapter on the JVM for SSR, or install via set-hiccup-emitter!"
                     :render-tree render-tree}))))

;; ---- context provider -----------------------------------------------------

(defn- register-context-provider [_frame-keyword]
  ;; Implementation lives in re-frame.views (CLJS-only). The frame-
  ;; keyword arg is ignored — `build-frame-provider` is 0-arity
  ;; (rf2-4y60); the returned component takes the frame keyword at
  ;; render time. Per IMPL-SPEC §9.4: the views.cljs ns continues to
  ;; back the frame-provider; the rewrite doesn't replace it.
  (views/build-frame-provider))

;; ---- disposal -------------------------------------------------------------

(defn- dispose-adapter! []
  ;; Reactions GC themselves when their owners (componentWillUnmount-
  ;; disposed render Reactions) go away. Nothing per-adapter to do.
  nil)

(def adapter
  "The reagent-slim adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.adapter.reagent-slim :as reagent-slim])
      (rf/init! reagent-slim/adapter)

  Drop-in shape-compatible with `re-frame.adapter.reagent/adapter` per
  IMPL-SPEC §2.1 — the only difference is the substrate, not the keys.
  Per Spec 006 §CLJS reference + rf2-agql: there is no default-adapter
  registry; adapter wiring is explicit at the call site."
  {:make-state-container      make-state-container
   :read-container            read-container
   :replace-container!        replace-container!
   :subscribe-container       subscribe-container
   :make-derived-value        make-derived-value
   :render                    render
   :render-to-string          render-to-string
   :register-context-provider register-context-provider
   :dispose-adapter!          dispose-adapter!})

;; Per rf2-uo7v + IMPL-SPEC §9.2: publish the hiccup-emitter installer
;; through the late-bind hook table so re-frame.ssr (in
;; day8/re-frame2-ssr) resolves it at its ns-load. Without this, an
;; app pulling in the SSR seam would have to manually call
;; `(reagent-slim/set-hiccup-emitter! ssr/render-to-string)` —
;; the late-bind table makes that wiring automatic when both
;; artefacts are on the classpath.
(late-bind/set-fn! :reagent/set-hiccup-emitter! set-hiccup-emitter!)

;; Per rf2-d4sf + IMPL-SPEC §9.6: publish the React-context-aware
;; current-frame impl through the late-bind hook so subscribe / dispatch
;; consult the React-context tier of the 3-tier resolution chain. The
;; rewrite preserves the bridge's class-component context-read shape
;; (`(.-context cmp)`) per IMPL-SPEC §9.6 — the views.cljs impl works
;; with reagent-slim's class components without source change.
;;
;; Per rf2-0d35: route `:adapter/current-frame` through the installed
;; adapter (same pattern as the bridge ns; see reagent.cljs for the
;; full rationale). In test bundles that load multiple adapter ns's
;; the last-loaded would otherwise silently win at the hook regardless
;; of which adapter was actually `(rf/init!)`-installed.
(let [previous (late-bind/get-fn :adapter/current-frame)]
  (late-bind/set-fn! :adapter/current-frame
    (fn reagent-slim-adapter-current-frame-routed []
      (if (identical? adapter (substrate-adapter/current-adapter))
        (views/current-frame)
        (if previous
          (previous)
          (frame/current-frame))))))

;; Per rf2-wbnl: publish reagent2's `current-component` through the
;; late-bind hook so `re-frame.views` reads the in-flight component
;; from the slim adapter's reactive substrate, not from stock
;; Reagent's. Before rf2-wbnl, views.cljs statically `:require`d
;; `reagent.core` and called its `current-component` directly — under
;; the slim adapter that read returned nil for slim-rendered
;; components, which silently dropped the React-context tier of the
;; resolution chain (`subscribe`/`dispatch` under a non-default
;; `frame-provider` routed to `:rf/default`). The classic bridge
;; installs the same hook against stock Reagent; consumers that
;; require exactly one of the two adapter ns's see the matching
;; reader installed.
;;
;; Per rf2-0d35: route through the installed adapter (same pattern as
;; `:adapter/current-frame` above; see reagent.cljs for the full
;; rationale). The slim adapter's reader runs only when the slim
;; adapter is the installed one; otherwise fall through to whatever
;; reader was previously registered by another adapter ns.
(let [previous (late-bind/get-fn :adapter/current-component)]
  (late-bind/set-fn! :adapter/current-component
    (fn reagent-slim-adapter-current-component-routed []
      (if (identical? adapter (substrate-adapter/current-adapter))
        (r/current-component)
        (when previous (previous))))))

;; Per rf2-s36l: publish the reactive-substrate surfaces consumed by
;; `re-frame.interop` through the late-bind hook table. Before rf2-s36l,
;; `interop.cljs` statically `:require`d stock Reagent and forwarded
;; every call there — so under the slim adapter the very first
;; `(interop/add-on-dispose! ...)` (called from the sub-cache's slot
;; teardown wiring; see Spec 006 §subscription-cache and the rf2-3yij
;; cross-substrate-cache decision) threw a protocol-dispatch error
;; because `reagent2.ratom/Reaction` reifies its OWN `IDisposable`
;; protocol (`reagent2.ratom/IDisposable`), NOT stock Reagent's.
;; That parked the counter-slim-and-fast Playwright smoke (PR #305,
;; rf2-5lbx) behind a `skip:` field until this seam landed.
;;
;; Per rf2-0d35: route through the installed adapter, mirroring the
;; `:adapter/current-frame` and `:adapter/current-component` pattern
;; established for this ns above. The slim adapter's impl runs only
;; when this adapter is the `(rf/init!)`-installed one; otherwise the
;; hook chains to the previously-registered reader. This keeps the
;; stock-Reagent path live in mixed-adapter test bundles even though
;; both adapter ns's load and call `set-fn!`.
(let [previous (late-bind/get-fn :adapter/ratom)]
  (late-bind/set-fn! :adapter/ratom
    (fn reagent-slim-adapter-ratom-routed [v]
      (if (identical? adapter (substrate-adapter/current-adapter))
        (r/atom v)
        (when previous (previous v))))))

(let [previous (late-bind/get-fn :adapter/ratom?)]
  (late-bind/set-fn! :adapter/ratom?
    (fn reagent-slim-adapter-ratom?-routed [x]
      (if (identical? adapter (substrate-adapter/current-adapter))
        (satisfies? ratom/IReactiveAtom ^js x)
        (if previous (previous x) false)))))

(let [previous (late-bind/get-fn :adapter/make-reaction)]
  (late-bind/set-fn! :adapter/make-reaction
    (fn reagent-slim-adapter-make-reaction-routed [f]
      (if (identical? adapter (substrate-adapter/current-adapter))
        (ratom/make-reaction f)
        (when previous (previous f))))))

(let [previous (late-bind/get-fn :adapter/add-on-dispose!)]
  (late-bind/set-fn! :adapter/add-on-dispose!
    (fn reagent-slim-adapter-add-on-dispose!-routed [a-ratom f]
      (if (identical? adapter (substrate-adapter/current-adapter))
        (ratom/add-on-dispose! a-ratom f)
        (when previous (previous a-ratom f))))))

(let [previous (late-bind/get-fn :adapter/dispose!)]
  (late-bind/set-fn! :adapter/dispose!
    (fn reagent-slim-adapter-dispose!-routed [a-ratom]
      (if (identical? adapter (substrate-adapter/current-adapter))
        (ratom/dispose! a-ratom)
        (when previous (previous a-ratom))))))

(let [previous (late-bind/get-fn :adapter/reactive?)]
  (late-bind/set-fn! :adapter/reactive?
    (fn reagent-slim-adapter-reactive?-routed []
      (if (identical? adapter (substrate-adapter/current-adapter))
        (ratom/reactive?)
        (if previous (previous) false)))))

(let [previous (late-bind/get-fn :adapter/after-render)]
  (late-bind/set-fn! :adapter/after-render
    (fn reagent-slim-adapter-after-render-routed [f]
      (if (identical? adapter (substrate-adapter/current-adapter))
        (r/after-render f)
        (when previous (previous f))))))
