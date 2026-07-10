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
            [re-frame.views            :as views]
            ;; Both dependencies are in core and introduce no stock-Reagent
            ;; edge. The lease factory owns shared lifecycle semantics; context
            ;; supplies the React frame context.
            [re-frame.adapter.resource-lease :as resource-lease]
            [re-frame.adapter.context  :as adapter-context]))

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
  Returns nil. No-op when act() is unreachable in the current React build.
  The promise-returning `reagent2.dom.client/flush-views!` remains available
  when callers need deterministic Suspense ordering."
  (:flush-views! spine-fns))

;; ---- resource-lease mount-lifecycle helper -------------------------------
;;
;; Lease identity and ordering are shared across adapter families so mixed
;; trees cannot mint colliding owners. The published namespace matches the
;; stock adapter, so both artefacts expose the same Var. Slim's restricted
;; create-class map cannot carry `:context-type`; contextType is attached to
;; the returned class without introducing a stock-Reagent dependency.

(def with-resource-lease
  "reagent-slim component that takes a resource liveness lease for its mounted
  lifetime. It is the slim Form-3
  counterpart of the UIx / Helix `use-resource-lease` hook and the shape-twin
  of `re-frame.adapter.reagent/with-resource-lease`. On mount it dispatches
  `:rf.resource/ensure` with an app-minted `[:lease …]` owner; on unmount it
  releases that lease via `:rf.resource/release-owner`. Use it so a view
  declaratively OWNS a polled / cached resource for as long as it is mounted.

  Call it as a component with the resource descriptor and a body thunk:

      [reagent-slim/with-resource-lease
       {:resource :my/feed :scope :rf.scope/global :params {:page 0}}
       (fn [] [feed-view])]

  Or with opts (a map between the descriptor and the body thunk):

      [reagent-slim/with-resource-lease
       {:resource :my/feed :scope … :params …}
       {:cause :dashboard-widget :frame :some-frame}
       (fn [] [feed-view])]

  `descriptor` is the resource-instance identity `{:resource :scope :params}`
  (the ensure payload's read keys, Spec 016 §Events). `opts`:
    :cause  — recorded on the ensure (observability; free-form data value).
              Defaults to `[:lease :mount]`.
    :frame  — pin the lease to an explicit frame id, bypassing ambient
              frame-provider / dynamic-var resolution.

  Frame resolution and timing: the lease frame is
  resolved in `:reagent-render` via `resolve-lease-frame` — explicit `:frame`
  opt, else `frame/require-current-frame!` (dynamic-var FIRST, then the
  React-context tier). Resolving at RENDER time keeps the dynamic-var tier
  able to win and mirrors the twin.

  Re-lease on change: a `:component-did-update` diffs the render-time
  `[frame descriptor cause]` against the held lease and, on any change,
  RELEASES the old lease then ENSURES the new target (same token) — so a
  descriptor whose `:params` change across re-renders releases the old
  resource and ensures the new one WITHOUT waiting for unmount; a value-equal
  descriptor holds ONE lease with no churn.

  Idempotency: the lease owner is minted ONCE per instance and reused across
  re-leases, so a hot-reload re-mount settles to exactly one held lease.
  Under SSR (`render-to-string`) lifecycle methods do not run, so the
  acquire/release is a natural no-op — a client-lifetime concern.

  The shared factory owns lease identity and lifecycle; this Var only supplies
  slim's current-component reader and context wiring."
  (resource-lease/make-resource-lease-component
    {:current-component r/current-component
     ;; The slim create-class cap (IMPL-SPEC §6.1) excludes
     ;; `:context-type`, so React contextType is wired directly on the returned
     ;; class AFTER `create-class` — mirroring `reagent2.impl.template/
     ;; fn-to-class`'s `:contextType` threading and the reg-view
     ;; `{:contextType frame-context}` wiring, so the in-flight component's
     ;; `.-context` carries the enclosing frame-provider's frame for the shared
     ;; factory's React-context tier. Set post-hoc (never a `:context-type` map
     ;; key crossing into stock Reagent) keeps slim bundle isolation intact.
     :build-class (fn [class-map]
                    (let [klass (r/create-class class-map)]
                      (set! (.-contextType ^js klass) adapter-context/frame-context)
                      klass))}))

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
