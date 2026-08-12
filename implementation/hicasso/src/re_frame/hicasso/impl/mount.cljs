(ns re-frame.hicasso.impl.mount
  "ARM 1's ROOT — one operation, an idempotent teardown (HD-021(b)).

  `root!` associates a DOM node, a frame, and a hiccup tree, and returns
  the handle every other door here takes. That is the whole execution
  contract this arm needs; the names stay unfrozen (HD-021 pins the
  semantics, not the spelling).

  ## Three of these vars ARE the public door (rf2-31xm, rf2-e2al)

  `re-frame.hicasso` re-exports [[root!]], [[render!]] and [[unmount!]]
  under their own names, so a consumer's whole root lifecycle — mount,
  re-render after a hot reload, tear down — is spelled here. Each of the
  three is root-scoped: it takes a handle (or makes one) and reaches
  nothing another root owns.

  [[release!]] is NOT among them. It is [[unmount!]] plus a detached
  container plus a page-wide `collector/reset-runtime!` — the fixture
  door, correct where one app owns the page and wrong on a public
  facade, where tearing down one of two roots would empty the runtime
  under the other.

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
  "Render `hiccup` into an existing root, synchronously. Answers the
  handle.

  **The public door's re-render, and the whole of a consumer's hot-reload
  hook** (rf2-e2al):

      (defonce ^:private !root (atom nil))

      (defn ^:export init []
        (reset! !root (h/root! node ::frame [app {}])))

      (defn ^:dev/after-load reload! []
        (h/render! @!root [app {}]))

  It re-renders the root React already has, so React reconciles against
  the tree on the page and the reloaded view code meets its own DOM.
  Calling [[root!]] again instead would `createRoot` a second time and
  REPLACE the tree — every node discarded, every subscription re-made,
  every scrap of component state and scroll position lost, which is
  exactly what a hot reload must not do.

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

;; ---------------------------------------------------------------------------
;; The one root option a CALLER may name (rf2-hic-046)
;; ---------------------------------------------------------------------------
;;
;; `identifierPrefix` is React's own, and `:identifier-prefix` is a
;; PASS-THROUGH to it: no default, no coercion, no schema, no second
;; spelling. Two facts make it necessary and React owns both of them.
;; `useId` numbers each root from the same start, so two roots on one page
;; mint the same ids unless their prefixes differ; and a HYDRATING root
;; must be given the prefix its server render used, or every `useId` in
;; the tree resolves to a different string on the client and React
;; recovers by throwing the server's nodes away. Neither is a fact about
;; this arm. The only thing missing was a way to say the word through
;; these doors, and until rf2-hic-046 there was none.
;;
;; **The server half is React's too, and it needs no door here.** A
;; consumer server-renders through `react-dom/server` itself —
;; `renderToString(element, #js {"identifierPrefix" "a-"})` — which per
;; rf2-ggnp's census, re-run, is the only server path this package has.
;; So the two sides already meet in React's own vocabulary and what
;; matters is that the caller hands BOTH of them the same string.
;; Agreement is the contract; presence on one side proves nothing.

(defn- root-options
  "React's `react-dom/client` root options object, or nil when there is
  nothing to say.

  Nil rather than an empty object, because both root doors branch on it
  to call React's BARE arity — the arrangement [[hydrate-root!]] has
  always had for production, where the reporter compiles away and the
  shipped call is the two-argument one it was before any option existed.

  String keys through `unchecked-set`, for the reason the `displayName`
  stamps in this file use it: the string key is what keeps the property
  off Closure's renamer under `:advanced`, and an `identifierPrefix`
  renamed is a prefix React never sees."
  [identifier-prefix on-recoverable-error]
  (when (or (some? identifier-prefix) (some? on-recoverable-error))
    (let [o #js {}]
      (when (some? identifier-prefix)
        (unchecked-set o "identifierPrefix" identifier-prefix))
      (when (some? on-recoverable-error)
        (unchecked-set o "onRecoverableError" on-recoverable-error))
      o)))

(defn root!
  "Associate `container`, `frame-kw` and `hiccup`. Returns the handle.

  `opts` is optional and carries ONE key, `:identifier-prefix`, handed
  straight to `createRoot` as React's `identifierPrefix` (rf2-hic-046).
  A page mounting two roots gives them distinct prefixes so their `useId`
  values cannot collide; a page with one root names none, and passing no
  `opts` is how it says so — the call React receives is then the bare one
  it always was."
  ([container frame-kw hiccup] (root! container frame-kw hiccup nil))
  ([container frame-kw hiccup opts]
   (let [ropts  (root-options (:identifier-prefix opts) nil)
         handle {:root      (if ropts
                              (react-dom-client/createRoot container ropts)
                              (react-dom-client/createRoot container))
                 :frame     frame-kw
                 :container container}]
     (render! handle hiccup)
     handle)))

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
  "The `react-dom/client` root options for the root owning `window` and
  named by `opts`, or nil.

  The reporter is debug-only: in production the emit DCEs behind
  `interop/debug-enabled?` and the only thing left to install would be a
  replica of the default React would have run anyway. The spine gates the
  same way, one clause wider: it also installs for a host-authored
  `:on-recoverable-error`, and this arm has no host-authored callback to
  compose with.

  **`:identifier-prefix` is NOT gated, and that asymmetry is the point**
  (rf2-hic-046). The reporter is a diagnostic and a release build is
  entitled to lose it; the prefix is BEHAVIOUR — it decides what `useId`
  answers, and a release build that dropped it would hydrate every server
  `useId` into a mismatch. So a caller who names one gets it in every
  build, and a caller who names none still gets nil here in production
  and React's bare two-argument call downstream."
  [window opts]
  (root-options (:identifier-prefix opts)
                (when interop/debug-enabled? (hydration-reporter window))))

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

  ## It carries two root options — one the arm's, one the CALLER's

  [[hydration-reporter]] rides as the root's `onRecoverableError`, so a
  divergence React recovers from surfaces as Spec 011's
  `:rf.ssr/hydration-mismatch` instead of only as an uncaught window
  error (rf2-2rtt6.97). It is the SPINE's arrangement, and it does not
  soften `rf2-mwx08`: the reporter always reports, so the uncaught error
  is still uncaught and the diagnostic is added beside it.

  `opts` is the caller's half and carries ONE key,
  `:identifier-prefix` — React's `identifierPrefix`, passed through
  untouched (rf2-hic-046). **Hand it the same string the server render
  used.** `useId` is numbered per root and prefixed by this option, so a
  hydrating root given a different prefix (or none, where the server had
  one) resolves every id in the tree differently from the bytes it is
  adopting; React sees the divergence as a mismatch and recovers by
  replacing the subtree, which is a correct repair of a page that should
  never have shipped. The server side of the pair is React's own
  `react-dom/server` option and needs nothing from this arm — see the
  [[root-options]] section comment.

  Name neither and [[hydrate-root-options]] answers nil in production, so
  this call is the bare two-argument one it always was.

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
  ([container frame-kw hiccup] (hydrate-root! container frame-kw hiccup nil))
  ([container frame-kw hiccup opts]
   (let [window  (roots/open-adoption-window!)
         handle  {:frame frame-kw :container container :adoption window}
         element (tree handle hiccup)
         ropts   (hydrate-root-options window opts)]
     (assoc handle :root (if ropts
                           (react-dom-client/hydrateRoot container element ropts)
                           (react-dom-client/hydrateRoot container element))))))

(defn unmount!
  "Take THIS root down, and touch nothing else. `re-frame.hicasso`'s
  public teardown door and [[root!]]'s exact inverse.

  Two things it deliberately does not do, and each is a fault that was
  once shipped through the public facade (rf2-31xm):

  **It touches nothing the runtime holds.** Whatever edges, cells and
  cached closures survive are the ones React's own cleanup failed to
  release, which is what makes this the half a residue gate can read: a
  teardown that emptied the tables first answers `0` whether it released
  anything or not, and that is a gate that cannot go red (rf2-2rtt6.48).
  It is also what makes teardown ROOT-scoped in a multi-root page —
  every table this arm holds is one-per-page and keyed by frame, so a
  door that reset them would tear down every sibling root's state along
  with its own.

  **It does not remove the container from the document.** The container
  is the CALLER's node, handed to [[root!]], and a teardown door may not
  delete a node it did not create — React's own `root.unmount()` empties
  a container and leaves it where it is, which is the contract every
  consumer already knows. Detaching is [[release!]]'s, where the
  container is one [[fresh-container!]] minted for the occasion.

  **Shuts this root's adoption window first** (rf2-6tmu). A root torn
  down before its passive effects ever ran never gets its closer, and an
  open window that outlives its root is a window nothing can ever shut.
  Root teardown owns root state, so it is this door's job and not a
  page-wide reset's — closing a sibling's window from a reset is exactly
  the cross-root reach that bead removed.

  Idempotent: `roots/close-adoption-window!` is idempotent and
  nil-tolerant, so an ordinary handle (which carries no window) and a
  second call are both no-ops."
  [handle]
  (roots/close-adoption-window! (:adoption handle))
  (when-some [r (:root handle)]
    (react-dom/flushSync (fn [] (.unmount r))))
  nil)

(defn release!
  "Unmount the root, drop its container, and empty the runtime. **The
  fixture door, and it is not on the public facade** (rf2-31xm): it ends
  with a page that holds nothing, whatever this test left behind, which
  is right when one app owns the page and wrong for a consumer who has
  two roots and meant to tear down one.

  The two acts [[unmount!]] refuses are both here, and each is legitimate
  for a fixture: the container it detaches is a [[fresh-container!]] this
  same fixture appended, and the runtime it empties is a page the fixture
  owns end to end.

  Idempotent: releasing twice is not an error, because a teardown door
  that throws on the second call is a teardown door test fixtures route
  around.

  **Not the door a residue assertion takes** — see [[unmount!]]."
  [handle]
  (unmount! handle)
  (when-some [c (:container handle)]
    (when-some [p (.-parentNode c)] (.removeChild p c)))
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
