(ns day8.re-frame2-xray.viewcell-evidence
  "Xray's OWNED consumer of the re-frame.ui ViewCell invalidation-evidence
  projection (rf2-vxgfnd.146) — the real native runtime the
  `re-frame.ui.tool.evidence` tier was built for (rf2-vxgfnd.75/.46).

  ## Ownership + lifecycle

  Xray claims the projection under the STABLE identity `owner-id`
  (`:rf.xray/viewcell-evidence`) from the preload's debug-gated boot
  block. The evidence tier's identity-owned, generation-fenced lifecycle
  (rf2-vxgfnd.147) does the rest:

    - first load → `install!` claims the projection; a shadow-cljs
      `:after-load` re-runs the SAME call as the idempotent same-owner
      re-arm (accumulated evidence survives — the HMR path);
    - a foreign owner already holding the projection is NEVER clobbered:
      Xray's install is rejected (returns false; the evidence tier emits
      the one console diagnostic) and Xray simply projects zero rows;
    - `uninstall!` releases exactly Xray's registration — the
      tool-shutdown/test door. Generation fencing downstream guarantees
      an in-flight delivery cannot repopulate the released projection,
      and a stale release cannot touch a successor owner.

  ## Dependency boundary

  tools/xray depends on the ui artefact's TOOL tier (dev-only, exactly
  like the feature artefacts Xray already reads); `re-frame.ui` does NOT
  know Xray exists — it exposes the projection, Xray consumes it.
  Production applications never pull Xray (the tools/README
  bundle-isolation contract), and tool absence stays zero-cost: this ns
  loads only from the dev-only preload, and the debug evidence plane
  itself DCEs out of production builds.

  ## What the developer sees

  `rows` shapes the bounded per-cell accumulators for Xray's query +
  render path — the `:rf.xray/viewcell-evidence` sub feeding the Views
  panel's ViewCell Invalidation Evidence section: stable cell/view/root
  identity plus first/latest frame-epoch, occurrence count, batch count,
  the cause union, the bounded shown-target sample, and the HONEST loss
  account (`:dropped-count` / `:dropped-exact?`). A host not running the
  re-frame.ui substrate simply projects zero rows."
  (:require [goog.object :as gobj]
            [re-frame.interop :as interop]
            [re-frame.ui.tool.evidence :as evidence]))

(def owner-id
  "Xray's stable evidence-projection owner identity. ONE value for the
  whole tool lifetime — preload install, `:after-load` re-arm, and
  shutdown uninstall all name it, so HMR/reinstall and teardown release
  and re-claim exactly this registration and never another owner's."
  :rf.xray/viewcell-evidence)

(def ^:private sentinel-key
  "`js/globalThis` marker mirroring a successful install — the browser
  feature-gate's cheap 'is the native evidence consumer wired?' probe
  (parallels the runtime session sentinel in
  `day8.re-frame2-xray.runtime`)."
  "__day8_re_frame2_xray_viewcell_evidence")

(defn- sync-sentinel!
  [installed?]
  (when (and interop/debug-enabled? (exists? js/globalThis))
    (if installed?
      (gobj/set js/globalThis sentinel-key
                (js-obj "owner" (str owner-id)
                        "installed" (.now js/Date)))
      (gobj/remove js/globalThis sentinel-key))))

(defn install!
  "Claim (or same-owner re-arm) the ViewCell evidence projection for
  Xray — the preload boot step and the `:after-load` path. Idempotent.
  Returns the evidence tier's verdict: true installed/re-armed; false
  rejected (a foreign owner holds the projection — untouched) or
  production no-op."
  []
  (let [ok? (evidence/install! owner-id)]
    (when ok? (sync-sentinel! true))
    ok?))

(defn uninstall!
  "Release exactly Xray's registration (owner-checked here,
  generation-fenced downstream). Returns true when released; false when
  Xray was not the current owner (nothing changes)."
  []
  (let [ok? (evidence/uninstall! owner-id)]
    (when ok? (sync-sentinel! false))
    ok?))

(defn installed?
  "True when Xray currently owns the projection (query/test read)."
  []
  (= owner-id (evidence/installed-owner)))

(defn rows
  "The developer-facing query projection over Xray's accumulated ViewCell
  invalidation evidence — a vector, in stable `:cell-id` order:

    {:cell-id        ord      ; stable per-cell-instance ordinal
     :view-id        vid      ; the authoring view (defview identity)
     :root-id        rid|nil  ; the owning LIVE client root, or nil
     :first-epoch    e0       ; anchor of the first delivered window
     :latest-epoch   eN       ; most recent movement
     :count          n        ; total invalidation occurrences
     :batches        b        ; flushed batches that delivered evidence
     :causes         #{…}     ; union cause set (:value/:hmr/:disposed)
     :targets        [tk …]   ; bounded shown sample of moving targets
     :dropped-count  d        ; distinct omitted targets (honest loss)
     :dropped-exact? bool}    ; false ⇒ d is a lower bound (saturated)

  `[]` when Xray does NOT own the installed projection (never installed,
  uninstalled, or a FOREIGN owner holds it — ownership rejection must mean
  no observation through Xray), when empty, or in a production build (where
  the debug evidence plane is elided)."
  []
  ;; Ownership fence (rf2-vxgfnd.238). The evidence tier's `projection`
  ;; read returns the shared slot's entries REGARDLESS of owner, so a bare
  ;; read here would expose a foreign owner's evidence whenever Xray's
  ;; install was rejected (`installed?` false). Gate EVERY read — rows and
  ;; the `:rf.xray/viewcell-evidence` sub/UI that derive from it — on the
  ;; exact ownership token: Xray observes iff Xray is the installed owner.
  (if-not (installed?)
    []
    (into []
          (map (fn [{:keys [cell-id view-id root-id evidence]}]
                 {:cell-id        cell-id
                  :view-id        view-id
                  :root-id        root-id
                  :first-epoch    (:first-epoch evidence)
                  :latest-epoch   (:latest-epoch evidence)
                  :count          (:count evidence)
                  :batches        (:batches evidence)
                  :causes         (:causes evidence)
                  :targets        (:targets evidence)
                  :dropped-count  (count (:dropped evidence))
                  :dropped-exact? (:dropped-exact? evidence)}))
          (or (evidence/projection) []))))
