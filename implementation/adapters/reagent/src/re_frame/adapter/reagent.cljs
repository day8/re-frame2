(ns re-frame.adapter.reagent
  "The Reagent adapter — browser default. Implements the substrate
  contract; see `re-frame.substrate.adapter` for the contract itself
  (canonical home: `core/src/re_frame/substrate/adapter.cljc`) and
  Spec 006 §CLJS reference for the per-slot semantics."
  (:require [reagent.core :as r]
            [reagent.ratom :as ratom]
            [reagent.dom.client :as rdc]
            [re-frame.disposable :as rf-disposable]
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
  ;; Per recompute we want zero lazy-seq allocation and zero `apply`
  ;; cost on the dominant arities. Subs typically chain off 1 input
  ;; (layer-1 always; layer-n usually 1–2); specialise those, fall
  ;; back to `mapv` (eager, vector-backed) + `apply` for ≥3.
  ;; rf2-v1nu0; sourced from the rf2-fzrav perf sweep findings.
  ;;
  ;; `count` is captured once at construction (source-containers is a
  ;; vector — see `inputs` in `re-frame.subs`) so the recompute closure
  ;; pays no per-tick count.
  (let [n (count source-containers)
        recompute (case n
                    0 (fn [] (compute-fn))
                    1 (let [s0 (nth source-containers 0)]
                        (fn [] (compute-fn @s0)))
                    2 (let [s0 (nth source-containers 0)
                            s1 (nth source-containers 1)]
                        (fn [] (compute-fn @s0 @s1)))
                    (fn [] (apply compute-fn (mapv deref source-containers))))]
    (ratom/make-reaction recompute)))

;; ---- render ---------------------------------------------------------------
;;
;; Active roots are tracked in a per-adapter atom so `dispose-adapter!`
;; can drain them (rf2-9fdkb). Each mount adds the Reagent Root to the
;; active set; the returned unmount thunk removes itself from the set
;; before calling `(rdc/unmount root)`.

(defonce ^:private active-roots (atom #{}))

(defn- render [render-tree mount-point opts]
  ;; React 18+ Root API: create-root → render → unmount; hydrate-root
  ;; on the SSR path returns its own Root (rf2-fn5rk + rf2-6hyy slim
  ;; parity). Pin: `adapter-render-cljs-test`. Per rf2-gwkvr: Spec 006
  ;; §`render` types `:hydrate?` as a boolean; no defensive coercion.
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
                    {:reason "require re-frame.ssr (the SSR ns-load resolves the :reagent/set-hiccup-emitter! late-bind hook automatically), or call set-hiccup-emitter! directly"
                     :render-tree render-tree}))))

;; ---- context provider -----------------------------------------------------

(defn- register-context-provider [_frame-keyword]
  ;; Implementation lives in re-frame.views (CLJS-only). The frame-
  ;; keyword arg is ignored — `build-frame-provider` is 0-arity
  ;; (rf2-4y60); the returned component takes the frame keyword at
  ;; render time. The arg stays in the substrate signature per
  ;; Spec 006 §Frame-provider via React context.
  (views/build-frame-provider))

;; ---- disposal -------------------------------------------------------------

(defn- dispose-adapter! []
  ;; Spec 006 §Adapter disposal lifecycle (rf2-9fdkb, rf2-a47kq). The
  ;; four-MUST list:
  ;;
  ;;   1. Cancel all in-flight reactive subscriptions — walk every live
  ;;      frame's per-frame sub-cache and dispose each cached Reaction.
  ;;      Component-unmount-driven disposal handles the mounted case
  ;;      (Reagent reaps Reactions when their last watcher drops); the
  ;;      explicit walk covers the test-fixture / headless path where
  ;;      no component unmount fires before the adapter goes away.
  ;;      `interop/dispose!` routes through `:adapter/dispose!` which is
  ;;      still wired for this adapter at this point in the teardown
  ;;      (substrate-adapter clears the install slot AFTER calling us).
  ;;
  ;;   2. Release host-specific resources — drain the active-roots set
  ;;      (React 18+ Roots; mounted-but-not-unmounted at process exit /
  ;;      hot-reload). Swallow per-root throws so one misbehaving root
  ;;      cannot strand the rest of the drain.
  ;;
  ;;   3. Discard internal caches — clear hiccup-emitter (the SSR
  ;;      late-bind sink; the registered fn captures `re-frame.ssr`
  ;;      state that must not survive the adapter).
  ;;
  ;;   4. Make subsequent calls return `:rf.error/adapter-disposed` —
  ;;      handled one level up by `substrate-adapter/dispose-adapter!`
  ;;      via the `disposed?` breadcrumb (rf2-6wxys).
  (doseq [[_ frame-record] @frame/frames]
    (when-let [cache (:sub-cache frame-record)]
      (doseq [[_k entry] @cache]
        (when-let [h (:pending-dispose entry)]
          (try (interop/clear-timeout! h)
               (catch :default _ nil)))
        (when-let [r (:reaction entry)]
          (try (interop/dispose! r)
               (catch :default _ nil))))
      (reset! cache {})))
  (doseq [root @active-roots]
    (try (rdc/unmount root)
         (catch :default _ nil)))
  (reset! active-roots #{})
  (reset! hiccup-emitter nil)
  nil)

(def adapter
  "The Reagent adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.adapter.reagent :as reagent])
      (rf/init! reagent/adapter)

  See Spec 006 §CLJS reference: Reagent as default adapter for the
  bridging pseudocode. Per rf2-agql there is no default-adapter
  registry — adapter wiring is explicit at the call site."
  {:kind                      :rf.adapter/reagent
   :make-state-container      make-state-container
   :read-container            read-container
   :replace-container!        replace-container!
   :subscribe-container       subscribe-container
   :make-derived-value        make-derived-value
   :render                    render
   :render-to-string          render-to-string
   :register-context-provider register-context-provider
   :dispose-adapter!          dispose-adapter!})

;; Wire ssr's render-to-string into this adapter's :render-to-string
;; slot. Per rf2-uo7v ssr ships in day8/re-frame2-ssr; this adapter
;; cannot statically `:require [re-frame.ssr]` without dragging the SSR
;; namespace into every Reagent bundle. Publish set-hiccup-emitter!
;; through the late-bind hook table — when the ssr artefact is loaded,
;; its ns-load resolves the hook and wires the emitter through it. When
;; ssr is absent the hook is never consumed and render-to-string raises
;; the "no-hiccup-emitter-bound" error on first call. Per Spec 006
;; §Adapter shipping convention (rf2-0hxm).
(late-bind/set-fn! :reagent/set-hiccup-emitter! set-hiccup-emitter!)

;; Each late-bind hook below is routed through `(substrate-adapter/
;; current-adapter)` per rf2-0d35 via `substrate-adapter/route-hook!`
;; (see that fn's docstring for the routing contract). The wrapper runs
;; this adapter's impl ONLY when this adapter is the (rf/init!)-installed
;; one; otherwise it chains to the previously-registered handler.
;;
;; Hook-specific rationale:
;;   :adapter/current-frame  — rf2-d4sf. React-context tier of the
;;     3-tier resolution chain. Reagent path uses (.-context cmp) on
;;     the in-flight Reagent component (class-component machinery), so
;;     `reg-view*`-wrapped components route to the surrounding
;;     provider's frame while plain Reagent fns fall through to
;;     :rf/default — that narrowness makes the
;;     plain-fn-under-non-default-frame-once warning meaningful.
;;     Chain-bottom fallback is `frame/current-frame` so headless /
;;     pre-init shape is preserved.
;;   :adapter/current-component — rf2-wbnl. Reads stock Reagent's
;;     in-flight component without hard-binding re-frame.views to
;;     reagent.core; the slim adapter wires this hook to
;;     reagent2.core/current-component.
;;   :adapter/ratom etc. — rf2-s36l. The reactive-substrate surfaces
;;     consumed by `re-frame.interop`. UIx and Helix adapters reify
;;     stock reagent.ratom/IDisposable on their derived values, so
;;     they wire these hooks to the same stock-Reagent impls.
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
;; rf2-jicu2: a Reagent-installed app may still hold a spine-produced
;; derived value (e.g. inherited through a cross-substrate test bundle
;; that pre-loaded UIx/Helix machinery). Dispatch handles BOTH shapes —
;; the re-frame-owned `re-frame.disposable/IDisposable` (spine derived
;; values) and Reagent's own `reagent.ratom/IDisposable` (Reagent
;; reactions). Protocol-checks the re-frame-owned one first because it
;; is the new path; falls through to Reagent's protocol for
;; Reagent-native reactions.
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
