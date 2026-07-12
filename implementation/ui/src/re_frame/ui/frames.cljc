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

  Per-plan disposition (document order; conflicts validated FIRST):

    - NO live frame         -> INSTALL: `make-frame` creates it
      (create-if-absent), then record `{:config-fingerprint
      :installed-by}`.
    - LIVE frame, NO install
      record (a boot `rf/make-frame`
      the plan registry never saw) -> ADOPT: create-if-absent means
      create NOTHING (03 §8 / 004C §6 — later roots find it live and do
      not re-seed). The boot frame's OWN config + image generation are
      AUTHORITATIVE and left UNTOUCHED — no `make-frame`, so no
      config/generation clobber, no `:images` discard, no re-seed. Only
      the plan's `{:config-fingerprint :installed-by}` is recorded, so a
      later cross-root arrival is conflict-scoped against it. This is the
      shared-frame case: an app boots the frame with its images /
      `:fx-overrides` and a root then declares `[frame-root {:id …}]`
      merely to SCOPE it. (rf2-vxgfnd.26: adopting via a config-carrying
      `make-frame` used to swap the generation to the default and wipe
      the boot config — the durable app-db survived, masking the clobber.)
    - SAME fingerprint      -> found live: PURE NO-OP (the ratified
      idempotent install — no re-seed, no config churn, no trace noise).
      This is the HMR / remount / shared-frame non-reseed guarantee.
    - DIFFERENT fingerprint,
      SAME installing root  -> REFRESH: the root re-declares its OWN
      frame (an HMR config edit). `make-frame`'s surgical update applies
      the new config; durable state survives; `:initial-events` are
      RE-RECORDED, never REPLAYED (EP-0027). The recorded fingerprint
      advances. (The §7 conflict names two DIFFERENT parties —
      `:installed-by` — so same-party re-declaration is the Clojure
      re-def model, exactly the Layer-1 same-file watch tolerance.)
    - DIFFERENT fingerprint,
      DIFFERENT root        -> `:rf.error/frame-payload-conflict`:
      fail THE ARRIVING ROOT; the installed frame and the roots already
      using it are untouched (06 §2 failure scoping). No first-wins
      silent merge, no last-wins overwrite.

  A destroyed frame invalidates its install record: a later plan for the
  same id is a genuinely new frame lifetime — created fresh,
  `:initial-events` REPLAYED (the opt-in destroy-then-reseat composition,
  rf2-lxwpob).

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

  There is NO `:rf/default` floor (the EP-0002 carried invariant). The
  chain reads the SHARED React context object
  (`re-frame.adapter.context/frame-context`) directly rather than the
  `:adapter/current-frame` late-bind hook: that hook is published by the
  per-adapter routing chains and is dead in an app running the compiled
  substrate alone. (When the ui substrate grows its own adapter spec, it
  should ALSO route the hook so core's ambient `dispatch`/`subscribe`
  see the context tier — an adapter-side touchpoint, out of S2c scope.)

  Scope has React context semantics: it applies to descendant VIEW
  boundaries rendered under the provider; sites in the SAME template body
  as the provider read the OUTER scope.

  On the JVM (structural rendering) there is no React context; the chain
  is explicit pin > dynamic binding > loud error, and `frame-provider`
  scopes by BINDING the dynamic tier around its subtree's construction
  (`jvm-provider-scope`)."
  (:require [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.live-frame :as live-frame]
            #?@(:cljs [[re-frame.adapter.context :as adapter-context]])))

;; ---------------------------------------------------------------------------
;; The plan-install registry
;; ---------------------------------------------------------------------------

(defonce ^:private installed-plans
  ;; frame-id -> {:config-fingerprint "cf1-…" :installed-by root-id}
  ;; An entry is live only while its frame is (a destroyed frame's record
  ;; is stale and replaced on the next install).
  (atom {}))

(defn installed-plan-entry
  "The recorded install for `frame-id` (tool/test read):
  `{:config-fingerprint … :installed-by root-id}` or nil. nil when the
  frame is no longer live (a stale record is not an install)."
  [frame-id]
  (when (some? (frame/frame frame-id))
    (get @installed-plans frame-id)))

(defn reset-installed-plans!
  "Test support: wipe the plan-install registry. Does NOT destroy frames —
  `frame/destroy-frame!` them (or reset the frames registry) separately."
  []
  (reset! installed-plans {})
  nil)

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
        (pr-str (:installed-by installed)) "). One frame, one plan: align "
        "the configs, or drop the duplicate frame-root. The installed "
        "frame and the roots already using it are untouched — this root "
        "failed before any install")
   {:recovery :align-frame-plan-config
    :extra {:frame-id  frame-id
            :installed installed
            :arriving  {:config-fingerprint config-fingerprint
                        :root-id root-id}}}))

(defn execute-frame-plans!
  "Execute a root's static frame plans (the LIVE preflight ENSURE — see
  the ns docstring for the full disposition table and the Q49 ruling).
  `plans` are the evaluated `[{:frame-id … :config-fingerprint …
  :config {…}} …]` in document order; `root-id` is the arriving root.

  Phase 1 VALIDATES every plan (pure — cross-root fingerprint conflicts
  throw `:rf.error/frame-payload-conflict` before ANY install). Phase 2
  installs/adopts/refreshes in document order: `make-frame` creates the
  frame if absent and drains `:initial-events` synchronously before
  returning (EP-0027); a found-live same-fingerprint plan is a pure no-op
  (no re-seed — the HMR guarantee); a live plan-less (boot-created) frame
  is ADOPTED — its config/generation untouched, only the fingerprint
  recorded (rf2-vxgfnd.26). Returns nil."
  [root-id plans]
  (let [decided
        (mapv (fn [{:keys [frame-id config-fingerprint] :as plan}]
                (let [installed (installed-plan-entry frame-id)]
                  (cond
                    (some? installed)
                    ;; a recorded plan already governs this LIVE frame
                    (cond
                      (= (:config-fingerprint installed) config-fingerprint)
                      (assoc plan ::action :found-live)

                      (= (:installed-by installed) root-id)
                      (assoc plan ::action :refresh)

                      :else
                      (throw-plan-conflict! root-id plan installed))

                    ;; No install record. Either the id is genuinely absent
                    ;; (INSTALL) or a frame is already LIVE under it but was
                    ;; created OUTSIDE the plan registry — a boot
                    ;; `rf/make-frame`. ENSURE is create-if-absent (03 §8):
                    ;; a live frame is ADOPTED, never re-created, so its
                    ;; boot config + image generation stay authoritative.
                    (some? (frame/frame frame-id))
                    (assoc plan ::action :adopt)

                    :else
                    (assoc plan ::action :install))))
              plans)]
    (doseq [{:keys [frame-id config config-fingerprint] :as plan} decided]
      (case (::action plan)
        ;; the ratified idempotent no-op: no re-seed, no record churn.
        :found-live nil

        ;; a live plan-less (boot-created) frame: create-if-absent means
        ;; create NOTHING. Record only the plan's fingerprint so a later
        ;; cross-root arrival is conflict-scoped — the boot frame's
        ;; config/generation/`:images` are left entirely untouched
        ;; (rf2-vxgfnd.26: a config-carrying `make-frame` here clobbered
        ;; them, masked by the durable app-db surviving).
        :adopt (swap! installed-plans assoc frame-id
                      {:config-fingerprint config-fingerprint
                       :installed-by root-id})

        ;; :install / :refresh — create-if-absent / surgical refresh;
        ;; :initial-events drain synchronously inside the engine, in
        ;; document order across plans. The record lands only AFTER a
        ;; successful install, so a throwing plan leaves no install record
        ;; (and the engine leaves no frame residue).
        (do
          (live-frame/make-frame (assoc (or config {}) :id frame-id))
          (swap! installed-plans assoc frame-id
                 {:config-fingerprint config-fingerprint
                  :installed-by root-id}))))
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
