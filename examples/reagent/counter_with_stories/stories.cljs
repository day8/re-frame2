(ns counter-with-stories.stories
  "Stories for the counter app. Demonstrates the seven `reg-*` macros
  end-to-end on a deliberately small domain so the patterns are
  visible rather than buried in business logic.

  Per spec/007 §Variants the body of every variant is plain data —
  no fn-slots. The view at the centre of each variant is referenced
  by id (`:counter-with-stories.views/counter-card`); the events
  the variant dispatches reference event-ids; decorators reference
  decorator-ids. Closures live exactly one place: the decorator's
  *registration site* (see `:counter-with-stories/log-decorator`
  below).

  The seven `reg-*` macros each appear at least once:

  - `reg-tag`         — `:counter-with-stories/canonical` (project tag).
  - `reg-mode`        — `:Mode.app/dark` + `:Mode.app/light` (theme tuples).
  - `reg-decorator`   — `:counter-with-stories/log-decorator` (custom
                        hiccup decorator alongside Story's canonical
                        `:rf.story/layout-debug.*` set).
  - `reg-story-panel` — `:Panel.counter-with-stories/notes` (a small
                        project-custom panel in the right pane).
  - `reg-story`       — `:story.counter` parent (the four variants
                        below all inherit its decorators + args).
  - `reg-variant`     — four variants exercising every authoring shape.
  - `reg-workspace`   — two workspaces: `:grid` + `:variants-grid`.

  Every variant declares `:substrates #{:reagent}` per IMPL-SPEC
  §2.8.1 (Reagent is the v1 lock; UIx / Helix variants ship post-v1).

  The play sequences at the bottom exercise three of the seven
  canonical `:rf.assert/*` events — `path-equals`, `sub-equals`,
  `dispatched?` — and one `force-fx-stub` decorator reference."
  (:require [re-frame.story :as story]
            ;; Source the event and view ids by requiring the namespaces
            ;; so they register themselves; the variant bodies reference
            ;; the ids as plain keywords (no fn-slots leak through).
            [counter-with-stories.events]
            [counter-with-stories.subs]
            [counter-with-stories.views]))

;; ---------------------------------------------------------------------------
;; register-all!
;;
;; Wrap every registration in a top-level fn so the test fixture can
;; re-fire the lot after a clear-all!. The fn is called once at
;; namespace load time (the trailing `(register-all!)` below) so
;; consumers who just `:require` this namespace get the side-table
;; populated as they expect.
;;
;; The seven canonical Story tags (:dev :docs :test :screenshot
;; :experimental :internal :agent) must register before any reg-story
;; / reg-variant references them, or the registrar throws
;; `:rf.error/unknown-tag`. install-canonical-vocabulary! is
;; idempotent; calling it at load time is cheap.
;; ---------------------------------------------------------------------------

(defn register-all!
  "Register the counter-with-stories example's Story artefacts.
  Idempotent. The trailing top-level call fires this at namespace
  load; the test fixture calls it again after a clear-all! per test."
  []
  (story/install-canonical-vocabulary!)

  ;; -------------------------------------------------------------------------
  ;; reg-tag — register the project's custom tag
  ;;
  ;; Per spec/007 §Inclusion tags the seven canonical tags (:dev :docs
  ;; :test :screenshot :experimental :internal :agent) register at
  ;; Story load; project-specific tags must register before use or the
  ;; registrar throws `:rf.error/unknown-tag`.
  ;; -------------------------------------------------------------------------

  (story/reg-tag :counter-with-stories/canonical
    {:doc "Tag applied to the variant that ships as the example's
          canonical screenshot — the one the README points at."})

  ;; -------------------------------------------------------------------------
  ;; reg-mode — the dark / light Chromatic-style saved tuples
  ;;
  ;; Per IMPL-SPEC §2.8.3 modes are saved tuples of args. When a variant
  ;; renders against `:Mode.app/dark` its `:args` deep-merge into the
  ;; variant's effective args (precedence: global < mode < story <
  ;; variant). Each `(variant × mode)` cell has its own snapshot-
  ;; identity for visual regression keying.
  ;; -------------------------------------------------------------------------

  (story/reg-mode :Mode.app/dark
    {:doc  "Dark theme — sets the background and label colours."
     :args {:theme       :dark
            :background  "#1e1e1e"
            :foreground  "#e0e0e0"}})

  (story/reg-mode :Mode.app/light
    {:doc  "Light theme — the default."
     :args {:theme       :light
            :background  "#ffffff"
            :foreground  "#1a1a1a"}})

  ;; -------------------------------------------------------------------------
  ;; reg-decorator — a project-custom hiccup decorator
  ;;
  ;; Per IMPL-SPEC §3.1 + §13.2 #3 decorators are the ONLY Story
  ;; authoring surface where a closure legally lives — and only on
  ;; `:hiccup`-kind decorators' `:wrap` slot. The closure lives at
  ;; registration time, not in variant bodies. The variant body
  ;; references the decorator by id; the registrar resolves the
  ;; closure at render time.
  ;; -------------------------------------------------------------------------

  (story/reg-decorator :counter-with-stories/log-decorator
    {:doc  "Wrap the variant in a labelled outline — a tiny custom
           decorator alongside Story's canonical `:rf.story/layout-
           debug.*` set. The first ref-arg becomes the label.

           Per IMPL-SPEC §5.3 (`apply-hiccup-decorators`): the `:wrap`
           fn receives `[body args-map]`. Decorator ref-args from
           `[:dec-id arg1 arg2 ...]` references arrive under
           `(:decorator/args args-map)` — that's where the label lives."
     :kind :hiccup
     :wrap (fn [body args]
             (let [label (first (:decorator/args args))]
               [:div {:style {:border  "1px dashed #888"
                              :padding "0.5em"
                              :margin  "0.25em"}}
                [:div {:style {:font-size "10px" :color "#888"}}
                 (str "decorator: " (or label "log"))]
                body]))})

  ;; -------------------------------------------------------------------------
  ;; reg-story-panel — a project-custom right-pane panel
  ;;
  ;; Per spec/007 §Story-tool extension hook + IMPL-SPEC §3.1, panels
  ;; are the project's escape hatch into the shell's chrome. Story
  ;; ships three v1 built-in panels (a11y / layout-debug / 10x-epoch
  ;; stub); projects add their own via reg-story-panel.
  ;; -------------------------------------------------------------------------

  (story/reg-story-panel :Panel.counter-with-stories/notes
    {:doc       "A small project-custom panel that renders prose
                alongside the active variant. Reads no app-db; pure
                static content."
     :title     "Notes"
     :placement :right
     :render    :counter-with-stories.views/parity-badge
     :for       #{:story.counter}})

  ;; -------------------------------------------------------------------------
  ;; reg-story — the parent story
  ;;
  ;; Inherits down to every variant: `:decorators`, `:args`, `:tags`.
  ;; The variant bodies below override / extend these.
  ;; -------------------------------------------------------------------------

  (story/reg-story :story.counter
    {:doc        "The counter — every variant of the canonical example."
     :component  :counter-with-stories.views/counter-card
     :decorators [[:counter-with-stories/log-decorator "story-level"]]
     :args       {:label "Count"}
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; reg-variant — four variants, each pulling on a different authoring shape
  ;; -------------------------------------------------------------------------

  ;; Variant 1 — empty / zero state. The simplest possible variant body:
  ;; one initialisation event, no decorators of its own (inherits the
  ;; story-level log-decorator). One play assertion: after init, the
  ;; `:count` slot equals zero.
  (story/reg-variant :story.counter/empty
    {:doc    "Fresh counter at zero. The simplest possible variant."
     :events [[:counter/initialise 0]]
     :play   [[:rf.assert/path-equals [:count] 0]]
     :tags   #{:dev :docs :test}
     :substrates #{:reagent}})

  ;; Variant 2 — non-zero state with three-level args. Demonstrates:
  ;; the variant-level :args override the story-level :args (precedence:
  ;; global < mode < story < variant). The label is overridden to
  ;; "Total" for this variant only.
  (story/reg-variant :story.counter/loaded
    {:doc    "A counter seeded with a non-zero value."
     :args   {:label "Total"}
     :events [[:counter/initialise 7]]
     :play   [[:rf.assert/path-equals [:count]              7]
              [:rf.assert/sub-equals  [:count-doubled]      14]
              [:rf.assert/sub-equals  [:count-parity]       :odd]]
     :tags   #{:dev :docs :test :counter-with-stories/canonical}
     :substrates #{:reagent}})

  ;; Variant 3 — interaction. The increments happen INSIDE the play
  ;; sequence (not the :events slot) so the `:rf.assert/dispatched?`
  ;; assertion's accumulator sees them — the trace listener is wired
  ;; for phase-4 play, not for phase-2 :events.
  (story/reg-variant :story.counter/clicked-three-times
    {:doc    "Counter after three increments from zero, driven from
             the play slot so :rf.assert/dispatched? observes them."
     :events [[:counter/initialise 0]]
     :play   [[:counter/inc]
              [:counter/inc]
              [:counter/inc]
              [:rf.assert/path-equals [:count] 3]
              [:rf.assert/dispatched? [:counter/inc]]]
     :tags   #{:dev :docs :test}
     :substrates #{:reagent}
     :decorators [[:counter-with-stories/log-decorator "variant-level"]]})

  ;; Variant 4 — `force-fx-stub` shape. The :counter/save event walks
  ;; a :fx slot that issues :counter/sync-to-server. The
  ;; `:rf.story/force-fx-stub` decorator intercepts the fx-id and
  ;; records the stub call without running the real fx. The :save
  ;; event is dispatched FROM :play (not :events) so the
  ;; trace-bus accumulator (installed at phase-4 start) sees the fx
  ;; and `:rf.assert/effect-emitted` passes.
  (story/reg-variant :story.counter/save-stubbed
    {:doc    "The save flow with the network fx stubbed. Demonstrates
             the MSW-shaped force-fx-stub decorator alongside the
             `:rf.assert/effect-emitted` assertion."
     :events [[:counter/initialise 5]]
     :decorators [[story/force-fx-stub-id :counter/sync-to-server {:ok? true}]]
     :play   [[:counter/save]
              [:rf.assert/path-equals     [:saving?] true]
              [:rf.assert/effect-emitted  :counter/sync-to-server]]
     :tags   #{:dev :test}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; reg-workspace — two workspaces, one per layout the v1 ships
  ;;
  ;; `:grid` — explicit variant ids, in the order they appear, in a grid.
  ;; `:variants-grid` — enumerates the parent story's variants automatically.
  ;; -------------------------------------------------------------------------

  (story/reg-workspace :Workspace.counter/all-states
    {:doc       "Every named counter state, side-by-side."
     :layout    :grid
     :variants  [:story.counter/empty
                 :story.counter/loaded
                 :story.counter/clicked-three-times
                 :story.counter/save-stubbed]
     :columns   2
     :tags      #{:docs}})

  (story/reg-workspace :Workspace.counter/auto-grid
    {:doc      "Auto-enumerated grid — pulls every variant off
               :story.counter. New variants appear here without
               touching this workspace."
     :layout   :variants-grid
     :for      :story.counter
     :columns  2
     :tags     #{:docs}}))

;; Fire the registrations once at namespace load.
(register-all!)
