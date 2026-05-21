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
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.substrate.spine :as spine]
            [re-frame.views :as views]))

;; ---- shared ratom-spine wiring --------------------------------------------
;;
;; The container quartet, the React-root renderer, the dispose body and
;; the SSR emitter helpers are the SAME shape as reagent-slim, modulo the
;; reactive-atom impl (stock `reagent.*` here, `reagent2.*` there). They
;; live in `re-frame.substrate.spine/make-ratom-spine` (rf2-rzex9) — one
;; implementation, two adapters, zero drift, mirroring the React-hook
;; `make-react-spine` that backs UIx/Helix. The reactive-atom ops are
;; INJECTED here so the spine never `:require`s a reactive-atom ns: this
;; adapter passes its stock `reagent.*` impls; the slim adapter passes
;; `reagent2.*`. (Load-bearing for reagent-slim bundle isolation — the
;; slim adapter's deps.edn carries zero direct `reagent.*` requires.)

(def ^:private spine-fns
  (spine/make-ratom-spine
    {:substrate-name    "Reagent"
     ;; Substrate-scoped gensym prefix (rf2-l4dmr) so a cross-substrate
     ;; test bundle / log / inspector can attribute a watch to the
     ;; Reagent adapter rather than confusing it with a slim watch.
     :gensym-prefix-sub "rf-reagent-sub-"
     ;; Each op is a thin call-through lambda rather than the bare Var
     ;; value so the spine resolves the namespaced fn at CALL time. This
     ;; keeps the `with-redefs [rdc/create-root …]` test-observability the
     ;; adapter-render / dispose-drain pins rely on (capturing the bare
     ;; Var value at load time would freeze the original impls past any
     ;; `with-redefs` rebind). Runtime behaviour is identical.
     :ratom-ops         {:r/atom              (fn [v] (r/atom v))
                         :ratom/make-reaction (fn [thunk] (ratom/make-reaction thunk))
                         :rdc/create-root     (fn [mp] (rdc/create-root mp))
                         :rdc/render          (fn [root tree] (rdc/render root tree))
                         :rdc/hydrate-root    (fn [mp tree] (rdc/hydrate-root mp tree))
                         :rdc/unmount         (fn [root] (rdc/unmount root))}}))

(def set-hiccup-emitter!
  "Install the hiccup → HTML fn used by render-to-string. Idempotent.
  Reagent ships server-side rendering via reagent.dom.server — but in
  CLJS browser builds we don't typically render-to-string; install the
  emitter via this fn (or let the SSR seam resolve the late-bind hook)
  if you need it in CLJS."
  (:set-hiccup-emitter! spine-fns))

;; ---- context provider -----------------------------------------------------

(defn- register-context-provider [_frame-keyword]
  ;; Implementation lives in re-frame.views (CLJS-only). The frame-
  ;; keyword arg is ignored — `build-frame-provider` is 0-arity
  ;; (rf2-4y60); the returned component takes the frame keyword at
  ;; render time. The arg stays in the substrate signature per
  ;; Spec 006 §Frame-provider via React context. Kept as adapter-side
  ;; wiring (not in the shared ratom-spine) because it is the Reagent-
  ;; component-shaped frame-provider, distinct from the React-hook
  ;; spine's hook-shaped one — and so the core spine carries no
  ;; spine→views dependency edge.
  (views/build-frame-provider))

(def adapter
  "The Reagent adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.adapter.reagent :as reagent])
      (rf/init! reagent/adapter)

  See Spec 006 §CLJS reference: Reagent as default adapter for the
  bridging pseudocode. Per rf2-agql there is no default-adapter
  registry — adapter wiring is explicit at the call site.

  The container quartet, renderer, render-to-string and dispose body
  come from `spine/make-ratom-spine` (rf2-rzex9, shared with
  reagent-slim); `register-context-provider` stays adapter-local
  (Reagent-component-shaped frame-provider via `re-frame.views`)."
  {:kind                      :rf.adapter/reagent
   :make-state-container      (:make-state-container spine-fns)
   :read-container            (:read-container       spine-fns)
   :replace-container!        (:replace-container!   spine-fns)
   :subscribe-container       (:subscribe-container  spine-fns)
   :make-derived-value        (:make-derived-value   spine-fns)
   :render                    (:render               spine-fns)
   :render-to-string          (:render-to-string     spine-fns)
   :register-context-provider register-context-provider
   :dispose-adapter!          (:dispose-adapter!     spine-fns)})

;; Chained SSR emitter install (rf2-4z7bp / parity rf2-cl1qv):
;; `re-frame.ssr.emit` invokes `:reagent/set-hiccup-emitter!` at
;; ns-load; every loaded React-shaped adapter (Reagent, reagent-slim,
;; UIx, Helix) contributes its own install step so a single
;; `(require '[re-frame.ssr])` auto-wires every adapter's
;; render-to-string slot. The directory entry for this hook is
;; `:chained? true` and lists all four adapters as producers — using
;; `chain-fn!` (not `set-fn!`) is load-order-independent: any adapter
;; loading after the others composes onto the existing chain instead
;; of clobbering it. Per rf2-uo7v ssr ships in day8/re-frame2-ssr;
;; this adapter cannot statically `:require [re-frame.ssr]` without
;; dragging the SSR namespace into every Reagent bundle. When ssr is
;; absent the hook is never consumed and render-to-string raises the
;; "no-hiccup-emitter-bound" error on first call. Per Spec 006
;; §Adapter shipping convention (rf2-0hxm).
(late-bind/chain-fn! :reagent/set-hiccup-emitter! set-hiccup-emitter!)

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
