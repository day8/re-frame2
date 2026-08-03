(ns re-frame.bench.hicasso.arm1.mount
  "ARM 1's ROOT — one operation, an idempotent teardown (HD-021(b)).

  `root!` associates a DOM node, a frame, and a hiccup tree, and returns
  the handle `release!` takes. That is the whole execution contract this
  arm needs; the names stay unfrozen (HD-021 pins the semantics, not the
  spelling) and nothing here is a public API.

  The frame reaches the tree through the substrate's single internal
  React context — the same object every React-shaped adapter reads
  (`re-frame.adapter.context/frame-context`), so a Hicasso subtree and a
  UIx subtree under one provider resolve the same frame. That is what
  lets the dogfood screen's three renderings sit side by side in one page
  and be compared on authoring rather than on plumbing.

  ## Why the flushes are explicit

  React 19 renders a root concurrently, and a `useSyncExternalStore`
  notification schedules at the SYNC lane rather than committing inline.
  Witnesses assert the DOM, so every door here commits before it returns:
  `render!` renders inside `flushSync`, and [[settle!]] is the empty
  `flushSync` that lets an already-scheduled sync-lane notification land.
  Neither is `act` — `act` diverts work to a queue that is not the
  browser's, which is the right tool for an effect-ordering test and the
  wrong one for a witness that reads the page."
  (:require [re-frame.adapter.context :as adapter-context]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.codec :as codec]
            ["react" :as react]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]))

(defn provider
  "Scope `frame-kw` for a subtree. Renders no DOM of its own, so it cannot
  move a canonical-DOM parity comparison."
  [frame-kw child]
  (react/createElement (.-Provider adapter-context/frame-context)
                       #js {:value frame-kw}
                       child))

(defn settle!
  "Let an already-scheduled sync-lane notification commit. The empty
  `flushSync` — no work of its own, and the reason a witness may read the
  DOM on the line after a dispatch."
  []
  (react-dom/flushSync (fn [] nil))
  nil)

(defn render!
  "Render `hiccup` into an existing root, synchronously.

  The root hiccup is interpreted through
  [[re-frame.bench.hicasso.front.codec/root-element]] rather than
  `as-element`, because the root is the one creator with no ancestor body
  to inherit the frame from — the case rf2-2rtt6.39's frame-as-a-prop
  variant needs named. The context provider is installed regardless: it
  is what the substrate's other React-shaped adapters read, and what the
  error boundary and the presence tray resolve their frame from."
  [handle hiccup]
  (react-dom/flushSync
    (fn [] (.render (:root handle)
                    (provider (:frame handle)
                              (codec/root-element (:frame handle) hiccup)))))
  handle)

(defn root!
  "Associate `container`, `frame-kw` and `hiccup`. Returns the handle."
  [container frame-kw hiccup]
  (let [handle {:root      (react-dom-client/createRoot container)
                :frame     frame-kw
                :container container}]
    (render! handle hiccup)
    handle))

(defn unmount!
  "Unmount the root and detach its container, and **touch nothing the
  runtime holds**. Whatever edges, cells and cached closures survive are
  the ones React's own cleanup failed to release.

  This is the half a residue gate has to be able to read. [[release!]]
  also resets the runtime, and a reading taken after that reset is a
  reading of an emptied table: it answers `0` whether teardown worked or
  not, which is a gate that cannot go red (rf2-2rtt6.48). A witness that
  wants to assert on teardown therefore calls this, waits one macrotask
  for the cell and entry reapers, asserts, and resets afterwards.

  Idempotent, for the same reason [[release!]] is."
  [handle]
  (when-some [r (:root handle)]
    (react-dom/flushSync (fn [] (.unmount r))))
  (when-some [c (:container handle)]
    (when-some [p (.-parentNode c)] (.removeChild p c)))
  nil)

(defn release!
  "Unmount the root and drop every edge, cell and cached closure the arm
  held. The fixture door: it ends with a runtime that holds nothing,
  whatever this test left behind. Idempotent: releasing twice is not an
  error, because a teardown door that throws on the second call is a
  teardown door test fixtures route around.

  **Not the door a residue assertion takes** — see [[unmount!]]."
  [handle]
  (unmount! handle)
  (rt/reset-runtime!)
  nil)

(defn dispatch!
  "Dispatch through the arm's synchronous door and commit the echo. The
  witness door; an intent written in a view reaches
  `runtime/dispatch!` on its own."
  [handle event]
  (rt/dispatch! (:frame handle) event)
  (settle!)
  nil)

(defn fresh-container!
  "A detached-then-attached container, appended to the document body."
  []
  (let [c (js/document.createElement "div")]
    (.appendChild js/document.body c)
    c))

(defn browser?
  "Is there a real DOM here? `:node-test` has none, and every DOM claim in
  this arm degrades to a stated skip there rather than a false green."
  []
  (and (exists? js/document) (some? (.-createElement js/document))))
