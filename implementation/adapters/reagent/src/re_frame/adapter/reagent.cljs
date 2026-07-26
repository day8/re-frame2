(ns re-frame.adapter.reagent
  "Default browser adapter, implementing the Spec 006 substrate contract
  with stock Reagent."
  (:require [reagent.core :as r]
            [reagent.ratom :as ratom]
            [reagent.dom.client :as rdc]
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
     ;; rf2-8cnxg — the missing `deref-capture`. A stock `Reaction` learns
     ;; its sources ONLY by being run through `deref-capture`; `ratom/run`
     ;; (its `IRunnable` op) is exactly that run, and after it the reaction
     ;; is on Reagent's ordinary batched push path (`_handle-change` →
     ;; enqueue → `ratom/flush!` → notify). A plain `deref` outside
     ;; `*ratom-context*` deliberately does NOT do this, which is why an
     ;; `add-watch`-only observer — the observation port over a compiled
     ;; ViewCell — never heard from a Reagent-hosted subscription.
     ;;
     ;; Guarded twice, and both guards are load-bearing. `IRunnable` skips
     ;; anything that is not a Reagent `Reaction` (a base `r/atom`, or a
     ;; spine-produced derived value inherited through a cross-substrate
     ;; test bundle — rf2-jicu2). A non-nil `watching` means the reaction is
     ;; ALREADY capturing, so re-running it would recompute a live node for
     ;; nothing; skipping keeps activation idempotent across the second and
     ;; subsequent ViewCells that acquire the same cached node.
     :activate-reaction! (fn [rx]
                           (when (and (satisfies? ratom/IRunnable rx)
                                      (nil? (.-watching rx)))
                             (ratom/run rx))
                           nil)
     :disposable?       (fn [a] (satisfies? ratom/IDisposable a))
     :add-on-dispose!   ratom/add-on-dispose!
     :dispose!          ratom/dispose!
     :reactive?         ratom/reactive?
     :after-render      r/after-render}))
