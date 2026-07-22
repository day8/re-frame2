(ns re-frame.freehand.shell
  "The React glue that drives the atomic shell — the thin `useRef` /
  `useContext` / `useSyncExternalStore` / `useLayoutEffect` layer over
  [[re-frame.freehand.cell]]'s host-agnostic ViewCell and commit
  reconciler.

  One shape, deliberately. The interpreted mode ALWAYS observes frame
  context: a provider retarget from frame A to frame B must rebind a
  child even when its props are equal, and a view body is unrestricted
  Clojure whose reads no analysis has enumerated. Eliding the machinery
  requires PROOF that neither the body nor its events are frame-sensitive
  — and proof is what a static manifest gives, so elision belongs to the
  compiled tier, never to a guess made here.

  The contract this layer realises:

    - **ONE `useSyncExternalStore` per view.** Every dependency shares
      the cell's scalar revision snapshot, so N moving targets settle in
      ONE render pass and React owns the schedule; a batch wrapper here
      would only fight it.
    - **Render resolves and probes; the LAYOUT COMMIT owns.** The body
      runs against a candidate this render HOLDS. The layout effect
      closes over that exact candidate, and React runs it only for the
      render it SELECTED — so a candidate belonging to an abandoned
      render (StrictMode's double render, a time-sliced tear-off) is
      simply never handed to `commit!`. There is no flag to check and no
      slot to clear.
    - **Two layout effects, and they mean different things.** The
      reconcile effect runs after EVERY committed render and publishes
      that render's bundle; the lifecycle effect owns connect and
      disconnect, so unmount releases every dependency and retires every
      callback.

  Normative owner:
  [`spec/006-ReactiveSubstrate.md`](../../../../../spec/006-ReactiveSubstrate.md)
  §The Freehand atomic shell."
  (:require ["react" :as react]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.frame :as frame]
            [re-frame.freehand.cell :as cell]))

;; A shared, never-mutated empty deps array for mount-only layout effects.
;; React reads deps element-wise, so one instance serves every view and
;; saves an allocation per render.
(def ^:private empty-deps #js [])

(defn frame-context-frame
  "The frame this component is mounted under, or nil when it is mounted
  under no frame boundary at all.

  The read is a real `useContext` — not a peek at the context object's
  private current-value slot. That matters twice: `useContext`
  SUBSCRIBES the component to context changes, which is what makes a
  provider retarget repaint a child whose own props did not move; and it
  is renderer-agnostic, so the value is right under `react-dom/server` as
  well as under the client renderer, where the two renderers populate
  different private slots.

  An ambient `re-frame.frame/*current-frame*` still wins, because a
  caller who bound a frame explicitly meant it."
  []
  (let [ctx-frame (react/useContext adapter-context/frame-context)]
    (or frame/*current-frame*
        (adapter-context/context-value->current-frame ctx-frame))))

(defn provide-frame
  "Wrap `children` in the shared frame-context provider for `frame-id` —
  the element a Freehand root and any explicit frame boundary render
  through, so a `useContext` read below resolves this frame."
  [frame-id & children]
  (apply adapter-context/provider-element frame-id children))

(defn- use-cell
  "Create or read this component instance's ViewCell, keeping it across
  every re-render of this exact Fiber."
  [view-id]
  (let [cell-ref (react/useRef nil)]
    (when (nil? (.-current cell-ref))
      (set! (.-current cell-ref) (cell/cell view-id)))
    (.-current cell-ref)))

(defn- use-revision!
  "Install the one aggregated repaint subscription. A revision advance
  re-renders this component even when its props are unchanged — the
  dependency-driven repaint channel, distinct from the prop-driven one
  React's own reconciliation owns."
  [c]
  (let [subscribe (react/useCallback
                    (fn [listener] (cell/subscribe c listener))
                    #js [c])]
    (react/useSyncExternalStore subscribe
                                (fn [] (cell/snapshot c))
                                (fn [] 0))))

(defn render
  "Run one render of a declared view inside the shell and return the
  element it produced.

  `render-candidate` is called with THIS render's candidate; it runs the
  view body under `cell/with-capture` and walks the result, recording
  reads and event sites on that candidate and on nothing else.

  Hot reload in the interpreted mode goes through boundary IDENTITY: a
  redeclared view is a new descriptor value, so the emitter mints a new
  component, React remounts the boundary, and this shell's lifecycle
  cleanup disconnects the OLD cell — releasing exactly the ownership the
  old body had. A cell therefore never publishes a body it did not
  execute."
  [view-id render-candidate]
  (let [frame-id (frame-context-frame)
        c        (use-cell view-id)
        _        (use-revision! c)
        cand     (cell/candidate c frame-id)
        element  (render-candidate cand)]
    ;; Reconcile after every committed render, with this render's exact
    ;; candidate. There is deliberately no cleanup: ownership lives on
    ;; the cell, never on a render.
    (react/useLayoutEffect
      (fn reconcile []
        (cell/commit! cand)
        js/undefined))
    ;; Connect through the commit above; cleanup releases every
    ;; dependency and retires every callback.
    (react/useLayoutEffect
      (fn lifecycle []
        (fn cleanup [] (cell/disconnect! c)))
      empty-deps)
    element))
