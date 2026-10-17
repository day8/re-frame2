(ns re-frame.substrate.plain-atom
  "The plain-atom adapter — JVM / SSR / headless. Per Spec 006 §Plain-atom
  adapter (JVM, SSR, headless).

  The container is just `clojure.core/atom`. There is no reactivity graph
  and no caching: a derived value recomputes on every read, and watches its
  sources only while an observer is attached. Trivially compliant with the
  revertibility contract because there is no state outside the atom beyond
  those transient watches.

  There is no default-adapter registry and no ns-load side-effect.
  Consumers (JVM tests, headless SSR hosts, any process that wants the
  plain-atom path on CLJS) call `(rf/init! plain-atom/adapter)`
  explicitly. The `adapter` var below is the public surface.

  ## Disposal / ref-count participation

  The sub-cache wires symmetric input-release through
  `re-frame.interop/add-on-dispose!` at slot construction (Spec 006
  §Reference counting and disposal) and `re-frame.interop/dispose!` at
  slot evict — so a layer-2+ sub's declared inputs lose their reader when
  the parent reaction disposes. Both runtimes must honour that contract
  or input ref-counts leak monotonically until `clear-sub-cache!`.

    - **JVM.** The plain-atom JVM derived value implements
      `re-frame.interop/IDisposeRegistry`, so it carries its own on-dispose
      callback storage on the object. So
      `re-frame.interop/add-on-dispose!` / `dispose!` store/fire
      callbacks directly on the value — no process-wide registry, and
      an orphaned value is GC-reclaimable along with its callbacks
      rather than pinned in a global strong-ref map.

    - **CLJS.** `re-frame.interop` (the `.cljs`) reaches those calls
      through the `:adapter/add-on-dispose!` / `:adapter/dispose!`
      late-bind slots, which hold the installed map's `:add-on-dispose!` /
      `:dispose!`. The plain-atom CLJS derived value therefore reifies
      `re-frame.disposable/IDisposable` (mirroring the spine), and the map
      carries `add-on-dispose!` / `dispose!` below over that protocol, so a
      CLJS-plain-atom host (rather than a leak) releases inputs
      symmetrically on slot evict. The SSR adapter, which reuses
      `make-derived-value`, carries the same two.

  The atom-backed container quartet (`make-state-container` /
  `read-container` / `replace-container!` / `subscribe-container`) lives in
  `re-frame.substrate.atom-container` — see that ns for why only the
  container quartet (not `make-derived-value`) lives there."
  (:require [re-frame.error :as rf.error]
            [re-frame.substrate.atom-container :as rf.substrate.atom-container]
            #?@(:clj  [[re-frame.interop :as rf.interop]]
                :cljs [[re-frame.disposable :as rf.disposable]])))

#?(:clj (set! *warn-on-reflection* true))

(defn- watch-sources!
  "Attach one observer to a plain-atom derived value: watch each source,
  recompute on a source change, and call `(notify old new)` when the computed
  value moved by `=` from the last one this observer was handed (its baseline
  is the value at attach). Returns a thunk that removes those source watches;
  a source refusing its watch leaves none of the others behind."
  [source-containers compute notify]
  (let [k         (gensym "rf-plain-atom-derived-")
        baseline  (atom (compute))
        unwatch   (fn [] (doseq [s source-containers] (remove-watch s k)))
        on-change (fn [_ _ _ _]
                    (let [nu      (compute)
                          [old _] (swap-vals! baseline (constantly nu))]
                      (when-not (= old nu) (notify old nu))))]
    (try
      (doseq [s source-containers] (add-watch s k on-change))
      (catch #?(:clj Throwable :cljs :default) e
        (unwatch)
        (throw e)))
    unwatch))

(defn make-derived-value
  "The plain-atom derived value: it recomputes on every deref and caches
  nothing. It takes a watch on both runtimes, so `subscribe-container` works on
  it as on a base container (Spec 006 §`make-derived-value`): each observer
  watches the sources while it is attached. It is disposable on both runtimes,
  so a layer-2+ sub's declared inputs are released when its cache entry is, and
  disposal removes any observer's source watches left. The SSR adapter reuses
  it."
  [source-containers compute-fn]
  ;; No caching: derived values recompute on every deref. SSR runs each
  ;; sub at most a handful of times per request; caching would add
  ;; complexity for negligible gain. An observer's baseline is not a cache:
  ;; it is the value that observer was last handed, so its next `old` is true.
  ;;
  ;; JVM: the value implements `rf.interop/IDisposeRegistry`, carrying its own
  ;; on-dispose callback storage. `re-frame.interop`'s `add-on-dispose!` /
  ;; `dispose!` store and fire callbacks directly on it — no process-wide
  ;; registry — so an un-disposed value is GC-reclaimable along with its
  ;; callbacks rather than pinned in a global strong-ref map.
  ;;
  ;; CLJS: `re-frame.interop` (the .cljs) reaches
  ;; `add-on-dispose!` / `dispose!` through the `:adapter/*` slots, which hold
  ;; the map's dispatch on the derived value reifying `IDisposable`. Reify it here
  ;; (mirroring `re-frame.substrate.spine`) so the sub-cache's symmetric
  ;; input-release callback is registered at slot construction and fires
  ;; at slot evict — otherwise layer-2+ input ref-counts never decrement
  ;; on the CLJS-plain-atom path.
  (let [compute          (fn [] (apply compute-fn (map deref source-containers)))
        observers        (atom {}) ; watch key -> [watch-fn unwatch-sources]
        on-dispose-fns   (atom [])
        add-observer!    (fn [this k f]
                           (let [unwatch    (watch-sources! source-containers compute
                                                            (fn [old nu] (f k this old nu)))
                                 [before _] (swap-vals! observers assoc k [f unwatch])]
                             ;; Re-adding a key replaces its watch, as on an atom.
                             (when-let [[_ replaced] (get before k)] (replaced))
                             this))
        remove-observer! (fn [this k]
                           (let [[before _] (swap-vals! observers dissoc k)]
                             (when-let [[_ unwatch] (get before k)] (unwatch))
                             this))
        dispose!         (fn []
                           ;; Snapshot-and-clear before firing, so a re-entrant
                           ;; dispose from inside an on-dispose callback finds
                           ;; nothing to fire twice.
                           (doseq [[_ [_ unwatch]] (first (reset-vals! observers {}))]
                             (unwatch))
                           (doseq [f (first (reset-vals! on-dispose-fns []))]
                             (f)))]
    #?(:clj
       ;; No disposed flag, as on `rf.interop`'s `Reaction`: a callback that a
       ;; dispose re-registers fires on the next one, which is how the sub-cache
       ;; keeps a slot that an explicit subscribe still holds.
       (reify
         clojure.lang.IDeref
         (deref [_] (compute))
         clojure.lang.IRef
         (addWatch [this k f] (add-observer! this k f))
         (removeWatch [this k] (remove-observer! this k))
         (getWatches [_] (into {} (map (fn [[k [f _]]] [k f])) @observers))
         (getValidator [_] nil)
         (setValidator [_ _]
           (throw (UnsupportedOperationException.
                    "a derived value is read-only, so it takes no validator")))
         rf.interop/IDisposeRegistry
         (-add-on-dispose [_ f] (swap! on-dispose-fns conj f) nil)
         (-dispose [_] (dispose!)))
       :cljs
       (let [disposed? (volatile! false)]
         (reify
           IDeref
           (-deref [_] (compute))
           IWatchable
           (-add-watch [this k f] (add-observer! this k f))
           (-remove-watch [this k] (remove-observer! this k))
           rf.disposable/IDisposable
           (-add-on-dispose [_ f] (swap! on-dispose-fns conj f))
           (-dispose [_]
             ;; Disposed once, mirroring the spine (spine.cljs): flip the
             ;; guard FIRST so a re-entrant or second dispose short-circuits.
             (when-not @disposed?
               (vreset! disposed? true)
               (dispose!))))))))

(defn- render [_ _ _]
  ;; SSR uses render-to-string exclusively. Calling render on the JVM is
  ;; a programmer error worth surfacing loudly.
  (rf.error/throw-error!
    :rf.error/render-on-headless-adapter
    'rf/render
    (str "render is not supported on the plain-atom adapter (it is headless "
         "— JVM/SSR, no React reactivity); render server-side HTML with "
         "ssr/render-to-string instead of rf/render.")
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
    (rf.error/throw-error!
      :rf.error/no-hiccup-emitter-bound
      'ssr/render-to-string
      (str "no hiccup emitter is bound on the plain-atom adapter; require the "
           "re-frame.ssr namespace (which calls set-hiccup-emitter! on load) "
           "before calling render-to-string.")
      ;; EP-0015: carry an EP-0015-safe SUMMARY of the
      ;; render-tree, never the raw tree (a thrown render diagnostic is
      ;; captured off-box before path-based projection can classify it).
      {:recovery :require-re-frame-ssr
       :extra    {:render-tree/summary (rf.error/diag-value-summary render-tree)}})))

(defn- register-context-provider [_frame-keyword]
  ;; No React context on the JVM; users thread frames as arguments per
  ;; Spec 002 §View ergonomics fallback.
  nil)

(defn- dispose-adapter! []
  ;; Nothing of the adapter's own to release: core's teardown disposes every
  ;; frame's sub-cache, watch handles are GC'd with their atoms, and the hiccup
  ;; emitter stays armed because it holds no host resource.
  nil)

;; ---- disposal capabilities (CLJS only) ------------------------------------
;;
;; On CLJS `re-frame.interop`'s `add-on-dispose!` / `dispose!` read the
;; `:adapter/*` slots `install-adapter!` fills from these two map keys (the JVM
;; `re-frame.interop` implements both directly over its own `Reaction`, so the
;; keys are CLJS-only). Each dispatches into the `re-frame.disposable/
;; IDisposable` protocol the CLJS `make-derived-value` reifies above, so a
;; CLJS-plain-atom host participates in the sub-cache's ref-count / disposal
;; contract symmetrically with the React-shaped adapters. The SSR adapter
;; reuses `make-derived-value`, so its map carries these same two fns.
;;
;; Both tolerate a value that does NOT satisfy the protocol by no-op'ing:
;; plain-atom never produces one, so it is not one of this adapter's values.

#?(:cljs
   (defn add-on-dispose!
     "Register `f` to run when `a` — a value `make-derived-value` returned —
     is disposed. A no-op on anything else."
     [a f]
     (when (satisfies? rf.disposable/IDisposable a)
       (rf.disposable/-add-on-dispose a f))))

#?(:cljs
   (defn dispose!
     "Dispose `a`, a value `make-derived-value` returned, firing its
     registered callbacks once. A no-op on anything else."
     [a]
     (when (satisfies? rf.disposable/IDisposable a)
       (rf.disposable/-dispose a))))

(def adapter
  "The plain-atom adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.substrate.plain-atom :as plain-atom])
      (rf/init! plain-atom/adapter)

  See Spec 006 §The adapter API contract for the required, optional and
  lifecycle functions and the capability keys; on CLJS the map carries the
  `:add-on-dispose!` / `:dispose!` capabilities."
  {:kind                      :rf.adapter/plain-atom
   :make-state-container      rf.substrate.atom-container/make-state-container
   :read-container            rf.substrate.atom-container/read-container
   :replace-container!        rf.substrate.atom-container/replace-container!
   :subscribe-container       rf.substrate.atom-container/subscribe-container
   :make-derived-value        make-derived-value
   :render                    render
   :render-to-string          render-to-string
   :register-context-provider register-context-provider
   :dispose-adapter!          dispose-adapter!
   #?@(:cljs [:add-on-dispose!  add-on-dispose!
              :dispose!         dispose!])})
