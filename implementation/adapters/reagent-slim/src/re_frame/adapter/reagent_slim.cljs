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

;; ---- shared ratom-spine wiring --------------------------------------------
;;
;; The container quartet, the React-root renderer, the dispose body and
;; the SSR emitter helpers are the SAME shape as the bridge adapter
;; `re-frame.adapter.reagent`, modulo the reactive-atom impl (`reagent2.*`
;; here, stock `reagent.*` there). They live in
;; `re-frame.substrate.spine/make-ratom-spine` (rf2-rzex9) — one
;; implementation, two adapters, zero drift, mirroring the React-hook
;; `make-react-spine` that backs UIx/Helix.
;;
;; CRITICAL — bundle isolation (IMPL-SPEC §1.8 / the
;; `test:reagent-slim:bundle-isolation` gate). The spine MUST NOT
;; `:require` stock `reagent.*`; the reactive-atom ops are INJECTED here
;; so this adapter's `reagent2.*` requires stay confined to this ns and
;; the spine never names a reactive-atom ns. deps.edn carries zero direct
;; `reagent.*` requires (the slim framing: a re-implementation, not a
;; thin wrapper).

(def ^:private spine-fns
  (spine/make-ratom-spine
    {:substrate-name    "reagent-slim"
     ;; Substrate-scoped gensym prefix (rf2-l4dmr) so a cross-substrate
     ;; test bundle / log / inspector can attribute a watch to the slim
     ;; adapter rather than confusing it with a stock-Reagent watch.
     :gensym-prefix-sub "rf-reagent-slim-sub-"
     ;; Each op is a thin call-through lambda rather than the bare Var
     ;; value so the spine resolves the namespaced fn at CALL time. This
     ;; keeps the `with-redefs [rdc/create-root …]` test-observability the
     ;; slim render / dispose-drain pins rely on (capturing the bare Var
     ;; value at load time would freeze the original impls past any
     ;; `with-redefs` rebind). Runtime behaviour is identical.
     :ratom-ops         {:r/atom              (fn [v] (r/atom v))
                         :ratom/make-reaction (fn [thunk] (ratom/make-reaction thunk))
                         :rdc/create-root     (fn [mp] (rdc/create-root mp))
                         :rdc/render          (fn [root tree] (rdc/render root tree))
                         :rdc/hydrate-root    (fn [mp tree] (rdc/hydrate-root mp tree))
                         :rdc/unmount         (fn [root] (rdc/unmount root))}}))

(def set-hiccup-emitter!
  "Install the hiccup → HTML fn used by render-to-string. Idempotent.
  Per rf2-uo7v / IMPL-SPEC §2.1: published through the late-bind hook
  `:reagent/set-hiccup-emitter!` so the SSR seam at re-frame.ssr
  resolves it at load time without a static :require."
  (:set-hiccup-emitter! spine-fns))

;; ---- context provider -----------------------------------------------------

(defn- register-context-provider [_frame-keyword]
  ;; Implementation lives in re-frame.views (CLJS-only). The frame-
  ;; keyword arg is ignored — `build-frame-provider` is 0-arity
  ;; (rf2-4y60); the returned component takes the frame keyword at
  ;; render time. Per IMPL-SPEC §9.4: the views.cljs ns continues to
  ;; back the frame-provider; the rewrite doesn't replace it. Kept as
  ;; adapter-side wiring (not in the shared ratom-spine) because it is
  ;; the Reagent-component-shaped frame-provider, distinct from the
  ;; React-hook spine's hook-shaped one.
  (views/build-frame-provider))

(def adapter
  "The reagent-slim adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.adapter.reagent-slim :as reagent-slim])
      (rf/init! reagent-slim/adapter)

  Drop-in shape-compatible with `re-frame.adapter.reagent/adapter` per
  IMPL-SPEC §2.1 — the only difference is the substrate, not the keys.
  Per Spec 006 §CLJS reference + rf2-agql: there is no default-adapter
  registry; adapter wiring is explicit at the call site.

  The container quartet, renderer, render-to-string and dispose body
  come from `spine/make-ratom-spine` (rf2-rzex9, shared with the bridge
  Reagent adapter under an injected `reagent2.*` op set);
  `register-context-provider` stays adapter-local (Reagent-component-
  shaped frame-provider via `re-frame.views`)."
  {:kind                      :rf.adapter/reagent-slim
   :make-state-container      (:make-state-container spine-fns)
   :read-container            (:read-container       spine-fns)
   :replace-container!        (:replace-container!   spine-fns)
   :subscribe-container       (:subscribe-container  spine-fns)
   :make-derived-value        (:make-derived-value   spine-fns)
   :render                    (:render               spine-fns)
   :render-to-string          (:render-to-string     spine-fns)
   :register-context-provider register-context-provider
   :dispose-adapter!          (:dispose-adapter!     spine-fns)})

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
