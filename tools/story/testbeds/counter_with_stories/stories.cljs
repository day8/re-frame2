(ns counter-with-stories.stories
  "Stories for the counter app. Demonstrates seven of the nine `reg-*`
  macros end-to-end on a deliberately small domain so the patterns are
  visible rather than buried in business logic. (The `reg-fragment` /
  `reg-check` composition cohort — spec/017 §Strict composition — is
  not exercised here.)

  Per /spec/007-Stories.md §Variants the body of every variant is plain data —
  no fn-slots. The view at the centre of each variant is referenced
  by id (`:counter-with-stories.views/counter-card`); the events
  the variant dispatches reference event-ids; decorators reference
  decorator-ids. Closures live exactly one place: the decorator's
  *registration site* (see `:counter-with-stories/log-decorator`
  below).

  Seven of the nine `reg-*` macros each appear at least once:

  - `reg-tag`         — `:counter-with-stories/canonical` (project tag).
  - `reg-mode`        — `:Mode.app/dark` + `:Mode.app/light` (theme tuples).
  - `reg-decorator`   — `:counter-with-stories/log-decorator` (custom
                        hiccup decorator alongside Story's canonical
                        `:rf.story/layout-debug.*` set).
  - `reg-story-panel` — `:Panel.counter-with-stories/notes` (a small
                        project-custom panel in the right pane), and
                        `:Panel.counter-with-stories/broken-render`
                        (a testbed panel whose :render points at an
                        unregistered view to exercise the panel-host's
                        broken-render fallback branch).
  - `reg-story`       — four parent stories. The exemplary
                        `:story.counter` parent (whose five variants
                        below all inherit its decorators + args) plus
                        three sibling fixture parents
                        (`:story.counter-diagnostics`,
                        `:story.counter-matrix`,
                        `:story.counter-play-script`).
  - `reg-variant`     — an exemplary core of five `:story.counter`
                        variants exercising every authoring shape (four
                        canonical + the events-only loader-body shape),
                        PLUS the deliberate
                        diagnostics / matrix / CI-runner fixtures under
                        the sibling parents below.
  - `reg-workspace`   — five workspaces (`:grid`, `:variants-grid`,
                        `:prose`, `:tabs`, `:custom`).

  This file is therefore two layers stacked in one namespace. The
  EXEMPLARY block — the `:story.counter` parent + its five teaching
  variants + the all-states / auto-grid workspaces — is the small,
  copyable reference a consumer reads to learn the authoring shapes.
  The GATE-FIXTURE block below it (the `:story.counter-diagnostics`,
  `:story.counter-matrix`, and `:story.counter-play-script` parents
  and roughly thirty variants, the throwing decorators, the custom
  panels, the schema/a11y/recorder/isolation surfaces, and the
  CI-runner play targets) is browser-gate + test-mode diagnostics
  machinery, NOT teaching variants. Each block carries a banner
  comment so the reference surface stays distinguishable from the
  gate machinery. (A future split into a sibling fixtures file is
  tracked separately and deliberately out of scope here.)

  Every variant declares `:substrates #{:reagent}` per `001-Authoring.md`
  §Substrates (Reagent is the v1 lock; UIx / Helix variants ship post-v1).

  The exemplary `:script` bodies exercise the documented authoring
  surface end-to-end: the `:assert-db` checkpoint sugar plus the
  explicit `[:assert [:rf.assert/…]]` form for the assertions the
  sugar does not cover — `sub-equals`, `dispatched?`, `effect-emitted`
  — alongside one `force-fx-stub` decorator reference."
  (:require [re-frame.core  :as rf]
            [re-frame.story :as story]
            ;; Source the event and view ids by requiring the namespaces
            ;; so they register themselves; the variant bodies reference
            ;; the ids as plain keywords (no fn-slots leak through).
            [counter-with-stories.events]
            [counter-with-stories.subs]
            [counter-with-stories.views]))

;; ---------------------------------------------------------------------------
;; App image (EP-0026 §Default Image)
;;
;; This testbed is co-loaded with other apps in the `node-test` build, so the
;; variant frames must scope their registration resolution to THIS app's
;; namespace rather than the whole co-loaded store (whose cross-app same-`[kind
;; id]` registrations — e.g. `[:route :rf.route/not-found]` — would collide in
;; the EP-0026 default-image projection). Each parent story below declares this
;; app image on its `:images`; every variant inherits it, and the runtime
;; composes the canonical Story runtime image on top
;; (`re-frame.story.frames/allocate!`), so each variant frame sees exactly this
;; app + the Story machinery. `:select-ns` SELECTS registered descriptors by
;; `:rf.provenance/ns`; it does not LOAD (no require, no DCE defeat).
;; ---------------------------------------------------------------------------

(def app-image
  (rf/image
    {:id        :counter-with-stories/app
     :select-ns {:include ["counter-with-stories.**"]}}))

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
;; :experimental :internal :agent) auto-install on the first `reg-*`
;; call below — no explicit boot step needed. `reg-tag` /
;; `reg-mode` / `reg-decorator` etc. all
;; trigger the same idempotent installer chain via the registrar's
;; `maybe-auto-install!` hook.
;; ---------------------------------------------------------------------------

(defn register-all!
  "Register the counter-with-stories example's Story artefacts.
  Idempotent. The trailing top-level call fires this at namespace
  load; the test fixture calls it again after a clear-all! per test.
  The canonical vocabulary auto-installs on the first `reg-*` call —
  no explicit boot step required."
  []
  ;; -------------------------------------------------------------------------
  ;; reg-tag — register the project's custom tag
  ;;
  ;; Per /spec/007-Stories.md §Inclusion tags the seven canonical tags (:dev :docs
  ;; :test :screenshot :experimental :internal :agent) register at
  ;; Story load; project-specific tags must register before use or the
  ;; registrar throws `:rf.error/unknown-tag`.
  ;; -------------------------------------------------------------------------

  (story/reg-tag :counter-with-stories/canonical
    {:doc "Tag applied to the variant that ships as the example's
          canonical screenshot — the one the README points at."})

  ;; Faceted tag taxonomy (SB9 parity). Tags carrying an
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
  ;; Per `005-SOTA-Features.md` §`reg-mode` saved-tuple primitive modes are saved tuples of args. When a variant
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
  ;; Per `001-Authoring.md` §Registration macros + `002-Runtime.md` §Open items (Stage 3 picks) decorators are the ONLY Story
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

           Per `002-Runtime.md` §Decorator composition order (`apply-hiccup-decorators`): the `:wrap`
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
  ;; Per /spec/007-Stories.md §Story-tool extension hook + `001-Authoring.md` §Registration macros, panels
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
  ;; Broken-render testbed panel
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
     :images     [app-image]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  (story/reg-story :story.counter-diagnostics
    {:doc        "Small deterministic failure surfaces for Story's
                 diagnostics and test-mode UI. Kept separate from
                 :story.counter so the canonical four counter variants
                 stay stable."
     :component  :counter-with-stories.views/counter-card
     :args       {:label "Diagnostics"}
     :images     [app-image]
     :tags       #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-story :story.counter-play-script
    {:doc        "Parent story for the rich-DSL :script CI-as-test
                 fixtures (rf2-3qcxk). Two variants exercise both the
                 pass and fail terminal paths of the play runner so the
                 CI runner has live targets in every browser-gate run."
     :component  :counter-with-stories.views/counter-card
     :args       {:label "Play-script CI"}
     :images     [app-image]
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
     :images     [app-image]
     :tags       #{:dev :test :internal}
     :substrates #{:reagent}})

  ;; ===========================================================================
  ;; EXEMPLARY BLOCK — the small, copyable teaching surface.
  ;;
  ;; reg-variant — the five `:story.counter` variants. Four exercise
  ;; distinct authoring shapes; the fifth is the events-only loader-body
  ;; shape. This block plus the all-states / auto-grid
  ;; workspaces is what a consumer reads to learn the authoring surface.
  ;; Everything below the GATE-FIXTURE banner is diagnostics machinery.
  ;; ===========================================================================

  ;; Variant 1 — empty / zero state. The simplest possible variant body:
  ;; one initialisation event in :setup, no decorators of its own
  ;; (inherits the story-level log-decorator). One :script checkpoint:
  ;; after init, the `:count` slot equals zero.
  (story/reg-variant :story.counter/empty
    {:doc    "Fresh counter at zero. The simplest possible variant."
     :setup [[:counter/initialise 0]]
     :script [[:assert-db [:count] 0]]
     :tags   #{:dev :docs :test}
     :substrates #{:reagent}})

  ;; Variant 2 — non-zero state with three-level args. Demonstrates:
  ;; the variant-level :args override the story-level :args (precedence:
  ;; global < mode < story < variant). The label is overridden to
  ;; "Total" for this variant only.
  (story/reg-variant :story.counter/loaded
    {:doc    "A counter seeded with a non-zero value."
     :args   {:label "Total"}
     :setup [[:counter/initialise 7]]
     :script [[:assert-db [:count] 7]
              [:assert [:rf.assert/sub-equals [:count-doubled] 14]]
              [:assert [:rf.assert/sub-equals [:count-parity]  :odd]]]
     ;; Faceted tags alongside the existing canonical seven.
     ;; The sidebar groups these into per-axis chip rows.
     :tags   #{:dev :docs :test :counter-with-stories/canonical
               :status/stable :role/dev :team/counter :feature/counter}
     :substrates #{:reagent}})

  ;; Variant 3 — interaction. The increments happen INSIDE the :script
  ;; (not the :setup slot) so the `:rf.assert/dispatched?`
  ;; assertion's accumulator sees them — the trace listener is wired
  ;; for the phase-4 :script, not for the phase-2 :setup.
  (story/reg-variant :story.counter/clicked-three-times
    {:doc    "Counter after three increments from zero, driven from
             the :script so :rf.assert/dispatched? observes them."
     :setup [[:counter/initialise 0]]
     ;; The play-runner's :rf.assert/* bridge surfaces assertion
     ;; failures up through the runner. The
     ;; path-equals [:count] 3 check passes under plain-atom (CLJS unit
     ;; test) but Playwright shows a different count (Reagent + StrictMode
     ;; — see reg_variant_e2e_cljs_test.cljs:18). The canonical count-3
     ;; contract is pinned by the CLJS unit test; the dispatched? assertion
     ;; remains here because it observes the trace bus, not the rendered
     ;; count, so substrate quirks do not affect it.
     :script [[:dispatch-sync [:counter/inc]]
              [:dispatch-sync [:counter/inc]]
              [:dispatch-sync [:counter/inc]]
              [:assert [:rf.assert/dispatched? [:counter/inc]]]]
     :tags   #{:dev :docs :test}
     :substrates #{:reagent}
     :decorators [[:counter-with-stories/log-decorator "variant-level"]]})

  ;; Variant 3b — canonical events-only loader-body shape. A variant is
  ;; *events-only* when its body declares no `:loaders` AND no
  ;; `:loaders-complete-when` AND its resolved decorator stack carries no
  ;; `:frame-setup` decorators. Such variants take the lifecycle fast-path
  ;; `:pre-mount → :ready` on mount (skipping `:mounting`/`:loading`) so
  ;; the canvas's loading skeleton never engages for variants that have
  ;; nothing to wait for.
  ;;
  ;; Pinned by `tools/story/test/re_frame/story_runtime_cljs_test.cljs`
  ;; (`cljs-events-only-fast-path-to-ready` + `cljs-events-only-
  ;; classifier`) as the canonical events-only body. NO :script slot,
  ;; NO :loaders slot, NO :frame-setup decorator — those would break
  ;; the events-only classification and the fast-path it gates. (The
  ;; runtime classifier is named "events-only" after the lowered
  ;; `:events` slot; the authoring surface is `:setup`.)
  (story/reg-variant :story.counter/events-only-loaded
    {:doc    "Canonical events-only loader-body shape (rf2-043cm fast-
             path repro). Counter seeded at 5 via `:setup`; no
             `:loaders`, no `:loaders-complete-when`, no `:frame-setup`
             decorators — the lifecycle takes the fast-path direct to
             `:ready`. Folded in from the retired xray_rhs_smoke
             testbed per rf2-9jfo1.2."
     :setup [[:counter/initialise 5]]
     :tags   #{:dev :test :internal}
     :substrates #{:reagent}})

  ;; Variant 4 — `force-fx-stub` shape. The :counter/save event walks
  ;; a :fx slot that issues :counter/sync-to-server. The
  ;; `:rf.story/force-fx-stub` decorator intercepts the fx-id and
  ;; records the stub call without running the real fx. The :save
  ;; event is dispatched FROM the :script (not :setup) so the
  ;; trace-bus accumulator (installed at phase-4 start) sees the fx
  ;; and `:rf.assert/effect-emitted` passes.
  (story/reg-variant :story.counter/save-stubbed
    {:doc    "The save flow with the network fx stubbed. Demonstrates
             the MSW-shaped force-fx-stub decorator alongside the
             `:rf.assert/effect-emitted` assertion."
     :setup [[:counter/initialise 5]]
     :decorators [[story/force-fx-stub-id :counter/sync-to-server {:ok? true}]]
     :script [[:dispatch-sync [:counter/save]]
              [:assert-db [:saving?] true]
              [:assert [:rf.assert/effect-emitted :counter/sync-to-server]]]
     :tags   #{:dev :test}
     :substrates #{:reagent}})

  ;; ===========================================================================
  ;; GATE-FIXTURE BLOCK — browser-gate + test-mode diagnostics machinery.
  ;;
  ;; Everything below this banner lives under the sibling parent stories
  ;; (`:story.counter-diagnostics`, `:story.counter-matrix`,
  ;; `:story.counter-play-script`). These are DELIBERATE failure /
  ;; matrix / CI fixtures — they keep Story's diagnostics, feature-load
  ;; gate, schema/a11y/recorder/isolation surfaces, and the CI-as-test
  ;; runner under continuous coverage. They are NOT teaching variants;
  ;; a consumer learning the authoring surface reads the EXEMPLARY block
  ;; above, not this one.
  ;; ===========================================================================

  ;; Diagnostic variant 1 — failing assertion without an exception.
  ;; Test mode must show the failure as data, not as an uncaught browser
  ;; error. This gives the feature-load gate a stable red test-mode
  ;; surface that does not depend on timing or external services.
  (story/reg-variant :story.counter-diagnostics/failing-play
    {:doc    "Deterministic failing :script assertion. The counter is
             initialised to 1 but the :script assertion expects 999."
     :setup [[:counter/initialise 1]]
     :script [[:assert-db [:count] 999]]
     :tags   #{:dev :test :internal}
     :substrates #{:reagent}})

  ;; Diagnostic variant 2 — phase-4 handler exception. The Story
  ;; play-runner projects handler exceptions into the assertion list so
  ;; the test pane can explain the failure without blanking the shell.
  (story/reg-variant :story.counter-diagnostics/failing-event-throws
    {:doc    "Deterministic event-handler exception during the :script."
     :setup [[:counter/initialise 0]]
     :script [[:dispatch-sync [:counter/throw-deterministic]]]
     :tags   #{:dev :test :internal}
     :substrates #{:reagent}})

  ;; Diagnostic variant 3 — loader-phase exception. This exercises the
  ;; phase-1 error capture path separately from ordinary play failures.
  (story/reg-variant :story.counter-diagnostics/loader-throws
    {:doc     "Deterministic loader exception before :setup/render."
     :loaders [[:counter/throw-deterministic]]
     :setup  [[:counter/initialise 0]]
     :script [[:assert-db [:count] 0]]
     :tags    #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-diagnostics/render-throws
    {:doc       "Deterministic render exception. The canvas error
                boundary should project variant id, render phase, view
                id, and stack detail while keeping the Story shell
                interactive."
     :component :counter-with-stories.views/throwing-card
     :setup    [[:counter/initialise 0]]
     :script [[:assert-db [:count] 0]]
     :tags      #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/no-play
    {:doc    "Healthy variant with no :script. Test mode should
             render its explicit empty state instead of pretending the
             variant passed."
     :args   {:label "No play"
              :settings {:title "No play" :enabled? true}}
     :setup [[:counter/initialise 2]]
     :tags   #{:dev :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/loader-success
    {:doc     "Loader success path. The loader seeds :count before the
              normal event phase and the :script assertion observes the
              loaded value."
     :args    {:label "Loaded by loader"
               :settings {:title "Loader" :enabled? true}}
     :loaders [[:counter/set 12]]
     :script [[:assert-db [:count] 12]]
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
     :script [[:assert-db [:count] 13]]
     :tags    #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/loader-rejects
    {:doc     "Loader rejection path. The loader throws a deterministic
              ExceptionInfo value whose data must be visible in test
              diagnostics."
     :args    {:label "Loader rejects"
               :settings {:title "Rejects" :enabled? true}}
     :loaders [[:counter/throw-loader-rejection]]
     :setup  [[:counter/initialise 0]]
     :script [[:assert-db [:count] 0]]
     :tags    #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/schema-invalid
    {:doc    "Args that mismatch the component's expected prop shape
             (a numeric :label, an unused :settings map). The
             schema-validation panel surfaces a violation only when the
             registered :component view carries a props schema (per
             rf2-hwcdh2 the props schema lives on the view's reg-view
             metadata, not on the story / variant body); counter-card
             ships none, so this variant stays interactive and the
             panel reports 'no schema registered'."
     :args   {:label 42
              :settings {:title "Bad label" :enabled? true}}
     :setup [[:counter/initialise 4]]
     :script [[:assert-db [:count] 4]]
     :tags   #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/nested-controls
    {:doc    "Nested args/schema fixture for the controls panel. The
             counter card ignores :settings; the right-pane controls
             still expose path-aware nested widgets."
     :args   {:label "Nested"
              :settings {:title "Nested title" :enabled? true}}
     :setup [[:counter/initialise 6]]
     :script [[:assert-db [:count] 6]]
     :tags   #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/decorator-throws
    {:doc        "Decorator failure projection fixture."
     :args       {:label "Decorator failure"
                  :settings {:title "Decorator" :enabled? true}}
     :setup     [[:counter/initialise 8]]
     :decorators [[:counter-with-stories/throwing-decorator]]
     :script [[:assert-db [:count] 8]]
     :tags       #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/multi-substrate
    {:doc        "Side-by-side substrate fixture. Reagent should render;
                 the synthetic substrate should project an unsupported
                 state rather than leak frames or crash."
     :args       {:label "Substrates"
                  :settings {:title "Substrates" :enabled? true}}
     :setup     [[:counter/initialise 10]]
     :script [[:assert-db [:count] 10]]
     :tags       #{:dev :test :internal}
     :substrates #{:reagent :uix}})

  (story/reg-variant :story.counter-matrix/isolation-a
    {:doc    "Frame-isolation fixture A. Same handlers as fixture B,
             different seed."
     :args   {:label "Isolation A"
              :settings {:title "A" :enabled? true}}
     :setup [[:counter/initialise 1]]
     :script [[:assert-db [:count] 1]]
     :tags   #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/isolation-b
    {:doc    "Frame-isolation fixture B. Same handlers as fixture A,
             different seed."
     :args   {:label "Isolation B"
              :settings {:title "B" :enabled? true}}
     :setup [[:counter/initialise 100]]
     :script [[:assert-db [:count] 100]]
     :tags   #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/recorder-redaction
    {:doc       "Recorder browser fixture: the visible button dispatches
                a :sensitive? event with a password payload. The Story
                recorder must preserve the row position while emitting
                [:rf/redacted] in the generated :script snippet."
     :component :counter-with-stories.views/recorder-redaction-card
     :args      {:label "Recorder redaction"
                 :settings {:title "Recorder" :enabled? true}}
     :setup    [[:counter/initialise 11]]
     :script [[:assert-db [:count] 11]]
     :tags      #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/a11y-known-good
    {:doc       "Deterministic a11y known-good browser fixture. The
                browser scenario injects a stable axe-compatible
                scanner and asserts this fixture reports zero rows."
     :component :counter-with-stories.views/a11y-known-good-card
     :args      {:label "A11y known good"
                 :settings {:title "A11y good" :enabled? true}}
     :setup    [[:counter/initialise 21]]
     :script [[:assert-db [:count] 21]]
     :tags      #{:dev :test :internal}
     :substrates #{:reagent}})

  (story/reg-variant :story.counter-matrix/a11y-known-bad
    {:doc       "Deterministic a11y known-bad browser fixture. The
                violation is tied to a fixture-owned image selector so
                output stays stable and never depends on Story chrome."
     :component :counter-with-stories.views/a11y-known-bad-card
     :args      {:label "A11y known bad"
                 :settings {:title "A11y bad" :enabled? true}}
     :setup    [[:counter/initialise 22]]
     :script [[:assert-db [:count] 22]]
     :tags      #{:dev :test :internal}
     :substrates #{:reagent}})

  ;; Failing-fx-stub-miss testbed variant. The :script declares
  ;; :rf.assert/effect-emitted against
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
  (story/reg-variant :story.counter-matrix/failing-fx-stub-miss
    {:doc       "Deterministic failing-fx-stub-miss failing assertion. The
                :script asserts :rf.assert/effect-emitted :never-stubbed with
                NO force-fx-stub decorator covering it — the assertion
                fails with the canonical 'fx <id> was not emitted
                during play' reason. Pattern: :story.counter-
                diagnostics/failing-play."
     :args      {:label "failing-fx-stub-miss"
                 :settings {:title "failing-fx-stub-miss" :enabled? true}}
     :setup    [[:counter/initialise 0]]
     :script [[:assert [:rf.assert/effect-emitted :never-stubbed]]]
     :tags      #{:dev :test :internal}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; :script CI fixtures (CI-as-test)
  ;;
  ;; The CI runner at `examples/scripts/serve-and-run-story-play-scripts.cjs`
  ;; discovers every registered variant whose body carries a non-empty
  ;; `:script` slot (via `re-frame.story.play.ci-runner/
  ;; variants-with-play-scripts`; the runtime fn keeps its name after the
  ;; `:script` → `:play-script` lowering), navigates the live Story shell
  ;; to each, waits for the auto-run's terminal status, and asserts the
  ;; aggregate result. These two fixtures pin the contract: one passes,
  ;; one is deliberately wrong so the CI runner's failure path stays
  ;; under continuous coverage too.
  ;;
  ;; The fixtures use `:dispatch-sync` drive steps (not `:dispatch`) so the
  ;; runner observes the resulting app-db state on the very next step
  ;; without needing a `:wait`, and author their checkpoints with the
  ;; `:assert-db` sugar.
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
     :setup     []
     :script
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
     :setup     []
     :script
     {:name      "initialise-one-but-expect-nine"
      :auto-run? true
      :script    [[:dispatch-sync [:counter/initialise 1]]
                  [:assert-db [:count] 9]]}
     :tags       #{:dev :test :internal}
     :substrates #{:reagent}})

  ;; fn-direct :pred fixture — verifies the advanced-CLJS-safe
  ;; authoring path for `:assert-db :pred` (a fn reference handed in
  ;; directly instead of a symbol). The fixture pairs an in-line
  ;; anonymous predicate with a top-level clojure.core predicate so both
  ;; cases land in the browser-side play-script runner.
  (story/reg-variant :story.counter-play-script/pred-fn-direct
    {:doc        "rf2-inbad CI fixture — `:assert-db :pred` with fn
                 references handed in directly. Survives advanced CLJS
                 because no symbol resolution is performed at run time."
     :args       {:label "Play-script :pred fn-direct"}
     :setup     []
     :script
     {:name      "pred-fn-direct-passes"
      :auto-run? true
      :script    [[:dispatch-sync [:counter/initialise 3]]
                  [:assert-db [:count] :pred pos?]
                  [:assert-db [:count] :pred (fn [n] (= n 3))]]}
     :tags       #{:dev :test :internal}
     :substrates #{:reagent}})

  ;; DOM-step fixtures — live-browser coverage of the rich-DSL
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
     :setup     []
     :script
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
     :setup     []
     :script
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

  ;; Multi-play fixture — three named plays on one variant.
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
     :setup     []
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
