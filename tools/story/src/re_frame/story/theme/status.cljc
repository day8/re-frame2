(ns re-frame.story.theme.status
  "Story's shared **status colour vocabulary** (spec/018 §12.6 + §11).

  This is the single source of truth for the nine tool-wide status
  values every Story region inherits — the sidebar signal chips, the
  test-mode result rows, the evidence-spine beats, the play-status
  banner, and the Story↔Xray seam all key off these tokens so a
  `:pass` reads identically whether it is a sidebar dot, a result pill,
  or an evidence beat.

  ## Why a shared status layer

  Before this namespace each region encoded its own
  status→colour map (the sidebar's `:signal-status-*` keys, the
  test-mode view's pass/fail pills, the play-status banner's tints).
  The maps agreed by convention, not by construction — a drift in one
  region produced a tool that read `:fail` as one red in the sidebar
  and a different red in the result row. Spec/018 §12.6 makes the
  vocabulary normative and tool-wide; this namespace makes it
  *constructed* rather than *conventional*.

  ## The contract (spec/018 §12.6)

  > Do not encode everything as red/green. `pending`, `fail`, `error`,
  > `cannot-run`, `blocked`, `dirty`, and `redacted` MUST remain
  > distinguishable in colour, **icon, text, and shape**.

  So each status carries FOUR discriminators, not one:

  - `:fg` / `:bg` / `:border` — the colour treatment (resolved from
    `theme.colors/tokens`; zero raw hex per rf2-i3i5j).
  - `:glyph` — a single structural character (`✓ ✗ ! ⊘ ▣ ● ◌ ◐ ▢`) so
    the state survives colour-blindness AND Windows High-Contrast Mode
    (where `forced-colors` strips the inline colour — see
    `theme.motion/motion-css`).
  - `:shape` — `:solid` / `:outline` / `:dashed` / `:ring` — the chip /
    dot border treatment, a third non-colour channel.
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
  `theme.colors/tokens` — zero raw hex (rf2-i3i5j).

  Shape vocabulary:
  - `:solid`   — filled ground, no special border (settled states).
  - `:outline` — 1px solid border in the fg colour (states that need a
                 second channel: cannot-run, error).
  - `:dashed`  — 1px dashed border (redacted — reads as 'removed').
  - `:ring`    — transparent ground + neutral 1px ring (pending — the
                 reserved-but-empty slot).
  - `:half`    — a partial / in-flight treatment (running)."
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

(defn- border-style
  "Map a descriptor `:shape` to the CSS border the chip wears."
  [{:keys [shape border]}]
  (case shape
    :outline (str "1px solid " border)
    :dashed  (str "1px dashed " border)
    :ring    (str "1px solid " border)
    :half    (str "1px solid " border)
    ;; :solid — no explicit border; the ground carries the signal.
    nil))

(defn chip-style
  "Build an inline `:style` map for a status chip / pill. Composes the
  descriptor's ground + foreground + the shape-derived border. Regions
  layer their own padding / radius / type-scale on top — this fn owns
  ONLY the status-bearing colour + shape so every region's chip reads
  the same status the same way. Pure data → data."
  [status]
  (let [d (descriptor status)]
    (cond-> {:background (:bg d)
             :color      (:fg d)}
      (border-style d) (assoc :border (border-style d)))))

(defn rollup
  "Given a seq of statuses, return the single most-attention-demanding
  one per `order`. Empty / all-nil → `:pending`. Used by the sidebar
  story-row rollup + any aggregate pill so a mixed set surfaces its
  worst member. Pure."
  [statuses]
  (let [present (set (remove nil? statuses))]
    (or (some present order)
        :pending)))
