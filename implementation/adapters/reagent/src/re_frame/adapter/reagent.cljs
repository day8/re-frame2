(ns re-frame.adapter.reagent
  "The Reagent adapter — browser default. Per Spec 006 §CLJS reference.

  Ships in its own artefact (day8/re-frame2-reagent). Dependency
  direction is adapter → core; core does NOT `:require` this ns.

  The SSR surface (`day8/re-frame2-ssr`) is also optional, so this
  adapter MUST NOT statically `:require [re-frame.ssr]`. Instead the
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
  ;; React 18+ uses the root API: `reagent.dom.client/render` takes a Root
  ;; (from `create-root`) — NOT a raw DOM element. Calling
  ;; `(rdc/render mount-point …)` directly throws
  ;; `TypeError: root.render is not a function` at runtime.
  ;;
  ;; Per Reagent v2's `reagent.dom.client` API:
  ;;   - `(rdc/create-root <dom>)`       → Root
  ;;   - `(rdc/render <root> <tree>)`    → render into the Root
  ;;   - `(rdc/hydrate-root <dom> <tree>)` → returns its own Root
  ;;   - `(rdc/unmount <root>)`          → tear the Root down
  ;;
  ;; The unmount thunk closes over the Root so the runtime can release
  ;; it without consulting the DOM element again.
  (let [hydrate? (boolean (:hydrate? opts))]
    (if hydrate?
      (let [root (rdc/hydrate-root mount-point render-tree)]
        (fn unmount [] (rdc/unmount root)))
      (let [root (rdc/create-root mount-point)]
        (rdc/render root render-tree)
        (fn unmount [] (rdc/unmount root))))))

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
  ;; `build-frame-provider` is 0-arity; the returned component takes the
  ;; frame keyword at render time. The arg stays in the substrate
  ;; signature per Spec 006 §Frame-provider via React context.
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
  bridging pseudocode. There is no default-adapter registry — adapter
  wiring is explicit at the call site."
  {:kind                      :reagent
   :make-state-container      make-state-container
   :read-container            read-container
   :replace-container!        replace-container!
   :subscribe-container       subscribe-container
   :make-derived-value        make-derived-value
   :render                    render
   :render-to-string          render-to-string
   :register-context-provider register-context-provider
   :dispose-adapter!          dispose-adapter!})

;; Published through the late-bind table because re-frame.ssr is
;; optional; ssr's ns-load resolves the hook when present, and
;; render-to-string raises "no-hiccup-emitter-bound" when absent.
(late-bind/set-fn! :reagent/set-hiccup-emitter! set-hiccup-emitter!)

;; Each hook below is routed through `substrate-adapter/route-hook!`
;; (see its docstring): the wrapper runs this adapter's impl only
;; when this adapter is the (rf/init!)-installed one, otherwise it
;; chains to the previously-registered handler.
;;
;;   :adapter/current-frame  — React-context tier of the three-tier
;;     resolution chain. Reagent's class-component machinery means
;;     `reg-view*`-wrapped components route to the surrounding provider,
;;     while plain Reagent fns fall through to `:rf/default` — that
;;     narrowness makes the plain-fn-under-non-default-frame-once
;;     warning meaningful. Chain-bottom fallback is `frame/current-frame`
;;     so the headless / pre-init shape is preserved.
;;   :adapter/current-component — reads stock Reagent's in-flight
;;     component without hard-binding re-frame.views to reagent.core.
;;     The slim adapter wires this hook to `reagent2.core/current-
;;     component`.
;;   :adapter/ratom etc. — reactive-substrate surfaces consumed by
;;     `re-frame.interop`. UIx and Helix reify stock
;;     `reagent.ratom/IDisposable` on their derived values, so they
;;     wire these hooks to the same stock-Reagent impls.
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
