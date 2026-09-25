(ns day8.re-frame2-xray.panels.epoch.badge
  "Pure-data badge taxonomy for the Epoch panel.

  Maps each cascade step badge → its visual chrome (colour
  token + label text). The colour resolver returns a CSS-variable
  string off `theme/tokens` so the panel's badges flow through the
  active theme like every other Xray chrome element.

  ## Why a separate file

  The badge table is referenced by both the projection (for badge
  validation) and the view (for paint). Pulling it into a tiny shared
  ns keeps the visual contract one source of truth — the view's
  numbered cascade renders the same colour the spec/021 §10.1 badge
  catalogue commits to.

  ## Pure-data + JVM-portable

  Hex resolution happens via `theme/tokens` keyword → CSS-variable
  string lookup; no DOM, no substrate runtime."
  (:require [clojure.string :as str]
            [day8.re-frame2-xray.theme.tokens :as tokens]))

;; ---- badge taxonomy -----------------------------------------------------
;;
;; The design mock's badge colour table:
;;
;;     :DISPATCH      "#8c959f"                  ; mid grey
;;     :COEFFECT      "#a855f7"                  ; light purple
;;     :HANDLER       "var(--devtools-active)"   ; blue
;;     :FLOW          "var(--devtools-active)"   ; blue
;;     :SIDE-EFFECTS  "rgb(154, 103, 0)"         ; orange/brown
;;     :SUBSCRIPTIONS "#ec4899"                  ; pink
;;     :VIEWS         "var(--devtools-success)"  ; green
;;
;; The mock hex values map onto Xray's theme tokens:
;;
;;     #8c959f                — `:text-tertiary` (the muted grey in the
;;                              palette)
;;     #a855f7                — :magenta (violet-500; the COEFFECT mock
;;                              hue. The dark `:magenta` token's hex is
;;                              this exact value so the badge and the
;;                              mock match — and the shared `:magenta`
;;                              consumers (redacted sentinel chip, filter
;;                              "out" mode, etc.) read a violet-family
;;                              hue.)
;;     var(--devtools-active) — `:accent` (the single blue identity)
;;     rgb(154,103,0)         — :orange (functional amber, perf-slow
;;                              tier — close enough hue for the SIDE
;;                              EFFECTS step's irreversible/post-commit
;;                              signal)
;;     #ec4899                — :magenta-pink (the SUBSCRIPTIONS mock
;;                              hue, its own palette token so the
;;                              SUBSCRIPTIONS pill is visually distinct
;;                              from COEFFECT's violet-magenta; on one
;;                              shared `:magenta` the eye cannot separate
;;                              the two pills at a glance.)
;;     var(--devtools-success) — `:success` / `:green`
;;
;; :RECORDABLE-COFX (EP-0010 causal provenance — the flat `:rf.cofx` map's
;; declared recordable leaves, EP-0017 §9) pulls :text-secondary (a step
;; lighter than DISPATCH's :text-tertiary — it reads as orienting
;; causal-context metadata, not a pipeline action).
;;
;; The badge inventory is binding: the view never paints a badge
;; whose keyword is not in this map.

(def ^:private badge->token-key
  "Map from badge keyword → theme-token keyword. Resolves through
  `tokens/tokens` so the badge background reads off the active
  theme's CSS variable (one badge across light + dark).

  COEFFECT pulls `:magenta` (violet `#a855f7`); SUBSCRIPTIONS pulls
  `:magenta-pink` (`#ec4899`). On one shared token operators cannot
  separate them at a glance, so visual distinguishability earns one
  extra palette token. The two hues are the mock's assignment.

  HANDLER + FLOW share `:accent` — the Figma export's single-accent
  identity.

  Schema violations render as an inline per-step sub-block; hot-reload
  drift rides a standalone SCHEMA-HOT-RELOAD tail step, which pulls
  `:warning`.
  INTERCEPTOR pulls `:accent` (the same blue as HANDLER —
  the interceptor chain WRAPS the handler; they read as one identity
  family in the cascade, the chain around the handler body)."
  {:DISPATCH          :text-tertiary
   ;; EP-0017 §9 — RECORDABLE COEFFECTS (EP-0010 causal
   ;; provenance, the flat `:rf.cofx` map's declared recordable leaves)
   ;; sits right after DISPATCH SITE and reads as CONTEXT, not a pipeline
   ;; action. `:text-secondary` (brighter muted) keeps it in the
   ;; dispatch-site muted-family — a step lighter than DISPATCH's
   ;; `:text-tertiary` — so the causal-input section reads as orienting
   ;; metadata rather than competing with the action badges (HANDLER /
   ;; FLOW accent, etc.).
   :RECORDABLE-COFX   :text-secondary
   :COEFFECT          :magenta
   ;; EP-0022 §11 — INTERCEPTORS (plural, the AUTHORED chain)
   ;; shares `:accent` with INTERCEPTOR (the exception-only step) + HANDLER:
   ;; the authored chain WRAPS the handler, so both interceptor surfaces read
   ;; as one identity family in the cascade (the chain around the body).
   :INTERCEPTORS      :accent
   :INTERCEPTOR       :accent
   :HANDLER           :accent
   :FLOW              :accent
   :SIDE-EFFECTS      :orange
   :SUBSCRIPTIONS     :magenta-pink
   :VIEWS             :success
   :SCHEMA-HOT-RELOAD :warning})

(def ^:private badge->label
  "Map from badge keyword → uppercase label rendered in the badge
  pill. Pure data."
  {:DISPATCH          "DISPATCH"
   :RECORDABLE-COFX   "RECORDABLE COEFFECTS"
   :COEFFECT          "COEFFECT"
   :INTERCEPTORS      "INTERCEPTORS"
   :INTERCEPTOR       "INTERCEPTOR"
   :HANDLER           "EVENT HANDLER"
   :FLOW              "FLOW"
   :SIDE-EFFECTS      "EFFECT HANDLERS"
   :SUBSCRIPTIONS     "SUBSCRIPTIONS"
   :VIEWS             "VIEWS"
   :SCHEMA-HOT-RELOAD "SCHEMA HOT-RELOAD"})

(defn token-key
  "Return the theme-token KEYWORD for `badge` (e.g. `:accent` for
  `:HANDLER`). Useful in helpers + tests where the keyword is the
  primary value. Falls back to `:text-tertiary` for unknown badges so
  the view always paints something."
  [badge]
  (get badge->token-key badge :text-tertiary))

(defn colour
  "Return the CSS-variable string that paints `badge` (e.g.
  `\"var(--rf-xray-accent)\"` for `:HANDLER`). Falls back to muted
  text colour for unknown badges so the view never paints `nil`."
  [badge]
  (get tokens/tokens (token-key badge)
       (get tokens/tokens :text-tertiary)))

(defn label
  "Return the uppercase label string the badge pill renders (e.g.
  `\"EVENT HANDLER\"` for `:HANDLER`). Falls back to `(name badge)` for
  unknown badges so a future taxonomy extension still paints text."
  [badge]
  (or (get badge->label badge)
      (when (keyword? badge) (str/upper-case (name badge)))
      "?"))

(def step-numbered-circle-diameter-px
  "21px — the numbered cascade circle's diameter (the numbered cascade
  pattern)."
  21)

(def vertical-line-offset-px
  "13px — the vertical line starts at 13px from the
  top of the pipeline section."
  13)

(def circle-left-offset-px
  "-44px — the numbered circle's left anchor."
  -44)

(def line-left-offset-px
  "-34px — the vertical line's left anchor (between
  the circle column and the content column)."
  -34)

(def fib
  "Fibonacci spacing scale
  (3 · 5 · 8 · 13 · 21 · 34 · 55 · 89).

  Catalogued as data so view sites read keyed values rather than
  scattered magic numbers. Pure data; JVM-portable."
  {:f3   3
   :f5   5
   :f8   8
   :f13  13
   :f21  21
   :f34  34
   :f55  55
   :f89  89})

(defn fib-px
  "Resolve a fibonacci-key to a CSS px string (e.g. `:f13` →
  `\"13px\"`). Returns `\"0\"` for unknown keys so the view never
  paints `nil`."
  [k]
  (if-let [n (get fib k)]
    (str n "px")
    "0"))

;; ---- Machine-cascade row badges -----------------------------------------
;;
;; The machine-cascade view renders one row per substrate
;; emit (`:rf.machine/guard-evaluated`, `:rf.machine/action-ran`,
;; `:rf.machine/transition`, `:rf.machine.timer/cancelled`). Each row
;; carries a small badge — the row's KIND (`:guard / :action / :transition
;; / :timer`) — and, for `:action` rows, a PHASE chip (one of the
;; closed set: `:exit / :transition / :entry / :always / :after-action /
;; :initial-entry / :destroy-exit`).
;;
;; The kind / phase chrome is intentionally muted — the row's source
;; code body is the punchline; the chip exists to make scanning across
;; rows for "which phase fired here?" possible without parsing the
;; verb. Hue assignments:
;;
;;   :guard       — :text-tertiary (muted grey; guards are sentinels
;;                                  the cascade reads BEFORE the
;;                                  state change)
;;   :action      — :accent (the same blue HANDLER pulls; actions ARE
;;                           the handler's body)
;;   :transition  — :magenta (the same hue COEFFECT pulls; transitions
;;                            are state changes — the heart of the
;;                            machine handler)
;;   :timer       — :warning (after-timer cancellations ride at the
;;                            warning tone — they're consequential
;;                            but not errors)
;;
;; Phase chips (`:exit / :transition / :entry / :always / :after-action
;; / :initial-entry / :destroy-exit`) ride at the muted `:text-tertiary`
;; tone — they're refinement on the `:action` row's primary chrome.

(def ^:private cascade-kind->token-key
  "Map from machine-cascade row `:kind` keyword → theme-token keyword.
  The view's row-badge resolver reads off this table."
  {:guard       :text-tertiary
   :action      :accent
   :transition  :magenta
   ;; A parent-owned parallel `:always` ROUND's regional
   ;; transition. It IS a state change, so it shares the magenta transition
   ;; hue; the `[ALWAYS]` label + `for <region> · round <n>` clause
   ;; distinguish it from the outer aggregate `[TRANSITION]`.
   :microstep   :magenta
   :timer       :warning
   ;; The benign unhandled-event no-op. Muted/tertiary tone:
   ;; benign, low-signal — explicitly NOT an error/warning hue.
   :no-op       :text-tertiary
   ;; The machine's BIRTH (`[START]`). Green/success tone: a
   ;; clean creation is a GOOD event (xstate's `createActor(m).start()`),
   ;; and the green reads as "the machine came alive here" — distinct from
   ;; the blue ACTION, the magenta TRANSITION, and the muted no-op.
   :start       :success})

(def ^:private cascade-kind->label
  "Map from machine-cascade row `:kind` keyword → uppercase label
  rendered in the row's kind chip."
  {:guard       "GUARD"
   :action      "ACTION"
   :transition  "TRANSITION"
   ;; The parent-owned `:always` ROUND regional transition. The
   ;; eventless `:always` source names the round; the region + round-index
   ;; ride the row's `for <region> · round <n>` clause.
   :microstep   "ALWAYS"
   :timer       "TIMER"
   ;; "NO OP" (space, not hyphen) is the SOLE marker for the
   ;; benign unhandled-user-event no-op. The row reads
   ;; "[NO OP] staying in {state}" (`format/cascade-row-label`) — the pill
   ;; carries the one badge; the verb carries the consequence. No "ignored"
   ;; outcome chip, no "no-op —" prefix, no ", no transition" suffix.
   :no-op       "NO OP"
   ;; The machine's BIRTH pill. "START" mirrors xstate's
   ;; `createActor(m).start()` — the machine ran its initial-entry cascade
   ;; and installed its initial state.
   :start       "START"})

(defn cascade-kind-token-key
  "Theme-token keyword for a cascade row's `:kind`. Falls
  back to `:text-tertiary` for unknown kinds so the view always paints
  something."
  [kind]
  (get cascade-kind->token-key kind :text-tertiary))

(defn cascade-kind-colour
  "CSS-variable string for a cascade row's `:kind`."
  [kind]
  (get tokens/tokens (cascade-kind-token-key kind)
       (get tokens/tokens :text-tertiary)))

(defn cascade-kind-label
  "Uppercase label string for a cascade row's `:kind`.
  Falls back to `(str/upper-case (name kind))` for unknown kinds so
  the cascade still paints text on a taxonomy extension."
  [kind]
  (or (get cascade-kind->label kind)
      (when (keyword? kind) (str/upper-case (name kind)))
      "?"))

(def cascade-kind-set
  "Closed set of cascade row kinds the view paints chrome for.
  New kinds extend the projection's
  `machine-cascade-trace-ops` AND this set in lockstep.
  `:start` is the machine's birth `[START]` badge; `:microstep` is a
  parent-owned parallel `:always` round's regional transition, the
  `[ALWAYS]` badge."
  #{:guard :action :transition :microstep :timer :no-op :start})

(defn cascade-kind?
  "Predicate — `kind` keyword is a member of `cascade-kind-set`."
  [kind]
  (contains? cascade-kind-set kind))

;; ---- Action-phase chips (closed set) ------------------------------------

(def ^:private cascade-phase->label
  "Map from machine-cascade `:action` row's `:phase` keyword →
  short label rendered in the per-row phase chip.
  A closed set."
  {:exit            "exit"
   :transition      "transition"
   :entry           "entry"
   :always          "always"
   :after-action    "after-action"
   :initial-entry   "initial-entry"
   :destroy-exit    "destroy-exit"})

(defn cascade-phase-label
  "Short label string for a cascade `:action` row's `:phase`.
  Pure-data; the view reads off this table for the
  per-row phase chip."
  [phase]
  (or (get cascade-phase->label phase)
      (when (keyword? phase) (name phase))
      ""))

(def cascade-phase-set
  "Closed set of phases the substrate stamps on `:rf.machine/action-ran`
  trace events. Tests + the view's chip-rendering bail-out
  read this set."
  #{:exit :transition :entry :always
    :after-action :initial-entry :destroy-exit})

(defn cascade-phase?
  "Predicate — `phase` keyword is a member of `cascade-phase-set`."
  [phase]
  (contains? cascade-phase-set phase))

;; ---- merged ACTION badge ------------------------------------------------
;;
;; The per-`:action` row carries ONE descriptive badge that names the phase
;; AND the kind in a single token, rather than an `ACTION` kind pill beside
;; a separate phase pill (`exit` / `entry` / …):
;;
;;   ACTION + :exit          → `EXIT ACTION`
;;   ACTION + :entry         → `ENTRY ACTION`
;;   ACTION + :transition    → `TRANSITION ACTION`   (the LCA action — a REAL
;;                             action that runs between exit + entry per
;;                             Spec 005)
;;   ACTION + :always        → `ALWAYS ACTION`
;;   ACTION + :after-action  → `AFTER-ACTION ACTION`
;;   ACTION + :initial-entry → `INITIAL-ENTRY ACTION`
;;   ACTION + :destroy-exit  → `DESTROY-EXIT ACTION`
;;
;; A state-change TRANSITION ROW (kind = `:transition`) is a DISTINCT thing
;; (not an action) and has its own single `TRANSITION` pill — both can
;; appear in one cascade (the LCA action AND the state change). See
;; `cascade-kind-label`; this fn governs only the `:action` kind.

(defn cascade-action-badge-label
  "Merged ACTION-badge label for an `:action` cascade row. Folds the
  action's `:phase` and the `ACTION` kind into ONE token —
  `EXIT ACTION` / `ENTRY ACTION` / `TRANSITION ACTION` / `ALWAYS ACTION` /
  `AFTER-ACTION ACTION` / `INITIAL-ENTRY ACTION` / `DESTROY-EXIT ACTION`.

  Falls back to the bare `ACTION` label when no phase was stamped (an
  anonymous / phase-less action — defensive; the substrate always stamps a
  phase off the closed set). Pure-data; the view reads off this
  for the merged kind pill."
  [phase]
  (if (cascade-phase? phase)
    (str (str/upper-case (name phase)) " ACTION")
    (cascade-kind-label :action)))

;; ---- Outcome chip resolver ----------------------------------------------
;;
;; Each cascade row carries a thin outcome chip — `pass | fail | threw`
;; for guards and `cancelled (<reason>)` for timers. Actions carry no
;; chip and `:transition` rows carry no chip (an `N microstep(s)`
;; summary would be redundant with the per-microstep cascade rows).
;; The chip colour rides the outcome
;; keyword (success / warning / error) so the operator can eye-scan a
;; long cascade for failures without reading every label.

(defn cascade-outcome-token-key
  "Theme-token keyword for a cascade row's outcome glyph + chip
  colour. Pure-data; the view's outcome-chrome resolver
  keys off this table.

  Maps:
    :pass      → :success    (✓ green)
    :ok        → :success    (✓ green)
    :fail      → :warning    (▲ amber — fail is expected behaviour for
                              gating guards; not alarmist)
    :threw     → :error      (✗ red — threw is a bug)
    :cancelled → :text-tertiary  (· muted — cancellation is housekeeping)

  Falls back to `:text-tertiary` for unknown outcomes so the chip
  paints something even on a taxonomy extension."
  [outcome]
  (case outcome
    :pass      :success
    :ok        :success
    :fail      :warning
    :threw     :error
    :cancelled :text-tertiary
    ;; The benign no-op renders no outcome chip
    ;; (`format/cascade-outcome-label` returns nil for it; the "[NO OP]"
    ;; pill + "staying in {state}" verb carry the whole notice), so there
    ;; is no `:ignored` outcome value.
    :text-tertiary))

(defn cascade-outcome-glyph
  "Single-char outcome glyph for a cascade row. Pure-data;
  the view's per-row outcome chip composes the glyph + label.

  Maps:
    :pass / :ok → \"✓\"
    :fail       → \"▲\"
    :threw      → \"✗\"
    :cancelled  → \"·\"
    default     → \"·\""
  [outcome]
  (case outcome
    :pass      "✓"
    :ok        "✓"
    :fail      "▲"
    :threw     "✗"
    :cancelled "·"
    ;; There is no `:ignored` outcome (the benign no-op has no outcome chip).
    "·"))

;; There is no per-STAGE status glyph. On a clean run a ✓ on every stage
;; badge carries no information, and a failure is surfaced by the inline
;; exception card UNDER the failing stage, so a per-stage ✗ would be
;; redundant too. The pipeline reads quieter without it.
;;
;; The per-step `:ok` / `:error` / `:skipped` SHAPE lives in the
;; projection (`projection/step-status`) — it feeds the overall
;; cascade-outcome banner (`projection/epoch-outcome`, the `(some #(= :error …))`
;; scan) — and the per-EFFECT outcome glyphs (`fx-row-status-glyph`,
;; below) plus the cascade-outcome banner (`cascade-outcome-glyph`, above)
;; carry the distinct signals.

;; ---- Flat SIDE EFFECTS ledger row glyphs --------------------------------
;;
;; The flat per-effect ledger leads each row with a status glyph. The
;; closed set is the `:ok` / `:error` pair plus the three fx-outcome
;; statuses the projection produces (`fx-outcome-op->status`):
;;
;;   :ok         → ✓ (success)   — fx handler ran ok / :db committed
;;   :error      → ✗ (error)     — fx handler threw / no-such-fx
;;   :rollback   → ✗ (error)     — :db schema-fail rollback (red, same as
;;                                 :error; the row's reason box carries the
;;                                 "rolled back" detail)
;;   :overridden → ↺ (accent)    — fx override applied
;;   :skipped    → – (tertiary)  — :skipped-on-platform (gated, didn't run
;;                                 here) OR a dropped `other` effect. A
;;                                 distinct MUTED en-dash "n/a" — NEUTRAL,
;;                                 never trips the badge to cross. The
;;                                 en-dash avoids the middle-dot (= the
;;                                 `:cancelled` cascade glyph above) and the
;;                                 circled-slash (reads error-ish).

(def skipped-glyph
  "The muted en-dash glyph for a SKIPPED ledger row —
  `:skipped-on-platform` (gated, didn't run here) or a dropped `other`
  effect. Distinct from the `:cancelled` middle-dot; reads NEUTRAL."
  "–")

(def skipped-hover
  "Hover/title text for a `:skipped-on-platform` ledger row."
  "skipped on this platform — gated, didn't run here")

(defn overridden-hover
  "Hover/title text for an `:overridden` ledger row. Names
  the replacement and claims nothing about real I/O: an override replaces
  the HANDLER, and a replacement may well delegate to the real one."
  [override-to]
  (str "overridden — an :fx-overrides entry replaced this effect's handler"
       (cond
         (= :re-frame.fx/fn-value override-to) " with a function"
         (some? override-to)                   (str ", redirected to " override-to))))

(def noop-glyph
  "The empty-set glyph for a NO-OP `:db` ledger row — the
  handler returned an unchanged db (`:rf.event/db-noop`), so the commit
  skipped the write. Distinct from the `:skipped` en-dash (a gated /
  dropped effect) and the `:ok` tick (a real commit); reads NEUTRAL —
  the event ran and committed nothing."
  "∅")

(def noop-hover
  "Hover/title text for a NO-OP `:db` ledger row."
  "returned unchanged db — nothing committed")

(defn fx-row-status-glyph
  "Single-char leading glyph for a flat SIDE EFFECTS ledger row.
  Closed set over the `fx-outcome-op->status` outcomes plus
  the synthesised `:db` row's `:ok` / `:rollback` / `:noop`:
    :ok / :db-commit → ✓
    :error / :rollback → ✗
    :overridden      → ↺
    :skipped         → – (muted en-dash, NEUTRAL)
    :noop            → ∅ (empty-set, NEUTRAL — db unchanged)
  Defaults to the success tick for unknown statuses."
  [status]
  (case status
    :error      "✗"
    :rollback   "✗"
    :overridden "↺"
    :skipped    skipped-glyph
    :noop       noop-glyph
    "✓"))

(defn fx-row-status-token-key
  "Theme-token keyword for a flat SIDE EFFECTS ledger row's glyph colour:
    :ok         → :success
    :error      → :error
    :rollback   → :error
    :overridden → :accent
    :skipped    → :text-tertiary  (muted/tertiary — NEUTRAL)
    :noop       → :text-tertiary  (muted/tertiary — NEUTRAL)
  Falls back to `:success` for unknown statuses (the quiet tick)."
  [status]
  (case status
    :error      :error
    :rollback   :error
    :overridden :accent
    :skipped    :text-tertiary
    :noop       :text-tertiary
    :success))

(defn fx-row-status-colour
  "CSS-variable string for a flat SIDE EFFECTS ledger row's glyph."
  [status]
  (get tokens/tokens (fx-row-status-token-key status)
       (get tokens/tokens :success)))
