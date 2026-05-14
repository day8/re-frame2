(ns re-frame.adapter.reagent-slim
  "The day8/reagent-slim adapter — drop-in replacement for the bridge
  adapter `re-frame.adapter.reagent`.

  Emits the same 9-key map shape `re-frame.substrate.adapter` consumes;
  internals swap from stock `reagent.*` to the rewrite's `reagent2.*`
  namespaces. Signatures match the bridge's byte-for-byte (IMPL-SPEC
  §2.1, §9, §13.1).

  This ns coexists with `re-frame.adapter.reagent` only in the in-tree
  monorepo build; at artefact-publication time the slim artefact ships
  its own `re-frame.adapter.reagent` and consumers depend on exactly
  one of `{day8/re-frame2-reagent, day8/reagent-slim}`.

  Late-bind hooks installed at ns-load time (routed through the
  installed adapter so test bundles that load multiple adapter ns's
  resolve to the correct impl):

    :reagent/set-hiccup-emitter!  — set-hiccup-emitter! (SSR seam)
    :adapter/current-frame        — re-frame.views/current-frame
    :adapter/current-component    — reagent2.core/current-component
    :adapter/ratom                — reagent2.core/atom
    :adapter/ratom?               — reagent2.ratom/IReactiveAtom?
    :adapter/make-reaction        — reagent2.ratom/make-reaction
    :adapter/add-on-dispose!      — reagent2.ratom/add-on-dispose!
    :adapter/dispose!             — reagent2.ratom/dispose!
    :adapter/reactive?            — reagent2.ratom/reactive?
    :adapter/after-render         — reagent2.core/after-render

  Interop is fully late-bound: this ns does NOT statically `:require`
  stock Reagent anywhere. `re-frame.interop` reads ratom / reaction /
  disposable surfaces through the hook table, so the slim Maven
  artefact (`day8/reagent-slim`) can be published without a stock-
  Reagent dep — downstream consumers depending on `reagent-slim` alone
  gain the ~25 KB gz saving.

  Targets the same React-context Provider / `(.-context cmp)` shape
  the bridge uses (IMPL-SPEC §9.6), so the views.cljs wiring works
  unchanged.

  Render path:
    - `wrap-render` returns hiccup per IMPL-SPEC §5.1; the React
      `render` method converts that to a React element via
      `reagent2.impl.template/as-element`, registered into
      `reagent2.impl.component/set-as-element-fn!` at template ns-load.
      Without that conversion React would reject the CLJS vector with
      'Objects are not valid as a React child'.
    - `make-render-method` mirrors stock Reagent's render path: the
      first render creates the per-component render Reaction with a
      custom auto-run that queues a React re-render; each subsequent
      render calls `(._run rea false)` directly so deref-capture
      rewires the watching graph AND the user's render fn executes
      with the latest state. A plain `@rea` would return the cached
      prior state because `_handle-change` with a fn-valued auto-run
      doesn't set `dirty?`.

  The SSR surface (`day8/re-frame2-ssr`) is optional, so this adapter
  MUST NOT statically `:require [re-frame.ssr]`. Instead it publishes
  `set-hiccup-emitter!` through the late-bind hook table."
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
  Published through the late-bind hook `:reagent/set-hiccup-emitter!`
  so the SSR seam at re-frame.ssr resolves it at load time without a
  static :require."
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
  ;; `build-frame-provider` is 0-arity; the returned component takes
  ;; the frame keyword at render time. IMPL-SPEC §9.4: views.cljs
  ;; continues to back the frame-provider; the rewrite doesn't replace
  ;; it.
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
  There is no default-adapter registry; adapter wiring is explicit at
  the call site."
  {:kind                      :reagent-slim
   :make-state-container      make-state-container
   :read-container            read-container
   :replace-container!        replace-container!
   :subscribe-container       subscribe-container
   :make-derived-value        make-derived-value
   :render                    render
   :render-to-string          render-to-string
   :register-context-provider register-context-provider
   :dispose-adapter!          dispose-adapter!})

;; Publishes the hiccup-emitter installer through the late-bind table
;; so re-frame.ssr resolves it at its ns-load when present (apps then
;; don't have to manually call `(reagent-slim/set-hiccup-emitter!
;; ssr/render-to-string)`).
(late-bind/set-fn! :reagent/set-hiccup-emitter! set-hiccup-emitter!)

;; Each hook below is routed through `substrate-adapter/route-hook!`
;; (see its docstring). Slim-adapter specifics:
;;
;;   :adapter/current-frame  — the rewrite preserves the bridge's class-
;;     component `(.-context cmp)` shape, so `views/current-frame` works
;;     unchanged with reagent-slim's class components.
;;   :adapter/current-component — `reagent2.core/current-component` —
;;     stock Reagent's reader would return nil for slim-rendered
;;     components.
;;   :adapter/ratom etc. — wires `reagent2.*` impls so
;;     `re-frame.interop`'s reactive-substrate calls dispatch onto
;;     `reagent2.ratom/IDisposable` / `IReactiveAtom`. Without this
;;     seam the very first `(interop/add-on-dispose! ...)` under the
;;     slim adapter threw because `reagent2.ratom/Reaction` does NOT
;;     reify stock Reagent's `IDisposable`.
(substrate-adapter/route-hook! adapter :adapter/current-frame
  views/current-frame
  #(frame/current-frame))
(substrate-adapter/route-hook! adapter :adapter/current-component
  r/current-component)
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
