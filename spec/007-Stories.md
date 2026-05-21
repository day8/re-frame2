# Spec 007 — Stories, Variants, and Workspaces

> Builds on the frame foundation in [002-Frames.md](002-Frames.md) and the testing infrastructure in [008-Testing.md](008-Testing.md) — stories *use* the same primitives tests use, layered with rendering, args/controls, decorators, play functions, and a Storybook-class UI.
>
> **Ownership boundary:** [008-Testing.md](008-Testing.md) is the **owner** of the testing infrastructure surface — fixtures, dispatch-sync, per-test stubbing, headless evaluation, framework adapters, the JVM-runnable test surface. 007 cross-references 008 for portable-stories-as-tests rather than restating; story-as-test plumbing builds *on* 008's primitives.

## Abstract

A re-frame2 component-development tool surfaces re-frame components in isolation, in specific states, with rich tooling around them — data-oriented and frame-native. This Spec captures the design space.

The unit of design is a **three-way split**:

- **Story** — a topic / component / slice. Defines what's being shown and the surrounding fixtures.
- **Variant** — one concrete scenario of a Story. Each variant is the Story rendered in a specific state.
- **Workspace** — a layout that arranges stories/variants on screen for browsing, documentation, or comparison.

The rest of the design — args, decorators, play, tags — slots cleanly into one of the three.

## Why a separate Spec

Stories/variants/workspaces are downstream concerns. They are *enabled by* the frame and view designs in 002 and 004; they shouldn't drive those decisions. Keeping the design here:

- Lets 002 and 004 stay focused on the foundation.
- Lets the story-tool design evolve independently of foundation framework decisions.
- `story/reg-story`/`story/reg-variant`/`story/reg-workspace` are sugar; everything is doable by hand with `reg-frame` + `reg-view` + `frame-provider`.

## Canonical id grammar

The story / variant / workspace id syntax is **locked** and used consistently throughout the document, the registrar, and the story tool:

| Artefact | Id shape | Example |
|---|---|---|
| Story | `:story.<dotted-path>` | `:story.auth.login-form` |
| Variant | `:story.<dotted-path>/<variant-name>` | `:story.auth.login-form/empty` |
| Workspace | `:Workspace.<dotted-path>/<workspace-name>` | `:Workspace.Auth/all-states` |

Rules:

1. The `:story.<...>` and `:Workspace.<...>` prefixes are **library-owned** by the `re-frame.story` tools artefact (`tools/story/`) — they are not framework-reserved under `:rf/*` (see [Conventions §Library-owned prefixes](Conventions.md#library-owned-prefixes)). User code MUST NOT register stories/workspaces under conflicting prefixes when this library is loaded.
2. The dotted path segments organise the tree the story tool renders — split on `.` to build the navigator.
3. Variant names go after `/`. A variant id always belongs to exactly one story; the story id is everything before `/`.
4. Tools enumerate via `(story/registrations :story)`, `(story/registrations :variant)`, `(story/registrations :workspace)` — the Story library exposes its own registry-introspection over the tool-owned side-table at `tools.story.registry/*` (Story is a separate tools artefact and `:story`/`:variant`/`:workspace` are NOT framework registry kinds — see [Conventions §Library-owned prefixes](Conventions.md#library-owned-prefixes) and [§Public-query parity](#story-tool-extension-hook)). The hierarchy is recoverable from the id alone — no separate `:title` field is required.

## The three concepts

### Story

A story is the **topic** — typically a component, slice, or screen. It declares what's being demonstrated, the *shared* fixtures across its variants, decorators, args, play, and metadata. A story without variants is a degenerate case.

```clojure
(story/reg-story :story.auth.login-form
  {:doc        "The login form component."
   :component  :app.auth/login-form          ;; keyword id of a registered :view
   :decorators [[:centered-layout]
                [:theme :light]]
   :args       {:placeholder "you@example.com"
                :submit-label "Sign in"}
   :argtypes   {:placeholder  {:control :text}
                :submit-label {:control :text}}
   :tags       #{:dev :docs}})               ;; inclusion tags — see below
```

The story is registered under a hierarchical keyword: `:story.<path>` where path segments organise the story tree.

### Variant

A variant is a **specific scenario** — one state of a story. Variants register against a parent story and inherit its decorators, args, etc.; variants override or extend.

```clojure
(story/reg-variant :story.auth.login-form/empty
  {:doc    "Fresh form, nothing entered."
   :events [[:auth/initialise]]})

(story/reg-variant :story.auth.login-form/validation-error
  {:doc    "Invalid email shown inline after submit."
   :events [[:auth/initialise]
            [:auth/email-changed "not-an-email"]
            [:auth/login-pressed]]
   :tags   #{:dev :docs :test}})        ;; this one is also used as a test fixture

(story/reg-variant :story.auth.login-form/loading
  {:doc       "Submit pressed, server response pending."
   :events    [[:auth/initialise]
               [:auth/email-changed "alice@example.com"]
               [:auth/login-pressed]]
   :decorators [[:force-fx-stub :my-app/http {:status :pending}]]})
```

(`:my-app/http` here is a placeholder for a user-supplied fx; the framework ships `:rf.http/managed` — see [014-HTTPRequests](014-HTTPRequests.md).)

The keyword convention `:story.<path>/<variant>` keeps stories and their variants discoverable as a group, while still being a single keyword for re-frame's purposes.

#### Variant artefact contract — variants are data, not functions

Locked. A variant's body is a **serialisable artefact** — every field is plain data (vectors, maps, keywords, strings, numbers), not a function. This makes variants:

- **Wire-portable.** A variant is round-trippable as EDN/JSON; the visual-regression service, the documentation generator, and the agent-input pipeline all consume the same shape.
- **Storable.** A frozen variant snapshot (per [§Variant snapshot identity](#variant-snapshot-identity)) is serialisable.
- **Diffable.** Two variants compare structurally; the story tool's "what changed" panel is structural diff, not function identity.

Concretely, the keys allowed in a `story/reg-variant` body:

| Key | Shape | Notes |
|---|---|---|
| `:doc` | string | One-sentence what-and-why. |
| `:extends` | variant-id | Parent variant; merged at registration time per [§Composed variants](#composed-variants--reference-parent-by-id-override-by-data). Resolves to a registered variant id; cycles are a registration error. |
| `:events` | vector of event vectors | Setup events; dispatch-synced into the variant's frame in order, after `:loaders` complete. Data only. |
| `:play` | vector of event vectors (incl. `:rf.assert/*`) | Post-render interaction sequence. Data only. |
| `:args` | map | Override or extend the parent story's args. |
| `:argtypes` | map (optional override) | Per-arg control description. Auto-derived from the view's [Spec 010](010-Schemas.md) schema where present. |
| `:tags` | set of keyword | Inclusion tags from the registered vocabulary (see [§Inclusion tags](#inclusion-tags)). |
| `:decorators` | vector of vectors | Each decorator is `[decorator-id args...]` — id-valued, not function-valued. |
| `:loaders` | vector of event vectors | Async setup events; dispatch-synced before `:events` and before render (see [§Loaders](#loaders-advanced--async-setup) for the lifecycle). Data; the *handler* the loader event ids point to is the only fn-valued part. |
| `:args->events` | map | Per-arg event-id mapping `{<arg-key> <event-id>}`; the registered handler at `<event-id>` receives the new value as its payload. Data only — see [§Args mapping to state](#args-mapping-to-state). |
| `:platforms` | set | Subset of `#{:server :client}`; controls where the variant runs. |

**No fn-valued slots** in variant bodies. Where today's prior art (Storybook decorators, Histoire `setup`) takes a function, re-frame2 takes a registered id (`story/reg-decorator :centered-layout {...}`); the function lives at the registration site, not at the variant call site.

#### Composed variants — reference parent by id, override by data

A variant may reference another variant as its base, overriding selected keys:

```clojure
(story/reg-variant :story.auth.login-form/loading-with-prefill
  {:extends :story.auth.login-form/loading                ;; parent variant id
   :events [[:auth/initialise]
            [:auth/email-changed "alice@example.com"]
            [:auth/password-changed "hunter2"]
            [:auth/login-pressed]]                         ;; override events
   :tags   #{:dev :docs}})                                  ;; override tags
```

`:extends` resolves at registration time. The library merges the parent's body with the child's (child wins key-by-key); the result is a fully data-shaped variant artefact. Composition is a pure-data transform — no closures, no inheritance ceremony.

The `:rf/variant` schema enforces both rules (data-only fields; `:extends` resolves to a registered variant id). See [Spec-Schemas §`:rf/variant`](Spec-Schemas.md#rfvariant).

### Combined `story/reg-story` form — a sugar that desugars

Two registration forms are canonical, and authors choose by ergonomics:

**Form A (separate, hot-reload-friendly):** `(story/reg-story :id metadata)` + N `(story/reg-variant :story-id/variant-id metadata)` calls. Each variant is a top-level form — saving the file invalidates only the changed variant; hot-reload is precise.

**Form B (combined):** `(story/reg-story :id metadata)` with a `:variants` map in the metadata. The story library *desugars* this at macro-expansion time into Form A — the registrar receives N independent `story/reg-variant` calls, so hot-reload-by-variant still works the same way. Form B is sugar for one-form-per-story authoring.

```clojure
;; Form B: combined; the macro emits N story/reg-variant calls at expansion.
(story/reg-story :story.auth.login-form
  {:doc "The login form component."
   :component :app.auth/login-form
   :decorators [[:centered-layout]]
   :args     {:placeholder "you@example.com"}
   :argtypes {:placeholder {:control :text}}
   :tags     #{:dev :docs}
   :variants {:empty             {:events [[:auth/initialise]]}
              :validation-error  {:events [[:auth/initialise]
                                           [:auth/email-changed "not-an-email"]
                                           [:auth/login-pressed]]
                                  :tags   #{:dev :docs :test}}
              :loading           {:events [[:auth/initialise]
                                           [:auth/email-changed "alice@example.com"]
                                           [:auth/login-pressed]]
                                  :decorators [[:force-fx-stub :my-app/http {:status :pending}]]}}})
```

Both forms are first-class.

### Workspace

A workspace is a **layout** — multiple stories/variants arranged on screen for browsing, documentation, or side-by-side comparison.

```clojure
(story/reg-workspace :Workspace.Auth/all-states
  {:doc    "Every login-form state side by side, for QA review."
   :layout :grid
   :variants [:story.auth.login-form/empty
              :story.auth.login-form/validation-error
              :story.auth.login-form/loading
              :story.auth.login-form/rate-limited]})

(story/reg-workspace :Workspace.Auth/docs
  {:doc       "Auth flow documentation page."
   :layout    :prose                  ;; markdown-flavoured layout
   :content   [{:type :prose :body "## The login flow\n\n..."}
               {:type :variant :id :story.auth.login-form/empty}
               {:type :prose :body "When the email is invalid:"}
               {:type :variant :id :story.auth.login-form/validation-error}]})
```

Workspaces are themselves rendered like other re-frame views; the workspace tool reads the registry and lays them out.

## Args and controls

Storybook's headline UX is the **controls** panel — interactive props that re-render the story. We need an equivalent.

### Args at three levels

1. **Global args** — re-frame2 doesn't have a global default beyond what frames give us. Story-tool config can supply defaults (theme, locale).
2. **Story-level args** — declared on `story/reg-story`; inherited by every variant.
3. **Variant-level args** — override or extend the story's args.

```clojure
(story/reg-variant :story.auth.login-form/customised
  {:args {:placeholder "your.email@company.com"      ;; override story default
          :submit-label "Authenticate"}
   :events [[:auth/initialise]]})
```

### Argtypes describe controls

`:argtypes` is a map of arg-name → control specification. The story tool reads this to render sidebar widgets.

```clojure
{:argtypes
 {:placeholder  {:control :text}
  :submit-label {:control :text}
  :variant      {:control {:type :select
                           :options [:primary :secondary :danger]}}
  :disabled?    {:control :boolean}
  :max-length   {:control {:type :number :min 1 :max 100}}}}
```

Control types map to common widgets: `:text`, `:textarea`, `:number`, `:boolean`, `:select`, `:radio`, `:date`, `:color`. The tool can extend with custom controls.

> **Auto-derivation from Spec 010 schemas.** A Malli enum on a view's arg becomes a `:select`; a string becomes `:text`; a `[:int {:min 1 :max 100}]` becomes a bounded number control. The stories library consults the view's [Spec 010](010-Schemas.md) schema and synthesises `:argtypes`; authors write `:argtypes` only to override or extend. Single source of truth for arg shape.

### Args mapping to state

Args are passed to the view as data. By default the view renders with the current args:

```clojure
(rf/reg-view login-form [args]                ;; receives the current args
  [:form
   [:input {:placeholder (:placeholder args)}]
   [:button (:submit-label args)]])
```

When a control mutates an arg, the story tool dispatches `[:story/set-arg <story-id> <arg-key> <new-value>]` into the variant's frame; the view re-renders with the new args.

For variants that need args to map into `app-db` (e.g., a `:logged-in?` arg controls whether the auth section is rendered), the variant declares an explicit mapping by registered event id:

```clojure
;; Register an event handler that receives the new arg value as its payload.
(rf/reg-event-fx :story.auth/set-logged-in
  (fn [_ [_ v]]
    (if v
      {:fx [[:dispatch [:auth/restore-session {:user "alice"}]]]}
      {:fx [[:dispatch [:auth/log-out]]]})))

(story/reg-variant :story.auth.login-form/logged-in-arg
  {:args         {:logged-in? false}
   :args->events {:logged-in? :story.auth/set-logged-in}    ;; registered id, not a fn
   :events       [[:auth/initialise]]})
```

`:args->events` is `{<arg-key> <event-id>}` — entries are registered event ids, not inline functions. When the control mutates the arg, the story tool dispatches `[<event-id> <new-value>]` into the variant's frame. Most stories don't need `:args->events` — args going to the view directly is enough.

## Decorators

Decorators wrap stories with shared infrastructure: themes, layout containers, mocked providers, fixed widths. Story-level decorators apply to every variant; variants can add their own.

### Three kinds of decorator

1. **Hiccup wrapper.** A vector that wraps the rendered view.
2. **Frame setup.** A function that mutates the story's frame at creation — pre-populates `app-db`, registers per-frame interceptors.
3. **Fx override.** A declaration that swaps an fx for the lifetime of the variant — `[:force-fx-stub :my-app/http canned-response]`.

Each decorator vector in a variant body is `[<decorator-id> args...]` — id-valued (a keyword), not function-valued, per the variant-as-data discipline:

```clojure
;; Hiccup wrapper — pure visual
[:centered-layout]
[:theme :light]
[:fixed-width 480]

;; Frame setup — affects state
[:mock-auth {:user {:id 42 :name "Alice"}}]
[:mock-router {:current-path "/dashboard"}]

;; Fx override — affects effects. The stub payload is data; any handler logic
;; lives in a registered event/fx handler that the decorator references by id.
[:force-fx-stub :my-app/http {:status 200 :body {...}}]
[:force-fx-stub :localstorage {:value nil}]
```

Decorators are themselves registered library artefacts — usually small libraries that ship as `re-frame.decorators.theme`, `re-frame.decorators.auth`, etc. Story authors `(:require ...)` the decorator library to register the ids, then reference each decorator by its keyword id from the variant body; decorators register hooks against the framework's interceptor and fx surfaces (no new framework primitives required).

### Decorator-as-frame-config-merger

A decorator's *frame setup* mode generalises into "things that should be true of any frame using this decorator." For complex apps, common decorators (auth context, router, theme) get factored into the team's design system — story authors compose them, don't reinvent them.

## Play functions

Play is a **sequence of events fired after the variant has rendered**, distinct from `:events` (which run before render to set up state).

```clojure
(story/reg-variant :story.auth.login-form/login-flow
  {:doc    "Full happy-path login interaction."
   :events [[:auth/initialise]]                    ;; setup before render
   :play   [[:auth/email-changed "alice@example.com"]
            [:auth/password-changed "hunter2"]
            [:auth/login-pressed]
            [:rf.assert/path-equals [:auth :status] :authenticated]
            [:rf.assert/path-equals [:nav :route] :dashboard]]})
```

`:rf.assert/*` events are themselves dispatches, handled by the story tool's test runner. In dev/docs mode they're rendered as a checked-step list; in test mode they fail loudly when assertions don't hold; in agent mode they're simulation breakpoints. The `:rf.assert/*` namespace is the canonical assertion namespace — see §Assertion vocabulary is registered and enumerable below for the full registered set.

> **Assertion vocabulary is registered and enumerable.** The `:rf.assert/*` namespace is reserved (see [Conventions.md §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)) and registered as a public, queryable set of events. The stories library registers the canonical vocabulary at load time: `:rf.assert/path-equals`, `:rf.assert/path-matches`, `:rf.assert/sub-equals`, `:rf.assert/dispatched?`, `:rf.assert/state-is` (machine), `:rf.assert/no-warnings`, `:rf.assert/effect-emitted`. Tooling enumerates `(rf/registrations :event #(re-find #"^:rf\.assert/" (str (:id %))))` to discover the vocabulary. Per [Principles §Public query surfaces](Principles.md#public-query-surfaces).

> **Sibling surface — the `(ts/assert-*-equals ...)` fn-family.** The `:rf.assert/*` event-vector family is the **Story `:play`-block** assertion surface; the sibling surface for **in-process `clojure.test` bodies** is the sync fn-family `(ts/assert-path-equals path expected-val)` (mirrors `:rf.assert/path-equals`) + `(ts/assert-db-equals expected-db)` (companion full-db form; no event analog) in `re-frame.test-support` (see [008-Testing §`assert-path-equals` / `assert-db-equals` example](008-Testing.md#assert-path-equals--assert-db-equals-example)). The shared `path-equals` name root between event-side and fn-side is deliberate (per rf2-8j9m6) — same intent (db-shape assertion), different runner/reporting channel; readers navigating between the two surfaces do not need a translation table. Choose by test surface — a story variant's `:play` vector takes `:rf.assert/*`; a `deftest` body calls `ts/assert-path-equals` / `ts/assert-db-equals`. The two are not interchangeable: `:rf.assert/*` events are dispatches handled by the story library's test runner (checked-step list in dev/docs, loud failures in test mode, simulation breakpoints in agent mode); the fn-family reports via `clojure.test/do-report`.

### Story-as-test duality

A variant with `:events` + `:play` + `:rf.assert/*` is a complete component test. Same artefact serves dev-time visualisation, regression testing, and tooling input. Test runners iterate over `:story.*/*` variants tagged `:test` and run their setup + play, asserting on the resulting state.

This collapses several artefacts a typical project maintains separately: the dev-time playground, the test suite, the regression-screenshot fixtures, and the documentation. They become facets of one registered thing.

## Inclusion tags

The standardised inclusion-tag vocabulary controls which contexts include a variant:

| Tag | Meaning |
|---|---|
| `:dev` | Visible in the development story tool. |
| `:docs` | Included in generated documentation pages. |
| `:test` | Run as a test in the test suite (`:play` + `:rf.assert/*`). |
| `:screenshot` | Captured in screenshot/visual-regression runs. |
| `:experimental` | Hidden in production-ish views; visible in dev. |
| `:internal` | Excluded from public-facing docs. |
| `:agent` | Surfaced to AI agents as canonical examples. |

A variant's tags default to `#{:dev :docs}`. Tools intersect their requested tag set with the variant's tags.

```clojure
:tags #{:dev :docs :test :screenshot}      ;; full coverage
:tags #{:dev :experimental}                ;; in dev only, marked experimental
:tags #{:dev :test}                        ;; not in docs (e.g., edge case)
```

### Tags are a registered, queryable vocabulary

Tags are not free-form strings — every tag a project recognises must be **registered** via `story/reg-tag`:

```clojure
(story/reg-tag :dev
  {:doc "Visible in the development story tool."})

(story/reg-tag :auth/regression-set
  {:doc "Variants used in the auth feature's regression suite."})
```

The default tag vocabulary above (`:dev`, `:docs`, `:test`, `:screenshot`, `:experimental`, `:internal`, `:agent`) is registered by the stories library at load time. Project-specific tags must be registered before use. The tag set is **queryable**:

```clojure
(story/registrations :tag)               ;; → all registered tags + their docs
(story/registrations :tag #(contains? (:tags %) :auth))   ;; filtered
```

Tools enumerate this set before assigning tags to a variant. A variant whose tags include an unregistered keyword fails registration with `:rf.error/unknown-tag`. This is the AI-first "public query surfaces" principle ([Principles.md §Public query surfaces](Principles.md#public-query-surfaces)) applied to tag vocabulary.

## Loaders (advanced — async setup)

Loaders run asynchronously before stories render to fetch data. **Deterministic `:events`** are preferred because they're reproducible and replayable. Loaders are an escape hatch for cases that genuinely need async setup (e.g., generating a test image from a remote service).

```clojure
;; The async work lives in a registered event handler; the variant references it by id.
(rf/reg-event-fx :charts.heatmap/fetch-fixture
  (fn [_ _]
    {:fx [[:my-app/http {:url     "/fixtures/heatmap.json"
                         :on-success [:charts/load-fixture]}]]}))

(story/reg-variant :story.charts.heatmap/with-real-data
  {:doc      "Renders against a fixture fetched from disk."
   :loaders  [[:charts.heatmap/fetch-fixture]]              ;; event vector — the handler does the async work
   :events   [[:charts/load-fixture]]
   :tags     #{:dev :docs}})
```

### Loader lifecycle (canonical)

The variant setup phases run in this fixed order:

1. **Loaders.** Each event in `:loaders` is dispatched into the variant's frame. The library waits for the loader's drain to settle (run-to-completion per [002](002-Frames.md)) and any pending fx the loader emitted (e.g., `:rf.http/managed` or a user-supplied HTTP fx) to resolve and dispatch their continuation events. A loader is *complete* when no further events are in flight against the variant's frame.
2. **Events.** Each event in `:events` is dispatch-synced in order, after every loader has completed. By the time `:events` runs, the loaded data is already in `app-db`.
3. **Render.** The view renders against the post-events `app-db`.
4. **Play.** Each event in `:play` is dispatched in order against the now-rendered view (per [§Play functions](#play-functions)).

Hosts that don't have a usable async surface for waiting on loader completion (rare) treat `:loaders` as a synonym for `:events`; the canonical flow is the four-phase sequence above. Mark loaders as advanced in docs. The vast majority of variants should use `:events` only.

## Effect mocking — hook design, not policy

Stubbing HTTP (and similar effects) for stories uses **hooks for per-variant interceptors and fx overrides** — not mocking policy baked into `reg-variant`. The effect being stubbed may be the framework-shipped `:rf.http/managed` (see [014-HTTPRequests](014-HTTPRequests.md)) or a user-supplied fx like `:my-app/http`; the stubbing mechanism is the same.

The framework hooks (at the foundation level — see [002-Frames.md](002-Frames.md)):

- `:on-create` events run at frame creation.
- **Per-frame fx override** — a variant can declare fx replacements active for its frame's lifetime. Available via `reg-frame :fx-overrides` (see [002 §Per-frame and per-call overrides](002-Frames.md)).
- **Per-frame interceptor injection** — a variant can register interceptors that run only for its frame.

Decorators expose these hooks as composable building blocks (`force-fx-stub`, `inject-interceptor`, etc.). Story authors compose decorators; they don't manually wire interceptors. The framework provides the hooks; the decorator library provides the ergonomics.

## Portable into tests

Variants are runnable outside the story UI. The library exposes a function form for each:

```clojure
(story/run-variant :story.auth.login-form/validation-error)
;; → {:frame   :story.auth.login-form/validation-error
;;    :app-db  {...}                        ;; final state after :events + :play
;;    :assertions [{:passed? true ...} ...]
;;    :rendered-hiccup [...]                ;; if :render? true was supplied
;;    :elapsed-ms 12.4}
```

Use cases:

- **Component tests** (`deftest` in CLJS test suites) — call `run-variant`, assert on `:assertions` or `:app-db`.
- **Screenshot tests** — render `:rendered-hiccup` to JSDOM/Playwright, capture image, diff.
- **Tooling input** — pass the variant id to an attached agent or inspector; consumers read `:app-db` and `:assertions` to reason about behaviour.
- **Manual REPL exploration** — call `run-variant` interactively to see what state events produce.

The same data drives every consumer. No artefact duplication.

> **Live-watching a variant.** `(story/watch-variant variant-id)` re-runs the variant whenever any of its dependencies (events, subs, view, schema) re-register. The framework already ships hot-reload notifications; `watch-variant` is a thin library composition over them. Cycle-prevention via registry-version diffing — only re-run when a dependency's registration metadata actually changed.

## Variant snapshot identity

Every variant has a stable **snapshot identity** comprising its `:variant-id` plus a content hash of its serialised body. The hash includes:

- `:events` and `:play` event vectors (in order),
- the resolved (post-`:extends`-merge) args, decorators, and tags,
- the parent story's component id (`:component`) and decorators,
- the *registered* schema digest of the view (per [011 §`:rf/schema-digest`](011-SSR.md)) — so a schema change invalidates the snapshot identity.

The hash is computed over a canonicalised data form (sorted keys, deterministic vector order) so it round-trips across hosts. Visual-regression and screenshot pipelines key against `[variant-id content-hash]` — when the body changes, the hash changes; when the body doesn't change, the hash is stable across runs. The library hook is `(story/snapshot-identity variant-id) → {:variant-id ..., :content-hash "..."}`.

This is the AI-first "machine-readable invariants" principle: tooling comparing before-and-after a code change asks the runtime which variants' snapshot identities changed without re-rendering them.

## Story-tool extension hook

The stories library's tool surface is **extensible by registering panels**. A panel is a registered view with a known kind:

```clojure
(story/reg-story-panel :a11y/inspector
  {:doc       "Accessibility issues for the active variant."
   :title     "Accessibility"
   :placement :right
   :render    :a11y/inspector-view})        ;; id of a reg-view
```

Panels are registered against the story-tool's own registry; the tool reads `(story/registrations :story-panel)` and lays them out. Same shape as everything else in re-frame2 — registry + metadata.

Story maintains its kind-shaped registrations in a tool-owned side-table at `tools.story.registry/*`. This is internal to the `tools/story/` artefact and stays out of production bundles. The bridge fn `story/registrations` exposes the §Public-query-surfaces parity (e.g. `(story/registrations :story)` enumerates the side-table). The framework registrar's closed-kinds discipline ([001-Registration.md](001-Registration.md)) is preserved — Story does not register with `re-frame.registrar`.

### Third-party egress in story tooling (rf2-su313)

Story tooling makes two documented network calls to third-party endpoints:

- **QR-code generation hits `api.qrserver.com`.** The story-tool's "share this variant" affordance posts the current URL to a public QR-rendering endpoint and inlines the returned PNG. User-triggered, off by default unless the dev clicks the action.
- **Axe-core loads from a public CDN.** The accessibility-inspector panel pulls axe-core's runtime from a public CDN rather than bundling it into the story artefact. Story bundles stay small for the a11y-disabled majority; the a11y-using minority takes the runtime CDN hop on first open.

These are **dev-tool conveniences with documented egress, not gated**. Both endpoints are unauthenticated; neither carries app-db state, framework secrets, or variant payloads. Apps that need air-gapped story tooling bundle local replacements on the user side — the story library does not ship a feature flag for swapping them out. Per rf2-su313 and [Security.md §Pragmatic stance](Security.md#pragmatic-stance) ("third-party egress in story tooling — documented, not gated").

> Cross-reference: see [Security.md §Threat model + scope](Security.md#threat-model--scope) — "Third-party egress in dev tooling" is one of the framework's named out-of-scope categories.

## What the framework supplies vs. what the library adds

The 007-Stories contract has a deliberate two-tier shape — what a conformant port MUST ship, and what the port MAY consume from a hosted library (or hosted tool, or third-party catalog browser). Confusing the two yields ports that either over-ship (carry tool-specific UI shell as a normative contract) or under-ship (omit pattern-level surfaces other tools depend on).

### Pattern contract — port MUST ship

Pattern-level surfaces every conformant 007-Stories implementation MUST ship:

- **Framework hooks** (already in 002, listed here for completeness): `make-frame` / `destroy-frame!` / `reset-frame!`; per-frame `:fx-overrides` / `:interceptor-overrides` / `:interceptors`; run-to-completion drain; the public registrar query API (`registrations` / `frame-meta` / `frame-ids` / `get-frame-db` / `snapshot-of` / `sub-topology`); hot-reload notifications. These are 002's contract and 007 inherits them; a port that ships 002 ships them.
- **Story registry kinds.** The kinds `:story`, `:variant`, `:workspace`, `:story.decorator`, `:story.tag`, `:story.mode`, `:story-panel` (per [§Canonical id grammar](#canonical-id-grammar)) — registration shape, metadata grammar, the four-phase setup ordering (loaders → events → render → play), and the `:variant *is* a frame` identity (per [§Relationship with frames](#relationship-with-frames)).
- **Lifecycle event surface.** The trace events fired by variant setup, render, and play execution — including the `:rf.assert/*` family per the Story event-family contract — are pattern contract so cross-tool consumers (Causa, snapshot harnesses, headless test runners) can attach uniformly.
- **Programmatic execution + assertion surface.** `story/run-variant`, `story/reset-variant`, `story/variants-with-tags`, `story/snapshot-identity` — these are the API the [Story-as-test duality](#story-as-test-duality) leans on, and a port that omits them breaks the round-trip with `008-Testing`.
- **Variant snapshot identity** (per [§Variant snapshot identity](#variant-snapshot-identity)) — the hash function that names a variant's settled state independently of UI affordances. Visual-regression / golden-snapshot tools key on this; the contract is locked across ports.

A port can ship every pattern-contract surface above without shipping a single line of catalog-browser UI; the surface is purely the data + lifecycle contract.

### Implementation discretion — port MAY consume

Surfaces a conformant port MAY consume from a host library, third-party catalog browser, or in-house tool, rather than ship itself:

- **The catalog-browser UI shell.** The browseable index of registered stories / variants / workspaces, the per-variant render preview, the args-control panel, the tag-filter sidebar — all UI affordances are tool-discretionary. The CLJS reference ships one (the `re-frame.story` library's story-tool UI in the `tools/story/` artefact); other ports MAY ship a different shell, embed into a host like Storybook / devcards / Workspaces via an adapter shim (per [§Devcards / Workspaces interop](#devcards--workspaces-interop-post-v1-rf2-9amwm)), or omit a UI entirely and consume the pattern-contract surface from tests only.
- **MDX / markdown wrappers, doc-tooling integration.** Annotating stories with prose, embedding them in a docs site, generating screenshots — all post-processing patterns that consume the pattern-contract surface. None are normative.
- **The story-tool extension hook** (`tools.story.registry/*` side-table registries, `story/reg-story-panel`, per [§Story-tool extension hook](#story-tool-extension-hook)) — pattern contract names the registry kinds and the registration shape; the *tool* that consumes the side-table to render extension panels is implementation discretion. Story-MCP and Causa each consume it differently; both are valid.
- **Visual-regression / screenshot integration** (per the [resolved decision](#screenshot--visual-regression-integration)) — pattern contract reserves `[:story.snapshot/*]` event ids; the *runner* that takes the snapshot, diffs against golden, and reports is tool discretion.

### Reading the split

The test is "would this need re-defining for another port to interoperate?" — if yes, it's pattern contract; if no, it's tool discretion. The framework surface above is sufficient for any team to roll their own equivalent tool; the discretionary list names what the team is free to vary without breaking interop with the wider re-frame2 ecosystem.

## Relationship with frames

A variant *is* a frame, registered under its variant keyword. But variant `:events` are NOT desugared to `reg-frame :on-create` — `reg-frame :on-create` is single-event by design ([002 §reg-frame](002-Frames.md#reg-frame--atomic-create-and-register-and-the-canonical-metadata-grammar)), while variant `:events` is an explicitly multi-step setup sequence (the whole point of stories is to express setup as a list of user-flavoured steps). The story library handles its own iteration, in the four-phase order locked above:

```clojure
;; conceptual setup logic for story/reg-variant
(defn setup-variant! [variant-id]
  (let [{:keys [loaders events]} (variant-meta variant-id)
        story-events             (story-events-for variant-id)
        all-events               (concat story-events events)]
    (rf/reg-frame variant-id {:doc ...})              ;; frame starts with app-db = {}
    (doseq [ev loaders]                               ;; phase 1 — async loaders
      (rf/dispatch-sync ev {:frame variant-id})
      (await-loader-drain variant-id))
    (doseq [ev all-events]                            ;; phase 2 — :events (incl. story-level)
      (rf/dispatch-sync ev {:frame variant-id}))
    (record-variant-meta variant-id {:view ..., :decorators ..., :play ..., :tags ...})))
;; phase 3 (render) and phase 4 (play) happen later, driven by the host.
```

So the variant's *frame* is a normal frame (no `:on-create`); the variant *library* handles the multi-event setup. This keeps `reg-frame :on-create` semantically simple (one event) while letting stories express their richer setup pattern.

Workspaces are *not* frames (or not necessarily — they may be ordinary frames containing nested `frame-provider`s, one per included variant). Each variant included in a workspace renders inside its own `frame-provider`, isolated from siblings. This falls out of 002's design without extra machinery.

## Open questions

> **SA-4 classification (rf2-p6xyh).** Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md): "Workspaces — generic or specialised?" classifies as **`:resolved`** (the inline `:layout`-field framing IS the answer — migrated to `## Resolved decisions` below); "Story composition across libraries" classifies as **`:resolved`** (the inline "story tool reads all registered `:story.*` ids" framing IS the answer — migrated to `## Resolved decisions` below); "Devcards / Workspaces interop" classifies as **`:post-v1 tracked`** at rf2-9amwm (adapter shim, deferred).

### Devcards / Workspaces interop (post-v1, rf2-9amwm)

Existing CLJS projects using devcards or other workspace tools should be able to consume re-frame2 stories with adapter shims. Deferred to rf2-9amwm.

#### Post-v1 Tracking — rf2-9amwm

- **Foundation in v1.** The variant id surface (`:story.<ns>/<variant>`) is stable; the registry is readable via `rf/variants` (per the story registry shape); rendered hiccup is a plain value.
- **Scope deferred.** A thin adapter shim per host tool — devcards (`defcard` wrapping a `story/run-variant` call) and nubank/workspaces (workspace card from variant id) are the obvious first targets. No story-side change required.
- **Reconsideration trigger.** A downstream project migrating from devcards/workspaces asks for the shim, or the story tool's own UI needs an embeddable card form.
- **Out of scope for the bead.** Reverse direction (rendering a devcard inside a story workspace) — devcards' macro-time registration model doesn't compose cleanly with story's variant registry.

## Resolved decisions

### Should `story/reg-story` and `story/reg-variant` be separate, or unified?

**Both forms, with the combined form desugaring to separate registrations.** `(story/reg-story :id metadata)` + N `(story/reg-variant :story-id/variant-id metadata)` is the canonical pair. The combined `:variants {...}` map on `story/reg-story` is sugar that desugars at macro-expansion time to N independent `story/reg-variant` calls — hot-reload-by-variant still works. See [§Combined `story/reg-story` form](#combined-storyreg-story-form--a-sugar-that-desugars).

### Args mapping — view-direct or via app-db?

Args go to the view directly by default; explicit `:args->events` for variants that need state changes. Simple cases stay simple; complex cases have an opt-in mechanism.

### Test integration — built-in runner or test-framework adapter?

The story library ships a `story/run-variant`-flavoured runner. Test-framework adapters (`re-frame-test`, etc.) consume `story/run-variant` and produce framework-specific test cases. The built-in runner is part of the story library, not the framework; adapters layer on top of it.

### Screenshot / visual-regression integration

The library hook is: variants have a stable snapshot identity (`:variant-id` + content hash) per [§Variant snapshot identity](#variant-snapshot-identity). Specific visual-regression service integrations consume the variant registry, `story/run-variant`'s rendered hiccup, and the snapshot identity.

### Workspaces — generic or specialised?

A `:layout` field with the closed set `:grid`, `:prose`, `:tabs` etc. covers common cases. Custom layouts are just custom views referencing variant ids — no specialised primitive is needed.

### Story composition across libraries

Multiple `:story.*` namespaces can come from different libraries. The story tool reads every registered `:story.*` id at runtime — composition is automatic via the shared registry; no per-library wiring is needed.

## See also

- [`tools/story/`](https://github.com/day8/re-frame2/tree/main/tools/story) — the reference implementation of this spec (`day8/re-frame2-story`).
- [`tools/story/spec/`](https://github.com/day8/re-frame2/tree/main/tools/story/spec) — the implementation contract (decisions, runtime shape, elision, MCP boundary).
- [`tools/story-mcp/`](https://github.com/day8/re-frame2/tree/main/tools/story-mcp) — the agent-facing MCP server (`day8/re-frame2-story-mcp`).
- [Story tutorial](../docs/story/index.md) — the narrative walkthrough of this spec.
- [`tools/story/testbeds/counter_with_stories/`](https://github.com/day8/re-frame2/tree/main/tools/story/testbeds/counter_with_stories) — the worked example pivoting on the counter from guide chapters 03–10 (rf2-p8f2s — relocated from `examples/reagent/` as the tool's testbed).
