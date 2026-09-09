(ns re-frame.fresco.impl.mount
  "The package's root: one operation that associates a DOM node, a frame
  and a hiccup tree, and an idempotent teardown (HD-021(b),
  docs/design/fresco/decisions.md). `re-frame.fresco`'s root lifecycle
  is spelled here, in TWO tiers. The public grammar is Spec 006 §The
  client root's — `client-root`, `render-client-root!`,
  `unmount-client-root!`, published as `h/client-root`, `h/render!` and
  `h/unmount!` — and it is a thin branch over the impl tier below it:
  `root!` and `hydrate-root!` are the two constructors one first-call
  `:hydrate?` chooses between, `render!` the update path, `unmount!` the
  teardown (docs/design/fresco/product/naming-ledger.md rows 13 and 20).
  Every door is root-scoped: it takes a handle, or makes one, and reaches
  nothing another root owns. `release!` is the fixture door and is not on
  the facade.

  Every door commits before it returns — `render!` inside `flushSync`,
  `settle!` as the empty `flushSync` — because React 19 renders a root
  concurrently and a witness reads the DOM on the next line.
  `hydrate-root!` is the one exception: adoption is React's own concurrent
  business, so it returns before the tree is adopted and a witness waits
  for the adoption window to close. The mechanism record is
  docs/design/fresco/architecture.md, section The root."
  (:require [re-frame.adapter.context :as rf.adapter.context]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.error :refer [fail!]]
            [re-frame.fresco.impl.roots :as rf.fresco.impl.roots]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.trace :as rf.trace]
            ["react" :as react]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]))

(defn provider
  "Scope `frame-kw` for a subtree through the substrate's one internal
  React context (`re-frame.adapter.context/frame-context`), so a Fresco
  subtree and a UIx subtree under one provider resolve the same frame.
  Renders no DOM of its own, so it cannot move a canonical-DOM parity
  comparison."
  [frame-kw child]
  (react/createElement (.-Provider rf.adapter.context/frame-context)
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
  `re-frame.fresco.server/render` builds its element here too, so the
  bytes it emits and the tree `hydrate-root!` adopts agree on `useId`'s
  tree position by construction
  (docs/design/fresco/product/dispositions.md HS-11, obstruction 2;
  witnesses in docs/design/fresco/architecture.md, section The root)."
  [handle hiccup]
  (let [frame-kw (:frame handle)
        element  (rf.fresco.impl.codec/root-element frame-kw hiccup)
        ;; A handle with NO frame is a root whose TREE names its own —
        ;; `h/frame-root` / `h/frame-provider` write the same one context
        ;; this provider would, from inside the tree, so wrapping one
        ;; here as well would put a second Provider fiber above every
        ;; such root for nothing. The impl tier's own witness-driving
        ;; shape still passes a frame and still gets the wrapper.
        app      (if (some? frame-kw) (provider frame-kw element) element)]
    (if-some [window (:adoption handle)]
      (react/createElement (.-Fragment react) nil
                           (react/createElement adoption-window-closer
                                                #js {:rfWindow window})
                           (rf.fresco.impl.roots/with-adoption window app))
      app)))

(defn render!
  "Render `hiccup` into an existing root, synchronously, and answer the
  handle: the IMPL tier's update path, and what a live client-root handle
  reaches on every render after its first. React reconciles
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

(def ^:private root-options-roster
  "Every key a ROOT DOOR's opts map may carry. Two keys, because a root
  door configures the REACT ROOT and nothing else: the frame is the
  TREE's business now (`h/frame-root` / `h/frame-provider`).

  `:hydrate?` picks which React constructor the FIRST render through a
  handle calls; `:identifier-prefix` is React's own `identifierPrefix`,
  an option of both constructors. Both are first-call keys — a later
  `h/render!` through a live handle updates the Root it already owns, so
  neither can be re-read without meaning a second root."
  #{:hydrate? :identifier-prefix})

(def ^:private frame-config-keys
  "The two keys the root door used to own and no longer does. Named
  separately from the unknown-key refusal so a caller who wrote the OLD
  spelling is answered with the NEW one rather than with `is not an
  option`."
  {:frame          "frame-provider {:frame …} (SCOPE — the frame already exists) or frame-root {:id …} (ENSURE — create it if absent)"
   :initial-events "frame-root {:id … :initial-events […]}"})

(defn require-root-options!
  "Refuse a root-door opts map carrying anything outside
  `root-options-roster`.

  A closed list that was *ignored without complaint* is what this
  replaces, and the two refusals are separated because the two mistakes
  are: `:frame` and `:initial-events` are FRAME configuration, which the
  tree now spells, so they are answered with the head that spells them
  (`:rf.error/fresco-frame-config-misplaced`); anything else is an
  option the door does not have
  (`:rf.error/fresco-unknown-root-option`), which is the No-silent-
  swallow rule — `:fx-overrides` handed to a mount used to be dropped on
  the floor, and the whole make-frame-then-join detour existed because of
  it.

  `where` is the door's own facade symbol, so the refusal names
  `h/render!` rather than an impl fn the caller did not write. Answers
  nil."
  [config where]
  (when (some? config)
    (when-not (map? config)
      (fail! :rf.error/fresco-unknown-root-option
             where
             (str "A root door's opts is a MAP of root options, and this one "
                  "is " (pr-str config) ". The shape is "
                  "(h/render! handle tree node opts).")
             {:config config}))
    (doseq [k (keys config)]
      (when-not (contains? root-options-roster k)
        (if-some [head (get frame-config-keys k)]
          (fail! :rf.error/fresco-frame-config-misplaced
                 where
                 (str "The root door no longer configures a frame: " (pr-str k)
                      " belongs in the TREE, on " head ". A root door carries "
                      "ROOT options only (:hydrate?, :identifier-prefix), and "
                      "the frame boundary is a head you write:\n\n"
                      "  (h/render! app-root [h/frame-root {:id :app/main "
                      ":initial-events [[:app/init]]} [root-view]] node)\n\n"
                      "Scoping a frame that already exists — after "
                      "`rf.ssr/hydrate!`, or a second root on the same frame — "
                      "is [h/frame-provider {:frame :app/main} [root-view]].")
                 {:option k :config config})
          (fail! :rf.error/fresco-unknown-root-option
                 where
                 (str (pr-str k) " is not a root option. A root door carries "
                      ":hydrate? — adopt this container's server-rendered DOM "
                      "on the first render through this handle — and "
                      ":identifier-prefix, React's own `identifierPrefix`, so "
                      "a page with two roots can keep their `useId` values "
                      "apart. Frame configuration lives on the `h/frame-root` "
                      "/ `h/frame-provider` head in the tree, and every "
                      "`rf/make-frame` option rides `h/frame-root` whole.")
                 {:option k :roster root-options-roster :config config}))))))

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
  (docs/core/fresco/00-installation.md). Synchronous, and called before
  `createRoot`: `make-frame` drains the seed to a fixed point before it
  returns, so the first paint is the seeded one
  (docs/design/fresco/architecture.md, section The root).

  A NIL `frame-kw` ensures nothing, and is not an error: it is a root
  whose TREE names its own frame — `h/frame-root`, whose ENSURE is core's
  commit-owned two-pass, or `h/frame-provider`. That is the shape the
  PUBLIC doors take; this positional ensure is the impl tier's, kept for
  the witnesses that drive `root!` directly."
  [frame-kw initial-events]
  (when (and (some? frame-kw)
             (nil? (rf.frame/frame-incarnation-token frame-kw)))
    (rf/make-frame (cond-> {:id frame-kw}
                     (seq initial-events) (assoc :initial-events initial-events))))
  frame-kw)

(defn root!
  "Associate `container`, `frame-kw` and `hiccup`: ensure the frame,
  create the React root, render once inside `flushSync`. Returns the
  handle `{:root :frame :container}` every other door takes. The IMPL
  tier's create path: the public grammar reaches it through
  `mount-client-root!`, and the test kit's `catching-root!` drives this
  positional shape directly.

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
  (docs/design/fresco/architecture.md, section The root)."
  [^js props]
  (react/useEffect (fn close-window []
                     (rf.fresco.impl.roots/close-adoption-window! (.-rfWindow props))
                     js/undefined)
                   #js [])
  nil)

;; `unchecked-set`, not `aset`: `aset` is the ARRAY writer and a component
;; is a function. The STRING key is what keeps `displayName` off Closure's
;; renamer under `:advanced`.
(unchecked-set adoption-window-closer "displayName" "fresco/adoption-window-closer")

(defn- report-recoverable-default!
  "React's own default reporting, replicated. Installing any
  `onRecoverableError` takes React's default off, so a reporter that only
  emitted would swallow the error, which the fail-open rule forbids
  (docs/design/fresco/studio/ssr-spike-witness.md, rf2-2rtt6.97)."
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
  (rf.trace/emit! :warning :rf.ssr/hydration-mismatch
               {:error    (some-> error .-message)
                :where    're-frame.fresco.impl.mount/hydrate-root!
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
  mismatch (docs/design/fresco/architecture.md, section The root).
  Attribute-only divergences are outside React's contract and stay
  outside this channel (docs/design/fresco/production-server-arm.md).
  Public so a witness can drive the real callback across the window
  boundary."
  [^js window]
  (fn on-recoverable [error _error-info]
    (when (rf.fresco.impl.roots/adopting? window)
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
                (when rf.interop/debug-enabled? (hydration-reporter window))))

(defn hydrate-root!
  "Associate `container`'s existing server-rendered DOM with `frame-kw`
  and `hiccup` by adoption: `root!`'s hydrating twin, reached from the
  public grammar by `{:hydrate? true}` on the FIRST `h/render!` through a
  handle. Returns `root!`'s handle shape plus `:adoption`, this root's
  own window, and every other door takes a hydrated handle
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
  the server half must be `re-frame.fresco.server/render`, which builds
  its element from the same function
  (docs/design/fresco/product/dispositions.md HS-11, obstruction 2;
  `re-frame.fresco.server-render-ssr-dom-cljs-test`). Mechanism and
  witnesses: docs/design/fresco/architecture.md, section The root."
  ([container frame-kw hiccup] (hydrate-root! container frame-kw hiccup nil))
  ([container frame-kw hiccup opts]
   (let [window  (rf.fresco.impl.roots/open-adoption-window!)
         handle  {:frame frame-kw :container container :adoption window}
         element (tree handle hiccup)
         ropts   (hydrate-root-options window opts)]
     (assoc handle :root (if ropts
                           (react-dom-client/hydrateRoot container element ropts)
                           (react-dom-client/hydrateRoot container element))))))

(defn unmount!
  "Take THIS root down and touch nothing else: the impl tier's teardown,
  `root!`'s inverse. Shuts the root's own adoption window first — a root torn down
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
  `release!`'s (docs/design/fresco/product/globals.md, the
  `reset-runtime!` paragraph)."
  [handle]
  (rf.fresco.impl.roots/close-adoption-window! (:adoption handle))
  (when-some [r (:root handle)]
    (react-dom/flushSync (fn [] (.unmount r))))
  nil)

(defn release!
  "Unmount the root, detach its container and empty the runtime: the
  fixture door, and not on the facade, because it ends with a page that
  holds nothing — right where one test owns the page, wrong for a consumer
  tearing down one of two roots
  (docs/design/fresco/product/naming-ledger.md row 13). Idempotent, so a
  fixture can route through it twice. Not the door a residue assertion
  takes — see `unmount!`."
  [handle]
  (unmount! handle)
  (when-some [c (:container handle)]
    (when-some [p (.-parentNode c)] (.removeChild p c)))
  (rf.fresco.impl.collector/reset-runtime!)
  nil)

;; ---------------------------------------------------------------------------
;; The client root — Spec 006 §The client root, Fresco's realisation
;; ---------------------------------------------------------------------------
;;
;; ONE handle with a first-call mode, in place of the four verbs this
;; package used to spell (rf2-kuky.59). The grammar is the one every React
;; view adapter publishes — `client-root` / `render!` / `unmount!` — and
;; the semantics below are the spec's, proved here against Fresco's OWN
;; root path rather than the spine's: `flushSync` on every update, a
;; per-root adoption window, a per-root recoverable-error reporter, and
;; the stable wrapper tree `tree` puts over every post-hydration render so
;; the client agrees with `re-frame.fresco.server/render` about `useId`.
;;
;; The handle logic is written here rather than taken from
;; `re-frame.substrate.spine/make-client-root-fns` DELIBERATELY: the spine
;; is core's React-HOOK machinery, and one `:require` of it from this
;; namespace would put the whole spine into the bundle of every Fresco
;; application, including the ones that install Reagent, reagent-slim or
;; UIx instead. That is the exact cost `re-frame.fresco.substrate`'s row
;; in `scripts/check_optional_module_reachability.py` exists to keep
;; optional. Twenty lines of handle branch is the cheaper half of that
;; trade, and the SEMANTICS are pinned to the shared ones by test rather
;; than by a shared call.

;; React Roots this package currently keeps mounted, `root` -> its handle.
;; MEMBERSHIP IS THE SINGLE LIVENESS FACT (the rf2-k5r9t rule): no flag in
;; the handle may disagree with it, which is what lets `drain-active-roots!`
;; and a handle's own `unmount!` both reach the host unmount exactly once
;; per root, whichever gets there first.
;;
;; One cell for the package rather than one per adapter, because a Fresco
;; root is created by Fresco's own door whatever adapter is installed —
;; `h/render!` never routes through the substrate contract's `render` slot.
;; The drain is published to core on the `:fresco/drain-client-roots!`
;; late-bind hook below and invoked by
;; `re-frame.substrate.adapter/dispose-adapter!`, which is the PROCESS
;; teardown boundary: `rf/destroy-adapter!` therefore releases these roots
;; whichever adapter an application installed, Fresco over UIx and over
;; Reagent included (rf2-kuky.59).
;;
;; A comment rather than a docstring because CLJS `defonce` takes no
;; docstring — it is `(defonce name expr)` and nothing else.
(defonce ^:private active-roots (atom {}))

(defn- track-active-root!
  "Register `handle`'s Root and return the idempotent release thunk that
  drops it and calls `unmount!`. `unmount!` shuts the root's adoption
  window on the way past, so a root torn down before its closer ran
  leaves no window open."
  [handle]
  (let [root (:root handle)]
    (swap! active-roots assoc root handle)
    (fn release []
      (when (contains? @active-roots root)
        (swap! active-roots dissoc root)
        (unmount! handle)))))

(defn drain-active-roots!
  "Unmount every Root this package still holds and empty the cell: the
  host-resource half of Spec 006 §Adapter disposal lifecycle for Fresco,
  reached from `re-frame.substrate.adapter/dispose-adapter!` through the
  `:fresco/drain-client-roots!` hook published below.

  The cell is emptied FIRST, so a handle whose Root this drain took finds
  itself already released and a `render!` through it mounts afresh. Each
  unmount is caught so one throwing root cannot strand its siblings, and
  the first failure is rethrown once the drain is complete — the shape
  `re-frame.substrate.spine/dispose-active-roots-and-caches!` uses, for
  its reason: a teardown that reported clean over a failed host unmount
  is worse than a late throw."
  []
  (let [live (vals @active-roots)]
    (reset! active-roots {})
    (let [failure (reduce (fn [failure handle]
                            (try (unmount! handle) failure
                                 (catch :default e (or failure e))))
                          nil
                          live)]
      (when failure (throw failure))))
  nil)

;; Published at NS LOAD, not from a once-body, per `late-bind`'s own contract
;; that `hooks` is populated by the producing namespace at load time — the
;; upgrade path a `defonce`-guarded publication breaks is written out at
;; `:live-frame/release-frame-generation-pool!`'s directory row. This
;; namespace is loaded by every door that can create a root, so the hook is
;; bound before the first Root can exist.
(rf.late-bind/set-fn! :fresco/drain-client-roots! drain-active-roots!)

(defn client-root
  "Allocate an inert client-root handle: `h/client-root`. No DOM work and
  no React call, so it is safe at namespace load under a `defonce`."
  []
  (atom nil))

(defn- mount-client-root!
  "The FIRST render through a handle: create or adopt this handle's one
  Root from `mount-point` and `opts`, track it, and answer the live-root
  map `render-client-root!` drives —
  `{:live? :update! :unmount!}`.

  `{:hydrate? true}` takes `hydrate-root!`, which installs this root's
  own adoption window and (in debug builds) its own recoverable-error
  reporter; anything else takes `root!`, which renders inside `flushSync`.
  Either way the frame slot is `nil`: post-views.3 the frame is spelled in
  the TREE, on `h/frame-root` or `h/frame-provider`."
  [render-tree mount-point opts]
  (let [handle (if (:hydrate? opts)
                 (hydrate-root! mount-point nil render-tree opts)
                 (root! mount-point nil render-tree opts))]
    {:live?    (fn live? [] (contains? @active-roots (:root handle)))
     :update!  (fn update! [next-tree] (render! handle next-tree) nil)
     :unmount! (track-active-root! handle)
     ;; This root's own adoption window, or nil for a created root. It
     ;; rides the live-root map so a WITNESS can wait on the completion
     ;; signal a hydrating first render has instead of a flush — the
     ;; public handle is opaque to consumers, and the impl tier is the
     ;; tier a witness may read. It is a reading, never a flag: `tree`
     ;; still holds the window on the handle it wraps, and the two are
     ;; the same object rather than two facts that could disagree.
     :adoption (:adoption handle)}))

(defn render-client-root!
  "`h/render!`. The first call through `handle` creates (or, with
  `{:hydrate? true}`, adopts) one Root at `mount-point`; every later call
  UPDATES that same Root with `render-tree`, inside `flushSync`, so
  component state, scroll position and the hydration adoption survive and
  no second constructor ever runs. `mount-point` and `opts` are read on
  the first call only — a later `{:hydrate? true}` is ignored rather than
  hydrating twice. Answers nil."
  ([handle render-tree mount-point]
   (render-client-root! handle render-tree mount-point nil))
  ([handle render-tree mount-point opts]
   (require-root-options! opts 're-frame.fresco/render!)
   (let [live @handle]
     (if (and live ((:live? live)))
       ((:update! live) render-tree)
       (reset! handle (mount-client-root! render-tree mount-point opts))))
   nil))

(defn unmount-client-root!
  "`h/unmount!`. Release the Root `handle` holds and return it to inert.
  Idempotent, and a no-op on a handle whose Root `drain-active-roots!`
  already took — liveness is the active set's to say, never the handle's.
  Answers nil."
  [handle]
  (when-let [live @handle]
    (reset! handle nil)
    ((:unmount! live)))
  nil)

(defn dispatch!
  "Dispatch through the package's synchronous door and commit the echo.
  The witness door; an intent written in a view reaches
  `collector/dispatch!` on its own.

  `target` is the FRAME KEYWORD, or a handle that names one. A handle
  names one only when it came from the impl tier's positional shape: a
  root mounted through `h/render!` scopes its frame in the TREE
  (`h/frame-root` / `h/frame-provider`), so its handle names no frame and
  a witness driving one names the frame itself — which is the honest
  spelling anyway, since a root can hold more than one boundary."
  [target event]
  (rf.fresco.impl.collector/dispatch!
    (if (keyword? target) target (:frame target))
    event)
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
