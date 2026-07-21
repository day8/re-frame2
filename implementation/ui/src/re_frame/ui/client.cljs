(ns re-frame.ui.client
  "Client kernel of the re-frame.ui substrate — root identity + the mount
  surface (S1c, rf2-vxgfnd.3; the root-identity-and-mount contract).

  What lives here:

    - the `Root` handle (React root + container + root-id — identity is
      immutable for the Root's lifetime);
    - the LAYER-3 root-claim registry: a per-document map root-id -> entry.
      `create-root` / `mount` register their root-id BEFORE any render;
      registering an id already active or tearing down throws
      `:rf.error/duplicate-root-id`
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

  `hydrate-root*` runs that same preflight path and then ADOPTS the
  server-rendered DOM (rf2-3omxp): it resolves the container's Root
  Manifest through the `:ssr/discover-root-manifest` late-bind hook the
  optional SSR artefact publishes, takes `:root-id` and
  `:identifier-prefix` from the validated manifest's CONTENT, and hands
  the compiled element to `hydrateRoot`. It does NOT install the
  hydration payload — `re-frame.ssr/hydrate!` owns that, and the public
  boot is the two calls in order (`ssr/hydrate!`, then
  `ui/hydrate-root`). No manifest is a loud
  `:rf.error/root-manifest-invalid` `{:missing :manifest}`; no SSR
  artefact is a loud `:rf.error/ssr-artefact-missing`. Descriptors + site coords
  ride the registry in dev only (goog.DEBUG — production carries no
  manifests, I-12); root-id and container ownership are load-bearing
  identity and stay in production."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [clojure.string :as str]
            [re-frame.error :as error]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.runtime :as runtime]
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
;; Layer 3 — the per-document root-claim registry
;; ---------------------------------------------------------------------------

(defonce ^:private live-roots
  ;; root-id -> {:root Root :container node :provenance kw
  ;;             :identifier-prefix str|nil :site {..}|nil :descriptor {..}|nil
  ;;             :root-incarnation token
  ;;             :tearing-down? bool? :cleanup-failure? bool?}
  ;; The effective identifier-prefix rides the entry so claim-time uniqueness
  ;; (rf2-ez3fqk) reads it and release frees it by dissoc — no side index.
  (atom {}))

(defonce ^:private disposing-live-roots?
  ;; Adapter teardown snapshots one exact generation of Root handles, then
  ;; tears down the generic React spine. Root admission stays closed across
  ;; BOTH phases: an effect cleanup from either registry must not install a
  ;; later incarnation that the already-taken snapshot cannot own.
  (atom false))

(defonce ^:private consumed-containers
  ;; The exact DOM container nodes whose public Root suffered a THROWING/consumed
  ;; host `.unmount` and were terminally reclaimed (rf2-sddbc). A host cleanup that
  ;; QUEUES late DOM work — a microtask that `replaceChildren`s the node — and THEN
  ;; throws leaves that queued host authority UNSETTLED and unobservable in-process:
  ;; the adapter reclaim clearing the node's current DOM + React ownership marker is
  ;; a SNAPSHOT, never proof the queued task has run. So the exact node is
  ;; FAIL-CLOSED for reuse — a fresh `mount`/`create-root` onto it is rejected
  ;; (`:rf.error/root-container-consumed`) and the ergonomic recovery is a FRESH
  ;; node. This is a WeakSet DENYLIST of exact nodes (identity `.has`, GC-weak so a
  ;; node the app drops is collected) — NOT a task tracker or scheduler; it records
  ;; only THAT a node is poisoned, never WHAT work is pending. The root-id and
  ;; identifier-prefix are released normally, so the same root-id re-mounted on a
  ;; fresh container (the recovery) and unrelated fresh roots are unaffected. The
  ;; WeakRef capability this leans on is already required (see the JavaScript host
  ;; capability boundary), so `js/WeakSet` is always present.
  (atom (js/WeakSet.)))

(defn container-consumed?
  "Whether `container` is a FAIL-CLOSED consumed surface (tool/test read): an exact
  node a throwing/consumed host `.unmount` poisoned and an adapter reclaim retired
  into the consumed denylist (rf2-sddbc). A nil/absent container reads false."
  [container]
  (and (some? container) (.has ^js/WeakSet @consumed-containers container)))

(defn- mark-container-consumed!
  "Record `container` as a fail-closed consumed surface — a node whose Root's host
  `.unmount` threw/consumed the handle and whose id/prefix claim an adapter reclaim
  is about to release. Fenced fail-closed against reuse thereafter (rf2-sddbc)."
  [container]
  (when (some? container) (.add ^js/WeakSet @consumed-containers container))
  nil)

(defn live-root-ids
  "The root-ids whose claims this document still owns (tool/test read).
  Includes active roots and exact claims held `:tearing-down` through deferred
  settlement or cleanup-failure quarantine."
  []
  (set (keys @live-roots)))

(defn live-root-entry
  "The active or tearing-down claim for `root-id` (tool/test read), or nil.
  Entries carry the exact Root/container/incarnation plus provenance; transitional
  entries additionally carry `:tearing-down?` and, only after a host cleanup
  throw, `:cleanup-failure?`."
  [root-id]
  (get @live-roots root-id))

(defn reset-live-roots!
  "Test support: wipe the live-root registry. Does NOT unmount React
  roots — unmount! them first (or use fresh containers per test)."
  []
  (reset! live-roots {})
  (reset! disposing-live-roots? false)
  (reset! consumed-containers (js/WeakSet.))
  nil)

(declare current-adapter-generation)

(defn- predecessor-teardown-in-flight?
  "True when a live-root claim from a PRIOR adapter generation is still
  `:tearing-down` and its host teardown is genuinely SETTLEMENT-PENDING — a
  DEFERRED host root teardown scheduled by the predecessor generation's
  `destroy-adapter!` drain that React (react-dom 19.2 refusing an in-render
  unmount) has not yet settled (rf2-9pyles). Keyed to the EXACT adapter generation
  each claim was registered under (`:adapter-generation`), so an ordinary
  SAME-generation deferred `unmount!` mid-settlement does NOT block a sibling mount
  — only an UNSETTLED PREDECESSOR generation does. A claim with no recorded
  generation (a low-level test/tool seam that registered with no adapter installed)
  is never counted.

  A `:cleanup-failure?` quarantine is EXCLUDED (rf2-sddbc): a throwing host
  `.unmount` never fires `on-settled`, so its claim is TERMINAL, not in flight —
  it will never settle and release. Counting it here would globally fence every
  successor Root forever (even on unrelated fresh containers). Its fail-closed
  reach is instead the EXACT poisoned container alone (`consumed-containers` +
  `check-root-claim!`); only a genuine deferred-return teardown globally fences."
  [current-generation]
  (some (fn [[_ entry]]
          (and (:tearing-down? entry)
               (not (:cleanup-failure? entry))
               (some? (:adapter-generation entry))
               (not (identical? (:adapter-generation entry) current-generation))))
        @live-roots))

(defn- require-root-creation-open!
  [where]
  ;; Probe/capture the required weak-ownership primitive once, before frame
  ;; preflight, React allocation, live-root registration, or ViewCell work.
  (reactive/ensure-platform-compatible! where)
  (when (or @disposing-live-roots?
            (substrate-adapter/adapter-disposed?))
    (error/throw-error!
     :rf.error/adapter-disposed where
     (str "the re-frame.ui adapter's current generation is tearing down or "
          "has been destroyed — no public compiled Root may be created until "
          "destroy-adapter! finishes and a fresh adapter is installed")
     {:recovery :install-a-fresh-adapter}))
  ;; rf2-9pyles — the HONEST generation boundary. `destroy-adapter!` returns
  ;; SYNCHRONOUSLY while react-dom may still have DEFERRED each root's teardown
  ;; (its layout/effect cleanups — which may dispatch through a stale frame
  ;; capture — have not run). Re-`init!` clears the disposed breadcrumb, so the
  ;; check above reopens; but a SUCCESSOR generation must not become USABLE (admit
  ;; a public Root, whose preflight ENSUREs a fresh same-id frame the predecessor
  ;; cleanup could then mutate) until every predecessor host teardown has SETTLED.
  ;; Fail LOUD with a typed lifecycle error until then; the exact id/container is
  ;; reusable once the deferred settlement releases each predecessor claim.
  (when (predecessor-teardown-in-flight? (current-adapter-generation))
    (error/throw-error!
     :rf.error/adapter-teardown-in-flight where
     (str "a predecessor re-frame.ui adapter generation's public Root teardown is "
          "still in flight — react-dom deferred it (an in-render unmount that "
          "returned but has not settled), so its host cleanup authority has not "
          "yet released. A fresh adapter is installed, but the successor "
          "generation cannot admit a public compiled Root until the predecessor "
          "settles (else a deferred predecessor cleanup could mutate a same-id "
          "frame the new Root reseats). Retry after settlement; the exact "
          "id/container is reusable then")
     {:recovery :retry-after-teardown-settles})))

(defn- current-adapter-generation
  "The opaque token identifying the EXACT installed adapter generation, read
  from the substrate lifecycle state (nil when none is installed). Minted afresh
  on every install, so a destroy — or a destroy+reinstall of the SAME adapter
  spec — yields a different (or nil) token. A fresh public-root mount captures
  this BEFORE its side-effecting frame preflight and revalidates it AFTER, so a
  re-entrant preflight that swaps generation A for generation B is distinguished
  from the generation that admitted the attempt (rf2-vxgfnd.199). Compared only
  by `identical?`; `adapter-lifecycle-state` is the documented public lifecycle
  cell owned by the install/dispose pair."
  []
  (get-in @substrate-adapter/adapter-lifecycle-state [:installed :generation]))

(defn- require-adapter-generation-open!
  "Re-assert — AFTER a fresh mount's side-effecting frame preflight and BEFORE
  any React allocation — that public root creation is still open AND the exact
  adapter generation that admitted this attempt (`receipt`) is still installed.
  `run-preflight!` drains `:initial-events` synchronously (arbitrary app /
  override code) which may terminally destroy the adapter, or destroy generation
  A and install a replacement generation B. Either fails LOUD with
  `:rf.error/adapter-disposed` here — before `createRoot`, live-root
  registration, DOM mutation, ViewCell creation, or observation acquisition — so
  an attempt admitted under A can never allocate a Root under a disposed A or a B
  that never admitted it (rf2-vxgfnd.199, the re-entrant-preflight companion to
  rf2-vxgfnd.104's separate-thread teardown fence). A terminal disposal is caught
  by `require-root-creation-open!`; a same-breadcrumb replacement (B installed,
  the disposed breadcrumb already cleared) is caught by the exact-generation
  compare a boolean recheck cannot see."
  [where receipt]
  (require-root-creation-open! where)
  (when-not (identical? receipt (current-adapter-generation))
    (error/throw-error!
     :rf.error/adapter-disposed where
     (str "the re-frame.ui adapter generation that admitted this mount was "
          "destroyed or replaced during frame preflight (:initial-events drain) "
          "— a Root admitted under one adapter generation must not be allocated "
          "under another. No React root, live-root registration, or DOM was "
          "created; install a fresh adapter and re-mount")
     {:recovery :install-a-fresh-adapter})))

;; ---------------------------------------------------------------------------
;; Root Descriptor v1 — the client read surface (dev only)
;; ---------------------------------------------------------------------------
;;
;; Root Descriptor v1 (Spec 004C §2) is per-root static facts, baked at
;; expansion; it rides the emitted CLJS and each live-root entry's
;; `:descriptor` (see `re-frame.ui.compiler.root/root-descriptor`). These fns
;; are the CLIENT counterpart of the compiler's
;; `re-frame.ui.compiler.root/descriptor-index`: they read the stored static
;; descriptor off the live-root registry. The client does NOT derive identity
;; from the set of modules currently loaded; reads are O(1). These reads are
;; goog.DEBUG-elided from production.

(defn descriptor
  "Root Descriptor v1 for live root `root-id` (dev): the per-root static
  descriptor from the live-root registry (Spec 004C §2). nil if `root-id` is
  not live, its entry carries no descriptor yet (a `create-root` before its
  first `render!`), or in production.

  `goog.DEBUG`-gated, so the registry read costs advanced production output
  zero bytes (Spec 004C §2: the descriptor read is absent from production, not
  merely empty there)."
  [root-id]
  (when ^boolean js/goog.DEBUG
    (:descriptor (get @live-roots root-id))))

(defn descriptor-index
  "Every live root's Root Descriptor v1 (dev): root-id -> per-root static
  descriptor. The CLIENT counterpart of the compiler's
  `re-frame.ui.compiler.root/descriptor-index` — the same shape — the Xray /
  tool read surface for the live runtime descriptors. Roots still awaiting
  their first `render!` (no descriptor yet) are omitted. Empty in production.

  `goog.DEBUG`-gated, so the registry TRAVERSAL is removed from advanced
  production output rather than merely running over an empty registry (Spec
  004C §2). The empty-map return is kept as the production branch so the
  documented shape holds on both sides of the gate."
  []
  (if ^boolean js/goog.DEBUG
    (into {}
          (keep (fn [[rid entry]]
                  (when-let [d (:descriptor entry)]
                    [rid d])))
          @live-roots)
    {}))

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
  present (bare infos). A container a throwing/consumed host `.unmount` poisoned
  is fail-closed with `:rf.error/root-container-consumed` (rf2-sddbc — recovery is
  a FRESH node), checked ahead of the ordinary in-use arm."
  [where {:keys [root-id provenance site identifier-prefix]} container]
  (require-container! where root-id container)
  ;; rf2-sddbc / rf2-h05lm — a container a throwing/consumed host `.unmount` POISONED
  ;; is fail-closed, and this is checked AHEAD OF the duplicate-root-id and in-use
  ;; arms. A same-id retry against the EXACT poisoned node must report the terminal
  ;; consumed-container condition (fresh-node recovery + owner evidence), never let
  ;; duplicate-ID ordering hide the poisoned node behind a `:make-root-ids-unique`
  ;; that promises a settlement the cleanup-failure quarantine will never reach. The
  ;; poison rides either an unreleased cleanup-failure claim (isolated `unmount!`,
  ;; pre-destroy — `cf-owner`) or the post-reclaim `consumed-containers` denylist
  ;; (id/prefix already released). Clearing its DOM + React marker was a SNAPSHOT,
  ;; never proof a queued host task settled, so recovery is a FRESH node — NEVER a
  ;; wait-for-settlement. A same-id retry onto a DIFFERENT fresh node falls through to
  ;; the duplicate-root-id arm below (the id, not this node, is the obstacle).
  (let [cf-owner (when-let [owner (container-owner container)]
                   (when (:cleanup-failure? (get @live-roots owner)) owner))]
    (when (or cf-owner (container-consumed? container))
      (error/throw-error!
       :rf.error/root-container-consumed where
       (str "the container for root " (pr-str root-id) " is a CONSUMED surface"
            (if cf-owner
              (str " still owned by cleanup-failure quarantine " (pr-str cf-owner))
              "")
            " — a prior root's host `.unmount` threw and React consumed that "
            "handle. Its teardown may have QUEUED late DOM work that has not "
            "settled, so this exact node can never be proven free; clearing its "
            "DOM and React marker was a snapshot, not settlement. Mount into a "
            "FRESH container node")
       {:recovery :use-a-fresh-container
        :extra (cond-> {:root-id root-id}
                 cf-owner (assoc :owner-root-id cf-owner))})))
  (when-let [existing (get @live-roots root-id)]
    (let [cleanup-failure? (:cleanup-failure? existing)]
      (error/throw-error!
       :rf.error/duplicate-root-id where
       (cond
         cleanup-failure?
         ;; rf2-sddbc — a throwing/consumed host `.unmount` left this id held by a
         ;; CONSUMED root. There is NO settlement signal ("re-mount after settlement"
         ;; would be dishonest): the id frees only when the adapter is destroyed and
         ;; reinstalled (its drain reclaims the quarantine), and the exact old
         ;; container is fail-closed regardless. (The exact poisoned node is already
         ;; routed to `:rf.error/root-container-consumed` above; this arm fires on a
         ;; same-id retry onto a DIFFERENT fresh node.)
         (str "root-id " (pr-str root-id) " is held by a CONSUMED root — its host "
              "`.unmount` threw and its exact container is fail-closed. There is no "
              "settlement to wait for; the id frees only when the adapter is "
              "destroyed and reinstalled. Use a distinct :root-id with a fresh "
              "container, or destroy + re-init the adapter to reclaim it")
         (:tearing-down? existing)
         ;; rf2-vxgfnd.182 — a deferred host teardown still owns this id.
         (str "root-id " (pr-str root-id) " is tearing down — a host teardown is "
              "in flight (a deferred React unmount that returned but has not yet "
              "settled). The root-id frees once teardown settles; re-mount after "
              "settlement, or use a distinct :root-id and a fresh container")
         :else
         (str "root-id " (pr-str root-id) " is already live in this document — "
              "root-ids are page-unique identity. "
              (if (= :derived (:provenance existing) provenance)
                "both ids derived from the same view — add :disambiguator or author :root-id"
                "unmount! the existing root, or author a distinct :root-id")))
       ;; rf2-h05lm — the structured recovery/evidence is structurally EXACT: a
       ;; cleanup-failure quarantine never settles, so its recovery is NOT the
       ;; deferred/live `:make-root-ids-unique` (which invites a wait) but adapter
       ;; destroy + re-init (reclaims the id) or a distinct id with a fresh node. The
       ;; `:cleanup-failure?` flag rides `:existing` so structured consumers/AIs
       ;; recover the terminal-vs-deferred distinction the human message already draws.
       {:recovery (if cleanup-failure?
                    :reinit-adapter-or-use-a-fresh-identity
                    :make-root-ids-unique)
        :extra {:root-id  root-id
                :existing (select-keys existing [:provenance :site :tearing-down? :cleanup-failure?])
                :arriving {:provenance provenance :site site}}})))
  (when-let [owner (container-owner container)]
    (error/throw-error!
     :rf.error/root-container-in-use where
     (if (:tearing-down? (get @live-roots owner))
       ;; rf2-vxgfnd.182 — the owner is tearing down; the node is not yet free.
       (str "the container for root " (pr-str root-id) " is still owned by root "
            (pr-str owner) ", whose host teardown is in flight (a deferred React "
            "unmount that has not yet settled) — one container, one root. The "
            "node frees once teardown settles; mount into a fresh node, or "
            "re-mount after settlement")
       (str "the container for root " (pr-str root-id) " is already owned by "
            "live root " (pr-str owner) " — one container, one root. "
            "unmount! the owning root first, or mount into a different node"))
     {:recovery :unmount-the-owning-root-first
      :extra (cond-> {:root-id root-id :owner-root-id owner}
               (:tearing-down? (get @live-roots owner))
               (assoc :existing {:tearing-down? true}))}))
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

  Takes the root's per-mount INCARNATION (rf2-vxgfnd.85/.92) — a fresh opaque
  token. The 4-arity form receives one minted by the caller BEFORE `createRoot`
  (rf2-vxgfnd.264, so the root's `onUncaughtError` wrapper can close over the EXACT
  incarnation it settles); the 3-arity form mints one internally (the low-level
  test/tool seam that installs no host error wrapper). It scopes every ViewCell
  under this Root (provided via the root-incarnation React context at render) and
  drives the incarnation-aware root teardown reap at `unmount!*`. Stable for the
  Root's lifetime (registration runs once per Root); a re-mount under the same
  root-id after `unmount!` mints a DISTINCT incarnation, so a stale teardown can
  never reap the replacement's cells."
  ([info container root]
   (register-live-root! info container root (reactive/make-root-incarnation)))
  ([{:keys [root-id provenance site descriptor identifier-prefix]} container root incarnation]
   (swap! live-roots assoc root-id {:root root
                                    :container container
                                    :provenance provenance
                                    :identifier-prefix identifier-prefix
                                    :site site
                                    :descriptor descriptor
                                    :root-incarnation incarnation
                                    ;; rf2-9pyles — the EXACT adapter generation this
                                    ;; claim was admitted under. A claim left
                                    ;; `:tearing-down` by a PRIOR generation's disposal
                                    ;; drain fences the successor generation's root
                                    ;; admission until its deferred host teardown settles
                                    ;; (`predecessor-teardown-in-flight?`).
                                    :adapter-generation (current-adapter-generation)})
   nil))

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

(defn- queue-microtask!
  "Enqueue `f` at the next FIFO microtask checkpoint. Failed-first-mount
  rollback has no committed reporter from which to observe host settlement, so
  a normally returning cleanup conservatively releases its exact claim here —
  after the current mount/error stack has unwound, never inline."
  [f]
  (if (exists? js/queueMicrotask)
    (js/queueMicrotask f)
    (.then (js/Promise.resolve) (fn [_] (f)))))

;; ---------------------------------------------------------------------------
;; The pending render-attempt owner — terminal receipt settlement on React
;; abort / abandonment (rf2-vxgfnd.264)
;; ---------------------------------------------------------------------------
;;
;; The `root-commit-reporter` (rf2-vxgfnd.248) finalizes a preflight receipt from
;; a layout effect only after React COMMITS the render. But it provides no owner
;; for a receipt when React FAILS or ABANDONS the render before that reporter
;; mounts: a descendant throw AFTER `.render` returns goes to root
;; `onUncaughtError` (the commit is aborted; the reporter's layout effect never
;; runs), a fresh async render followed by an immediate unmount never commits, and
;; a superseding render leaves the old attempt provisional. Left alone, each such
;; receipt lingers forever — neither committed nor terminally failed.
;;
;; The fix gives each live Root entry ONE exact pending render-attempt owner — the
;; seated `receipt`, keyed to the root `:root-incarnation`. It is seated BEFORE
;; `.render`; the exact reporter commit finalizes and clears it; a host error
;; (`onUncaughtError`), an explicit supersession (a later `.render` seating a new
;; attempt), and unmount TERMINALLY settle (abort) that exact pending receipt. The
;; frames-layer `abort-preflight-attempt!`/`finalize-preflight-attempt!` stay
;; REV-GUARDED, so a stale/late/cross-root/reincarnation signal can never settle a
;; newer attempt. Suspense stays pending until a real terminal edge (commit,
;; supersession, unmount, or host failure). No global flushSync, no second
;; coordinator, no public API.

(defn- seat-pending-attempt!
  "Seat `receipt` (this render attempt's preflight evidence — possibly nil for a
  no-plan render) as root `root-id`'s single pending render-attempt owner, keyed
  to `incarnation`. A prior UNCOMMITTED attempt is a SUPERSESSION: its receipt is
  aborted (only the OLD receipt — the new attempt proceeds to commit). Seated
  BEFORE `.render`, and identity-guarded to the live incarnation, so a same-id
  reincarnation's entry is never seated by a stale caller.

  The overtaking `receipt` is handed to the abort as the SUPERSEDING attempt: a
  same-root write the new preflight already refreshed (rev1 → rev2) settles as
  EXPECTED supersession, not a spurious `frame-preflight-evidence-mismatch`
  (rf2-5ep117). Disjoint old writes the new attempt does not cover still abort
  terminally. Returns nil."
  [root-id receipt incarnation]
  (let [superseded (volatile! nil)]
    (swap! live-roots
           (fn [m]
             (let [entry (get m root-id)]
               (if (and entry (identical? (:root-incarnation entry) incarnation))
                 (do (vreset! superseded (:pending-attempt entry))
                     (assoc m root-id (assoc entry :pending-attempt receipt)))
                 m))))
    (when-some [old @superseded]
      (frames/abort-preflight-attempt! old receipt)))
  nil)

(defn- settle-committed-attempt!
  "The reporter's HOST-COMMIT edge (rf2-vxgfnd.248/.264): finalize `receipt`
  (mark its records `:committed`) and clear it from root `root-id`'s pending slot,
  BOTH identity-guarded to the exact `receipt` + `incarnation` so a stale/late
  reporter, a same-id reincarnation, or a cross-root control can never clear (or
  finalize past) a newer attempt. Returns nil."
  [root-id receipt incarnation]
  (frames/finalize-preflight-attempt! receipt)
  (swap! live-roots
         (fn [m]
           (let [entry (get m root-id)]
             (if (and entry
                      (identical? (:root-incarnation entry) incarnation)
                      (identical? (:pending-attempt entry) receipt))
               (assoc m root-id (dissoc entry :pending-attempt))
               m))))
  nil)

(defn- abort-pending-attempt!
  "The host-error / unmount edge (rf2-vxgfnd.264): TERMINALLY settle root
  `root-id`'s currently-seated pending render attempt as FAILED (`:fresh` writes →
  `:mount-incomplete`, `:live`/committed refreshes → `:preflight-attempt-failed`)
  and clear it. Identity-guarded to `incarnation` (a same-id reincarnation's entry
  is a distinct incarnation, left untouched); the frames abort is rev-guarded, so
  a stale receipt never clobbers an overtaking write. No-op when nothing is
  seated (a committed attempt already cleared its slot). Returns nil."
  [root-id incarnation]
  (let [pending (volatile! nil)]
    (swap! live-roots
           (fn [m]
             (let [entry (get m root-id)]
               (if (and entry
                        (identical? (:root-incarnation entry) incarnation)
                        (some? (:pending-attempt entry)))
                 (do (vreset! pending (:pending-attempt entry))
                     (assoc m root-id (dissoc entry :pending-attempt)))
                 m))))
    (when-some [r @pending]
      (frames/abort-preflight-attempt! r)))
  nil)

(defn- abort-seated-attempt!
  "The SYNCHRONOUS post-preflight-throw edge (the element thunk, host `.render`,
  or the post-preflight ownership fence threw BEFORE React scheduled the commit):
  terminally settle `receipt` as failed AND clear it from root `root-id`'s pending
  slot when still seated. Unlike `abort-pending-attempt!`, the receipt is aborted
  UNCONDITIONALLY (it is the caller's exact receipt) — a throw before the seat, or
  after a first-mount rollback dissoc'd the entry, still settles it — preserving
  the pre-.264 catch behaviour while also clearing any seated slot. Returns nil."
  [root-id receipt incarnation]
  (frames/abort-preflight-attempt! receipt)
  (swap! live-roots
         (fn [m]
           (let [entry (get m root-id)]
             (if (and entry
                      (identical? (:root-incarnation entry) incarnation)
                      (identical? (:pending-attempt entry) receipt))
               (assoc m root-id (dissoc entry :pending-attempt))
               m))))
  nil)

(defn- mark-tearing-down!
  "Move active root `root-id`'s claim into the `:tearing-down` state — the
  `:live -> :tearing-down -> :released` transition of rf2-vxgfnd.182. Marked
  BEFORE host cleanup, so reentrant `mount*`/`create-root*` on the exact
  root-id/container is REJECTED until ownership is proven free. The claim may be
  observed while a committed reporter authoritatively awaits deferred settlement,
  while failed-first cleanup waits for its FIFO checkpoint, or after a cleanup
  throw leaves it quarantined. A synchronous successful teardown releases inline.
  Identity-guarded to THIS exact Root; a no-op if the entry was superseded."
  [root-id root]
  (swap! live-roots
         (fn [m]
           (let [entry (get m root-id)]
             (if (identical? (:root entry) root)
               (assoc m root-id (assoc entry :tearing-down? true))
               m))))
  nil)

(defn- mark-cleanup-failure-quarantine!
  "Classify THIS exact tearing-down Root as a cleanup-failure quarantine.
  Adapter destruction may terminally reclaim this consumed host surface; a
  genuinely deferred settlement has no such mark and remains reporter-owned."
  [root-id root]
  (swap! live-roots
         (fn [m]
           (let [entry (get m root-id)]
             (if (and (identical? (:root entry) root)
                      (:tearing-down? entry))
               (assoc m root-id (assoc entry :cleanup-failure? true))
               m))))
  nil)

(defn- exact-cleanup-failure-quarantine?
  [root-id root]
  (let [entry (get @live-roots root-id)]
    (and (identical? (:root entry) root)
         (:tearing-down? entry)
         (:cleanup-failure? entry))))

(defn- attach-rollback-cleanup-error!
  "Retain a failed-first-mount host-cleanup error as canonical SECONDARY
  evidence on `primary`. The mount failure remains the thrown value."
  [primary cleanup-error]
  (try
    (js/Object.defineProperty
     primary "rfUiRollbackCleanupError"
     #js {:value cleanup-error :configurable true})
    (catch :default _ nil))
  primary)

(defn- rollback-failed-first-mount!
  "Roll back one exact first-mount incarnation after its element/host render
  boundary threw.

  Transaction law (rf2-vxgfnd.291): retain the id/container/prefix claim and
  mark it `:tearing-down` BEFORE host cleanup. Drive cleanup through
  `reactive/teardown-root!` so a throwing host force-deads every framework
  owner in this exact incarnation. A normal return has no committed reporter
  settlement signal, so it releases the identity-guarded claim at the next
  FIFO microtask. A cleanup throw schedules no release: the host surface is not
  proven free and stays fail-closed quarantined. The original mount error is
  always primary; cleanup rides `rfUiRollbackCleanupError`."
  [root-id ^Root root incarnation primary]
  (mark-tearing-down! root-id root)
  (try
    (reactive/teardown-root!
     incarnation
     (fn []
       (try
         (.unmount (.-react-root root))
         (catch :default e
           ;; Publish the cleanup-failure cause at the host throw edge, before
           ;; exact-incarnation framework reaping can run user cleanup hooks.
           (mark-cleanup-failure-quarantine! root-id root)
           (throw e))))
     ;; No reporter committed on a synchronous failed first render. The raw
     ;; cleanup return is therefore insufficient evidence for inline reuse;
     ;; settle conservatively at the next host microtask checkpoint.
     (fn []
       (queue-microtask! #(release-root! root-id root))))
    (catch :default cleanup-error
      (mark-cleanup-failure-quarantine! root-id root)
      (when (and ^boolean js/goog.DEBUG (exists? js/console))
        (.warn js/console
               (str "[re-frame.ui] rollback of a failed first mount of root "
                    (pr-str root-id) " could not cleanly unmount the host root "
                    "— the original mount error is rethrown as primary, every "
                    "framework owner in this exact incarnation is force-dead, "
                    "and this id/container/identifierPrefix claim remains "
                    "QUARANTINED (tearing-down). The host surface is not proven "
                    "settled; mount into a fresh/replaced container.")
               cleanup-error))
      (attach-rollback-cleanup-error! primary cleanup-error)))
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
  `render!` (or `mount`) a fresh root.

  A root whose claim is `:tearing-down` (deferred host settlement,
  failed-first settlement checkpoint, or cleanup-failure quarantine) is NOT
  live either: its React tree is being unmounted/consumed, so
  a `render!` into it would `.render` a consumed/unmounting handle. It fails the
  same LOUD `:rf.error/root-not-live` — recreate after the teardown settles."
  [^Root root where]
  (let [rid   (.-root-id root)
        entry (get @live-roots rid)]
    (when (or (not (identical? (:root entry) root))
              (:tearing-down? entry))
      (error/throw-error!
       :rf.error/root-not-live where
       (str "the Root handle for root-id " (pr-str rid) " is no longer the "
            "live root for that id — it was unmount!ed"
            (if (:tearing-down? entry)
              " (a host teardown is in flight — the root is tearing down)"
              (str ", or superseded by a newer root claiming the same id "
                   "(possibly during this operation's frame preflight, which "
                   "drains :initial-events — arbitrary app code)"))
            ". A superseded or tearing-down root can never commit a "
            "render; rendering into it would drain :initial-events "
            "(irreversible fx) and write install records against a dead "
            "root. create-root + render! (or mount) a fresh root")
       {:recovery :recreate-the-root
        :extra (cond-> {:root-id rid}
                 (:tearing-down? entry)
                 (assoc :existing {:tearing-down? true}))}))))

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
  ns docstring).

  Returns the live executor's preflight-attempt RECEIPT (rf2-vxgfnd.139) — the
  caller renders it through the `root-commit-reporter`, which calls
  `frames/finalize-preflight-attempt!` only when React COMMITS the render
  (rf2-vxgfnd.248), and `frames/abort-preflight-attempt!` if the post-preflight
  boundary throws SYNCHRONOUSLY (before React receives the element). nil when
  there are no static plans, or when a capture-hook override returns a
  non-receipt (both are no-ops at the boundary)."
  [root-id plans-thunk]
  (when plans-thunk
    (let [plans (plans-thunk)
          f     (or @preflight-hook frames/execute-frame-plans!)]
      (f root-id plans))))

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

(defn- report-uncaught-default!
  "React 19's default `onUncaughtError` reporting, preserved when the app authored
  NO callback (rf2-vxgfnd.264): once we install a wrapper to catch the receipt-abort
  edge, React no longer runs its own default, so we replicate it —
  `globalThis.reportError` when present (React 19.2's `reportGlobalError`), else
  `console.error`."
  [error]
  (if (fn? (.-reportError js/globalThis))
    (js/reportError error)
    (when (exists? js/console) (.error js/console error))))

(defn- report-recoverable-default!
  "React's default `onRecoverableError` reporting, preserved when the app
  authored NO callback but we installed a wrapper for the compiled-tier
  hydration-mismatch diagnostic (rf2-6z1i2): once a wrapper is set React no
  longer runs its own default, so we replicate it — `globalThis.reportError`
  when present, else `console.error`. Mirrors `report-uncaught-default!`."
  [error]
  (if (fn? (.-reportError js/globalThis))
    (js/reportError error)
    (when (exists? js/console) (.error js/console error))))

(defn- react-opts-with-hydration-mismatch
  "Compose the React root options with the compiled-tier HYDRATION-MISMATCH
  verification (rf2-6z1i2, the Path A ruling). A hydrating compiled root has no
  structural render-tree hash (the `:render-tree-fn` hash channel is
  hiccup-tier-only); it VERIFIES by React-native ADOPTION — React diffs the
  root's first `:server`-phase render (its `ui/client-only` fallbacks) against
  the server DOM during hydration and reports the divergences it
  AUTOMATICALLY RECOVERS FROM (a text-content mismatch, or a missing / extra /
  wrong-type element) through `onRecoverableError`. This is NOT exhaustive
  server-vs-client divergence detection: an ATTRIBUTE-ONLY mismatch (a stale
  `class` / `style` / ARIA value) takes React's dev-only warning path, is not
  guaranteed to be patched, and fires NEITHER `onRecoverableError` nor any
  production equivalent — so it surfaces NO `:rf.ssr/hydration-mismatch` here
  (an intrinsic boundary of React-native adoption; the compiled tier carries no
  structural hash — Spec 011 §Hydration-mismatch detection, the attribute-only
  boundary). In the ADOPTION WINDOW — before this root's phase flip,
  tracked by `adoption-ref` (a `#js {:adopting true}` the `PhaseFlipper` clears
  on its `:server` commit) — such a recoverable error IS the compiled tier's
  `:rf.ssr/hydration-mismatch` signal: surface the framework diagnostic FIRST,
  THEN honour the host's authored `onRecoverableError` (compose, never clobber)
  or React's default report. Post-flip recoverable errors pass straight through.

  The wrapper is installed ONLY when the app authored an `onRecoverableError`
  OR debug is on: with neither there is nothing to add over React's default
  `reportError` (the emit DCEs), so no wrapper is installed and production pays
  zero cost — the `report-uncaught-default!` preserve-default precedent (.264).
  ONLY `hydrate-root*` calls this; non-hydrating mounts have no adoption window
  and install no wrapper. Returns a fresh options object, or `react-opts`
  unchanged when no wrapper is warranted."
  [react-opts root-id ^js adoption-ref]
  (let [authored (when react-opts (unchecked-get react-opts "onRecoverableError"))]
    (if (or (fn? authored) interop/debug-enabled?)
      (let [composed (fn on-recoverable [error error-info]
                       ;; In the adoption window a React recoverable error is the
                       ;; compiled tier's hydration-mismatch signal. Emit the
                       ;; framework diagnostic FIRST (debug-gated + DCE'd inside
                       ;; `emit-hydration-mismatch!`), THEN delegate — never clobber.
                       (when (.-adopting adoption-ref)
                         (runtime/emit-hydration-mismatch! root-id error))
                       (if (fn? authored)
                         (authored error error-info)
                         (report-recoverable-default! error)))
            o        (if react-opts (js/Object.assign #js {} react-opts) #js {})]
        (unchecked-set o "onRecoverableError" composed)
        o)
      react-opts)))

(defn- react-opts-with-attempt-abort
  "Compose the (already-built) React root options with the .264 host-error edge:
  an `onUncaughtError` that TERMINALLY settles root `root-id`'s currently-seated
  pending render attempt (React aborts the commit before the reporter's layout
  effect can finalize it), keyed to the EXACT `incarnation` this `createRoot`
  registers, and THEN delegates to the authored callback — or to React's default
  global-error report when none was authored. Every other option (identifierPrefix,
  onCaughtError, onRecoverableError) is carried through unchanged. Returns a fresh
  options object; the caller passes it straight to `createRoot`."
  [react-opts root-id incarnation]
  (let [authored (when react-opts (unchecked-get react-opts "onUncaughtError"))
        composed (fn on-uncaught [error error-info]
                   ;; React aborted this commit — the reporter never runs. Settle
                   ;; the exact seated attempt (rev-guarded downstream), THEN honour
                   ;; the authored React error callback / React's default.
                   (abort-pending-attempt! root-id incarnation)
                   (if (fn? authored)
                     (authored error error-info)
                     (report-uncaught-default! error)))
        o        (if react-opts (js/Object.assign #js {} react-opts) #js {})]
    (unchecked-set o "onUncaughtError" composed)
    o))

;; ---------------------------------------------------------------------------
;; The host-commit reporter — preflight evidence finalizes on a REAL commit
;; (rf2-vxgfnd.248)
;; ---------------------------------------------------------------------------
;;
;; `ReactDOMRoot.render` SCHEDULES the update and can return BEFORE React lays
;; out / commits it (react.dev/reference/react-dom/client/createRoot; the pinned
;; react-dom routes `.render` to `updateContainerImpl`, which enqueues + schedules
;; without a commit callback). A component that succeeds the element thunk but
;; then THROWS during React's render is caught by the root's `onUncaughtError`
;; and the commit is ABORTED — yet `.render` returned normally, so neither the
;; synchronous mount rollback nor `abort-preflight-attempt!` runs. Finalizing the
;; preflight attempt immediately after `.render` therefore records a COMMITTED
;; root scope for a render that never committed (the rf2-vxgfnd.248 defect).
;;
;; The fix binds attempt settlement to an AUTHORITATIVE host commit signal, not
;; the `.render` return: the rendered root element is wrapped in this transparent
;; reporter, whose sole `useLayoutEffect` calls `frames/finalize-preflight-
;; attempt!`. Layout effects run ONLY for COMMITTED renders — an ABANDONED render
;; (StrictMode double-invoke, time-sliced tear-off) publishes no layout effect,
;; and an ABORTED commit (an uncaught render throw with no error boundary)
;; discards the whole root tree INCLUDING this reporter, so the effect never
;; runs. The attempt is thus finalized IFF React actually committed it; a
;; commit contained by an error boundary (a fallback DID commit) finalizes, as a
;; committed root scope truthfully exists. `finalize-preflight-attempt!` is itself
;; REV-GUARDED (frames/finalize-writes!) to the exact install-record revision the
;; receipt captured, so a stale, duplicated, or replayed commit signal can never
;; finalize a newer attempt or another root's evidence. No global `flushSync`:
;; the only cost is one transparent wrapper + one layout effect per root render.

(defn- root-commit-reporter
  "React function component wrapping a root render (rf2-vxgfnd.248/.264). Renders
  its `children` unchanged and, in a `useLayoutEffect` keyed to the exact attempt
  `rfReceipt`, finalizes that attempt AND clears its pending-attempt slot at
  React's REAL commit boundary (`settle-committed-attempt!`). Because layout
  effects fire only for committed renders, an aborted/abandoned render never
  reaches here — its receipt is instead terminally settled by the host-error /
  supersession / unmount edges (rf2-vxgfnd.264). The finalize + clear are both
  identity/rev-guarded, so a stale commit signal, a no-plan render, and a same-id
  reincarnation are all harmless. A separate mount-lifetime layout effect is
  the authoritative deferred-host-settlement reporter for this exact root
  incarnation, including cell-less/Activity-hidden roots; ViewCell population
  never classifies deferral. A cleanup throw takes the distinct quarantined
  claim path even if a late reporter cleanup eventually fires."
  [^js props]
  (let [receipt     (.-rfReceipt props)
        root-id     (.-rfRootId props)
        incarnation (.-rfIncarnation props)]
    (react/useLayoutEffect
     (fn commit-report []
       (settle-committed-attempt! root-id receipt incarnation)
       js/undefined)
     #js [receipt])
    ;; rf2-vxgfnd.275 — the ROOT-LEVEL host-teardown sentinel. A mount-lifetime
    ;; layout effect (empty deps → setup once, cleanup once) whose CLEANUP fires
    ;; exactly when React tears this reporter — and thus the whole root tree —
    ;; down: SYNCHRONOUSLY inside a synchronous `.unmount`, or in the deferred
    ;; microtask when react-dom 19.2 refuses an in-render unmount. Because the
    ;; reporter wraps EVERY root render, this signal exists even for a compiled
    ;; static/cell-less or entirely Activity-hidden root that owns no connected
    ;; ViewCell — the population `reactive/teardown-root!` cannot otherwise
    ;; observe. It lets an in-flight teardown recognise a SYNCHRONOUS host
    ;; teardown (release the claim now) from a DEFERRED one (hold `:tearing-down`
    ;; until settlement). Keyed to `incarnation` so a fired-later cleanup names
    ;; the exact root generation.
    (react/useLayoutEffect
     (fn root-teardown-sentinel []
       ;; SETUP runs on COMMIT: a rendered root tree now exists whose host
       ;; teardown React will signal — mark the reporter live so teardown-root!
       ;; awaits it (fences a deferred teardown of even a cell-less root). An
       ;; unrendered/pre-commit root never reaches here, so it has no pending
       ;; teardown to await and settles synchronously.
       (reactive/report-root-commit! incarnation)
       (fn cleanup []
         (reactive/report-root-teardown! incarnation)
         js/undefined))
     #js []))
  (.-children props))

(defn- render-attempt-element
  "The exact element a root's `.render` receives for one attempt: the root
  incarnation-provided `element` wrapped in the `root-commit-reporter` carrying
  this attempt's `receipt` (plus its `root-id` + `incarnation`, so the commit can
  clear the exact pending-attempt slot — rf2-vxgfnd.264), so the preflight attempt
  finalizes only after React commits this render (rf2-vxgfnd.248), never merely
  because `.render` returned. `element-thunk` is still evaluated eagerly by the
  caller, so a synchronous throw from it (a top-region provider naming an absent
  frame) still propagates into the caller's rollback try/catch unchanged."
  [receipt root-id incarnation element]
  (react/createElement root-commit-reporter
                       #js {:rfReceipt receipt :rfRootId root-id :rfIncarnation incarnation}
                       (viewcell/provide-root-incarnation incarnation element)))

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
  then a REVALIDATION of adapter admission (rf2-vxgfnd.199) and a RE-CHECK
  of the claim, then `createRoot` + registration + render.
  A preflight failure therefore fails the mount loudly with the container
  untouched — no React root, no live-root registration, no render; retry =
  re-call `mount`. Preflight also drains `:initial-events` (arbitrary app
  code) that may DESTROY or destroy-and-replace the adapter; the exact
  adapter-generation receipt captured before preflight is revalidated after
  it (rf2-vxgfnd.199), so an attempt admitted under generation A never
  allocates a Root under a disposed A or a replacement generation B. The
  re-check (rf2-vxgfnd.52) closes the re-entrancy
  window that preflight opens: `run-preflight!` drains `:initial-events`
  synchronously (arbitrary app code) and a re-entrant mount can claim this
  root-id / container / identifier-prefix while this mount is still
  unregistered; the second `check-root-claim!` detects that ownership
  change and fails loud BEFORE the unconditional register, rather than
  clobbering the inner root's entry (which the rollback would then delete,
  orphaning the inner root's live tree). Registration still precedes any
  render (contract §7 Layer 3);
  if that FIRST render throws SYNCHRONOUSLY (the element thunk, or host
  `.render` before React receives the element), the mount rolls back as one
  exact-incarnation teardown transaction: the claim becomes `:tearing-down`
  before host cleanup; a normal cleanup releases it at the next FIFO microtask,
  while a cleanup throw force-deads framework ownership and leaves the host
  claim quarantined fail-closed. The original error stays primary and cleanup
  is attached as canonical secondary evidence. Preflight evidence is finalized only
  when React actually COMMITS the render, through the `root-commit-reporter`
  wrapper (rf2-vxgfnd.248): a first render whose element thunk succeeds but
  whose component throws during React's render (onUncaughtError aborts the
  commit AFTER `.render` returned) is left uncommitted — never a phantom
  committed root scope. A failed RE-render on an already-live root is the
  distinct case: the existing root stays registered with its last committed
  render intact (Q49).
  Returns the Root."
  [{:keys [root-id] :as info} container element-thunk react-opts plans-thunk]
  (require-root-creation-open! 're-frame.ui/mount)
  (require-container! 're-frame.ui/mount root-id container)
  (let [existing (get @live-roots root-id)]
    ;; rf2-vxgfnd.182 — a `:tearing-down` entry is NOT eligible for the
    ;; idempotent re-mount fast path (its React tree is being unmounted). Fall
    ;; through to `check-root-claim!`, which rejects the reentrant mount with the
    ;; tearing-down diagnostic rather than `.render`ing into a dying root.
    (if (and existing (not (:tearing-down? existing))
             (identical? (:container existing) container))
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
        (let [receipt     (run-preflight! root-id plans-thunk)
              incarnation (root-incarnation-of root-id)]
          (try
            ;; rf2-vxgfnd.69: run-preflight! drained :initial-events SYNCHRONOUSLY
            ;; (arbitrary app code) that may have unmount!ed this root and mounted
            ;; a replacement under the same id/container. Revalidate ownership of
            ;; the CAPTURED handle before the re-render — a superseded root must
            ;; never commit, and `.render`ing the stale handle would write into a
            ;; root the replacement now owns. Mirrors the fresh-path re-check.
            (require-live-root! root 're-frame.ui/mount)
            ;; rf2-vxgfnd.264: seat this attempt as the root's single pending
            ;; render-attempt owner BEFORE `.render`, so a host error / supersession
            ;; / unmount that aborts the commit still terminally settles the receipt.
            (seat-pending-attempt! root-id receipt incarnation)
            ;; rf2-vxgfnd.139/.248: wrap the render in the host-commit reporter so
            ;; the attempt's evidence is finalized (clear stale tags + mark
            ;; committed) only when React actually COMMITS this render, never
            ;; merely because `.render` returned. No rollback on this same-root
            ;; path: the existing root stays registered with its last committed
            ;; render intact even if this re-render never commits.
            (.render (.-react-root root)
                     (render-attempt-element
                      receipt root-id incarnation (element-thunk)))
            root
            (catch :default e
              ;; the re-render (or the post-preflight ownership fence) threw AFTER
              ;; preflight completed. The existing live root + its last committed
              ;; render are untouched (Q49); record only the FAILED ATTEMPT — an
              ;; already-committed refresh stays :committed + :preflight-attempt-
              ;; failed, never falsely mount-incomplete (rf2-vxgfnd.139) — and clear
              ;; the seated slot (rf2-vxgfnd.264).
              (abort-seated-attempt! root-id receipt incarnation)
              (throw e)))))
      (do
        (check-root-claim! 're-frame.ui/mount info container)
        ;; preflight BEFORE any React work (Q49: container untouched on
        ;; failure) — and before registration, so a failed mount leaves
        ;; no registry entry either.
        ;; rf2-vxgfnd.199: capture the EXACT adapter generation admitting this
        ;; fresh mount BEFORE the side-effecting preflight, so re-entrant
        ;; preflight code that destroys (or destroys and replaces) the adapter
        ;; cannot let this attempt allocate a Root under a generation that never
        ;; admitted it.
        (let [adapter-receipt (current-adapter-generation)
              receipt (run-preflight! root-id plans-thunk)
              ;; rf2-vxgfnd.264: mint the root incarnation BEFORE createRoot so the
              ;; onUncaughtError wrapper closes over the EXACT incarnation it settles,
              ;; and the register + the pending-attempt seat all share it.
              incarnation (reactive/make-root-incarnation)]
          (try
            ;; REVALIDATE adapter admission FIRST — before the root-claim
            ;; re-check and any React work (rf2-vxgfnd.199). A terminal disposal
            ;; or a destroy+reinstall during preflight fails loud before
            ;; createRoot / registration / DOM; a boolean recheck alone cannot
            ;; see a same-breadcrumb replacement generation.
            (require-adapter-generation-open! 're-frame.ui/mount adapter-receipt)
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
            (let [react-root (rdc/createRoot
                              container
                              (react-opts-with-attempt-abort react-opts root-id incarnation))
                  root       (Root. react-root container root-id)]
              ;; register BEFORE any render (contract §7 Layer 3) — installs the
              ;; incarnation minted above that scopes every ViewCell to this root and
              ;; drives the onUncaughtError receipt-abort wrapper (rf2-vxgfnd.264).
              (register-live-root! info container root incarnation)
              ;; rf2-vxgfnd.264: seat this attempt as the root's single pending
              ;; render-attempt owner BEFORE `.render`, so a host error / unmount that
              ;; aborts the commit still terminally settles the receipt.
              (seat-pending-attempt! root-id receipt incarnation)
              (try
                ;; rf2-vxgfnd.139/.248: wrap the FIRST render in the host-commit
                ;; reporter so the attempt's evidence binds to React's real
                ;; COMMIT of this render, not the `.render` return. If the element
                ;; thunk returns but a component then throws during React's render
                ;; (onUncaughtError aborts the commit after `.render` returned),
                ;; the reporter's layout effect never runs, so the fresh install is
                ;; left uncommitted — never falsely recorded as a committed root
                ;; scope, and the seated receipt is terminally settled
                ;; :mount-incomplete by the onUncaughtError edge (rf2-vxgfnd.264).
                (.render react-root
                         (render-attempt-element
                          receipt root-id incarnation (element-thunk)))
                root
                (catch :default e
                  ;; rf2-vxgfnd.291 — the failed first mount enters the SAME
                  ;; exact-incarnation lifecycle as normal teardown. Retain and
                  ;; mark the claim BEFORE cleanup; normal return settles at the
                  ;; next FIFO microtask (there is no committed reporter), while
                  ;; cleanup throw force-deads framework ownership and leaves
                  ;; the host claim quarantined. The original mount error stays
                  ;; primary; cleanup is canonical secondary evidence.
                  (rollback-failed-first-mount! root-id root incarnation e)
                  (throw e))))
            (catch :default e
              ;; rf2-vxgfnd.139: preflight completed but the post-preflight
              ;; boundary threw (the re-entrancy re-check, or the first-render
              ;; rollback above rethrowing). A fresh install whose host mount
              ;; never committed is :mount-incomplete — no root scopes it — never
              ;; a phantom completed installer. Rev-guarded: a re-entrant root
              ;; that took ownership of the same frame is untouched. Clears the
              ;; seated slot too when still present (rf2-vxgfnd.264).
              (abort-seated-attempt! root-id receipt incarnation)
              (throw e))))))))

(defn create-root*
  "Runtime half of `ui/create-root` — claim identity + container and
  build the React root; no render (preflight runs before the first
  `render!`). Returns the Root."
  [{:keys [root-id] :as info} container react-opts]
  (require-root-creation-open! 're-frame.ui/create-root)
  (check-root-claim! 're-frame.ui/create-root info container)
  (let [incarnation (reactive/make-root-incarnation)
        ;; rf2-vxgfnd.264: install the onUncaughtError receipt-abort wrapper at
        ;; createRoot (closing over this incarnation) so a later `render!*`'s
        ;; host-aborted attempt is terminally settled — the render seats onto THIS
        ;; entry's incarnation.
        react-root  (rdc/createRoot
                     container
                     (react-opts-with-attempt-abort react-opts root-id incarnation))
        root        (Root. react-root container root-id)]
    (register-live-root! info container root incarnation)
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
  a replacement root. Preflight evidence is finalized only when React COMMITS
  the render, through the `root-commit-reporter` wrapper (rf2-vxgfnd.248): a
  re-render whose component throws during React's render (the commit aborts
  after `.render` returned) leaves the prior committed record untouched — the
  attempt is never falsely marked committed off a `.render` return."
  [^Root root element-thunk plans-thunk descriptor-base]
  (require-live-root! root 're-frame.ui/render!)
  (let [rid         (.-root-id root)
        receipt     (run-preflight! rid plans-thunk)
        incarnation (root-incarnation-of rid)]
    (try
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
      ;; rf2-vxgfnd.264: seat this attempt as the root's single pending
      ;; render-attempt owner BEFORE `.render` (a host error / supersession /
      ;; unmount aborting the commit then terminally settles the receipt).
      (seat-pending-attempt! rid receipt incarnation)
      ;; rf2-vxgfnd.139/.248: wrap the render in the host-commit reporter so the
      ;; attempt finalizes at React's real COMMIT of this render, not the
      ;; `.render` return. A live root's re-render that never commits (an
      ;; uncaught render throw) leaves its prior committed record untouched.
      (.render (.-react-root root)
               (render-attempt-element
                receipt rid incarnation (element-thunk)))
      root
      (catch :default e
        ;; the post-preflight ownership fence, element thunk, or host render
        ;; threw AFTER preflight completed. A live root's failed re-render leaves
        ;; its last committed render untouched (Q49); record only the failed
        ;; ATTEMPT (rf2-vxgfnd.139) — rev-guarded, never falsely mount-incomplete
        ;; on an already-committed root, never clobbering a superseding root — and
        ;; clear the seated slot (rf2-vxgfnd.264).
        (abort-seated-attempt! rid receipt incarnation)
        (throw e)))))

(def ^:private ssr-artefact
  ;; The optional-artefact identity the fail-loud missing-hook error reports.
  ;; Mirrors `re-frame.core-ssr/ssr-artefact` (core owns its private copy for
  ;; the `rf/*` wrappers) exactly as `re-frame.ui.route-link-seam` carries its
  ;; own routing copy — the ui artefact names the missing ssr artefact at the
  ;; hydrate site without reaching into core internals.
  {:error-keyword :rf.error/ssr-artefact-missing
   :maven         "day8/re-frame2-ssr"
   :require-ns    "re-frame.ssr"})

(defn- discover-root-manifest!
  "Resolve the `:ssr/discover-root-manifest` hook and read `container`'s Root
  Manifest — the validated manifest, or nil when there is none.

  `hydrate-root` is a BOOT-TIME caller, so this is the plain unmemoised
  resolution (Conventions §Late-bind hook key grammar rule 6): no cached
  resolution, because this is not a hot path. `require-fn!` supplies the
  hook-absent throw — `:rf.error/ssr-artefact-missing`, carrying the
  copy-pasteable `day8/re-frame2-ssr` coordinate and the `re-frame.ssr`
  require-ns — so an app that compiled `ui/hydrate-root` without the SSR
  artefact on its classpath is told exactly what to add."
  [container]
  ((late-bind/require-fn! :ssr/discover-root-manifest
                          're-frame.ui/hydrate-root
                          ssr-artefact)
   container))

(defn- react-opts-with-identifier-prefix
  "Return a fresh copy of `react-opts` carrying the SERVER-AUTHORED
  `identifierPrefix`. Hydration cannot choose its own prefix: React's `useId`
  output is derived from it, so a client prefix that disagrees with the one the
  server render used produces different ids and breaks hydration. The compiled
  `hydrate-root` call site therefore emits `nil` for the prefix (identity opts
  client-side are a COMPILE error) and the effective value arrives here, from
  the manifest's content. A manifest that carries no `:identifier-prefix` means
  the server used none, so none is set — never a synthesised default, which
  would be exactly the disagreement this guards."
  [react-opts identifier-prefix]
  (let [o (if react-opts (js/Object.assign #js {} react-opts) #js {})]
    (when (some? identifier-prefix)
      (unchecked-set o "identifierPrefix" identifier-prefix))
    o))

(defn hydrate-root*
  "Runtime half of `ui/hydrate-root` — the compiled-view DOM-ADOPTION call.

  Hydrating mounts read identity FROM the server-emitted Root Manifest adjacent
  to the container, never from opts (identity opts at a hydrate site are a
  compile error) and never from the container element itself. Discovery lives in
  the optional SSR artefact and is reached through the `:ssr/discover-root-
  manifest` late-bind hook (rf2-3omxp) — `ui -> core registry <- ssr`, the same
  shape `route-link` uses against routing; a direct require is forbidden by the
  Independence rule and would fail to compile every non-SSR ui app.

  RESPONSIBILITY BOUNDARY (Spec 011 §Client-side hydration boot helper). This
  function adopts server-rendered DOM; it does NOT install the hydration
  payload. `re-frame.ssr/hydrate!` remains the explicit SSR state-boot call —
  it reads `__rf_payload`, installs it idempotently, dispatches `:rf/hydrate`,
  and verifies. The public boot is therefore TWO calls, in order: `ssr/hydrate!`
  then `ui/hydrate-root`, so the first compiled render already sees the server's
  app-db.

  Two distinct failures, neither of them a new error id:

    - the SSR artefact is ABSENT — `:rf.error/ssr-artefact-missing` from
      `require-fn!`, naming `day8/re-frame2-ssr`;
    - the artefact is present but discovery finds NO adjacent manifest —
      `:rf.error/root-manifest-invalid` `{:missing :manifest}`. There is
      nothing left to hydrate *as*, so this fails loud rather than guessing
      identity (Spec 011 §Discovery). A corrupt manifest already threw the same
      id out of the SSR artefact's own `validate!`.

  ORDER mirrors `mount*`'s fresh path — claim check, preflight, adapter-
  admission revalidation, claim re-check, then React — with ONE forced
  difference: React fuses root creation and the initial render for hydration
  (`hydrateRoot` takes the children as its second argument), so registration and
  the pending-attempt seat necessarily follow the call rather than precede the
  render. The incarnation is minted BEFORE `hydrateRoot` and baked into the
  element, so every ViewCell is scoped to this root regardless of that ordering;
  registration and seating are plain `swap!`s with no user code between them and
  the call, so they complete before React can flush the scheduled hydration
  work. A synchronous throw from the element thunk or `hydrateRoot` settles the
  preflight receipt and leaves no registry entry (there is nothing to roll back
  — registration had not happened)."
  [container element-thunk plans-thunk react-opts]
  (require-root-creation-open! 're-frame.ui/hydrate-root)
  ;; The root-id is genuinely unknown until the manifest is read, so a nil
  ;; container is reported before discovery — otherwise `discover` would walk
  ;; `nil.nextElementSibling`, return nil, and the honest "you handed me no
  ;; container" fault would masquerade as "no manifest here".
  (require-container! 're-frame.ui/hydrate-root nil container)
  (let [manifest (discover-root-manifest! container)]
    (when (nil? manifest)
      (error/throw-error!
       :rf.error/root-manifest-invalid 're-frame.ui/hydrate-root
       (str "no root manifest is discoverable — hydrating mounts take root-id "
            "and identifier-prefix from the manifest the server render emits "
            "as the container's immediately following element sibling, and "
            "nothing was found there. Use ui/mount for a client-only root")
       {:recovery :use-ui-mount
        :extra {:missing :manifest}}))
    (let [root-id (:root-id manifest)
          prefix  (:identifier-prefix manifest)
          info    {:root-id           root-id
                   :provenance        :manifest
                   :identifier-prefix prefix}]
      (check-root-claim! 're-frame.ui/hydrate-root info container)
      ;; rf2-vxgfnd.199 — capture the EXACT adapter generation admitting this
      ;; hydrate BEFORE the side-effecting preflight, and revalidate after.
      (let [adapter-receipt (current-adapter-generation)
            receipt         (run-preflight! root-id plans-thunk)
            incarnation     (reactive/make-root-incarnation)]
        (try
          (require-adapter-generation-open! 're-frame.ui/hydrate-root
                                            adapter-receipt)
          ;; rf2-vxgfnd.52 — preflight drained `:initial-events` synchronously
          ;; (arbitrary app code) which may have claimed this id / container /
          ;; prefix. Re-check before any React work.
          (check-root-claim! 're-frame.ui/hydrate-root info container)
          ;; rf2-6z1i2 — the root-local ADOPTION-WINDOW flag. It starts `true`
          ;; and the `PhaseFlipper` clears it on the hydration (`:server`) commit;
          ;; the `onRecoverableError` wrapper reads it to classify a recoverable
          ;; error as a compiled-tier `:rf.ssr/hydration-mismatch` ONLY while
          ;; React is still adopting the server DOM (before the flip). Shared by
          ;; both ends here — the flipper (via `with-phase-flip`) and the wrapper
          ;; (via `react-opts-with-hydration-mismatch`).
          (let [adoption-ref #js {:adopting true}
                react-root (rdc/hydrateRoot
                            container
                            ;; rf2-3omxp — a HYDRATING root is the one root kind
                            ;; that flips: wrap the compiled element in the phase
                            ;; flipper, which boots `:server` (first render =
                            ;; fallbacks, matching the server markup) and flips to
                            ;; `:client` as its next ordinary update after the
                            ;; hydration commit. `(element-thunk)` still evaluates
                            ;; first, so a synchronous throw from it propagates to
                            ;; the rollback catch unchanged. Non-hydrating mounts
                            ;; (mount* / render!*) wrap nothing and never flip.
                            (render-attempt-element
                             receipt root-id incarnation
                             (runtime/with-phase-flip root-id (element-thunk)
                                                      adoption-ref))
                            ;; rf2-6z1i2 — compose the compiled-tier hydration-
                            ;; mismatch onRecoverableError wrapper (adoption-window
                            ;; scoped) over the host's react-opts, then the .264
                            ;; onUncaughtError edge. Hydrating roots only.
                            (react-opts-with-attempt-abort
                             (react-opts-with-hydration-mismatch
                              (react-opts-with-identifier-prefix react-opts prefix)
                              root-id adoption-ref)
                             root-id incarnation))
                root       (Root. react-root container root-id)]
            (register-live-root! info container root incarnation)
            (seat-pending-attempt! root-id receipt incarnation)
            root)
          (catch :default e
            ;; The element thunk, the claim fence, or the host hydrate threw.
            ;; No registry entry exists on this path, so `abort-seated-attempt!`
            ;; does exactly what is owed: settle the receipt terminally (it
            ;; aborts unconditionally, seated or not) and clear nothing.
            (abort-seated-attempt! root-id receipt incarnation)
            (throw e)))))))

(defn unmount!*
  "Runtime half of `ui/unmount!` — TOTAL teardown: unmount the React root
  and release the root claim (contract §7). Idempotent: a Root already released,
  superseded in the registry, OR already `:tearing-down` is a no-op. The latter
  can be reporter-authoritative deferred settlement or cleanup-failure quarantine;
  only adapter-wide destruction may reclaim the quarantine's consumed host surface.
  A newly-started claim is released at
  the SETTLEMENT BOUNDARY through `teardown-root!`'s `on-settled` callback:
  synchronously for a synchronous host teardown, or in the settlement microtask
  after a DEFERRED one clears the DOM. A THROWING host `.unmount` QUARANTINES the
  exact root-id/container/prefix claim `:tearing-down` (rf2-vxgfnd.275 — fail
  closed: the container cannot be proven free), a second `unmount!*` is then a
  no-op, and the host teardown error still propagates to the caller.

  OWNERSHIP FENCE through DEFERRED teardown (rf2-vxgfnd.182). react-dom 19.2
  refuses a synchronous `.unmount` from inside render/commit: it consumes the
  handle and returns NORMALLY while the teardown work stays scheduled for a later
  microtask. The claim is marked `:tearing-down` BEFORE the host `.unmount`, so
  during that deferred window a reentrant `mount*`/`create-root*` on the exact
  root-id/container is REJECTED (`check-root-claim!`) rather than admitted onto a
  container React is about to clear. `teardown-root!` observes deferral through
  the committed root reporter's cleanup sentinel and holds `on-settled` until
  React's teardown settles; a SYNCHRONOUS teardown fires
  `on-settled` at once, freeing the claim before this fn returns and preserving
  the ratified same-container immediate re-mount.

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

  AFTERMATH of a THROWING host `.unmount` (rf2-vxgfnd.53/.84; fail-closed by
  rf2-vxgfnd.275). The concrete case is React 18/19 refusing to `.unmount`
  synchronously from inside a render pass (\"attempted to synchronously unmount a
  root while React was already rendering\"): the synchronous unmount did not
  complete, so the claim is FAILED CLOSED — left QUARANTINED `:tearing-down`
  (marked before the `.unmount`), NOT released, because the container cannot be
  proven free and a released claim could be clobbered by React self-completing
  the aborted teardown onto a container a successor now owns. A second `unmount!*`
  is a no-op (the membership guard). Two facts, verified against the pinned
  react-dom 19.2.0 source, shape what that aftermath actually IS:

    - the root handle is CONSUMED: `ReactDOMRoot.unmount()` nulls its
      `_internalRoot` BEFORE the flush that throws, so re-calling `.unmount`
      on the SAME handle is a SILENT NO-OP — there is NO manual escape
      through the consumed handle (the earlier diagnostic recommended exactly
      that no-op, which never tears anything down);
    - React usually DEFERS and self-completes the teardown work it scheduled
      before the throw, so the tree is typically removed regardless — the
      \"stuck host root\" is mostly moot, not the common outcome.

  So this is NOT silent, and now HONEST: in a dev build a throwing `.unmount`
  emits a console diagnostic stating the handle is consumed (no manual escape),
  that the id/container/prefix claim is quarantined rather than released, and
  that recovery is a FRESH container. The adapter-wide destroy path performs a
  container-clearing fallback itself (including the pinned React root marker) and
  releases the id/identifier-prefix for a same-id re-mount on a FRESH container —
  but the EXACT container node stays FAIL-CLOSED (`consumed-containers`, rf2-sddbc):
  clearing its DOM is a snapshot, not proof that host authority the throwing
  `.unmount` may have QUEUED before it threw has settled, so reusing that exact
  node is rejected (`:rf.error/root-container-consumed`). An isolated public
  `unmount!` leaves host recovery to its caller. The diagnostic is goog.DEBUG-gated
  (Closure-DCE'd from production — I-12). Returns nil."
  [^Root root]
  (when (and (some? root)
             (let [entry (get @live-roots (.-root-id root))]
               (and (identical? (:root entry) root)
                    ;; A teardown already in flight is not re-driven: a deferred
                    ;; claim remains reporter-owned, while a cleanup-failure
                    ;; quarantine has a consumed handle and is reclaimable only
                    ;; by adapter-wide destruction.
                    (not (:tearing-down? entry)))))
    ;; rf2-vxgfnd.182 — fence the exact claim BEFORE the host `.unmount`, so a
    ;; deferred host teardown cannot advertise the id/container as free while
    ;; React is still scheduled to clear the DOM.
    (mark-tearing-down! (.-root-id root) root)
    ;; rf2-vxgfnd.264 — a render attempt seated but not yet committed when the
    ;; root is torn down never commits: TERMINALLY settle it now (a fresh install's
    ;; :fresh writes → :mount-incomplete), rather than leaving it forever
    ;; provisional. Identity-guarded to this root's incarnation.
    (abort-pending-attempt! (.-root-id root) (root-incarnation-of (.-root-id root)))
    (try
      ;; rf2-vxgfnd.62 — drive the host unmount through the root-teardown window
      ;; so this root's ViewCells are retroactively proven :unmounted → :dead
      ;; after React sweeps their effect cleanups (see the docstring LIFECYCLE
      ;; note). teardown-root! rethrows a throwing host `.unmount` unchanged, so
      ;; the catch below keeps the rf2-vxgfnd.53 ownership behaviour.
      ;; rf2-vxgfnd.85/.92 — pass this root's INCARNATION so a cell Activity-hidden
      ;; BEFORE the unmount window (which the window can never capture) is still
      ;; reaped through the root-incarnation ownership registry, even when the
      ;; whole root was hidden and the window collects nothing.
      ;; rf2-vxgfnd.182 — `on-settled` releases the exact claim at the settlement
      ;; boundary: inline for a synchronous teardown, or via the settlement
      ;; microtask after a deferred one clears the container.
      (reactive/teardown-root! (root-incarnation-of (.-root-id root))
                               (fn []
                                 (try
                                   (.unmount (.-react-root root))
                                   (catch :default e
                                     (mark-cleanup-failure-quarantine!
                                      (.-root-id root) root)
                                     (throw e))))
                               (fn [] (release-root! (.-root-id root) root)))
      (catch :default e
        (mark-cleanup-failure-quarantine! (.-root-id root) root)
        ;; rf2-vxgfnd.275 — the host `.unmount` THREW: React consumed the Root
        ;; handle but its teardown flush aborted, and whether the container is
        ;; actually settled is UNKNOWABLE in-process. FAIL CLOSED — do NOT
        ;; release. The claim stays QUARANTINED `:tearing-down` (marked above,
        ;; before the `.unmount`), so a same-id / same-container / same-prefix
        ;; reuse is rejected (`check-root-claim!`) and the honest recovery is a
        ;; FRESH container. This REVERSES the earlier immediate-release
        ;; (rf2-vxgfnd.18/.84): a claim released here could be clobbered by React
        ;; self-completing the aborted teardown onto a container a successor now
        ;; owns. The exact generation is already force-dead by `teardown-root!`.
        ;; A second `unmount!*` is a no-op (the :tearing-down guard); the primary
        ;; host error still propagates. Dev-only diagnostic (goog.DEBUG-stripped).
        (when (and ^boolean js/goog.DEBUG (exists? js/console))
          (.warn js/console
                 (str "[re-frame.ui] host .unmount threw while tearing down root "
                      (pr-str (.-root-id root)) " — React did NOT complete the "
                      "synchronous unmount, and the root handle is now CONSUMED: "
                      "ReactDOMRoot.unmount nulls its internal root BEFORE the "
                      "flush that threw, so re-calling .unmount on this handle is "
                      "a silent no-op — there is no manual escape through it. "
                      "Because the container cannot be proven free, this root's "
                      "id/container/identifierPrefix claim is QUARANTINED "
                      "(tearing-down) rather than released, so a reused id or "
                      "container fails loud instead of racing React's aborted "
                      "teardown. Mount into a FRESH container to recover; a "
                      "second unmount! is a no-op.")))
        (throw e))))
  nil)

;; PRESENCE, not JS truthiness, decides whether a container-reclaim step failed
;; (rf2-s2cfv). A reclaim step can throw a legitimately falsy value (false/nil);
;; the failure slot holds this identity sentinel until a throw records into it, so
;; the FIRST failure is preserved by presence — a later step never overwrites a
;; falsy first failure, and a falsy failure is still rethrown rather than silently
;; swallowed.
(defonce ^:private no-reclaim-failure #js {})

(defn- reclaim-consumed-container!
  "Adapter-disposal fallback after a public Root's host unmount threw.

  React 19 can consume the Root handle and throw before it clears either DOM
  children or its private `__reactContainer$…` ownership marker. There is no
  supported retry through the consumed handle. The adapter owns the terminal
  process teardown, so it clears both pieces against the PINNED React host. That
  clearing is a SNAPSHOT, NOT proof the surface settled — a throwing `.unmount`
  may have QUEUED late host DOM work (a scheduled `replaceChildren`) before it
  threw — so it does NOT license same-container re-init: the exact node is
  recorded fail-closed (`consumed-containers`) and terminally denied, while only
  the id/identifier-prefix free for a same-id re-mount on a FRESH container (see
  `reclaim-and-retire-quarantine!`). Any fallback failure propagates as
  secondary cleanup evidence, never over the host error.
  The FIRST reclaim failure is preserved BY PRESENCE (not truthiness), so a
  legitimately falsy thrown value (false/nil) is neither overwritten by a later
  step nor silently swallowed at the rethrow (rf2-s2cfv)."
  [^Root root]
  (let [container (.-container root)
        failure   (volatile! no-reclaim-failure)]
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
          ;; keep the FIRST failure by PRESENCE — never let this secondary step
          ;; overwrite a falsy (false/nil) first failure.
          (when (identical? @failure no-reclaim-failure) (vreset! failure e)))))
    ;; rethrow by PRESENCE so a falsy first failure still propagates.
    (when-not (identical? @failure no-reclaim-failure) (throw @failure))
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

(defn- reclaim-and-retire-quarantine!
  "Terminal recovery for one exact cleanup-failure quarantine whose host handle
  React consumed: adapter-reclaim its exact container, retire that spent reporter
  authority, FAIL-CLOSE the exact container, then release the identity-fenced claim.
  The `reclaim → retire → mark-consumed → release` order is fixed — the steps below
  the reclaim run only once it returns, and a FAILED reclaim throws above them,
  skipping ALL of them so the claim stays quarantined AND reporter-authoritative
  fail-closed (rf2-nd7z9h; while quarantined the still-`:tearing-down` claim itself
  fences the exact container).

  rf2-sddbc — the reclaim CLEARS the container's DOM + React ownership marker, but
  that is a SNAPSHOT, not proof that host authority the throwing `.unmount` QUEUED
  before it threw (a scheduled `replaceChildren`) has settled. So the release frees
  ONLY the identity (root-id + identifier-prefix) for a same-id re-mount on a fresh
  container; the exact container node is recorded in `consumed-containers` and stays
  FAIL-CLOSED against reuse (`check-root-claim!` → `:rf.error/root-container-consumed`).
  Recording BEFORE the release means the exact node is never momentarily reusable
  between leaving `live-roots` and entering the consumed denylist.

  Retirement targets the EXACT `incarnation` CAPTURED before the reclaim — never
  a fresh registry read. `reclaim-consumed-container!` runs synchronous host code
  (`replaceChildren`), during which a custom-element callback or a low-level/test
  seam can install a same-id SUCCESSOR into `live-roots`. A bare-id lookup after
  the reclaim would then resolve to that innocent successor: it would retire the
  successor's reporter while `release-root!`'s identity fence correctly preserves
  the successor's claim — leaving it live WITHOUT reporter authority (its teardown
  could settle before React's cleanup) and strongly retaining the failed
  predecessor's dead token. Passing the captured predecessor `incarnation` keeps
  retirement bound to the predecessor, so a bypass-installed successor is never
  disturbed and the stale snapshot never owns a replacement it did not admit
  (rf2-j7225). Returns nil."
  [root-id ^Root root incarnation]
  (reclaim-consumed-container! root)
  (reactive/retire-root-reporter! incarnation)
  (mark-container-consumed! (.-container root))
  (release-root! root-id root)
  nil)

(defn drain-live-roots!
  "Drain one exact snapshot of every public compiled client Root.

  The caller owns the surrounding root-admission fence. `unmount!*`'s identity
  guard means a stale handle can never evict a later same-id incarnation. The
  snapshot is never refreshed: stale disposal does not own a replacement that
  appeared through an internal/test bypass.

  Every active root is attempted even when one host cleanup throws. A snapshot
  entry already classified `:cleanup-failure?` is not re-unmounted through its
  consumed handle; adapter ownership directly reclaims that exact container and
  releases the quarantine. A merely deferred `:tearing-down` entry is left for
  its reporter-authoritative settlement. The first new error is rethrown after
  the whole snapshot drains; later failures are retained on its
  `rfUiAdapterCleanupErrors` diagnostic array because one teardown failure must
  neither strand siblings nor erase their evidence."
  []
  (let [entries (vec (vals @live-roots))
        errors  (volatile! [])]
    (doseq [{:keys [root]} entries]
      (let [^Root root root
            root-id (.-root-id root)
            current (get @live-roots root-id)
            exact?  (identical? (:root current) root)
            quarantined?
            (and exact?
                 (:tearing-down? current)
                 (:cleanup-failure? current))
            deferred?
            (and exact?
                 (:tearing-down? current)
                 (not (:cleanup-failure? current)))]
        (cond
          ;; A pre-existing cleanup failure was already force-dead and its host
          ;; handle consumed. Adapter destruction owns terminal container reclaim.
          quarantined?
          (try
            ;; rf2-nd7z9h / rf2-j7225 — reclaim the consumed surface, then retire
            ;; THIS captured incarnation's spent reporter authority BEFORE releasing
            ;; the claim (never re-read the registry: a successor installed during
            ;; the synchronous reclaim keeps its own reporter + claim). A FAILED
            ;; reclaim throws and skips both, keeping quarantine AND reporter
            ;; authority fail-closed.
            (reclaim-and-retire-quarantine! root-id root (:root-incarnation current))
            (catch :default recovery-error
              (vswap! errors conj recovery-error)))

          ;; Never consume reporter authority or clear a container whose deferred
          ;; React teardown is still scheduled.
          deferred?
          nil

          :else
          (try
            (unmount!* root)
            (catch :default e
              (vswap! errors conj e)
              ;; `unmount!*` publishes these at its host throw edge. Reassert at
              ;; the adapter seam as a defensive totality boundary (including
              ;; injected/test host cleanup implementations that throw directly).
              (mark-tearing-down! root-id root)
              (mark-cleanup-failure-quarantine! root-id root)
              (when (exact-cleanup-failure-quarantine? root-id root)
                (try
                  ;; `unmount!*` just classified this exact root as cleanup-failed.
                  ;; A successful adapter reclaim clears the node as a SNAPSHOT (NOT
                  ;; proof the surface is free — the exact node stays fail-closed in
                  ;; `consumed-containers`) and frees only the id/prefix for a same-id
                  ;; re-mount on a FRESH container; the identity fence prevents a
                  ;; stale snapshot releasing a successor.
                  ;; rf2-nd7z9h — retire this incarnation's reporter authority too,
                  ;; between the successful reclaim and the claim release, so the
                  ;; reporter ledger returns to baseline (a failed reclaim throws
                  ;; above and retains both quarantine and reporter authority).
                  ;; rf2-j7225 — retire the EXACT incarnation CAPTURED in `current`,
                  ;; NOT a fresh `root-incarnation-of` read: the synchronous reclaim
                  ;; can install a same-id successor, and a bare-id lookup would then
                  ;; retire the successor's reporter while the identity fence keeps
                  ;; its claim — the very stale-snapshot ownership `current` fences.
                  (reclaim-and-retire-quarantine!
                   root-id root (:root-incarnation current))
                  (catch :default recovery-error
                    (vswap! errors conj recovery-error)))))))))
    ;; PRESENCE (seq/count), not truthiness: a non-empty error vector whose FIRST
    ;; payload is falsy (false/nil) must still rethrow — `when-let` on `(first …)`
    ;; would suppress the whole set (rf2-s2cfv).
    (when (seq @errors)
      (let [primary (first @errors)]
        (when (< 1 (count @errors))
          (try
            (js/Object.defineProperty
             primary "rfUiAdapterCleanupErrors"
             #js {:value (to-array (rest @errors)) :configurable true})
            (catch :default _
              ;; a primitive primary (nil/false/number) cannot carry a diagnostic
              ;; property; keep the later failures observable via console instead,
              ;; and rethrow the primary unchanged.
              (when (exists? js/console)
                (.warn js/console
                       (str "[re-frame.ui] adapter cleanup errors could not ride "
                            "the primitive primary rejection; the primary is "
                            "rethrown unchanged. Later cleanup errors:")
                       (to-array (rest @errors)))))))
        (throw primary))))
  nil)

(defn dispose-live-roots!
  "Standalone adapter-internal teardown of the public compiled Root registry.
  The first-party composed adapter uses `with-root-admission-closed!` around
  this drain AND its generic-spine tail; this zero-arity helper preserves the
  direct/test seam with the same one-owner fencing law."
  []
  (with-root-admission-closed! drain-live-roots!)
  nil)
