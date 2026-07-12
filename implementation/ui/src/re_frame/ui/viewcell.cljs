(ns re-frame.ui.viewcell
  "React glue for the S2b reactive core — the thin `useRef` /
  `useSyncExternalStore` / `useLayoutEffect` layer that drives
  `re-frame.ui.reactive`'s host-agnostic ViewCell + commit reconciler.
  The compiled CLJS emitter wraps a sub-bearing view's render body in
  `render`; sub-free views are never wrapped (no per-view hook overhead,
  no behaviour change — the dev fixed-hook-skeleton of I-15 is an HMR
  refinement that lands with S2e).

  The contract, per 03 §3:

    - ONE `useSyncExternalStore` per view (all sites share the cell's
      scalar revision snapshot). A revision advance re-renders the
      memoized component even when props are `rf=` (the sub-driven repaint
      channel, distinct from the prop-driven one memo gates).
    - render RESOLVES + PROBES without ownership (via `reactive/with-capture`
      → `sub` → the observation port's `resolve-target`/`probe`); the
      LAYOUT commit acquires the CAPTURED targets. Abandoned renders
      (StrictMode double-render, time-sliced tear-off) retain nothing.
    - the reconcile layout effect runs after EVERY committed render and is
      idempotent (kept-check retains unchanged leases untouched); the
      lifecycle layout effect (empty deps) owns connect/disconnect, so
      React unmount / Activity hide releases owners and reveal reacquires
      and corrects before paint.

  ## Epoch close → React (S2d)

  Sub deltas do NOT re-render synchronously. A moving site's `on-change`
  (registered at commit) marks the cell dirty in `reactive`'s epoch-scoped
  registry (constant-work, coalesced once per cell per epoch); one
  microtask-aligned flush advances the cell's revision, `getSnapshot`
  moves, and this component re-renders. React BATCHING is inherited, not
  hand-rolled: `useSyncExternalStore` routes every revision advance
  through React's own scheduler, so N cells flushed in one drain settle in
  ONE render pass, and adding an explicit batch wrapper here would only
  fight React's ownership of the schedule. The synchronous forcing door is
  `reactive/flush-frame!` / `ui.test/flush!` (the Q51 scope ruling —
  03 §3); the commit reconciler's own step-8 advance already runs inside
  this layout effect (React's commit phase), correcting moved evidence
  before paint without any flush call."
  (:require ["react" :as react]
            [re-frame.ui.reactive :as reactive]))

(defn render
  "Wrap a compiled view's render `thunk` (a zero-arg fn returning the host
  element) in its ViewCell. Establishes the cell (stable per React
  instance), subscribes the component to the cell's revision, runs the body
  under a fresh render capture, and wires the reconcile + lifecycle layout
  commits. Returns the host element."
  [view-id thunk]
  (let [cell-ref (react/useRef nil)]
    (when (nil? (.-current cell-ref))
      (set! (.-current cell-ref) (reactive/make-cell view-id)))
    (let [cell      (.-current cell-ref)
          subscribe (react/useCallback
                      (fn [listener] (reactive/subscribe cell listener))
                      #js [cell])]
      ;; ONE useSyncExternalStore — the cell's scalar revision drives
      ;; sub-invalidation repaints (getServerSnapshot = 0: SSR reads are
      ;; one-shot, no reactive loop).
      (react/useSyncExternalStore subscribe
                                  (fn [] (reactive/get-snapshot cell))
                                  (fn [] 0))
      ;; render-phase: resolve + probe every executed site into a fresh
      ;; ownership-free capture, stashed on the cell for the layout commit.
      (let [element (reactive/with-capture cell thunk)]
        ;; reconcile — after EVERY committed render; no cleanup (leases are
        ;; owned by the cell and reconciled by the kept-check, never torn
        ;; down between renders — that would churn shared nodes).
        (react/useLayoutEffect
          (fn reconcile []
            (reactive/commit! cell)
            js/undefined))
        ;; lifecycle — empty deps: connect implicitly via the reconcile
        ;; above; the cleanup releases owners on React unmount AND Activity
        ;; hide (indistinguishable here — 03 §4). Reveal re-mounts this
        ;; effect and the reconcile reacquires + corrects before paint.
        (react/useLayoutEffect
          (fn lifecycle []
            (fn cleanup [] (reactive/disconnect! cell)))
          #js [])
        element))))
