(ns re-frame.ui.substrate
  "The compiled-view substrate's first-party React adapter machinery.

  This namespace has three jobs and no view API of its own:

  1. build the watchable ten-function substrate adapter installed through
     `rf/init!`; and
  2. publish the React-context frame reader for both that adapter and the
     plain-atom CLJS path used by headless/native-DOM conformance fixtures;
     and
  3. compose adapter disposal with every public compiled client Root, whose
     registry is intentionally separate from the generic spine registry.

  The watchable half is the production observation path that stands on its
  own, independent of the wrapper adapters. It uses the core's React spine directly
  — plain atom state, one coherent-write scheduler, `IDeref` + `IWatchable`
  derived values, and no dependency on either wrapper library. Compiled views
  do not use the spine's legacy subscription hook: their sole read owner is
  `re-frame.ui.reactive` (probe/acquire/read/release) and one ViewCell uSES
  subscription per view.

  ## Why the plain-atom route remains

  `re-frame.ui.frames/resolve-frame` reads the shared React frame context
  (`re-frame.adapter.context/frame-context`) DIRECTLY, so frame-scoped
  operations that route through it already see the `frame-provider` /
  `frame-root` scope. But the compiled view SUB-READ path does not go
  through that primitive: `re-frame.ui.reactive/sub-read` →
  `re-frame.substrate.observation/resolve-target` →
  `re-frame.frame/require-current-frame!` → `resolve-current-frame`, which
  consults the `:adapter/current-frame` late-bind hook for the React-context
  tier (Spec 006 §Frame-provider via React context). In a PURE compiled-ui
  app — one running on the plain-atom reactive adapter — that hook otherwise
  has no publisher, so a mounted `(sub …)` under a provider resolved the
  dynamic tier only and
  raised `:rf.error/no-frame-context`.

  ## The context publish

  This ns publishes the shared function-component React-context reader —
  `re-frame.adapter.context/function-component-current-frame` — through the
  SAME `:adapter/current-frame` hook, so core's `resolve-current-frame`
  reaches the context tier on the compiled-view sub-read path. There is ONE
  resolution order (frames.cljc §The ambient frame chain); this reuses the
  shared reader rather than duplicating it.

  Homed in the ui artefact (not core's `plain-atom`) because the reader is a
  React-context read — the React dependency belongs with the substrate that
  renders React, keeping plain-atom's headless CLJS bundle React-free.

  ## Routing, not clobbering

  The plain-atom route and the first-party React route both use
  `substrate-adapter/route-hook!`, so the installed adapter selects exactly
  one reader and load order cannot clobber a sibling route."
  (:require ["react" :as react]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.substrate.spine :as spine]
            [re-frame.ui.reactive :as reactive]
            [re-frame.views.frame-boundary :as boundary]))

;; The React-context-tier frame-id reader for the pure compiled-view runtime.
;; Routed against plain-atom so `resolve-current-frame` sees the context tier
;; when plain-atom is installed (the pure-ui case); the classic-adapter chain
;; is untouched. Fallback `#(frame/current-frame)` mirrors the UIx
;; routing — the dynamic-var tier when neither this nor a chained handler runs.
(substrate-adapter/route-hook! plain-atom/adapter :adapter/current-frame
  adapter-context/function-component-current-frame
  #(frame/current-frame))

;; One substrate-owned spine. `make-react-spine` supplies the mature adapter
;; contract implementation without importing a view-wrapper library. The
;; generated legacy `use-subscribe` surface is deliberately not exported;
;; compiled views own reads through ViewCell + the observation port. The
;; adapter assembler consumes the container/render/disposal entries only, and
;; advanced compilation can remove unused hook machinery.
(defonce ^:private spine-fns
  (spine/make-react-spine
   {:substrate-name        "re-frame.ui"
    :gensym-prefix-sub     "rf-ui-sub-"
    :gensym-prefix-derived "rf-ui-derived-"
    :gensym-prefix-use-sub "rf-ui-use-sub-"
    :use-memo              react/useMemo
    :use-callback          react/useCallback
    :use-context           react/useContext}))

(defn- frame-provider-component
  "The adapter-contract provider component. `ui/frame-provider` normally
  compiles straight to the shared Context Provider; this component serves the
  lower-level `:register-context-provider` slot without UIx marshalling."
  [props]
  (let [frame-id (frame/require-frame-provider-target!
                  (.-frame props)
                  're-frame.ui/frame-provider)]
    (boundary/require-live-frame-for-scope!
     frame-id 're-frame.ui/frame-provider)
    (apply adapter-context/provider-element
           frame-id
           (adapter-context/normalize-children (.-children props)))))

(defonce ^:private spine-adapter
  (spine/make-react-adapter
   spine-fns
   {:kind :rf.adapter/ui :frame-provider frame-provider-component}))

;; PRESENCE, not JS truthiness, decides whether the public-root drain and the
;; generic spine dispose failed (rf2-s2cfv). Either can throw a legitimately falsy
;; value (false/nil); each slot holds this identity sentinel until a throw records
;; into it, so a falsy failure is preserved — thrown in primary order, never lost
;; to a truthy `cond`. Compared only by `identical?`.
(defonce ^:private no-dispose-error #js {})

(defn- attach-secondary-cleanup!
  "Preserve `primary` as the thrown value; when a secondary cleanup failure is
  PRESENT (not merely truthy), retain it on the primary as
  `rfUiAdapterCleanupError` for diagnostics. Presence — not truthiness — gates the
  attach, so a falsy secondary is real. A primitive primary (nil/false/number)
  cannot carry a property, so the secondary rides the always-on console diagnostic
  instead and stays observable. `primary` is returned UNCHANGED either way — never
  coerced or replaced (rf2-s2cfv)."
  [primary secondary-present? secondary]
  (when secondary-present?
    (let [attached?
          (try
            (js/Object.defineProperty
             primary "rfUiAdapterCleanupError"
             #js {:value secondary :configurable true})
            true
            (catch :default _ false))]
      (when (and (not attached?) (exists? js/console))
        (.warn js/console
               (str "[re-frame.ui] an adapter spine cleanup failure could not "
                    "ride the primary rejection (a primitive reason cannot carry "
                    "a diagnostic property); the primary is rethrown unchanged. "
                    "Secondary cleanup error:")
               secondary))))
  primary)

(defn ^:no-doc dispose-outcome
  "The disposal outcome policy, decided by failure PRESENCE (never JS truthiness):
  a public-root drain failure is PRIMARY (a present spine failure rides it as a
  diagnostic), else a spine-only failure is thrown, else disposal is clean.
  `root-present?`/`spine-present?` are decided by presence at the call site, so a
  legitimately falsy drain/spine failure (false/nil) is preserved and correctly
  ordered. Returns `[:throw reason]` or `[:ok]`. Extracted as the ONE place the
  presence decision lives, so it is unit-checkable off the DOM (rf2-s2cfv)."
  [root-present? root-error spine-present? spine-error]
  (cond
    root-present?
    [:throw (attach-secondary-cleanup! root-error spine-present? spine-error)]

    spine-present? [:throw spine-error]
    :else          [:ok]))

(defn- dispose-adapter!
  "Dispose public compiled roots first, then the generic React spine. Both
  halves are attempted. A public-root teardown failure stays primary while a
  spine failure is retained on `rfUiAdapterCleanupError`. Public Root admission
  stays closed around the complete two-phase lifecycle, not merely the first
  snapshot drain."
  [with-root-admission-closed! drain-live-roots!]
  (with-root-admission-closed!
   (fn []
     (let [root-error  (volatile! no-dispose-error)
           spine-error (volatile! no-dispose-error)]
       (try
         (drain-live-roots!)
         (catch :default e
           (vreset! root-error e)))
       (try
         ((:dispose-adapter! spine-adapter))
         (catch :default e
           (vreset! spine-error e)))
       ;; PRESENCE-decided (rf2-s2cfv): a falsy drain/spine failure (false/nil) is
       ;; preserved, thrown in primary order, and never lost to a truthy `cond`.
       (let [[outcome reason]
             (dispose-outcome
              (not (identical? @root-error no-dispose-error))  @root-error
              (not (identical? @spine-error no-dispose-error)) @spine-error)]
         (when (= :throw outcome) (throw reason)))))))

(defn- ui-flush-render!
  "The first-party adapter's `:flush-render!` — the ViewCell-settling override of
  the generic spine verb (rf2-vxgfnd.207).

  The inherited spine `flush-render!` only runs `react-dom/flushSync`, which
  commits React work SCHEDULED inside its callback. But a compiled ViewCell
  invalidation does NOT notify React synchronously: `reactive/enrol-dirty!` marks
  the cell and arms a LATER microtask. So a caller that flushes a thunk which
  dispatches — changing a subscribed value — would see `flushSync` return while
  the ViewCell is still pending and the DOM still stale (the .207 defect).

  The fix runs the supplied thunk AND the pending ViewCell registry publication
  (`reactive/flush-pending!`) INSIDE the spine's synchronous commit boundary, so
  the dirty cells' revision bumps + `useSyncExternalStore` notifications fire and
  React commits them before the call returns. It then continues to a bounded
  fixed point: a legitimate commit-triggered re-dirty (a layout effect that
  dispatches) is drained by re-flushing until the registry is quiescent.

  Write-all-queued-events THEN one read/render is preserved: the thunk's
  `dispatch-sync!` runs its whole run-to-completion drain (every queued event,
  each committing its own epoch) BEFORE `flush-pending!` performs the single
  coalesced read/render — this is NOT a per-epoch renderer. Any redundant
  microtask armed during the drain later finds the registry already drained and
  renders nothing.

  Runaway convergence IS bounded here (rf2-0faipl): the re-dirty drain runs
  through the SHARED `reactive/converge-flush!` law with a finite pass budget.
  Ordinary write-driven re-dirty settles in a handful of passes, and the ambient
  guards (the router's `:rf.error/drain-depth-exceeded` for a dispatch cascade,
  React's maximum-update-depth for an update-in-effect loop) still break a
  runaway that surfaces WITHIN one `flushSync` pass. But a re-enrolment that
  lands ACROSS separate `flushSync` passes — which those single-pass guards
  cannot see — would once have spun this synchronous adapter call forever; when
  the registry cannot quiesce within `reactive/flush-convergence-budget` passes,
  the driver now fails loud with the typed
  `:rf.error/flush-convergence-exceeded` diagnostic (pass budget + residual
  pending count) instead. `ui.test/flush!`'s loop-to-quiescence rests on the
  SAME shared bound.

  An open router drain fails loud through the SHARED guard rather than publishing
  a partial render phase. Exceptions from the thunk propagate unchanged (the pending
  publication does not run when the thunk throws). The 0-arity flushes
  already-pending work with an empty thunk; a no-op flush stays a no-op. This
  override is installed ONLY on the first-party adapter — the generic spine
  contract for stacks that do not use ViewCells is untouched."
  ([] (ui-flush-render! (fn [] nil)))
  ([f]
   (reactive/guard-open-drain! 're-frame.ui.substrate/flush-render!)
   (let [spine-flush (:flush-render! spine-adapter)]
     ;; One React sync-commit boundary: run the update and commit phases, then publish the
     ;; pending ViewCell notifications so React commits them before returning.
     (spine-flush (fn [] (f) (reactive/flush-pending!)))
     ;; Drain any commit-triggered re-dirty to a BOUNDED fixed point — each pass
     ;; is its own sync-commit boundary. The bound is the shared
     ;; `converge-flush!` law (rf2-0faipl): a non-quiescent cascade that
     ;; re-dirties every pass fails loud with
     ;; `:rf.error/flush-convergence-exceeded` rather than spinning this
     ;; synchronous adapter call forever.
     (reactive/converge-flush! 're-frame.ui.substrate/flush-render!
                               (fn [] (spine-flush reactive/flush-pending!))))
   nil))

(defn adapter-with-client-roots
  "Build the retained first-party adapter around the client kernel's
  all-public-roots admission fence + exact-snapshot drain. The injections keep
  this namespace below the client/frames layer and avoid a substrate → client →
  frames → substrate dependency cycle.

  Also overrides the generic spine `:flush-render!` with the ViewCell-settling
  `ui-flush-render!` (rf2-vxgfnd.207), so a synchronous `flush-render!` settles
  every pending compiled ViewCell — and its React commit — before returning."
  [with-root-admission-closed! drain-live-roots!]
  (assoc spine-adapter
         :dispose-adapter!
         #(dispose-adapter! with-root-admission-closed! drain-live-roots!)
         :flush-render! ui-flush-render!))
