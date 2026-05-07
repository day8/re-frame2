(ns re-frame-2.substrate.adapter
  "The reactive substrate adapter contract. Per Spec 006.

  The runtime is substrate-agnostic; every host-specific reactivity concern
  goes through these 9 functions. The CLJS reference ships two adapters:
  Reagent (browser) and plain-atom (JVM / SSR / headless).

  Required functions (6):
    make-state-container, read-container, replace-container!,
    make-derived-value, render, render-to-string

  Optional (2):
    subscribe-container, register-context-provider

  Lifecycle (1):
    dispose-adapter!

  An adapter is a Clojure map with these keys; it is installed into the
  process via install-adapter! and read via current-adapter.")

;; ---- adapter installation -------------------------------------------------

(defonce ^:private installed-adapter (atom nil))

(defn install-adapter!
  "Install the adapter for this process. Once. A second call without an
  intervening dispose-adapter! raises :rf.error/adapter-already-installed
  (per Spec 006 §Single adapter per process)."
  [adapter]
  (when @installed-adapter
    (throw (ex-info ":rf.error/adapter-already-installed"
                    {:installed @installed-adapter :attempted adapter})))
  (reset! installed-adapter adapter)
  adapter)

(defn current-adapter
  "Return the installed adapter, or nil if none."
  []
  @installed-adapter)

(defn dispose-adapter!
  "Tear down the installed adapter. Calls the adapter's :dispose-adapter!
  fn (if present), then clears the slot so a new adapter can install."
  []
  (when-let [adapter @installed-adapter]
    (when-let [f (:dispose-adapter! adapter)]
      (f))
    (reset! installed-adapter nil))
  nil)

;; ---- delegation helpers ---------------------------------------------------
;; These let other namespaces call adapter functions without knowing which
;; adapter is installed.

(defn make-state-container [initial-value]
  (let [a (or @installed-adapter
              (throw (ex-info "no adapter installed" {})))]
    ((:make-state-container a) initial-value)))

(defn read-container [container]
  (let [a @installed-adapter] ((:read-container a) container)))

(defn replace-container! [container new-value]
  (let [a @installed-adapter] ((:replace-container! a) container new-value)))

(defn make-derived-value [source-containers compute-fn]
  (let [a @installed-adapter]
    ((:make-derived-value a) source-containers compute-fn)))

(defn render [render-tree mount-point opts]
  (let [a @installed-adapter] ((:render a) render-tree mount-point opts)))

(defn render-to-string [render-tree opts]
  (let [a @installed-adapter] ((:render-to-string a) render-tree opts)))

(defn subscribe-container
  "Optional — adapters may omit. Returns nil if the adapter doesn't
  implement it; callers fall back to running invalidation inline within
  replace-container! (per Spec 006)."
  [container on-change]
  (let [a @installed-adapter
        f (:subscribe-container a)]
    (when f (f container on-change))))

(defn register-context-provider
  "Optional. Returns the substrate's context-provider component for this
  frame keyword, or nil if the substrate has no context concept."
  [frame-keyword]
  (let [a @installed-adapter
        f (:register-context-provider a)]
    (when f (f frame-keyword))))
