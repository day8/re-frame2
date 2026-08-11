(ns re-frame.hicasso.impl.roots
  "THE HYDRATION ADOPTION WINDOW (rf2-2rtt6.84) — one window PER ROOT, and
  the three doors that move it (rf2-hic-009, rf2-6tmu).

  A window is what tells a render *whether the DOM it is about to produce
  is already on the screen*. `re-frame.hicasso.impl.mount` opens one
  around each `hydrateRoot` and hands it to that root's closer, which
  shuts it on that root's hydration commit;
  `re-frame.hicasso.impl.presence-react` is the one reader, and it reads
  it during a RENDER, through the context [[with-adoption]] installs.

  It is its own namespace because the window is the whole mechanism and
  it is worth being able to see all of it at once: the ref, the three
  doors, the context that carries it down a subtree, and the hook that
  reads it. The root LIFECYCLE proper — `root!`, `hydrate-root!`,
  `render!`, `unmount!`, `release!` — lives in
  `re-frame.hicasso.impl.mount`.

  ## It used to be ONE BOOLEAN FOR THE WHOLE PAGE, and that was a defect

  Every hydrating root opened the same module-level flag and every root's
  closer shut it, so N overlapping adoptions performed N opens and the
  FIRST close ended the window for all of them. Two consequences, both
  measured (PR #7751, rf2-6tmu):

    - **A silenced diagnostic.** Two overlapping divergent hydrations
      produced two React `Hydration failed` complaints and only ONE
      `:rf.ssr/hydration-mismatch`. Root B's mismatch was discarded, so
      every tool reading the instrumentation stream saw a healthy root
      that had in fact failed to adopt.
    - **Cross-root presence interference.** While ANY root hydrated, a
      presence tray in an unrelated ORDINARY root was told it was
      adopting too, and lost its enter transition.

  Correctness rested on the relative ordering of commits, passive
  effects, presence renders and recoverable-error callbacks across
  otherwise-independent roots. React's root API gives no reason to treat
  page-wide ordering as an invariant — `onRecoverableError` is an option
  of an *individual* `hydrateRoot`, and independently configured roots on
  one page are supported — so the ordering was an accident this arm
  depended on. Now each root carries its own window and nothing about one
  root's lifecycle is visible in another's.

  A page-global REFERENCE COUNT would not have fixed it: it keeps B's
  window open when A commits, so a later recoverable error from the
  already-committed A is falsely labelled a hydration mismatch, and it
  still leaks adoption semantics into presence components outside both
  hydrating subtrees. It converts one interference bug into another."
  (:require ["react" :as react]))

(defn open-adoption-window!
  "Open a window and answer it — the ONLY handle on it there is.

  Minted per `hydrate-root!` call — TODAY'S ONLY MINTER. Its one
  product consumer is `impl.mount/tree`, which scopes it over that
  root's subtree with [[with-adoption]] when the handle carries an
  `:adoption`. **No server-render entry exists in this tier**, so a
  server render finds no provider above it, reads the adoption
  context's `nil` default, and [[adopting?]] calls that closed —
  measured in `re-frame.hicasso.presence-ssr-seam-dom-cljs-test`.

  rf2-hic-046 has RULED the Render arm for motion and presence, which
  adds a second minter: one window per server render/request, scoped
  over that request's tree (rf2-6tmu's repair shape, item 5). That
  door is named here because it is DECIDED, not because it is built.

  Born open: the renders that follow it are adopting server-rendered
  DOM until something calls [[close-adoption-window!]] on this very
  object.

  A bare mutable ref rather than a registry entry, and deliberately.
  A registry keyed by frame cannot work — several roots may intentionally
  share a frame — and one keyed by container recreates React context
  badly and adds a cleanup path that can leak. The ref is reachable only
  from the root that minted it, so a root whose construction throws
  leaves nothing behind to close: the window it would have opened is
  simply unreachable. That is the shape of the prior art in this repo —
  `re-frame.substrate.spine` mints `#js {:adopting true}` per root and
  closes over it in its reporter, and the Freehand root does the same
  while also closing over its root-id."
  []
  #js {"open" true})

(defn adopting?
  "Is `window` open — is THIS root adopting server-rendered DOM?

  **Nil is closed**, and that is the load-bearing default rather than
  defensiveness: an ordinary client mount has no window at all, and a
  subtree with no [[adoption-context]] provider above it reads `nil` and
  answers false. So the absence of an adoption is spelled the same way
  everywhere and there is no third state to reason about."
  [^js window]
  (and (some? window) (true? (.-open window))))

(defn close-adoption-window!
  "Shut `window`. Idempotent, and nil-tolerant for the same reason
  [[adopting?]] is.

  Called by that root's closer component from a passive effect on the
  hydration commit, and again by `impl.mount/unmount!` — a root torn down
  before its passive effect ever ran must not leave an open window
  behind. Both doors belong to the root that minted the window; nothing
  page-wide closes it, because a reset or a sibling's teardown must not
  change the lifecycle state of a root that is still adopting."
  [^js window]
  (when (some? window) (set! (.-open window) false))
  nil)

(defonce ^:private adoption-context
  ;; The window, carried down ONE root's subtree. The default is `nil`,
  ;; which [[adopting?]] reads as closed — so every tree with no provider
  ;; above it (every ordinary mount, every Hicasso boundary anywhere) gets
  ;; the right answer with no provider, no object and no branch. Only a
  ;; hydrating root installs one. This is the shape the compiled tier's
  ;; phase context already uses for the same fact.
  (react/createContext nil))

(defn with-adoption
  "Scope `window` over `element`'s subtree. Renders no DOM of its own, so
  it cannot move a canonical-DOM parity comparison and cannot itself
  mismatch."
  [window element]
  (react/createElement (.-Provider adoption-context) #js {:value window} element))

(defn adopting-here?
  "A hook — is the subtree THIS component renders in being adopted?

  False on every ordinary client mount, false in any root that is not
  hydrating however many siblings are, and false again the moment this
  root's own closer commits. One `useContext` and no other cost, paid
  only by the optional presence component that reads it."
  []
  (adopting? (react/useContext adoption-context)))
