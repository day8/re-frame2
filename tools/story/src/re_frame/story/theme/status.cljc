(ns re-frame.story.theme.status
  "Story's shared **status colour vocabulary** (spec/018 §12.6 + §11).

  This is the single source of truth for the nine tool-wide status
  values every Story region inherits — the sidebar signal chips, the
  test-mode result rows, the evidence-spine beats, the play-status
  banner, and the Story↔Xray seam all key off these tokens so a
  `:pass` reads identically whether it is a sidebar dot, a result pill,
  or an evidence beat.

  ## Why a shared status layer

  This namespace is the one construction every region keys off, so a
  `:fail` reads as the same red in the sidebar, the test-mode result
  row, and the play-status banner — the vocabulary is *constructed*
  tool-wide rather than each region agreeing its own status→colour map
  by convention (which drifts). Spec/018 §12.6 makes the vocabulary
  normative and tool-wide.

  ## The contract (spec/018 §12.6)

  > Do not encode everything as red/green. `pending`, `fail`, `error`,
  > `cannot-run`, `blocked`, `dirty`, and `redacted` MUST remain
  > distinguishable in colour, **icon, text, and shape**.

  So each status carries FOUR discriminators, not one:

  - `:fg` / `:bg` / `:border` — the colour treatment (resolved from
    `theme.colors/tokens`; zero raw hex).
  - `:glyph` — a single structural character (`✓ ✗ ! ⊘ ▣ ● ◌ ◐ ▢`) so
    the state survives colour-blindness AND Windows High-Contrast Mode
    (where `forced-colors` strips the inline colour — see
    `theme.motion/motion-css`).
  - `:shape` — `:solid` / `:outline` / `:dashed` / `:ring` / `:half` —
    the chip / dot border treatment, a third non-colour channel. Each
    shape renders a VISUALLY DISTINCT border (solid edge / dashed edge /
    double ring / one-sided accent bar), so the shape discriminates even
    when the hue is stripped (see `shape-decoration`).
  - `:label` — the canonical short human label.

  Plus a presentation hint:

  - `:emphasis` — `:high` / `:normal` / `:low`. `:fail` / `:error`
    demand attention (`:high`); `:pass` recedes unless filtering
    (`:low`); the rest sit at `:normal`. Regions MAY use this to size /
    weight a status without re-deriving the priority order.

  ## Nine canonical statuses

  | status       | meaning (spec/018 §12.6)                                   |
  |--------------|------------------------------------------------------------|
  | `:pending`   | not yet run, currently running, or awaiting evidence       |
  | `:running`   | a run is in flight (the live sibling of `:pending`)        |
  | `:pass`      | settled success; low emphasis unless filtering             |
  | `:fail`      | expectation failed; primary attention                      |
  | `:error`     | tool/runtime/schema problem; DISTINCT from a failed expect |
  | `:cannot-run`| required evidence or runner missing; neutral warning       |
  | `:blocked`   | known-missing substrate or disabled unsafe operation       |
  | `:dirty`     | current controls/render differ from the saved variant      |
  | `:redacted`  | data was intentionally removed or hidden                   |

  `:running` is split out from `:pending` because the live workshop
  distinguishes \"queued\" from \"in flight\" — the spec lists `pending`
  as covering both, so `:running` aliases to `:pending`'s neutral
  posture but wears the warning hue + a half-ring shape so a run in
  progress is legible at a glance. Callers that only model the spec's
  eight may map `:running` → `:pending`.

  ## How call sites consume it

      (:require [re-frame.story.theme.status :as status])

      ;; full descriptor (colour + glyph + shape + label + emphasis):
      (status/descriptor :fail)
      ;; => {:fg \"#F47171\" :bg \"#3F1818\" :border \"#F47171\"
      ;;     :glyph \"✗\" :shape :outline :label \"Fail\" :emphasis :high}

      ;; a chip / pill style map (drop into an inline :style):
      (status/chip-style :cannot-run)

      ;; just the foreground colour (e.g. for a dot / glyph tint):
      (status/fg :pass)

  Pure data → data; JVM-portable so the test corpus can assert the
  vocabulary without a render pass."
  {:no-doc true}
  (:require [re-frame.story.theme.colors :as colors]))

(def order
  "Canonical priority order, most-attention-demanding first. Regions
  that sort or roll up mixed-status sets (the sidebar story-row rollup,
  the test-mode aggregate pill) walk this vector so a story carrying
  one `:fail` among many `:pass`es surfaces the `:fail`."
  [:error :fail :cannot-run :blocked :dirty :running :pending :redacted :pass])

(def descriptors
  "The canonical status → descriptor map. Each descriptor carries the
  four discriminators spec/018 §12.6 mandates (colour / glyph / shape /
  label) plus an `:emphasis` presentation hint. Colours resolve through
  `theme.colors/tokens` — zero raw hex.

  Shape vocabulary (each renders a DISTINCT border — see
  `shape-decoration`):
  - `:solid`   — filled ground, no special border (settled states).
  - `:outline` — 1px SOLID border in the status colour (states that need
                 a second channel: cannot-run, error).
  - `:dashed`  — 1px DASHED border (redacted — reads as 'removed').
  - `:ring`    — 2px DOUBLE border (pending — the reserved-but-empty slot
                 reads as a hollow double-ring, never a solid edge).
  - `:half`    — 3px SOLID left-accent bar (running — a one-sided
                 in-flight mark, visibly a partial treatment)."
  {:pending    {:fg (:text-tertiary colors/tokens)
                :bg "transparent"
                :border (:border-default colors/tokens)
                :glyph "◌" :shape :ring :label "Pending" :emphasis :low}
   :running    {:fg (:warning colors/tokens)
                :bg (:warning-bg colors/tokens)
                :border (:warning colors/tokens)
                :glyph "◐" :shape :half :label "Running" :emphasis :normal}
   :pass       {:fg (:success colors/tokens)
                :bg (:success-bg colors/tokens)
                :border (:success colors/tokens)
                :glyph "✓" :shape :solid :label "Pass" :emphasis :low}
   :fail       {:fg (:danger colors/tokens)
                :bg (:danger-bg colors/tokens)
                :border (:danger colors/tokens)
                :glyph "✗" :shape :solid :label "Fail" :emphasis :high}
   ;; error is DISTINCT from fail — same danger hue but an OUTLINE so it
   ;; never reads as a plain failed expectation (spec/018 §12.6).
   :error      {:fg (:danger colors/tokens)
                :bg (:danger-bg colors/tokens)
                :border (:danger colors/tokens)
                :glyph "!" :shape :outline :label "Error" :emphasis :high}
   ;; cannot-run is a NEUTRAL warning, not a failure — warning hue on a
   ;; neutral ground + outline so it reads 'could not observe', not 'bad'.
   :cannot-run {:fg (:warning colors/tokens)
                :bg (:bg-3 colors/tokens)
                :border (:warning colors/tokens)
                :glyph "⊘" :shape :outline :label "Can't run" :emphasis :normal}
   :blocked    {:fg (:mono-1 colors/tokens)
                :bg (:mono-3 colors/tokens)
                :border (:mono-2 colors/tokens)
                :glyph "▣" :shape :solid :label "Blocked" :emphasis :normal}
   :dirty      {:fg (:accent-amber colors/tokens)
                :bg (:accent-amber-soft colors/tokens)
                :border (:accent-amber-deep colors/tokens)
                :glyph "●" :shape :solid :label "Dirty" :emphasis :normal}
   :redacted   {:fg (:text-tertiary colors/tokens)
                :bg (:bg-3 colors/tokens)
                :border (:border-strong colors/tokens)
                :glyph "▢" :shape :dashed :label "Redacted" :emphasis :low}})

(def ^:private fallback
  "Unknown statuses degrade to `:pending`'s neutral descriptor rather
  than blanking — a typo in a status keyword should still paint a
  legible reserved slot, never throw."
  (:pending descriptors))

(defn descriptor
  "Return the full descriptor map for `status`, or the neutral
  `:pending` fallback when `status` is unknown / nil. Pure."
  [status]
  (get descriptors status fallback))

(defn fg
  "Foreground colour for `status` (the dot / glyph / text tint)."
  [status]
  (:fg (descriptor status)))

(defn bg
  "Background tint for `status` (the chip / pill ground)."
  [status]
  (:bg (descriptor status)))

(defn glyph
  "The structural discriminator glyph for `status` — the non-colour
  channel that survives colour-blindness + Windows HCM."
  [status]
  (:glyph (descriptor status)))

(defn label
  "The canonical short human label for `status`."
  [status]
  (:label (descriptor status)))

(defn- shape-decoration
  "Map a descriptor `:shape` to the inline-style FRAGMENT that gives the
  chip its non-colour SHAPE channel (spec/018 §12.6 — distinguishable in
  shape, not only colour). Returns a style-map fragment merged into
  `chip-style`, or `nil` for `:solid` (the filled ground carries the
  signal alone). Each shape renders VISUALLY DISTINCT so the five shapes
  are genuinely five channels, not two:

  - `:solid`   — no border; the filled `:bg` ground is the signal.
  - `:outline` — a 1px SOLID border in the status colour (error /
                 cannot-run — a crisp ring that reads 'flagged').
  - `:dashed`  — a 1px DASHED border (redacted — reads as 'removed').
  - `:ring`    — a 2px DOUBLE border (pending — the reserved-but-empty
                 slot reads as a hollow double-ring, never a solid edge).
  - `:half`    — a 3px SOLID left-accent BAR (running — a one-sided
                 in-flight mark, visibly a partial treatment not a full
                 border).

  Distinct CSS per shape — `border` vs `border-style: double` vs a
  one-sided `border-left` — so the shape survives a greyscale / forced-
  colors pass where the hue collapses."
  [{:keys [shape border]}]
  (case shape
    :outline {:border (str "1px solid " border)}
    :dashed  {:border (str "1px dashed " border)}
    :ring    {:border (str "2px double " border)}
    :half    {:border-left (str "3px solid " border)}
    ;; :solid — no explicit border; the ground carries the signal.
    nil))

(defn chip-style
  "Build an inline `:style` map for a status chip / pill. Composes the
  descriptor's ground + foreground + the shape-derived decoration. Regions
  layer their own padding / radius / type-scale on top — this fn owns
  ONLY the status-bearing colour + shape so every region's chip reads
  the same status the same way. The colour channel (`:background` /
  `:color`) plus the shape channel (`:border` / `:border-left`, see
  `shape-decoration`) are both carried so a chip survives a colour-blind
  / Windows-HCM pass on shape alone. Pure data → data.

  Public read-shape (the canvas + sidebar consume this directly):
  always a `:style`-mergeable map with `:background` + `:color`, plus a
  shape decoration for every non-`:solid` status."
  [status]
  (let [d (descriptor status)]
    (merge {:background (:bg d)
            :color      (:fg d)}
           (shape-decoration d))))

(defn rollup
  "Given a seq of statuses, return the single most-attention-demanding
  one per `order`. Empty / all-nil → `:pending`. Used by the sidebar
  story-row rollup + any aggregate pill so a mixed set surfaces its
  worst member. Pure."
  [statuses]
  (let [present (set (remove nil? statuses))]
    (or (some present order)
        :pending)))
