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
            [re-frame.ui.frames :as frames]))

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
  "The `render!` stale-handle guard — the render-side mirror of
  `unmount!*`'s membership check. A `Root` is LIVE iff the live-root
  registry still maps its root-id to THIS exact Root; a handle whose id
  was `unmount!`ed (absent entry) or SUPERSEDED by a newer root claiming
  the same id (entry points at a different Root) is STALE. `unmount!` on
  a stale root is an idempotent no-op, but `render!` fails LOUD with
  `:rf.error/root-not-live`: a stale root can never commit a render, so
  running its frame preflight (the `:initial-events` drain — IRREVERSIBLE
  fx) and writing install records against a dead root-id would be side
  effects with NO committed render. Thrown BEFORE any side effect; retry
  = `create-root` + `render!` (or `mount`) a fresh root."
  [^Root root where]
  (let [rid (.-root-id root)]
    (when-not (identical? (:root (get @live-roots rid)) root)
      (error/throw-error!
       :rf.error/root-not-live where
       (str "render! was called on root-id " (pr-str rid) ", whose Root "
            "handle is no longer live — it was unmount!ed, or superseded "
            "by a newer root claiming the same id. A stale root can never "
            "commit a render; rendering into it would drain :initial-events "
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
        ;; re-preflight BEFORE the re-render: a failure leaves the live
        ;; root and its last committed render untouched (Q49).
        (run-preflight! root-id plans-thunk)
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
  re-call `render!`). In dev, completes the compile-time descriptor-base
  with the Root's identity on the registry entry."
  [^Root root element-thunk plans-thunk descriptor-base]
  (require-live-root! root 're-frame.ui/render!)
  (let [rid (.-root-id root)]
    (run-preflight! rid plans-thunk)
    (when ^boolean js/goog.DEBUG
      (when descriptor-base
        (swap! live-roots
               (fn [m]
                 (if-let [entry (get m rid)]
                   (assoc m rid
                          (assoc entry :descriptor
                                 (assoc descriptor-base
                                        :root-id rid
                                        :root-id-provenance (:provenance entry))))
                   m)))))
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
  host teardown error still propagates to the caller. Returns nil."
  [^Root root]
  (when (and (some? root)
             (identical? (:root (get @live-roots (.-root-id root))) root))
    (try
      (.unmount (.-react-root root))
      (finally
        (release-root! (.-root-id root) root))))
  nil)
