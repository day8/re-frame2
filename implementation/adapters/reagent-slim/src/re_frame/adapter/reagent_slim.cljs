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
            [re-frame.substrate.spine   :as rf.substrate.spine]
            [re-frame.views            :as rf.views]))

;; ---- shared ratom-spine wiring --------------------------------------------
;;
;; Reagent and reagent-slim share the ratom spine but inject different
;; reactive implementations. The spine must not require either ratom namespace:
;; stock `reagent.*` is structurally absent from the slim dependency graph.

(def ^:private spine-fns
  (rf.substrate.spine/make-ratom-spine
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
  It publishes a render phase, so do not call it from inside a
  `dispatch-sync` handler (rf2-0c23j).
  The promise-returning `reagent2.dom.client/flush-views!` remains available
  when callers need deterministic Suspense ordering."
  (:flush-views! spine-fns))

;; ---- the client root ------------------------------------------------------
;;
;; rf2-k5r9t. The same `client-root` / `render!` / `unmount!` trio the stock
;; Reagent adapter publishes, from the same shared ratom spine, so an app
;; that swaps coordinates keeps its boot namespace byte for byte. See
;; `re-frame.adapter.reagent` for the recipe.

(def client-root
  "Allocate an inert client-root handle. No DOM work — safe at namespace
  load under a `defonce`, in tests, and on Node. The React Root is created
  (or hydrated) by the first `render!` through it. The handle is opaque:
  hold it, hand it to `render!` and `unmount!`, and nothing else."
  (:client-root spine-fns))

(def render!
  "Render `render-tree` (hiccup) through the client-root `handle` at the DOM
  element `mount-point`. Returns nil.

      (render! handle render-tree mount-point)
      (render! handle render-tree mount-point {:hydrate? true})

  The first call creates the React Root at `mount-point` and renders into
  it — or, with `{:hydrate? true}`, hydrates the server-rendered markup
  already inside `mount-point` (once). Every later call updates that same
  Root with the new tree: no second `create-root`, no second hydration, so
  the one call is both the boot path and the `^:dev/after-load` hook.
  `mount-point` is read on the first call only. `rf/destroy-adapter!`
  releases a Root this handle still holds, exactly once."
  (:render-client-root! spine-fns))

(def unmount!
  "Unmount the React Root `handle` holds and return the handle to inert.
  Idempotent: a second call, or a call after `rf/destroy-adapter!` has
  already released the Root, does nothing. Returns nil."
  (:unmount-client-root! spine-fns))

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
  (rf.substrate.spine/make-ratom-adapter
    spine-fns
    {:kind :rf.adapter/reagent-slim
     ;; The returned component receives the frame keyword at render time.
     :register-context-provider (fn [_frame-keyword] (rf.views/build-frame-provider))
     ;; reagent2 reactions do not implement stock Reagent's IDisposable; the
     ;; spine handles re-frame-owned disposal before these substrate ops.
     :current-frame     rf.views/current-frame
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
(rf.substrate.spine/install-clear-warn-once-step! template/clear-warned-keyword-prop!
                                     {:label :reagent-slim/warned-keyword-prop})
