(ns re-frame.ui.client
  "Client kernel of the re-frame.ui substrate — root identity + the mount
  surface (S1c, rf2-vxgfnd.3; the root-identity-and-mount contract).

  What lives here:

    - the `Root` handle (React root + container + root-id — identity is
      immutable for the Root's lifetime);
    - the LAYER-3 live-root registry: a per-document map root-id -> entry.
      `create-root` / `mount` register their root-id BEFORE any render;
      registering an id already live throws `:rf.error/duplicate-root-id`
      with the existing root untouched (failure isolation) — the last line
      of the three-layer duplicate-detection contract (§7). Container
      ownership rides the same registry: a node already owned by a
      different live root is `:rf.error/root-container-in-use`; a nil
      container is `:rf.error/root-container-missing`; and `render!` on a
      STALE handle (a root-id no longer mapped to that Root — `unmount!`ed
      or superseded by a newer root claiming the same id) is
      `:rf.error/root-not-live`, the render-side mirror of `unmount!`'s
      membership guard (thrown before any side effect);
    - the runtime halves of `ui/mount` / `ui/create-root` / `ui/render!` /
      `ui/hydrate-root` / `ui/unmount!` (the compile halves live in
      `re-frame.ui.compiler.root`);
    - the LIVE PREFLIGHT (S2c, rf2-vxgfnd.9): frame ENSURE +
      `:initial-events` drain semantics are owned by Spec 002; at every
      mount/render! the root descriptor's static frame plans execute
      through `re-frame.ui.frames/execute-frame-plans!` BEFORE the React
      root is created and before any render (03 §8 — ENSURE is host
      preflight, never render). Plan config expressions evaluate exactly
      at preflight. `set-preflight-hook!` remains as the test/tool
      OVERRIDE seam (a capture hook replaces the live executor).

  ## Q49 RULING (ENSURE retry-after-preflight-failure)

  A preflight failure FAILS THE MOUNT LOUDLY and the container is
  untouched: `mount*` runs preflight AFTER the Layer-3 claim checks but
  BEFORE `createRoot`, before the live-root registration, and before any
  render — a throwing plan leaves no React root, no registry entry, and
  (per the engine's zero-residue guarantee) no frame residue. RETRY =
  the host re-calls `mount` / `render!`; there is NO auto-retry. Plans
  that installed before the failure are found live on retry and do not
  re-seed. A failing RE-preflight (idempotent re-mount / `render!` on a
  live root) leaves the existing root and its last committed render
  untouched. Pinned by the G-4/G-6 fixtures
  (`re-frame.ui.preflight-frame-wiring-dom-cljs-test`).

  S1 scope notes: hydration manifests land S5, so `hydrate-root*` fails
  loud (`:rf.error/root-manifest-invalid`) — there is no server to have
  emitted a manifest yet (hydrate preflight will share the same
  `execute-frame-plans!` path when it lands). Descriptors + site coords
  ride the registry in dev only (goog.DEBUG — production carries no
  manifests, I-12); root-id and container ownership are load-bearing
  identity and stay in production."
  (:require ["react-dom/client" :as rdc]
            [re-frame.error :as error]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.reactive :as reactive]))

;; ---------------------------------------------------------------------------
;; The Root handle
;; ---------------------------------------------------------------------------

(deftype Root [react-root container root-id])

(defn root-id-of
  "The root-id a Root was created under (immutable for its lifetime)."
  [^Root root]
  (.-root-id root))

;; ---------------------------------------------------------------------------
;; Layer 3 — the per-document live-root registry
;; ---------------------------------------------------------------------------

(defonce ^:private live-roots
  ;; root-id -> {:root Root :container node :provenance kw
  ;;             :identifier-prefix str|nil :site {..}|nil :descriptor {..}|nil}
  ;; The effective identifier-prefix rides the entry so claim-time uniqueness
  ;; (rf2-ez3fqk) reads it and release frees it by dissoc — no side index.
  (atom {}))

(defn live-root-ids
  "The set of root-ids currently live in this document (tool/test read)."
  []
  (set (keys @live-roots)))

(defn live-root-entry
  "The registry entry for `root-id` (tool/test read):
  `{:root :container :provenance :site :descriptor}` or nil."
  [root-id]
  (get @live-roots root-id))

(defn reset-live-roots!
  "Test support: wipe the live-root registry. Does NOT unmount React
  roots — unmount! them first (or use fresh containers per test)."
  []
  (reset! live-roots {})
  nil)

(defn- container-owner
  "root-id of the live root owning `container` (identical?), nil if
  unclaimed."
  [container]
  (some (fn [[id entry]]
          (when (identical? (:container entry) container) id))
        @live-roots))

(defn- prefix-owner
  "root-id of a DIFFERENT live root already claiming effective
  identifierPrefix `prefix` (`=`), nil if unclaimed. Only non-nil stored
  prefixes are compared, so entries registered without an effective prefix
  (bare test infos) never alias (rf2-ez3fqk)."
  [prefix root-id]
  (some (fn [[id entry]]
          (when (and (not= id root-id)
                     (= prefix (:identifier-prefix entry)))
            id))
        @live-roots))

(defn require-container!
  "`:rf.error/root-container-missing` on a nil container — mount /
  create-root need a live element (the S5 hydration arm — a manifest
  locator resolving to no element — lands with server rendering)."
  [where root-id container]
  (when (nil? container)
    (error/throw-error!
     :rf.error/root-container-missing where
     (str "the container DOM node for root " (pr-str root-id) " is nil — "
          "mount/create-root need a live element (did the lookup return "
          "nothing?)")
     {:recovery :supply-a-live-container
      :extra {:root-id root-id}})))

(defn check-root-claim!
  "The Layer-3 checks, BEFORE any render (the existing root is untouched
  on failure): a root-id already live throws
  `:rf.error/duplicate-root-id`; a container owned by a DIFFERENT live
  root throws `:rf.error/root-container-in-use`; and a DIFFERENT live root
  already using this root's effective `identifierPrefix` throws
  `:rf.error/duplicate-identifier-prefix` (rf2-ez3fqk — the derived default
  prefix is injective over root-id, so this backstops AUTHORED
  `:identifier-prefix` opts that can still alias distinct roots and collide
  `use-id` output). The prefix arm is a no-op when no effective prefix is
  present (bare infos)."
  [where {:keys [root-id provenance site identifier-prefix]} container]
  (require-container! where root-id container)
  (when-let [existing (get @live-roots root-id)]
    (error/throw-error!
     :rf.error/duplicate-root-id where
     (str "root-id " (pr-str root-id) " is already live in this document — "
          "root-ids are page-unique identity. "
          (if (= :derived (:provenance existing) provenance)
            "both ids derived from the same view — add :disambiguator or author :root-id"
            "unmount! the existing root, or author a distinct :root-id"))
     {:recovery :make-root-ids-unique
      :extra {:root-id  root-id
              :existing (select-keys existing [:provenance :site])
              :arriving {:provenance provenance :site site}}}))
  (when-let [owner (container-owner container)]
    (error/throw-error!
     :rf.error/root-container-in-use where
     (str "the container for root " (pr-str root-id) " is already owned by "
          "live root " (pr-str owner) " — one container, one root. "
          "unmount! the owning root first, or mount into a different node")
     {:recovery :unmount-the-owning-root-first
      :extra {:root-id root-id :owner-root-id owner}}))
  (when (some? identifier-prefix)
    (when-let [owner (prefix-owner identifier-prefix root-id)]
      (error/throw-error!
       :rf.error/duplicate-identifier-prefix where
       (str "root " (pr-str root-id) " uses identifierPrefix "
            (pr-str identifier-prefix) ", already claimed by live root "
            (pr-str owner) " — two live roots sharing a prefix collide "
            "React's use-id output. The DERIVED default is unique per "
            "root-id; give one root a distinct :identifier-prefix")
       {:recovery :make-identifier-prefixes-unique
        :extra {:root-id root-id
                :identifier-prefix identifier-prefix
                :owner-root-id owner}})))
  nil)

(defn require-stable-identifier-prefix!
  "The same-root/same-container re-mount guard for the effective React
  `identifierPrefix` (rf2-vxgfnd.59). A live root's `identifierPrefix` is
  fixed at `createRoot` — React root options are IMMUTABLE for the root's
  lifetime — so a re-mount (an HMR edit) that AUTHORS a different effective
  prefix for the same root-id / container cannot be applied to the running
  root. Silently reusing the old option would be dishonest: the authored
  value never takes effect and React's `use-id` keeps emitting the old
  prefix. A changed prefix therefore FAILS LOUD with
  `:rf.error/root-identifier-prefix-immutable`, thrown BEFORE preflight so
  the live root and its last committed render are untouched and no
  `:initial-events` drain runs. Equal prefixes (the common idempotent
  re-mount, and every non-prefix HMR edit) pass; a no-op when EITHER side
  lacks an effective prefix (bare test/tool infos), mirroring the
  `check-root-claim!` prefix arm — the compiled mount path always supplies a
  derived-or-authored effective prefix, so a nil arises only from a bare
  info and never expresses a real prefix change."
  [where root-id requested existing]
  (when (and (some? requested) (some? existing) (not= requested existing))
    (error/throw-error!
     :rf.error/root-identifier-prefix-immutable where
     (str "re-mount of root " (pr-str root-id) " requested identifierPrefix "
          (pr-str requested) ", but this live root was created with "
          (pr-str existing) " — a root's identifierPrefix is fixed at "
          "createRoot (React root options are immutable for the root's "
          "lifetime), so a re-mount / HMR edit cannot change it in place and "
          "the old prefix is still in effect. unmount! this root, then mount "
          "again to adopt the new identifierPrefix")
     {:recovery :unmount-before-changing-identifier-prefix
      :extra {:root-id   root-id
              :requested requested
              :existing  existing}})))

(defn register-live-root!
  "Register a claimed root (checks already passed) — before any render.
  The effective `:identifier-prefix` rides the entry so a later claim can
  assert prefix uniqueness and release frees it by dissoc (rf2-ez3fqk)."
  [{:keys [root-id provenance site descriptor identifier-prefix]} container root]
  (swap! live-roots assoc root-id {:root root
                                   :container container
                                   :provenance provenance
                                   :identifier-prefix identifier-prefix
                                   :site site
                                   :descriptor descriptor})
  nil)

(defn release-root!
  "Unregister `root-id` iff the entry still belongs to `root` (a stale
  handle never evicts a newer claim)."
  [root-id root]
  (swap! live-roots
         (fn [m]
           (if (identical? (:root (get m root-id)) root)
             (dissoc m root-id)
             m)))
  nil)

(defn require-live-root!
  "The shared live-root OWNERSHIP invariant — the render-side mirror of
  `unmount!*`'s membership check. A `Root` is LIVE iff the live-root
  registry still maps its root-id to THIS exact Root; a handle whose id
  was `unmount!`ed (absent entry) or SUPERSEDED by a newer root claiming
  the same id (entry points at a different Root) is STALE. `unmount!` on
  a stale root is an idempotent no-op, but a render fails LOUD with
  `:rf.error/root-not-live`: a stale/superseded root can never commit a
  render, so writing a descriptor or `.render`ing into it would be side
  effects with NO committed render.

  ONE invariant, THREE call sites (rf2-vxgfnd.52, rf2-vxgfnd.69):

    1. `render!*` PRE-preflight — the stale-handle guard: a handle already
       dead before this call fails before running its frame preflight (the
       `:initial-events` drain — IRREVERSIBLE fx) or writing install
       records against a dead root-id.
    2. `render!*` POST-preflight and 3. `mount*`'s same-root fast path
       POST-preflight — the re-entrancy revalidation: `run-preflight!`
       drains `:initial-events` SYNCHRONOUSLY (arbitrary app code that may
       `unmount!` this root and mount a replacement under the same
       id/container), so the captured handle can be superseded MID-CALL.
       Re-checking here — before the descriptor write or `.render` — means
       a stale render fails loud and leaves the replacement root wholly
       untouched, mirroring the fresh-mount re-check.

  Thrown BEFORE the descriptor write / `.render`; retry = `create-root` +
  `render!` (or `mount`) a fresh root."
  [^Root root where]
  (let [rid (.-root-id root)]
    (when-not (identical? (:root (get @live-roots rid)) root)
      (error/throw-error!
       :rf.error/root-not-live where
       (str "the Root handle for root-id " (pr-str rid) " is no longer the "
            "live root for that id — it was unmount!ed, or superseded by a "
            "newer root claiming the same id (possibly during this "
            "operation's frame preflight, which drains :initial-events — "
            "arbitrary app code). A superseded root can never commit a "
            "render; rendering into it would drain :initial-events "
            "(irreversible fx) and write install records against a dead "
            "root. create-root + render! (or mount) a fresh root")
       {:recovery :recreate-the-root
        :extra {:root-id rid}}))))

;; ---------------------------------------------------------------------------
;; The preflight — LIVE (S2c); the hook stays as the test/tool override
;; ---------------------------------------------------------------------------

(defonce ^:private preflight-hook (atom nil))

(defn set-preflight-hook!
  "Install a frame-preflight OVERRIDE `(fn [root-id plans])` — plans are
  `[{:frame-id .. :config-fingerprint .. :config {evaluated}}]` in
  document order. The live default (no hook) is the real ENSURE +
  `:initial-events` drain, `re-frame.ui.frames/execute-frame-plans!`
  (semantics owned by Spec 002); tests install capture hooks to observe
  plans without touching the frames registry. Returns the previous hook."
  [f]
  (let [prev @preflight-hook]
    (reset! preflight-hook f)
    prev))

(defn run-preflight!
  "Execute a root's frame preflight over its plans-thunk (nil thunk = no
  static plans = nothing to do). Plan config expressions evaluate exactly
  here — at preflight, before any React work (03 §8). Routes to the
  installed override hook when one is set, else the live executor. A
  throw propagates to the mount/render! caller (the Q49 ruling — see the
  ns docstring)."
  [root-id plans-thunk]
  (when plans-thunk
    (let [plans (plans-thunk)
          f     (or @preflight-hook frames/execute-frame-plans!)]
      (f root-id plans)))
  nil)

;; ---------------------------------------------------------------------------
;; React root options
;; ---------------------------------------------------------------------------

(defn root-options
  "Build the react-dom/client root options object: identifierPrefix + the
  host error callbacks (plain fns handed to React, invoked OUTSIDE the
  re-frame2 commit path — the §02 §3 handler boundary law does not apply
  to this host-tier surface)."
  [identifier-prefix on-uncaught on-caught on-recoverable]
  (let [o (js-obj)]
    (when (some? identifier-prefix)
      (unchecked-set o "identifierPrefix" identifier-prefix))
    (when (some? on-uncaught)
      (unchecked-set o "onUncaughtError" on-uncaught))
    (when (some? on-caught)
      (unchecked-set o "onCaughtError" on-caught))
    (when (some? on-recoverable)
      (unchecked-set o "onRecoverableError" on-recoverable))
    o))

;; ---------------------------------------------------------------------------
;; The mount surface — runtime halves
;; ---------------------------------------------------------------------------

(defn mount*
  "Runtime half of `ui/mount` — claim checks + frame preflight +
  create-root + render!, one-shot, IDEMPOTENT PER ROOT: called again with
  the same root-id and the same container it re-runs preflight (frames
  found live, no re-seed — the guide-01 reload path) and re-renders the
  existing Root. Same root-id on a DIFFERENT container fails loud
  (`:rf.error/duplicate-root-id`); a container owned by a different live
  root is `:rf.error/root-container-in-use`.

  ORDER (the Q49 ruling — ns docstring): claim checks, then preflight,
  then a RE-CHECK of the claim, then `createRoot` + registration + render.
  A preflight failure therefore fails the mount loudly with the container
  untouched — no React root, no live-root registration, no render; retry =
  re-call `mount`. The re-check (rf2-vxgfnd.52) closes the re-entrancy
  window that preflight opens: `run-preflight!` drains `:initial-events`
  synchronously (arbitrary app code) and a re-entrant mount can claim this
  root-id / container / identifier-prefix while this mount is still
  unregistered; the second `check-root-claim!` detects that ownership
  change and fails loud BEFORE the unconditional register, rather than
  clobbering the inner root's entry (which the rollback would then delete,
  orphaning the inner root's live tree). Registration still precedes any
  render (contract §7 Layer 3);
  if that FIRST render (element thunk or host `.render`) throws, the mount
  rolls back TOTALLY — the exact claim is released and the host root is
  best-effort unmounted, so a retry starts clean (no phantom live root),
  and the original error is rethrown even if cleanup also throws. A failed
  RE-render on an already-live root is the distinct case: the existing
  root stays registered with its last committed render intact (Q49).
  Returns the Root."
  [{:keys [root-id] :as info} container element-thunk react-opts plans-thunk]
  (require-container! 're-frame.ui/mount root-id container)
  (let [existing (get @live-roots root-id)]
    (if (and existing (identical? (:container existing) container))
      (let [^Root root (:root existing)]
        ;; rf2-vxgfnd.59: the effective identifierPrefix is IMMUTABLE for a
        ;; live root (React root options are fixed at createRoot). A same-root
        ;; re-mount that authored a DIFFERENT effective prefix fails loud
        ;; BEFORE preflight — the running root cannot adopt it, and silently
        ;; reusing the old option (the pre-fix behaviour) is dishonest.
        (require-stable-identifier-prefix!
         're-frame.ui/mount root-id
         (:identifier-prefix info) (:identifier-prefix existing))
        ;; re-preflight BEFORE the re-render: a failure leaves the live
        ;; root and its last committed render untouched (Q49).
        (run-preflight! root-id plans-thunk)
        ;; rf2-vxgfnd.69: run-preflight! drained :initial-events SYNCHRONOUSLY
        ;; (arbitrary app code) that may have unmount!ed this root and mounted
        ;; a replacement under the same id/container. Revalidate ownership of
        ;; the CAPTURED handle before the re-render — a superseded root must
        ;; never commit, and `.render`ing the stale handle would write into a
        ;; root the replacement now owns. Mirrors the fresh-path re-check.
        (require-live-root! root 're-frame.ui/mount)
        (.render (.-react-root root) (element-thunk))
        root)
      (do
        (check-root-claim! 're-frame.ui/mount info container)
        ;; preflight BEFORE any React work (Q49: container untouched on
        ;; failure) — and before registration, so a failed mount leaves
        ;; no registry entry either.
        (run-preflight! root-id plans-thunk)
        ;; RE-CHECK the claim AFTER preflight (rf2-vxgfnd.52). run-preflight!
        ;; drains :initial-events SYNCHRONOUSLY — arbitrary app code that may
        ;; itself mount a root for this id or into this container (re-entrancy),
        ;; registering it while THIS mount is still unregistered. The
        ;; pre-preflight claim is now stale; re-running it BEFORE createRoot and
        ;; the UNCONDITIONAL register means a re-entrant mount that took
        ;; ownership fails this mount loud (duplicate-root-id /
        ;; root-container-in-use / duplicate-identifier-prefix) rather than
        ;; createRoot-ing a container the inner root owns and clobbering its
        ;; registry entry — which the failed-first-render rollback below would
        ;; then match on THIS root and delete, orphaning the inner root's live
        ;; React tree. No user code runs between this re-check and register
        ;; (createRoot is React-internal, register is a swap!), so the window
        ;; is closed.
        (check-root-claim! 're-frame.ui/mount info container)
        (let [react-root (rdc/createRoot container react-opts)
              root       (Root. react-root container root-id)]
          ;; register BEFORE any render (contract §7 Layer 3)
          (register-live-root! info container root)
          (try
            (.render react-root (element-thunk))
            root
            (catch :default e
              ;; TOTAL rollback of a failed FIRST mount — the element thunk
              ;; or the synchronous host render threw. Release this exact
              ;; claim (identity-guarded: a stale handle never evicts a
              ;; newer root) and best-effort unmount the host root so a
              ;; retry starts clean, with no phantom live root and no host
              ;; root residue. Rethrow the ORIGINAL mount error even if the
              ;; host cleanup also throws (cleanup never masks it).
              (release-root! root-id root)
              (try (.unmount react-root) (catch :default _ nil))
              (throw e))))))))

(defn create-root*
  "Runtime half of `ui/create-root` — claim identity + container and
  build the React root; no render (preflight runs before the first
  `render!`). Returns the Root."
  [{:keys [root-id] :as info} container react-opts]
  (check-root-claim! 're-frame.ui/create-root info container)
  (let [react-root (rdc/createRoot container react-opts)
        root       (Root. react-root container root-id)]
    (register-live-root! info container root)
    root))

(defn render!*
  "Runtime half of `ui/render!` — frame preflight + render/re-render the
  compiled root template into `root`. A STALE handle (its root-id
  `unmount!`ed or superseded — no longer the live root for its id) fails
  loud with `:rf.error/root-not-live` BEFORE any side effect (no
  preflight, no `:initial-events` drain, no install-record write, no
  `.render`) — the render-side mirror of `unmount!*`'s membership guard.
  Otherwise preflight runs FIRST, keyed by the Root's identity (fixed at
  create-root): a preflight failure leaves the root's last committed
  render (and, in dev, its descriptor) untouched (the Q49 ruling — retry =
  re-call `render!`). Preflight drains `:initial-events` (arbitrary app
  code) that may unmount this root and mount a replacement under the same
  id, so ownership is REVALIDATED after preflight (rf2-vxgfnd.69) before the
  descriptor write or `.render`: a superseded handle fails loud and the
  replacement root is left untouched. In dev, completes the compile-time
  descriptor-base with the Root's identity on the registry entry — the swap
  is IDENTITY-GUARDED to THIS exact Root, never merely the current entry
  under the same id, so it can never write the stale call's descriptor onto
  a replacement root."
  [^Root root element-thunk plans-thunk descriptor-base]
  (require-live-root! root 're-frame.ui/render!)
  (let [rid (.-root-id root)]
    (run-preflight! rid plans-thunk)
    ;; rf2-vxgfnd.69: revalidate ownership AFTER the side-effecting preflight
    ;; (which may have unmount!ed this root and mounted a replacement under
    ;; the same id) and BEFORE the descriptor write / .render — a superseded
    ;; handle fails loud, leaving the replacement root untouched.
    (require-live-root! root 're-frame.ui/render!)
    (when ^boolean js/goog.DEBUG
      (when descriptor-base
        (swap! live-roots
               (fn [m]
                 ;; identity-guarded to THIS exact Root (rf2-vxgfnd.69): never
                 ;; write the descriptor onto a root that merely shares the id.
                 (let [entry (get m rid)]
                   (if (identical? (:root entry) root)
                     (assoc m rid
                            (assoc entry :descriptor
                                   (assoc descriptor-base
                                          :root-id rid
                                          :root-id-provenance (:provenance entry))))
                     m))))))
    (.render (.-react-root root) (element-thunk))
    root))

(defn hydrate-root*
  "Runtime half of `ui/hydrate-root`. Hydrating mounts read identity FROM
  the server-emitted manifest adjacent to the container — and server
  rendering, the manifest script-element convention, and hydrate preflight
  all land S5. At S1 no manifest can exist, so every hydrate fails loud
  (`:rf.error/root-manifest-invalid`) rather than guessing identity."
  [_container _element-thunk _plans-thunk _react-opts]
  (error/throw-error!
   :rf.error/root-manifest-invalid 're-frame.ui/hydrate-root
   (str "no root manifest is discoverable — hydrating mounts take root-id "
        "and identifier-prefix from the manifest the server render emits "
        "adjacent to the container, and server rendering lands with S5. "
        "Use ui/mount for a client-only root")
   {:recovery :use-ui-mount
    :extra {:missing :manifest}}))

(defn unmount!*
  "Runtime half of `ui/unmount!` — TOTAL teardown: unmount the React root
  and unregister the root-id (contract §7). Idempotent: a Root already
  torn down (or superseded in the registry) is a no-op. Framework
  ownership is released in a `finally`, so a throwing host `.unmount`
  still frees the exact root-id/container claim (identity-guarded — never
  evicts a newer claim) and a second `unmount!*` is then a no-op; the
  host teardown error still propagates to the caller (rf2-vxgfnd.18 AC4).

  LIFECYCLE (03 §4; rf2-vxgfnd.62). The host `.unmount` is driven through
  `reactive/teardown-root!`, which arms a collection window around it: React
  runs the effect-cleanups of this root's tree synchronously during `.unmount`,
  and each ViewCell's cleanup (`disconnect!`) — which emits the transient
  `:disconnected {:reason :unknown}` fact, indistinguishable from an Activity
  hide at that moment — is captured. AFTER `.unmount` returns, those captured
  cells (exactly this root's cells — the sweep is React-scoped, so sibling and
  nested-portal roots are untouched) are retroactively proven `:unmounted
  {:proof :host-teardown}` → `:dead`, so a real root unmount no longer leaves an
  `:unknown` disconnect forever and a retained handle can never reconnect after
  its root is gone. A throwing `.unmount` (React refused) collected nothing and
  tears no cell down — the orphaned tree's cells stay live (see below).

  AFTERMATH of a THROWING host `.unmount` (rf2-vxgfnd.53). The concrete
  case is React 18/19 refusing to `.unmount` synchronously from inside a
  render pass (\"attempted to synchronously unmount a root while React was
  already rendering\"): React did NOT tear the tree down, yet the `finally`
  releases the claim (the AC4 total-teardown contract, KEPT — the
  root-id/container must not strand). So the live React tree is now
  ORPHANED in a container the registry no longer tracks, and — because
  release makes a second `unmount!*` a no-op (the membership guard) — the
  framework can never retry the host unmount for that handle. A later
  `mount` into the same container would `createRoot` OVER the still-live
  tree (a double-root). The claim-release is deliberate; the orphaned tree
  is the price of AC4.

  So this is NOT silent: in a dev build a throwing `.unmount` emits a
  console diagnostic naming the MANUAL ESCAPE — once you are out of the
  render pass, tear the orphaned tree down yourself with
  `(.unmount (.-react-root root))` on the SAME `Root` handle BEFORE
  mounting into that container again. The diagnostic is goog.DEBUG-gated
  (Closure-DCE'd from production — I-12). Returns nil."
  [^Root root]
  (when (and (some? root)
             (identical? (:root (get @live-roots (.-root-id root))) root))
    (try
      ;; rf2-vxgfnd.62 — drive the host unmount through the root-teardown window
      ;; so this root's ViewCells are retroactively proven :unmounted → :dead
      ;; after React sweeps their effect cleanups (see the docstring LIFECYCLE
      ;; note). teardown-root! rethrows a throwing host `.unmount` unchanged, so
      ;; the catch/finally below keep the rf2-vxgfnd.53 ownership behaviour.
      (reactive/teardown-root! (fn [] (.unmount (.-react-root root))))
      (catch :default e
        ;; rf2-vxgfnd.53 — React did NOT unmount, yet the `finally` below
        ;; still releases the claim (AC4). The live tree is now orphaned and
        ;; the host root is unrecoverable through the framework (a second
        ;; `unmount!*` is a no-op). Make the aftermath non-silent: name the
        ;; manual escape + the remount double-root hazard. Dev-only
        ;; (goog.DEBUG-stripped in production). Then rethrow the ORIGINAL host
        ;; error so it still propagates to the caller (AC4).
        (when (and ^boolean js/goog.DEBUG (exists? js/console))
          (.warn js/console
                 (str "[re-frame.ui] host .unmount threw while tearing down root "
                      (pr-str (.-root-id root)) " — React did NOT unmount it, so "
                      "its live React tree is now orphaned in a container the "
                      "framework no longer tracks (the claim is released per the "
                      "total-teardown contract, so a second unmount! is a no-op). "
                      "A remount into this container would createRoot OVER the "
                      "still-live tree (a double-root). Escape: once you are out "
                      "of the render pass, tear the orphaned tree down yourself "
                      "with (.unmount (.-react-root root)) on the SAME Root handle "
                      "before mounting into that container again.")))
        (throw e))
      (finally
        (release-root! (.-root-id root) root))))
  nil)
