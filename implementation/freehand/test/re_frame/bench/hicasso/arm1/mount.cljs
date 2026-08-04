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
  wrong one for a witness that reads the page.

  **[[hydrate-root!]] is the exception, and deliberately** (rf2-2rtt6.84).
  Adoption is React's own concurrent business and nothing in this tree
  forces it synchronously; wrapping `hydrateRoot` in a `flushSync` would
  be inventing a schedule to make a witness easier to write. So that one
  door returns before the tree is adopted, and a witness for it waits for
  the closer instead of for a flush."
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

(declare adoption-window-closer)

(defn- tree
  "The root element for `handle`'s next render — **the one place the root
  tree's SHAPE is decided**, and the reason it is a function of the
  handle rather than of its two callers.

  A hydrated root carries [[adoption-window-closer]] as a Fragment
  sibling of the app subtree, and it has to carry it on EVERY subsequent
  render: React reconciles a root by its top element, so handing a
  hydrated root a bare provider where a Fragment stood would not be a
  cheaper render, it would be a different tree — React unmounts the
  adopted subtree and mounts a fresh one, discarding every node, cell and
  subscription the adoption just established. Measured: the first
  `render!` after a hydration re-ran all four boundary bodies and
  replaced all four DOM nodes.

  An ordinary root gets no wrapper at all, which keeps the tree the whole
  bench lane measures exactly what it was — no extra fiber, no extra
  passive effect, and nothing new in
  `re-frame.bench.hicasso.arm1.runtime/retained-inventory`."
  [handle hiccup]
  (let [app (provider (:frame handle)
                      (codec/root-element (:frame handle) hiccup))]
    (if (:hydrated? handle)
      (react/createElement (.-Fragment react) nil
                           (react/createElement adoption-window-closer nil)
                           app)
      app)))

(defn render!
  "Render `hiccup` into an existing root, synchronously.

  The root hiccup is interpreted through
  [[re-frame.bench.hicasso.front.codec/root-element]] rather than
  `as-element`, because the root is the one creator with no ancestor body
  to inherit the frame from — the case rf2-2rtt6.39's frame-as-a-prop
  variant needs named. The context provider is installed regardless: it
  is what the substrate's other React-shaped adapters read, and what the
  error boundary and the presence tray resolve their frame from.

  Takes a hydrated handle unchanged — [[tree]] is what makes that true."
  [handle hiccup]
  (react-dom/flushSync (fn [] (.render (:root handle) (tree handle hiccup))))
  handle)

(defn root!
  "Associate `container`, `frame-kw` and `hiccup`. Returns the handle."
  [container frame-kw hiccup]
  (let [handle {:root      (react-dom-client/createRoot container)
                :frame     frame-kw
                :container container}]
    (render! handle hiccup)
    handle))

(defn adoption-window-closer
  "The component that CLOSES the adoption window, on the hydration commit
  and not before (rf2-2rtt6.84).

  Lifted verbatim in shape from the spine's own
  `re-frame.substrate.spine/adoption-window-closer`: a passive
  `useEffect` with empty deps, so it runs exactly once and strictly AFTER
  the commit that adopted the server DOM. Renders nil — no DOM, so it
  adds nothing for `hydrateRoot` to match and cannot itself mismatch.

  Public because that is what makes adoption OBSERVABLE without a probe:
  `(rt/adopting?)` answering false is this effect having run, which is
  the completion signal a witness waits on in place of the `flushSync`
  [[hydrate-root!]] refuses to perform."
  [_props]
  (react/useEffect (fn close-window [] (rt/close-adoption-window!) js/undefined)
                   #js [])
  nil)

(aset adoption-window-closer "displayName" "hicasso/adoption-window-closer")

(defn hydrate-root!
  "Associate `container`'s **existing server-rendered DOM** with
  `frame-kw` and `hiccup`, by adoption. [[root!]]'s hydrating twin, and
  the client half every SSR route shares (rf2-2rtt6.84).

  Returns the same handle shape [[root!]] does — `{:root :frame
  :container}` — so [[render!]], [[dispatch!]], [[unmount!]] and
  [[release!]] all take a hydrated handle unchanged.

  ## `hydrateRoot` is called PLAIN

  No `flushSync`, and that is a finding rather than an omission: nothing
  in this tree forces hydration synchronously, so a flush here would
  manufacture a schedule no shipped caller has and every row taken
  through it would be a row about the manufactured one. The consequence
  is that **this returns before the tree is adopted** — React adopts
  concurrently, and the DOM on the line after this call is still the
  server's.

  ## So the window is closed by a COMPONENT, not by this function

  [[adoption-window-closer]] rides as a Fragment sibling of the app
  subtree and clears the window from its passive effect. That is the
  spine's pattern (`spine.cljs`, `adoption-window-closer` /
  `make-render`), and the reason is the same in both places: a passive
  effect is the earliest thing that is unambiguously after the hydration
  commit. Closing the window here, before `hydrateRoot` returns, would
  close it before a single body had run inside it.

  The closer sits OUTSIDE the frame provider on purpose — it reads no
  subscription and needs no frame, and a nil-rendering component with no
  frame dependency is the smallest thing that can carry the effect.

  ## The handle carries `:hydrated?`, and it has to

  [[root!]]'s three keys are all here and every door takes this handle
  unchanged. The fourth key is not decoration: the wrapper above is part
  of the ROOT's shape, so [[render!]] has to reproduce it or React tears
  the adopted tree down and rebuilds it. [[tree]] is the one place that
  decision is made and `:hydrated?` is what it reads."
  [container frame-kw hiccup]
  (rt/open-adoption-window!)
  (let [handle {:frame frame-kw :container container :hydrated? true}]
    (assoc handle :root (react-dom-client/hydrateRoot container (tree handle hiccup)))))

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
