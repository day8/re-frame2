(ns panel-gallery.gallery-diff-mode-universal
  "Story coverage for the **universalised three-mode diff toggle**
  (rf2-yqjrd).

  Sibling to `gallery-diff-mode-3` (which covers the R1-R8 grammar
  itself via the edn-inspector widget). This gallery exercises the
  PER-SURFACE adoption — App-DB, Machine Inspector snapshot,
  SUBSCRIPTIONS step value cells — so a reviewer can verify each
  affected surface paints the same `[diff][full][full+diff]` button
  bar with the same shared widget chrome.

  ## Surfaces

  - **App-DB panel** — universal panel-wide diff-mode toggle at the top.
    Variants seed an epoch into the spine so the App-DB panel's
    sections paint with diff annotations, then exercise each mode
    keyword via the toggle.
  - **Machine Inspector snapshot drill-in** — single mount + the
    three-mode toggle (replacing the prior Before/After side-by-
    side grid).
  - **SUBSCRIPTIONS step value cells** — per-row value rendering
    routed through the universal toggle.

  ## Why a separate file from the foundation gallery

  `gallery-diff-mode-3.cljs` (rf2-n2jig) covers the R-rule grammar
  via the edn-inspector widget directly. This file's variants ride
  the actual PANEL views (App-DB / Machine / SUBSCRIPTIONS) so the
  reviewer sees the toggle in its native chrome surrounding — not
  the standalone widget mount.

  ## Note on Story snapshot identity

  The bead (rf2-yqjrd) lists Story snapshot as a fourth surface.
  Investigation 2026-05-27: Story's snapshot identity (per rf2-zfy1e)
  is a content-hash string only — there's no value-diff UI surface
  in Story to retire. The hash is consumed by visual-regression
  services (Chromatic/Argos/Percy) as an opaque join key. No Story-
  side toggle work was required; the universal toggle adoption is
  limited to the three Xray surfaces above."
  (:require [re-frame.core :as rf]
            [re-frame.story :as story]
            [panel-gallery.panel-views :as panel-views]))

;; The gallery reuses the panel-views' registered `:Panel` views
;; (App-DB, Machine Inspector, Epoch) — each variant seeds a focused-
;; epoch state into the variant frame's :rf/xray app-db so the panel
;; renders meaningfully.

(defn register-all!
  "Register the universalised three-mode toggle Story surface.
  Idempotent under `install-canonical-vocabulary!` resets."
  []
  (story/install-canonical-vocabulary!)
  (panel-views/register!)

  (story/reg-tag :feature/xray-diff-mode-universal
    {:axis :feature
     :doc  "Xray universal three-mode toggle adoption (rf2-yqjrd) —
            App-DB / Machine Inspector / SUBSCRIPTIONS surfaces all
            consume the same `views.diff-mode-toggle` widget."})

  ;; -- App-DB panel --------------------------------------------------------

  (story/reg-story :story.xray.app-db/diff-mode
    {:doc        "App-DB panel — universal three-mode toggle drives
                  every section (TOP + per-:rf/* areas) uniformly."
     :component  :panel-gallery.app-db/Panel
     :tags       #{:dev :feature/xray-diff-mode-universal}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.app-db/diff-mode-full+diff
    {:doc        "App-DB panel in `:full+diff` mode (default). Every
                  section paints LIVE current-state with inline diff
                  annotations against the focused epoch's `:db-before`.
                  The mode-3 R1-R8 grammar applies to each section
                  body."
     :events     [;; Default mode — :full+diff. No mode seed needed.
                  ]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.app-db/diff-mode-full
    {:doc        "App-DB panel in `:full` mode. Every section paints
                  LIVE current-state with NO `:before` threaded —
                  plain browse, no diff chrome. Useful when the
                  operator wants to inspect shape alone."
     :events     [[:rf.xray.app-db/set-diff-mode :full]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.app-db/diff-mode-diff
    {:doc        "App-DB panel in `:diff` mode. Section list
                  suppressed; one flat path-prefixed change list at
                  the top. Same source as the Epoch HANDLER `:diff`
                  view + the MCP exporter (`:rf.xray/selected-epoch-
                  diff` triples)."
     :events     [[:rf.xray.app-db/set-diff-mode :diff]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; -- Machine Inspector snapshot drill-in --------------------------------

  (story/reg-story :story.xray.machine-inspector/snapshot-diff-mode
    {:doc        "Machine Inspector snapshot drill-in — single mount
                  + three-mode toggle. Replaces the rf2-3d987 side-
                  by-side Before/After grid."
     :component  :panel-gallery.machines/Panel
     :tags       #{:dev :feature/xray-diff-mode-universal}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.machine-inspector/snapshot-full+diff
    {:doc        "Machine Inspector snapshot in `:full+diff` mode
                  (default). Single mount; AFTER snapshot painted
                  with BEFORE threaded as the diff pre-image so
                  inline R1-R8 grammar annotations fire."
     :events     []
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.machine-inspector/snapshot-full
    {:doc        "Machine Inspector snapshot in `:full` mode. AFTER
                  snapshot alone, no BEFORE comparison — plain
                  browse of the post-transition state."
     :events     [[:rf.xray.machine-inspector/set-diff-mode :full]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.machine-inspector/snapshot-diff
    {:doc        "Machine Inspector snapshot in `:diff` mode. Flat
                  path-prefixed change list — same shape as App-DB
                  and Epoch HANDLER `:diff` lenses."
     :events     [[:rf.xray.machine-inspector/set-diff-mode :diff]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; -- SUBSCRIPTIONS step value-cell ---------------------------------------

  (story/reg-story :story.xray.epoch/subs-value-diff-mode
    {:doc        "Epoch SUBSCRIPTIONS step — per-row value cell
                  routed through the universal three-mode toggle.
                  Orthogonal to the existing `[all][changed]
                  [unchanged]` row-filter button bar."
     :component  :panel-gallery.epoch/Panel
     :tags       #{:dev :feature/xray-diff-mode-universal}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.epoch/subs-value-full+diff
    {:doc        "SUBSCRIPTIONS value cells in `:full+diff` mode
                  (default). Each `:changed?` row's value cell
                  paints the AFTER value via the edn-inspector with
                  BEFORE threaded as the diff pre-image."
     :events     []
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.epoch/subs-value-full
    {:doc        "SUBSCRIPTIONS value cells in `:full` mode. Each
                  `:changed?` row's value cell renders the AFTER
                  value alone via the edn-inspector — no BEFORE
                  comparison."
     :events     [[:rf.xray.epoch/set-subs-value-diff-mode :full]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.epoch/subs-value-diff
    {:doc        "SUBSCRIPTIONS value cells in `:diff` mode — the
                  pre-rf2-yqjrd shape: `✓ before → after` via the
                  `mini` widget. Preserved as one mode of the new
                  toggle so the prior compact rendering remains
                  available."
     :events     [[:rf.xray.epoch/set-subs-value-diff-mode :diff]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; -- Workspace ----------------------------------------------------------

  (story/reg-workspace :Workspace.xray.diff-mode-universal/all
    {:doc      "All universalised three-mode toggle variants in one
                auto-grid. Compare across surfaces — every panel
                paints the same `[diff][full][full+diff]` button bar
                with the same shared widget chrome."
     :layout   :variants-grid
     :columns  2
     :tags     #{:dev}}))

(register-all!)
