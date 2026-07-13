(ns re-frame.ui.viewcell
  "React glue for the S2b reactive core — the thin `useRef` /
  `useSyncExternalStore` / `useLayoutEffect` layer that drives
  `re-frame.ui.reactive`'s host-agnostic ViewCell + commit reconciler.
  The compiled CLJS emitter wraps a sub-bearing PRODUCTION view's render body
  in `render`; sub-free production views are never wrapped. In DEV the stable
  Fast Refresh Inner always calls `render`, providing the fixed hook skeleton
  before a view gains its first sub site without charging production for it.

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
            [re-frame.ui.reactive :as reactive]))

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

(defn render
  "Wrap a compiled view's render `thunk` (a zero-arg fn returning the host
  element) in its ViewCell. Establishes the cell (stable per React
  instance), subscribes the component to the cell's revision, runs the body
  under a fresh render capture, reads its root incarnation from context, and
  wires the reconcile + lifecycle layout commits. Returns the host element."
  [view-id thunk]
  (let [body-revision (if ^boolean js/goog.DEBUG
                        (reactive/view-generation view-id)
                        0)
        cell-ref (react/useRef nil)]
    (when (nil? (.-current cell-ref))
      (set! (.-current cell-ref)
            (reactive/make-cell view-id body-revision)))
    (let [cell             (.-current cell-ref)
          root-incarnation (react/useContext root-incarnation-context)
          subscribe (react/useCallback
                      (fn [listener] (reactive/subscribe cell listener))
                      #js [cell])]
      ;; Same-signature Fast Refresh keeps this Fiber/ViewCell but advances the
      ;; body revision. Sync BEFORE capture so the selected render records the
      ;; exact descriptor revision it executed. Production folds this branch
      ;; away with the entire HMR slot.
      (when ^boolean js/goog.DEBUG
        (reactive/advance-generation! cell body-revision))
      ;; ONE useSyncExternalStore — the cell's scalar revision drives
      ;; sub-invalidation repaints (getServerSnapshot = 0: SSR reads are
      ;; one-shot, no reactive loop).
      (react/useSyncExternalStore subscribe
                                  (fn [] (reactive/get-snapshot cell))
                                  (fn [] 0))
      ;; render-phase: resolve + probe every executed site into a fresh
      ;; ownership-free capture. The selected Fiber's effect closes over this
      ;; exact value; speculative sibling renders cannot replace it.
      (let [[element capture] (reactive/with-capture cell thunk)]
        ;; reconcile — after EVERY committed render; no cleanup (leases are
        ;; owned by the cell and reconciled by the kept-check, never torn
        ;; down between renders — that would churn shared nodes).
        (react/useLayoutEffect
          (fn reconcile []
            (reactive/commit! cell capture)
            js/undefined))
        ;; lifecycle — connect implicitly via the reconcile above; the cleanup
        ;; releases owners on React unmount AND Activity hide (indistinguishable
        ;; here — 03 §4). Reveal re-mounts this effect and the reconcile
        ;; reacquires + corrects before paint. In DEV, React StrictMode
        ;; double-invokes this effect (mount→cleanup→remount in one commit), so
        ;; every sub node churns through a zero-owner dispose/rebuild — an
        ;; inherent dev cost, balanced by the idempotent reconcile; the
        ;; same-commit reconnect is NOT a hide and `reactive/connect!` does not
        ;; log an Activity-hide proof for it (rf2-vxgfnd.44). On mount the cell ENROLS under its
        ;; root incarnation (`attach-root!`, rf2-vxgfnd.85/.92) — an ownership that
        ;; SURVIVES the hide-cleanup, so root teardown reaps a cell hidden before
        ;; its unmount window. Keyed on the incarnation identity, so a re-mount
        ;; under a fresh root re-enrols. `attach-root!` is idempotent; the cleanup
        ;; NEVER detaches the root (that is `teardown!`'s job — the hide must stay
        ;; reapable-by-its-root).
        (react/useLayoutEffect
          (fn lifecycle []
            (when (some? root-incarnation)
              (reactive/attach-root! cell root-incarnation))
            (fn cleanup [] (reactive/disconnect! cell)))
          #js [root-incarnation])
        element))))
