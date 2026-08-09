(ns re-frame.hicasso.impl.mount
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
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.roots :as roots]
            [re-frame.interop :as interop]
            [re-frame.trace :as trace]
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

  A hydrated root carries [[adoption-window-closer]] and its own
  adoption-window provider as a Fragment around the app subtree, and it
  has to carry them on EVERY subsequent render: React reconciles a root
  by its top element, so handing a hydrated root a bare provider where a
  Fragment stood would not be a cheaper render, it would be a different
  tree — React unmounts the adopted subtree and mounts a fresh one,
  discarding every node, cell and subscription the adoption just
  established. Measured: the first `render!` after a hydration re-ran all
  four boundary bodies and replaced all four DOM nodes.

  **The window is what says a root is hydrated** (rf2-6tmu). The handle
  used to carry a separate `:hydrated?` boolean to drive this branch;
  now the presence of `:adoption` says the same thing and is the thing
  the wrapper is FOR, so there is one fact here rather than two that
  could disagree.

  An ordinary root gets no wrapper at all, which keeps the tree the whole
  bench lane measures exactly what it was — no extra fiber, no extra
  passive effect, no context provider, and nothing new in
  `re-frame.hicasso.impl.inventory/retained-inventory`."
  [handle hiccup]
  (let [app (provider (:frame handle)
                      (codec/root-element (:frame handle) hiccup))]
    (if-some [window (:adoption handle)]
      (react/createElement (.-Fragment react) nil
                           (react/createElement adoption-window-closer
                                                #js {:rfWindow window})
                           (roots/with-adoption window app))
      app)))

(defn render!
  "Render `hiccup` into an existing root, synchronously.

  The root hiccup is interpreted through
  [[re-frame.hicasso.impl.codec/root-element]] rather than
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
  "The component that CLOSES **its own root's** adoption window, on that
  root's hydration commit and not before (rf2-2rtt6.84, rf2-6tmu).

  Lifted in shape from the spine's own
  `re-frame.substrate.spine/adoption-window-closer`, down to taking the
  window off a prop: a passive `useEffect` with empty deps, so it runs
  exactly once and strictly AFTER the commit that adopted the server DOM.
  Renders nil — no DOM, so it adds nothing for `hydrateRoot` to match and
  cannot itself mismatch.

  The window arrives as `rfWindow` rather than being read from a module
  slot, and that prop IS the repair: a closer can only shut the window
  its own root minted, so a sibling root still adopting stays adopting.

  Public because that is what makes adoption OBSERVABLE without a probe:
  `(roots/adopting? (:adoption handle))` answering false is this effect
  having run, which is the completion signal a witness waits on in place
  of the `flushSync` [[hydrate-root!]] refuses to perform."
  [^js props]
  (react/useEffect (fn close-window []
                     (roots/close-adoption-window! (.-rfWindow props))
                     js/undefined)
                   #js [])
  nil)

;; `unchecked-set`, not `aset`: `aset` is cljs.core's ARRAY writer and a
;; component is a function, so the write was a type error the compiler
;; happens not to enforce. Both emit the same `(x["displayName"] = ...)`,
;; and it is the STRING key that keeps the property off Closure's renamer
;; under `:advanced` — the reason the arm's other stamps
;; (`collector/mint-view!`, `codec/memoize-boundary!`) are spelled this way.
(unchecked-set adoption-window-closer "displayName" "hicasso/adoption-window-closer")

;; ---------------------------------------------------------------------------
;; The recoverable-error reporter (rf2-2rtt6.97)
;; ---------------------------------------------------------------------------
;;
;; React reports the adoption divergences it RECOVERS FROM — a text
;; mismatch, or a missing / extra / wrong-type element — through the root's
;; `onRecoverableError`. Left with no root options this door got React's
;; DEFAULT handler, so a mismatch was an uncaught window error and NOTHING
;; ELSE: Spec 011's `:rf.ssr/hydration-mismatch` never fired, and a mismatch
;; was invisible to every tool that reads the instrumentation stream.
;;
;; The shape below is the spine's (`re-frame.substrate.spine`,
;; `native-hydration-reporter` / `hydrate-root-options`), because this arm is
;; a React-element root exactly as a native UIx root is: no hashable client
;; render-tree, so adoption IS the verification channel. Attribute-only
;; divergences are outside it by React's own contract and stay outside it
;; here (Spec 011 §Hydration-mismatch detection).

(defn- report-recoverable-default!
  "React's own default reporting, replicated.

  Installing ANY `onRecoverableError` takes React's default OFF, so a
  reporter that only emitted would SWALLOW the error — the fail-open
  `rf2-mwx08` exists to prevent. This door composes over React's default
  and never clobbers it: the uncaught error a runner treats as fatal is
  still uncaught, and the diagnostic is added beside it."
  [error]
  (if (fn? (.-reportError js/globalThis))
    (js/reportError error)
    (when (exists? js/console) (.error js/console error))))

(defn- emit-hydration-mismatch!
  "Spec 011's `:rf.ssr/hydration-mismatch`, tier-discriminated by
  `:where` — this door's own site, the way the spine tags
  `re-frame.substrate.spine/make-render` and the compiled tier tags its
  own hydrate door. No hash and no `:root-id`: a React-element root has
  neither. `:recovery` is `:warned-and-replaced` because React has
  already patched the DOM by the time this runs — there is no
  `:hard-error` escalation to make.

  **The compiled tier's namespace is deliberately not spelled**, here or
  anywhere under `implementation/freehand`. EP-0036's donor-boundary law
  is enforced by a plain `git grep` over this tree, and a grep cannot
  tell a docstring from a `:require` — correctly, because a cleverer one
  would eventually let a real dependency through. Naming the donor in
  prose reds the gate; describing it does not.

  Not an event and it mints no epoch: it fires from a React root-error
  callback, outside any dispatch scope."
  [error]
  (trace/emit! :warning :rf.ssr/hydration-mismatch
               {:error    (some-> error .-message)
                :where    're-frame.hicasso.impl.mount/hydrate-root!
                :recovery :warned-and-replaced}))

(defn hydration-reporter
  "BUILD the `onRecoverableError` for the root that owns `window`
  (rf2-6tmu). A builder, exactly as the spine's
  `re-frame.substrate.spine/native-hydration-reporter` is a builder, and
  for the spine's reason: the window it consults belongs to ONE root, so
  the callback has to close over it.

  Emits the framework diagnostic ONLY while THAT root's window is open,
  then ALWAYS reports. Both halves matter and they fail in opposite
  directions:

    - React holds this callback for the root's whole lifetime and fires
      it for post-hydration recoverable errors too — a concurrent render
      it retried and recovered. Emitting outside the window would label
      one of those a hydration mismatch. **Reading a page-wide window
      here would mislabel a completed root's later recovery whenever any
      sibling was still adopting** — which is what a page-global
      reference count would have bought.
    - Never emitting is the other failure, and it is the one that was
      shipping: a page-wide boolean let root A's closer silence root B's
      genuine mismatch.

  The delegation is unconditional in both cases — installing ANY
  `onRecoverableError` takes React's default off, so `rf2-mwx08`'s
  fail-open is preserved by reporting whether or not the framework
  emitted.

  Public because that is what lets a witness drive the REAL callback
  across the window boundary rather than a copy of it — the spine's own
  reason for publishing `native-hydration-reporter`."
  [^js window]
  (fn on-recoverable [error _error-info]
    (when (roots/adopting? window)
      (emit-hydration-mismatch! error))
    (report-recoverable-default! error)))

(defn- hydrate-root-options
  "The `react-dom/client` root options for the root owning `window`, or
  nil.

  Nil in production, where the emit DCEs behind `interop/debug-enabled?`
  and the only thing left to install would be a replica of the default
  React would have run anyway — so the caller passes no options at all
  and the shipped path is exactly what it was. The spine gates the same
  way, one clause wider: it also installs for a host-authored
  `:on-recoverable-error`, and this arm has no host-authored callback to
  compose with."
  [window]
  (when interop/debug-enabled?
    #js {:onRecoverableError (hydration-reporter window)}))

(defn hydrate-root!
  "Associate `container`'s **existing server-rendered DOM** with
  `frame-kw` and `hiccup`, by adoption. [[root!]]'s hydrating twin, and
  the client half every SSR route shares (rf2-2rtt6.84).

  Returns the same handle shape [[root!]] does — `{:root :frame
  :container}` — so [[render!]], [[dispatch!]], [[unmount!]] and
  [[release!]] all take a hydrated handle unchanged.

  ## `hydrateRoot` is not wrapped in `flushSync`

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

  ## It DOES carry root options, and only these (rf2-2rtt6.97)

  [[hydration-reporter]] rides as the root's `onRecoverableError`, so a
  divergence React recovers from surfaces as Spec 011's
  `:rf.ssr/hydration-mismatch` instead of only as an uncaught window
  error. It is the SPINE's arrangement, and it does not soften
  `rf2-mwx08`: the reporter always reports, so the uncaught error is
  still uncaught and the diagnostic is added beside it. In production
  [[hydrate-root-options]] answers nil and this call is the bare
  two-argument one it always was.

  ## The handle carries `:adoption` — this root's OWN window (rf2-6tmu)

  [[root!]]'s three keys are all here and every door takes this handle
  unchanged. The fourth key is not decoration and it is not a flag: it is
  the window itself, minted here and reachable from nowhere else. Three
  things need it and each gets the same object — the closer, the
  reporter, and the provider presence reads — so \"this root is adopting\"
  is one fact with one owner rather than a page-wide slot four callers
  race for.

  It is also what [[tree]] branches on, because the wrapper is part of the
  ROOT's shape and [[render!]] has to reproduce it or React tears the
  adopted tree down and rebuilds it.

  **Minted unconditionally, unlike the spine's** — which mints only when
  it is going to install a reporter, because its window has no other
  reader. This arm's window has a PRODUCTION reader: presence starts an
  adopted child `:present` rather than `:mounting`, which is behaviour a
  release build must keep. So the window and its provider ride in
  production and only the reporter is debug-gated."
  [container frame-kw hiccup]
  (let [window  (roots/open-adoption-window!)
        handle  {:frame frame-kw :container container :adoption window}
        element (tree handle hiccup)
        opts    (hydrate-root-options window)]
    (assoc handle :root (if opts
                          (react-dom-client/hydrateRoot container element opts)
                          (react-dom-client/hydrateRoot container element)))))

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

  **Shuts this root's adoption window first** (rf2-6tmu). A root torn
  down before its passive effects ever ran never gets its closer, and an
  open window that outlives its root is a window nothing can ever shut.
  Root teardown owns root state, so it is this door's job and not a
  page-wide reset's — closing a sibling's window from a reset is exactly
  the cross-root reach this bead removed.

  Idempotent, for the same reason [[release!]] is —
  `roots/close-adoption-window!` is idempotent and nil-tolerant, so an
  ordinary handle (which carries no window) and a second call are both
  no-ops."
  [handle]
  (roots/close-adoption-window! (:adoption handle))
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
  (collector/reset-runtime!)
  nil)

(defn dispatch!
  "Dispatch through the arm's synchronous door and commit the echo. The
  witness door; an intent written in a view reaches
  `collector/dispatch!` on its own."
  [handle event]
  (collector/dispatch! (:frame handle) event)
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
