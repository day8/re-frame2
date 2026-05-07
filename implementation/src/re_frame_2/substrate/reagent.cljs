(ns re-frame-2.substrate.reagent
  "The Reagent adapter — browser default. Per Spec 006 §CLJS reference:
  Reagent as default adapter."
  (:require [reagent.core :as r]
            [reagent.ratom :as ratom]
            [reagent.dom.client :as rdc]
            [re-frame-2.interop :as interop]))

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

(declare ^:dynamic *hiccup-emitter*)

(defn- render-to-string [render-tree opts]
  ;; Reagent ships server-side rendering via reagent.dom.server — but in
  ;; CLJS browser builds we don't typically render-to-string. Bind
  ;; *hiccup-emitter* from the SSR namespace if you need this in CLJS.
  (if (bound? #'*hiccup-emitter*)
    (*hiccup-emitter* render-tree opts)
    (throw (ex-info ":rf.error/no-hiccup-emitter-bound"
                    {:reason "use the plain-atom adapter on the JVM for SSR, or bind *hiccup-emitter*"
                     :render-tree render-tree}))))

;; ---- context provider -----------------------------------------------------

(defn- register-context-provider [frame-keyword]
  ;; Implementation lives in re-frame-2.views (CLJS-only); this slot is
  ;; populated dynamically by views/ns load.
  (if-let [provider-builder (resolve 're-frame-2.views/build-frame-provider)]
    ((deref provider-builder) frame-keyword)
    nil))

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
