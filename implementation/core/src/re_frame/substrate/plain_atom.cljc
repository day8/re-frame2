(ns re-frame.substrate.plain-atom
  "The plain-atom adapter — JVM / SSR / headless. Per Spec 006 §Plain-atom
  adapter (JVM, SSR, headless).

  The container is just `clojure.core/atom`. There is no reactivity layer,
  no caching, no listeners. Trivially compliant with the revertibility
  contract because there is no state outside the atom.

  There is no default-adapter registry and no ns-load side-effect.
  Consumers (JVM tests, headless SSR hosts, any process that wants the
  plain-atom path on CLJS) call `(rf/init! plain-atom/adapter)`
  explicitly. The `adapter` var below is the public surface.

  ## Disposal / ref-count participation (rf2-uatcy)

  The sub-cache wires symmetric input-release through
  `re-frame.interop/add-on-dispose!` at slot construction (Spec 006
  §Reference counting and disposal) and `re-frame.interop/dispose!` at
  slot evict — so a layer-2+ sub's `:<-` inputs lose their reader when
  the parent reaction disposes. Both runtimes must honour that contract
  or input ref-counts leak monotonically until `clear-sub-cache!`.

    - **JVM.** `re-frame.interop` (the `.clj`) implements
      `add-on-dispose!` / `dispose!` directly, keyed by the reaction
      object in a process-wide callback registry — so the plain-atom
      JVM derived value (a bare `IDeref` reify) participates without
      reifying any disposal protocol.

    - **CLJS.** `re-frame.interop` (the `.cljs`) routes those calls
      through the `:adapter/add-on-dispose!` / `:adapter/dispose!`
      late-bind hooks, which dispatch on the derived value reifying a
      disposal protocol. The plain-atom CLJS derived value therefore
      reifies `re-frame.disposable/IDisposable` (mirroring the spine),
      and this ns publishes the two hooks via `substrate-adapter/
      route-hook!` so a CLJS-plain-atom host (rather than a leak)
      releases inputs symmetrically on slot evict."
  #?(:cljs (:require [re-frame.disposable :as rf-disposable]
                     [re-frame.substrate.adapter :as substrate-adapter])))

#?(:clj (set! *warn-on-reflection* true))

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
  ;;
  ;; JVM: a bare `IDeref` reify suffices — `re-frame.interop` (the .clj)
  ;; keys its `add-on-dispose!` / `dispose!` callback registry by this
  ;; object's identity, so disposal participation needs no protocol on
  ;; the value itself.
  ;;
  ;; CLJS (rf2-uatcy): `re-frame.interop` (the .cljs) routes
  ;; `add-on-dispose!` / `dispose!` through the `:adapter/*` hooks, which
  ;; dispatch on the derived value reifying `IDisposable`. Reify it here
  ;; (mirroring `re-frame.substrate.spine`) so the sub-cache's symmetric
  ;; input-release callback is registered at slot construction and fires
  ;; at slot evict — otherwise layer-2+ input ref-counts never decrement
  ;; on the CLJS-plain-atom path. The plain-atom derived value owns no
  ;; source watches (it recomputes on deref), so `-dispose` only fires
  ;; the registered on-dispose callbacks.
  #?(:clj
     (reify
       clojure.lang.IDeref
       (deref [_] (apply compute-fn (map deref source-containers))))
     :cljs
     (let [on-dispose-fns (atom [])]
       (reify
         IDeref
         (-deref [_] (apply compute-fn (map deref source-containers)))
         rf-disposable/IDisposable
         (-add-on-dispose [_ f]
           (swap! on-dispose-fns conj f))
         (-dispose [_]
           (doseq [f @on-dispose-fns] (f))
           (reset! on-dispose-fns []))))))

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
  {:kind                      :rf.adapter/plain-atom
   :make-state-container      make-state-container
   :read-container            read-container
   :replace-container!        replace-container!
   :subscribe-container       subscribe-container
   :make-derived-value        make-derived-value
   :render                    render
   :render-to-string          render-to-string
   :register-context-provider register-context-provider
   :dispose-adapter!          dispose-adapter!})

;; ---- late-bind hook routing (CLJS only, rf2-uatcy) ------------------------
;;
;; On CLJS `re-frame.interop`'s `add-on-dispose!` / `dispose!` route
;; through these `:adapter/*` hooks (the JVM `re-frame.interop` implements
;; both directly, so this block is CLJS-only). Each routes into the
;; `re-frame.disposable/IDisposable` protocol the CLJS `make-derived-value`
;; reifies above, so a CLJS-plain-atom host participates in the sub-cache's
;; ref-count / disposal contract symmetrically with the React-shaped
;; adapters (see `re-frame.substrate.spine`'s identical routing). Routed
;; through `substrate-adapter/route-hook!` so a test bundle that also loads
;; a React adapter only runs the plain-atom impl while plain-atom is the
;; `(rf/init!)`-installed adapter (per Spec 006 §adapter routing).
;;
;; The `add-on-dispose!` / `dispose!` dispatch tolerates a value that does
;; NOT satisfy the protocol (e.g. a foreign reaction inherited through a
;; cross-substrate test bundle) by no-op'ing — mirroring the Reagent
;; adapter's fall-through dispatch.
#?(:cljs
   (do
     (substrate-adapter/route-hook! adapter :adapter/add-on-dispose!
       (fn add-on-dispose!-dispatch [a f]
         (when (satisfies? rf-disposable/IDisposable a)
           (rf-disposable/-add-on-dispose a f))))
     (substrate-adapter/route-hook! adapter :adapter/dispose!
       (fn dispose!-dispatch [a]
         (when (satisfies? rf-disposable/IDisposable a)
           (rf-disposable/-dispose a))))))
