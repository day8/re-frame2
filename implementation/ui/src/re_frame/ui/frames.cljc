(ns re-frame.ui.frames
  "Frame wiring for the re-frame.ui substrate (S2c, rf2-vxgfnd.9) — the
  preflight ENSURE executor, the plan-install registry, the frame-scope
  element builders, and the ambient frame-resolution chain.

  ## Preflight ENSURE (03 §8: host preflight, never render)

  `execute-frame-plans!` is the LIVE consumer behind the
  `re-frame.ui.client` preflight seam: at every `ui/mount` / `ui/render!`
  (and, when S5 lands, `ui/hydrate-root`) the root descriptor's static
  frame plans execute BEFORE the host renderer is invoked — create the
  frame if absent, drain `:initial-events` exactly once, and record the
  plan's config fingerprint. The emitted `frame-root` component then only
  SCOPES the already-live frame. Because ENSURE happens at host preflight,
  it is untouched by abandoned renders, StrictMode replay, HMR, and error
  recovery — there is nothing render-phased to replay.

  Two authorities, kept DISTINCT (rf2-vxgfnd.56). An INSTALLED record
  means this root OWNS the frame lifetime and may refresh it; an ADOPTED
  record means the frame is boot/externally authoritative and the root
  merely SCOPES it — adoption never grants refresh rights.

  Per-plan disposition (document order; conflicts validated FIRST):

    - NO live frame         -> INSTALL: `make-frame` creates it
      (create-if-absent), then — only while that created incarnation is still
      live — record `{:config-fingerprint :installed-by}` (root-OWNED
      authority). A setup that self-destroys the frame it is seating fails the
      preflight CLOSED (`:rf.error/frame-preflight-lifecycle-loss`, rf2-5svfa1)
      rather than returning over an absent frame.
    - LIVE frame, NO install
      record (a boot `rf/make-frame`
      the plan registry never saw):
        · CONFIG-LESS plan `[frame-root {:id …}]` -> ADOPT: create-if-
          absent means create NOTHING (03 §8 / 004C §6). The boot frame's
          OWN config + image generation are AUTHORITATIVE and left
          UNTOUCHED — no `make-frame`, no clobber, no `:images` discard,
          no re-seed. An ADOPTED record `{:config-fingerprint :adopted-by
          :adopted true}` is written (for cross-root conflict scoping)
          that can NEVER enter a refresh path. This is the shared-frame
          case: an app boots the frame with its images / `:fx-overrides`
          and a root declares `[frame-root {:id …}]` merely to SCOPE it.
          (rf2-vxgfnd.26: adopting via a config-carrying `make-frame` used
          to swap the generation to the default and wipe the boot config —
          the durable app-db survived, masking the clobber.)
        · CONFIG-BEARING plan -> `:rf.error/frame-payload-conflict`: the
          plan would silently discard its config onto a boot-authoritative
          frame. Fail loud instead (rf2-vxgfnd.56) — either boot the frame
          config-less and scope it, or drop the boot `make-frame` and let
          the frame-root own the lifetime.
    - ADOPTED frame, later
      SAME-root plan:
        · CONFIG-LESS  -> found live: PURE NO-OP.
        · CONFIG-BEARING -> `:rf.error/frame-payload-conflict`: an adopt
          record confers no ownership, so a later config edit can NOT
          `make-frame`/refresh over the boot config (rf2-vxgfnd.56).
    - SAME fingerprint      -> found live: PURE NO-OP (the ratified
      idempotent install — no re-seed, no config churn, no trace noise).
      This is the HMR / remount / shared-frame non-reseed guarantee.
    - DIFFERENT fingerprint,
      SAME installing root  -> REFRESH (INSTALLED records only): the root
      re-declares its OWN frame (an HMR config edit). `make-frame`'s
      surgical update applies the new config; durable state survives;
      `:initial-events` are RE-RECORDED, never REPLAYED (EP-0027). The
      recorded fingerprint advances. (The §7 conflict names two DIFFERENT
      parties — `:installed-by` — so same-party re-declaration is the
      Clojure re-def model, exactly the Layer-1 same-file watch tolerance.)
    - DIFFERENT fingerprint,
      DIFFERENT root        -> `:rf.error/frame-payload-conflict`:
      fail THE ARRIVING ROOT; the installed frame and the roots already
      using it are untouched (06 §2 failure scoping). No first-wins
      silent merge, no last-wins overwrite.

  A destroyed frame invalidates its install record — the
  `:ui/on-frame-destroyed!` hook PRUNES it (rf2-vxgfnd.30 (a)), so a boot
  re-create of the same id can no longer resurrect a dead-lifetime record
  as a false conflict. Publication uses the same incarnation's lifecycle
  gate, so an `:initial-events` handler that destroys its own frame cannot
  re-add the dead lifetime's record after the hook prunes it. A later plan
  for the same id is a genuinely new frame lifetime — created fresh,
  `:initial-events` REPLAYED (the opt-in destroy-then-reseat composition,
  rf2-lxwpob).

  ## Attempt evidence, bound to the host commit boundary (rf2-vxgfnd.139)

  An install record proves plan AUTHORITY, not a COMMITTED React root. Attempt
  evidence therefore binds to two axes the plan action alone cannot see:

    - the EXACT write — every install/refresh/adopt mints a monotone `:rev`
      (the install-record revision). Mark/clear acts only while the current
      record still carries that rev, so an overtaking overwrite, a destroyed-
      then-recreated frame, or an unrelated equal-plan root can never mark or
      clear another attempt's evidence.
    - the COMMITTED ROOT SCOPE — `:committed` is set at the client's host-commit
      boundary (`finalize-preflight-attempt!`), never by plan execution, and
      carried forward across a same-owner refresh. It distinguishes a genuinely
      committed live root from a fresh/uncommitted install or a retry of an
      incomplete mount.

  A plan that throws MID-RUN labels the siblings it already wrote by PROVENANCE,
  where provenance is a committed-scope fact (rf2-vxgfnd.30 (b), rf2-vxgfnd.72,
  rf2-vxgfnd.139): a FRESH / never-committed install is tagged
  `:mount-incomplete` — its mount never completed and no root scopes it. An
  already-COMMITTED refresh, or an adopt of a live BOOT frame, keeps its
  committed/boot state and carries neutral `:preflight-attempt-failed` evidence:
  the committed scope / boot frame PERSISTS (Q49), only the ATTEMPT failed.

  The COMPLEMENTARY boundary is the host render. When a run's plans all succeed
  but the client's post-preflight ownership fence, element thunk, or first host
  render then throws, the client calls `abort-preflight-attempt!` with the same
  provenance rule — so a fresh install whose host render failed reads as an
  incomplete mount (no root scopes it), never a phantom completed installer. A
  committed live root's failed re-render keeps its prior Root/DOM and records
  only the failed attempt. Evidence is finalized (cleared + `:committed`) only
  after that host boundary succeeds.

  ## Atomicity posture (the rf2-ktmto9 mirror)

  Conflict detection is PURE and runs over ALL of a root's plans BEFORE
  any install, so the conflict class fails with ZERO writes. Each install
  is then atomic per plan: `make-frame`'s own preflight validation throws
  BEFORE any container/process-global write, and a setup step that throws
  at runtime tears the partial frame down inside the engine — the failing
  plan leaves NO frame residue and NO install record. Sibling plans that
  installed earlier in the same run REMAIN live: their `:initial-events`
  effects are already real (most fx are irreversible — the FX-atomicity
  ruling), and a retry finds them live and does not re-seed. Faking
  multi-plan rollback by destroying seeded frames would not un-fire their
  effects.

  Every run atomically reserves its complete set of planned frame ids through
  core's shared per-id construction transaction BEFORE phase-1 decision, holds
  those exact identity-owned claims through publication, and releases them in
  `finally`. A same-id nested or cross-thread run fails immediately with
  `:rf.error/frame-preflight-overlap`; disjoint ids and plan-free runs proceed
  independently. There is no blocking monitor on either host. Each
  install/refresh hands the run's exact reservation into `make-frame` for one
  engine entry, so UI and core share one admission authority instead of
  self-colliding or maintaining two coordination layers.

  ## Q49 RULING (ENSURE retry-after-preflight-failure; pinned here)

  A preflight failure FAILS THE MOUNT LOUDLY; the container is untouched
  (no React root is created, no live-root registration happens — see
  `re-frame.ui.client/mount*` ordering). RETRY = the host re-calls
  `mount` (or `render!`); there is NO auto-retry and no partial mount.
  On retry, plans that installed before the failure are found live (no
  re-seed) and only the failing plan re-executes. A failing RE-preflight
  (idempotent re-mount / `render!`) leaves the existing root and its last
  committed render untouched.

  ## The ambient frame chain (03 §8 / 02 §6)

  `resolve-frame` is the ONE resolution order for frame-scoped operations
  in compiled views:

      explicit pin > dynamic binding (`rf/with-frame`) >
      React context (`frame-root` / `frame-provider` scope) >
      loud `:rf.error/no-frame-context`

  There is NO `:rf/default` floor (the EP-0002 carried invariant). This
  primitive reads the SHARED React context object
  (`re-frame.adapter.context/frame-context`) DIRECTLY. The COMPILED
  sub-read path (`reactive/sub-read` → `observation/resolve-target` →
  `frame/require-current-frame!`) instead reaches the context tier through
  the `:adapter/current-frame` late-bind hook, which `re-frame.ui.substrate`
  publishes for the native UI adapter and for the plain-atom CLJS conformance
  path (rf2-vxgfnd.24). An app booted with `ui/adapter` therefore resolves the
  React-context tier on ambient `dispatch`/`subscribe` without any wrapper
  adapter, not only through this primitive.

  Scope has React context semantics: it applies to descendant VIEW
  boundaries rendered under the provider; sites in the SAME template body
  as the provider read the OUTER scope.

  On the JVM (structural rendering) there is no React context; the chain
  is explicit pin > dynamic binding > loud error, and `frame-provider`
  scopes by BINDING the dynamic tier around its subtree's construction
  (`jvm-provider-scope`)."
  (:require [re-frame.core :as rf-core]
            [re-frame.error :as error]
            ;; The canonical two-channel error fan-out (`emit-error-both!`). ui
            ;; ships ABOVE core's require graph, so this is a plain static require
            ;; (no `error-emit` → `elision` → `frame` load cycle to route around
            ;; via late-bind, unlike the in-cycle core emit sites).
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.live-frame :as live-frame]
            ;; The reserved `:rf/redacted` egress sentinel. The dead-incarnation
            ;; emit seam redacts a stale bundle's captured payload to it AT
            ;; SOURCE (rf2-01ihi), so no same-id successor's elision policy can
            ;; ever be borrowed to un-redact it downstream.
            [re-frame.privacy :as privacy]
            ;; The dev-only warning seam (`trace/emit! :warning …`) the
            ;; cross-frame carried-subscribe honesty diagnostic rides
            ;; (rf2-vxgfnd.231). ui ships ABOVE core, so this is a plain static
            ;; require (no load cycle); the emit body is `interop/debug-enabled?`-
            ;; gated so it DCEs under `:advanced` + `goog.DEBUG=false`.
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]
            [re-frame.ui.reactive :as reactive]
            ;; rf2-vxgfnd.24: loading `re-frame.ui.substrate` publishes the
            ;; `:adapter/current-frame` reader for the pure compiled-view
            ;; runtime (React-context tier on the sub-read path). Required for
            ;; the ns-load side-effect — frames is on every ui mount/render
            ;; path, so the hook is live before any sub-read runs.
            #?@(:cljs [[re-frame.adapter.context :as adapter-context]
                       [re-frame.ui.substrate]])))

;; ---------------------------------------------------------------------------
;; The plan-install registry
;; ---------------------------------------------------------------------------

(defonce ^:private installed-plans
  ;; frame-id -> ONE of two records, discriminated by authority:
  ;;   INSTALLED (this root OWNS the frame lifetime — refresh rights):
  ;;     {:config-fingerprint "cf1-…" :installed-by root-id :rev N}
  ;;   ADOPTED (a boot / external frame this root merely SCOPES — NO refresh
  ;;     rights; boot config stays authoritative):
  ;;     {:config-fingerprint "cf1-…" :adopted-by root-id :adopted true :rev N}
  ;;
  ;; `:rev` (rf2-vxgfnd.139) is a globally-monotone identity minted at EVERY
  ;; install/refresh/adopt write — the "install record revision". It binds
  ;; attempt evidence to the EXACT write: a mark/clear from a stale attempt (an
  ;; overtaking overwrite, a destroyed-then-recreated frame, an unrelated equal-
  ;; plan root) can never match a newer record's rev. It is INTERNAL plumbing —
  ;; `installed-plan-entry` strips it from the tool/test projection.
  ;;
  ;; `:committed true` (rf2-vxgfnd.139) means a host render for the owning /
  ;; scoping root has COMMITTED — set by `finalize-preflight-attempt!` at the
  ;; client's host-commit boundary (never by plan execution alone), carried
  ;; forward across a same-owner refresh. Frame/boot AUTHORITY (an install
  ;; record) is distinct from a COMMITTED ROOT SCOPE (`:committed`): an install
  ;; record proves plan authority, not a committed React root.
  ;;
  ;; A failed ATTEMPT labels the surviving records it wrote by provenance
  ;; (rf2-vxgfnd.30 (b), rf2-vxgfnd.72, rf2-vxgfnd.139) — where provenance is a
  ;; COMMITTED-scope fact, NOT a :refresh/:adopt action:
  ;;   FRESH / never-committed install (no `:committed` before) → `:mount-
  ;;     incomplete true` (mount never completed, no root scopes it);
  ;;   already-COMMITTED refresh, or an adopt of a live BOOT frame →
  ;;     `:preflight-attempt-failed true` (neutral attempt evidence — the
  ;;     committed root scope / boot frame persists, Q49).
  ;; Records are PRUNED on `frame/destroy-frame!` (rf2-vxgfnd.30 (a)) — the
  ;; destroy hook dissocs the id, so a re-created same-id frame is a genuinely
  ;; new lifetime rather than a resurrected dead-lifetime record.
  (atom {}))

(defonce ^:private plan-rev
  ;; The globally-monotone install-record revision counter (rf2-vxgfnd.139).
  ;; A single atom shared across all frame-ids: each install/refresh/adopt write
  ;; mints a fresh rev, so a rev uniquely identifies one exact write across all
  ;; frames and time.
  (atom 0))

(defn- next-plan-rev! [] (swap! plan-rev inc))

;; The rev counter is deliberately NEVER reset (not even by
;; `reset-installed-plans!`): monotonicity across a wiped registry is the safety
;; property — a receipt captured before a reset can never collide with a record
;; minted after it.

(defn- adopted-record?
  "True when `record` marks a boot/external frame this root merely SCOPES
  (no installation authority — never a refresh target)."
  [record]
  (true? (:adopted record)))

(defn- record-root-id
  "The root whose install/adopt record carries settlement authority."
  [record]
  (or (:installed-by record) (:adopted-by record)))

(defn- installed-record
  "INTERNAL raw record for `frame-id` (carries `:rev` + `:committed`), guarded
  by frame liveness so a never-hooked dead record never surfaces as an install.
  `installed-plan-entry` is the tool/test projection of this (rev stripped)."
  [frame-id]
  (when (some? (frame/frame frame-id))
    (get @installed-plans frame-id)))

(defn installed-plan-entry
  "The recorded install/adoption for `frame-id` (tool/test read):
  `{:config-fingerprint … :installed-by root-id}` (installed) or
  `{:config-fingerprint … :adopted-by root-id :adopted true}` (adopted),
  optionally carrying `:committed` (a committed root scope, rf2-vxgfnd.139) or
  a failed-attempt tag (`:mount-incomplete` / `:preflight-attempt-failed`), or
  nil. The internal `:rev` (install-record revision) is STRIPPED — it is
  identity plumbing, not part of the projection. Records are pruned on destroy;
  the extra live-guard keeps a never-hooked path from surfacing a stale record
  as an install."
  [frame-id]
  (some-> (installed-record frame-id) (dissoc :rev)))

(defn reset-installed-plans!
  "Test support: wipe the plan-install registry. Does NOT destroy frames —
  `frame/destroy-frame!` them (or reset the frames registry) separately."
  []
  (reset! installed-plans {})
  nil)

(defn- publish-plan-for-live-incarnation!
  "Publish `record` for `frame-id` only while the pinned incarnation stays
  current. Returns true when published, nil otherwise.

  `expected-incarnation` is the EXACT frame authority this write is bound to
  (rf2-5svfa1). When non-nil (a `:refresh` carrying its decision-time
  incarnation token), the write is admitted ONLY while THAT incarnation is still
  live — never against a same-id replacement that overtook it after the plan
  decision. When nil (only a fresh `:install`, which has no decision-time live
  incarnation), the newly-created current incarnation is captured and pinned;
  an absent frame yields no token and the write is rejected. A boot `:adopt`
  passes its decision-time token. Any nil return is the caller's signal to FAIL
  CLOSED with `:rf.error/frame-preflight-lifecycle-loss`; publication rejection
  can never be a successful/silent ENSURE.

  The incarnation pin rejects a destroy/recreate that overtakes publication.
  The incarnation-scoped closing check also covers `destroy-frame!`'s earlier
  pre-liveness-flip window: its close marker is installed BEFORE the UI hook
  prunes plan authority, while `frame/frame` still returns the dying record.

  Both checks run under the frame's drain serialization. Revalidation after the
  write covers a destroy that starts between the first check and the registry
  swap: that tentative record is removed. Thus a record published before a
  later destroy is pruned by its UI hook, while an already-started or overtaking
  destroy leaves no dead-lifetime record. The incarnation-scoped predicate (not
  bare `frame-closing?`) permits a fresh B while A is only finishing teardown."
  [frame-id record expected-incarnation]
  (when-let [incarnation (or expected-incarnation
                             (frame/frame-incarnation-token frame-id))]
    (frame/call-serialized-with-drain!
     frame-id
     (fn []
       (when (and (identical? incarnation
                              (frame/frame-incarnation-token frame-id))
                  (not (frame/frame-incarnation-closing? frame-id incarnation)))
         (swap! installed-plans assoc frame-id record)
         (if (and (identical? incarnation
                              (frame/frame-incarnation-token frame-id))
                  (not (frame/frame-incarnation-closing? frame-id incarnation)))
           true
           (do
             (swap! installed-plans
                    (fn [plans]
                      (if (= record (get plans frame-id))
                        (dissoc plans frame-id)
                        plans)))
             nil)))))))

;; ---------------------------------------------------------------------------
;; Preflight ENSURE — the live plan executor
;; ---------------------------------------------------------------------------

(defn- throw-plan-conflict!
  [root-id {:keys [frame-id config-fingerprint]} installed]
  (error/throw-error!
   :rf.error/frame-payload-conflict 're-frame.ui/preflight
   (str "root " (pr-str root-id) " arrives with a frame plan for "
        (pr-str frame-id) " whose config fingerprint differs from the "
        "installed frame's recorded plan (installed by root "
        (pr-str (:installed-by installed)) ")"
        (cond
          ;; FRESH first mount that never completed — truthful "no root scopes"
          (:mount-incomplete installed)
          (str ", whose mount did NOT complete — a later plan in that "
               "root's run threw, so the frame is live only from its "
               "already-fired :initial-events and no root actually scopes it")
          ;; ALREADY-LIVE root whose re-preflight run failed — the frame and
          ;; its scoping root PERSIST (Q49); only the last attempt failed.
          (:preflight-attempt-failed installed)
          (str ", whose most recent preflight run did NOT complete — a later "
               "plan in that root's re-preflight threw. The installed frame is "
               "still LIVE and scoped by root " (pr-str (:installed-by installed))
               "; its prior mount and last committed render are untouched (Q49)"))
        ". One frame, one plan: align "
        "the configs, or drop the duplicate frame-root. The installed "
        "frame and the roots already using it are untouched — this root "
        "failed before any install")
   {:recovery :align-frame-plan-config
    :extra {:frame-id  frame-id
            :installed (dissoc installed :rev)   ; :rev is internal identity
            :arriving  {:config-fingerprint config-fingerprint
                        :root-id root-id}}}))

(defn- throw-boot-authority-conflict!
  "A config-BEARING plan met a boot / external frame this root does not own
  (a live plan-less frame, or one already ADOPTED as boot-authoritative).
  Adoption is create-if-absent SCOPING, never an ownership transfer: a
  frame-root carrying config cannot install/refresh over boot authority.
  Fails loud instead of silently discarding the config (rf2-vxgfnd.56).
  Same `:rf.error/frame-payload-conflict` id — the arriving plan's payload
  conflicts with the frame's authoritative boot config."
  [root-id {:keys [frame-id config-fingerprint]} installed]
  (error/throw-error!
   :rf.error/frame-payload-conflict 're-frame.ui/preflight
   (str "root " (pr-str root-id) " declares a CONFIG-BEARING frame-root for "
        (pr-str frame-id) ", but that frame is already live and "
        "boot-authoritative"
        (if installed
          (str " (adopted by root " (pr-str (:adopted-by installed)) ")"
               (when (:preflight-attempt-failed installed)
                 (str " — the run that recorded that adoption did not fully "
                      "complete (a later plan threw), though the boot frame "
                      "remains live and authoritative")))
          " (created outside the plan registry — a boot rf/make-frame)")
        ". A frame-root plan cannot install or refresh its config over a "
        "frame it does not OWN — adoption only SCOPES a boot frame, it never "
        "transfers ownership. Either (a) boot the frame WITHOUT config and "
        "scope it with a config-less [frame-root {:id " (pr-str frame-id)
        "}], or (b) drop the boot rf/make-frame and let this frame-root own "
        "the lifetime. The boot frame's config is untouched — this root "
        "failed before any install")
   {:recovery :scope-config-less-or-own-the-lifetime
    :extra {:frame-id  frame-id
            :installed (dissoc installed :rev)   ; :rev is internal identity
            :arriving  {:config-fingerprint config-fingerprint
                        :root-id root-id}}}))

(defn- throw-preflight-lifecycle-loss!
  "Fail preflight CLOSED (rf2-5svfa1): the exact frame authority a plan was
  bound to stopped being the live incarnation mid-preflight, so ENSURE cannot
  honour it. Raised on two arms of the SAME failure class — the plan's frame
  authority was lost — under one id `:rf.error/frame-preflight-lifecycle-loss`:

    :ensured-frame-lost      — publication was rejected AND the frame this
                               `:install` / `:refresh` / `:adopt` had to leave
                               live is now ABSENT. The usual cause is a
                               self-destroying setup: an `:initial-events`
                               handler destroyed the very frame it was seating.
                               Previously the executor SILENTLY skipped the
                               write and returned a normal receipt, so the
                               client mounted a live host root scoped to an
                               ABSENT frame (there is no frame-liveness guard
                               between preflight and `createRoot`). A rejection
                               The shared per-id reservation excludes a foreign
                               concurrent destroy; any rejection is therefore
                               fail-closed, never a silent successful ENSURE.
    :refresh-target-replaced — a `:refresh` decided against the incarnation live
                                at decision time, but that incarnation was
                                destroyed or replaced by a different same-id
                                incarnation before the surgical `make-frame`
                                could apply. Applying this root's config to an
                                unrelated replacement would silently overwrite the
                                replacement's own authority; fail BEFORE mutating.
    :found-live-authority-lost — a found-live no-op lost either its exact live
                                incarnation or exact install/adopt record after
                                phase-1 decision. It cannot confer settlement
                                rights over an absent/replacement lifetime.

  Fails loud like the payload/boot-authority conflicts — thrown out of preflight,
  so the mount fails before any `createRoot`, live-root registration, DOM
  mutation, or committed plan record (Q49). Siblings this run already wrote are
  marked by the executor's own abort on the way out."
  [root-id frame-id kind]
  (error/throw-error!
   :rf.error/frame-preflight-lifecycle-loss 're-frame.ui/preflight
   (str "root " (pr-str root-id) "'s frame preflight for " (pr-str frame-id)
        (case kind
          :ensured-frame-lost
          (str " could not ENSURE the frame: the incarnation it was seating was "
               "destroyed or replaced DURING preflight — before its plan record "
               "could be published. The usual cause is a self-destroying setup — "
               "an :initial-events handler that destroys the very frame it is "
               "initialising — or a concurrent teardown of the same id. ENSURE "
               "must not leave a live host root scoped to an absent frame, so the "
               "mount fails closed: no createRoot, no live-root registration, no "
               "plan record. Remove the self-destroying setup (an :initial-events "
               "handler must not destroy its own frame), or mount after the "
               "teardown settles")
          :refresh-target-replaced
          (str " decided a :refresh against the frame incarnation live at "
               "decision time, but that incarnation was destroyed or replaced by "
               "a different same-id incarnation before the refresh could apply "
               "(an earlier plan's :initial-events, or a concurrent "
               "teardown+recreate, swapped the live frame). A refresh must apply "
               "to the EXACT incarnation whose install record justified it — "
               "applying this root's config to an unrelated replacement would "
               "silently overwrite the replacement's authority. The mount fails "
               "closed before any mutation; re-mount so preflight re-decides "
               "against the live incarnation")
          :found-live-authority-lost
          (str " decided a found-live no-op against one exact live incarnation "
               "and install/adopt record, but that authority was destroyed, "
               "replaced, or overwritten before phase 2. A stale found-live "
               "receipt must not settle another lifetime or root's evidence. "
               "The mount fails closed; re-mount so preflight re-decides against "
               "the current frame authority")))
   {:recovery :keep-the-preflight-frame-live
    :extra {:frame-id frame-id
            :root-id  root-id
            :kind     kind}}))

;; `written` (and a preflight receipt's `:writes`) is a vector of
;; `{:frame-id :root-id :rev :provenance}` entries — one per record this attempt
;; has settlement authority for, in document order. `:root-id` + `:rev` bind the
;; entry to the EXACT root-owned record write; foreign equal-fingerprint
;; found-live remains valid scoping but enters no write. `:provenance` is a
;; COMMITTED-scope fact (rf2-vxgfnd.139), not an action:
;;   :fresh      — an install, or a refresh of a NEVER-committed record; on
;;                 abort → :mount-incomplete (no committed root scopes it).
;;   :live       — a refresh of an already-COMMITTED record, or an adopt of a
;;                 live BOOT frame; on abort → :preflight-attempt-failed (the
;;                 committed scope / boot frame persists, Q49).
;;   :found-live — the ratified same-fingerprint no-op; never marked on abort,
;;                 only committed / cleared on a successful host boundary.

(defn- emit-preflight-evidence-mismatch!
  "Surface a rejected finalize/abort entry without mutating plan authority.

  Settlement happens at/after the host boundary, where throwing cannot undo a
  host commit. Emit the canonical typed diagnostic through both observability
  channels and leave the overtaking/missing/foreign record untouched."
  [phase receipt-root-id {:keys [frame-id root-id]} record reason]
  (error-emit/emit-error-both!
   :rf.error/frame-preflight-evidence-mismatch
   nil nil frame-id nil 0 (interop/now-ms)
   {:frame-id        frame-id
    :phase           phase
    :reason          reason
    :receipt-root-id receipt-root-id
    :write-root-id   root-id}
   {:phase           phase
    :reason          reason
    :receipt-root-id receipt-root-id
    :write-root-id   root-id
    :record-root-id  (record-root-id record)})
  nil)

(defn- settlement-mismatch-reason
  "Return nil only when one receipt entry still names its exact root-owned
  record; otherwise the stable diagnostic reason. Pure — CAS retries must not
  duplicate evidence emission."
  [receipt-root-id {:keys [root-id rev]} record]
  (cond
    (not= receipt-root-id root-id) :receipt-root-mismatch
    (nil? record) :record-missing
    (not= rev (:rev record)) :record-revision-mismatch
    (not= root-id (record-root-id record)) :record-root-mismatch
    :else nil))

(defn- superseding-index
  "Index a superseding attempt's receipt writes by `frame-id →
  {:root-id :rev :receipt-root-id}` for the benign-supersession check. Each
  entry carries the receipt's OUTER `:root-id` — the controller that presents
  the write — alongside the write's own root/rev, so `benign-supersession?`
  can AUTHENTICATE the envelope: a legitimate receipt's outer controller equals
  the root of every write it minted (execute-frame-plans* stamps both from the
  same arriving root), so an entry whose outer controller was swapped for a
  foreign root can never silently settle another attempt's write. A nil
  receipt / no writes yields `{}`. Only the render-supersession abort path (a
  later `render!*` seating a new attempt for the SAME root) supplies one; every
  other abort/finalize path passes none, so out-of-band stale settlements stay
  dev-loud."
  [{receipt-root-id :root-id :as superseding-receipt}]
  (into {}
        (map (fn [{:keys [frame-id root-id rev]}]
               [frame-id {:root-id root-id :rev rev
                          :receipt-root-id receipt-root-id}]))
        (:writes superseding-receipt)))

(defn- benign-supersession?
  "True when an aborted write was LEGITIMATELY overtaken by the superseding
  same-root attempt, so its revision mismatch is EXPECTED SETTLEMENT rather than
  an authority failure. Suppression is precise: the superseding receipt's
  ENVELOPE must be AUTHENTIC — its outer controller root must match the write it
  presents — that write must own this exact frame-id, under the SAME root as the
  aborted write, and the CURRENT record must BE that superseding write (its rev,
  its root). Anything else — a split envelope (a foreign outer controller
  presenting the owner's intact inner write), a foreign-root write, a
  pruned/missing record, a reincarnation, or a divergence the superseding attempt
  does not account for — is NOT benign and stays dev-loud. `superseding` is the
  `superseding-index` map (empty for the non-supersession settlement paths, so
  those never suppress)."
  [superseding receipt-root-id {:keys [frame-id root-id]} record]
  (when-some [{sup-root :root-id sup-rev :rev sup-receipt-root :receipt-root-id}
              (get superseding frame-id)]
    (and (= receipt-root-id root-id)            ; the aborted write names its own root
         (= sup-receipt-root sup-root)          ; the superseding ENVELOPE vouches for its own write
         (= sup-root root-id)                   ; the superseding write is the SAME root
         (some? record)
         (= sup-rev (:rev record))              ; current authority IS that attempt
         (= root-id (record-root-id record))))) ; still owned by that same root

(defn- settle-writes!
  "CAS-settle all authorized writes, then emit each rejected entry exactly once.

  `settle-one` is pure `(fn [registry write record] registry')`. Evidence is
  accumulated during the pure reduction and fanned only after the CAS wins, so
  contention can retry without duplicate diagnostics.

  `superseding` is the `superseding-index` of a same-root attempt that has
  legitimately overtaken this one (empty for every path but render-supersession
  abort). A rejected write that is a `benign-supersession?` of that attempt is
  EXPECTED SETTLEMENT — the newer same-root receipt already owns the record — so
  it neither mutates nor emits. Every other rejection stays dev-loud."
  [phase receipt-root-id writes superseding settle-one]
  (when (seq writes)
    (loop []
      (let [before @installed-plans
            [after mismatches]
            (reduce
             (fn [[m mismatches] {:keys [frame-id] :as write}]
               (let [record (get m frame-id)]
                 (if-let [reason (settlement-mismatch-reason
                                  receipt-root-id write record)]
                   (if (benign-supersession? superseding receipt-root-id
                                             write record)
                     [m mismatches]      ; expected settlement — no mutate, no emit
                     [m (conj mismatches [write record reason])])
                   [(settle-one m write record) mismatches])))
             [before []]
             writes)]
        (if (compare-and-set! installed-plans before after)
          (doseq [[write record reason] mismatches]
            (emit-preflight-evidence-mismatch!
             phase receipt-root-id write record reason))
          (recur))))))

(defn- abort-writes!
  "Mark the surviving records of an aborted attempt, guarded by BOTH exact rev
  and root authority. A mismatch never mutates and emits
  `:rf.error/frame-preflight-evidence-mismatch`. :fresh →
  `:mount-incomplete`; :live → `:preflight-attempt-failed`; :found-live is left
  untouched.

  `superseding-receipt` (optional) is the same-root attempt that overtook this
  one on the render-supersession path: a write it legitimately overtook settles
  silently (`benign-supersession?`) instead of raising a spurious
  revision-mismatch. Omitted for the mid-run catch — those writes have no
  overtaking receipt."
  ([receipt-root-id writes] (abort-writes! receipt-root-id writes nil))
  ([receipt-root-id writes superseding-receipt]
   (settle-writes!
    :abort receipt-root-id writes (superseding-index superseding-receipt)
    (fn [m {:keys [frame-id provenance]} _record]
      (case provenance
        :fresh (assoc-in m [frame-id :mount-incomplete] true)
        :live  (assoc-in m [frame-id :preflight-attempt-failed] true)
        m)))))

(defn- finalize-writes!
  "A host boundary COMMITTED: mark every record this attempt still owns
  `:committed` and clear its stale failed-attempt evidence. Guarded by BOTH
  exact rev and root authority; a mismatch never mutates and emits
  `:rf.error/frame-preflight-evidence-mismatch`."
  [receipt-root-id writes]
  (settle-writes!
   :finalize receipt-root-id writes nil
   (fn [m {:keys [frame-id]} record]
     (assoc m frame-id
            (-> record
                (assoc :committed true)
                (dissoc :mount-incomplete
                        :preflight-attempt-failed))))))

(defn- exact-decided-authority-live?
  "True while a found-live decision still names its exact incarnation + record."
  [frame-id expected-incarnation expected-record]
  (let [current (installed-record frame-id)]
    (and (identical? expected-incarnation
                     (frame/frame-incarnation-token frame-id))
         (not (frame/frame-incarnation-closing?
               frame-id expected-incarnation))
         (= (:rev expected-record) (:rev current))
         (= (record-root-id expected-record) (record-root-id current)))))

(defn- execute-frame-plans*
  "The per-id-reserved decide+record run behind [[execute-frame-plans!]].
  `reservation-owner` is the exact core owner claimed by the public wrapper and
  handed into each install/refresh engine entry. Returns the preflight-attempt
  receipt `{:root-id … :writes […]}` on success."
  [root-id plans reservation-owner]
  (let [decided
        (mapv (fn [{:keys [frame-id config config-fingerprint] :as plan}]
                ;; `installed` is the RAW record (carries :rev + :committed);
                ;; conflict payloads strip :rev inside the throw fns.
                (let [installed       (installed-record frame-id)
                      config-bearing? (boolean (seq config))]
                  (cond
                    ;; a recorded plan/adoption already governs this LIVE frame
                    (some? installed)
                    (if (adopted-record? installed)
                      ;; ADOPTED = boot-authoritative; NO refresh rights. A
                      ;; config-less re-scope is a pure no-op; a config-bearing
                      ;; plan cannot install its config over boot authority
                      ;; (rf2-vxgfnd.56 — adoption never grants refresh rights).
                      (if config-bearing?
                        (throw-boot-authority-conflict! root-id plan installed)
                        (assoc plan ::action :found-live ::prior installed
                               ::incarnation
                               (frame/frame-incarnation-token frame-id)))
                      ;; INSTALLED = this-or-another root OWNS the lifetime.
                      (cond
                        (= (:config-fingerprint installed) config-fingerprint)
                        (assoc plan ::action :found-live ::prior installed
                               ::incarnation
                               (frame/frame-incarnation-token frame-id))

                        (= (:installed-by installed) root-id)
                        ;; Capture the EXACT incarnation the :refresh decision was
                        ;; made against (rf2-5svfa1 part B). `installed-record`
                        ;; guards on liveness, so this token is the currently-live
                        ;; incarnation. Phase 2 revalidates it BEFORE the surgical
                        ;; make-frame, so a same-id replacement that overtakes
                        ;; between decide and mutate cannot inherit this refresh.
                        (assoc plan ::action :refresh ::prior installed
                               ::incarnation (frame/frame-incarnation-token frame-id))

                        :else
                        (throw-plan-conflict! root-id plan installed)))

                    ;; No install record, but a frame is already LIVE under
                    ;; this id — created OUTSIDE the plan registry (a boot
                    ;; `rf/make-frame`). ENSURE is create-if-absent (03 §8): a
                    ;; CONFIG-LESS plan ADOPTS it (scopes, never re-creates —
                    ;; boot config/generation stay authoritative). A
                    ;; CONFIG-BEARING plan would silently discard its config
                    ;; onto a boot-authoritative frame — fail loud instead
                    ;; (rf2-vxgfnd.56).
                    (some? (frame/frame frame-id))
                    (if config-bearing?
                      (throw-boot-authority-conflict! root-id plan nil)
                      (assoc plan ::action :adopt
                             ::incarnation
                             (frame/frame-incarnation-token frame-id)))

                    :else
                    (assoc plan ::action :install))))
              plans)
        ;; The receipt's write log: one `{:frame-id :root-id :rev :provenance}`
        ;; per record this run may settle, in document order. Foreign equal-plan
        ;; found-live writes nothing. Bound to the host boundary by the receipt.
        written (volatile! [])]
    (try
      (doseq [{:keys [frame-id config config-fingerprint] :as plan} decided]
        (case (::action plan)
          ;; The ratified idempotent no-op: no re-seed, no record churn. It rides
          ;; the receipt only when THIS root owns the record. A foreign
          ;; equal-fingerprint root may scope the frame but gains no settlement
          ;; rights. Revalidate the exact decision-time incarnation + record
          ;; before carrying even an owner write to the host boundary.
          :found-live
          (let [prior (::prior plan)]
            (when-not (exact-decided-authority-live?
                       frame-id (::incarnation plan) prior)
              (throw-preflight-lifecycle-loss!
               root-id frame-id :found-live-authority-lost))
            (when (= root-id (record-root-id prior))
              (vswap! written conj
                      {:frame-id frame-id :root-id root-id
                       :rev (:rev prior) :provenance :found-live})))

          ;; a live plan-less (boot-created) frame: create-if-absent means
          ;; create NOTHING. Record only the plan's fingerprint under an
          ;; ADOPTED (non-owning) record so a later cross-root arrival is
          ;; conflict-scoped — the boot frame's config/generation/`:images`
          ;; are left entirely untouched (rf2-vxgfnd.26), and the record can
          ;; never enter a root-owned refresh path (rf2-vxgfnd.56). The boot
          ;; frame is already LIVE, so an aborted adoption is a :live attempt
          ;; failure (boot frame persists), never a mount-incomplete claim
          ;; over never-mounted state — but the ADOPTING root's SCOPE is not
          ;; committed until the host boundary succeeds.
          :adopt
          (let [rev (next-plan-rev!)]
            (if (publish-plan-for-live-incarnation!
                 frame-id
                 {:config-fingerprint config-fingerprint
                  :adopted-by root-id
                  :adopted true
                  :rev rev}
                 (::incarnation plan))
              (vswap! written conj
                      {:frame-id frame-id :root-id root-id
                       :rev rev :provenance :live})
              ;; Any rejection means the exact decision-time boot incarnation
              ;; was lost/replaced/closing. Never capture a successor.
              (throw-preflight-lifecycle-loss!
               root-id frame-id :ensured-frame-lost)))

          ;; :install / :refresh — create-if-absent / surgical refresh;
          ;; :initial-events drain synchronously inside the engine, in
          ;; document order across plans. The record lands only AFTER a
          ;; successful install AND only while the EXPECTED incarnation is still
          ;; live; a throwing setup propagates, and a self-destroying setup (or a
          ;; refresh whose decided incarnation was replaced) now fails CLOSED with
          ;; `:rf.error/frame-preflight-lifecycle-loss` rather than silently
          ;; skipping the write (rf2-5svfa1) — the mount can never proceed over an
          ;; absent frame or bind a stale refresh to a replacement.
          ;; PROVENANCE is a COMMITTED-scope fact, NOT the action: a refresh of
          ;; an already-`:committed` record is :live (its prior render persists,
          ;; and the record carries `:committed` FORWARD); a fresh install, or a
          ;; refresh of a never-committed record (a retry of an incomplete
          ;; mount), is :fresh — no root has committed a scope (rf2-vxgfnd.139).
          (let [refresh?   (= :refresh (::action plan))
                expected   (::incarnation plan)    ; nil for a fresh :install
                committed? (boolean (:committed (::prior plan)))
                rev        (next-plan-rev!)
                record     (cond-> {:config-fingerprint config-fingerprint
                                    :installed-by root-id
                                    :rev rev}
                             committed? (assoc :committed true))]
            ;; part B: a :refresh must apply to the EXACT incarnation its
            ;; decision was made against. Revalidate BEFORE the surgical
            ;; make-frame mutates — if a same-id replacement (or a destroy +
            ;; create-if-absent) overtook that incarnation since decide, fail
            ;; closed rather than refresh an unrelated frame (rf2-5svfa1).
            (when (and refresh?
                       (not (identical? expected
                                        (frame/frame-incarnation-token frame-id))))
              (throw-preflight-lifecycle-loss! root-id frame-id :refresh-target-replaced))
            (frame/call-with-frame-construction-handoff!
             reservation-owner frame-id
             #(live-frame/make-frame (assoc (or config {}) :id frame-id)))
            ;; part A: publish against the EXPECTED authority (the :refresh's
            ;; decision-time incarnation; captured-current for a fresh :install).
            ;; Any rejected write means the ensured exact incarnation was lost
            ;; mid-preflight (for example a self-destroying :initial-events
            ;; setup). Fail closed; never return a receipt over an absent,
            ;; closing, or replacement frame.
            (if (publish-plan-for-live-incarnation! frame-id record expected)
              (vswap! written conj
                      {:frame-id frame-id :root-id root-id :rev rev
                       :provenance (if committed? :live :fresh)})
              (throw-preflight-lifecycle-loss!
               root-id frame-id :ensured-frame-lost)))))
      ;; The whole plan run completed. Do NOT finalize here — evidence is bound
      ;; to the host commit boundary (rf2-vxgfnd.139): the client calls
      ;; `finalize-preflight-attempt!` only AFTER its post-preflight ownership
      ;; fence + synchronous host render succeed, and `abort-preflight-attempt!`
      ;; if that boundary throws. Return the receipt.
      {:root-id root-id :writes @written}
      (catch #?(:clj Throwable :cljs :default) e
        ;; a plan threw MID-RUN: the siblings this run wrote stay live
        ;; (irreversible :initial-events — the atomicity posture). This throw
        ;; propagates out of preflight, so the mount fails BEFORE the host
        ;; boundary; mark the writes here (the client never sees the receipt).
        ;; :fresh → :mount-incomplete (no root scopes); :live → neutral
        ;; :preflight-attempt-failed (committed scope / boot frame persists,
        ;; Q49). Rev-guarded — never clobbers an overtaking write.
        (abort-writes! root-id @written)
        (throw e)))))

(defn- throw-preflight-overlap!
  [root-id {:keys [frame owner-kind reason]}]
  (error/throw-error!
   :rf.error/frame-preflight-overlap 're-frame.ui/preflight
   (str "root " (pr-str root-id) " cannot preflight frame " (pr-str frame)
        " because that frame id is already reserved by an in-flight "
        (name (or owner-kind :frame)) " transaction. Same-id overlap fails "
        "immediately rather than waiting inside setup, adapter, or teardown "
        "callbacks. Retry after the owning transaction settles; disjoint frame "
        "ids and plan-free runs proceed independently.")
   {:recovery :retry-after-frame-preflight
    :extra {:frame-id   frame
            :root-id    root-id
            :reason     reason
            :owner-kind owner-kind}}))

(defn- claim-preflight-reservations!
  "Atomically reserve every planned id, translating core contention to the UI
  preflight discriminator. Empty plans claim nothing."
  [root-id plans]
  (let [frame-ids (into #{} (map :frame-id) plans)]
    (when (seq frame-ids)
      (try
        (frame/claim-frame-construction! frame-ids :preflight)
        (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
          (let [data (ex-data e)]
            (if (= :rf.error/frame-construction-in-progress
                   (:rf.error/id data))
              (throw-preflight-overlap! root-id data)
              (throw e))))))))

(defn execute-frame-plans!
  "Execute a root's static frame plans (the LIVE preflight ENSURE — see
  the ns docstring for the full disposition table and the Q49 ruling).
  `plans` are the evaluated `[{:frame-id … :config-fingerprint …
  :config {…}} …]` in document order; `root-id` is the arriving root.

  Phase 1 VALIDATES every plan (pure — cross-root fingerprint conflicts
  AND config-bearing-vs-boot-authority conflicts throw
  `:rf.error/frame-payload-conflict` before ANY install). Phase 2
  installs/adopts/refreshes in document order: `make-frame` creates the
  frame if absent and drains `:initial-events` synchronously before
  returning (EP-0027); its install record is published against the EXACT frame
  authority the decision was bound to (a `:refresh`'s decision-time incarnation,
  or a fresh install's created incarnation), and if that authority was lost
  mid-preflight — a self-destroying setup, or a same-id replacement overtaking a
  refresh — the run fails CLOSED with
  `:rf.error/frame-preflight-lifecycle-loss` (rf2-5svfa1) rather than mounting
  over an absent frame or binding a stale refresh to a replacement; a found-live
  same-fingerprint plan is a pure no-op
  (no re-seed — the HMR guarantee); a live plan-less (boot-created) frame
  met by a CONFIG-LESS plan is ADOPTED under a non-owning record — its
  config/generation untouched, only the fingerprint recorded
  (rf2-vxgfnd.26, rf2-vxgfnd.56). A plan that throws MID-RUN (the mount fails
  before the host boundary) labels the siblings it already wrote by provenance:
  a FRESH / never-committed install → `:mount-incomplete`; an already-COMMITTED
  refresh or an adopt of a live boot frame → `:preflight-attempt-failed` (the
  committed scope / boot frame persists, Q49 — rf2-vxgfnd.30 (b), rf2-vxgfnd.72,
  rf2-vxgfnd.139).

  Evidence is BOUND to the host commit boundary (rf2-vxgfnd.139): a run that
  completes its plans does NOT finalize here. It returns a preflight-attempt
  RECEIPT `{:root-id … :writes [{:frame-id :root-id :rev :provenance} …]}`;
  foreign equal-fingerprint found-live plans carry no settleable write. The client
  (`re-frame.ui.client/mount*` / `render!*`) calls `finalize-preflight-attempt!`
  only after its post-preflight ownership fence + synchronous host render
  succeed, and `abort-preflight-attempt!` if that boundary throws. So a run whose
  plans all succeed but whose host render fails leaves phase-correct evidence
  (a fresh install → `:mount-incomplete`), never a phantom completed installer.

  Concurrency: reserve every planned frame id BEFORE phase-1 decision and hold
  the exact identity-owned claims through publication. Same-id overlap on either
  host fails immediately with `:rf.error/frame-preflight-overlap`; disjoint ids
  and plan-free nesting proceed. There is no process-wide monitor and no wait.
  After release, a retry re-evaluates the committed disposition table."
  [root-id plans]
  (if-let [owner (claim-preflight-reservations! root-id plans)]
    (try
      (execute-frame-plans* root-id plans owner)
      (finally
        (frame/release-frame-construction! owner)))
    (execute-frame-plans* root-id plans nil)))

(defn finalize-preflight-attempt!
  "The client's HOST-COMMIT-BOUNDARY hook (rf2-vxgfnd.139). Called by
  `re-frame.ui.client` AFTER a preflight `receipt`'s root has passed the
  post-preflight ownership fence AND its synchronous host render has COMMITTED:
  mark every record this exact attempt still owns `:committed` and clear its
  stale failed-attempt evidence. REV+ROOT-GUARDED to the exact write — a record
  overwritten (an overtaking root), pruned (a destroy), or reincarnated since is
  left untouched with typed mismatch evidence, so a stale receipt can never finalize a newer attempt or a
  post-commit boundary. A nil receipt (no static plans, or a capture-hook
  override) is a no-op. Returns nil."
  [receipt]
  (when-let [writes (:writes receipt)]
    (finalize-writes! (:root-id receipt) writes))
  nil)

(defn abort-preflight-attempt!
  "The client's HOST-COMMIT-BOUNDARY hook (rf2-vxgfnd.139) for the failure arm:
  called when the post-preflight ownership fence, the element thunk, or the
  first synchronous host render throws AFTER preflight completed (so the client
  holds the receipt). Mark this attempt's :fresh writes `:mount-incomplete` and
  its :live writes `:preflight-attempt-failed` (a fresh install whose host
  render failed reads as never-scoped; an already-committed refresh / adopted
  boot frame keeps its committed scope). REV+ROOT-GUARDED — never clobbers an
  overtaking write; mismatches emit typed evidence and mutate nothing. A nil
  receipt is a no-op. Returns nil.

  On the render-SUPERSESSION path — a later `render!*` seating a NEW attempt for
  the SAME root aborts the older uncommitted one — pass that newer attempt's
  `superseding-receipt`. A write it legitimately overtook (its record now the
  superseding same-root attempt's) is EXPECTED SETTLEMENT: it settles silently,
  with no spurious `:rf.error/frame-preflight-evidence-mismatch`. Every other
  divergence (foreign root, missing/pruned record, reincarnation, a write the
  superseding attempt does not account for) stays dev-loud (rf2-5ep117)."
  ([receipt] (abort-preflight-attempt! receipt nil))
  ([receipt superseding-receipt]
   (when-let [writes (:writes receipt)]
     (abort-writes! (:root-id receipt) writes superseding-receipt))
   nil))

;; ---------------------------------------------------------------------------
;; frame-provider scope validation (shared by both hosts)
;; ---------------------------------------------------------------------------

(defn require-scope-frame!
  "Validate a `frame-provider` `:frame` target and return the frame id.
  The one frame-target grammar: a frame-id KEYWORD or a live frame VALUE
  (`make-frame`'s token) — nil is `:rf.error/no-frame-context` (a
  provider establishing no scope), any other shape is
  `:rf.error/bad-frame-provider-arg` (both via the core triage). A target
  naming NO live frame is `:rf.error/frame-provider-frame-absent`:
  providers SCOPE an already-created frame and create nothing — create
  the frame first (`rf/make-frame` at boot, or a `frame-root` plan in the
  root form, whose ENSURE runs at host preflight)."
  [target where]
  (let [id (frame/require-frame-provider-target! target where)]
    (if (some? (frame/frame id))
      id
      (error/throw-error!
       :rf.error/frame-provider-frame-absent where
       (str "[frame-provider {:frame " (pr-str id) "} …] scopes an "
            "ALREADY-LIVE frame and creates nothing — no live frame is "
            "registered under " (pr-str id) " (never created, or "
            "destroyed). Create it first: a frame-root plan in the root "
            "form (ENSURE runs at host preflight), or rf/make-frame in "
            "boot/event infrastructure — then scope")
       {:recovery :ensure-or-create-the-frame
        :extra {:frame id}}))))

;; ---------------------------------------------------------------------------
;; The ambient frame chain
;; ---------------------------------------------------------------------------

(defn- ambient-frame-id
  "The frame id the AMBIENT chain currently names, or nil when no scope is in
  effect — the middle tiers of `resolve-frame` (dynamic binding > React context
  on CLJS; dynamic binding on the JVM), WITHOUT the explicit-pin tier and
  WITHOUT raising `:rf.error/no-frame-context` on absence. A READER: it reports
  the in-effect scope, it never synthesises `:rf/default` (EP-0002). nil means
  no ambient frame — a top-of-stack / async-hop caller.

  Reads the shared React context object directly (see the ns docstring for why
  not the `:adapter/current-frame` late-bind hook), the SAME tier `resolve-frame`
  and the compiled sub-read path observe."
  []
  (frame/frame-value->id
   #?(:cljs (adapter-context/function-component-current-frame)
      :clj  (frame/current-frame))))

(defn resolve-frame
  "Resolve the frame a compiled-view frame-scoped `operation` targets —
  THE ambient chain (ns docstring): explicit `pin` > dynamic binding >
  React context (CLJS) > loud `:rf.error/no-frame-context`. Never
  synthesises `:rf/default` (EP-0002).

  A non-nil `pin` wins outright: a frame-id keyword or a live frame value
  normalises to the id; any other shape fails loud
  (`:rf.error/bad-frame-provider-arg` — an explicit-but-malformed target
  is never silently coerced). A nil `pin` falls through to the ambient
  tiers (`ambient-frame-id`). `where` names the resolving call site for the
  diagnostics.

  Reads the shared React context object directly (see the ns docstring
  for why not the `:adapter/current-frame` late-bind hook)."
  ([operation where] (resolve-frame nil operation where))
  ([pin operation where]
   (if (some? pin)
     (frame/require-frame-provider-target! pin where)
     (or (ambient-frame-id)
         (let [payload (frame/no-frame-context-payload
                        operation {:where where})]
           (frame/emit-no-frame-context! payload)
           (throw (error/ex-info-from-data payload)))))))

;; ---------------------------------------------------------------------------
;; (frame) — the compiled operation-bundle body form (rf2-vxgfnd.184)
;;
;; The compiler lowers a defview body's `(frame)` to `(frame-ops)`: resolve
;; the committed frame through THE ambient chain above (the same authority
;; every frame-scoped UI form uses), then return the standard capture-frame
;; bundle `{:frame :dispatch :dispatch-sync :subscribe}` locked to that
;; frame — MINTED BY core's `make-capture-frame` (the one checked-in bundle
;; constructor; no second frame abstraction). Two substrate-tier additions
;; over the raw core hold:
;;
;;   - STABLE IDENTITY per live incarnation: the bundle is cached keyed by
;;     the frame's incarnation token (`frame/frame-incarnation-token`), so
;;     repeated `(frame)` reads across renders return the IDENTICAL bundle
;;     (rf= memo-friendly) and a render CONSTRUCTS nothing — no bundle mint,
;;     no closure allocation, no registry walk. A cache HIT is a BOUNDED,
;;     allocation-free lifecycle+cache read: the incarnation token, the
;;     incarnation-scoped closing check, the cache entry, and the token
;;     identity compare that proves the entry belongs to THIS incarnation
;;     (`frame-ops-for` below is the whole path). Bounded, not free — the
;;     liveness reads are what make the stale-bundle fence sound, so they
;;     are load-bearing rather than overhead. A destroyed/replaced frame
;;     mints a fresh bundle.
;;   - INCARNATION-FENCED ops: each op checks the captured incarnation is
;;     still live (`frame/frame-incarnation-live?`) before delegating, so a
;;     bundle that outlives its frame fails loud with the canonical
;;     `:rf.error/frame-destroyed` — a destroyed-then-recreated SAME-ID
;;     frame can never be silently retargeted by a stale carried bundle
;;     (core's id-locked `capture-frame` alone cannot distinguish that).
;;
;; Host-shared: on the JVM the same bundle is returned during a Tier-1
;; structural render — core's dispatch/dispatch-sync/subscribe are host-
;; neutral (the ui.test harness itself dispatches on the JVM), so `(frame)`
;; is NOT a host-only op and never raises `:rf.error/jvm-host-op`.
;; ---------------------------------------------------------------------------

(defonce ^:private frame-ops-cache
  ;; frame-id -> {:token <incarnation token> :bundle <ops map>}. Entries are
  ;; PRUNED by the `:ui/on-frame-destroyed!` hook below; a token mismatch
  ;; (hard reset, replaced incarnation) re-mints on the next read either way.
  (atom {}))

(defn reset-frame-ops-cache!
  "Test/tool hygiene: forget every cached operation bundle. Correctness
  never depends on this (stale entries fail the token identity check and
  re-mint); it only releases the cached closures."
  []
  (reset! frame-ops-cache {}))

(def ^:dynamic ^:no-doc *frame-ops-publish-barrier*
  "JVM linearization TEST SEAM — nil in production (one nil check on the SLOW
  publish path only, zero cost on the fast cache-hit path), NEVER bound off a
  test path (the `reactive/*commit-barrier*` idiom). Bound to a
  `(fn [frame-id token] …)`, `frame-ops` calls it AFTER its initial liveness
  check and BEFORE the drain-serialized publish, so a fixture can interleave a
  same-id destroy + reincarnation between a stale reader's liveness check and
  its cache publication (rf2-vxgfnd.229). Nil-safe on both hosts; CLJS never
  interleaves a second publisher, so only JVM fixtures bind it."
  nil)

(defn- emit-and-throw-frame-destroyed!
  "The ONE UI-frame emit-and-throw seam every `(frame)` operation-bundle failure
  routes through (rf2-vxgfnd.230). FAN the canonical `:rf.error/frame-destroyed`
  record out along BOTH error channels via core's `error-emit/emit-error-both!`
  seam — the always-on error-emit listener (axis 1; survives `:advanced` +
  `goog.DEBUG=false`, the off-box-shipper / SSR-projector source of truth) AND
  the dev-only trace surface (axis 2; DCE'd in production) — THEN throw the same
  canonical typed error. A bare `throw-error!` here went SILENT under
  `goog.DEBUG=false`, so a boundary that swallowed the throw (a view error
  boundary, a swallowing callback) dropped the production failure entirely;
  fanning first keeps the breadcrumb regardless.

  The record carries the exact `frame-id`, the failing `op`
  (`:dispatch` / `:dispatch-sync` / `:subscribe`, or `:capture` for a `(frame)`
  read that resolved a dead incarnation), and the attempted `payload` on the
  record's `:event` slot (its head as `:event-id`) so an off-box shipper
  attributes the failure. A SUBSCRIBE query vector is public IDENTITY (rf2-zwgqe
  / rf2-alk8a) and rides `:event` RAW; a DISPATCH / dispatch-sync payload is
  redacted to `:rf/redacted` (see the fail-closed section). `payload` is nil for
  the `:capture` arm (the read failed before any op was invoked).

  The THROWN exception mirrors the same STRUCTURAL attribution in its `:extra`
  ex-data — `:frame`, `:op`, and the event/query `:event-id` HEAD (rf2-r79gr) —
  so a synchronous `try/catch` around a stale op attributes it from the caught
  exception alone, without correlating the async always-on record. Head only:
  the raw payload BODY never rides the exception (same fail-closed policy as the
  record — see below).

  Emitted EXACTLY ONCE, at this UI source: the incarnation fence emits and throws
  HERE — before delegating to core's dispatch/subscribe — so the same failure is
  never ALSO fanned by core's router/subs frame-destroyed path (source-level
  provenance; no double emission). Centralizing all arms through this one helper
  keeps their two-channel contract from drifting. Never returns.

  ## Out of the same-id successor's error sink (rf2-bf0io)

  This seam fires ONLY for a DEAD captured incarnation, so `frame-id` no longer
  names the incarnation the failure belongs to — a same-id destroy→reincarnation
  may have reseated a SUCCESSOR B under it. The corpus-wide record and dev trace
  are exact-incarnation FACTS and rightly carry A's bare frame id; but the
  EP-0015 frame-OWNED `:observability :errors` sink route resolves that bare id
  to the CURRENT frame — B — and would deliver A's failure into B's OWN sink,
  violating exact-incarnation attribution (B must not own A's error routing). So
  we pass `error-emit/emit-error-both!` a false `route-frame?`: the corpus
  fan-out (axis 1) and the dev trace (axis 2) still fire exactly once, and the
  typed throw still propagates — only the frame-owned sink route is suppressed.
  This is the exact-incarnation mirror of the predecessor teardown-report seam
  (rf2-vxgfnd.118), which suppresses the same frame-owned route for a post-dissoc
  report. Ordinary live / address-directed frame errors keep the default route.

  ## Fail closed for stale-bundle payload egress (rf2-01ihi)

  This seam fires ONLY for a DEAD captured incarnation (a stale bundle op, or a
  `(frame)` read that resolved a dead incarnation). By the time it runs,
  `frame-id` NO LONGER names the incarnation the `payload` was captured for: a
  same-id destroy→reincarnation may have reseated a SUCCESSOR under `frame-id`,
  or the frame may be absent. The captured incarnation owned the ONLY elision
  authority for its payload, and that authority is gone with it.

  `emit-error-both!` → `dispatch-on-error!` elides the record's `:event` through
  `elision/elide-wire-value` under THIS `frame-id`, and that walk fails closed
  ONLY when `frame-id` is UNRESOLVABLE. A live successor makes it resolvable, so
  the walk would BORROW the successor's (unrelated, possibly permissive) policy
  and project incarnation A's raw payload off-box — under BOTH the corpus-wide
  record AND the frame-owned observability sink route, which `dispatch-on-error!`
  feeds the RAW event. We therefore redact a DISPATCH / dispatch-sync payload
  BODY to the reserved `:rf/redacted` sentinel HERE, at the only site that knows
  the incarnation is dead: the structural event/query HEAD still rides
  `:event-id`, no successor policy can be borrowed, and no policy-snapshot
  machinery is introduced. The absent-frame and destroyed-without-successor arms
  fail closed identically.

  The SUBSCRIBE realm is EXEMPT from this source-redaction (rf2-alk8a). A
  subscription query vector is public IDENTITY, not payload (rf2-zwgqe / Spec
  015 — \"a subscription's query vector is identity, and it egresses raw\"), so
  it rides `:event` VERBATIM. rf2-t55hxg.18's unresolvable-frame fail-closed
  guards policy-walked VALUE slots — an identity slot never consults frame
  policy, so there is no successor policy to borrow: downstream
  `error-emit/raw-identity-query-vector-event?` (keyed on the ratified-public
  `:op :subscribe` this seam stamps) SKIPS elision on the query vector across
  BOTH egress routes, exactly as the core subscribe emitters do after #6497.
  This mirrors `subs/emit-frame-destroyed-recovery!` and
  `observation/throw-frame-destroyed!`; only the DISPATCH realm stays redacted."
  [frame-id op payload reason]
  (let [;; A SUBSCRIBE query vector is public IDENTITY (rf2-zwgqe / rf2-alk8a),
        ;; NOT payload, so it egresses RAW (see the fail-closed section above).
        ;; Keyed on `=`, NEVER `identical?`, on the keyword operand — a `.cljc`
        ;; `identical?` keyword compare is JVM-only sound and CLJS-unreachable
        ;; (#6365).
        subscribe?     (= :subscribe op)
        ;; The event/query BODY that egresses on the always-on `:event` slot AND
        ;; the dev-trace `:event` tag. For the SUBSCRIBE realm it is the RAW
        ;; query vector (identity — downstream `raw-identity-query-vector-event?`
        ;; skips elision on it, so no dead/successor frame policy is ever
        ;; consulted). For a DISPATCH / dispatch-sync it is a dispatched event
        ;; vector — payload, not identity — redacted at source to the reserved
        ;; `:rf/redacted` sentinel (rf2-01ihi): a dead incarnation has no
        ;; trustworthy policy, and a same-id successor's policy must never be
        ;; borrowed to un-redact it. nil for the `:capture` arm (no op ran).
        egress-event   (cond
                         (nil? payload) nil
                         subscribe?     payload
                         :else          privacy/redacted-sentinel)
        ;; The STRUCTURAL event/query HEAD — the attempted vector's first
        ;; element (the event-id / sub-id keyword). Rides BOTH the always-on
        ;; record's `:event-id` slot AND the thrown exception's `:extra
        ;; :event-id` (rf2-r79gr), so a synchronous catcher attributes the stale
        ;; op from the exception alone. The raw payload BODY never rides the
        ;; thrown exception on ANY realm — the subscribe query vector egresses
        ;; raw only on the always-on record's `:event` slot, not the exception.
        event-id       (when payload (first payload))]
    (error-emit/emit-error-both!
     :rf.error/frame-destroyed
     egress-event                    ;; :event — RAW query vector for :subscribe (identity); :rf/redacted for dispatch; nil for :capture
     event-id                        ;; :event-id / sub-id — the structural vector head (survives)
     frame-id
     nil                             ;; no exception — a dead-incarnation op, not a caught throw
     0                               ;; elapsed-ms — not a timed path
     (interop/now-ms)                ;; time
     {:frame frame-id :op op :event egress-event :reason :frame-destroyed} ;; dev-trace tags (axis 2)
     {:op op}                        ;; category-specific attribution for the always-on record (axis 1)
     false)                          ;; route-frame? — suppress ONLY the frame-owned sink route (rf2-bf0io):
                                     ;; a dead incarnation's bare id must never resolve to a same-id
                                     ;; successor's :observability :errors sink. Corpus record + dev
                                     ;; trace (above) + the throw (below) all still fire exactly once.
    (error/throw-error!
     :rf.error/frame-destroyed 're-frame.ui/frame
     reason
     {:extra {:frame frame-id :op op :event-id event-id}})))

(defn- throw-frame-ops-destroyed!
  "The ambient frame a `(frame)` read resolved has no live incarnation to
  publish against — it is absent, destroyed, closing, or a same-id
  reincarnation overtook the read between resolve and publish. Fail loud
  through the canonical destroyed-frame path (fanning the always-on record
  first — see `emit-and-throw-frame-destroyed!`) rather than returning (or
  displacing) a dead-incarnation bundle."
  [frame-id]
  (emit-and-throw-frame-destroyed!
   frame-id :capture nil
   (str "(frame) resolved the ambient frame " (pr-str frame-id)
        " but no live incarnation of it exists — the frame is absent,"
        " destroyed, or closing (a same-id frame may have been destroyed and"
        " re-created since the read began). The scope named a frame that no"
        " longer runs; create/ensure the frame before rendering views scoped"
        " to it")))

(defn- throw-frame-ops-stale!
  "A carried bundle op fired after its captured incarnation was destroyed. Fan
  the always-on record (carrying the failing `op` + the attempted `payload`)
  then throw — see `emit-and-throw-frame-destroyed!`."
  [frame-id op payload]
  (emit-and-throw-frame-destroyed!
   frame-id op payload
   (str "a (frame) operation bundle captured for frame " (pr-str frame-id)
        " outlived that frame's incarnation — the frame was destroyed"
        " (or destroyed and re-created under the same id) after capture."
        " Bundle ops are locked to the exact incarnation they captured and"
        " never silently retarget; capture a fresh bundle from a mounted"
        " view, or hold a frame across lifetimes explicitly with"
        " rf/capture-frame")))

(defn- maybe-warn-cross-frame-carried-subscribe!
  "DEV-ONLY carried-operation honesty warning (03 §8; rf2-vxgfnd.231). A
  `(frame)` operation bundle captured under `origin-frame` can be CARRIED across
  a frame boundary (the HOLD semantics) and its `:subscribe` invoked beneath a
  DIFFERENT ambient frame — cross-frame access via carry exists and is NOT
  claimed impossible. Frames are ISOLATED contexts (no cross-frame reads), so
  when a carried subscribe runs under a FOREIGN ambient frame we emit
  `:rf.warning/cross-frame-carried-op` and CONTINUE against the captured
  (origin) frame — advisory only, never a retarget and never a refusal. The
  doctrine (frames are isolated) is thus held by this diagnostic + the absence
  of any cross-frame read *spelling*, never by a false impossibility claim.

  QUIET when the ambient chain names the SAME frame as the origin (the ordinary
  in-scope read) or names NO frame at all (`ambient-frame-id` nil — an async hop
  or a top-of-stack caller: nothing foreign to compare against). NARROW to
  `:subscribe`: a carried dispatch / dispatch-sync across a boundary drives its
  locked frame and does not warn.

  Emitted on EACH cross-frame invocation — the id carries no `-once` suffix
  (contrast the retired `:rf.warning/plain-fn-under-non-default-frame-once`), an
  advisory surface rather than a warn-once nag — through the canonical dev-only
  warning seam `trace/emit! :warning`, with `:recovery :warned-and-continued`.

  Wrapped WHOLE in `interop/debug-enabled?` (the OUTERMOST gate) so the ambient
  read, the frame comparison, the reason string, and the emit machinery DCE
  together under `:advanced` + `goog.DEBUG=false` (Spec 009 §Production builds)."
  [origin-frame query-v]
  (when interop/debug-enabled?
    (let [ambient (ambient-frame-id)]
      (when (and (some? ambient) (not= ambient origin-frame))
        (trace/emit! :warning
                     :rf.warning/cross-frame-carried-op
                     {:origin-frame   origin-frame
                      :ambient-frame  ambient
                      :rf.sub/query-v query-v
                      :reason
                      (str "a (frame) operation bundle captured for frame "
                           (pr-str origin-frame) " ran its :subscribe "
                           (pr-str query-v) " beneath a DIFFERENT ambient frame "
                           (pr-str ambient) ". Frames are ISOLATED contexts — a "
                           "subscription MUST NOT reach across a frame boundary. "
                           "The read CONTINUES against the captured frame "
                           (pr-str origin-frame) " (never the ambient one), so "
                           "behaviour is unchanged — but a carried subscribe "
                           "under a foreign frame is almost always an isolation "
                           "mistake. Scope the subtree with [frame-provider "
                           "{:frame …} …] and read through the ambient chain, or "
                           "pass VALUES across the boundary, not the ops bundle.")
                      :recovery :warned-and-continued})))))

(defn- fence-subscribe
  "Wrap the carried `:subscribe` op with the incarnation fence AND the dev-only
  cross-frame carried-subscribe honesty warning (rf2-vxgfnd.231). On the LIVE
  incarnation the honesty check runs — advisory, never altering the read (see
  `maybe-warn-cross-frame-carried-subscribe!`) — then the read proceeds against
  the captured frame. A bundle that outlived its incarnation fails loud with the
  canonical `:rf.error/frame-destroyed`; the honesty warning never fires for a
  dead bundle (the stale-op throw is the operative diagnostic there)."
  [frame-id token op]
  (fn [query-v]
    (if (frame/frame-incarnation-live? frame-id token)
      (do
        (maybe-warn-cross-frame-carried-subscribe! frame-id query-v)
        (op query-v))
      (throw-frame-ops-stale! frame-id :subscribe query-v))))

(defn- fence-op-2
  "Wrap a one-or-two-argument bundle op with the incarnation fence."
  [frame-id token op-name op]
  (fn
    ([a]
     (if (frame/frame-incarnation-live? frame-id token)
       (op a)
       (throw-frame-ops-stale! frame-id op-name a)))
    ([a b]
     (if (frame/frame-incarnation-live? frame-id token)
       (op a b)
       (throw-frame-ops-stale! frame-id op-name a)))))

(defn- mint-frame-ops
  "Build the incarnation-fenced `(frame)` bundle for one live incarnation of
  `frame-id`. The ops are core's own `make-capture-frame` closures (the
  stable dispatch/dispatch-sync/subscribe implementations, frame assoc'd
  LAST so a per-call `:frame` opt cannot override the lock) wrapped in the
  incarnation fence."
  [frame-id token]
  (let [base (rf-core/make-capture-frame frame-id nil)]
    {:frame         frame-id
     :dispatch      (fence-op-2 frame-id token :dispatch (:dispatch base))
     :dispatch-sync (fence-op-2 frame-id token :dispatch-sync (:dispatch-sync base))
     :subscribe     (fence-subscribe frame-id token (:subscribe base))}))

(defn- publish-frame-ops!
  "Mint and publish the `(frame)` bundle for the incarnation `token` of
  `frame-id`, LINEARIZED against a same-id destroy/reincarnation through the
  frame's drain serialization — the SAME seam `publish-plan-for-live-
  incarnation!` uses (rf2-vxgfnd.229). Returns the published (or the
  concurrently-published) bundle.

  The captured `token` is REVALIDATED as the still-live incarnation INSIDE the
  critical section, so publication and `destroy-frame!`'s liveness flip (which
  runs under the same `:drain-lock`) linearize. The incarnation-scoped closing
  check also covers `destroy-frame!`'s pre-liveness-flip window (its close
  marker is set — and the destroy hook has already PRUNED this cache entry —
  while `frame/frame` still returns the dying record). If a destroy/recreate
  overtook the read, the captured token is no longer live: we neither publish a
  dead-incarnation bundle nor displace the live incarnation's (a newer B's)
  cache entry — we fail loud through the canonical destroyed-frame path. Under
  the lock only ONE reader mints per incarnation, so a concurrent same-token
  first read observes the published entry and shares its identity.

  The publishing `swap!` is an unconditional `assoc`: the drain-serialized
  revalidation is what prevents an older-token reader from overwriting a newer
  entry, NOT a check-then-swap over the cache atom (which is exactly the torn
  check-then-independent-publish this fix removes)."
  [frame-id token]
  (or
   (frame/call-serialized-with-drain!
    frame-id
    (fn []
      (when (and (identical? token (frame/frame-incarnation-token frame-id))
                 (not (frame/frame-incarnation-closing? frame-id token)))
        (let [entry (get @frame-ops-cache frame-id)]
          (if (and entry (identical? token (:token entry)))
            (:bundle entry)
            (-> (swap! frame-ops-cache assoc frame-id
                       {:token token :bundle (mint-frame-ops frame-id token)})
                (get frame-id)
                :bundle))))))
   (throw-frame-ops-destroyed! frame-id)))

(defn frame-ops-for
  "INTERNAL committed-site bridge: return the incarnation-fenced operation
  bundle for an already-resolved `frame-id`.  Event candidates carry the exact
  frame observed by React render and call this only from the winning layout
  commit, so abandoned renders publish no frame-op cache state."
  [frame-id]
  (let [token (frame/frame-incarnation-token frame-id)]
    (when (or (nil? token)
              (frame/frame-incarnation-closing? frame-id token))
      (throw-frame-ops-destroyed! frame-id))
    (let [entry (get @frame-ops-cache frame-id)]
      (if (and entry (identical? token (:token entry)))
        (:bundle entry)
        (do
          (when-some [barrier *frame-ops-publish-barrier*]
            (barrier frame-id token))
          (publish-frame-ops! frame-id token))))))

(defn frame-ops
  "The runtime bridge `(frame)` lowers to (compiler-owned; the public
  authoring form is `re-frame.ui/frame`). Resolve the committed frame via
  the ambient chain (`resolve-frame` — explicit scope > dynamic binding >
  React context > loud `:rf.error/no-frame-context`), require it live
  (absent/closing raises the canonical `:rf.error/frame-destroyed`), and
  return the frame-locked operation bundle

      {:frame         <frame-id>
       :dispatch      (fn ([event] [event opts]))
       :dispatch-sync (fn ([event] [event opts]))
       :subscribe     (fn [query-v])}

  Identity is STABLE for one live frame incarnation (cached by incarnation
  token); ops are incarnation-fenced so a stale bundle fails loud instead
  of retargeting a same-id replacement frame. A cache MISS mints + publishes
  through `publish-frame-ops!`, which linearizes the write against a same-id
  destroy/reincarnation (rf2-vxgfnd.229) so a stale reader can neither displace
  a newer incarnation's entry nor return a dead-incarnation bundle."
  []
  (frame-ops-for (resolve-frame :frame 're-frame.ui/frame)))

;; ---------------------------------------------------------------------------
;; Scope element builders — the emitted halves
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn scope-element
     "The emitted client half of a top-region `frame-root`: SCOPE the
     preflight-ensured frame (its literal plan `:id`) to `children-arr`
     through the shared React frame context. Pure scope — ENSURE already
     ran at host preflight, before any render (03 §8), so no validation
     and no side effect happens here. A frame destroyed under a mounted
     root surfaces downstream as `:rf.error/frame-destroyed` (the
     registry-lookup category), never as a silent re-create."
     [frame-id children-arr]
     (apply adapter-context/provider-element frame-id
            (array-seq children-arr))))

#?(:cljs
   (defn provider-scope-element
     "The emitted client half of `[frame-provider {:frame f} …]`: validate
     the runtime target (`require-scope-frame!` — nil, malformed, and
     absent-frame all fail loud) and scope the live frame's id to
     `children-arr` through the shared React frame context. SCOPE-only:
     creates / refreshes / destroys nothing (the rf2-nyea0r split —
     providers scope, roots ensure)."
     [target children-arr]
     (apply adapter-context/provider-element
            (require-scope-frame! target 're-frame.ui/frame-provider)
            (array-seq children-arr))))

#?(:cljs
   (defn ->react-scope-element
     "The scope half of `ui/->react` for a SUPPLIED (own-property) `frame` prop
     (rf2-01rwd): validate the runtime `target` and scope the live frame's id to
     `children-arr` through the shared React frame context. Same one
     frame-target grammar (`frame/require-frame-provider-target!`) and shared
     provider (`adapter-context/provider-element`) as `provider-scope-element`,
     but SELF-ATTRIBUTED to `re-frame.ui/->react` — the outward bridge validates
     the prop it reserves and NAMES ITSELF in every typed failure, rather than
     delegating an error that blames `frame-provider` at exactly the migration
     seam `ui/->react` exists to simplify.

     `->react-component` calls this ONLY when the props object OWNS the `frame`
     key, so an explicit `frame={null}` / `frame={undefined}` arrives here as a
     nil `target` and fails loud with `:rf.error/no-frame-context` (the empty
     frame-target category) — never a silent fall-through to the ambient chain.
     A malformed target is `:rf.error/bad-frame-provider-arg`; a keyword / live
     frame value naming no live frame is `:rf.error/frame-provider-frame-absent`
     with bridge-relevant recovery; a valid target scopes without owning it.
     SCOPE-only: creates / refreshes / destroys nothing (the rf2-nyea0r split)."
     [target children-arr]
     (let [id (frame/require-frame-provider-target! target 're-frame.ui/->react)]
       (when (nil? (frame/frame id))
         (error/throw-error!
          :rf.error/frame-provider-frame-absent 're-frame.ui/->react
          (str "(ui/->react view) received a `frame` prop naming " (pr-str id)
               ", but no live frame is registered under it (never created, or "
               "already destroyed). The outward bridge SCOPES an already-live "
               "frame and creates nothing — create it first with rf/make-frame in "
               "the host app's boot/event infrastructure, or render the exported "
               "component under an ambient frame-provider / frame-root that scopes "
               "a live frame, then export the view")
          {:recovery :ensure-or-create-the-frame
           :extra {:frame id}}))
       (apply adapter-context/provider-element id (array-seq children-arr)))))

(defn jvm-provider-scope
  "The emitted JVM half of `[frame-provider {:frame f} …]`: validate the
  target and BIND the dynamic-tier ambient frame around the subtree's
  structural construction (`body-thunk` builds and returns the children
  fragment). The JVM has no React context; the dynamic binding is the
  same scope tier `rf/with-frame` uses, so frame-scoped reads inside the
  subtree resolve the provider's frame during a Tier-1 structural render.
  Returns the built subtree."
  [target body-thunk]
  (binding [frame/*current-frame*
            (require-scope-frame! target 're-frame.ui/frame-provider)]
    (body-thunk)))

(defn jvm-root-scope
  "The emitted JVM half of a top-region `frame-root` (rf2-vxgfnd.25): BIND
  the dynamic-tier ambient frame to the frame-root's literal `:id` around
  the subtree's structural construction (`body-thunk` builds and returns the
  children fragment). The JVM has no React context; the dynamic binding is
  the same scope tier `rf/with-frame` uses, so ambient frame-scoped reads
  inside the subtree resolve the frame-root's frame during a Tier-1
  structural render — the JVM mirror of the CLJS `scope-element`. PURE scope:
  ENSURE already ran at host preflight (03 §8), so — unlike the provider
  half — no runtime target validation happens here; the frame-root OWNS/
  ensured the frame it names. Returns the built subtree."
  [frame-id body-thunk]
  (binding [frame/*current-frame* frame-id]
    (body-thunk)))

;; ---------------------------------------------------------------------------
;; Frame-destroy → ViewCell teardown wiring (03 §4; rf2-vxgfnd.42)
;; ---------------------------------------------------------------------------
;;
;; core's `frame/destroy-frame!` fires the named cleanup hook
;; `:ui/on-frame-destroyed!` — exactly like `:machines/on-frame-destroyed!`,
;; `:schemas/on-frame-destroyed!`, `:routing/on-frame-destroyed!`, … — one
;; per optional artefact that owns frame-scoped teardown. The compiled-view
;; substrate answers it through `reactive/teardown-frame!`, which sweeps a
;; bounded victim set to `:dead`: every currently-connected ViewCell whose
;; retained subscription targets OR resource-incarnation records name the
;; destroyed frame (resource ownership is not read observation), PLUS every
;; still-disconnected but React-retained
;; root-owned ViewCell whose last published site values (retained subscription
;; targets / resource reservations) name it. The disconnected arm is the
;; non-obvious half and the reason the hook can never be connected-only — an
;; Activity-hidden cell holds no live observers yet must not survive its frame
;; to throw on reveal. The union is deduplicated and incarnation-scoped (a
;; same-id successor frame is never reaped); a cell whose retained React fiber
;; was already collected has left the weak-live `root-cells` registry and is
;; not scanned. A `:dead` cell's later read/probe follows the 03 §4 dead-cell
;; lifecycle instead of throwing `:rf.error/frame-destroyed` off the observation
;; port. Late-bound so core never statically requires this artefact — the hook
;; is simply unbound (a no-op) when day8/re-frame2-ui is absent from the
;; classpath. The disconnected-cell obligation is pinned by
;; `reactive-frame-teardown-cljs-test`'s
;; `frame-destroy-reaps-an-activity-hidden-cell`.
;;
;; The hook ALSO prunes the destroyed id's install record (rf2-vxgfnd.30 (a)):
;; `installed-plan-entry` only HID a dead record behind a liveness guard, so a
;; boot re-create of the same id resurrected the stale record as a false
;; `:rf.error/frame-payload-conflict` blaming the dead lifetime's installer.
;; Pruning makes the invariant real — a destroyed frame invalidates its install
;; record, so a later same-id plan is a genuinely new lifetime.
(late-bind/set-fn! :ui/on-frame-destroyed!
                   (fn [frame-id]
                     (swap! installed-plans dissoc frame-id)
                     ;; the destroyed incarnation's cached (frame) bundle can
                     ;; never be returned again (its token is dead) — prune it
                     ;; so the closures don't linger for the id's lifetime
                     (swap! frame-ops-cache dissoc frame-id)
                     (reactive/teardown-frame! frame-id)))
