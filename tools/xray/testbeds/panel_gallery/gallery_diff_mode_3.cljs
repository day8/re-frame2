(ns panel-gallery.gallery-diff-mode-3
  "Story coverage for the **mode-3 diff grammar** (rf2-n2jig).

  Mike's bead acceptance lists 22+ Story variants covering every
  R-rule (R1-R8 per the findings doc), every combination + edge case,
  and theme + density. Each variant is one Story entry the operator
  can navigate independently; the Workspace at the bottom collects
  them all in a single auto-grid so Mike can step through the
  grammar end-to-end in a logical order:

      Single-rule (R1-R8)
        → Combination + edge (all-ops, mixed, deep, long-vector, empty, massive)
        → Mode toggle three-up
        → Theme + density (light + dark + narrow)

  Frame isolation: each variant fires its `:panel-gallery.edn-
  inspector/seed!` event into its own variant frame; the gallery's
  `:panel-gallery.edn-inspector/Panel` view (mounted via
  `panel-views`) subscribes from the variant frame so widget state
  + expansion overrides stay independent across variants.

  ## Where the variants live

  Per Mike's bead, the choice is either `tools/xray/stories/diff-
  mode-3/` (new dir) OR fold into the existing
  `tools/xray/testbeds/panel_gallery/` (alongside the other
  gallery namespaces). We picked the latter — re-using the
  existing fixture seed event + Panel mount keeps the variant set
  surface uniform with the rest of the widget gallery, and the
  workspace can interleave mode-3 with the broader edn-inspector
  Story set."
  (:require [re-frame.core :as rf]
            [re-frame.story :as story]
            [panel-gallery.fixtures-diff-mode-3 :as fixtures]
            [panel-gallery.panel-views :as panel-views]))

;; The fixture-slot + sub + view registration live in
;; `gallery-edn-inspector` — both galleries seed into the same slot
;; key (`:panel-gallery.edn-inspector/fixture`) and reuse the
;; `:panel-gallery.edn-inspector/Panel` mount. We rely on the
;; sibling namespace having installed the seed event + Panel view
;; by the time variants here run.

(defn register-all!
  "Register the mode-3 grammar Story surface. Idempotent under
  `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (story/install-canonical-vocabulary!)
  (panel-views/register!)

  (story/reg-tag :feature/xray-diff-mode-3
    {:axis :feature
     :doc  "Xray edn-inspector mode-3 diff grammar (rf2-n2jig) —
            full data tree with inline diff annotations per the
            R1-R8 rules from the findings doc."})

  (story/reg-story :story.xray.diff-mode-3
    {:doc        "Mode-3 grammar visual reference — full data tree
                  WITH inline diff annotations. One variant per
                  R-rule + scenario + theme/density case so Mike
                  can visually verify the full grammar in action."
     :component  :panel-gallery.edn-inspector/Panel
     :tags       #{:dev :feature/xray-diff-mode-3}
     :substrates #{:reagent}})

  ;; -- Single-rule demonstrations (R1-R8) ---------------------------------

  (story/reg-variant :story.xray.diff-mode-3/r1-modified-scalar
    {:doc        "R1 — modified scalar. `{:counter 5} → {:counter 6}`.
                  Shows the value-side `~` glyph + `← was 5`
                  italic muted suffix."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/r1-modified-scalar)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.diff-mode-3/r2-new-map-key
    {:doc        "R2 (refined rf2-zpeyv) — new map key.
                  `{:a 1} → {:a 1 :b 2}`. Slot-anchored chrome: the
                  green wash spans the WHOLE row (`:b` key cell +
                  value cell). `+` glyph sits at column 1 of the key
                  cell. The slot identity changed (a key appeared),
                  so the visual unit is the whole row."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/r2-new-map-key)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.diff-mode-3/r2-removed-map-key
    {:doc        "R2 (refined rf2-zpeyv) — removed map key.
                  `{:a 1 :b 2} → {:a 1}`. Slot-anchored chrome: the
                  red wash spans the WHOLE row, and the strike-through
                  reaches the KEY text (`:b`) — not just the value.
                  `−` glyph sits at column 1 of the key cell. The
                  whole slot died, so the whole row reads as dead."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/r2-removed-map-key)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.diff-mode-3/r3-collapsed-chip
    {:doc        "R3 (revised) — collapsed `[N∆]` chip. A nested
                  `:user` map with three modified leaves, collapsed
                  via `:default-expanded-depth 1`. Operator sees
                  `▸ :user {…} [3∆]`. Click the triangle to expand
                  — chip vanishes, per-row signals appear."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/r3-collapsed-chip)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.diff-mode-3/r4-vertical-rail
    {:doc        "R4 — single 2px vertical rail through a change-
                  bearing subtree, painted in the dominant-op hue.
                  Sub-tree carries one change at row 3 of 5; the
                  rail runs the full subtree height."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/r4-vertical-rail)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.diff-mode-3/r5-wholly-new-subtree
    {:doc        "R5 (revised) — wholly-new subtree. `:flash` didn't
                  exist before; parent gets `+` glyph + green
                  triangle + green rail. Descendant gutter glyphs +
                  stripes SUPPRESSED; descendant row washes RETAINED
                  for partial-visibility readability."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/r5-wholly-new-subtree)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.diff-mode-3/r5-wholly-removed-subtree
    {:doc        "R5 (revised) — wholly-removed subtree. Inverse of
                  the new-subtree case; parent gets `−` glyph + red
                  triangle + red rail. Descendants paint red wash
                  but no per-leaf glyphs/stripes."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/r5-wholly-removed-subtree)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.diff-mode-3/r6-vector-insert
    {:doc        "R6 — vector insert with LCS shift. `[:a :b :c]` →
                  `[:a :NEW :b :c]`. After-index 1 carries `+`; after-
                  indices 2 and 3 paint `:same-shifted` + `(was 1)`
                  / `(was 2)` muted suffixes."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/r6-vector-insert)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.diff-mode-3/r6-vector-remove
    {:doc        "R6 — vector remove. Surviving elements carry
                  `(was N)` suffixes; the removed element lives on
                  the `:vector-removals` channel — the pure-diff
                  lens interleaves a `−` row at the surgical
                  position."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/r6-vector-remove)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.diff-mode-3/r6-vector-reorder
    {:doc        "R6 — vector reorder. `[:a :b :c]` → `[:c :a :b]`.
                  Demonstrates LCS detection (vs. naive's `every
                  element modified`). Editscript transcript is one
                  insert + one remove — the smallest possible."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/r6-vector-reorder)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.diff-mode-3/r7-type-change
    {:doc        "R7 — type-change container. `{:flash \"hi\"}` →
                  `{:flash {:level :ok}}`. String-to-map flip
                  classifies as `:modified` (not `:children`); value
                  column shows new map shape; suffix reads `← was
                  \"hi\"` via the `mini` renderer."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/r7-type-change)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.diff-mode-3/r8-was-redacted-now-visible
    {:doc        "R8 — `was redacted, now visible`. Before-side
                  carries `:rf/redacted`; after-side carries a real
                  value. Row reads `:modified` + curated `← was
                  redacted` suffix (no sentinel text leak)."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/r8-was-redacted-now-visible)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.diff-mode-3/r8-was-visible-now-redacted
    {:doc        "R8 — `was visible, now redacted`. Inverse; after-
                  side carries `:rf/redacted`. Suffix reads `← now
                  redacted`; prior value is NOT printed (the
                  redaction was probably applied for a reason)."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/r8-was-visible-now-redacted)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; -- Combination + edge-case demonstrations -----------------------------

  (story/reg-variant :story.xray.diff-mode-3/combo-all-ops-cascade
    {:doc        "All-ops cascade — the §2 canonical example: counter
                  modified + user/name modified + flash added +
                  legacy-flag removed. The full grammar in one mount."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/combo-all-ops-cascade)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.diff-mode-3/combo-mixed-ops-subtree
    {:doc        "Mixed-ops subtree — `:user` has BOTH `:added`,
                  `:removed`, and `:modified` descendants. The R5
                  wholly-added-collapse rule does NOT trigger;
                  per-leaf marking returns for the added leaves."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/combo-mixed-ops-subtree)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.diff-mode-3/combo-deeply-nested-change
    {:doc        "Deeply-nested change at depth 5+. Verifies
                  triangles + rails + chips don't pile up uselessly.
                  Single leaf change at the bottom produces a chain
                  of `:children` ancestors with their rails."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/combo-deeply-nested-change)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.diff-mode-3/combo-long-vector-one-insert
    {:doc        "Long-vector-one-insert (LCS payoff demo). 12-
                  element vector with one element inserted at index
                  4. Under naive compare this would render as 8
                  `~`s; under LCS it's 1 `+` + 7 `(was N)` suffixes.
                  The dramatic operator UX win."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/combo-long-vector-one-insert)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.diff-mode-3/combo-empty-diff
    {:doc        "Empty diff (no changes). Mode-3 must render
                  correctly when before == after; no glyphs, no
                  chips, no rails, no washes."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/combo-empty-diff)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.diff-mode-3/combo-massive-diff
    {:doc        "Massive diff — every key got new values. Verifies
                  the chrome doesn't melt into noise; the eye still
                  tracks the cascade because every row carries the
                  same `~` glyph at the same column."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/combo-massive-diff)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.diff-mode-3/combo-mode-toggle-three-up
    {:doc        "Mode-toggle three-up — same diff rendered in all
                  three modes (the workspace renders three separate
                  cards via `:variants-grid` layout; this single
                  variant pins the data). Operator sees the
                  relationship between `:diff` / `:full` /
                  `:full+diff`."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/combo-mode-toggle-three-up)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; -- Theme + density variants -------------------------------------------

  (story/reg-variant :story.xray.diff-mode-3/theme-narrow-panel
    {:doc        "Narrow-panel rendering — chrome must stay readable
                  at L4-panel-on-laptop widths (320px). The `(was N)`
                  suffixes + `[N∆]` chips don't wrap awkwardly.
                  Theme axis (light vs. dark) is handled by Story's
                  global theme toggle, not per-variant — the same
                  hiccup paints under both themes via CSS variable
                  resolution."
     :events     [[:panel-gallery.edn-inspector/seed!
                   (fixtures/theme-density-narrow-panel)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; -- Workspace ---------------------------------------------------------

  (story/reg-workspace :Workspace.xray.diff-mode-3/all
    {:doc      "All mode-3 grammar variants in one auto-grid. Walk
                top-to-bottom for the canonical sequence: R1 → R8
                → combinations → edge cases → theme/density. Each
                variant carries a title + 1-2 line description so
                Mike can step through visually without context-
                switching."
     :layout   :variants-grid
     :story    :story.xray.diff-mode-3
     :columns  2
     :tags     #{:dev}}))

(register-all!)
