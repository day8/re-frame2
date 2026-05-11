# Stories — re-frame2 binding

> Authoring `reg-story` / `reg-variant` (and the five other Story `reg-*` macros). Assumes you already know what a component playground / story is — this leaf only covers re-frame2's specific declarations.

## When to load this leaf

- Author or edit a `.cljs` namespace under `<app>/stories/*` that uses `re-frame.story`.
- Add a variant, decorator, mode, workspace, panel, or tag.
- Wire a story into the conformance / test harness via the `:play` slot.

Do **not** load this leaf to learn what a story is — that is training knowledge. Load it for: the macro signatures, the `:rf.assert/*` vocabulary, and the no-fn-slots rule that distinguishes re-frame2 stories from Storybook stories.

## Canonical signatures

All seven macros live in `re-frame.story`. All elide to `nil` under `:advanced`.

```clojure
(story/reg-story     id metadata)   ; parent — inherits down to variants
(story/reg-variant   id metadata)   ; one cell — pure-data body, no fn slots
(story/reg-workspace id metadata)   ; layout over N variants (:grid / :variants-grid / :prose / :tabs / :custom)
(story/reg-decorator id metadata)   ; :hiccup | :frame-setup | :fx-override
(story/reg-story-panel id metadata) ; right/left/bottom/top pane in the shell
(story/reg-tag       id metadata)   ; project tag; canonical seven register at load
(story/reg-mode      id metadata)   ; saved args tuple — each (variant × mode) cell has its own snapshot-identity
```

Variant `id` grammar: `:story.<dotted.path>/<variant-name>`. Story `id`: `:story.<dotted.path>` (no `/<variant>` suffix). The seven canonical tags — `:dev :docs :test :screenshot :experimental :internal :agent` — register automatically when `re-frame.story` loads; project tags must `reg-tag` before any variant references them, or registration throws `:rf.error/unknown-tag`.

## Canonical mini-example

Distilled from `examples/reagent/counter_with_stories/stories.cljs` — every key shape in one variant per slot.

```clojure
(ns app.stories.counter
  (:require [re-frame.story :as story]
            [app.events]                 ;; ensure event ids exist
            [app.views]))                ;; ensure view ids exist

(defn register-all! []
  (story/install-canonical-vocabulary!)   ;; idempotent; registers :rf.assert/* + canonical tags

  (story/reg-story :story.counter
    {:doc        "The counter."
     :component  :app.views/counter-card  ;; reg-view id, not a fn
     :args       {:label "Count"}
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; Inherits :component, :args, :tags from the parent story.
  (story/reg-variant :story.counter/loaded
    {:doc    "Seeded with seven."
     :args   {:label "Total"}            ;; override / extend story args
     :events [[:counter/initialise 7]]   ;; phase-2 setup; runs before render
     :play   [[:counter/inc]             ;; phase-4; trace-bus observes these
              [:rf.assert/path-equals [:count] 8]
              [:rf.assert/sub-equals  [:count-doubled] 16]
              [:rf.assert/dispatched? [:counter/inc]]]
     :tags   #{:dev :docs :test}
     :substrates #{:reagent}})

  (story/reg-decorator :app/log-decorator
    {:kind :hiccup                       ;; ONLY :hiccup decorators take a closure
     :wrap (fn [body [_ label]]
             [:div {:style {:border "1px dashed #888"}} body])})

  (story/reg-mode :Mode.app/dark
    {:args {:theme :dark}})              ;; deep-merges into variant args at render time

  (story/reg-workspace :Workspace.counter/all
    {:layout :variants-grid              ;; auto-enumerates :story.counter variants
     :for    :story.counter
     :tags   #{:docs}}))

(register-all!)
```

## Common gotchas — re-frame2-specific

- **No fn slots in variant bodies.** `:events`, `:play`, `:decorators`, `:loaders` carry **event-vectors and ids** — never anonymous functions. The `:rf/variant` schema (in `spec/Spec-Schemas.md`) enforces this; macro-time validation rejects with `:rf.error/variant-shape`. The single legal closure site is a `:hiccup`-kind decorator's `:wrap` slot at the decorator's registration site (not the variant body).
- **`:events` vs `:play`.** `:events` (phase 2) runs before render; the trace-bus accumulator is NOT yet installed, so `:rf.assert/dispatched?` will not see those events. Put events you want to assert against in `:play` (phase 4). Variant 3 in the counter example demonstrates this — three `[:counter/inc]` dispatches in `:play`, then `[:rf.assert/dispatched? [:counter/inc]]`.
- **Component is an id, not a fn.** `:component :app.views/counter-card` — the keyword id of a `reg-view`. Story consults the view's registered Malli schema (Spec 010) to auto-derive `:argtypes` for the controls panel; supplying `:argtypes` overrides per-key.
- **Reference events / views / decorators by id; require their namespaces only to trigger registration.** The stories namespace does not `:refer` view fns — it `:require`s `[app.events] [app.views]` for side-effect loading and uses the keyword ids.
- **`:rf.assert/*` events record, they do not throw.** The seven canonical assertions append a record to `:assertions` rather than aborting the play sequence. Use `(story/read-assertions variant-id)` and `(story/assertions-passing? result)` from a test runner. See `tools/story/spec/004-Assertions.md`.
- **`:extends` is resolved at registration time.** A variant inheriting from another merges the parent's body once; cycles raise `:rf.error/extends-cycle` at `reg-variant` time, not at render.
- **Modes multiply snapshot identity.** Each `(variant × mode)` cell has an independent `snapshot-identity` — visual-regression services iterate cells, not variants. Precedence (lowest to highest): global `configure!` args < mode args < story args < variant args.
- **Combined form (Form B).** `(reg-story id {:variants {:a {...} :b {...}}})` desugars at macro-expansion time to one `reg-story*` + N `reg-variant*` top-level forms — hot-reload-by-variant still works. The `*`-suffixed runtime helpers are public for programmatic / MCP-driven registration.
- **Production elision.** Under `:advanced` every `reg-*` and the seven `:rf.assert/*` event-ids drop to `nil` via the `:rf.story/enabled?` goog-define. Do not condition production code on story state.

## Stories as a unit-test substrate

A variant's `:play` slot IS the test. `(story/run-variant :story.counter/loaded)` returns a result map; `(story/read-assertions :story.counter/loaded)` returns the assertion records; `(story/assertions-passing? result)` is the boolean. The play-runner stamps each `:rf.assert/*` entry with its source coord so failures point at the variant body line.

For tests that don't need a render shell, run variants headless from a JVM test (`shadow-cljs run`, deps.edn alias) — the play-runner is platform-agnostic per Spec 011.

## Deeper material

- Authoring grammar in full (every key, every slot) → `SKILL-REDIRECT.md` → *EP — Stories (007)*.
- Worked example, every macro at least once → `examples/reagent/counter_with_stories/`.
- The seven `:rf.assert/*` events, semantics + source-stamping → `SKILL-REDIRECT.md` → *EP — Stories (007)* §Assertions.
- Render shell, panel placement, multi-substrate pane → `SKILL-REDIRECT.md` → *Guide — Stories*.
- MCP write surface (programmatic registration via `reg-*` helpers) → `SKILL-REDIRECT.md` → *EP — Stories (007)* §MCP Surface.

---

*Derived from `tools/story/` (artefact source) and `examples/reagent/counter_with_stories/` @ main `89bd9c3`. Re-verify after Story-macro grammar changes or new `:rf.assert/*` events.*
