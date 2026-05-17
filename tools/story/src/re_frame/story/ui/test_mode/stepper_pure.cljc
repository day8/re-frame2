(ns re-frame.story.ui.test-mode.stepper-pure
  "Pure data → data helpers for the play step-debugger (rf2-ulw5m + spec/009
  §Play step-debugger).

  The step-debugger gives the `:test` mode pane Storybook's
  Interactions-panel feel: step / pause / rewind / step-back / breakpoint
  controls over the variant's `:play` sequence. Everything below is pure
  data → data so the JVM test corpus pins the contract without booting
  Reagent or the runtime.

  Companion namespaces:

  - `re-frame.story.ui.test-mode.stepper-state`  — CLJS local-state atom +
    the lifecycle mutators (`begin!` / `step!` / `pause!` / `resume!` /
    `step-back!` / `rewind!` / `toggle-breakpoint!` / `end!`).
  - `re-frame.story.ui.test-mode.stepper-styles` — pure style map.
  - `re-frame.story.ui.test-mode.stepper-view`   — CLJS Reagent component."
  (:require [re-frame.story.predicates :as pred]
            [re-frame.story.ui.test-mode.pure :as test-mode-pure]))

;; ---- step status -------------------------------------------------------
;;
;; Each step in the stepper UI has a *position* status (pending / current /
;; done) AND an *outcome* status (the existing :pass / :fail / :event /
;; :skip from `test-mode-pure/play-step-statuses`). Pure: maps the cursor +
;; the outcome list onto one fold the view renders directly.

(defn step-position
  "Return `:done` / `:current` / `:pending` for `index` given the stepper
  `cursor` (`:ran` count — the number of events that have been dispatched
  so far). Pure; JVM-testable.

  Conventions:
   - `cursor` 0 ⇒ no step has run; the first row is `:current`.
   - `cursor` n where n = step count ⇒ every row is `:done`; nothing is
     `:current` (the stepper is parked at the end)."
  [index cursor]
  (cond
    (< index cursor)  :done
    (= index cursor)  :current
    :else             :pending))

(defn enrich-statuses
  "Walk `test-mode-pure/play-step-statuses` output and stamp each row with
   - `:position` — :done / :current / :pending (per `step-position`)
   - `:breakpoint?` — boolean, true when the row's `:index` is in
     `breakpoints` (a set)
   - `:outcome` — the existing `:status` (renamed so `:status` is free for
     the position semantics the view styles glyph against)

  Returns a vector of maps. Pure; JVM-testable."
  [statuses cursor breakpoints]
  (let [bps (or breakpoints #{})]
    (mapv (fn [{:keys [index status] :as row}]
            (assoc row
                   :position    (step-position index cursor)
                   :breakpoint? (contains? bps index)
                   :outcome     status))
          statuses)))

;; ---- progress label -----------------------------------------------------

(defn progress-label
  "Render `\"step N of M\"` for the header strip. `cursor` is the
  zero-based pointer into the play vector (0 = before-first; n = after-
  last when n = total). Total of 0 returns `\"no steps\"`; non-numeric
  inputs collapse to the empty string for safe interpolation."
  [cursor total]
  (cond
    (or (not (integer? cursor))
        (not (integer? total)))   ""
    (<= total 0)                  "no steps"
    (<= cursor 0)                 (str "ready · " total " steps")
    (>= cursor total)             (str "done · " total " of " total)
    :else                         (str "step " cursor " of " total)))

;; ---- can-step? / can-step-back? / can-rewind? / at-end? ----------------

(defn at-end?
  "True iff the cursor has dispatched every event."
  [cursor total]
  (and (integer? cursor) (integer? total) (>= cursor total)))

(defn at-start?
  "True iff the cursor is parked before the first event."
  [cursor]
  (or (not (integer? cursor)) (<= cursor 0)))

(defn can-step?
  "Forward step is legal iff the stepper is active AND there are events
  remaining. Pure."
  [{:keys [active? cursor total]}]
  (boolean (and active? (not (at-end? cursor total)))))

(defn can-step-back?
  "Step-back is legal iff the stepper is active AND at least one event
  has been dispatched (cursor > 0). Pure."
  [{:keys [active? cursor]}]
  (boolean (and active? (not (at-start? cursor)))))

(defn can-rewind?
  "Rewind is legal iff the stepper is active AND at least one event has
  been dispatched. Same shape as `can-step-back?` — the UI offers both
  affordances but the predicate is identical."
  [{:keys [active? cursor]}]
  (boolean (and active? (not (at-start? cursor)))))

(defn can-pause?
  "Pause is offered iff the stepper is auto-playing."
  [{:keys [active? auto-playing?]}]
  (boolean (and active? auto-playing?)))

(defn can-resume?
  "Resume is offered iff the stepper is active, not currently auto-
  playing, AND has more steps to take."
  [{:keys [active? auto-playing? cursor total]}]
  (boolean (and active? (not auto-playing?) (not (at-end? cursor total)))))

;; ---- breakpoint hit? ----------------------------------------------------

(defn breakpoint-hit?
  "True iff the next step (`cursor`) is a breakpoint AND auto-play is
  in flight. Pure; the state mutator uses this to short-circuit the
  interval tick into a pause without dispatching the event.

  The `cursor` slot points at the NEXT step (zero-based, so cursor 0
  means 'step 1 has not yet run'). Breakpoint on step 3 (zero-based
  index 2) means: pause BEFORE dispatching the event at index 2."
  [cursor breakpoints]
  (and (set? breakpoints)
       (contains? breakpoints cursor)))

;; ---- event-label re-export ----------------------------------------------
;;
;; The view renders `(first event)` as the per-step row label. Re-uses the
;; existing pure helper so the stepper rows and the scrubber ticks share
;; one label projection.

(def play-step-label test-mode-pure/play-step-label)

;; ---- assertion-event? re-export (used by the view to glyph rows) -------

(def assertion-event? pred/assertion-event?)
