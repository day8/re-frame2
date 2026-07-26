(ns re-frame.adapter.reagent-slim
  "Reagent-compatible adapter backed by the `reagent2.*` rewrite, with no
  stock-Reagent dependency.

  A downstream app requires this adapter at its PUBLISHED namespace
  `re-frame.adapter.reagent` — the slim jar ships its adapter there (the
  in-tree `-slim` namespace is renamed during publication):

      (require '[re-frame.adapter.reagent :as ra])
      (rf/init! ra/adapter)

  See IMPL-SPEC.md §13.1 for the publication transform."
  (:require [reagent2.core             :as r]
            [reagent2.ratom            :as ratom]
            [reagent2.dom.client       :as rdc]
            [reagent2.impl.template    :as template]
            [re-frame.substrate.spine   :as spine]
            [re-frame.views            :as views]))

;; ---- shared ratom-spine wiring --------------------------------------------
;;
;; Reagent and reagent-slim share the ratom spine but inject different
;; reactive implementations. The spine must not require either ratom namespace:
;; stock `reagent.*` is structurally absent from the slim dependency graph.

(def ^:private spine-fns
  (spine/make-ratom-spine
    {;; Keep generated watch ids attributable in mixed-adapter test bundles.
     :gensym-prefix-sub "rf-reagent-slim-sub-"
     ;; Each op is a thin call-through lambda rather than the bare Var
     ;; value so the spine resolves the namespaced fn at CALL time. This
     ;; keeps the `with-redefs [rdc/create-root …]` test-observability the
     ;; slim render / dispose-drain pins rely on (capturing the bare Var
     ;; value at load time would freeze the original impls past any
     ;; `with-redefs` rebind). Runtime behaviour is identical.
     :r-atom        (fn [v] (r/atom v))
     :make-reaction (fn [thunk] (ratom/make-reaction thunk))
     :create-root   (fn [mount-point] (rdc/create-root mount-point))
     :render-root   (fn [root tree] (rdc/render root tree))
     :hydrate-root  (fn [mount-point tree] (rdc/hydrate-root mount-point tree))
     :unmount-root  (fn [root] (rdc/unmount root))
     ;; Cleanup owns this exact substrate dispatch even after the process
     ;; lifecycle's terminal claim closes every public routed hook.
     :disposable?   (fn [a] (satisfies? ratom/IDisposable a))
     :dispose!      (fn [a] (ratom/dispose! a))
     ;; This is the production synchronous commit primitive. The React
     ;; `flushSync` boundary is required because React 19 otherwise batches
     ;; forceUpdate; `reagent2.dom.client/flush-views!` is the separate test
     ;; primitive that composes with act and Suspense.
     :flush-render! rdc/flush-render!}))

(def set-hiccup-emitter!
  "Install the hiccup → HTML fn used by render-to-string. Last call wins.
  Published through a late-bound hook so `re-frame.ssr` can install it
  without a static adapter-to-SSR dependency."
  (:set-hiccup-emitter! spine-fns))

(def flush-views!
  "Flush pending slim renders synchronously. Wraps React's act() —
  intended for test code only. Calls (act (fn [] (batching/flush!)));
  with `f`, runs `f` then the synchronous render drain inside act.
  Returns nil. When act() is unreachable in the current React build it
  degrades to a plain synchronous flush (still runs `f` and drains the
  render queue), so a `:node-test` runner with no real React render path
  still flushes.
  The promise-returning `reagent2.dom.client/flush-views!` remains available
  when callers need deterministic Suspense ordering."
  (:flush-views! spine-fns))

(def adapter
  "The reagent-slim adapter map. Pass to `(rf/init! ...)` to install, using
  the PUBLISHED ns `re-frame.adapter.reagent` (the in-tree `-slim` ns is
  renamed at publication — IMPL-SPEC §13.1), so this is rename-stable:

      (require '[re-frame.adapter.reagent :as ra])
      (rf/init! ra/adapter)

  Shape-compatible with `re-frame.adapter.reagent/adapter`; only the substrate
  implementation differs. Adapter installation is explicit.

  The shared ratom spine owns rendering, disposal, hook routing, and SSR
  publication. It receives only injected `reagent2.*` operations, preserving
  the invariant that stock Reagent is absent from slim bundles."
  (spine/make-ratom-adapter
    spine-fns
    {:kind :rf.adapter/reagent-slim
     ;; The returned component receives the frame keyword at render time.
     :register-context-provider (fn [_frame-keyword] (views/build-frame-provider))
     ;; reagent2 reactions do not implement stock Reagent's IDisposable; the
     ;; spine handles re-frame-owned disposal before these substrate ops.
     :current-frame     views/current-frame
     :current-component r/current-component
     :atom              r/atom
     :ratom?            (fn [x] (satisfies? ratom/IReactiveAtom x))
     :make-reaction     ratom/make-reaction
     ;; rf2-8cnxg — the missing `deref-capture`, same defect and same shape
     ;; as stock Reagent's (the rewrite keeps stock's nine-field Reaction
     ;; kernel, demand-driven `-deref` included). `ratom/activate!` is the
     ;; rewrite's name for stock's `IRunnable` `run`; it is idempotent and
     ;; a no-op on anything that is not one of its Reactions.
     :activate-reaction! ratom/activate!
     :disposable?       (fn [a] (satisfies? ratom/IDisposable a))
     :add-on-dispose!   ratom/add-on-dispose!
     :dispose!          ratom/dispose!
     :reactive?         ratom/reactive?
     :after-render      r/after-render}))

;; ---- warn-once cache reset wiring -----------------------------------------
;;
;; The slim template interpreter has its own keyword-prop warning cache in
;; addition to the spine cache. Enrol its public reset function here so test
;; fixtures re-arm both caches. Keeping this wiring in the adapter avoids a
;; `reagent2.*` to `re-frame.*` dependency; the private cache intentionally has
;; no arm-state probe.
(spine/install-clear-warn-once-step! template/clear-warned-keyword-prop!
                                     {:label :reagent-slim/warned-keyword-prop})
