(ns re-frame.adapter.reagent
  "The Reagent adapter — browser default. Per Spec 006 §CLJS reference:
  Reagent as default adapter.

  Ships in its own Maven artefact (day8/re-frame2-reagent) per
  Spec 006 §Adapter shipping convention (rf2-0hxm). Apps that use
  Reagent depend on both day8/re-frame2 (core) and this artefact;
  apps targeting a different substrate (UIx, Helix) depend on the
  matching adapter artefact instead. Core does *not* :require this ns —
  the dependency direction is adapter → core.

  Per rf2-uo7v the SSR surface ships in `day8/re-frame2-ssr` —
  this adapter MUST NOT statically `:require [re-frame.ssr]` either,
  because that would drag the SSR namespace, the FNV-1a render-tree-hash
  machinery, the per-request `[:rf/response]` accumulator, and every
  `:rf.ssr/*` / `:rf.server/*` keyword string into every Reagent app's
  bundle even when no server-side rendering is performed. Instead the
  adapter publishes its `set-hiccup-emitter!` callback through the
  late-bind hook table; if the SSR artefact is on the classpath, its
  ns-load resolves the hook and wires the emitter."
  (:require [reagent.core :as r]
            [reagent.ratom :as ratom]
            [reagent.dom.client :as rdc]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.views :as views]))

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
  (let [hydrate? (boolean (:hydrate? opts))]
    (if hydrate?
      (rdc/hydrate-root mount-point render-tree)
      (rdc/render        mount-point render-tree))
    (fn unmount [] (rdc/unmount mount-point))))

(defonce ^:private hiccup-emitter (atom nil))

(defn set-hiccup-emitter!
  "Install the hiccup → HTML fn used by render-to-string. Idempotent."
  [f]
  (reset! hiccup-emitter f))

(defn- render-to-string [render-tree opts]
  ;; Reagent ships server-side rendering via reagent.dom.server — but in
  ;; CLJS browser builds we don't typically render-to-string. Install
  ;; the emitter via set-hiccup-emitter! if you need this in CLJS.
  (if-let [emit @hiccup-emitter]
    (emit render-tree opts)
    (throw (ex-info ":rf.error/no-hiccup-emitter-bound"
                    {:reason "use the plain-atom adapter on the JVM for SSR, or install via set-hiccup-emitter!"
                     :render-tree render-tree}))))

;; ---- context provider -----------------------------------------------------

(defn- register-context-provider [_frame-keyword]
  ;; Implementation lives in re-frame.views (CLJS-only). The frame-
  ;; keyword arg is ignored — `build-frame-provider` is 0-arity
  ;; (rf2-4y60); the returned component takes the frame keyword at
  ;; render time. The arg stays in the substrate signature per
  ;; Spec 006 §Frame-provider via React context.
  (views/build-frame-provider))

;; ---- disposal -------------------------------------------------------------

(defn- dispose-adapter! []
  ;; Reagent's reaction caches GC themselves when their owners go away.
  ;; Nothing special to do here.
  nil)

(def adapter
  "The Reagent adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.adapter.reagent :as reagent])
      (rf/init! reagent/adapter)

  See Spec 006 §CLJS reference: Reagent as default adapter for the
  bridging pseudocode. Per rf2-agql there is no default-adapter
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

;; Wire ssr's render-to-string into this adapter's :render-to-string
;; slot. Per rf2-uo7v ssr ships in day8/re-frame2-ssr; this adapter
;; cannot statically `:require [re-frame.ssr]` without dragging the SSR
;; namespace into every Reagent bundle. Publish set-hiccup-emitter!
;; through the late-bind hook table — when the ssr artefact is loaded,
;; its ns-load resolves the hook and wires the emitter through it. When
;; ssr is absent the hook is never consumed and render-to-string raises
;; the "no-hiccup-emitter-bound" error on first call. Per Spec 006
;; §Adapter shipping convention (rf2-0hxm).
(late-bind/set-fn! :reagent/set-hiccup-emitter! set-hiccup-emitter!)

;; Each late-bind hook below is routed through `(substrate-adapter/
;; current-adapter)` per rf2-0d35 via `substrate-adapter/route-hook!`
;; (see that fn's docstring for the routing contract). The wrapper runs
;; this adapter's impl ONLY when this adapter is the (rf/init!)-installed
;; one; otherwise it chains to the previously-registered handler.
;;
;; Hook-specific rationale:
;;   :adapter/current-frame  — rf2-d4sf. React-context tier of the
;;     3-tier resolution chain. Reagent path uses (.-context cmp) on
;;     the in-flight Reagent component (class-component machinery), so
;;     `reg-view*`-wrapped components route to the surrounding
;;     provider's frame while plain Reagent fns fall through to
;;     :rf/default — that narrowness makes the
;;     plain-fn-under-non-default-frame-once warning meaningful.
;;     Chain-bottom fallback is `frame/current-frame` so headless /
;;     pre-init shape is preserved.
;;   :adapter/current-component — rf2-wbnl. Reads stock Reagent's
;;     in-flight component without hard-binding re-frame.views to
;;     reagent.core; the slim adapter wires this hook to
;;     reagent2.core/current-component.
;;   :adapter/ratom etc. — rf2-s36l. The reactive-substrate surfaces
;;     consumed by `re-frame.interop`. UIx and Helix adapters reify
;;     stock reagent.ratom/IDisposable on their derived values, so
;;     they wire these hooks to the same stock-Reagent impls.
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
