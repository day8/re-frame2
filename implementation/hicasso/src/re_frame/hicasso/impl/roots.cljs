(ns re-frame.hicasso.impl.roots
  "THE HYDRATION ADOPTION WINDOW (rf2-2rtt6.84) — one boolean, and the
  three doors that move it (rf2-hic-009).

  The window is what tells a render *whether the DOM it is about to
  produce is already on the screen*. `re-frame.hicasso.impl.mount` opens
  it around `hydrateRoot` and the root's closer component shuts it on the
  hydration commit; `re-frame.hicasso.impl.presence-react` is the one
  reader, and it reads it during a RENDER.

  It is its own namespace because the flag is **module-level and the
  runtime is multi-root**, and those two facts are in tension. Today that
  tension is stated rather than engineered around (see [[adoption]]);
  rf2-hic-012 is the bead that has to resolve it, by making adoption
  root-scoped. A boundary drawn here means that bead edits one small file
  whose entire contents are the thing it is changing, rather than
  thirty lines inside two thousand — and means the reviewer of that
  change can see the whole of the before at once.

  The root LIFECYCLE proper — `root!`, `hydrate-root!`, `render!`,
  `unmount!`, `release!` — lives in `re-frame.hicasso.impl.mount`, which
  the copy brought over as its own file and which rf2-hic-009 therefore
  did not carve. The two files together are rf2-hic-012's surface.")

(def ^:private ^js adoption
  "Is this page's tree being ADOPTED from server-rendered HTML right now?

  Opened by [[re-frame.hicasso.impl.mount/hydrate-root!]] before it
  calls `hydrateRoot`, and closed by that root's closer component from a
  passive effect on the hydration commit — the spine's own pattern
  (`re-frame.substrate.spine/adoption-window-closer`), whose whole point
  is that a passive effect runs strictly AFTER the commit and therefore
  cannot close the window early.

  **Module-level, like every other slot in this arm.** The collector's
  `rstate`, its scratch and its cell table are one-per-page too. A page
  hydrates once, at boot, so one window is the shape the case has; a
  second hydrating root opened while the first window is still open would
  share it, and that limit is stated rather than engineered around. The
  spine keeps its flag root-local because it hands the flag to its closer
  as a prop — doing the same here would put a prop or a context read on
  `impl.presence-react`, which is a cost this arm has no case for.

  A boolean and nothing else: it selects **adoption behaviour** in a
  renderer that still has exactly one render mode (the charter's
  one-mode law). Nothing branches on it but presence's born-present
  seeding."
  #js {"open" false})

(defn adopting?
  "Is a hydration adoption in flight?

  False on every ordinary client mount, and false again the moment the
  adopted root's closer commits. A server render entry opens the same
  window around its own `renderToString`, so the two halves of an SSR
  route answer this identically — which is what stops the client's first
  pass from rendering something the server did not."
  []
  (.-open adoption))

(defn open-adoption-window!
  "Declare the renders that follow to be adopting server-rendered DOM."
  []
  (set! (.-open adoption) true)
  nil)

(defn close-adoption-window!
  "Close the window. The closer component's passive effect calls this,
  and so does [[re-frame.hicasso.impl.collector/reset-runtime!]] — a
  fixture that throws mid-hydration must not leave the page permanently
  adopting."
  []
  (set! (.-open adoption) false)
  nil)
