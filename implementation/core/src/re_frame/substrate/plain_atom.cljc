(ns re-frame.substrate.plain-atom
  "The plain-atom adapter — JVM / SSR / headless. Per Spec 006 §Plain-atom
  adapter (JVM, SSR, headless).

  The container is just `clojure.core/atom`. There is no reactivity layer,
  no caching, no listeners. Trivially compliant with the revertibility
  contract because there is no state outside the atom.

  Per rf2-agql (replaces rf2-84po) there is no default-adapter registry
  and no ns-load side-effect. Consumers (JVM tests, headless SSR hosts,
  any process that wants the plain-atom path on CLJS) call
  `(rf/init! plain-atom/adapter)` explicitly. The `adapter` var below
  is the public surface.")

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

;; The hiccup emitter is set by re-frame.ssr at namespace-load time
;; via set-hiccup-emitter!. Stored in an atom so the lookup works on
;; both JVM and CLJS (CLJS lacks JVM's Var-bound? semantics for
;; ^:dynamic vars).
(defonce ^:private hiccup-emitter (atom nil))

(defn set-hiccup-emitter!
  "Install the hiccup → HTML fn used by render-to-string. Idempotent.
  Called by re-frame.ssr on its namespace load."
  [f]
  (reset! hiccup-emitter f))

(defn- render-to-string [render-tree opts]
  (if-let [emit @hiccup-emitter]
    (emit render-tree opts)
    (throw (ex-info ":rf.error/no-hiccup-emitter-bound"
                    {:reason "the SSR namespace must call set-hiccup-emitter! before render-to-string"
                     :render-tree render-tree}))))

(defn- register-context-provider [_frame-keyword]
  ;; No React context on the JVM; users thread frames as arguments per
  ;; Spec 002 §View ergonomics fallback.
  nil)

(defn- dispose-adapter! []
  ;; Watch handles are GC'd with their atoms; nothing else to clean up.
  nil)

(def adapter
  "The plain-atom adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.substrate.plain-atom :as plain-atom])
      (rf/init! plain-atom/adapter)

  See Spec 006 §The adapter API contract for the nine-fn shape."
  {:kind                      :plain-atom
   :make-state-container      make-state-container
   :read-container            read-container
   :replace-container!        replace-container!
   :subscribe-container       subscribe-container
   :make-derived-value        make-derived-value
   :render                    render
   :render-to-string          render-to-string
   :register-context-provider register-context-provider
   :dispose-adapter!          dispose-adapter!})
