(ns re-frame.ui.viewcell
  "React glue for the S2b reactive core — the thin `useRef` /
  `useSyncExternalStore` / `useLayoutEffect` layer that drives
  `re-frame.ui.reactive`'s host-agnostic ViewCell + commit reconciler.
  The compiled CLJS emitter selects one exact PRODUCTION wrapper:
  `render-subs`, `render-leases`, or `render-subs-and-leases`; a view with
  neither capability is not wrapped. In DEV the stable Fast Refresh Inner
  always calls `render-dev`, providing the fixed superset hook skeleton before
  a view gains or loses either capability without charging production for it.

  The contract, per 03 §3:

    - ONE `useSyncExternalStore` per view (all sites share the cell's
      scalar revision snapshot). A revision advance re-renders the
      memoized component even when props are `rf=` (the sub-driven repaint
      channel, distinct from the prop-driven one memo gates).
    - render RESOLVES + PROBES without ownership (via `reactive/with-capture`
      → `sub` → the observation port's `resolve-target`/`probe`); the
      LAYOUT commit acquires the CAPTURED targets. Each render's effect closes
      over that render's exact immutable capture. Abandoned renders (StrictMode
      double-render, time-sliced tear-off) acquire no ownership and publish no
      speculative capture state to the ViewCell.
    - the reconcile layout effect runs after EVERY committed render and is
      idempotent (kept-check retains unchanged leases untouched); the
      lifecycle layout effect (empty deps) owns connect/disconnect, so
      React unmount / Activity hide releases owners and reveal reacquires
      and corrects before paint.

  ## Drain quiescence → React (S2d)

  Sub deltas do NOT re-render synchronously. A moving site's `on-change`
  (registered at commit) marks the cell dirty in `reactive`'s dirty
  registry (constant-work, coalesced once per cell per DRAIN — every epoch
  a run-to-completion drain commits folds into one render batch, 03 §3);
  one flush on the host MICROTASK queue (`reactive/schedule-flush!` →
  `queue-microtask!`, a true microtask that runs before the next paint —
  rf2-vxgfnd.40, NOT `goog.async.nextTick`) advances the cell's revision,
  `getSnapshot` moves, and this component re-renders — so a watch-fired
  movement is corrected before a torn frame can show. React BATCHING is
  inherited, not
  hand-rolled: `useSyncExternalStore` routes every revision advance
  through React's own scheduler, so N cells flushed in one drain settle in
  ONE render pass, and adding an explicit batch wrapper here would only
  fight React's ownership of the schedule. The synchronous forcing door is
  `reactive/flush-frame!` / `ui.test/flush!` (the Q51 scope ruling —
  03 §3); the commit reconciler's own step-8 advance already runs inside
  this layout effect (React's commit phase), correcting moved evidence
  before paint without any flush call."
  (:require ["react" :as react]
            [re-frame.interop :as interop]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.sub-overrides :as sub-overrides]))

;; ---------------------------------------------------------------------------
;; Root-incarnation context (03 §4; rf2-vxgfnd.85/.92)
;;
;; The ROOT scoping for per-cell root-incarnation ownership. `client/mount!`
;; mints ONE incarnation per Root (`reactive/make-root-incarnation`) and wraps
;; the root element in this context's Provider, so EVERY ViewCell under a root —
;; across the whole tree, however deeply nested — reads the SAME incarnation via
;; `useContext` and enrols under it (`attach-root!`). That true root scope is why
;; an entirely-hidden sibling subtree is still reaped at root unmount: the
;; ownership follows the ROOT, not the topmost still-mounted subtree. nil above a
;; root that supplies no provider (bare/test mounts) — `attach-root!` is skipped.
;; ---------------------------------------------------------------------------

(defonce root-incarnation-context
  (react/createContext nil))

(defn provide-root-incarnation
  "Wrap `element` in the root-incarnation context Provider carrying
  `incarnation` — the seam `client/mount!` (and `render!`) render through so
  every ViewCell in the root's tree shares ONE incarnation (rf2-vxgfnd.92).
  A nil `incarnation` still provides (a root with no reactive cells is inert)."
  [incarnation element]
  (react/createElement (.-Provider root-incarnation-context)
                       #js {:value incarnation}
                       element))

(defn- use-cell
  "Create/read this component instance's ViewCell and root-incarnation.

  This is the common two-hook prefix shared by every wrapped production shape
  and the stable dev shell.  Keeping the capability-specific hooks out of this
  helper makes their absence structural in advanced output."
  [view-id]
  (let [body-revision (if ^boolean js/goog.DEBUG
                        (reactive/view-generation view-id)
                        0)
        cell-ref (react/useRef nil)]
    (when (nil? (.-current cell-ref))
      (set! (.-current cell-ref)
            (reactive/make-cell view-id body-revision)))
    (let [cell             (.-current cell-ref)
          root-incarnation (react/useContext root-incarnation-context)]
      ;; Same-signature Fast Refresh keeps this Fiber/ViewCell but advances the
      ;; body revision. Sync BEFORE capture so the selected render records the
      ;; exact descriptor revision it executed. Production folds this branch
      ;; away with the entire HMR slot.
      (when ^boolean js/goog.DEBUG
        (reactive/advance-generation! cell body-revision))
      [cell root-incarnation])))

(defn- use-sub-revision!
  "Install the one aggregated subscription hook and return Story overrides."
  [cell]
  (let [overrides (sub-overrides/use-current)
        subscribe (react/useCallback
                    (fn [listener] (reactive/subscribe cell listener))
                    #js [cell])]
    (react/useSyncExternalStore subscribe
                                (fn [] (reactive/get-snapshot cell))
                                (fn [] 0))
    overrides))

(defn- capture-plain
  [cell thunk]
  (reactive/with-capture cell thunk))

(defn- capture-with-overrides
  "Capture a sub-bearing body under Story's dev-only mounted override door."
  [cell overrides thunk]
  (if interop/debug-enabled?
    (do
      ;; Stable bundle sentinel: the advanced production gate asserts this
      ;; whole debug branch is absent.
      (when (and (some? overrides) (not (map? overrides)))
        (throw
         (js/Error. "rf-ui-mounted-override-binding expects a map")))
      (binding [reactive/*sub-overrides* overrides]
        (reactive/with-capture cell thunk)))
    (reactive/with-capture cell thunk)))

(defn- use-commit-and-lifecycle!
  "Publish the selected render capture and own React visibility lifecycle."
  [cell root-incarnation capture]
  ;; Reconcile after every committed render. There is deliberately no cleanup:
  ;; retained observation/resource owners live on the cell, not on a render.
  (react/useLayoutEffect
    (fn reconcile []
      (reactive/commit! cell capture)
      js/undefined))
  ;; Connect via commit above; cleanup releases ownership on both unmount and
  ;; Activity hide. Root enrolment deliberately survives hide so root teardown
  ;; can reap an entirely-hidden subtree.
  (react/useLayoutEffect
    (fn lifecycle []
      (when (some? root-incarnation)
        (reactive/attach-root! cell root-incarnation))
      (fn cleanup [] (reactive/disconnect! cell)))
    #js [root-incarnation]))

(defn- use-resource-reconcile!
  "Reconcile resource ownership after the layout commit accepted `capture`.

  This is a passive effect: render and layout publish only an ownership-free
  desired plan; queued ensure/release events are write-side work for the next
  drain.  A stale/abandoned capture is rejected by `reactive`."
  [cell capture]
  (react/useEffect
    (fn reconcile-resources []
      (reactive/reconcile-resource-leases! cell capture)
      js/undefined)))

(defn render-subs
  "Production wrapper for a view with sub sites and no resource leases."
  [view-id thunk]
  (let [[cell root-incarnation] (use-cell view-id)
        overrides              (use-sub-revision! cell)
        [element capture]      (capture-with-overrides cell overrides thunk)]
    (use-commit-and-lifecycle! cell root-incarnation capture)
    element))

(defn render-leases
  "Production wrapper for a view with resource leases and no sub sites.

  Structurally contains zero `useSyncExternalStore` calls."
  [view-id thunk]
  (let [[cell root-incarnation] (use-cell view-id)
        [element capture]      (capture-plain cell thunk)]
    (use-commit-and-lifecycle! cell root-incarnation capture)
    (use-resource-reconcile! cell capture)
    element))

(defn render-subs-and-leases
  "Production wrapper for a view containing both sub and resource sites."
  [view-id thunk]
  (let [[cell root-incarnation] (use-cell view-id)
        overrides              (use-sub-revision! cell)
        [element capture]      (capture-with-overrides cell overrides thunk)]
    (use-commit-and-lifecycle! cell root-incarnation capture)
    (use-resource-reconcile! cell capture)
    element))

(defn render-dev
  "Stable Fast Refresh wrapper: the full hook superset regardless of the
  currently-compiled body capabilities."
  [view-id thunk]
  (let [[cell root-incarnation] (use-cell view-id)
        overrides              (use-sub-revision! cell)
        [element capture]      (capture-with-overrides cell overrides thunk)]
    (use-commit-and-lifecycle! cell root-incarnation capture)
    (use-resource-reconcile! cell capture)
    element))

(def render
  "Internal compatibility alias for pre-S2b direct-render fixtures. New
  compiled output names its exact capability wrapper."
  render-subs)
