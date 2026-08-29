(ns re-frame.hicasso.impl.mount
  "THE PACKAGE'S ROOT — one operation, an idempotent teardown
  (HD-021(b)).

  `root!` ENSURES a frame, associates it with a DOM node and a hiccup
  tree, and returns the handle every other door here takes. That is the
  whole execution contract the package needs; the names stay unfrozen
  (HD-021 pins the semantics, not the spelling).

  ## Three of these vars ARE the public door

  `re-frame.hicasso` re-exports [[root!]], [[render!]] and [[unmount!]],
  so a consumer's whole root lifecycle — mount, re-render after a hot
  reload, tear down — is spelled here. Each of the three is root-scoped:
  it takes a handle (or makes one) and reaches nothing another root owns.

  **Two of the three keep their spelling on the door and [[root!]] does
  not** (naming-ledger row 13): it is published as `h/mount!`,
  over the `(node config view)` contract row 20 settles, and the config
  map is what carries [[ensure-frame!]]'s `:initial-events` alongside
  `:identifier-prefix` without a second arity. [[hydrate-root!]] is
  published as `h/hydrate!` over the same shape and for the same reason,
  so the asymmetry here is one rule rather than two exceptions: the impl
  door keeps ONE positional caller shape for its own witnesses to drive,
  and the facade adapts.

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

  **[[hydrate-root!]] is the exception, and deliberately.**
  Adoption is React's own concurrent business and nothing in this tree
  forces it synchronously; wrapping `hydrateRoot` in a `flushSync` would
  be inventing a schedule to make a witness easier to write. So that one
  door returns before the tree is adopted, and a witness for it waits for
  the closer instead of for a flush."
  (:require [re-frame.adapter.context :as adapter-context]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
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

(defn tree
  "The root element for `handle`'s next render — **the one place the root
  tree's SHAPE is decided**, and the reason it is a function of the
  handle rather than of its two callers.

  **Public because the SERVER half calls it too.**
  `re-frame.hicasso.server/render` builds its element from this exact
  function, with a handle carrying a per-request `:adoption` window, so
  the bytes it emits and the tree [[hydrate-root!]] adopts are the same
  shape by CONSTRUCTION rather than by two implementations agreeing.
  That is what makes the server a matching entry rather than a second
  renderer: were the server to build its own element, the fork this
  function decides would have to be mirrored in a place nothing forces to
  follow it, which is the shape of the bug rather than of the fix.

  A hydrated root carries [[adoption-window-closer]] and its own
  adoption-window provider as a Fragment around the app subtree, and it
  has to carry them on EVERY subsequent render: React reconciles a root
  by its top element, so handing a hydrated root a bare provider where a
  Fragment stood would not be a cheaper render, it would be a different
  tree — React unmounts the adopted subtree and mounts a fresh one,
  discarding every node, cell and subscription the adoption just
  established. Measured: the first `render!` after a hydration re-ran all
  four boundary bodies and replaced all four DOM nodes.

  **The window is what says a root is hydrated.** The presence of
  `:adoption` drives this branch, and it is the thing the wrapper is FOR,
  so there is one fact here rather than a separate `:hydrated?` flag that
  could disagree with it.

  An ordinary root gets no wrapper at all, which keeps the tree the whole
  bench lane measures free of it — no extra fiber, no extra
  passive effect, no context provider, and nothing new retained per
  boundary."
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
  hook**:

      (defonce ^:private !root (atom nil))

      (defn ^:export init []
        (reset! !root (h/mount! node {:frame ::frame} [app {}])))

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
  to inherit the frame from — the case the frame-as-a-prop variant needs
  named. The context provider is installed regardless: it
  is what the substrate's other React-shaped adapters read, and what the
  error boundary and the presence tray resolve their frame from.

  Takes a hydrated handle unchanged — [[tree]] is what makes that true."
  [handle hiccup]
  (react-dom/flushSync (fn [] (.render (:root handle) (tree handle hiccup))))
  handle)

;; ---------------------------------------------------------------------------
;; The one root option a CALLER may name
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
;; this package. These doors are what let a caller say the word.
;;
;; **The PREFIX's server half is React's too, and it needs no door
;; here.** It is spelled on whichever server caller bakes the bytes:
;; `re-frame.hicasso.server/render` carries `:identifier-prefix`, a
;; pass-through of exactly this shape, and a hand-rolled
;; `renderToString(element, #js {"identifierPrefix" "a-"})` names
;; React's option directly. Either way the two sides meet in React's
;; own vocabulary and what matters is that the caller hands BOTH of
;; them the same string.
;; Agreement is the contract; presence on one side proves nothing.
;;
;; **That settles the prefix and not the pair**, and the difference is
;; the whole of why no hydrating door is public. `useId` derives from
;; tree POSITION as well as from the prefix, and a hydrating root's tree
;; is not the app subtree. [[hydrate-root!]] carries the measurement and
;; the consequence.

(defn- root-options
  "React's `react-dom/client` root options object, or nil when there is
  nothing to say.

  Nil rather than an empty object, because both root doors branch on it
  to call React's BARE arity — the arrangement [[hydrate-root!]] relies
  on for production, where the reporter compiles away and the shipped
  call is the bare two-argument one.

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

(defn ensure-frame!
  "Ensure `frame-kw` before its root's first render: create it through
  `rf/make-frame` seeded with `initial-events` when absent; join it
  untouched — no re-seed, no config refresh — when live. Answers
  `frame-kw`. This is core's `frame-root` vocabulary
  (docs/EP/EP-0027-frame-initial-events.md): `:initial-events` reaches
  `make-frame` untouched, and EP-0027's preflight owns its shape and
  errors.

  The guard asks `frame/frame-incarnation-token` rather than trusting
  `make-frame`, because re-`make-frame`-ing a live id is idempotent
  REPLACEMENT — config and generation refresh, durable state preserved —
  so an unguarded call would not fail a joining root, it would silently
  refresh the first root's config, and the guide promises the opposite
  (docs/core/hicasso/00-installation.md). Synchronous, and called before
  `createRoot`: `make-frame` drains the seed to a fixed point before it
  returns, so the first paint is the seeded one
  (docs/design/hicasso/architecture.md, section The root)."
  [frame-kw initial-events]
  (when (nil? (frame/frame-incarnation-token frame-kw))
    (rf/make-frame (cond-> {:id frame-kw}
                     (seq initial-events) (assoc :initial-events initial-events))))
  frame-kw)

(defn root!
  "Associate `container`, `frame-kw` and `hiccup`. Returns the handle.

  `opts` is optional and carries two keys, both of them the CALLER's:

  `:initial-events` seeds the frame this root names, through
  [[ensure-frame!]] and before React renders anything — ordinary events,
  in the order given, run once when this mount CREATES the frame and
  never when it joins one. It is core's `:initial-events`
  reaching `rf/make-frame` untouched, not a spelling this package owns.

  `:identifier-prefix` is handed straight to `createRoot` as React's
  `identifierPrefix`. A page mounting two roots gives them
  distinct prefixes so their `useId` values cannot collide; a page with
  one root names none, and passing no `opts` is how it says so — the call
  React receives is then the bare one it always was."
  ([container frame-kw hiccup] (root! container frame-kw hiccup nil))
  ([container frame-kw hiccup opts]
   (ensure-frame! frame-kw (:initial-events opts))
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
  root's hydration commit and not before.

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
;; under `:advanced` — the reason the package's other stamps
;; (`collector/mint-view!`, `codec/memoize-boundary!`) are spelled this way.
(unchecked-set adoption-window-closer "displayName" "hicasso/adoption-window-closer")

;; ---------------------------------------------------------------------------
;; The recoverable-error reporter
;; ---------------------------------------------------------------------------
;;
;; React reports the adoption divergences it RECOVERS FROM — a text
;; mismatch, or a missing / extra / wrong-type element — through the root's
;; `onRecoverableError`. With no root options that channel is React's
;; DEFAULT handler, so a mismatch would be an uncaught window error and
;; NOTHING ELSE: Spec 011's `:rf.ssr/hydration-mismatch` would never fire,
;; and a mismatch would be invisible to every tool that reads the
;; instrumentation stream. The reporter below is what closes that gap.
;;
;; The shape is the spine's (`re-frame.substrate.spine`,
;; `native-hydration-reporter` / `hydrate-root-options`), because this
;; package's root is a React-element root exactly as a native UIx root is:
;; no hashable client render-tree, so adoption IS the verification channel.
;; Attribute-only divergences are outside it by React's own contract and
;; stay outside it here (Spec 011 §Hydration-mismatch detection).

(defn- report-recoverable-default!
  "React's own default reporting, replicated.

  Installing ANY `onRecoverableError` takes React's default OFF, so a
  reporter that only emitted would SWALLOW the error, which the
  fail-open rule forbids. This door composes over React's default
  and never clobbers it: the uncaught error a runner treats as fatal is
  still uncaught, and the diagnostic is added beside it."
  [error]
  (if (fn? (.-reportError js/globalThis))
    (js/reportError error)
    (when (exists? js/console) (.error js/console error))))

(defn- emit-hydration-mismatch!
  "Spec 011's `:rf.ssr/hydration-mismatch`, tier-discriminated by
  `:where` — this door's own site, the way the spine tags
  `re-frame.substrate.spine/make-render`. No hash and no `:root-id`: a
  React-element root has
  neither. `:recovery` is `:warned-and-replaced` because React has
  already patched the DOM by the time this runs — there is no
  `:hard-error` escalation to make.

  Not an event and it mints no epoch: it fires from a React root-error
  callback, outside any dispatch scope."
  [error]
  (trace/emit! :warning :rf.ssr/hydration-mismatch
               {:error    (some-> error .-message)
                :where    're-frame.hicasso.impl.mount/hydrate-root!
                :recovery :warned-and-replaced}))

(defn hydration-reporter
  "BUILD the `onRecoverableError` for the root that owns `window`.
  A builder, exactly as the spine's
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
    - Never emitting is the other failure: a page-wide BOOLEAN lets
      root A's closer silence root B's genuine mismatch, which is why
      the window is per-root rather than a page-wide flag.

  The delegation is unconditional in both cases — installing ANY
  `onRecoverableError` takes React's default off, so the fail-open rule
  is preserved by reporting whether or not the framework emitted.

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
  `:on-recoverable-error`, and this package has no host-authored callback
  to compose with.

  **`:identifier-prefix` is NOT gated, and that asymmetry is the point.**
  The reporter is a diagnostic and a release build is
  entitled to lose it; the prefix is BEHAVIOUR — it decides what `useId`
  answers, and a release build that dropped it would hydrate every server
  `useId` into a mismatch. So a caller who names one gets it in every
  build, and a caller who names none still gets nil here in production
  and React's bare two-argument call downstream."
  [window opts]
  (root-options (:identifier-prefix opts)
                (when interop/debug-enabled? (hydration-reporter window))))

(defn hydrate-root!
  "Associate `container`'s existing server-rendered DOM with `frame-kw`
  and `hiccup` by adoption: `root!`'s hydrating twin, published as
  `h/hydrate!`. Returns `root!`'s handle shape plus `:adoption`, this
  root's own window, and every other door takes a hydrated handle
  unchanged. Does not ensure the frame — an adopting root's state arrives
  through `re-frame.ssr/hydrate!` first, and a seed here would overwrite
  it.

  Returns BEFORE the tree is adopted: `hydrateRoot` is called plain,
  because a `flushSync` would manufacture a schedule no shipped caller
  has, so the DOM on the next line is still the server's and the window
  is closed by `adoption-window-closer` from a passive effect, the
  earliest point unambiguously after the hydration commit. In debug
  builds the root carries `hydration-reporter` as its `onRecoverableError`,
  so a divergence React recovers from surfaces as Spec 011's
  `:rf.ssr/hydration-mismatch` beside the uncaught error, never instead
  of it. The window is minted in every build because presence reads it in
  production.

  `opts` carries one key, `:identifier-prefix` — React's `identifierPrefix`,
  passed through untouched, and it must be the string the server render
  used or every `useId` in the tree resolves differently from the bytes
  and React recovers by replacing the subtree. Matching the prefix is
  necessary and not sufficient: `useId` also derives from tree position,
  and this root's tree is `tree`'s Fragment rather than the bare app, so
  the server half must be `re-frame.hicasso.server/render`, which builds
  its element from the same function
  (implementation/hicasso/spec/dispositions.md HS-11, obstruction 2;
  `re-frame.hicasso.server-render-ssr-dom-cljs-test`). Mechanism and
  witnesses: docs/design/hicasso/architecture.md, section The root."
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

  Two things it deliberately does not do, and each would be a fault on a
  public teardown door:

  **It touches nothing the runtime holds.** Whatever edges, cells and
  cached closures survive are the ones React's own cleanup failed to
  release, which is what makes this the half a residue gate can read: a
  teardown that emptied the tables first answers `0` whether it released
  anything or not, and that is a gate that cannot go red.
  It is also what makes teardown ROOT-scoped in a multi-root page —
  every table the package holds is one-per-page and keyed by frame, so a
  door that reset them would tear down every sibling root's state along
  with its own.

  **It does not remove the container from the document.** The container
  is the CALLER's node, handed to [[root!]], and a teardown door may not
  delete a node it did not create — React's own `root.unmount()` empties
  a container and leaves it where it is, which is the contract every
  consumer already knows. Detaching is [[release!]]'s, where the
  container is one [[fresh-container!]] minted for the occasion.

  **Shuts this root's adoption window first.** A root torn
  down before its passive effects ever ran never gets its closer, and an
  open window that outlives its root is a window nothing can ever shut.
  Root teardown owns root state, so it is this door's job and not a
  page-wide reset's — closing a sibling's window from a reset is exactly
  the cross-root reach this door must not have.

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
  fixture door, and it is not on the public facade.** It ends
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
  "Dispatch through the package's synchronous door and commit the echo.
  The witness door; an intent written in a view reaches
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
  this package degrades to a stated skip there rather than a false green."
  []
  (and (exists? js/document) (some? (.-createElement js/document))))
