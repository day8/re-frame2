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

    - **JVM.** The plain-atom JVM derived value IS an
      `re-frame.interop/make-reaction` `Reaction` (per rf2-tnnln), which
      carries its own on-dispose callback storage on the object. So
      `re-frame.interop/add-on-dispose!` / `dispose!` store/fire
      callbacks directly on the reaction — no process-wide registry, and
      an orphaned reaction is GC-reclaimable along with its callbacks
      rather than pinned in a global strong-ref map.

    - **CLJS.** `re-frame.interop` (the `.cljs`) routes those calls
      through the `:adapter/add-on-dispose!` / `:adapter/dispose!`
      late-bind hooks, which dispatch on the derived value reifying a
      disposal protocol. The plain-atom CLJS derived value therefore
      reifies `re-frame.disposable/IDisposable` (mirroring the spine),
      and this ns publishes the two hooks via `substrate-adapter/
      route-hook!` so a CLJS-plain-atom host (rather than a leak)
      releases inputs symmetrically on slot evict.

  The atom-backed container quartet (`make-state-container` /
  `read-container` / `replace-container!` / `subscribe-container`) is
  shared with the test-react adapter via `re-frame.substrate.atom-container`
  — see that ns for the rationale on why only the container quartet (not
  `make-derived-value`) is shared."
  (:require [re-frame.error :as error]
            [re-frame.substrate.atom-container :as atom-container]
            #?@(:clj  [[re-frame.interop :as interop]]
                :cljs [[re-frame.disposable :as rf-disposable]
                       [re-frame.substrate.adapter :as substrate-adapter]])))

#?(:clj (set! *warn-on-reflection* true))

(defn- make-derived-value [source-containers compute-fn]
  ;; No caching: derived values recompute on every deref. SSR runs each
  ;; sub at most a handful of times per request; caching would add
  ;; complexity for negligible gain.
  ;;
  ;; JVM (rf2-tnnln): the derived value IS an `interop/make-reaction`
  ;; `Reaction`, which carries its own on-dispose callback storage on the
  ;; object. `re-frame.interop`'s `add-on-dispose!` / `dispose!` store and
  ;; fire callbacks directly on it — no process-wide registry — so an
  ;; un-disposed reaction is GC-reclaimable along with its callbacks
  ;; rather than pinned in a global strong-ref map.
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
     (interop/make-reaction
       (fn [] (apply compute-fn (map deref source-containers))))
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
  (error/throw-error!
    :rf.error/render-on-headless-adapter
    'rf/render
    (str "render is not supported on the plain-atom adapter (it is headless "
         "— JVM/SSR, no React reactivity); render server-side HTML with "
         "rf/render-to-string instead of rf/render.")
    {:recovery :use-render-to-string}))

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
    (error/throw-error!
      :rf.error/no-hiccup-emitter-bound
      'rf/render-to-string
      (str "no hiccup emitter is bound on the plain-atom adapter; require the "
           "re-frame.ssr namespace (which calls set-hiccup-emitter! on load) "
           "before calling render-to-string.")
      ;; EP-0015 (rf2-uwqale): carry an EP-0015-safe SUMMARY of the
      ;; render-tree, never the raw tree (a thrown render diagnostic is
      ;; captured off-box before path-based projection can classify it).
      {:recovery :require-re-frame-ssr
       :extra    {:render-tree/summary (error/diag-value-summary render-tree)}})))

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

  See Spec 006 §The adapter API contract for the ten-fn shape (six
  required + three optional + one lifecycle)."
  {:kind                      :rf.adapter/plain-atom
   :make-state-container      atom-container/make-state-container
   :read-container            atom-container/read-container
   :replace-container!        atom-container/replace-container!
   :subscribe-container       atom-container/subscribe-container
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
