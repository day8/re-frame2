(ns re-frame.adapter.reagent
  "The Reagent adapter — browser default. Per Spec 006 §CLJS reference:
  Reagent as default adapter.

  Ships in its own Maven artefact (day8/re-frame-2-reagent) per
  Spec 006 §Adapter shipping convention (rf2-0hxm). Apps that use
  Reagent depend on both day8/re-frame-2 (core) and this artefact;
  apps targeting a different substrate (UIx, Helix) depend on the
  matching adapter artefact instead. Core does *not* :require this ns —
  the dependency direction is adapter → core.

  Per rf2-uo7v the SSR surface ships in `day8/re-frame-2-ssr` —
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
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as substrate-adapter]))

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
  ;; Implementation lives in re-frame.views (CLJS-only); this slot is
  ;; populated dynamically by views/ns load. The frame-keyword arg is
  ;; ignored — `build-frame-provider` is 0-arity (rf2-4y60); the returned
  ;; component takes the frame keyword at render time. The arg stays in
  ;; the substrate signature per Spec 006 §Frame-provider via React
  ;; context.
  (when-let [provider-builder (resolve 're-frame.views/build-frame-provider)]
    ((deref provider-builder))))

;; ---- disposal -------------------------------------------------------------

(defn- dispose-adapter! []
  ;; Reagent's reaction caches GC themselves when their owners go away.
  ;; Nothing special to do here.
  nil)

(def adapter
  "The Reagent adapter map. See Spec 006 §CLJS reference: Reagent as
  default adapter for the bridging pseudocode."
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
;; slot. Per rf2-uo7v ssr ships in day8/re-frame-2-ssr; this adapter
;; cannot statically `:require [re-frame.ssr]` without dragging the SSR
;; namespace into every Reagent bundle. Publish set-hiccup-emitter!
;; through the late-bind hook table — when the ssr artefact is loaded,
;; its ns-load resolves the hook and wires the emitter through it. When
;; ssr is absent the hook is never consumed and render-to-string raises
;; the "no-hiccup-emitter-bound" error on first call. Per Spec 006
;; §Adapter shipping convention (rf2-0hxm).
(late-bind/set-fn! :reagent/set-hiccup-emitter! set-hiccup-emitter!)

;; ---- default-adapter registration (rf2-84po) -----------------------------
;;
;; Register this adapter as a default-resolution candidate for
;; `(rf/init!)` (no args). Consumers who `(:require
;; [re-frame.adapter.reagent])` pick up Reagent as the default at
;; ns-load time without an explicit adapter arg.
;;
;; Wrapped in `defonce` so shadow-cljs hot-reload doesn't perturb the
;; registry. Per rf2-84po (resolves rf2-4cb6).

(defonce ^:private __register-default-adapter
  (do (substrate-adapter/register-default-adapter! :reagent adapter) nil))
