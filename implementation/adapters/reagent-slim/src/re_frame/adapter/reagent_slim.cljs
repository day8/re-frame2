(ns re-frame.adapter.reagent-slim
  "The day8/reagent-slim adapter — emits the 9-key substrate map for
  `re-frame.substrate.adapter`. Shape-compatible with the bridge
  adapter `re-frame.adapter.reagent`; internals route through the
  `reagent2.*` rewrite of Reagent (no stock-Reagent dep).

      (require '[re-frame.adapter.reagent-slim :as reagent-slim])
      (rf/init! reagent-slim/adapter)

  See IMPL-SPEC.md §2.1 (9-key map contract), §9 (late-bind hook table),
  §13.1 (artefact-publication shape) and DESIGN-RATIONALE.md."
  (:require [reagent2.core             :as r]
            [reagent2.ratom            :as ratom]
            [reagent2.dom.client       :as rdc]
            [re-frame.disposable       :as rf-disposable]
            [re-frame.frame            :as frame]
            [re-frame.late-bind        :as late-bind]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.substrate.spine   :as spine]
            [re-frame.views            :as views]))

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
  ;; Arity-specialised recompute closure via `spine/build-recompute-fn`
  ;; (rf2-eoy63 / rf2-v1nu0 / rf2-fzrav). Pre-rf2-eoy63 this body was a
  ;; naive `(apply compute-fn (map deref ...))` — paid `apply` + a lazy
  ;; cons cell per source per recompute on the hot path. Lifted into
  ;; the spine so reagent-slim shares the same arity-spec as the
  ;; Reagent, UIx and Helix adapters — single source of truth, same
  ;; pattern as `spine/dispose-frame-sub-caches!` (rf2-jcjul).
  (ratom/make-reaction (spine/build-recompute-fn source-containers compute-fn)))

;; ---- render ---------------------------------------------------------------
;;
;; Active roots are tracked in a per-adapter atom so `dispose-adapter!`
;; can drain them (rf2-7v82h; mirrors the Reagent adapter's
;; rf2-9fdkb tracking). Each mount adds the React-19 root to the active
;; set; the returned unmount thunk removes itself from the set before
;; calling `(rdc/unmount root)`.

(defonce ^:private active-roots (atom #{}))

(defn- render [render-tree mount-point opts]
  ;; The bridge mounted into a raw DOM container directly; under the
  ;; rewrite we explicitly create a React-19 root once and reuse it.
  ;; The unmount thunk closes over the root to call its .unmount.
  ;; Per rf2-gwkvr: Spec 006 §`render` types `:hydrate?` as a boolean;
  ;; no defensive coercion.
  (let [hydrate? (:hydrate? opts)
        root     (if hydrate?
                   (rdc/hydrate-root mount-point render-tree)
                   (let [r (rdc/create-root mount-point)]
                     (rdc/render r render-tree)
                     r))]
    (swap! active-roots conj root)
    (fn unmount []
      (swap! active-roots disj root)
      (rdc/unmount root))))

(defonce ^:private hiccup-emitter (atom nil))

(defn set-hiccup-emitter!
  "Install the hiccup → HTML fn used by render-to-string. Idempotent.
  Per rf2-uo7v / IMPL-SPEC §2.1: published through the late-bind hook
  `:reagent/set-hiccup-emitter!` so the SSR seam at re-frame.ssr
  resolves it at load time without a static :require."
  [f]
  (reset! hiccup-emitter f))

(defn- render-to-string [render-tree opts]
  (if-let [emit @hiccup-emitter]
    (emit render-tree opts)
    (throw (ex-info ":rf.error/no-hiccup-emitter-bound"
                    {:reason      "require re-frame.ssr (the SSR ns-load resolves the :reagent/set-hiccup-emitter! late-bind hook automatically), or call set-hiccup-emitter! directly"
                     :render-tree render-tree}))))

;; ---- context provider -----------------------------------------------------

(defn- register-context-provider [_frame-keyword]
  ;; Implementation lives in re-frame.views (CLJS-only). The frame-
  ;; keyword arg is ignored — `build-frame-provider` is 0-arity
  ;; (rf2-4y60); the returned component takes the frame keyword at
  ;; render time. Per IMPL-SPEC §9.4: the views.cljs ns continues to
  ;; back the frame-provider; the rewrite doesn't replace it.
  (views/build-frame-provider))

;; ---- disposal -------------------------------------------------------------

(defn- dispose-adapter! []
  ;; Spec 006 §Adapter disposal lifecycle (rf2-9fdkb, rf2-a47kq,
  ;; rf2-jcjul, rf2-7v82h). The four-MUST list — brought to full parity
  ;; with the Reagent adapter (reagent.cljs:100-137):
  ;;
  ;;   1. Cancel all in-flight reactive subscriptions — walk every live
  ;;      frame's per-frame sub-cache and dispose each cached Reaction.
  ;;      The component-unmount-driven path handles the mounted case
  ;;      (reagent2 reaps Reactions once their last watcher drops) — but
  ;;      `dispose-adapter!` MUST cover the headless / test-fixture path
  ;;      where no component unmount fires before the adapter goes away,
  ;;      and the SSR path where the rendered tree was string-serialised
  ;;      without ever being mounted. Without the walk, sequential
  ;;      `init! → dispose-adapter!` cycles in a long-lived process
  ;;      accumulate per-frame sub-cache entries closed over stale
  ;;      Reactions forever. Delegated to
  ;;      `spine/dispose-frame-sub-caches!` (rf2-jcjul): the same helper
  ;;      backs the Reagent adapter and the spine-built UIx / Helix
  ;;      adapters so all three substrates share one implementation —
  ;;      no three-way drift on the lifecycle MUST.
  ;;
  ;;   2. Release host-specific resources — drain the active-roots set
  ;;      (React 19 roots; mounted-but-not-unmounted at process exit /
  ;;      hot-reload). Swallow per-root throws so one misbehaving root
  ;;      cannot strand the rest of the drain (rf2-7v82h).
  ;;
  ;;   3. Discard internal caches — clear the hiccup-emitter (the SSR
  ;;      late-bind sink; the registered fn captures `re-frame.ssr`
  ;;      state that must not survive the adapter) (rf2-7v82h).
  ;;
  ;;   4. Make subsequent calls return `:rf.error/adapter-disposed` —
  ;;      handled one level up by `substrate-adapter/dispose-adapter!`
  ;;      via the `disposed?` breadcrumb (rf2-6wxys).
  (spine/dispose-frame-sub-caches!)
  (doseq [root @active-roots]
    (try (rdc/unmount root)
         (catch :default _ nil)))
  (reset! active-roots #{})
  (reset! hiccup-emitter nil)
  nil)

(def adapter
  "The reagent-slim adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.adapter.reagent-slim :as reagent-slim])
      (rf/init! reagent-slim/adapter)

  Drop-in shape-compatible with `re-frame.adapter.reagent/adapter` per
  IMPL-SPEC §2.1 — the only difference is the substrate, not the keys.
  Per Spec 006 §CLJS reference + rf2-agql: there is no default-adapter
  registry; adapter wiring is explicit at the call site."
  {:kind                      :rf.adapter/reagent-slim
   :make-state-container      make-state-container
   :read-container            read-container
   :replace-container!        replace-container!
   :subscribe-container       subscribe-container
   :make-derived-value        make-derived-value
   :render                    render
   :render-to-string          render-to-string
   :register-context-provider register-context-provider
   :dispose-adapter!          dispose-adapter!})

;; Per rf2-uo7v + IMPL-SPEC §9.2: publish the hiccup-emitter installer
;; through the chained late-bind hook table so re-frame.ssr (in
;; day8/re-frame2-ssr) resolves it at its ns-load. Without this, an
;; app pulling in the SSR seam would have to manually call
;; `(reagent-slim/set-hiccup-emitter! ssr/render-to-string)` —
;; the late-bind table makes that wiring automatic when both
;; artefacts are on the classpath.
(late-bind/chain-fn! :reagent/set-hiccup-emitter! set-hiccup-emitter!)

;; Each late-bind hook below is routed through `(substrate-adapter/
;; current-adapter)` per rf2-0d35 via `substrate-adapter/route-hook!`
;; (see that fn's docstring for the routing contract). The wrapper runs
;; this adapter's impl ONLY when this adapter is the (rf/init!)-installed
;; one; otherwise it chains to the previously-registered handler.
;;
;; Hook-specific rationale (slim-adapter notes):
;;   :adapter/current-frame  — rf2-d4sf + IMPL-SPEC §9.6. The rewrite
;;     preserves the bridge's class-component (.-context cmp) shape, so
;;     re-frame.views/current-frame works unchanged with reagent-slim's
;;     class components. Chain-bottom fallback is frame/current-frame.
;;   :adapter/current-component — rf2-wbnl. Wires
;;     reagent2.core/current-component so re-frame.views resolves the
;;     in-flight component from the slim substrate (stock Reagent's
;;     reader would return nil for slim-rendered components).
;;   :adapter/ratom etc. — rf2-s36l. Wires reagent2.* impls so
;;     re-frame.interop's reactive-substrate calls dispatch onto
;;     reagent2.ratom/IDisposable / IReactiveAtom — without this seam
;;     the very first (interop/add-on-dispose! ...) under the slim
;;     adapter threw because reagent2.ratom/Reaction does NOT reify
;;     stock Reagent's IDisposable.
(substrate-adapter/route-hook! adapter :adapter/current-frame
  views/current-frame
  #(frame/current-frame))
(substrate-adapter/route-hook! adapter :adapter/current-component
  r/current-component)
(substrate-adapter/route-hook! adapter :adapter/ratom
  r/atom)
(substrate-adapter/route-hook! adapter :adapter/ratom?
  (fn ratom?-impl [x] (satisfies? ratom/IReactiveAtom x))
  (constantly false))
(substrate-adapter/route-hook! adapter :adapter/make-reaction
  ratom/make-reaction)
;; rf2-jicu2: spine-produced derived values (from UIx/Helix substrates
;; in a cross-substrate test bundle) reify the re-frame-owned
;; `re-frame.disposable/IDisposable`; reagent-slim's own Reactions
;; reify `reagent2.ratom/IDisposable`. The dispatcher protocol-checks
;; both shapes — the re-frame-owned one first because it is the new
;; path; falls through to reagent2's protocol for slim-native
;; reactions.
(substrate-adapter/route-hook! adapter :adapter/add-on-dispose!
  (fn add-on-dispose!-dispatch [a f]
    (cond
      (satisfies? rf-disposable/IDisposable a) (rf-disposable/-add-on-dispose a f)
      (satisfies? ratom/IDisposable a)         (ratom/add-on-dispose! a f)
      :else                                    nil)))
(substrate-adapter/route-hook! adapter :adapter/dispose!
  (fn dispose!-dispatch [a]
    (cond
      (satisfies? rf-disposable/IDisposable a) (rf-disposable/-dispose a)
      (satisfies? ratom/IDisposable a)         (ratom/dispose! a)
      :else                                    nil)))
(substrate-adapter/route-hook! adapter :adapter/reactive?
  ratom/reactive?
  (constantly false))
(substrate-adapter/route-hook! adapter :adapter/after-render
  r/after-render)
