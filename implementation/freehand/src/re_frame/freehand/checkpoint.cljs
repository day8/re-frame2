(ns ^:no-doc re-frame.freehand.checkpoint
  "The ViewCell pending window's REACT-VISIBLE closer (rf2-w2m25).

  ## The hole this fills

  A mark is constant work: [[re-frame.freehand.cell/mark-dirty!]] records
  the cause, enrols the cell in the open pending window and arms a
  microtask. Nothing React can see happens — no component re-renders, no
  setState lands, no root is scheduled. So `react-dom/flushSync`, which
  returns as soon as the React work SCHEDULED INSIDE its callback has
  committed, had nothing to commit:

      (react-dom/flushSync (fn [] (frame/replace-app-db! fid …)))
      ;; app-db moved, the subscription recomputed, the cell was marked,
      ;; and the DOM still showed the OLD value when this returned.

  Measured four ways on a mounted 300-cell grid under the UIx adapter
  (rf2-dq20a): the write-inside-`flushSync` arm never repainted, while
  the same write followed by a yielded microtask, a `setTimeout 0` or a
  `requestAnimationFrame` all did. It was silent, and `flushSync` is
  exactly what a consumer reaches for when the next line measures layout,
  moves focus, sets a caret or hands off to imperative third-party code.

  ## The fix, and what it deliberately does not do

  The microtask is not abolished. Making a mark notify SYNCHRONOUSLY
  would recompute and re-render inside the source write — the trap Spec
  006 invariant 6 exists to forbid — and would dissolve the coalescing
  every batch in the substrate rests on.

  Instead the window gains a SECOND closer, armed by the same first mark
  and running the same idempotent [[re-frame.freehand.cell/flush!]]: a
  render of one nil-returning sentinel component, whose LAYOUT EFFECT
  closes the window. React's own scheduler then decides when that render
  happens, and that is the whole point —

    - **inside `flushSync`**, an update issued during the callback takes
      the SYNC lane, so React renders the sentinel, runs its layout
      effect, closes the window, and flushes the cells' resulting
      `useSyncExternalStore` updates before `flushSync` returns. The DOM
      is current on the next line.
    - **outside it**, the update takes the default lane and React gets to
      it later — by which time the microtask closer, armed FIRST and
      running at the microtask checkpoint, has already closed the window.
      The sentinel's effect then finds nothing pending and does nothing.
      Ordinary batching is bit-for-bit what it was.

  Neither closer can run while the marking stack is still unwinding, so
  Spec 006 §Render-batch finalization guarantees 1 and 2 — a
  run-to-completion drain cannot be split, and N epochs in one drain
  coalesce into one batch — hold exactly as before. What changed is only
  which checkpoint may close a window a SYNCHRONOUS commit is waiting on.

  ## Why a detached root of its own

  A Freehand ViewCell does not need a Freehand root: `v/->react` mounts
  one inside a foreign React tree, and the sentinel has to reach that
  cell too. A single detached root — created lazily on the first mark,
  never attached to the document, rendering nil — covers every mount path
  with one mechanism, and `flushSync` flushes sync work across ALL roots,
  so being a separate root costs the mechanism nothing. It is the same
  shape the spine's after-render driver root already uses
  (`re-frame.substrate.spine`, rf2-t0x90), for the same reason.

  It holds no application state and is deliberately never torn down: it
  is one two-fiber tree for the life of the process, reused by every
  window, and re-creating it per adapter generation would cost more than
  it could ever save.

  INTERNAL. Nothing here is application API — the door is `v/sub` and the
  mounting verbs; this is what makes them honest under a synchronous
  flush.

  Normative owner:
  [`spec/006-ReactiveSubstrate.md`](../../../../../spec/006-ReactiveSubstrate.md)
  §Render-batch finalization — the host-checkpoint boundary."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [re-frame.freehand.cell :as cell]))

(defonce ^:private driver-root (atom nil))

(defonce ^:private window-seq (atom 0))

(defn- close-window-sentinel
  "The whole component: render nothing, and close the pending window from
  a LAYOUT effect on every commit.

  A layout effect rather than a passive one because `flushSync` ALWAYS
  runs layout effects synchronously during the commit — a documented
  guarantee — while its flushing of passive effects is a React-19
  implementation detail and not a contract. Under a React that deferred
  passives this component would close the window after the flush had
  returned, which is the bug it exists to remove.

  No deps array: firing on every commit IS the contract, because every
  commit of this root was provoked by a mark that wants the window
  closed."
  [_props]
  (react/useLayoutEffect
    (fn close-window []
      (cell/flush!)
      js/undefined))
  nil)

(defn- dom-available?
  "True when a `document` capable of creating elements is reachable — the
  precondition for the driver root. False under a pure-node runner (no
  jsdom) and under `react-dom/server`, where there is no synchronous
  commit to be visible to and the microtask closer is the whole story."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- ensure-driver-root!
  "The lazily-created singleton detached root. Never attached to the
  document: the sentinel renders nil, so a detached host node is
  sufficient and nothing this creates can be seen."
  []
  (or @driver-root
      (let [host (.createElement js/document "div")
            root (rdc/createRoot host)]
        (reset! driver-root root)
        root)))

(defn arm!
  "Schedule one sentinel render — the pending window's host-visible
  closer, installed on [[re-frame.freehand.cell]] at load.

  A FRESH props object every time, deliberately: React bails out of
  re-rendering a child whose element is identical to the last one, and a
  bailed-out render runs no layout effect and would close no window.

  Returns nil. A host with no DOM arms nothing and leaves the microtask
  closer to do the whole job."
  []
  (when (dom-available?)
    (.render (ensure-driver-root!)
             (react/createElement close-window-sentinel
                                  #js {:rfWindow (swap! window-seq inc)})))
  nil)

(cell/set-host-window-closer! arm!)
