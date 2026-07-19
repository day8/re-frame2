(ns re-frame.adapter.reagent
  "Default browser adapter, implementing the Spec 006 substrate contract
  with stock Reagent."
  (:require [reagent.core :as r]
            [reagent.ratom :as ratom]
            [reagent.dom.client :as rdc]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.adapter.resource-lease :as resource-lease]
            [re-frame.substrate.spine :as spine]
            [re-frame.views :as views]))

;; ---- shared ratom-spine wiring --------------------------------------------
;;
;; Reagent and reagent-slim share the ratom spine but inject different
;; reactive implementations. The spine must not require either ratom namespace:
;; that dependency direction keeps stock Reagent out of slim bundles.

(def ^:private spine-fns
  (spine/make-ratom-spine
    {;; Keep generated watch ids attributable in mixed-adapter test bundles.
     :gensym-prefix-sub "rf-reagent-sub-"
     ;; Each op is a thin call-through lambda rather than the bare Var
     ;; value so the spine resolves the namespaced fn at CALL time. This
     ;; keeps the `with-redefs [rdc/create-root …]` test-observability the
     ;; adapter-render / dispose-drain pins rely on (capturing the bare
     ;; Var value at load time would freeze the original impls past any
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
     ;; Drain Reagent synchronously after `f`; unlike its normal next-tick path,
     ;; this works in backgrounded and headless tabs.
     :flush-render! (fn [f] (f) (r/flush))}))

(def set-hiccup-emitter!
  "Install the hiccup → HTML fn used by render-to-string. Last call wins.
  Published through a late-bound hook so `re-frame.ssr` can install it
  without a static adapter-to-SSR dependency."
  (:set-hiccup-emitter! spine-fns))

(def flush-views!
  "Flush pending Reagent renders synchronously. Wraps React's act() —
  intended for test code only. Calls (act (fn [] (reagent.core/flush)));
  with `f`, runs `f` then the synchronous render drain inside act. Returns
  nil. When act() is unreachable in the current React build it degrades to
  a plain synchronous flush (still runs `f` and drains the render queue),
  so a `:node-test` runner with no real React render path still flushes."
  (:flush-views! spine-fns))

;; ---- resource-lease mount-lifecycle helper -------------------------------
;;
;; Lease identity and ordering are shared across adapter families so mixed
;; trees cannot mint colliding owners. This namespace supplies only stock
;; Reagent's component and context wiring.

(def with-resource-lease
  "Reagent component that takes a resource liveness lease for its mounted
  lifetime. It is the Form-3 counterpart of the UIx and Helix
  `use-resource-lease` hook. On mount it
  dispatches `:rf.resource/ensure` with an app-minted `[:lease …]` owner; on
  unmount it releases that lease via `:rf.resource/release-owner`.

  Call it as a Reagent component with the resource descriptor and a body
  thunk (a 0-arg fn returning hiccup — the children rendered while the lease
  is held):

      [reagent/with-resource-lease
       {:resource :my/feed :scope :rf.scope/global :params {:page 0}}
       (fn [] [feed-view])]

  Or with opts (a map between the descriptor and the body thunk):

      [reagent/with-resource-lease
       {:resource :my/feed :scope … :params …}
       {:cause :dashboard-widget :frame :some-frame}
       (fn [] [feed-view])]

  `descriptor` is the resource-instance identity `{:resource :scope
  :params}` (the ensure payload's read keys, Spec 016 §Events). `opts`:
    :cause  — recorded on the ensure (observability; free-form data value).
              Defaults to `[:lease :mount]`.
    :frame  — pin the lease to an explicit frame id, bypassing ambient
              resolution — which, when `:frame` is omitted, reads the
              dynamic `with-frame` binding FIRST, then the surrounding
              `frame-provider` (SCOPE) / `frame-root` (ENSURE) React
              context, else raises `:rf.error/no-frame-context`.

  The component is one stable Form-3 class, not a Form-2 that creates a new
  class on every render. It resolves the target frame during render, then uses
  that captured target in commit-phase callbacks. On `[frame descriptor
  cause]` changes it releases the old target before ensuring the new one while
  retaining the same owner token. Value-equal inputs do not churn the lease.
  SSR runs no lifecycle methods, so ownership is client-only."
  (resource-lease/make-resource-lease-component
    {:current-component r/current-component
     ;; Reagent promotes `:context-type` to React's static `contextType`.
     :build-class (fn [class-map]
                    (r/create-class
                      (assoc class-map :context-type adapter-context/frame-context)))}))

(def adapter
  "The Reagent adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.adapter.reagent :as reagent])
      (rf/init! reagent/adapter)

  Adapter installation is explicit; there is no default-adapter registry.
  `make-ratom-spine` and `make-ratom-adapter` own the logic shared with
  reagent-slim. The Reagent-shaped frame-provider remains injected from
  `re-frame.views`, keeping the spine independent of that component layer."
  (spine/make-ratom-adapter
    spine-fns
    {:kind :rf.adapter/reagent
     ;; The returned component receives the frame keyword at render time.
     :register-context-provider (fn [_frame-keyword] (views/build-frame-provider))
     ;; The spine handles re-frame-owned disposal before these substrate ops.
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
