(ns re-frame.story.budgets
  "Single source of truth for the Story UI **parity budgets** (rf2-ba86n.2,
  ratified 2026-05-30). The qualitative Storybook parity bars in
  spec/018 §3.1 are turned into concrete, enforceable numbers here; the
  normative table lives in spec/018 §10 and this namespace is the code-side
  mirror it points at.

  ## Why a dedicated namespace

  The caps were previously inlined per surface (`sidebar/default-variant-cap`,
  `sidebar/default-artifact-cap`, `docs/evidence-excerpt-beat-cap`). That left
  no place for the *new* surfaces (controls flat-panel cap, variants-grid cell
  cap, matrix dimension guard) and no single artefact the enforcement gate
  (`re-frame.story.budgets-cljs-test`) could read. This namespace is that
  single source: the implementation surfaces read these constants, and the
  gate asserts the budgets against synthetic floor-scale fixtures. Change a
  number here and both the UI and the gate move together — no parallel copy.

  ## What is enforced vs. documented

  - **Structural / complexity budgets** (bounded output, single-pass
    derivation) are ENFORCED by the deterministic gate. These are the
    cap constants and the pure `bound-cells` / matrix-guard helpers.
  - **Latency budgets** (rebuild ≤ 8 ms, inline-validate ≤ 4 ms,
    failure→evidence ≤ 1 gesture / excerpt ≤ 2 beats, spine first paint
    ≤ 100 ms) are DOCUMENTED TARGETS — they live in `latency-targets-ms`
    as data so the spec, review checklist, and any future micro-bench
    share one number, but the CI gate does NOT assert wall-clock time
    (flaky in CI). The gate enforces the *shape* that makes the targets
    achievable (bounded output, no O(n²) pass), not the clock.

  ## F1 = cap-and-page (ratified)

  Every scale budget here is the proven `bound-variants` philosophy
  extended: a bounded prefix plus a `+N more` / page affordance. True
  virtualization (windowed render) is FUTURE (spec/018 §10) — it is not
  required for the first Story UI EPIC and is not a pure-data gate.

  Pure data → data. JVM + CLJS. No Reagent, no runtime."
  (:refer-clojure :exclude [bound-cells]))

;; ---------------------------------------------------------------------------
;; Navigation (sidebar) — N1, N2, N3
;; ---------------------------------------------------------------------------

(def sidebar-variant-cap
  "N1 — per-story variant rows the sidebar shows before bounding with a
  `+N more` expander (spec/018 §10). CURRENT, ratified at 40: a typical
  design-system story (a handful to a dozen variants) is never bounded,
  while a matrix-scale story stays scannable until the author opts to
  expand it. `re-frame.story.ui.sidebar/default-variant-cap` aliases this."
  40)

(def captured-artifact-cap
  "N2 — captured run-artifact rows the sidebar's collapsible captures
  section shows before bounding with a `+N more` expander (spec/018 §10).
  CURRENT, ratified at 20: captures accumulate across matrix / generated
  runs; the cap keeps the section scannable.
  `re-frame.story.ui.sidebar/default-artifact-cap` aliases this."
  20)

(def project-floor
  "N3 — the realistic project floor the sidebar derivation MUST stay
  bounded + single-pass at (spec/018 §10). Matches Storybook's documented
  'thousands of stories' handled range. The enforcement gate builds a
  synthetic registry of at least this size and asserts the derivation
  emits bounded output in a single pass — it does NOT assert wall-clock ms.
  Data so the gate and any future micro-bench share one floor."
  {:variants   2000
   :stories    200
   :workspaces 50})

;; ---------------------------------------------------------------------------
;; Controls — C2 (flat-panel row cap); C1 / C4 are the existing
;; summarise-before-expand lazy-nesting contract (spec/019 §4)
;; ---------------------------------------------------------------------------

(def controls-flat-row-cap
  "C2 — control rows a flat controls panel shows before bounding with a
  `+N more` summarise-and-expand affordance (spec/018 §10, spec/019 §4).
  A single view rarely has more than ~30 args (matches Storybook's eager
  args-table reality); 60 is generous headroom. Beyond it, summarise rather
  than flood. Nested controls are lazy past depth 1 regardless of this cap
  (the C1/C4 summarise-before-expand contract, spec/019 §4)."
  60)

;; ---------------------------------------------------------------------------
;; Variants-grid / matrices — G1, G2, G3
;; ---------------------------------------------------------------------------

(def grid-visible-cell-cap
  "G1 — visible cells a variants-grid renders before bounding with a
  `+N more` / page affordance (spec/018 §10). 100 cells (e.g. 10×10) is
  about the largest a human scans at once; beyond it the grid pages rather
  than floods the canvas. Mirrors the sidebar bounding philosophy (F1 =
  cap-and-page)."
  100)

(def matrix-warn-threshold
  "G2 — soft-warn threshold on a matrix's dimension product
  (axisA × axisB). A 12×12 (= 144) matrix is already dense; at or above
  this the UI SHOULD warn the author before generating a wall of cells.
  The warn is advisory — it does not bound rendering (that is `matrix-hard-cap`)."
  144)

(def matrix-hard-cap
  "G3 — hard ceiling on cells a matrix renders. Beyond this the grid MUST
  paginate and never render all cells, so a generated `:variants-grid` from
  a large registry can never freeze the canvas (spec/018 §10 — never freeze
  or flood). Strictly greater than `grid-visible-cell-cap`: the visible cap
  bounds one page; the hard cap bounds the total a single matrix may attempt
  before paging is forced."
  400)

;; ---------------------------------------------------------------------------
;; Cross-cutting documented latency TARGETS (X1, X2; N4, C3)
;; — data only; NOT asserted as wall-clock by the gate
;; ---------------------------------------------------------------------------

(def latency-targets-ms
  "Documented latency TARGETS in milliseconds (spec/018 §10). These are
  NOT enforced as wall-clock assertions (flaky in CI); the gate enforces the
  bounded-output + single-pass *shape* that makes them achievable. Kept as
  data so the spec, the review checklist, and any future opt-in micro-bench
  cite one number.

  - `:filtered-rebuild` — N4: the pure sidebar filter pipeline
    (`filter-variants` → `group-variants-by-story` → `filter-grouped-tree`)
    per search keystroke at the project floor.
  - `:inline-validate`  — C3: pure validation of one edited control field.
  - `:spine-first-paint` — X2: first paint of the evidence spine for a
    typical run (≤ ~200 beats)."
  {:filtered-rebuild  8
   :inline-validate   4
   :spine-first-paint 100})

(def evidence-gesture-budget
  "X1 — failure → first useful evidence budget (spec/018 §10). Documented
  target: at most one gesture from a failed assertion row to the evidence
  spine, and the docs inline excerpt is capped at `:excerpt-beats` beats
  (the CURRENT `re-frame.story.ui.docs/evidence-excerpt-beat-cap`). The
  excerpt-beat cap is structurally enforced where the excerpt is built; the
  one-gesture reach is a review-checklist bar (cannot be a pure unit gate)."
  {:max-gestures 1
   :excerpt-beats 2})

;; ---------------------------------------------------------------------------
;; Pure budget helpers — read by the implementation surfaces AND the gate
;; ---------------------------------------------------------------------------

(defn bound-cells
  "G1/G3 — pure data → data: bound a variants-grid cell seq to at most `cap`
  entries unless `expanded?`. Returns `{:shown [...] :hidden <n>}` — `:shown`
  is the capped (or full, when expanded) prefix and `:hidden` is the count
  paged out (0 when nothing was bounded). The cell sequence is consumed in a
  single bounded pass (`take`), so output never exceeds `cap` regardless of
  input size. Mirrors `re-frame.story.ui.sidebar/bound-variants` so the grid
  uses the same cap-and-page idiom (F1). `cap` defaults to
  `grid-visible-cell-cap`."
  ([cells] (bound-cells cells grid-visible-cell-cap false))
  ([cells cap expanded?]
   (let [total (count cells)]
     (if (or expanded? (<= total cap))
       {:shown (vec cells) :hidden 0}
       {:shown  (vec (take cap cells))
        :hidden (- total cap)}))))

(defn matrix-product
  "Pure: the dimension product (cell count) of a matrix with axis sizes
  `axis-sizes` (a seq of per-axis counts). An empty axis list is 0 cells."
  [axis-sizes]
  (if (seq axis-sizes)
    (reduce * 1 axis-sizes)
    0))

(defn matrix-warn?
  "G2 — pure predicate: should the author be soft-warned about this matrix?
  True when the dimension product reaches `matrix-warn-threshold` (144).
  Advisory only — does not bound rendering."
  [axis-sizes]
  (>= (matrix-product axis-sizes) matrix-warn-threshold))

(defn matrix-over-hard-cap?
  "G3 — pure predicate: does this matrix exceed the hard render cap (400)?
  True when the dimension product is greater than `matrix-hard-cap`, at
  which point the grid MUST paginate rather than render all cells."
  [axis-sizes]
  (> (matrix-product axis-sizes) matrix-hard-cap))

(defn matrix-page-count
  "G3 — pure: how many pages of `grid-visible-cell-cap` cells a matrix of
  `total` cells spans. A matrix at or under one page is `1`; the result is
  the ceiling of `total / grid-visible-cell-cap` (minimum 1). Lets the UI
  show a deterministic `page X of N` affordance without rendering all cells."
  [total]
  (max 1 (long (Math/ceil (/ (double (max 0 total)) grid-visible-cell-cap)))))
