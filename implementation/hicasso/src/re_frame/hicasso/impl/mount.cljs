(ns re-frame.hicasso.impl.mount
  "The package's root: one operation that associates a DOM node, a frame
  and a hiccup tree, and an idempotent teardown (HD-021(b),
  docs/design/hicasso/decisions.md). `re-frame.hicasso`'s root lifecycle
  is spelled here — `root!` and `hydrate-root!` are published as
  `h/mount!` and `h/hydrate!` over the guide's `(node config view)` shape,
  `render!` and `unmount!` under their own names
  (docs/design/hicasso/product/naming-ledger.md rows 13 and 20). Every
  door is root-scoped: it takes a handle, or makes one, and reaches
  nothing another root owns. `release!` is the fixture door and is not on
  the facade.

  Every door commits before it returns — `render!` inside `flushSync`,
  `settle!` as the empty `flushSync` — because React 19 renders a root
  concurrently and a witness reads the DOM on the next line.
  `hydrate-root!` is the one exception: adoption is React's own concurrent
  business, so it returns before the tree is adopted and a witness waits
  for the adoption window to close. The mechanism record is
  docs/design/hicasso/architecture.md, section The root."
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
  "Scope `frame-kw` for a subtree through the substrate's one internal
  React context (`re-frame.adapter.context/frame-context`), so a Hicasso
  subtree and a UIx subtree under one provider resolve the same frame.
  Renders no DOM of its own, so it cannot move a canonical-DOM parity
  comparison."
  [frame-kw child]
  (react/createElement (.-Provider adapter-context/frame-context)
                       #js {:value frame-kw}
                       child))

(defn settle!
  "Let an already-scheduled sync-lane notification commit: the empty
  `flushSync`, and the reason a witness may read the DOM on the line after
  a dispatch. Not `act`, which diverts work to a queue that is not the
  browser's."
  []
  (react-dom/flushSync (fn [] nil))
  nil)

(declare adoption-window-closer)

(defn tree
  "The root element for `handle`'s next render, and the one place the root
  tree's shape is decided. A hydrated root — one whose handle carries an
  `:adoption` window — is a Fragment of `adoption-window-closer` and the
  app subtree under the window's provider; an ordinary root is the bare
  frame provider, so the tree the bench lane measures carries no extra
  fiber, effect or context.

  A hydrated root must be handed this wrapper on EVERY render, not only
  the adopting one: React reconciles a root by its top element, so a bare
  provider where the Fragment stood is a different tree, and React
  unmounts the adopted subtree and mounts a fresh one — every node, cell
  and subscription the adoption established discarded. The window's
  presence is the one fact that says a root is hydrated; there is no
  second flag to disagree with it. Public because
  `re-frame.hicasso.server/render` builds its element here too, so the
  bytes it emits and the tree `hydrate-root!` adopts agree on `useId`'s
  tree position by construction
  (docs/design/hicasso/product/dispositions.md HS-11, obstruction 2;
  witnesses in docs/design/hicasso/architecture.md, section The root)."
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
  "Render `hiccup` into an existing root, synchronously, and answer the
  handle: the public re-render (`h/render!`) and the whole of a consumer's
  hot-reload hook (docs/core/hicasso/00-installation.md). React reconciles
  against the tree on the page, so component state and scroll position
  survive; calling `root!` again would `createRoot` a second time and
  replace the tree. The root hiccup goes through `codec/root-element`
  rather than `as-element` because the root is the one creator with no
  ancestor body to inherit the frame from. Takes a hydrated handle
  unchanged — `tree` is what makes that true."
  [handle hiccup]
  (react-dom/flushSync (fn [] (.render (:root handle) (tree handle hiccup))))
  handle)

(defn- root-options
  "React's `react-dom/client` root options object, or nil when there is
  nothing to say. Nil rather than an empty object, because both root doors
  branch on it to call React's bare arity — the shipped production call
  once the reporter compiles away. String keys through `unchecked-set`:
  the string is what keeps the property off Closure's renamer under
  `:advanced`, and an `identifierPrefix` renamed is a prefix React never
  sees."
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
  "Associate `container`, `frame-kw` and `hiccup`: ensure the frame,
  create the React root, render once inside `flushSync`. Returns the
  handle `{:root :frame :container}` every other door takes. Published as
  `h/mount!` over the guide's config map.

  `opts` is optional and carries two keys, both the caller's:
  `:initial-events`, ordinary events dispatched in order when this mount
  CREATES the frame and never when it joins one (`ensure-frame!`); and
  `:identifier-prefix`, handed to `createRoot` as React's
  `identifierPrefix` untouched — no default, no coercion — so a page
  mounting two roots can keep their `useId` values apart. Name neither and
  the call React receives is the bare one."
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
  "The component that closes its own root's adoption window, from a
  passive `useEffect` with empty deps — once, and strictly after the
  commit that adopted the server DOM. Renders nil, so it adds nothing for
  `hydrateRoot` to match. The window arrives as the `rfWindow` prop rather
  than from a module slot, so a closer can only shut the window its own
  root minted and a sibling still adopting stays adopting. Public because
  `(roots/adopting? (:adoption handle))` turning false is this effect
  having run — the completion signal a witness waits on in place of the
  `flushSync` `hydrate-root!` refuses. The shape is
  `re-frame.substrate.spine/adoption-window-closer`'s
  (docs/design/hicasso/architecture.md, section The root)."
  [^js props]
  (react/useEffect (fn close-window []
                     (roots/close-adoption-window! (.-rfWindow props))
                     js/undefined)
                   #js [])
  nil)

;; `unchecked-set`, not `aset`: `aset` is the ARRAY writer and a component
;; is a function. The STRING key is what keeps `displayName` off Closure's
;; renamer under `:advanced`.
(unchecked-set adoption-window-closer "displayName" "hicasso/adoption-window-closer")

(defn- report-recoverable-default!
  "React's own default reporting, replicated. Installing any
  `onRecoverableError` takes React's default off, so a reporter that only
  emitted would swallow the error, which the fail-open rule forbids
  (docs/design/hicasso/studio/ssr-spike-witness.md, rf2-2rtt6.97)."
  [error]
  (if (fn? (.-reportError js/globalThis))
    (js/reportError error)
    (when (exists? js/console) (.error js/console error))))

(defn- emit-hydration-mismatch!
  "Spec 011's `:rf.ssr/hydration-mismatch`, `:where` naming this door the
  way the spine's `make-render` tags its own. No hash and no `:root-id` —
  a React-element root has neither — and `:recovery` is
  `:warned-and-replaced` because React has already patched the DOM. Not
  an event and mints no epoch: it fires from a root-error callback,
  outside any dispatch scope."
  [error]
  (trace/emit! :warning :rf.ssr/hydration-mismatch
               {:error    (some-> error .-message)
                :where    're-frame.hicasso.impl.mount/hydrate-root!
                :recovery :warned-and-replaced}))

(defn hydration-reporter
  "Build the `onRecoverableError` for the root that owns `window`: emit
  the framework diagnostic while THAT root's window is open, then always
  delegate to React's default reporting. Without it a mismatch is an
  uncaught window error and nothing else — Spec 011's
  `:rf.ssr/hydration-mismatch` never fires. A builder, as the spine's
  `native-hydration-reporter` is, because the window belongs to one root
  and the callback has to close over it.

  Per-root rather than page-wide because both failure directions are
  real: React fires this callback for post-hydration recoveries too, so a
  page-wide window would label a completed root's later recovery a
  hydration mismatch whenever a sibling was still adopting, and a
  page-wide boolean lets one root's closer silence another's genuine
  mismatch (docs/design/hicasso/architecture.md, section The root).
  Attribute-only divergences are outside React's contract and stay
  outside this channel (docs/design/hicasso/production-server-arm.md).
  Public so a witness can drive the real callback across the window
  boundary."
  [^js window]
  (fn on-recoverable [error _error-info]
    (when (roots/adopting? window)
      (emit-hydration-mismatch! error))
    (report-recoverable-default! error)))

(defn- hydrate-root-options
  "The root options for the root owning `window`, or nil. The reporter is
  debug-only — in production the emit compiles away behind
  `interop/debug-enabled?` and what would remain is a replica of React's
  default — while `:identifier-prefix` is never gated: it is behaviour,
  deciding what `useId` answers, and a release build that dropped it would
  hydrate every server `useId` into a mismatch."
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
  (docs/design/hicasso/product/dispositions.md HS-11, obstruction 2;
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
  "Take THIS root down and touch nothing else: `h/unmount!`, `root!`'s
  inverse. Shuts the root's own adoption window first — a root torn down
  before its passive effects ran never gets its closer — then unmounts
  inside `flushSync`. Idempotent and nil-tolerant: an ordinary handle
  carries no window, and a second call is a no-op.

  Two things it deliberately does not do. It empties none of the
  runtime's tables — they are one-per-page and keyed by frame, so a reset
  here would tear down every sibling root's state, and what survives an
  unmount is exactly what a residue gate reads, which a teardown that
  emptied the tables first could never turn red. And it does not remove
  the container: that is the caller's node, and React's own
  `root.unmount()` empties it and leaves it where it is. Both are
  `release!`'s (docs/design/hicasso/product/globals.md, the
  `reset-runtime!` paragraph)."
  [handle]
  (roots/close-adoption-window! (:adoption handle))
  (when-some [r (:root handle)]
    (react-dom/flushSync (fn [] (.unmount r))))
  nil)

(defn release!
  "Unmount the root, detach its container and empty the runtime: the
  fixture door, and not on the facade, because it ends with a page that
  holds nothing — right where one test owns the page, wrong for a consumer
  tearing down one of two roots
  (docs/design/hicasso/product/naming-ledger.md row 13). Idempotent, so a
  fixture can route through it twice. Not the door a residue assertion
  takes — see `unmount!`."
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
