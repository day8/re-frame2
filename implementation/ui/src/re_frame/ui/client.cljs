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
            [clojure.string :as str]
            [re-frame.error :as error]
            [re-frame.ui.digest-carrier :as digest-carrier]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.viewcell :as viewcell]))

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

(defonce ^:private disposing-live-roots?
  ;; Adapter teardown snapshots one exact generation of Root handles, then
  ;; tears down the generic React spine. Root admission stays closed across
  ;; BOTH phases: an effect cleanup from either registry must not install a
  ;; later incarnation that the already-taken snapshot cannot own.
  (atom false))

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
  (reset! disposing-live-roots? false)
  nil)

(defn- require-root-creation-open!
  [where]
  (when (or @disposing-live-roots?
            (substrate-adapter/adapter-disposed?))
    (error/throw-error!
     :rf.error/adapter-disposed where
     (str "the re-frame.ui adapter's current generation is tearing down or "
          "has been destroyed — no public compiled Root may be created until "
          "destroy-adapter! finishes and a fresh adapter is installed")
     {:recovery :install-a-fresh-adapter})))

;; ---------------------------------------------------------------------------
;; The COMPLETE Root Descriptor v1 — the client read-time projection (dev only)
;; ---------------------------------------------------------------------------
;;
;; Spec 004C §2 splits Root Descriptor v1 into a STATIC CORE (baked at
;; expansion, NO `:build-digest` — it rides the emitted CLJS and each live-root
;; entry's `:descriptor`) and the COMPLETE descriptor (static core PLUS the
;; whole-build `:build-digest`). The digest is a READ-TIME projection on BOTH
;; hosts, never baked: it is a whole-build aggregate the mount site cannot know
;; at expansion — compile-order dependent AND stale under a view-only HMR edit
;; (see `re-frame.ui.compiler.root/root-descriptor`). These fns are the CLIENT
;; counterpart of the compiler's `re-frame.ui.compiler.root/descriptor-index`:
;; they stamp the SAME compiler-finalized `:build-digest` onto the live-root
;; static cores. The client does NOT derive identity from the set of modules
;; currently loaded: compile-finish patches one dev-only carrier from the
;; candidate whole-build snapshot; Shadow activates it only on successful
;; build/watch completion, and client reads are O(1). Thus lazy/multi-entry
;; loading cannot change identity, view-only HMR refreshes it without
;; re-expanding a mount site, and unsaved no-pass REPL evaluation cannot mutate
;; it. The whole carrier and these reads are goog.DEBUG-elided from production.

(defn current-build-digest
  "The client's whole-build view-identity digest (dev): the SAME compiler-
  finalized digest. The compiler is the sole authority and publishes it into a
  tiny dev-only carrier at the successful build boundary; this is an O(1)
  read, independent of loaded modules and runtime registrar contents. nil in
  production (goog.DEBUG=false)."
  []
  (digest-carrier/current))

(defn descriptor
  "The COMPLETE Root Descriptor v1 for live root `root-id` (dev): its static
  core from the live-root registry, stamped with the current whole-build
  `:build-digest` (`current-build-digest`) — the client read-time projection
  (Spec 004C §2). nil if `root-id` is not live, its entry carries no descriptor
  yet (a `create-root` before its first `render!`), or in production."
  [root-id]
  (when-let [d (:descriptor (get @live-roots root-id))]
    (assoc d :build-digest (current-build-digest))))

(defn descriptor-index
  "Every live root's COMPLETE Root Descriptor v1 (dev): root-id -> static core
  + the current whole-build `:build-digest`. The CLIENT counterpart of the
  compiler's `re-frame.ui.compiler.root/descriptor-index` — the same shape and
  the same (byte-identical) digest — the Xray / tool read surface for the live
  runtime descriptors. Roots still awaiting their first `render!` (no
  descriptor yet) are omitted. Empty in production."
  []
  (let [bd (current-build-digest)]
    (into {}
          (keep (fn [[rid entry]]
                  (when-let [d (:descriptor entry)]
                    [rid (assoc d :build-digest bd)])))
          @live-roots)))

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
  assert prefix uniqueness and release frees it by dissoc (rf2-ez3fqk).

  MINTS the root's per-mount INCARNATION here (rf2-vxgfnd.85/.92) — a fresh
  opaque token that scopes every ViewCell under this Root (provided via the
  root-incarnation React context at render) and drives the incarnation-aware
  root teardown reap at `unmount!*`. Stable for the Root's lifetime (registration
  runs once per Root); a re-mount under the same root-id after `unmount!` mints a
  DISTINCT incarnation, so a stale teardown can never reap the replacement's
  cells."
  [{:keys [root-id provenance site descriptor identifier-prefix]} container root]
  (swap! live-roots assoc root-id {:root root
                                   :container container
                                   :provenance provenance
                                   :identifier-prefix identifier-prefix
                                   :site site
                                   :descriptor descriptor
                                   :root-incarnation (reactive/make-root-incarnation)})
  nil)

(defn- root-incarnation-of
  "The per-mount root incarnation minted for live root `root-id` at
  registration (rf2-vxgfnd.92), or nil if the id is not live. Read at every
  render (to provide the root-incarnation context) and at `unmount!*` (to drive
  the incarnation-aware root-teardown reap)."
  [root-id]
  (:root-incarnation (get @live-roots root-id)))

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
  (require-root-creation-open! 're-frame.ui/mount)
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
        (.render (.-react-root root)
                 (viewcell/provide-root-incarnation
                  (root-incarnation-of root-id) (element-thunk)))
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
          ;; register BEFORE any render (contract §7 Layer 3) — this MINTS the
          ;; root incarnation the provider below scopes every ViewCell to.
          (register-live-root! info container root)
          (try
            (.render react-root
                     (viewcell/provide-root-incarnation
                      (root-incarnation-of root-id) (element-thunk)))
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
  (require-root-creation-open! 're-frame.ui/create-root)
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
    (.render (.-react-root root)
             (viewcell/provide-root-incarnation
              (root-incarnation-of rid) (element-thunk)))
    root))

(defn hydrate-root*
  "Runtime half of `ui/hydrate-root`. Hydrating mounts read identity FROM
  the server-emitted manifest adjacent to the container — and server
  rendering, the manifest script-element convention, and hydrate preflight
  all land S5. At S1 no manifest can exist, so every hydrate fails loud
  (`:rf.error/root-manifest-invalid`) rather than guessing identity."
  [_container _element-thunk _plans-thunk _react-opts]
  (require-root-creation-open! 're-frame.ui/hydrate-root)
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
  its root is gone. If `.unmount` throws after React consumes its host handle,
  the explicit root-incarnation token force-deads every cell in that exact
  generation and releases its observation owners before the error escapes;
  a later same-id incarnation is a distinct token and remains untouched.

  AFTERMATH of a THROWING host `.unmount` (rf2-vxgfnd.53, diagnostic
  corrected by rf2-vxgfnd.84). The concrete case is React 18/19 refusing to
  `.unmount` synchronously from inside a render pass (\"attempted to
  synchronously unmount a root while React was already rendering\"): the
  SYNCHRONOUS unmount did not complete, yet the `finally` releases the claim
  (the AC4 total-teardown contract, KEPT — the root-id/container must not
  strand), and a second `unmount!*` is a no-op (the membership guard). Two
  facts, verified against the pinned react-dom 19.2.0 source, shape what that
  aftermath actually IS:

    - the root handle is CONSUMED: `ReactDOMRoot.unmount()` nulls its
      `_internalRoot` BEFORE the flush that throws, so re-calling `.unmount`
      on the SAME handle is a SILENT NO-OP — there is NO manual escape
      through the consumed handle (the earlier diagnostic recommended exactly
      that no-op, which never tears anything down);
    - React usually DEFERS and self-completes the teardown work it scheduled
      before the throw, so the tree is typically removed regardless — the
      \"stuck host root\" is mostly moot, not the common outcome.

  So this is NOT silent, and now HONEST: in a dev build a throwing `.unmount`
  emits a console diagnostic stating the handle is consumed (no manual escape)
  and names direct container clearing as the host fallback. The adapter-wide
  destroy path performs that fallback itself (including the pinned React root
  marker) so the same container can be reused after re-init; an isolated public
  `unmount!` still leaves host recovery to its caller. The diagnostic is
  goog.DEBUG-gated (Closure-DCE'd from production — I-12). Returns nil."
  [^Root root]
  (when (and (some? root)
             (identical? (:root (get @live-roots (.-root-id root))) root))
    (try
      ;; rf2-vxgfnd.62 — drive the host unmount through the root-teardown window
      ;; so this root's ViewCells are retroactively proven :unmounted → :dead
      ;; after React sweeps their effect cleanups (see the docstring LIFECYCLE
      ;; note). teardown-root! rethrows a throwing host `.unmount` unchanged, so
      ;; the catch/finally below keep the rf2-vxgfnd.53 ownership behaviour.
      ;; rf2-vxgfnd.85/.92 — pass this root's INCARNATION so a cell Activity-hidden
      ;; BEFORE the unmount window (which the window can never capture) is still
      ;; reaped through the root-incarnation ownership registry, even when the
      ;; whole root was hidden and the window collects nothing.
      (reactive/teardown-root! (root-incarnation-of (.-root-id root))
                               (fn [] (.unmount (.-react-root root))))
      (catch :default e
        ;; rf2-vxgfnd.53 / rf2-vxgfnd.84 — the synchronous host .unmount did NOT
        ;; complete, yet the `finally` below still releases the claim (AC4) and a
        ;; second `unmount!*` is a no-op (the membership guard). Make the
        ;; aftermath non-silent AND honest: verified against pinned react-dom
        ;; 19.2.0, ReactDOMRoot.unmount nulls its internal root BEFORE the flush
        ;; that threw, so the handle is CONSUMED — re-calling .unmount on it is a
        ;; silent no-op, NOT a manual escape (the pre-.84 diagnostic recommended
        ;; that no-op). React usually defers + self-completes the teardown work
        ;; scheduled pre-throw, so a stuck tree is the exception; when one does
        ;; strand, the only real recovery is clearing the container (innerHTML) or
        ;; a fresh container. Dev-only (goog.DEBUG-stripped in production). Then
        ;; rethrow the ORIGINAL host error so it still propagates (AC4).
        (when (and ^boolean js/goog.DEBUG (exists? js/console))
          (.warn js/console
                 (str "[re-frame.ui] host .unmount threw while tearing down root "
                      (pr-str (.-root-id root)) " — React did NOT complete the "
                      "synchronous unmount, but the root handle is now CONSUMED: "
                      "ReactDOMRoot.unmount nulls its internal root BEFORE the "
                      "flush that threw, so re-calling .unmount on this handle is "
                      "a silent no-op — there is no manual escape through it. "
                      "React usually defers and self-completes the teardown work "
                      "scheduled before the throw, so the tree is typically "
                      "removed regardless; the claim is released per the "
                      "total-teardown contract (a second unmount! is a no-op). If "
                      "a container is genuinely left with a stuck tree, clear it "
                      "directly (set its innerHTML to \"\") or mount into a FRESH "
                      "container — never reuse the consumed handle.")))
        (throw e))
      (finally
        (release-root! (.-root-id root) root))))
  nil)

(defn- reclaim-consumed-container!
  "Adapter-disposal fallback after a public Root's host unmount threw.

  React 19 can consume the Root handle and throw before it clears either DOM
  children or its private `__reactContainer$…` ownership marker. There is no
  supported retry through the consumed handle. The adapter owns the terminal
  process teardown, so it clears both pieces against the PINNED React host;
  this makes DOM-empty + same-container re-init deterministic. Any fallback
  failure propagates as secondary cleanup evidence, never over the host error."
  [^Root root]
  (let [container (.-container root)
        failure   (volatile! nil)]
    (when container
      (try
        (if (fn? (.-replaceChildren container))
          (.replaceChildren container)
          (set! (.-innerHTML container) ""))
        (catch :default e
          (vreset! failure e)))
      (try
        (doseq [k (array-seq (js/Object.getOwnPropertyNames container))]
          (when (str/starts-with? k "__reactContainer$")
            (js/Reflect.deleteProperty container k)))
        (catch :default e
          (when-not @failure (vreset! failure e)))))
    (when @failure (throw @failure))
    nil))

(defn with-root-admission-closed!
  "Run `f` while public compiled Root creation is fenced. Idempotent under a
  concurrent/re-entrant close: only the caller that flips the fence executes
  `f`; the owner always reopens the local fence in `finally`. The core's
  terminal adapter breadcrumb remains the post-destroy admission guard until a
  fresh install. Adapter-internal; injected into `re-frame.ui.substrate` so the
  fence spans BOTH public-root and generic-spine cleanup."
  [f]
  (when (compare-and-set! disposing-live-roots? false true)
    (try
      (f)
      (finally
        (reset! disposing-live-roots? false)))))

(defn drain-live-roots!
  "Drain one exact snapshot of every public compiled client Root.

  The caller owns the surrounding root-admission fence. `unmount!*`'s identity
  guard means a stale handle can never evict a later same-id incarnation. The
  snapshot is never refreshed: stale disposal does not own a replacement that
  appeared through an internal/test bypass.

  Every root is attempted even when one host cleanup throws. The first error
  is rethrown after the whole snapshot drains; later failures are retained on
  its `rfUiAdapterCleanupErrors` diagnostic array because one teardown failure
  must neither strand siblings nor erase their evidence."
  []
  (let [roots  (mapv :root (vals @live-roots))
        errors (volatile! [])]
    (doseq [root roots]
      (try
        (unmount!* root)
        (catch :default e
          (vswap! errors conj e)
          (try
            (reclaim-consumed-container! root)
            (catch :default recovery-error
              (vswap! errors conj recovery-error))))))
    (when-let [primary (first @errors)]
      (when (< 1 (count @errors))
        (try
          (js/Object.defineProperty
           primary "rfUiAdapterCleanupErrors"
           #js {:value (to-array (rest @errors)) :configurable true})
          (catch :default _ nil)))
      (throw primary)))
  nil)

(defn dispose-live-roots!
  "Standalone adapter-internal teardown of the public compiled Root registry.
  The first-party composed adapter uses `with-root-admission-closed!` around
  this drain AND its generic-spine tail; this zero-arity helper preserves the
  direct/test seam with the same one-owner fencing law."
  []
  (with-root-admission-closed! drain-live-roots!)
  nil)
