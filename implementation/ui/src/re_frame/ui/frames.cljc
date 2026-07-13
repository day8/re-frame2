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
      (create-if-absent), then — only while that incarnation remains live —
      record `{:config-fingerprint :installed-by}` (root-OWNED authority).
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
  rf2-lxwpob). A plan that throws mid-run
  labels the siblings it already wrote by PROVENANCE (rf2-vxgfnd.30 (b),
  rf2-vxgfnd.72): a sibling that this run FRESH-installed is tagged
  `:mount-incomplete` — its mount never completed and no root yet scopes
  it. A sibling that was ALREADY LIVE (a refresh of an installed root, or
  an adopt of a boot frame) keeps its live-root state and carries neutral
  `:preflight-attempt-failed` evidence instead: the frame and its scoping
  root PERSIST (Q49), only the re-preflight ATTEMPT failed. So a later
  conflict names an incomplete FRESH mount without ever claiming that a
  still-live root does not scope its refreshed frame.

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
  (:require [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.live-frame :as live-frame]
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
  ;;     {:config-fingerprint "cf1-…" :installed-by root-id}
  ;;   ADOPTED (a boot / external frame this root merely SCOPES — NO refresh
  ;;     rights; boot config stays authoritative):
  ;;     {:config-fingerprint "cf1-…" :adopted-by root-id :adopted true}
  ;; A failed run labels the surviving records it wrote by provenance
  ;; (rf2-vxgfnd.30 (b), rf2-vxgfnd.72): a FRESH install → `:mount-incomplete
  ;; true` (mount never completed, no root scopes it); an ALREADY-LIVE refresh
  ;; or adopt → `:preflight-attempt-failed true` (neutral attempt evidence —
  ;; the live frame + its scoping root persist, Q49). Records are
  ;; PRUNED on `frame/destroy-frame!` (rf2-vxgfnd.30 (a)) — the destroy hook
  ;; dissocs the id, so a re-created same-id frame is a genuinely new
  ;; lifetime rather than a resurrected dead-lifetime record.
  (atom {}))

(defn- adopted-record?
  "True when `record` marks a boot/external frame this root merely SCOPES
  (no installation authority — never a refresh target)."
  [record]
  (true? (:adopted record)))

(defn installed-plan-entry
  "The recorded install/adoption for `frame-id` (tool/test read):
  `{:config-fingerprint … :installed-by root-id}` (installed) or
  `{:config-fingerprint … :adopted-by root-id :adopted true}` (adopted), or
  nil. Records are pruned on destroy; the extra live-guard keeps a
  never-hooked path from surfacing a stale record as an install."
  [frame-id]
  (when (some? (frame/frame frame-id))
    (get @installed-plans frame-id)))

(defn reset-installed-plans!
  "Test support: wipe the plan-install registry. Does NOT destroy frames —
  `frame/destroy-frame!` them (or reset the frames registry) separately."
  []
  (reset! installed-plans {})
  nil)

(defn- publish-plan-for-live-incarnation!
  "Publish `record` for `frame-id` only while the currently-live incarnation
  stays current. Returns true when published, nil otherwise.

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
  [frame-id record]
  (when-let [incarnation (frame/frame-incarnation-token frame-id)]
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
            :installed installed
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
            :installed installed
            :arriving  {:config-fingerprint config-fingerprint
                        :root-id root-id}}}))

(defn- mark-mount-incomplete!
  "Tag the surviving FRESH-installed records `frame-ids` (siblings this run
  created for the first time before a later plan threw) `:mount-incomplete
  true`, so a later conflict never presents their root as a completed owner
  it never became — their mount did not complete and no root scopes them yet."
  [frame-ids]
  (when (seq frame-ids)
    (swap! installed-plans
           (fn [m] (reduce (fn [m id]
                             (cond-> m
                               (contains? m id) (assoc-in [id :mount-incomplete] true)))
                           m frame-ids)))))

(defn- mark-preflight-attempt-failed!
  "Tag the surviving ALREADY-LIVE records `frame-ids` (a refresh of an
  installed root, or an adopt of a boot frame, that this run wrote before a
  later plan threw) `:preflight-attempt-failed true` (rf2-vxgfnd.72). Neutral
  attempt evidence: the frame and its scoping root PERSIST (Q49), so this
  records that the re-preflight ATTEMPT did not complete WITHOUT overwriting
  the live root's completed-mount state with a mount-incomplete claim."
  [frame-ids]
  (when (seq frame-ids)
    (swap! installed-plans
           (fn [m] (reduce (fn [m id]
                             (cond-> m
                               (contains? m id)
                               (assoc-in [id :preflight-attempt-failed] true)))
                           m frame-ids)))))

(defn- clear-run-incomplete-evidence!
  "A run completed: every frame it touched is backed by a completed mount, so
  drop any stale failed-run evidence an earlier throwing run left — both the
  fresh-mount `:mount-incomplete` tag and the live-root
  `:preflight-attempt-failed` attempt tag. Only entries that carry a tag are
  rewritten (the found-live no-op path stays write-free in the common,
  unmarked case)."
  [frame-ids]
  (swap! installed-plans
         (fn [m] (reduce (fn [m id]
                           (let [rec (get m id)]
                             (cond-> m
                               (or (:mount-incomplete rec)
                                   (:preflight-attempt-failed rec))
                               (update id dissoc :mount-incomplete
                                       :preflight-attempt-failed))))
                         m frame-ids))))

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
  returning (EP-0027); its install record is published only if that
  incarnation remains live; a found-live same-fingerprint plan is a pure no-op
  (no re-seed — the HMR guarantee); a live plan-less (boot-created) frame
  met by a CONFIG-LESS plan is ADOPTED under a non-owning record — its
  config/generation untouched, only the fingerprint recorded
  (rf2-vxgfnd.26, rf2-vxgfnd.56). A plan that throws mid-run labels the
  siblings it already wrote by provenance: a FRESH install →
  `:mount-incomplete`; an ALREADY-LIVE refresh/adopt →
  `:preflight-attempt-failed` (the live root persists, Q49 — rf2-vxgfnd.30
  (b), rf2-vxgfnd.72). Returns nil."
  [root-id plans]
  (let [decided
        (mapv (fn [{:keys [frame-id config config-fingerprint] :as plan}]
                (let [installed       (installed-plan-entry frame-id)
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
                        (assoc plan ::action :found-live))
                      ;; INSTALLED = this-or-another root OWNS the lifetime.
                      (cond
                        (= (:config-fingerprint installed) config-fingerprint)
                        (assoc plan ::action :found-live)

                        (= (:installed-by installed) root-id)
                        (assoc plan ::action :refresh)

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
                      (assoc plan ::action :adopt))

                    :else
                    (assoc plan ::action :install))))
              plans)
        ;; frame-ids whose record THIS run writes, each tagged by PROVENANCE
        ;; so a mid-run throw can label it honestly (rf2-vxgfnd.30 (b),
        ;; rf2-vxgfnd.72):
        ;;   :fresh — an :install that created the frame this run. On a later
        ;;     throw its mount never completed and no root yet scopes it.
        ;;   :live  — a :refresh of an already-live installed root, or an
        ;;     :adopt of an already-live boot frame. On a later throw the frame
        ;;     and its scoping root PERSIST (Q49); only the ATTEMPT failed.
        ;; Accumulated in document order.
        written (volatile! [])]
    (try
      (doseq [{:keys [frame-id config config-fingerprint] :as plan} decided]
        (case (::action plan)
          ;; the ratified idempotent no-op: no re-seed, no record churn.
          :found-live nil

          ;; a live plan-less (boot-created) frame: create-if-absent means
          ;; create NOTHING. Record only the plan's fingerprint under an
          ;; ADOPTED (non-owning) record so a later cross-root arrival is
          ;; conflict-scoped — the boot frame's config/generation/`:images`
          ;; are left entirely untouched (rf2-vxgfnd.26), and the record can
          ;; never enter a root-owned refresh path (rf2-vxgfnd.56). The boot
          ;; frame was already LIVE, so a later throw is an attempt failure,
          ;; not a mount-incomplete over never-mounted state.
          :adopt (when (publish-plan-for-live-incarnation!
                        frame-id
                        {:config-fingerprint config-fingerprint
                         :adopted-by root-id
                         :adopted true})
                   (vswap! written conj [frame-id :live]))

          ;; :install / :refresh — create-if-absent / surgical refresh;
          ;; :initial-events drain synchronously inside the engine, in
          ;; document order across plans. The record lands only AFTER a
          ;; successful install AND only while that incarnation is still live,
          ;; so a throwing or self-destroying setup leaves no install record.
          ;; An :install is FRESH (nothing scoped this id before); a :refresh
          ;; acts on an ALREADY-LIVE installed root (its prior mount + render
          ;; persist).
          (do
            (live-frame/make-frame (assoc (or config {}) :id frame-id))
            (when (publish-plan-for-live-incarnation!
                   frame-id
                   {:config-fingerprint config-fingerprint
                    :installed-by root-id})
              (vswap! written conj
                      [frame-id (if (= :install (::action plan)) :fresh :live)])))))
      ;; The whole run completed. Drop stale failed-run evidence from records
      ;; that survived; a self-destroying setup has neither a live frame nor a
      ;; published record here, so the missing id is a no-op.
      (clear-run-incomplete-evidence! (map :frame-id decided))
      (catch #?(:clj Throwable :cljs :default) e
        ;; a plan threw mid-run: the siblings this run wrote stay live
        ;; (irreversible :initial-events — the atomicity posture). Label each
        ;; by provenance (rf2-vxgfnd.72): a FRESH install whose mount never
        ;; completed is :mount-incomplete + "no root scopes"; an already-LIVE
        ;; refresh/adopt keeps its live-root state and carries neutral
        ;; :preflight-attempt-failed evidence instead — its live root and last
        ;; committed render are untouched (Q49).
        (let [w @written]
          (mark-mount-incomplete!
           (into [] (comp (filter #(= :fresh (second %))) (map first)) w))
          (mark-preflight-attempt-failed!
           (into [] (comp (filter #(= :live (second %))) (map first)) w)))
        (throw e)))
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
  (let [id (frame/require-keyword-frame-provider-arg! target where)]
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

(defn resolve-frame
  "Resolve the frame a compiled-view frame-scoped `operation` targets —
  THE ambient chain (ns docstring): explicit `pin` > dynamic binding >
  React context (CLJS) > loud `:rf.error/no-frame-context`. Never
  synthesises `:rf/default` (EP-0002).

  A non-nil `pin` wins outright: a frame-id keyword or a live frame value
  normalises to the id; any other shape fails loud
  (`:rf.error/bad-frame-provider-arg` — an explicit-but-malformed target
  is never silently coerced). A nil `pin` falls through to the ambient
  tiers. `where` names the resolving call site for the diagnostics.

  Reads the shared React context object directly (see the ns docstring
  for why not the `:adapter/current-frame` late-bind hook)."
  ([operation where] (resolve-frame nil operation where))
  ([pin operation where]
   (if (some? pin)
     (frame/require-keyword-frame-provider-arg! pin where)
     (or (frame/frame-value->id
          #?(:cljs (adapter-context/function-component-current-frame)
             :clj  (frame/current-frame)))
         (let [payload (frame/no-frame-context-payload
                        operation {:where where})]
           (frame/emit-no-frame-context! payload)
           (throw (error/ex-info-from-data payload)))))))

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
;; substrate answers it by transitioning every currently-connected ViewCell
;; observing the destroyed frame to `:dead` (`reactive/teardown-frame!`), so a
;; subsequent read/probe follows the 03 §4 dead-cell lifecycle instead of
;; throwing `:rf.error/frame-destroyed` off the observation port. Late-bound so
;; core never statically requires this artefact — the hook is simply unbound
;; (a no-op) when day8/re-frame2-ui is absent from the classpath.
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
                     (reactive/teardown-frame! frame-id)))
