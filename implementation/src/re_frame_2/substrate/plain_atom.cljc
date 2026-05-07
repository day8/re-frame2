(ns re-frame-2.substrate.plain-atom
  "The plain-atom adapter — JVM / SSR / headless. Per Spec 006 §Plain-atom
  adapter (JVM, SSR, headless).

  The container is just `clojure.core/atom`. There is no reactivity layer,
  no caching, no listeners. Trivially compliant with the revertibility
  contract because there is no state outside the atom.")

(defn- make-state-container [initial-value]
  (atom initial-value))

(defn- read-container [container]
  @container)

(defn- replace-container! [container new-value]
  (reset! container new-value)
  nil)

(defn- subscribe-container [container on-change]
  (let [k (gensym "rf-sub-")]
    (add-watch container k (fn [_ _ prev nu] (on-change prev nu)))
    (fn unsubscribe [] (remove-watch container k))))

(defn- make-derived-value [source-containers compute-fn]
  ;; No caching: derived values recompute on every deref. SSR runs each
  ;; sub at most a handful of times per request; caching would add
  ;; complexity for negligible gain.
  (reify
    #?(:clj clojure.lang.IDeref :cljs IDeref)
    (#?(:clj deref :cljs -deref) [_]
      (apply compute-fn (map deref source-containers)))))

(defn- render [_ _ _]
  ;; SSR uses render-to-string exclusively. Calling render on the JVM is
  ;; a programmer error worth surfacing loudly.
  (throw (ex-info ":rf.error/render-on-headless-adapter"
                  {:reason "render is not supported on the plain-atom adapter; use render-to-string"})))

(declare ^:dynamic *hiccup-emitter*)

(defn- render-to-string [render-tree opts]
  (if (bound? #'*hiccup-emitter*)
    (*hiccup-emitter* render-tree opts)
    (throw (ex-info ":rf.error/no-hiccup-emitter-bound"
                    {:reason "the SSR namespace must bind *hiccup-emitter* before calling render-to-string"
                     :render-tree render-tree}))))

(defn- register-context-provider [_frame-keyword]
  ;; No React context on the JVM; users thread frames as arguments per
  ;; Spec 002 §View ergonomics fallback.
  nil)

(defn- dispose-adapter! []
  ;; Watch handles are GC'd with their atoms; nothing else to clean up.
  nil)

(def adapter
  "The plain-atom adapter map. See Spec 006 §The adapter API contract."
  {:make-state-container      make-state-container
   :read-container            read-container
   :replace-container!        replace-container!
   :subscribe-container       subscribe-container
   :make-derived-value        make-derived-value
   :render                    render
   :render-to-string          render-to-string
   :register-context-provider register-context-provider
   :dispose-adapter!          dispose-adapter!})
