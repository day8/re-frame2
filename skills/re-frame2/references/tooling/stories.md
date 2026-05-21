# Stories — re-frame2 binding

> Authoring `reg-story` / `reg-variant` (and the five other Story `reg-*` macros). Assumes you already know what a component playground / story is — this leaf only covers re-frame2's specific declarations.

## Mental model: think in Storybook, map onto Story

**Standing advice for all Story work: think about how you'd do it in Storybook JS, then map those ideas onto Story.** Storybook is the widely-known component-playground mental model and it's in your training data — sketch the shape there first ("which story, which args, which controls, which play step?"), then translate to re-frame2's `reg-*` declarations. The primitives line up cleanly, with a few deliberate divergences that are themselves the interesting part.

| Storybook concept | Story equivalent | Notes |
|---|---|---|
| **story** (CSF named export `Default` / `WithProps` / …) | `reg-variant` (one variant per scenario); the component-level parent is `reg-story` | A `reg-story` carries the `:component` + shared defaults; each `reg-variant` is one scenario, exactly like a CSF named export. |
| **args** | `:args` | Same concept, same name. Five layers compose by deep-merge (global → story → mode → variant → live cell-override); vectors replace. Matches Storybook's merge convention. |
| **argTypes** | `:argtypes` — but rarely written | Story auto-derives the controls panel from the view's **Malli schema**. Write `[:int {:min 0 :max 100}]` once on the view and every story gets the slider. `:argtypes` is only the per-key override channel for what the schema can't say. |
| **controls** | the controls panel | Schema-derived (see above). `:string`→text, bounded `:int`→slider, `[:enum …]`→select, `[:map …]`→nested group, `[:vector X]`→repeater. |
| **play function** | `:play-script` (phase 4) | A vector of TAGGED steps (`[:dispatch-sync ev]` / `[:click sel]` / `[:type sel txt]` / `[:wait ms]` / …), NOT a closure. Assertions ride `[:dispatch-sync [:rf.assert/* …]]`. **Key divergence:** `:rf.assert/*` events *record* into `:assertions` and continue — Storybook's play throws on first failure; Story collects every mismatch. |
| **decorators** (project / component / story) | `reg-decorator` + `:decorators` | Three kinds — `:hiccup` (wrapper), `:frame-setup` (seed app-db / fire init events), `:fx-override` (stub an fx). Project-wide decorators go in `configure! :rf.story/global-decorators` — the `preview.ts` `decorators: […]` parity. Compose order is global → story → variant, same as Storybook. |
| **globals / globalTypes toolbar** (theme · viewport · locale · backgrounds) **+ Chromatic Modes** | `reg-mode` (saved arg tuples) | One primitive collapses all four Storybook toolbar addons. `(reg-mode :Mode.theme/dark {:axis :theme :args {:theme :dark}})` is the theme switcher; `:viewport` / `:locale` / `:background` axes cover the rest. Each `(variant × mode)` cell is independently snapshot-able — that's the Chromatic-Modes combinatorial-matrix idea, native. |
| **tags** (`autodocs` / `test` / `dev`, `!`-removal) | `:tags` + `reg-tag` | Same inclusion-and-filter role; same `!`-prefix removal (`:!dev` drops an inherited tag). The seven canonical tags auto-register. |
| **record-canvas-as-CSF** recorder | `start-recording!` → `gen-play-snippet` → `:play-script` | EDN out, not Testing-Library code — no DOM-event translation layer. See `story-recorder.md`. |

**Where Story deliberately has NO Storybook equivalent (read these as the payoff, not a gap):**

- **CSF itself / the default-export + named-export file format.** Story is EDN-first: the seven `reg-*` macros, pure data, no `:render` fn-slot. The combined Form-B `(reg-story id {:variants {…}})` is the closest *shape* to CSF's default-export-plus-named-exports, but a variant body round-trips as data (over the wire, into the MCP pipeline, to a visual-regression server) in a way CSF's inline-JSX bodies cannot. This restriction is the design centre, not a missing feature.
- **The render fn / `useArgs()` / `useState()`** — and the hooks-inside-stories re-render gotcha that comes with them. Every variant IS its own re-frame frame with its own `app-db`; stateful stories are the default, driven by `:events` (setup) and `:play-script` (post-render) and read with subs. No render fn means no place for a hook to break.
- **A first-party visual-regression service** (Chromatic / Percy). Story ships the `snapshot-identity` hook for downstream services to consume; it is not in the pixel-capture/baseline-storage business.

Sketch in Storybook mentally, then translate.

> **One-liner for the next leaf:** the same "think in Storybook, map onto Story" bridge applies to the recorder (`story-recorder.md`) and the agent loop (`story-mcp-loop.md`).

## When to load this leaf

- Author or edit a `.cljs` namespace under `<app>/stories/*` that uses `re-frame.story`.
- Add a variant, decorator, mode, workspace, panel, or tag.
- Wire a story into the conformance / test harness via the `:play-script` slot.

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

### `reg-tag` body slots — `:axis` and `:default-filter` (SB9 parity)

Project tag registrations may carry two optional body slots that drive the sidebar tag-filter UI:

```clojure
(story/reg-tag :auth/regression-set
  {:doc  "Auth regression-suite variants."
   :axis :team})                          ; facet grouping hint

(story/reg-tag :alpha
  {:doc            "Pre-release status."
   :axis           :status
   :default-filter :exclude})             ; pre-excluded at boot
```

- **`:axis :keyword`** — facet classifier (e.g. `:status` / `:role` / `:team` / `:feature`). Tags sharing an axis render as one collapsible row in the sidebar tag-filter; tags without `:axis` render in a trailing un-grouped row. Purely a UI grouping hint — does not affect variant `:tags` set semantics or `variants-with-tags` filtering. Query via `(story/tags-by-axis :status)` / `(story/tags-without-axis)`. Mirrors the same body-shape extension on `reg-mode` (toolbar grouping).
- **`:default-filter :include|:exclude`** — initial sidebar filter state. `:include` (default) leaves the tag's variants visible; `:exclude` pre-hides them at boot (use for `:internal` / `:experimental` style tags). Query the boot-exclusion set via `(story/tags-default-excluded)`.

Both slots are opt-in. The seven canonical tags register without `:axis` or `:default-filter`; project tags may opt in. Canonical home: `tools/story/spec/010-Toolbar.md` §Optional grouping (mirrors `reg-mode`); SB9 parity tracker `tools/story/spec/005-SOTA-Features.md`.

## Canonical mini-example

Distilled from `tools/story/testbeds/counter_with_stories/stories.cljs` — every key shape in one variant per slot.

```clojure
(ns app.stories.counter
  (:require [re-frame.story :as story]
            [app.events]                 ;; ensure event ids exist
            [app.views]))                ;; ensure view ids exist

(defn register-all! []
  ;; No explicit boot step — the first `reg-*` below auto-installs the
  ;; canonical vocabulary (seven tags, lifecycle machine, :rf.assert/*
  ;; handlers, force-fx-stub decorator, v1 panel set).

  (story/reg-story :story.counter
    {:doc        "The counter."
     :component  :app.views/counter-card  ;; reg-view id, not a fn
     :args       {:label "Count"}
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; Inherits :component, :args, :tags from the parent story.
  (story/reg-variant :story.counter/loaded
    {:doc         "Seeded with seven."
     :args        {:label "Total"}            ;; override / extend story args
     :events      [[:counter/initialise 7]]   ;; phase-2 setup; runs before render
     :play-script [[:dispatch-sync [:counter/inc]]  ;; phase-4; trace-bus observes these
                   [:dispatch-sync [:rf.assert/path-equals [:count] 8]]
                   [:dispatch-sync [:rf.assert/sub-equals  [:count-doubled] 16]]
                   [:dispatch-sync [:rf.assert/dispatched? [:counter/inc]]]]
     :tags        #{:dev :docs :test}
     :substrates  #{:reagent}})

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

- **No fn slots in variant bodies.** `:events`, `:play-script`, `:decorators`, `:loaders` carry **event-vectors / tagged steps and ids** — never anonymous functions. The `:rf/variant` schema (in `spec/Spec-Schemas.md`) enforces this; macro-time validation rejects with `:rf.error/variant-shape`. The single legal closure site is a `:hiccup`-kind decorator's `:wrap` slot at the decorator's registration site (not the variant body).
- **`:events` vs `:play-script`.** `:events` (phase 2) runs before render; the trace-bus accumulator is NOT yet installed, so `:rf.assert/dispatched?` will not see those events. Put events you want to assert against in `:play-script` (phase 4). `:play-script` is the canonical AND ONLY phase-4 surface. Steps are tagged: `[:dispatch ev]` / `[:dispatch-sync ev]` / `[:wait ms]` / `[:click sel]` / `[:type sel txt]` / `[:assert-db path val]` / `[:assert-dom sel mode]`. Bare event vectors at the script level lift to `[:dispatch ev]`; assertion events ride `[:dispatch-sync [:rf.assert/* ...]]`. See `tools/story/spec/001-Authoring.md` §`:play-script`.
- **Component is an id, not a fn.** `:component :app.views/counter-card` — the keyword id of a `reg-view`. Story consults the view's registered Malli schema (Spec 010) to auto-derive `:argtypes` for the controls panel — see Schema-derivation pipeline below; supplying `:argtypes` overrides per-key.
- **Reference events / views / decorators by id; require their namespaces only to trigger registration.** The stories namespace does not `:refer` view fns — it `:require`s `[app.events] [app.views]` for side-effect loading and uses the keyword ids.
- **`:rf.assert/*` events record, they do not throw.** The seven canonical assertions append a record to `:assertions` rather than aborting the play sequence. Use `(story/read-assertions variant-id)` and `(story/assertions-passing? result)` from a test runner. See `tools/story/spec/004-Assertions.md`.
- **`:extends` is resolved at registration time.** A variant inheriting from another merges the parent's body once; cycles raise `:rf.error/extends-cycle` at `reg-variant` time, not at render.
- **Loaders run before events, in their own phase.** Phase 1 (`:loaders`) seeds remote-data or installs long-lived fx — websocket subscriptions, firestore listeners — *before* phase-2 `:events`. The runtime waits for `:loaders-complete-when` (default: HTTP fx complete on response-event dispatch; long-lived fx complete on first inbound message) before draining `:events`. Authors override the predicate by setting `:loaders-complete-when` to an event id or a literal vector-of-event-vectors. Phase 3 renders; phase 4 runs `:play-script`. Put fixture seeds in `:loaders`, pre-render setup in `:events`, user interactions to assert against in `:play-script`.
- **Modes multiply snapshot identity; args precedence is strict.** Each `(variant × mode)` cell has an independent `snapshot-identity` — visual-regression services iterate cells, not variants. Effective args compose in this order (later wins): global `configure!` args → story `:args` → mode `:args` (deep-merge of nested maps, replace for vectors) → variant `:args` → cell-local overrides from the controls panel (`:story/set-arg`).
- **Combined form (Form B).** `(reg-story id {:variants {:a {...} :b {...}}})` desugars at macro-expansion time to one `reg-story*` + N `reg-variant*` top-level forms — hot-reload-by-variant still works. The `*`-suffixed runtime helpers are public for programmatic / MCP-driven registration.
- **Production elision.** Under `:advanced` every `reg-*` and the seven `:rf.assert/*` event-ids drop to `nil` via the `:rf.story/enabled?` goog-define. Do not condition production code on story state.

## Schema-derivation pipeline (controls panel)

The controls panel auto-derives a widget per arg-key by walking the component's Malli schema. `resolve-argtypes` consults, in order:

1. **Explicit `:argtypes`** on the variant body, then the parent story (per-key override).
2. **Explicit `:rf/schema` slot** (Spec 010 canonical key; the legacy `:schema` alias is still read for backward compat) on the variant body, then the parent story, then the registered view's `:spec`. First match wins — no merge. A variant-side `:rf/schema` is the all-or-nothing override when one variant exercises a narrower / stricter / experimental shape than the component-wide schema; the args editor inputs and the schema-validation panel both auto-derive from this one declaration.
3. **Value-shape fallback** — infer the widget from the live CLJS value when no schema is available.

Walker output for the common Malli forms:

| Schema | Widget |
|---|---|
| `:string` / `:keyword` | `:text` (keyword-coercion at edit time) |
| `:int` / `:double` (optional `:min` / `:max`) | `:number` |
| `:boolean` | `:boolean` |
| `[:enum a b c]` | `:select` with `:options [a b c]` |
| `[:map [k1 s1] [k2 s2] ...]` | `:group` — one nested row per key |
| `[:vector X]` | `:repeater` — rows of `X` with `[+]` / `[-]` affordances |
| `[:set X]` | `:repeater` `:kind :set` — values round-trip through `set` |
| `[:tuple X Y ...]` | `:tuple` — fixed-arity, one row per position |

The walker recurses: `[:map [:meta [:map [:author :string]]]]` yields a `:group` whose `[:meta]` entry is itself a `:group` with one `:author` `:text` row. Editing entry `kN` writes through to `[:cell-overrides variant-id arg-key kN ...]` at the appropriate depth; integer indices for vector/tuple slots.

When no schema is available the fallback infers from the value: maps recurse as `:group`, vectors as `:repeater`, scalars classify by `string?`/`integer?`/`boolean?`. This keeps "zero argtypes" stories from collapsing to inert `:text` widgets for non-trivial shapes. Author-supplied `:argtypes` still wins key-by-key over either derivation path.

## Stories as a unit-test substrate

A variant's `:play-script` slot IS the test. `(story/run-variant :story.counter/loaded)` returns a result map; `(story/read-assertions :story.counter/loaded)` returns the assertion records; `(story/assertions-passing? result)` is the boolean. The play-runner stamps each `:rf.assert/*` entry with its source coord so failures point at the variant body line.

For tests that don't need a render shell, run variants headless from a JVM test (`shadow-cljs run`, deps.edn alias) — the play-runner is platform-agnostic per Spec 011.

## Test-pane dev UX — SB9-parity affordances

The dev shell's Test pane and recorder ship ergonomic affordances that consume what the author writes — none are new authoring surfaces, but knowing they exist helps when recommending the right tool. All are dev-shell scoped and elided in production.

- **Chrome test widget + sidebar status dots.** A Vitest-style widget at the foot of the sidebar; per-variant status dots render next to each row. Aggregates pass/fail across the `:test`-tagged set — any variant carrying `:test` participates. See `tools/story/spec/009-Test-Mode.md` §Chrome-level test widget.
- **Watch mode.** The eye-icon chip on the test widget toggles watch mode; when on, the shell re-runs the focused `:test` variant on every hot-reload of its content. Scoped to one variant to keep noise low. See `tools/story/spec/009-Test-Mode.md` §Watch mode.
- **`:play-script` step-through scrubber.** The play-stepper UI pauses between steps in a `:play-script` sequence, surfaces the intermediate `:assertions` list per step, and offers a re-dispatch hook — useful for diagnosing which step flipped which assertion. See `tools/story/spec/004-Assertions.md` §play-stepper.
- **Save-as-variant modal.** When the chrome-toolbar REC chip stops a recording, the shell opens a modal that renders the captured trace as a `(reg-variant ...)` form (with `:extends` from the source variant) — one-click to paste into the stories namespace. See `tools/story/spec/005-SOTA-Features.md` §Save-as-variant.
- **Mid-recording assertion inserter.** The recording overlay carries an `+ assert` button next to `stop` that opens a picker over the canonical seven `:rf.assert/*` ids with EDN payload prompts; inserted assertions interleave with captured dispatches in-place. Pure helpers (`make-assertion`, `append-assertion`, `insert-assertion!`) live in `re-frame.story.recorder` (JVM-testable). See `tools/story/spec/005-SOTA-Features.md` §Mid-recording assertion insertion and `story-recorder.md` (sibling leaf).
- **Sidebar tag-as-badge.** Variant rows render their `:tags` set as small badges — fastest way to see which variants carry `:test` / `:experimental` / `:internal` at a glance. Composes with `:default-filter :exclude` on `reg-tag` to hide noisy tags by default.

## Deeper material

- Authoring grammar in full (every key, every slot) → `SKILL-REDIRECT.md` → *EP — Stories (007)*.
- Schema-derivation pipeline reference (every collection operator, default-element seeding, path-aware write contract) → `tools/story/spec/001-Authoring.md` §Schema-derivation pipeline.
- Args precedence + four-phase lifecycle full detail → `tools/story/spec/002-Runtime.md` §Args resolution precedence, §Four-phase lifecycle.
- Worked example, every macro at least once → `tools/story/testbeds/counter_with_stories/`.
- The seven `:rf.assert/*` events, semantics + source-stamping → `SKILL-REDIRECT.md` → *EP — Stories (007)* §Assertions.
- Render shell, panel placement, multi-substrate pane → `tools/story/spec/003-Render-Shell.md` §UI shell substrate, §Workspace layouts, §Multi-substrate side-by-side rendering.
- Test Codegen (record canvas interactions as `:play-script`) → `story-recorder.md` (sibling leaf).
- Agent self-healing loop over MCP (variant authoring → run → assert → refine) → `story-mcp-loop.md` (sibling leaf).
- MCP write surface (programmatic registration via `reg-*` helpers) → `SKILL-REDIRECT.md` → *EP — Stories (007)* §MCP Surface.

---

*Derived from `tools/story/` (artefact source) and `tools/story/testbeds/counter_with_stories/` @ main `89bd9c3`. Re-verify after Story-macro grammar changes or new `:rf.assert/*` events.*
