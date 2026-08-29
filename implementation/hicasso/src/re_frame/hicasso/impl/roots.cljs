(ns re-frame.hicasso.impl.roots
  "THE HYDRATION ADOPTION WINDOW — one window PER ROOT, and the three
  doors that move it.

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

  ## PER ROOT, never page-wide — the constraint the code cannot show

  Nothing about one root's lifecycle may be visible in another's. Roots
  on one page are independent by React's own design —
  `onRecoverableError` is an option of an *individual* `hydrateRoot` —
  so no page-wide state may stand in for a per-root window, and any
  attempt to share one makes correctness rest on the relative ordering of
  commits, passive effects, presence renders and recoverable-error
  callbacks across roots that have no ordering guarantee between them.

  Two things break the moment that ordering is relied on. A single shared
  flag is opened N times by N overlapping adoptions and shut by the
  FIRST close, so a second root's divergence raises React's
  `Hydration failed` while its `:rf.ssr/hydration-mismatch` is discarded
  — the instrumentation stream then shows a healthy root that never
  adopted. And an unrelated ORDINARY root's presence tray is told it is
  adopting for as long as ANY root hydrates, losing its enter
  transition.

  A page-global REFERENCE COUNT is not the alternative: it keeps a second
  root's window open when the first commits, so a later recoverable error
  from the already-committed root is falsely labelled a hydration
  mismatch, and it still leaks adoption semantics into presence
  components outside both hydrating subtrees. It trades one interference
  for another."
  (:require ["react" :as react]))

(defn open-adoption-window!
  "Open a window and answer it — the ONLY handle on it there is.

  TWO MINTERS, and each hands the window to the same consumer —
  `impl.mount/tree`, which scopes it over the subtree with
  [[with-adoption]] when the handle carries an `:adoption`:

  - `impl.mount/hydrate-root!`, once per hydrating client root; and
  - `re-frame.hicasso.server/render`, once per REQUEST, closed in that
    door's `finally`. Because both call `tree`, the fork is decided once
    for both sides of the wire.

  A render that neither door took — a hand-rolled `renderToString` over
  the app element — still finds no provider above it, reads the adoption
  context's `nil` default, and [[adopting?]] calls that closed. Both
  paths are measured in
  `re-frame.hicasso.presence-ssr-seam-dom-cljs-test`: §1 the windowless
  one, §5 the product door.

  Born open: the renders that follow it are adopting server-rendered
  DOM until something calls [[close-adoption-window!]] on this very
  object.

  A bare mutable ref rather than a registry entry, and deliberately.
  A registry keyed by frame cannot work — several roots may intentionally
  share a frame — and one keyed by container recreates React context
  badly and adds a cleanup path that can leak. The ref is reachable only
  from the root that minted it, so a root whose construction throws
  leaves nothing behind to close: the window it would have opened is
  simply unreachable. `re-frame.substrate.spine` carries the same shape,
  minting `#js {:adopting true}` per root and closing over it in its
  reporter."
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
  ;; hydrating root installs one — the same absence-means-closed discipline
  ;; `re-frame.substrate.spine` holds its root-local adoption flag to.
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
