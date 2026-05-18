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
                        project-custom panel in the right pane), and
                        `:Panel.counter-with-stories/broken-render`
                        (rf2-76wo5 testbed — :render points at an
                        unregistered view to exercise the panel-host's
                        broken-render fallback branch).
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

  ;; rf2-7ncf9 — faceted tag taxonomy (SB9 parity). Tags carrying an
  ;; `:axis` slot group into per-axis chip rows in the sidebar filter.
  ;; The filter applies AND across axes + OR within an axis. The
  ;; preferred shape is the namespaced keyword (`:status/stable`,
  ;; `:role/dev`, `:team/checkout`, `:feature/counter`) — the
  ;; namespace mirrors the axis, the name is the value.
  (story/reg-tag :status/alpha    {:axis :status :doc "Pre-release."})
  (story/reg-tag :status/stable   {:axis :status :doc "Production-ready."})
  (story/reg-tag :role/dev        {:axis :role   :doc "For devs."})
  (story/reg-tag :role/design     {:axis :role   :doc "For designers."})
  (story/reg-tag :team/counter    {:axis :team   :doc "Counter squad."})
  (story/reg-tag :feature/counter {:axis :feature
                                   :doc  "Counter feature surface."})

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

  ;; An `:axis :theme`-tagged mode exercises the chrome-level
  ;; toolbar's single-select-within-axis semantics (spec/010
  ;; §Selection semantics — by axis). Toggling :Mode.app/sepia
  ;; deactivates any other `:axis :theme` mode that was active.
  (story/reg-mode :Mode.app/sepia
    {:doc  "Sepia theme — exercises the toolbar's single-select-
           within-axis behaviour (`:axis :theme`)."
     :axis :theme
     :args {:theme      :sepia
            :background "#f4ecd8"
            :foreground "#5b4636"}})

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
               [:div {:style {:border  "1px dashed #9a9a9a"
                              :padding "0.5em"
                              :margin  "0.25em"}}
                [:div {:style {:font-size "10px" :color "#d0d0d0"}}
                 (str "decorator: " (or label "log"))]
                body]))})

  (story/reg-decorator :counter-with-stories/throwing-decorator
    {:doc  "Deterministic decorator failure used only by the
           occasional Story feature-load coverage gate. The canvas must
           project the error and keep rendering the underlying variant."
     :kind :hiccup
     :wrap (fn [_body _args]
             (throw (ex-info "story-load deterministic decorator failure"
                             {:surface :story-load
                              :kind    :decorator-exception})))})

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
  ;; rf2-76wo5 — broken-render testbed panel
  ;;
  ;; A panel pointing at an :render view id that is NEVER registered.
  ;; Exercises the panel-host's broken-render fallback branch in
  ;; tools/story/src/re_frame/story/ui/panels.cljs:330-333:
  ;;
  ;;   "panel <pid> has no registered :render view (<view-id>)"
  ;;
  ;; The :for filter scopes the panel to :story.counter so test runs
  ;; against /loaded surface the fallback without leaking it into
  ;; every variant. Pure testbed — no source-side fix; the broken-
  ;; render path is documented dev-time UX, not a defect.
  ;; -------------------------------------------------------------------------

  (story/reg-story-panel :Panel.counter-with-stories/broken-render
    {:doc       "Testbed panel for rf2-76wo5 — :render points at an
                unregistered view so the panel-host renders its
                'no registered :render view' fallback. Asserted by
                story_browser_scenarios.cjs."
     :title     "Broken render (testbed)"
     :placement :right
     :render    :counter-with-stories.views/not-registered
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

  (story/reg-story :story.counter-diagnostics
    {:doc        "Small deterministic failure surfaces for Story's
                 diagnostics and test-mode UI. Kept separate from
                 :story.counter so the canonical four counter variants
                 stay stable."
     :component  :counter-with-stories.views/counter-card
     :args       {:label "Diagnostics"}
     :tags       #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-story :story.counter-play-script
    {:doc        "Parent story for the rich-DSL :play-script CI-as-test
                 fixtures (rf2-3qcxk). Two variants exercise both the
                 pass and fail terminal paths of the play runner so the
                 CI runner has live targets in every browser-gate run."
     :component  :counter-with-stories.views/counter-card
     :args       {:label "Play-script CI"}
     :tags       #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-story :story.counter-matrix
    {:doc        "Deterministic browser-only affordances for the
                 Story feature coverage matrix. These variants keep
                 the canonical four counter variants stable while
                 exposing empty/error/schema/layout/isolation surfaces
                 for the occasional feature-load gate."
     :component  :counter-with-stories.views/counter-card
     :args       {:label "Matrix"}
     :tags       #{:dev :test :internal}
     :substrates #{:reagent}
     :schema     [:map
                  [:label :string]
                  [:settings
                   [:map
                    [:title :string]
                    [:enabled? :boolean]]]]})

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
     ;; rf2-7ncf9 — faceted tags alongside the existing canonical seven.
     ;; The sidebar groups these into per-axis chip rows.
     :tags   #{:dev :docs :test :counter-with-stories/canonical
               :status/stable :role/dev :team/counter :feature/counter}
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

  ;; Diagnostic variant 1 — failing assertion without an exception.
  ;; Test mode must show the failure as data, not as an uncaught browser
  ;; error. This gives the feature-load gate a stable red test-mode
  ;; surface that does not depend on timing or external services.
  (story/reg-variant :story.counter-diagnostics/failing-play
    {:doc    "Deterministic failing play assertion. The counter is
             initialised to 1 but the play assertion expects 999."
     :events [[:counter/initialise 1]]
     :play   [[:rf.assert/path-equals [:count] 999]]
     :tags   #{:dev :test :internal}
     :substrates #{:reagent}})

  ;; Diagnostic variant 2 — phase-4 handler exception. The Story
  ;; play-runner projects handler exceptions into the assertion list so
  ;; the test pane can explain the failure without blanking the shell.
  (story/reg-variant :story.counter-diagnostics/event-throws
    {:doc    "Deterministic event-handler exception during :play."
     :events [[:counter/initialise 0]]
     :play   [[:counter/throw-deterministic]]
     :tags   #{:dev :test :internal}
     :substrates #{:reagent}})

  ;; Diagnostic variant 3 — loader-phase exception. This exercises the
  ;; phase-1 error capture path separately from ordinary play failures.
  (story/reg-variant :story.counter-diagnostics/loader-throws
    {:doc     "Deterministic loader exception before events/render."
     :loaders [[:counter/throw-deterministic]]
     :events  [[:counter/initialise 0]]
     :play    [[:rf.assert/path-equals [:count] 0]]
     :tags    #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-diagnostics/render-throws
    {:doc       "Deterministic render exception. The canvas error
                boundary should project variant id, render phase, view
                id, and stack detail while keeping the Story shell
                interactive."
     :component :counter-with-stories.views/throwing-card
     :events    [[:counter/initialise 0]]
     :play      [[:rf.assert/path-equals [:count] 0]]
     :tags      #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/no-play
    {:doc    "Healthy variant with no :play sequence. Test mode should
             render its explicit empty state instead of pretending the
             variant passed."
     :args   {:label "No play"
              :settings {:title "No play" :enabled? true}}
     :events [[:counter/initialise 2]]
     :tags   #{:dev :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/loader-success
    {:doc     "Loader success path. The loader seeds :count before the
              normal event phase and the play assertion observes the
              loaded value."
     :args    {:label "Loaded by loader"
               :settings {:title "Loader" :enabled? true}}
     :loaders [[:counter/set 12]]
     :play    [[:rf.assert/path-equals [:count] 12]]
     :tags    #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/loader-never-completes
    {:doc     "Loader completion failure path. The loader runs, but the
              predicate intentionally never reports ready, so Story
              records a deterministic loader-incomplete assertion
              instead of making the browser wait for a timeout."
     :args    {:label "Loader never complete"
               :settings {:title "Never" :enabled? true}}
     :loaders [[:counter/set 13]]
     :loaders-complete-when :counter/loader-never-ready?
     :play    [[:rf.assert/path-equals [:count] 13]]
     :tags    #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/loader-rejects
    {:doc     "Loader rejection path. The loader throws a deterministic
              ExceptionInfo value whose data must be visible in test
              diagnostics."
     :args    {:label "Loader rejects"
               :settings {:title "Rejects" :enabled? true}}
     :loaders [[:counter/throw-loader-rejection]]
     :events  [[:counter/initialise 0]]
     :play    [[:rf.assert/path-equals [:count] 0]]
     :tags    #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/schema-invalid
    {:doc    "Deliberately invalid args against the parent story
             schema. The schema-validation panel should show the exact
             failing key while the shell stays interactive."
     :args   {:label 42
              :settings {:title "Bad label" :enabled? true}}
     :events [[:counter/initialise 4]]
     :play   [[:rf.assert/path-equals [:count] 4]]
     :tags   #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/nested-controls
    {:doc    "Nested args/schema fixture for the controls panel. The
             counter card ignores :settings; the right-pane controls
             still expose path-aware nested widgets."
     :args   {:label "Nested"
              :settings {:title "Nested title" :enabled? true}}
     :events [[:counter/initialise 6]]
     :play   [[:rf.assert/path-equals [:count] 6]]
     :tags   #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/decorator-throws
    {:doc        "Decorator failure projection fixture."
     :args       {:label "Decorator failure"
                  :settings {:title "Decorator" :enabled? true}}
     :events     [[:counter/initialise 8]]
     :decorators [[:counter-with-stories/throwing-decorator]]
     :play       [[:rf.assert/path-equals [:count] 8]]
     :tags       #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/multi-substrate
    {:doc        "Side-by-side substrate fixture. Reagent should render;
                 the synthetic substrate should project an unsupported
                 state rather than leak frames or crash."
     :args       {:label "Substrates"
                  :settings {:title "Substrates" :enabled? true}}
     :events     [[:counter/initialise 10]]
     :play       [[:rf.assert/path-equals [:count] 10]]
     :tags       #{:dev :test :internal}
     :substrates #{:reagent :uix}})

  (story/reg-variant :story.counter-matrix/isolation-a
    {:doc    "Frame-isolation fixture A. Same handlers as fixture B,
             different seed."
     :args   {:label "Isolation A"
              :settings {:title "A" :enabled? true}}
     :events [[:counter/initialise 1]]
     :play   [[:rf.assert/path-equals [:count] 1]]
     :tags   #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/isolation-b
    {:doc    "Frame-isolation fixture B. Same handlers as fixture A,
             different seed."
     :args   {:label "Isolation B"
              :settings {:title "B" :enabled? true}}
     :events [[:counter/initialise 100]]
     :play   [[:rf.assert/path-equals [:count] 100]]
     :tags   #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/recorder-redaction
    {:doc       "Recorder browser fixture: the visible button dispatches
                a :sensitive? event with a password payload. The Story
                recorder must preserve the row position while emitting
                [:rf/redacted] in the generated :play snippet."
     :component :counter-with-stories.views/recorder-redaction-card
     :args      {:label "Recorder redaction"
                 :settings {:title "Recorder" :enabled? true}}
     :events    [[:counter/initialise 11]]
     :play      [[:rf.assert/path-equals [:count] 11]]
     :tags      #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/a11y-known-good
    {:doc       "Deterministic a11y known-good browser fixture. The
                browser scenario injects a stable axe-compatible
                scanner and asserts this fixture reports zero rows."
     :component :counter-with-stories.views/a11y-known-good-card
     :args      {:label "A11y known good"
                 :settings {:title "A11y good" :enabled? true}}
     :events    [[:counter/initialise 21]]
     :play      [[:rf.assert/path-equals [:count] 21]]
     :tags      #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/a11y-known-bad
    {:doc       "Deterministic a11y known-bad browser fixture. The
                violation is tied to a fixture-owned image selector so
                output stays stable and never depends on Story chrome."
     :component :counter-with-stories.views/a11y-known-bad-card
     :args      {:label "A11y known bad"
                 :settings {:title "A11y bad" :enabled? true}}
     :events    [[:counter/initialise 22]]
     :play      [[:rf.assert/path-equals [:count] 22]]
     :tags      #{:dev :test :internal}
     :substrates #{:reagent}})

  ;; rf2-0uo4e — fx-stub-miss testbed variant. Source-side follow-on
  ;; from rf2-6hauy. :play declares :rf.assert/effect-emitted against
  ;; :never-stubbed WITHOUT a corresponding force-fx-stub decorator
  ;; covering the id — the play-runner never observes the fx, so the
  ;; assertion fails with the canonical reason:
  ;;
  ;;   "fx :never-stubbed was not emitted during play"
  ;;
  ;; Lets the test pane's failing-row reason-text surface be asserted
  ;; directly without authoring a one-off probe. The variant is
  ;; intentionally :test-tagged so the chrome test-widget picks it up
  ;; and reports the failure.
  (story/reg-variant :story.counter-matrix/fx-stub-miss
    {:doc       "Deterministic fx-stub-miss failing assertion. :play
                asserts :rf.assert/effect-emitted :never-stubbed with
                NO force-fx-stub decorator covering it — the assertion
                fails with the canonical 'fx <id> was not emitted
                during play' reason. Pattern: :story.counter-
                diagnostics/failing-play."
     :args      {:label "fx-stub-miss"
                 :settings {:title "fx-stub-miss" :enabled? true}}
     :events    [[:counter/initialise 0]]
     :play      [[:rf.assert/effect-emitted :never-stubbed]]
     :tags      #{:dev :test :internal}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; :play-script fixtures (rf2-3qcxk — CI-as-test)
  ;;
  ;; The CI runner at `examples/scripts/serve-and-run-story-play-scripts.cjs`
  ;; discovers every registered variant whose body carries a non-empty
  ;; `:play-script` slot (via `re-frame.story.play.ci-runner/
  ;; variants-with-play-scripts`), navigates the live Story shell to
  ;; each, waits for the auto-run's terminal status, and asserts the
  ;; aggregate result. These two fixtures pin the contract: one passes,
  ;; one is deliberately wrong so the CI runner's failure path stays
  ;; under continuous coverage too.
  ;;
  ;; The fixtures use `:dispatch-sync` (not `:dispatch`) so the runner
  ;; observes the resulting app-db state on the very next step without
  ;; needing a `:wait`. The play-script slot coexists with the legacy
  ;; `:play` slot — both run, independently, post-mount.
  ;; -------------------------------------------------------------------------

  ;; Both fixtures use IDEMPOTENT scripts (initialise → assert) so
  ;; the shell's deep-link auto-run AND its watcher-edge auto-run
  ;; both reach the same terminal state. The cumulative-dispatch
  ;; pattern (e.g. three `:dispatch-sync [:counter/inc]` then
  ;; `[:assert-db [:count] 3]`) drifts under double-fire — the CI
  ;; runner is gating the auto-run plumbing, not the under-test app's
  ;; idempotency, so we keep the scripts neutral on that axis.
  (story/reg-variant :story.counter-play-script/passing
    {:doc        "rf2-3qcxk CI fixture — initialise the counter to 3
                 and assert :count equals 3. Idempotent under any
                 number of auto-run repeats."
     :args       {:label "Play-script pass"}
     :events     []
     :play-script
     {:name      "initialise-three-and-assert-pass"
      :auto-run? true
      :script    [[:dispatch-sync [:counter/initialise 3]]
                  [:assert-db [:count] 3]]}
     :tags       #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-play-script/failing
    {:doc        "rf2-3qcxk CI fixture — initialise the counter to 1
                 then assert the WRONG final count (expect 9). The CI
                 runner observes the :fail terminal status and matches
                 it against the variant id's `failing` marker, so the
                 process-level result stays clean even though the
                 variant deliberately fails."
     :args       {:label "Play-script fail"}
     :events     []
     :play-script
     {:name      "initialise-one-but-expect-nine"
      :auto-run? true
      :script    [[:dispatch-sync [:counter/initialise 1]]
                  [:assert-db [:count] 9]]}
     :tags       #{:dev :test :internal}
     :substrates #{:reagent}})

  ;; rf2-e0kof DOM-step fixtures — live-browser coverage of the rich-DSL
  ;; `:click` / `:type` / `:assert-dom` steps. The pure-step + JVM
  ;; coverage in tools/story/test/re_frame/story/play/ exercises the
  ;; parser + the JVM no-DOM branches; this fixture closes the gap by
  ;; driving the actual DOM via the Story shell in the Playwright runner
  ;; at examples/scripts/serve-and-run-story-play-scripts.cjs.
  ;;
  ;; The :counter-with-stories.views/counter-with-input view exposes
  ;;   - [data-test=count-display] — read by `:assert-dom :text`
  ;;   - [data-test=count-input]   — written by `:type`
  ;;   - [data-test=set-button]    — clicked by `:click` (dispatches
  ;;                                 :counter/set with the input value)
  ;; The script: seed db → type "42" → click set → wait for re-render →
  ;; assert app-db AND the rendered text both reflect 42. End-to-end
  ;; coverage of every DOM-step type in one play.
  (story/reg-variant :story.counter-play-script/dom
    {:doc        "rf2-e0kof DOM-step fixture — exercises every
                 rich-DSL DOM step (`:click` / `:type` / `:assert-dom`)
                 end-to-end against a real browser DOM. Pairs with the
                 expected-fail twin to keep the failure path under CI
                 coverage too."
     :component  :counter-with-stories.views/counter-with-input
     :args       {:label "Play-script DOM"}
     :events     []
     :play-script
     {:name      "type-click-and-assert-dom"
      :auto-run? true
      ;; A leading `:wait 300` gives React's first commit a chance to
      ;; render the component before the first DOM assertion. The
      ;; auto-run fires as soon as the lifecycle machine reaches
      ;; `:ready`, which can race ahead of React's render flush.
      :script    [[:dispatch-sync [:counter/initialise 0]]
                  [:wait        300]
                  [:assert-dom  "[data-test=count-display]" :text "0"]
                  [:type        "[data-test=count-input]" "42"]
                  [:click       "[data-test=set-button]"]
                  ;; The click dispatches :counter/set synchronously
                  ;; on Reagent; the wait ensures the next render cycle
                  ;; has flushed before we read the DOM.
                  [:wait        300]
                  [:assert-db   [:count] 42]
                  [:assert-dom  "[data-test=count-display]" :text "42"]
                  [:assert-dom  "[data-test=set-button]"    :visible]]}
     :tags       #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-play-script/dom-expected-fail
    {:doc        "rf2-e0kof expected-fail twin — same DOM-step shape as
                 :story.counter-play-script/dom but the FINAL
                 `:assert-dom :text` expects '99' against an actual
                 '42'. The CI runner reads `expected-fail` off the
                 variant id and asserts the play reaches `:fail`."
     :component  :counter-with-stories.views/counter-with-input
     :args       {:label "Play-script DOM (expected-fail)"}
     :events     []
     :play-script
     {:name      "type-click-and-assert-dom-wrong"
      :auto-run? true
      :script    [[:dispatch-sync [:counter/initialise 0]]
                  [:wait          50]
                  [:type          "[data-test=count-input]" "42"]
                  [:click         "[data-test=set-button]"]
                  [:wait          100]
                  [:assert-dom    "[data-test=count-display]" :text "99"]]}
     :tags       #{:dev :test :internal}
     :substrates #{:reagent}})

  ;; rf2-tl7zk multi-play fixture — three named plays on one variant.
  ;; The first play (happy-path) auto-runs on mount per the per-position
  ;; default; the other two run on demand (manual trigger via the
  ;; toolbar dropdown OR the CI runner's `runPlay` hook). One play
  ;; deliberately fails to keep the failure path under CI coverage.
  (story/reg-variant :story.counter-play-script/multi
    {:doc        "rf2-tl7zk CI fixture — multi-play variant with three
                 named plays. The CI runner enumerates each play as its
                 own row (per-play pass/fail) and matches the per-play
                 expected status using `failing` / `expected-fail` name
                 markers."
     :args       {:label "Multi-play"}
     :events     []
     :plays      [{:name      "happy-path"
                   :script    [[:dispatch-sync [:counter/initialise 5]]
                               [:assert-db [:count] 5]]}
                  {:name      "edge-case-zero"
                   :script    [[:dispatch-sync [:counter/initialise 0]]
                               [:assert-db [:count] 0]]}
                  {:name      "deliberately-failing"
                   :script    [[:dispatch-sync [:counter/initialise 2]]
                               [:assert-db [:count] 99]]}]
     :tags       #{:dev :test :internal}
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
     :tags     #{:docs}})

  (story/reg-workspace :Workspace.counter/prose
    {:doc     "Prose layout fixture for docs/workspace coverage."
     :layout  :prose
     :content [{:type :prose
                :body "Story matrix prose block before the example."}
               {:type :variant
                :id   :story.counter/loaded}
               {:type :prose
                :body "Story matrix prose block after the example."}]
     :tags    #{:docs}})

  (story/reg-workspace :Workspace.counter/tabs
    {:doc      "Tabs layout fixture for workspace coverage."
     :layout   :tabs
     :variants [:story.counter/empty
                :story.counter/loaded]
     :tags     #{:docs}})

  (story/reg-workspace :Workspace.counter/custom
    {:doc    "Custom layout fixture for workspace coverage. The current
             renderer projects the configured view id as data."
     :layout :custom
     :render :counter-with-stories.views/counter-card
     :tags   #{:docs}}))

;; Fire the registrations once at namespace load.
(register-all!)
