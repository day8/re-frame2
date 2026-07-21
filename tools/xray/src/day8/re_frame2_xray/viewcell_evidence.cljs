(ns day8.re-frame2-xray.viewcell-evidence
  "Xray's OWNED consumer of the re-frame.ui ViewCell invalidation-evidence
  projection (rf2-vxgfnd.146) — the real native runtime the
  `re-frame.ui.tool.evidence` tier was built for (rf2-vxgfnd.75/.46).

  ## Ownership span + receipt (rf2-vxgfnd.286)

  Xray claims the projection under the STABLE identity `owner-id`
  (`:rf.xray/viewcell-evidence`) from the preload's debug-gated boot
  block. But a stable identity is a REUSABLE key — after one ownership
  span closes and another reclaims the same id, `owner-id` equality
  alone cannot tell the two incarnations apart. So this ns layers an
  opaque, per-span RECEIPT over the evidence tier's identity+generation
  lifecycle (rf2-vxgfnd.147):

    - `acquire!` claims (or same-span re-arms) the projection and returns
      the span's opaque receipt — a FRESH token for a fresh claim, the
      SAME token for a same-owner re-arm (the `:after-load`/HMR path, so
      accumulated evidence and in-flight deliveries stay valid together);
    - a foreign owner already holding the projection is NEVER clobbered:
      the acquire is rejected (returns nil; the evidence tier emits the
      one console diagnostic) and Xray projects zero rows;
    - `release!` frees EXACTLY the span named by a receipt — a stale
      receipt from a superseded span can neither read a successor's data
      nor clear a successor's registration (the same-key ABA fence);
    - `release-current!` frees whatever span Xray currently owns — the
      owner-checked teardown the clean-slate reset tier drives (a foreign
      registration survives it);
    - every AUTHORIZED read (`rows`) checks the exact receipt AND that
      Xray is still the installed tier owner, so neither a foreign
      takeover nor a same-key reclaim ever surfaces another span's data.

  `installed-owner` (the tier read) remains DIAGNOSTIC-only: ownership is
  authorized by the receipt, not by owner-id equality.

  ## Reactive freshness (rf2-vxgfnd.286)

  Ownership is not app-db state, so a change to it (acquire/release) would
  not, on its own, invalidate the `:rf.xray/viewcell-evidence` sub — a
  live subscription + rendered panel could keep a stale row after Xray
  released the projection, until some unrelated epoch pump recomputed it.
  So each ownership transition bumps a monotonic REVISION and notifies the
  optional `ownership-listener*` the reactive bridge installs
  (`set-ownership-listener!`): the bridge writes the revision into Xray's
  own `:rf/xray` app-db, which is a reactive input to the single derived
  query. Cache invalidation only TRIGGERS the recompute; the recompute's
  `rows` call still checks the exact receipt, so reactivity can never
  authorize a stale reader.

  ## Dependency boundary

  tools/xray depends on the ui artefact's TOOL tier (dev-only, exactly
  like the feature artefacts Xray already reads); `re-frame.ui` does NOT
  know Xray exists — it exposes the projection, Xray consumes it. The
  reactive bridge (the sub, the ownership event, the listener) lives in
  the Xray sub layer (`panels.reactive-panel-subs`), so this ns stays a
  thin ownership/receipt ledger over the tier — no frame or dispatch
  coupling. Production applications never pull Xray (the tools/README
  bundle-isolation contract), and tool absence stays zero-cost: this ns
  loads only from the dev-only preload, and the debug evidence plane
  itself DCEs out of production builds.

  ## What the developer sees

  `rows` shapes the bounded per-INCARNATION evidence for Xray's query +
  render path — the `:rf.xray/viewcell-evidence` sub feeding the Views
  panel's mounted-view evidence section.

  ## S3 — consume the VERSIONED PUBLIC projections (rf2-vxgfnd.95.7)

  This ns owns the ViewCell evidence PROJECTION (install/receipt/ownership),
  but it no longer reads the raw `re-frame.ui.tool.evidence/projection`
  interim shape. It reads the DEV-ONLY VERSIONED PUBLIC tier
  `re-frame.ui.tool` (rf2-vxgfnd.95.6) — the frozen read-only projections
  a debugging consumer reads WITHOUT touching private React state
  or the scheduler:

    - `explain-render`   → the cumulative render CAUSES per live incarnation,
                           carrying OCCURRENCE IDENTITY (`:occurrence`), the
                           live `:connection` state, the honest hide-vs-unmount
                           `:lifecycle` labels, cause set, first/latest epoch,
                           the moving `:observations` (with `:identity-exact?`),
                           and EXPLICIT `:loss` accounting.
    - `view-manifest` / `view-dependencies` / `view-event-sites` → the STATIC
                           registrar-manifest projections (`view-sites`) — a
                           view's declared source, dependency sites (sub,
                           literal vs `:dynamic`), and event-handler sites
                           (`:literal` / `:normalized` / `:dynamic`), available
                           before mount.

  Every projection stamps `:rf.ui.tool/version`; `version-status` reads it so
  a consumer built against an older head DEGRADES honestly (an unrecognized
  version suppresses row parsing rather than mis-reading an evolved shape)
  instead of presenting a mis-parse as exact.

  The ownership/receipt FENCE is unchanged and still load-bearing: the public
  tier's reads (`explain-render` → `evidence/instance-records`) return the
  shared slot's entries REGARDLESS of owner, so every AUTHORIZED read still
  checks the exact span receipt AND that Xray still owns the tier — a foreign
  takeover or a same-key reclaim never surfaces another span's data. Static
  manifest projections carry no ownership (the registrar is not a per-span
  slot), but `view-sites` is keyed off the receipt-fenced evidence rows'
  view-ids, so absent evidence yields no sites (the evidence-keyed empty-state
  law). A host not running the re-frame.ui substrate projects zero rows / zero
  sites. All of it DCEs out of production (the whole `re-frame.ui.tool` tier is
  `interop/debug-enabled?`-gated)."
  (:require [goog.object :as gobj]
            [re-frame.interop :as interop]
            [re-frame.ui.tool :as tool]
            [re-frame.ui.tool.evidence :as evidence]))

(def owner-id
  "Xray's stable evidence-projection owner identity. ONE value for the
  whole tool lifetime — preload acquire, `:after-load` re-arm, and
  shutdown release all name it, so HMR/reinstall and teardown release
  and re-claim exactly this registration and never another owner's. The
  id is the tier registration KEY; a single ownership SPAN under this id
  is identified exactly by the receipt (see the ns docstring)."
  :rf.xray/viewcell-evidence)

(def ^:private sentinel-key
  "`js/globalThis` marker mirroring a successful acquire — the browser
  feature-gate's cheap 'is the native evidence consumer wired?' probe
  (parallels the runtime session sentinel in
  `day8.re-frame2-xray.runtime`)."
  "__day8_re_frame2_xray_viewcell_evidence")

(defonce ^:private state*
  ;; Xray's own ownership-span ledger, layered over the evidence tier's
  ;; identity+generation lifecycle (rf2-vxgfnd.147/.286):
  ;;
  ;;   :revision — a monotonic counter bumped on every ownership
  ;;               TRANSITION (a fresh acquire and a release; NOT a
  ;;               same-span re-arm, which continues the span). The
  ;;               reactive freshness axis: the bridge writes it into
  ;;               `:rf/xray` app-db so the single derived query
  ;;               recomputes the instant ownership changes.
  ;;   :receipt  — the opaque token identifying the CURRENT ownership
  ;;               span, or nil when Xray does not own the projection.
  ;;               A fresh reference is minted on a fresh acquire and
  ;;               PRESERVED across a same-owner re-arm (HMR keeps the
  ;;               span); it goes nil on release. Reference identity is
  ;;               the exactness check — a stale span's receipt never
  ;;               equals the current one.
  ;;
  ;; `defonce` (module-lived): a hot reload keeps the ledger in lock-step
  ;; with the tier's own `defonce` state.
  (atom {:revision 0 :receipt nil}))

(defonce ^:private ownership-listener*
  ;; The single optional sink the reactive bridge installs
  ;; (`set-ownership-listener!`) — invoked with the new revision on every
  ;; ownership transition so a live `:rf.xray/viewcell-evidence`
  ;; subscription recomputes immediately, not only on the next epoch
  ;; pump. One listener (the Xray sub-graph bridge), NOT a registry: nil
  ;; until the sub layer wires it.
  (atom nil))

(defn set-ownership-listener!
  "Install the ownership-transition sink (the reactive bridge). Called
  once by the Xray sub layer; `f` is invoked with the new revision on
  each fresh acquire and each release. `nil` clears it."
  [f]
  (reset! ownership-listener* f)
  nil)

(defn- notify-listener!
  [revision]
  (when-let [f @ownership-listener*]
    (f revision))
  nil)

(defn- sync-sentinel!
  [installed?]
  (when (and interop/debug-enabled? (exists? js/globalThis))
    (if installed?
      (gobj/set js/globalThis sentinel-key
                (js-obj "owner" (str owner-id)
                        "installed" (.now js/Date)))
      (gobj/remove js/globalThis sentinel-key))))

(defn installed?
  "True when Xray currently owns the installed projection (diagnostic
  read). Ownership is AUTHORIZED by the receipt, not by this alone — but
  this catches a foreign takeover (tier owner ≠ Xray), which a stale
  receipt match cannot."
  []
  (= owner-id (evidence/installed-owner)))

(defn current-receipt
  "Xray's CURRENT ownership-span receipt, or nil when Xray does not own
  the projection. The token to present to `rows`/`release!` for an exact
  read/release of the live span."
  []
  (when (installed?)
    (:receipt @state*)))

(defn ownership-revision
  "The current monotonic ownership revision — the reactive-freshness
  value the bridge mirrors into app-db. Diagnostic / seed read."
  []
  (:revision @state*))

(defn- mint-fresh-span!
  "Open a NEW ownership span: mint a fresh receipt, bump + publish the
  revision, mirror the sentinel. Returns the receipt."
  []
  (let [receipt (js/Object.)
        rev     (:revision (swap! state* (fn [s]
                                           (-> s
                                               (update :revision inc)
                                               (assoc :receipt receipt)))))]
    (sync-sentinel! true)
    (notify-listener! rev)
    receipt))

(defn acquire!
  "Claim (or same-span re-arm) the ViewCell evidence projection for Xray
  — the preload boot step and the `:after-load` path. Idempotent.

    - unowned            → fresh claim: opens a NEW span, mints + returns
                           a fresh opaque receipt.
    - owned by Xray      → same-span RE-ARM (HMR / `:after-load`): the
                           accumulated evidence and in-flight deliveries
                           survive; returns the SAME receipt.
    - owned by another   → REJECTED (a foreign owner holds it, untouched;
                           the tier emits one console diagnostic) — returns
                           nil.
    - production          → no-op; returns nil.

  The returned receipt is the caller's handle for an exact read/release;
  standing callers (preload, `core/init!`) may ignore it — the ledger
  retains the current span, readable via `current-receipt`."
  []
  (let [pre-owner (evidence/installed-owner)
        ok?       (evidence/install! owner-id)]
    (cond
      (not ok?)               nil                 ; rejected / production
      (= pre-owner owner-id)                       ; same-owner re-arm
      (if-some [receipt (:receipt @state*)]
        (do (sync-sentinel! true) receipt)         ; continue the span
        (mint-fresh-span!))                         ; ledger lost it → remint
      :else                   (mint-fresh-span!)))) ; fresh claim

(defn- release-span!
  "Free Xray's current span: owner-checked tier release, ledger cleared,
  revision bumped + published, sentinel withdrawn. Assumes the caller has
  already authorized the release (receipt or owner check). Returns the
  tier's verdict."
  []
  (let [ok? (evidence/uninstall! owner-id)]
    (when ok?
      (let [rev (:revision (swap! state* (fn [s]
                                          (-> s
                                              (update :revision inc)
                                              (assoc :receipt nil)))))]
        (sync-sentinel! false)
        (notify-listener! rev)))
    ok?))

(defn release!
  "Release EXACTLY the ownership span named by `receipt` (the value
  `acquire!` returned). Receipt-checked AND owner-checked: releases iff
  `receipt` is Xray's CURRENT span receipt and Xray still owns the tier —
  so a stale receipt from a superseded span can NEVER clear a successor's
  registration (the same-key ABA fence). Returns true when released;
  false (nothing changes) otherwise."
  [receipt]
  (if (and (some? receipt)
           (= receipt (current-receipt)))
    (release-span!)
    false))

(defn release-current!
  "Release whatever span Xray currently owns — the owner-checked teardown
  the clean-slate reset tier drives. A foreign owner (Xray does not own)
  survives (returns false). Returns true when Xray's own registration was
  released."
  []
  (if (installed?)
    (release-span!)
    false))

(defn reset-for-test!
  "Test-only: force the WHOLE Xray evidence layer to baseline — the tier's
  `force-release!` (drops ANY owner + entries + sink), Xray's own ledger
  (receipt cleared), and the globalThis sentinel. The fixture-reset door
  that leaves no cross-test owner/sentinel leakage; production teardown
  uses the owner-checked `release-current!`. Returns nil."
  []
  (evidence/force-release!)
  (swap! state* assoc :receipt nil)
  (sync-sentinel! false)
  nil)

(def ^:private expected-schema-version
  "The `re-frame.ui.tool` evidence-schema version THIS Xray build was written to
  parse — a CONSUMER-OWNED literal, deliberately NOT `tool/schema-version`.
  Deriving support from the producer's own var makes ANY producer bump silently
  'supported', so an evolved shape would be mis-parsed as exact — the version
  boundary would be nominal. Pinning a literal makes a producer that bumps its
  schema a DETECTABLE mismatch until Xray is taught the new shape and this pin is
  bumped in lockstep. Currently 3, matching `re-frame.ui.tool/schema-version` 3."
  3)

(defn- supported-version?
  "True when the projection envelope carries EXACTLY the evidence-schema version
  THIS Xray build understands (`expected-schema-version`, a consumer-owned
  literal). A different (older or newer) producer version is degraded honestly
  rather than mis-parsed — the shape may have evolved incompatibly."
  [version]
  (= version expected-schema-version))

(defn- project-occurrence
  "Shape ONE `re-frame.ui.tool/explain-render` occurrence into the
  developer-facing row. The two loss axes stay DISTINCT and honest:
  `:targets-exact?` is observation-identity fidelity (the shown targets kept
  their exact identity), `:dropped-exact?` is completeness of the omitted-
  target count (`false` ⇒ a floor)."
  [{:keys [occurrence view-id root-id connection lifecycle causes
           render-count batches first-epoch latest-epoch observations loss]}]
  {:occurrence     occurrence
   :view-id        view-id
   :root-id        root-id
   :connection     connection
   :lifecycle      lifecycle
   :causes         (or causes #{})
   :count          (or render-count 0)
   :batches        (or batches 0)
   :first-epoch    first-epoch
   :latest-epoch   latest-epoch
   :targets        (:targets observations [])
   :targets-exact? (boolean (:identity-exact? observations))
   :dropped-count  (:dropped loss 0)
   :dropped-exact? (boolean (:exact? loss))})

(defn- project-rows
  "Shape the VERSIONED PUBLIC `re-frame.ui.tool/explain-render` projection
  into the developer-facing row vector. `[]` when the producer version is not
  the one this build understands (degrade — never mis-parse an evolved shape
  as exact)."
  []
  (let [envelope (tool/explain-render)]
    (if (supported-version? (:rf.ui.tool/version envelope))
      (mapv project-occurrence (:occurrences envelope))
      [])))

(defn rows
  "The developer-facing query projection over Xray's accumulated mounted-view
  evidence — a vector, in stable `:occurrence` order, sourced from the
  VERSIONED PUBLIC `re-frame.ui.tool/explain-render` projection (S3,
  rf2-vxgfnd.95.7):

    {:occurrence     ord      ; OCCURRENCE IDENTITY — stable per-incarnation
                              ;   ordinal (two instances of one view distinct)
     :view-id        vid      ; the authoring view (defview identity)
     :root-id        rid|nil  ; the owning LIVE client root, or nil
     :connection     state    ; the LIVE runtime lifecycle (:connected /
                              ;   :disconnected — dead cells are pruned)
     :lifecycle      {…}      ; bounded serializable lifecycle-fact log with
                              ;   the HONEST hide-vs-unmount labels + loss
     :first-epoch    e0       ; anchor of the first delivered window
     :latest-epoch   eN       ; most recent movement
     :count          n        ; total invalidation/render occurrences
     :batches        b        ; flushed batches that delivered evidence
     :causes         #{…}     ; union cause set (:subscription/:hmr/:disposed)
     :targets        [tk …]   ; bounded shown sample of moving targets
     :targets-exact? bool     ; observation-identity fidelity (false ⇒ opaque)
     :dropped-count  d        ; distinct omitted targets (honest loss)
     :dropped-exact? bool}    ; false ⇒ d is a lower bound (saturated)

  Two arities:

    (rows)          reads whatever span Xray CURRENTLY owns — the standing
                    panel/sub read.
    (rows receipt)  reads ONLY the span named by `receipt` — a confined
                    reader (one that captured a receipt) sees nothing once
                    its span closed, even if the same owner-id was reclaimed.

  `[]` when the presented span is not the installed owner (never
  installed, uninstalled, a FOREIGN owner holds it, or the receipt names a
  superseded span — ownership authorization must mean no observation
  through Xray), when empty, when the producer's evidence-schema version is
  not this build's (`version-status` surfaces the mismatch honestly), or in
  a production build (the whole `re-frame.ui.tool` tier is elided). The public
  tier reads `evidence/instance-records`, which returns the shared slot
  REGARDLESS of owner, so every read compares the exact receipt AND that Xray
  still owns the tier — cache invalidation alone can never authorize a stale
  reader (rf2-vxgfnd.238/.286)."
  ([] (rows (current-receipt)))
  ([receipt]
   ;; Exact authorization (rf2-vxgfnd.286): the receipt must name Xray's
   ;; CURRENT span AND Xray must still be the installed tier owner. The
   ;; public tier's read returns the shared slot's entries REGARDLESS of
   ;; owner, so a bare read would expose a foreign owner's evidence or a
   ;; superseded span's — gate EVERY read on both.
   (if (and (some? receipt)
            (= receipt (current-receipt)))
     (project-rows)
     [])))

(defn version-status
  "The evidence-schema version honesty read (receipt-fenced) — `{:version v
  :supported? bool}` for Xray's CURRENT span, or nil when Xray does not own
  the projection / no envelope / production. `:supported? false` (a version
  present but not this build's) means `rows` is suppressed to avoid a
  mis-parse, and the panel surfaces the mismatch rather than pretending an
  evolved shape is exact. The version rides EVERY `re-frame.ui.tool`
  projection, so one read is representative."
  ([] (version-status (current-receipt)))
  ([receipt]
   (when (and (some? receipt)
              (= receipt (current-receipt)))
     (when-some [version (:rf.ui.tool/version (tool/explain-render))]
       {:version    version
        :supported? (supported-version? version)}))))

(defn view-sites
  "The STATIC per-view manifest-site projections for `view-ids` — the
  event-site provenance, dependency sites, and manifest facts a debugging
  consumer reads from the compiler manifest BEFORE mount (S3,
  rf2-vxgfnd.95.7). One entry per view-id that resolves to a registered
  compiled view (unregistered / legacy-adapter ids are skipped — no
  fabricated `(no manifest)` row); shaped from the VERSIONED PUBLIC
  `view-manifest` / `view-dependencies` / `view-event-sites` projections:

    {:view-id        vid
     :source         {:file :line :column}?      ; the [code] chip source
     :display-name   str?
     :capabilities   #{…}                         ; :raw/:html/:foreign/…
     :site-counts    {:subs n :events n …}
     :template-fingerprint tf?
     :dependencies   {:subscriptions [{…}]}  ; literal vs :dynamic
     :event-sites    [{:prop :site-kind :classification :serializable?
                       :handler …}]                ; :literal/:normalized/:dynamic
     :diagnostics    [{:sid :id :tag :suppressed? :reason?}]}  ; compile-tier a11y

  These carry NO ownership (the registrar is not a per-span slot), so this
  read is NOT receipt-fenced; callers key it off the receipt-fenced evidence
  rows' view-ids so absent evidence yields no sites (the evidence-keyed
  empty-state law). A view whose producer version is unrecognized is skipped
  (degrade). nil-safe; distinct, first-seen view-id order preserved."
  [view-ids]
  (into []
        (comp
         (distinct)
         (keep (fn [view-id]
                 (let [m (tool/view-manifest view-id)]
                   (when (supported-version? (:rf.ui.tool/version m))
                     (let [deps   (tool/view-dependencies view-id)
                           events (tool/view-event-sites view-id)]
                       {:view-id              view-id
                        :source               (:source m)
                        :display-name         (:display-name m)
                        :capabilities         (:capabilities m)
                        :site-counts          (:site-counts m)
                        :template-fingerprint (:template-fingerprint m)
                        :dependencies         {:subscriptions (:subscriptions deps [])}
                        :event-sites          (:handlers events [])
                        ;; The compile-tier a11y findings (S4-C). Static
                        ;; evidence like everything else on this row: the
                        ;; COMPILER minted the site ids and made the
                        ;; suppressed-vs-printed call; this tier only reads
                        ;; them. Nothing is re-derived, and no check runs here.
                        :diagnostics          (:diagnostics m [])}))))))
        (or view-ids [])))
