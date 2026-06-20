(ns day8.re-frame2-xray.panel-enum
  "SINGLE SOURCE OF TRUTH for the Xray panel enumeration — the set of
  independently-mountable Xray panel surfaces. This is the
  API-governance keystone that also carries the guard half of the
  reconciliation.

  ## The problem this closes

  \"The set of Xray panels\" has THREE projections that could silently
  drift apart:

    1. THE MOUNT FACADE — the `mount-<panel>!` fn family in
       `day8.re-frame2-xray.panels` (the live code a host calls).
    2. THE XRAY API SPEC — the `mount-<panel>!` names enumerated in
       `tools/xray/spec/007-UX-IA.md` §Mountable surface inventory +
       `tools/xray/spec/008-Embedding-Contract.md`.
    3. THE API MANIFEST — the `:cljs-only` rows for
       `day8.re-frame2-xray.panels` in `spec/api-manifest-metadata.edn`
       that the CLJS enumeration probe runtime-verifies.

  Without a single source, adding / removing / renaming a panel means
  editing each projection by hand and HOPING they stay aligned — exactly
  the drift class the keystone exists to kill.

  ## The single source

  `panel-enum` below is the ONE authoritative enumeration. Every other
  projection DERIVES FROM or is VALIDATED AGAINST it:

    - The mount facade — the guard
      (`panel_enum_guard_cljs_test.cljs`) reconciles the live public
      `mount-*!` vars of `day8.re-frame2-xray.panels` against this set
      (bidirectional: a facade fn with no entry, or an entry with no
      live fn, → RED).
    - The Xray API spec — the same guard reconciles the `mount-*!`
      names parsed out of `007-UX-IA.md` + `008-Embedding-Contract.md`
      against this set (bidirectional → RED on drift).
    - The api-manifest `:cljs-only` rows — the CLJS enumeration probe +
      the `xray_spec_check` reconcile the manifest's `panels` rows
      against the live vars; because the live vars are themselves
      guarded against this enum, the manifest inherits enum coherence
      transitively.

  A DRIFT between any projection and this source goes RED in CI.

  ## Why `.cljc` + pure data

  Mirrors `panel_registry.cljc`: the JVM test corpus + the api-manifest
  tooling can read this enumeration without spinning a CLJS runtime,
  and the CLJS guard reads it as a plain value. The `:mount` / `:view`
  axes are STRINGS (not the actual vars) so this data file pulls in no
  view code and carries no substrate dependency — it is a pure
  catalogue, JVM-portable by construction.

  ## Entry shape

    :id     keyword — stable panel identity.
    :mount  string  — the public `mount-<panel>!` fn name in
                      `day8.re-frame2-xray.panels` (the facade var the
                      guard reconciles).
    :view   string  — the `reg-view` rendered by the mount fn, as a
                      `<ns-tail>/<View>` reference (documentation /
                      cross-check axis; not load-bearing for the guard).
    :tier   keyword — the 007-UX-IA §Mountable surface inventory tier:
                      :l3-tab        — Tier 1, an L3 detail-panel tab.
                      :overlay       — Tier 2, a modal-light popup.
                      :inline        — Tier 3, inline content surface.
                      :spine         — the embeddable L2 event spine
                                       (008 §Embeddable event spine).
                      :full-shell    — the master `mount-shell!` entry.

  Tier 4 internal sub-components (after-rings overlay, sim side-rail)
  are NOT in this enum — they are geometry-coupled to
  `machine-inspector/Panel` and expose no standalone mount fn (per
  007-UX-IA §Tier 4). The enum carries exactly the mountable surface.")

;; ---------------------------------------------------------------------------
;; THE SINGLE SOURCE — the mountable Xray panel enumeration.
;; ---------------------------------------------------------------------------

(def panel-enum
  "The authoritative vector of mountable Xray panel surfaces. Ordered
  by tier then by the 007-UX-IA inventory order for readable diffs.
  Adding / removing / renaming a panel is a ONE-LINE edit here — the
  guard then forces the mount facade + the Xray API spec to follow."
  [;; Tier 1 — L3 tab panels (007-UX-IA §Mountable surface inventory).
   {:id :epoch             :mount "mount-epoch-panel!"      :view "epoch-panel/Panel"      :tier :l3-tab}
   {:id :app-db-diff       :mount "mount-app-db-diff!"      :view "app-db-diff/Panel"      :tier :l3-tab}
   {:id :reactive          :mount "mount-reactive-panel!"   :view "reactive-panel/Panel"   :tier :l3-tab}
   {:id :trace             :mount "mount-trace!"            :view "trace/Panel"            :tier :l3-tab}
   {:id :machine-inspector :mount "mount-machine-inspector!" :view "machine-inspector/Panel" :tier :l3-tab}
   {:id :routing           :mount "mount-routing!"          :view "routing/Panel"          :tier :l3-tab}
   {:id :resources         :mount "mount-resources!"        :view "resources/Panel"        :tier :l3-tab}
   ;; The embeddable L2 event spine (008 §Embeddable event spine).
   {:id :event-spine       :mount "mount-event-spine!"      :view "shell/event-list"       :tier :spine}
   ;; Tier 2 — overlay / popup surfaces.
   {:id :segment-inspector :mount "mount-segment-inspector!" :view "app-db-segment-inspector/Popup" :tier :overlay}
   {:id :cancellation-cascade-side-panel
    :mount "mount-cancellation-cascade-side-panel!"
    :view "cancellation-cascade/SidePanel" :tier :overlay}
   {:id :cancellation-cascade-popover
    :mount "mount-cancellation-cascade-popover!"
    :view "cancellation-cascade/Popover"   :tier :overlay}
   ;; Tier 3 — inline content surface.
   {:id :managed-fx        :mount "mount-managed-fx!"       :view "panels/ManagedFxList"   :tier :inline}
   ;; The master full-shell entry.
   {:id :shell             :mount "mount-shell!"            :view "shell/shell-view"       :tier :full-shell}])

;; ---------------------------------------------------------------------------
;; Pure projections off the single source (JVM-portable; the guard reads
;; these).
;; ---------------------------------------------------------------------------

(def mount-fn-names
  "Set of every `mount-<panel>!` fn name the enum declares — the
  canonical mount-facade surface the guard reconciles the live vars +
  the spec references against."
  (into #{} (map :mount) panel-enum))

(def panel-ids
  "Set of every panel `:id` — stable identity axis."
  (into #{} (map :id) panel-enum))

(defn ids-by-tier
  "Map of `tier-kw -> #{panel-id ...}` for assertions over the tier
  partition."
  []
  (reduce (fn [acc {:keys [id tier]}]
            (update acc tier (fnil conj #{}) id))
          {} panel-enum))
