# re-frame2-story — Implementation Specification

> Stage 1 of [`rf2-u6fb`](../../) (parent epic). Bead **`rf2-2hir`**.
> Artefact: `day8/re-frame2-story` (ships from `tools/story/`).
> Agent surface: `day8/re-frame2-story-mcp` (separate jar at `tools/story-mcp/`).
>
> **Normative contract:** [`spec/007-Stories.md`](../../spec/007-Stories.md).
> This IMPL-SPEC sits *below* the spec — it is the engineering contract the
> Stage 2–8 implementation work commits against. Where the spec is
> intentionally open ("Mike to decide"), this document locks it.
>
> **Research lineage:**
> - Phase 1 — `findings/re-frame-2-story-feature-set.md` (rf2-m6tu, ~5,200 words).
> - Phase 2 — `findings/re-frame-2-story-sota-refinement.md` (rf2-94b0, ~5,300 words).
> - Architectural decisions resolved 2026-05-11 (Mike-delegation; §2 records each).

---

## §1 Purpose + scope

### 1.1 What re-frame2-story is

re-frame2-story is the **component-development tool** for re-frame2 apps. It
takes the primitives the rest of re-frame2 already exposes — frames, events,
subscriptions, effects, schemas, traces, epochs — and arranges them as a
Storybook-class interactive playground. The unit of design is a three-way
split locked in [Spec 007 §The three concepts](../../spec/007-Stories.md):

- **Story** — a topic / component / slice. Shared fixtures.
- **Variant** — a concrete scenario of a story; renders the story in a
  specific state.
- **Workspace** — a layout that arranges stories and variants on screen for
  browsing, documentation, or comparison.

Story sits on top of [Spec 002 — Frames](../../spec/002-Frames.md): each
variant *is* a frame. Story sits on top of [Spec 010 — Schemas](../../spec/010-Schemas.md):
controls auto-derive from registered view schemas. Story sits on top of
[Spec 009 — Instrumentation](../../spec/009-Instrumentation.md): the trace
panel consumes `register-trace-cb!` against the variant's frame. Story sits
on top of [Spec 008 — Testing](../../spec/008-Testing.md): `run-variant` is
the test-runner ingress for stories used as tests.

Story owns **no new framework primitives.** Every registry it uses
(`:story`, `:variant`, `:workspace`, `:story-panel`, `:tag`, `:mode`,
`:decorator`) registers via existing `reg-*` machinery. This is required by
the [downstream-EPs-consume-foundation](../../AGENTS.md) discipline.

### 1.2 What re-frame2-story is for

- **Visual development.** Iterate on a component in isolation; see every
  state side-by-side; flip between substrates (Reagent, UIx, Helix,
  reagent-slim) when the view is substrate-portable.
- **Test fixtures.** A `:test`-tagged variant *is* a complete component
  test; `(run-variant id)` returns `{:frame :app-db :assertions :rendered-hiccup
  :elapsed-ms}` — exactly what a `deftest` needs.
- **Documentation.** A `:docs`-tagged variant is included in the generated
  docs page for that story; story tool reads `:doc` + schemas to emit an
  auto-docs table.
- **Agent input.** The MCP surface (separate jar; see §7) exposes the
  registry to AI agents so they can discover components, generate variants,
  run tests, and self-correct.
- **Visual-regression keying.** Every variant has a content-hashed
  `snapshot-identity`; downstream pixel-diff services (Chromatic, Percy,
  Argos, BackstopJS) key against `[variant-id content-hash]` and skip
  unchanged variants in O(1).

### 1.3 What re-frame2-story intentionally isn't

- **A visual-regression service.** Story ships the `snapshot-identity` hook
  and emits stable iframes; pixel capture and diff happen downstream.
- **A reimplementation of re-frame-10x.** Story embeds 10x's epoch panel as
  a registered story panel (§2.7). The two artefacts share the epoch buffer.
- **A statechart visualisation engine.** Story ships a one-line current-state
  indicator for active machines; full chart rendering lives in a future
  `day8/re-frame2-machines-viz` artefact (per Phase 1 §6.8) which exposes a
  `reg-story-panel` adapter Story consumes.
- **An MCP server in-process.** The agent surface is a separate jar with
  its own stdio + JSON-RPC machinery. Story's runtime exposes the *data*
  the MCP server reads; the MCP server is not loaded by app code.
- **A static-site generator** (deferred to v2 — see §11).
- **A pixel-scrubbing UI.** BackstopJS-style before-after slider is out of
  scope; the epoch scrubber inside 10x's embedded panel is the equivalent
  in re-frame2's data-space.

### 1.4 Cross-references

| Concern | Source |
|---|---|
| Normative spec | [`spec/007-Stories.md`](../../spec/007-Stories.md) |
| Frame primitive | [`spec/002-Frames.md`](../../spec/002-Frames.md) |
| Testing surface | [`spec/008-Testing.md`](../../spec/008-Testing.md) |
| Trace API | [`spec/009-Instrumentation.md`](../../spec/009-Instrumentation.md) |
| Schemas → argtypes | [`spec/010-Schemas.md`](../../spec/010-Schemas.md) |
| Snapshot-identity schema digest | [`spec/011-SSR.md`](../../spec/011-SSR.md) |
| Registration grammar | [`spec/001-Registration.md`](../../spec/001-Registration.md) |
| Reserved prefixes | [`spec/Conventions.md`](../../spec/Conventions.md) |
| Tool-Pair primitives | [`spec/Tool-Pair.md`](../../spec/Tool-Pair.md) |
| Tools layout | [`tools/README.md`](../README.md) |

---

## §2 Architectural decisions (LOCKED)

The seven open questions from Phase 1 §6 (rf2-m6tu) are resolved as follows.
Each is binding for Stage 2–8.

### 2.1 MCP server — separate jar

**Decision.** The MCP agent surface ships as `day8/re-frame2-story-mcp` at
`tools/story-mcp/`. The Story core jar (`day8/re-frame2-story`) carries
**no** stdio / JSON-RPC dependency.

**Rationale.** The MCP server depends on transport machinery (stdio adapter,
JSON-RPC framing, asynchronous-handler runtime) that the vast majority of
Story consumers never load. Splitting at the jar boundary keeps the Story
core lean and lets the MCP surface evolve on its own cadence. The pattern
mirrors `tools/machines-viz/` vs. `tools/machines-viz-mcp/` (per
[`tools/README.md`](../README.md)).

**Implication.** Story's core jar exposes the *read* primitives the MCP
server consumes (`handlers`, `frame-meta`, `run-variant`, `snapshot-identity`,
`variant->edn`); the MCP server packages them as tools per the Storybook MCP
Dev / Docs / Testing toolset split (§7).

### 2.2 Substrate-specific failures — render inline

**Decision.** A variant may declare `:substrates #{:reagent :uix :helix
:reagent-slim}` (subset, default = the host frame's adapter); when Story
renders the variant against multiple substrates the failure for each is
rendered **inline** with the variant pane — not auto-skipped.

**Rationale.** Substrate-portability gaps are the entire point of
multi-substrate rendering. Hiding failures hides the bugs Story exists to
surface. Stage 1 of any substrate-portability audit is "look at the red
panes."

**Implication.** Stage 4 (render shell) treats per-substrate render in a
try/catch boundary; per-variant `:assertions` accumulates a substrate-tagged
failure entry; the multi-substrate pane shows the error inline alongside the
healthy substrates' renders.

### 2.3 Assertion failure mode — record, don't throw

**Decision.** `:rf.assert/*` events **record** failures into the variant's
`:assertions` list and continue the play sequence. They do not throw.

**Rationale.** Play sequences run to completion; the full picture of "what
went wrong" is more useful than "first failure halts everything." Aligns
with re-frame's run-to-completion drain semantics (Spec 002). Mirrors
devcards' behaviour; diverges from Storybook (which throws). Storybook's
choice is constrained by JavaScript's async-throw mess; we have no such
constraint.

**Implication.** Each `:rf.assert/*` handler returns a map describing the
assertion result; the play-runner concatenates these into the variant's
`:assertions` list. `run-variant`'s test-runner adapter (Stage 5)
post-processes the `:assertions` list and translates failures into the
host test framework's failure signal — `cljs.test`'s `is`, kaocha's
reporter, etc.

### 2.4 Loader completion for long-lived effects — `:loaders-complete-when` predicate

**Decision.** Variant body may include an optional
`:loaders-complete-when` event predicate. Default behaviour:

- HTTP-flavoured fx (request → success/failure dispatch) is "complete" when
  the response event has been dispatch-synced.
- Long-lived fx (`:websocket`, `:interval`, `:firestore`, etc.) is "complete"
  when the **first message arrives** (i.e. the first event the fx dispatches
  into the frame is received). After that the loader is considered complete
  and `:events` proceeds.
- Authors override either default via `:loaders-complete-when` — a
  vector-of-event-vectors or a registered predicate-event id; the predicate
  is invoked after each event drain settles; truthy result means
  loaders-complete.

**Rationale.** The spec/007 §Loader-lifecycle phrasing — "no further events
are in flight against the variant's frame" — works for request/response fx
but never satisfies for long-lived fx. The default-plus-override design
keeps simple cases simple and makes the long-lived case explicit.

**Implication.** Stage 3 (runtime) implements the four-phase lifecycle
(loaders → events → render → play) with the loader-complete check after
each loader's drain. The default predicate is "first non-loader event seen
by the frame, or loader's drain settles with no in-flight fx, whichever
comes first." Authors override via the variant body. Stage 2 macro
validates that `:loaders-complete-when` resolves to a registered event id
or is a literal data form (vector of event vectors).

### 2.5 Workspace persistence — both modes

**Decision.** Workspace layouts persist **both** ways:

- **Default: local-storage.** Interactive rearrangements (drag-resize
  panes, reorder variants in a grid) auto-save to local storage keyed by
  `[workspace-id breakpoint]`. Persistence is per-user, per-browser.
- **Save-as registered artefact.** A "Save layout as `:Workspace.x/y`"
  button serialises the current layout to transit (full
  `reg-workspace` body), re-registers it under the chosen id, and exports
  the transit blob for cross-machine sharing. Other users `(rf/reg-workspace
  ...)` the transit body to consume the layout durably.

**Rationale.** Nubank's workspaces ships both for the same reason: ephemeral
edits should not require a registration ceremony; deliberate-share edits
need a durable artefact. Local-storage is "where am I right now"; transit-
exported is "this is the team layout for Friday's review."

**Implication.** Stage 4 (render shell) wires the local-storage save on
every layout change; Stage 4 also adds the "Save layout" affordance.
`tools.story.workspace.transit/workspace->edn` returns the serialised form.

### 2.6 `:extends` DCE under `:advanced` — registration is dev-only

**Decision.** `reg-story`, `reg-variant`, `reg-workspace`, `reg-mode`,
`reg-story-panel`, `reg-decorator`, and `reg-tag` are **all dev-only**.
Under `:advanced` compiler builds, all seven macros elide to `nil`.

**Rationale.** Cross-library `:extends` (where lib A registers
`:story.x/parent` and lib B has `:extends :story.x/parent`) becomes
irrelevant in production builds because no registrations exist. There is
no consumer code that cares about a story registration *at runtime in
production* — Story is a dev tool. Eliding the registration form-by-form
collapses an entire class of "this won't dead-code-eliminate cleanly" bugs.

**Implication.** Stage 2 macros expand to one form in dev mode (the
registration call) and to `nil` in production. This follows the Spec 009
"sentinel pattern": PRESENT-in-control, ABSENT-in-release. The compile-time
flag is `goog-define :rf.story/enabled?` (default `true`; downstream apps
override to `false` for prod builds). See §6 for the elision contract in
detail.

### 2.7 re-frame-10x integration — embed via `reg-story-panel`

**Decision.** Story does **not** reimplement an epoch UI. Story registers
re-frame-10x's existing epoch panel as a story panel via:

```clojure
(rf/reg-story-panel :rf.story/10x-epoch
  {:doc       "re-frame-10x's epoch buffer for the active variant."
   :title     "Epochs (10x)"
   :placement :bottom
   :render    :re-frame-10x.epoch-panel/view})
```

The 10x epoch view is consumed from `day8/re-frame2-10x` (per the
`tools/10x/` alpha-phase line in [`tools/README.md`](../README.md)). Story's
panel is the **adapter**; 10x stays its own artefact, on its own release
cadence.

**Rationale.** The epoch panel's UX (time-travel scrubber, app-db follower,
event replay) is already best-in-class inside re-frame-10x. Reimplementing
it inside Story would (a) double the implementation surface, (b) split
the maintenance work, (c) drift over time. Embedding keeps one source of
truth.

**Implication.** Story's `:rf.story/10x-epoch` registration ships with
v1 but the panel only activates if `day8/re-frame2-10x` is on the
classpath (per the late-bind hook in spec/002). If 10x is absent, the
sidebar entry hides. The 10x artefact owns the actual view; Story owns the
*integration*.

### 2.8 Decisions surfaced during IMPL-SPEC drafting

These decisions emerged while writing this document; flagged for Mike's
review per the "Mike's delegation extends to additional decisions
surfaced during this stage" instruction.

**2.8.1 Story's UI shell substrate — Reagent at v1.** The Story tool's own
chrome (sidebar, control panel, trace ribbon, etc.) is rendered using
Reagent (`implementation/adapters/reagent/`). reagent-slim is still landing
(rf2-5djt); Story should not block on it. Stage 8 may revisit and migrate
once reagent-slim is GA.

**2.8.2 Public ns root — `re-frame.story` for user-facing API.** All public
`reg-*` macros and the `run-variant` family live under `re-frame.story`.
Internal namespaces live under `tools.story.*` (see §8 for the layout).
This matches the convention from `re-frame.adapter.reagent`,
`re-frame.ssr`, etc.

**2.8.3 `reg-mode` ships in v1.** Per Phase 2 §5.2 #3, the Chromatic-style
mode primitive (saved tuples of global args) lands in v1 — not v1.1 — because
the implementation cost is small (it's a saved `args` map plus a
snapshot-identity contribution) and the agent-integration benefit is large
(MCP can iterate variants × modes without combinatorial registration).

**2.8.4 The `:variants-grid` workspace layout ships in v1.** Per Phase 2
§5.2 #4. devcards-style multi-variant viewing has no JS competitor; the
implementation cost is layout-only.

**2.8.5 QR code in share menu — v1 polish.** Per Phase 2 §5.2 #6. Tiny
implementation, high signal. Adds `qr-code` dep.

**2.8.6 Layout-debug trio (measure / outline / pseudo-state) ships in v1.**
Per Phase 2 §5.2 #2. DOM-mutating utility; framework-agnostic; cheap.

**2.8.7 Perf ribbon ships in v1.1.** Per Phase 2 §5.2 #1. Live FPS/INP/CLS/
memory + Reagent-render-profiling at 50ms refresh; non-trivial implementation
(requires `PerformanceObserver`, frame-loop sampler, Reagent profile hooks).
Defer to first follow-up release.

**2.8.8 Design-token panel ships in v1.1, conditional.** Per Phase 2 §5.2
#5. Iff `re-com` or the host design system emits Style-Dictionary-shaped
tokens. Stage 6 ships the panel; activation is conditional on token
emission upstream.

**2.8.9 The 10x embed registration ships in v1 but stays inert if 10x
absent.** Per §2.7. The `reg-story-panel` call is unconditional; the panel's
`:render` resolves via late-bind to a hidden no-op if 10x isn't present.
This keeps the user experience graceful when 10x isn't on the classpath.

If any of 2.8.1–2.8.9 reads wrong, flag the bead before Stage 2 starts.

---

## §3 Public API surface

This is the contract Stages 2–8 implement against. Every entry below is
locked at signature; the IMPL details (which ns, what private helpers)
land per-stage.

### 3.1 Registration macros (all DCE under `:advanced`; see §6)

All registration macros live under `re-frame.story` and shadow the existing
`re-frame.core/reg-*` calling convention.

#### `(reg-story id metadata)` and the combined form

```clojure
(reg-story id metadata)
```

| Slot | Shape | Notes |
|---|---|---|
| `id` | keyword in `:story.<dotted-path>` namespace | Per spec/007 §Canonical id grammar. |
| `metadata` | map | Body shape below. |

Metadata keys:

```clojure
{:doc        "..."                 ; string
 :component  reg-view-id           ; keyword id of a registered :view
 :decorators [[:dec-id args ...]]  ; vector of decorator vectors (id-valued)
 :args       {<arg-key> <value>}   ; story-level defaults
 :argtypes   {<arg-key> {...}}     ; control spec; auto-derived from :component's schema
 :tags       #{:dev :docs ...}     ; subset of registered tags
 :modes      #{<mode-id> ...}      ; saved-tuple modes (§3.5)
 :substrates #{:reagent :uix ...}  ; default substrate set for variants
 :platforms  #{:server :client}    ; SSR opt-in per spec/007 §:platforms
 :variants   {<variant-name> <variant-body>}}  ; Form B (sugar) — see §4
```

`:variants` is the Form-B combined-form sugar. The macro desugars to N
independent `reg-variant` calls at expansion time so hot-reload-by-variant
works (per spec/007 §Combined `reg-story` form).

#### `(reg-variant id metadata)`

```clojure
(reg-variant id metadata)
```

| Slot | Shape | Notes |
|---|---|---|
| `id` | keyword `:story.<path>/<variant>` | Per spec/007. |
| `metadata` | map | Body shape below. |

Body shape (locked at spec/007 §Variant artefact contract — no fn-slots):

```clojure
{:doc                   "..."
 :extends               <variant-id>             ; parent variant; merged at registration
 :events                [[:event-id args ...]]   ; setup events; phase 2
 :play                  [[:event-id args ...]]   ; post-render events; phase 4
 :args                  {<arg-key> <value>}      ; override/extend story args
 :argtypes              {<arg-key> {...}}        ; override story argtypes
 :tags                  #{:dev :docs ...}        ; subset of registered tags
 :decorators            [[:dec-id args ...]]
 :loaders               [[:loader-event-id ...]] ; async setup; phase 1
 :loaders-complete-when <pred>                   ; §2.4 — vector or registered event-id
 :args->events          {<arg-key> <event-id>}   ; arg → app-db mapping (spec/007 §Args mapping)
 :platforms             #{:server :client}
 :substrates            #{:reagent :uix ...}
 :modes                 #{<mode-id> ...}}        ; cell = (variant × mode)
```

The `:rf/variant` schema (in `spec/Spec-Schemas.md`) enforces the
no-fn-slots rule. Stage 2 macros validate the body against this schema and
reject with `:rf.error/variant-shape` on miss.

#### `(reg-workspace id metadata)`

```clojure
(reg-workspace id metadata)
```

| Slot | Shape | Notes |
|---|---|---|
| `id` | keyword `:Workspace.<path>/<name>` | Per spec/007. |
| `metadata` | map | Body shape below. |

Body:

```clojure
{:doc      "..."
 :layout   :grid | :prose | :variants-grid | :tabs | :custom
 :variants [<variant-id> ...]                    ; for :grid / :variants-grid / :tabs
 :content  [{:type :prose :body "md..."} ...]    ; for :prose
 :render   <view-id>                              ; for :custom (a registered view)
 :modes    #{<mode-id> ...}}
```

The `:variants-grid` layout (§2.8.4) renders every variant of a single
parent story side-by-side; it differs from `:grid` (which renders an
explicit `:variants` list) by enumerating variants from the registry.

#### `(reg-decorator id metadata)`

```clojure
(reg-decorator id metadata)
```

| Slot | Shape | Notes |
|---|---|---|
| `id` | keyword | Decorator id. |
| `metadata` | map | Body shape below. |

Body (one of three kinds per spec/007):

```clojure
;; Hiccup wrapper
{:doc  "..."
 :kind :hiccup
 :wrap (fn [body args] [:div ... body])}        ; ONLY closure allowed at registration site

;; Frame setup
{:doc  "..."
 :kind :frame-setup
 :init [[:event-id args ...]]                    ; events to dispatch into the variant's frame
 :app-db-patch {<path> <value>}}                 ; or a static app-db patch

;; Fx override (force-fx-stub)
{:doc  "..."
 :kind :fx-override
 :fx-id     <fx-id>                              ; the fx the decorator stubs
 :response  <data>}                              ; the stubbed response
```

**Closure caveat.** A decorator's `:wrap` slot *is* a function — but it
lives at the *decorator registration site*, not the variant body. Variant
bodies reference decorators by id; the closure is registered once and
shared. The "variant body is pure data" rule is preserved.

#### `(reg-story-panel id metadata)`

```clojure
(reg-story-panel id metadata)
```

Body:

```clojure
{:doc          "..."
 :title        "Display name"
 :placement    :right | :left | :bottom | :top | :modal
 :render       <view-id>                            ; registered :view that renders the panel
 :for          #{<context-id>}                     ; optional — restrict to specific story-tool contexts
 :enabled-when (optional)}                          ; optional registry-predicate fn
```

Per spec/007 §Story-tool extension hook. Stage 4 (render shell) reads
`(rf/handlers :story-panel)` and lays them out; Stage 6 (rf2-zhwd) adds
the v1.0 panel set + the tooling-embed contract below.

##### Panel-registration contract (Stage 6 lock)

Per IMPL-SPEC §4.5 the `reg-story-panel` surface is the **single hook**
through which tooling embeds itself into the Story chrome — no new
registry kind, no parallel mounting protocol. The contract:

1. **`:render` is a `:view` id.** The shell renders the panel by calling
   `(rf/view <render-id>)` and invoking the resolved fn with the current
   `variant-id`. Late-bind: the actual view can register from a
   different artefact (e.g. `day8/re-frame2-10x` for the epoch panel)
   so long as the same `:view` id is registered before the user opens
   the panel.

2. **Placement is one of five slots.** `:right` / `:left` / `:bottom`
   / `:top` host the panel inline; `:modal` opens it over the canvas.
   Each placement is an independent host; multiple panels at the same
   placement stack in id order.

3. **Visibility flows through `:panel-visibility`.** The shell state's
   `:panel-visibility` map (keyed by panel id) is the on/off switch.
   Unspecified → visible; explicit `false` → hidden. Users toggle via
   the chrome's panel-list affordance.

4. **Author calls `reg-story-panel` from anywhere on the classpath.**
   Stage 6's built-ins (`:rf.story.panel/a11y` /
   `:rf.story.panel/layout-debug` / `:rf.story.panel/epoch`) register
   from inside `install-canonical-vocabulary!`; third-party tooling
   (e.g. Causa's epoch view, future statechart-viz panels) registers
   from its own boot.

5. **The 10x embed.** Per IMPL-SPEC §2.7 + §2.8.9: Stage 6 ships
   `:rf.story.panel/epoch` registered against a STUB view. The Causa
   library (`tools/10x/`, rf2-buor) registers the live view under the
   same `:rf.story.panel/epoch-view` id when present; the shell's
   late-bind `rf/view` lookup picks Causa's view automatically. If
   Causa is absent the stub renders documenting the contract.

#### `(reg-tag id metadata)`

```clojure
(reg-tag id metadata)
```

Body:

```clojure
{:doc "..."}
```

The seven canonical tags (`:dev`, `:docs`, `:test`, `:screenshot`,
`:experimental`, `:internal`, `:agent`) register at Story load. Project
tags must be registered before use. An unregistered tag on a variant's
`:tags` set raises `:rf.error/unknown-tag` at registration.

`!`-prefix removal syntax: `:!dev` removes `:dev` from the inherited tag
set when included in a child variant's `:tags`. Per Phase 1 §2.1
Storybook-borrowed ergonomic.

#### `(reg-mode id metadata)`

```clojure
(reg-mode id metadata)
```

Body:

```clojure
{:doc  "..."
 :args {<arg-key> <value>}}                     ; the saved-tuple
```

Example:

```clojure
(rf/reg-mode :Mode.app/dark-mobile
  {:doc  "Dark theme on a mobile viewport."
   :args {:theme :dark :viewport :mobile :locale :en}})

(rf/reg-mode :Mode.app/light-desktop
  {:args {:theme :light :viewport :desktop :locale :en}})
```

When a variant is rendered against mode M, M's `:args` deep-merge into the
variant's effective args (precedence: global < mode < story < variant).
Each `(variant × mode)` cell has an independent `snapshot-identity` — see
§5.6.

### 3.2 Programmatic runtime

```clojure
(run-variant variant-id)
(run-variant variant-id {:render? true :mode :Mode.app/dark :substrate :reagent})
;; => {:frame           <variant-id>
;;     :app-db          {...}
;;     :assertions      [{:assertion :rf.assert/path-equals :passed? true ...} ...]
;;     :rendered-hiccup [...]                    ; only if :render? true
;;     :elapsed-ms      <number>
;;     :snapshot        {:variant-id ..., :mode ..., :substrate ..., :content-hash "..."}}
```

`run-variant` runs the four-phase lifecycle:
1. Allocate (or `reset-frame`) the variant's frame.
2. Run `:loaders` (phase 1), wait for `:loaders-complete-when` predicate.
3. Run `:events` (phase 2).
4. Optionally render (phase 3) and run `:play` (phase 4).
5. Tear down or persist per opts.

`run-variant` returns synchronously when no loaders are present and all
fx in `:events` are synchronous; otherwise returns a promise-like object
the host can await. The exact async return-shape is Stage 3's call;
candidates: a Promise (CLJS), or a manifold.deferred (CLJ-side bridge if
needed). Stage 3 picks one and locks it.

```clojure
(reset-variant variant-id)                       ; tear down + re-run :loaders + :events
(watch-variant variant-id)                       ; re-run on dep re-registration
(unwatch-variant variant-id)
(variants-with-tags tags)                        ; query — returns coll of variant ids
(variant->edn variant-id)                        ; canonical-form serialised body
(workspace->edn workspace-id)                    ; same, for workspace layouts
(snapshot-identity variant-id)
(snapshot-identity variant-id {:mode ... :substrate ...})
;; => {:variant-id ..., :mode ..., :substrate ..., :content-hash "..."}
```

### 3.3 Effects (fx) registered by Story

| Fx id | Payload | Notes |
|---|---|---|
| `:story/set-arg` | `{:variant <id> :key <k> :value <v>}` | Dispatched by control widgets when args change. |
| `:story/run-play` | `{:variant <id>}` | Run the play sequence (used by play-stepper). |
| `:story/reset` | `{:variant <id>}` | Reset variant to post-events baseline. |
| `:story/save-layout-as` | `{:workspace <id> :body <transit>}` | Persist the active layout as a registered workspace. |

### 3.4 Coeffects (cofx) registered by Story

| Cofx id | Shape | Notes |
|---|---|---|
| `:story/mode` | `<mode-id>` | The active mode for the variant; useful in mode-aware events. |
| `:story/substrate` | `:reagent`, `:uix`, ... | The active substrate. |

### 3.5 Assertion events (canonical vocabulary)

Per spec/007 §Assertion vocabulary the canonical seven register at Story
load. Each is a regular `reg-event-fx` against the variant's frame. All
**record** results into `:assertions` (per §2.3) rather than throwing.

| Event id | Payload | Semantics |
|---|---|---|
| `:rf.assert/path-equals` | `[path expected]` | `(= (get-in @app-db path) expected)` |
| `:rf.assert/path-matches` | `[path malli-schema]` | `(m/validate schema (get-in @app-db path))` |
| `:rf.assert/sub-equals` | `[sub-vec expected]` | `(= @(subscribe sub-vec) expected)` |
| `:rf.assert/dispatched?` | `[event-vec]` | Was this event dispatched against this frame? |
| `:rf.assert/state-is` | `[machine-id state]` | Active state of `reg-machine` machine-id is state. |
| `:rf.assert/no-warnings` | `[]` | No `:rf.warn/*` events seen during play. |
| `:rf.assert/effect-emitted` | `[fx-id]` (optional `pred`) | Did the variant's drain emit fx-id? |

Each handler returns a map of the form:

```clojure
{:assertion :rf.assert/path-equals
 :payload   [[:auth :status] :authenticated]
 :passed?   true
 :actual    :authenticated
 :expected  :authenticated
 :source    {:file "..." :line ...}}             ; from source-coord stamping (§9)
```

The play-runner collects these into `:assertions`. The list survives the
`run-variant` return.

### 3.6 Substrate hooks

```clojure
(rf/variant-substrates variant-id)
;; => #{:reagent :uix :helix :reagent-slim}  (or a subset, per :substrates on variant/story)
```

The story tool's multi-substrate pane iterates this set, rendering each;
substrate-specific failures show inline (§2.2).

### 3.7 Tag-vocabulary queries

```clojure
(rf/handlers :tag)                               ; all registered tags
(rf/handlers :tag #(contains? (:tags %) :auth))  ; filtered
```

Already framework-supplied via spec/001 registrar query API; Story
registers the seven canonical tags at load.

---

## §4 Authoring grammar (variant shape)

Worked examples illustrating every aspect of the authoring grammar.

### 4.1 Minimal example

```clojure
(ns app.stories.button
  (:require [re-frame.story :as story]
            [app.ui.button :refer [button]]))

(story/reg-story :story.ui.button
  {:doc       "Primary action button."
   :component button
   :args      {:label "Click me"}})

(story/reg-variant :story.ui.button/default
  {:doc "Default state."})
```

Three forms: a story and a variant. The variant inherits the story's
`:component` and `:args`. With `:tags` defaulted to `#{:dev :docs}` it
appears in the dev tool and docs page.

### 4.2 With controls (auto-derived from schema)

```clojure
;; The view ships a Spec 010 schema:
(rf/reg-view :app.ui/button
  [:map
   [:label :string]
   [:variant [:enum :primary :secondary :danger]]
   [:disabled? :boolean]]
  (fn [args]
    [:button.btn {:class (str "btn-" (name (:variant args)))} (:label args)]))

;; The story consults the registered schema to auto-derive argtypes:
(story/reg-story :story.ui.button
  {:doc       "Primary action button."
   :component :app.ui/button
   :args      {:label    "Click me"
               :variant  :primary
               :disabled? false}})
```

Story tool emits controls automatically:
- `:label` → `:text`
- `:variant` → `:select` with options `[:primary :secondary :danger]`
- `:disabled?` → `:boolean`

Author writes zero `:argtypes` unless overriding the auto-derivation.

### 4.3 Decorators and `:args->events`

```clojure
(rf/reg-decorator :centered-layout
  {:doc  "Centre the rendered content."
   :kind :hiccup
   :wrap (fn [body _]
           [:div.flex.items-center.justify-center.h-screen body])})

(rf/reg-decorator :mock-auth
  {:doc  "Inject a mock authenticated user into the variant's frame."
   :kind :frame-setup
   :init [[:auth/restore-session {:user "alice"}]]})

(rf/reg-event-fx :story.auth/set-logged-in
  (fn [_ [_ v]]
    (if v
      {:fx [[:dispatch [:auth/restore-session {:user "alice"}]]]}
      {:fx [[:dispatch [:auth/log-out]]]})))

(story/reg-variant :story.auth.dashboard/logged-in
  {:decorators [[:centered-layout]
                [:mock-auth]]
   :args       {:logged-in? true}
   :args->events {:logged-in? :story.auth/set-logged-in}
   :events     [[:dashboard/initialise]]
   :tags       #{:dev :docs :test}})
```

### 4.4 Play + assertions

```clojure
(story/reg-variant :story.auth.login-form/happy-path
  {:doc    "Full login flow."
   :events [[:auth/initialise]]
   :play   [[:auth/email-changed "alice@example.com"]
            [:auth/password-changed "hunter2"]
            [:auth/login-pressed]
            [:rf.assert/path-equals  [:auth :status] :authenticated]
            [:rf.assert/sub-equals   [:auth/current-user] {:id 42 :name "Alice"}]
            [:rf.assert/state-is     :auth/login :authenticated]
            [:rf.assert/no-warnings]
            [:rf.assert/effect-emitted :navigate]]
   :tags   #{:dev :docs :test}})
```

### 4.5 Loaders with `:loaders-complete-when`

```clojure
(rf/reg-event-fx :charts.heatmap/subscribe
  (fn [_ _]
    {:fx [[:websocket {:url     "wss://api/heatmap"
                       :on-message [:charts/heatmap-tick]}]]}))

(story/reg-variant :story.charts.heatmap/live
  {:doc     "Renders against a live websocket fixture."
   :loaders [[:charts.heatmap/subscribe]]
   :loaders-complete-when [[:rf.assert/dispatched? [:charts/heatmap-tick]]]
   :events  []
   :tags    #{:dev}})
```

The `:websocket` fx never "completes"; `:loaders-complete-when` declares
the predicate that means "we've received enough data to render."

### 4.6 Composed variant via `:extends`

```clojure
(story/reg-variant :story.auth.login-form/loading
  {:events     [[:auth/initialise]
                [:auth/email-changed "alice@example.com"]
                [:auth/login-pressed]]
   :decorators [[:force-fx-stub :http {:status :pending}]]})

(story/reg-variant :story.auth.login-form/loading-with-prefill
  {:extends :story.auth.login-form/loading
   :events  [[:auth/initialise]
             [:auth/email-changed "alice@example.com"]
             [:auth/password-changed "hunter2"]    ; prefill password
             [:auth/login-pressed]]
   :tags    #{:dev :docs}})
```

Resolution at registration time. Cycles raise `:rf.error/extends-cycle`.

### 4.7 Modes (saved tuples)

```clojure
(rf/reg-mode :Mode.app/dark-mobile
  {:args {:theme :dark :viewport :mobile}})

(rf/reg-mode :Mode.app/light-desktop
  {:args {:theme :light :viewport :desktop}})

(story/reg-story :story.ui.button
  {:component :app.ui/button
   :modes     #{:Mode.app/dark-mobile :Mode.app/light-desktop}})
```

The story renders against each mode. Each `(variant × mode)` pair has its
own `snapshot-identity` so a visual-regression service iterates cells
independently.

### 4.8 Substrates

```clojure
(story/reg-variant :story.ui.button/all-substrates
  {:substrates #{:reagent :uix :helix :reagent-slim}})
```

The multi-substrate pane (Stage 4) renders each substrate side-by-side.
Substrate-specific failures render inline (§2.2).

### 4.9 Combined form (Form B) desugaring

```clojure
;; Author writes:
(story/reg-story :story.auth.login-form
  {:doc       "..."
   :component login-form
   :args      {:placeholder "you@example.com"}
   :variants  {:empty            {:events [[:auth/initialise]]}
               :validation-error {:events [[:auth/initialise]
                                           [:auth/email-changed "x"]
                                           [:auth/login-pressed]]
                                  :tags   #{:dev :docs :test}}}})

;; The macro expands to three independent calls (story + N variants):
(do
  (story/reg-story* :story.auth.login-form
    {:doc "..." :component login-form :args {...}})
  (story/reg-variant* :story.auth.login-form/empty
    {:events [[:auth/initialise]]})
  (story/reg-variant* :story.auth.login-form/validation-error
    {:events [...] :tags #{...}}))
```

Stage 2's macro emits one top-level form per variant, preserving
hot-reload-by-variant. The `*`-suffix runtime helpers are public for
tooling that programmatically generates registrations (e.g. the MCP write
surface in v1.1).

---

## §5 Runtime architecture

### 5.1 Per-variant frame allocation

Per spec/007 §Relationship-with-frames each variant *is* a frame. At
variant-mount time the runtime:

1. Calls `(rf/reg-frame variant-id {:doc ... :app-db {} :substrate :reagent ...})`
   (per spec/002 atomic create-and-register).
2. Records side-table metadata (view id, decorators, play, tags, modes,
   substrates) in `tools.story.registry/*variants*`.
3. Runs the four-phase lifecycle (§5.4).

At variant-unmount the runtime calls `(rf/destroy-frame variant-id)`. Hot-
reload preserves the side-table; a re-registration of the same variant
calls `reset-frame` and re-runs the lifecycle.

### 5.2 Args resolution precedence

When the rendering layer asks "what args is this variant rendered with?",
the runtime composes them in this strict order (later wins):

1. **Global args** — `tools.story.config/*global-args*` (from
   `re-frame.story/configure!` at boot — theme, locale defaults).
2. **Story args** — `:args` on the parent story.
3. **Mode args** — the active `:mode`'s `:args` (deep-merge, not replace).
4. **Variant args** — `:args` on the variant.
5. **Cell-local args** — runtime overrides from controls (`:story/set-arg`).

`(get-effective-args variant-id {:mode ... :overrides ...})` is the
public lookup; Stage 3 owns the helper.

Deep-merge (per Storybook's convention) for nested maps; override-by-
replacement for vectors. This convention matches Phase 1 §1.1 cited
behaviour.

### 5.3 Decorator composition order

For a render against variant V belonging to story S:

1. Collect decorators: `(concat global-decorators story-decorators
   variant-decorators)`.
2. Apply in order — **outermost wraps innermost** for `:hiccup` kind,
   so the global decorator's wrap is the outermost element in the
   rendered tree.
3. `:frame-setup` decorators fire at frame creation (before phase 1
   loaders), in the same order.
4. `:fx-override` decorators register their stubs at frame creation,
   before loaders.

Decorator composition is deterministic; Stage 3 unit-tests against a
golden trace.

### 5.4 Loader four-phase lifecycle with `:loaders-complete-when`

Strict order, per spec/007:

1. **Phase 1 — Loaders.** For each event in `:loaders` (in order):
   - `dispatch-sync` the event into the variant's frame.
   - Wait for the drain to settle (no in-flight events; no pending fx
     dispatching follow-ups).
   - Evaluate `:loaders-complete-when` if provided. If truthy, proceed.
     Otherwise wait for the next non-loader event; re-evaluate.
2. **Phase 2 — Events.** For each event in `(concat story-events variant-events)`:
   - `dispatch-sync` in order. Drain to completion between events.
3. **Phase 3 — Render.** The view renders against the post-events `app-db`,
   with the effective args (§5.2) and decorator stack (§5.3) applied.
4. **Phase 4 — Play.** For each event in `:play`:
   - `dispatch-sync` in order. Drain to completion.
   - `:rf.assert/*` events record into `:assertions` (no throw — §2.3).

Phase 1 and 4 are async-safe; phases 2 and 3 are sync (per re-frame's run-
to-completion drain).

### 5.5 Error projection

A render error in phase 3, or an unexpected exception in phases 1, 2, or 4,
projects into the variant's `:assertions` as a special failure record:

```clojure
{:assertion :rf.error/exception
 :phase     :phase-3-render
 :substrate :reagent
 :error     {:message "..." :stack "..." :data {...}}
 :passed?   false}
```

The variant pane shows the error inline (per §2.2 for substrates; same
shape for non-substrate errors). The play sequence continues past phase-1
or phase-2 errors so the full picture is captured (§2.3).

### 5.6 Snapshot-identity computation

Per spec/007 §Variant snapshot identity, the hash includes:

- Variant id
- `:events`, `:play`, `:loaders` (in order; canonicalised)
- Effective `:args` (post-merge with story + mode)
- Decorator id sequence and their args
- Tag set
- Parent story `:component` id
- Parent story decorators
- Registered schema digest of `:component` (per spec/011 §`:rf/schema-digest`)
- Active substrate (when computing per-substrate identity)
- Active mode (when computing per-mode identity)

The hash is `sha-256` of a transit-serialised canonical form (keys sorted,
vectors stable). The identity changes iff any input changes; otherwise
visual-regression services skip the cell.

Stage 3 implements `tools.story.runtime.snapshot-id/compute`. The
canonical form is keyed by `:rf/snapshot-canonical-v1` to allow future
revisions without breaking baselines.

---

## §6 `:advanced` build elision

### 6.1 The sentinel pattern

Story uses the same PRESENT-in-control / ABSENT-in-release pattern Spec 009
locks for instrumentation:

```clojure
;; compile-time flag (CLJS goog-define)
(goog-define ^:dynamic *enabled?* true)

;; macros expand conditionally
(defmacro reg-story [id metadata]
  (if *enabled?*
    `(re-frame.story.impl/reg-story* ~id ~metadata)
    `nil))
```

Under `:advanced` compile with `closure-defines {'re-frame.story.config/*enabled?*' false}`,
every Story registration form expands to `nil` and the entire
`re-frame.story.impl` ns becomes unreachable from the main namespace
graph — Closure DCE removes it wholesale.

### 6.2 What gets DCE'd

- All variant / story / workspace / mode / panel registrations.
- The `tools.story.registry` side-table.
- The render shell (`tools.story.ui.*` — see §8).
- The trace / a11y / perf panels.
- The play-runner.
- The control-derivation logic (Stage 2 schema-walker).

### 6.3 What survives

- The fn body of `re-frame.story/run-variant` (and friends), but with no
  registrations to act on it returns an empty result map. This is a
  feature: production code that *accidentally* calls `run-variant` does
  not throw, it returns empty.
- Public Var stubs (so `(:require [re-frame.story :as story])` doesn't
  break a prod build that imports a stories ns conditionally).

### 6.4 Cross-library `:extends` policy

Per §2.6, all registrations are dev-only. In a production build:
- Lib A's `reg-variant` calls expand to `nil`.
- Lib B's `reg-variant :extends :A/x` calls expand to `nil` too.
- No `:extends` resolution happens at all.

There is therefore no production-build failure mode for cross-library
`:extends`. Dev builds (with `*enabled?*` true) resolve `:extends` at
registration time; an unresolved parent raises `:rf.error/extends-unknown`
synchronously.

### 6.5 Verification

`scripts/check-bundle-isolation.cjs` (per `tools/README.md`) gets a
Story sentinel: any `re-frame.story.impl.*` symbol surviving in a
`:advanced` build output is a leak. Stage 8 (examples + guide) wires the
example builds against this sentinel.

---

## §7 MCP surface boundary

The agent surface ships separately as `day8/re-frame2-story-mcp` from
`tools/story-mcp/`. This section locks the contract between the two
artefacts; Stage 7 implements `tools/story-mcp/` against this contract.

### 7.1 Architecture

```
┌─────────────────────────────┐         ┌───────────────────────────┐
│ Agent (Claude / Cursor /    │ stdio + │ tools/story-mcp/          │
│ Copilot)                    │ JSON-RPC│ day8/re-frame2-story-mcp  │
└─────────────────────────────┘ <────► │ - tool definitions         │
                                       │ - schema validation        │
                                       │ - bridges to ↓             │
                                       └──────┬────────────────────┘
                                              │ in-process or pair-style
                                              ▼
                                       ┌───────────────────────────┐
                                       │ tools/story/ runtime      │
                                       │ - registry queries        │
                                       │ - run-variant             │
                                       │ - snapshot-identity       │
                                       │ - variant->edn            │
                                       └───────────────────────────┘
```

The MCP server connects to a running app's story runtime via the
existing Tool-Pair primitives (see [`spec/Tool-Pair.md`](../../spec/Tool-Pair.md)):
nREPL-attached process, the agent reads the registry over the wire, runs
variants, reads results back. The MCP server itself runs in the agent's
process; the story runtime runs in the app.

### 7.2 Tool surface (Storybook MCP-borrowed shape)

Per Phase 2 §6.9 the toolset splits into three:

**Dev tools** (instructions + preview):
- `get-story-instructions` — agent-onboarding text: how stories are
  authored, the EDN-first constraint, the canonical variant body keys.
- `preview-variant {:variant-id ...}` — returns rendered hiccup for a
  variant + the assertions list.

**Docs tools** (introspection):
- `list-stories` — `(rf/handlers :story)` enumeration.
- `get-story {:story-id ...}` — full story metadata.
- `get-variant {:variant-id ...}` — full variant body (as EDN).
- `list-tags` — `(rf/handlers :tag)`.
- `list-modes` — `(rf/handlers :mode)`.
- `list-assertions` — the registered `:rf.assert/*` vocabulary.
- `variant->edn {:variant-id ...}` — canonical EDN form.

**Testing tools** (execution):
- `run-variant {:variant-id ... :render? ...}` — full lifecycle invocation.
- `snapshot-identity {:variant-id ... :mode ... :substrate ...}` — content
  hash.
- `run-a11y {:variant-id ...}` — axe-core results (delegates to a11y
  panel data; Stage 6).
- `read-failures {:variant-id ...}` — diagnostic for the last `run-variant`
  call.

### 7.3 Write surface (v1.1, dev-only, gated)

- `register-variant {:variant-id ... :body ...}` — emits a `reg-variant`
  call into a connected REPL.
- `unregister-variant {:variant-id ...}` — symmetric.

Gated behind `:rf.story.mcp/write-enabled?` config flag (default `false`).
The agent's "self-healing loop" — write story → run → read failures → fix
— activates with the write surface; without it the loop is read-only.

### 7.4 What Story's core jar exposes (no MCP coupling)

```clojure
;; Public read primitives, in re-frame.story
(handlers-of-kind kind)                          ; thin wrapper over (rf/handlers kind)
(story-tree)                                     ; structured by `.`-split dotted path
(get-variant variant-id)                         ; canonical EDN body
(get-story story-id)
(list-tags) (list-modes) (list-assertions)
(run-variant variant-id opts)                    ; per §3.2
(snapshot-identity variant-id opts)
(variant->edn variant-id)
```

Stage 7's `tools/story-mcp/` is a thin adapter: takes JSON-RPC requests,
calls these CLJS functions, serialises responses back over stdio. Zero
agent-specific logic lives in `tools/story/`.

---

## §8 UI shell substrate choice

### 8.1 Decision

Story's own UI shell (sidebar, control panel, trace ribbon, embedded 10x
panel, etc.) renders using **Reagent** at v1, sourced from
`implementation/adapters/reagent/`. The UI shell namespaces live under
`tools.story.ui.*`.

### 8.2 Rationale

- **Reagent is stable and dogfood-neutral.** The Story UI shell exercises
  the same re-frame primitives Story stories exercise; using a substrate
  the rest of the codebase already validates avoids a self-hosting bias.
- **reagent-slim is still landing.** rf2-5djt (the slim rewrite) is in
  Stage 4 implementation. Story should not block on it; once reagent-slim
  is GA Story can migrate (Stage 8 may revisit).
- **The UI shell is itself one app's worth of views; substrate switches
  are cheap if done early.** Reagent's API surface is the most well-trodden;
  reducing risk during the initial Stage 4 render-shell push.

### 8.3 Bundle implications

The `tools/story/deps.edn` declares `reagent/reagent` (Maven coord) and
`day8/re-frame2-reagent` (the adapter, via `:local/root`). Together these
load Reagent v2 (per `feedback_target_reagent_v2`) in the dev tool's
runtime. In a `:advanced` build of the host app, Story DCEs entirely (§6)
so neither dep reaches production.

### 8.4 Namespace layout

```
tools/story/
├── deps.edn                                     ; declared above
├── IMPL-SPEC.md                                 ; this document
├── README.md
├── src/
│   ├── re_frame/
│   │   └── story.cljs                           ; public ns — reg-* macros + run-variant
│   │   └── story.clj                            ; macro impl (the dev/prod expand split)
│   └── tools/
│       └── story/
│           ├── impl/                            ; private — what the macros call
│           │   ├── reg.cljs                     ; reg-story*, reg-variant*, etc.
│           │   ├── schema.cljs                  ; :rf/variant Malli schema
│           │   └── extends.cljs                 ; :extends resolution
│           ├── registry.cljs                    ; side-table; query helpers
│           ├── runtime/
│           │   ├── frame.cljs                   ; per-variant frame allocation
│           │   ├── args.cljs                    ; effective-args resolution
│           │   ├── decorators.cljs              ; composition order
│           │   ├── loaders.cljs                 ; 4-phase lifecycle
│           │   ├── play.cljs                    ; play-runner + assertion recorder
│           │   ├── snapshot-id.cljs             ; content-hash
│           │   └── source-coord.cljs            ; source-stamp pipe
│           ├── render/
│           │   ├── shell.cljs                   ; sidebar / canvas / panels
│           │   ├── variants_grid.cljs           ; :variants-grid layout
│           │   ├── controls.cljs                ; auto-derived controls
│           │   ├── multi_substrate.cljs         ; side-by-side substrate panes
│           │   └── time_travel.cljs             ; epoch scrubber (10x embed adapter)
│           ├── panels/
│           │   ├── trace.cljs                   ; six-domino panel
│           │   ├── a11y.cljs                    ; axe-core integration
│           │   ├── perf.cljs                    ; live ribbon (v1.1)
│           │   ├── layout_debug.cljs            ; measure / outline / pseudo
│           │   ├── design_tokens.cljs           ; v1.1, conditional
│           │   └── docs.cljs                    ; autodocs from :doc + schemas
│           ├── share/
│           │   ├── qr.cljs                      ; QR code generator
│           │   └── transit.cljs                 ; workspace transit export
│           ├── workspace/
│           │   ├── grid.cljs
│           │   ├── prose.cljs
│           │   └── transit.cljs
│           └── ui/
│               ├── widgets.cljs                 ; controls widgets
│               ├── theme.cljs
│               └── routing.cljs                 ; story-tool URL surface
└── test/
    └── tools/
        └── story/
            └── ...
```

The macro-emitting layer is `re-frame.story` (the user-facing ns); all
internal implementation lives under `tools.story.*`. Public ns names match
the convention from `re-frame.adapter.reagent` / `re-frame.ssr`.

---

## §9 Tooling integration

### 9.1 Embedding re-frame-10x's epoch panel

Per §2.7 Story registers 10x's epoch panel as a story panel:

```clojure
;; tools/story/src/tools/story/panels/epoch_10x.cljs

(defn enable!
  "Register the 10x panel adapter. Called at Story boot. Inert if 10x
   is not on the classpath (the late-bind hook returns nil)."
  []
  (let [view-id (late-bind/get-fn :re-frame-10x.epoch-panel/view-id)]
    (when view-id
      (rf/reg-story-panel :rf.story/10x-epoch
        {:doc       "re-frame-10x's epoch buffer."
         :title     "Epochs"
         :placement :bottom
         :render    view-id}))))
```

The late-bind hook (per spec/002 / spec/006) is set by
`day8/re-frame2-10x` at its own boot. If 10x is not loaded, the call
is a no-op and the panel never appears in the sidebar.

### 9.2 Trace bus consumption

Each variant's frame gets a trace callback registered at variant-mount:

```clojure
(rf/register-trace-cb! variant-id
  (fn [trace-event]
    (swap! (variant-trace-buffer variant-id) conj trace-event)))
```

The trace panel (`tools/story/src/tools/story/panels/trace.cljs`)
subscribes to `(rf/subscribe [:story/trace-events variant-id])` and
renders the six-domino sequence (event → handler → fx → effect →
subscription → re-render). Per Phase 1 §4.3 this is the debugging UX no
JS tool can match.

### 9.3 Source-coord stamping flow

Per spec/009 / `feedback_file_spec_beads_for_implementation_findings`,
every registration captures `{:file <s> :line <n>}` at macro-expansion
time. Story propagates this through:

- Variant registry: `(:source (get-variant id)) => {:file ... :line ...}`.
- `:assertions` list: each assertion entry carries `:source`.
- The story tool's "Open in editor" affordance (v1.1) reads `:source`.

Stage 2's `reg-story*` / `reg-variant*` macros stamp `:source` from
`&form`'s `:line` / `:file` meta into the registry entry. Stage 5's
play-runner copies the `:source` of each `:play` event into the
corresponding `:assertions` record.

### 9.4 Schema-derivation pipeline

The control-derivation logic at `tools/story/src/tools/story/render/controls.cljs`
walks the registered Malli schema for `:component`:

- `:string` → `:text`
- `:int`, `:double` with `:min` / `:max` → bounded `:number`
- `:boolean` → `:boolean`
- `:enum`-of-strings → `:select`
- `:keyword` → `:text` with keyword-coercion
- Nested `:map` → group with collapsible header

Author-supplied `:argtypes` overrides the auto-derivation key-by-key.
Stage 2 owns the macro side; Stage 4 (controls layer) owns the rendering.

---

## §10 Stage breakdown (rf2-u6fb stages 2–8)

Each subsequent stage gets its own bead; this section gives the scope,
deliverables, dependencies, and recommended sub-staging. Stage 1's job is
to write this document and file the beads; Stages 2–8 implement against
this contract.

### Stage 2 — Core authoring surface

**Scope.** The `reg-*` macros and their runtime impls. No render shell yet;
no play-runner; no MCP. Goal is "a downstream lib can `reg-story` /
`reg-variant` against the macros and `(rf/handlers :story)` returns the
results."

**Deliverables.**
- `re-frame.story` public ns: `reg-story`, `reg-variant`, `reg-workspace`,
  `reg-mode`, `reg-decorator`, `reg-story-panel`, `reg-tag` macros.
- `re-frame.story.clj` macro impls with the dev/prod expand split (§6.1).
- `tools.story.impl.reg`: `reg-story*`, `reg-variant*` runtime helpers.
- `tools.story.impl.schema`: `:rf/variant` Malli schema (cross-referenced
  with `spec/Spec-Schemas.md`).
- `tools.story.impl.extends`: `:extends` resolution at registration time.
- `tools.story.registry`: side-table + queries.
- The seven canonical tags + canonical `:rf.assert/*` event registrations
  at Story load.

**Dependencies.** Spec 010 schema registry; Spec 001 registrar query API.

**Files touched.** All of `tools/story/src/re_frame/story.{cljs,clj}` and
`tools/story/src/tools/story/{impl,registry}/`.

**Recommended sub-staging.**
1. Macros + dev/prod expand split + Malli `:rf/variant`.
2. `reg-variant*` runtime helper + side-table.
3. `:extends` resolution.
4. Combined `reg-story` Form B desugaring.
5. Tag + mode registries; canonical-vocabulary load.

### Stage 3 — Runtime

**Scope.** Frame allocation, args resolution, decorator composition,
loader lifecycle, snapshot identity. No render; no UI. Goal is "given a
registered variant, `(run-variant id)` runs the four-phase lifecycle and
returns the result map per §3.2 — sans rendered hiccup."

**Deliverables.**
- `tools.story.runtime.frame`: per-variant `reg-frame` allocation; tear-
  down on hot-reload.
- `tools.story.runtime.args`: effective-args resolution (§5.2).
- `tools.story.runtime.decorators`: composition order (§5.3); `:frame-setup`
  fire at frame allocation; `:fx-override` register stubs.
- `tools.story.runtime.loaders`: the four-phase lifecycle (§5.4) with
  `:loaders-complete-when` evaluation.
- `tools.story.runtime.play`: dispatch-sync play sequence; record assertion
  results (record-don't-throw per §2.3).
- `tools.story.runtime.snapshot-id`: content-hash computation (§5.6).
- `re-frame.story/run-variant` public; `reset-variant`; `watch-variant`;
  `variants-with-tags`; `variant->edn`.

**Dependencies.** Stage 2 (registry + Malli schema in place).

**Files touched.** `tools/story/src/tools/story/runtime/*`, additions to
`re-frame.story`.

### Stage 4 — Render shell

**Scope.** The interactive UI: sidebar, canvas, control panel, time-travel
adapter, trace panel hookup. Includes the `:grid` / `:prose` /
`:variants-grid` / `:tabs` workspace layouts. Multi-substrate side-by-side
pane (§2.2). Local-storage layout persistence (§2.5). QR code share
affordance (§2.8.5).

**Deliverables.**
- `tools.story.ui.shell`: top-level chrome.
- `tools.story.render.controls`: auto-derived control widgets from schemas.
- `tools.story.render.multi-substrate`: side-by-side panes.
- `tools.story.render.variants_grid`: the `:variants-grid` workspace
  layout.
- `tools.story.workspace.{grid,prose,transit}`: layouts + transit export.
- `tools.story.share.qr`: QR-code share menu (§2.8.5).
- `tools.story.ui.routing`: the story-tool URL surface (additive per
  re-frame2's additive-contract discipline).

**Dependencies.** Stages 2 and 3 (registry + runtime).

**Files touched.** `tools/story/src/tools/story/{ui,render,workspace,share}/*`.

### Stage 5 — Assertions + play

**Scope.** The full `:rf.assert/*` runtime + play sequence + `run-variant`
test-runner adapter (`cljs.test`, generic adapter for kaocha). Per §3.5 the
canonical seven assertions are first-class; per §2.3 they record rather
than throw.

**Deliverables.**
- Concrete handlers for the seven canonical `:rf.assert/*` events.
- `tools.story.runtime.play` finalisation (play-stepper UI hookup).
- `cljs.test` adapter — `(deftest my-test (run-variant-as-test :story.foo/bar))`.
- Generic adapter shape for kaocha and others.

**Dependencies.** Stage 3 (play runner shell), Stage 4 (play-stepper UI).

**Files touched.** `tools/story/src/tools/story/runtime/play.cljs` (extend),
`tools/story/src/tools/story/assertions/*` (new). New examples in
`tools/story/test/`.

### Stage 6 — SOTA features

**Scope.** The first-party panels: trace, a11y, perf (v1.1), design-tokens
(v1.1, conditional), layout-debug trio, 10x embed adapter. Multi-substrate
auto-failure surfacing (§2.2). Per Phase 2's six additions
(§2.8.4–§2.8.8 + §2.8.5).

**Deliverables.**
- `tools.story.panels.trace`: six-domino panel via `register-trace-cb!`.
- `tools.story.panels.a11y`: axe-core integration + CI hook.
- `tools.story.panels.layout_debug`: measure + outline + pseudo-state.
- `tools.story.panels.perf` (v1.1).
- `tools.story.panels.design_tokens` (v1.1, conditional).
- `tools.story.panels.docs`: autodocs from `:doc` + schemas + variant table.
- `tools.story.panels.epoch_10x`: re-frame-10x adapter.
- Multi-substrate failure rendering (§2.2 wiring).

**Dependencies.** Stage 4 (panel hosting in the shell), Stage 5 (assertion
list for failure-projection).

**Files touched.** `tools/story/src/tools/story/panels/*`.

### Stage 7 — `tools/story-mcp/` separate jar

**Scope.** The separate-jar agent surface per §7. Stdio + JSON-RPC
transport; toolset definitions per the Storybook MCP shape (Dev / Docs /
Testing); read-only at v1 (`register-variant` / `unregister-variant` gated
v1.1).

**Deliverables.**
- `tools/story-mcp/deps.edn`: declares `day8/re-frame2-story-mcp`; depends
  on `:local/root "../story"` for the underlying primitives.
- `tools/story-mcp/src/re_frame/story/mcp.clj`: the MCP server (JVM-side
  CLJ; consumes Tool-Pair primitives to drive the live CLJS app).
- `tools/story-mcp/IMPL-SPEC.md`: its own implementation contract.

**Dependencies.** Stages 2–3 (registry + run-variant must be queryable).
Tool-Pair surface (`spec/Tool-Pair.md`) for the JVM-to-CLJS bridge.

**Files touched.** All of `tools/story-mcp/*` (new artefact dir).

### Stage 8 — Examples + guide integration

**Scope.** A working stories example (under `examples/`), guide-doc
integration, bundle-isolation verification, the Stage-1 IMPL-SPEC's
substrate-choice revisit (consider migrating UI shell to reagent-slim
once reagent-slim is GA — §8.2).

**Deliverables.**
- `examples/story-counter/` — minimal example showcasing the seven `reg-*`
  macros + a couple of `:rf.assert/*` events.
- `docs/guide/component-playground.md` — narrative guide.
- `scripts/check-bundle-isolation.cjs` Story sentinel addition (§6.5).
- `examples/story-counter/` `:advanced` build excludes Story (verified
  by the sentinel).
- Migration assessment of UI shell substrate to reagent-slim (decision
  bead; not necessarily a migration in this stage).

**Dependencies.** All prior stages.

**Files touched.** `examples/story-counter/*` (new), `docs/guide/*`,
`scripts/check-bundle-isolation.cjs`.

---

## §11 v1 / v1.1 / v2 tiering

Every feature tagged. v1.0 = first Clojars publish. v1.1 = first follow-up
release. v2 = explicitly deferred post-1.0.

### 11.1 v1.0 (twelve high-confidence + must-ships)

Per Phase 2 §5.1's converged ship list (the 12 items) + additional
phase-2 SOTA adds that are cheap.

| Item | Bead reference |
|---|---|
| 1. MCP server + component manifest | Stage 7 |
| 2. a11y (axe-core) panel inline + CI hook | Stage 6 |
| 3. Play-style scripted interactions + `:rf.assert/*` vocabulary | Stages 2, 5 |
| 4. External visual-regression integration via `snapshot-identity` hook | Stage 3 |
| 5. Three-level args + auto-derived controls from Spec 010 schemas | Stages 2, 4 |
| 6. MSW-shaped effect mocking via `force-fx-stub` decorator | Stage 2 |
| 7. Six-domino trace panel per variant via `register-trace-cb!` | Stage 6 |
| 8. re-frame-10x epoch panel embedded as `reg-story-panel` | Stage 6 |
| 9. Story portability — `run-variant` returns `{:frame :app-db :assertions :rendered-hiccup :elapsed-ms}` | Stage 3 |
| 10. EDN-first variant artefact (no `:render` fn-slot, round-trippable) | Stage 2 |
| 11. Inclusion tags (seven canonical + `!`-prefix removal) | Stage 2 |
| 12. Workspace grid + transit-shareable layouts | Stage 4 |

Plus from Phase 2 §5.2 (additions ship in v1):

| Item | Bead reference |
|---|---|
| Layout-debug overlay trio (measure / outline / pseudo) | Stage 6 (rf2-zhwd) |
| `reg-mode` saved-tuple primitive | Stage 2 |
| `:variants-grid` workspace layout | Stage 4 |
| Per-variant QR code in share menu | Stage 6 (rf2-zhwd) |
| Multi-substrate side-by-side pane (substrate-failures inline) | Stage 6 (rf2-zhwd) |
| 10x epoch panel embed (stub + contract) | Stage 6 (rf2-zhwd) |

### 11.2 v1.1 (first follow-up)

| Item | Bead reference |
|---|---|
| Live performance ribbon (FPS, INP, long tasks, CLS, memory, Reagent profiling) | Stage 6 (deferred) |
| Design-token panel (conditional on upstream token emission) | Stage 6 (deferred) |
| MCP write surface (`register-variant`, `unregister-variant`) | Stage 7 (deferred) |
| "Open in editor" per variant | Stage 8 (post-Stage-1) |
| Per-variant autodocs panel polish | Stage 6 (deferred) |

### 11.3 v2 (post-1.0 deferred)

| Item | Source |
|---|---|
| Subscription topology visualiser (using `sub-topology`) | Phase 1 Tier 3 |
| Per-substrate variant filtering (deep substrate-divergence audit) | Phase 1 Tier 3 |
| Static export (render the whole story tool to a static site) | Phase 1 Tier 3 |
| Custom story panels by third parties (the v1 panels exhaust the v1 set) | Phase 1 Tier 3 |
| Remote Storybook federation (multi-host composition) | Phase 1 §2.2 |
| App-db snapshot diff (data-space visual regression) | Phase 2 §5.2 #7 |
| oEmbed URLs for Notion / static-doc inlining | Phase 2 §5.2 #6 (deferred) |
| BackstopJS-style pixel scrubber UI | Phase 2 §2.1 (out of scope; data-space scrubber via 10x suffices) |

---

## §12 What we deliberately don't ship

Phase 1 §5.7 + Phase 2 §5 + §1.3 above. Each is named with rationale so
the next agent on Stage 2 has a clear "no" list.

### 12.1 CSF Factories (JS) — we use EDN-first

**Rejected.** Storybook v10 introduced CSF Factories for type-safe
story-as-test-fixture. CSF still permits inline JSX in `:render`.

**Why.** EDN-first variant bodies are *strictly stronger* than CSF
Factories (per Phase 2 §5.1 #10): they round-trip across the network, feed
the MCP pipeline cleanly, and contain no closures. The data-only constraint
eliminates an entire class of "your story works but doesn't serialise"
bugs. Accepting `:render` fn-slots would re-import that complexity.

### 12.2 First-party visual-regression service — we use the `snapshot-identity` hook

**Rejected.** Storybook + Chromatic, Percy, Argos. Backstop. Etc.

**Why.** Pixel capture, baseline storage, and PR-review UX are *services* —
they want infrastructure, billing, ops. Story should not be in that
business. The right shape is a hook (`snapshot-identity`, stable iframes)
that downstream services consume. This is the dominant pattern across
modern workshops (Ladle, RC, Histoire all defer).

### 12.3 Component-co-located fixtures (React Cosmos `.fixture.tsx` files)

**Rejected.** RC's file-system-fixture model wires sidebar structure to
file paths.

**Why.** re-frame2's registered artefacts are the canonical structure
mechanism; file-system convention duplicates the registry. The Spec 007
canonical id grammar (`:story.<path>/<variant>`) already gives a
hierarchical name; the story-tool's sidebar is built from that namespace
graph. File-system colocation would be a second source of truth.

### 12.4 Statechart visualisation engine

**Rejected (delegated).** Phase 1 §6.8 split: Story ships a one-line
current-state indicator only; the full chart-rendering work lives in
`day8/re-frame2-machines-viz`.

**Why.** Auto-layout for hierarchical statecharts with parallel regions
is specialised work (XState invested years on Stately). The bundle weight
of layout engines (`@xyflow/react`, `d3-hierarchy`, `elkjs`) shouldn't
land on every Story consumer.

### 12.5 Pixel-scrubber UI (BackstopJS slider-between-before-after)

**Rejected.** BackstopJS's tactile pixel scrubber is a great UX for pixel
visual regression.

**Why.** Story's data-space scrubber via 10x's epoch panel covers the
same UX *better* for re-frame2 apps — scrub through events with `app-db`
following, not through static pixels. Pixel scrubbing is a downstream
visual-regression-service concern (see §12.2). Story does not host
pixels.

### 12.6 BackstopJS-style baseline storage

**Rejected.** Same rationale as §12.2 — services handle baselines.

### 12.7 First-party SSR rendering pipeline

**Rejected (delegated).** Story exposes `:platforms #{:server :client}` per
variant; the server-side pane uses `re-frame.ssr` (Spec 011's artefact).
Story doesn't ship its own JVM render path.

**Why.** SSR is owned by Spec 011 and `day8/re-frame2-ssr`; reusing that
artefact preserves single-source-of-truth for server-render decisions.

### 12.8 MCP server in-process

**Rejected.** Spec 007 doesn't mention MCP; Phase 1 §6.1 proposed an
external jar. §2.1 above locks it.

**Why.** stdio + JSON-RPC dependencies are dead weight in a typical
production deploy. Splitting the jar keeps the Story core lean.

### 12.9 Built-in pixel diff under `:test` tag

**Rejected.** A `:test`-tagged variant runs `run-variant` and asserts on
`:assertions` + `:app-db`. It does **not** capture or diff pixels.

**Why.** Pixel diff is downstream (§12.2). Stories-as-tests are
**state-space** tests — `app-db` reaches the expected state — not pixel-
space tests. This is the Spec 007 §Story-as-test-duality lock.

### 12.10 Full re-frame-10x reimplementation

**Rejected (delegated).** Per §2.7 Story embeds 10x's epoch panel; does
not own a parallel implementation.

**Why.** 10x's UX is mature; replicating it would split maintenance and
drift. The right primitive is "10x is a peer artefact, Story integrates."

---

## §13 Self-assessment (for the bead trail)

This section is auditable by the next agent reading the bead chain.

### 13.1 Decisions that were hardest

1. **§2.4 — `:loaders-complete-when` for long-lived fx.** The default
   ("first non-loader event seen, or drain settles") is a heuristic; the
   override is the safety valve. There's a future world where the default
   misfires (e.g. a websocket's first message is a heartbeat that isn't
   semantically "the data is ready"). The override exists for exactly that
   case; authors who hit it should file a Pattern doc, not work around it
   in the variant body. Flag.

2. **§2.8.1 — UI shell substrate.** Reagent is the right call now; the
   tension is that Story is *for* re-frame2 apps and most of those will
   eventually move to reagent-slim. Stage 8 (§10) re-opens this decision
   once reagent-slim hits GA; until then Reagent.

3. **§7 — MCP boundary contract.** The Tool-Pair primitives the MCP server
   leans on (nREPL-attached process, in-process bridge) are themselves
   evolving (rf2-qj8e parent epic). The MCP server's exact attachment shape
   is Stage 7's call; this IMPL-SPEC names the *toolset* (Dev / Docs /
   Testing split) but not the transport detail.

### 13.2 Areas still under-specified

1. **Async-result shape for `run-variant`.** §3.2 names "Promise or
   manifold.deferred — Stage 3 picks." This is a deliberate punt; the
   right shape depends on Stage 3's investigation of how
   `:loaders-complete-when` interacts with re-frame's synchronous drain.
   Locked decision: Stage 3 owns this.

2. **Mode × Variant × Substrate snapshot-identity matrix.** §5.6 mentions
   "the hash includes substrate when computing per-substrate identity"
   but the exact composition for the full cell `(variant × mode ×
   substrate)` is left to Stage 3. Three options: nested hash (substrate
   is leaf); composite key (`[variant-id mode-id substrate]`); or
   substrate as a separate axis with its own hash slot. Stage 3 picks.

3. **Decorator argument shapes per `:kind`.** §3.1 names the three kinds
   (`:hiccup`, `:frame-setup`, `:fx-override`) but Stage 2's Malli schema
   for `reg-decorator` bodies is yet to be drafted — the per-kind required
   keys differ. Stage 2's schema-design call.

4. **Hot-reload semantics for `reg-decorator` re-registration.** If a
   `:hiccup` decorator's `:wrap` closure changes, do all variants using
   it re-render automatically? Reagent's reactive graph handles this for
   subscription changes; decorator changes need explicit propagation
   (mark variants stale, re-mount). Stage 3 / Stage 4 jointly handle.

5. **`:rf.assert/effect-emitted` semantics under `force-fx-stub`.** If a
   variant stubs `:http` and then asserts `:rf.assert/effect-emitted
   :http`, does the assertion pass? The fx *is* emitted; the stub just
   intercepts. Stage 5 clarifies.

6. **The MCP server's protocol version.** Storybook v10 ships
   MCP-protocol-version-2024-11-05. The story-mcp jar should target the
   then-current MCP spec version; Stage 7 picks. Not in scope here.

### 13.3 Verification

- All seven §2 architectural decisions are documented as decided (per the
  user's brief — "all architectural decisions LOCKED").
- The twelve §5.1 Phase 2 high-confidence ship items appear in §11.1
  (v1.0).
- The six §5.2 Phase 2 additions appear in §11.1 (v1.0) or §11.2 (v1.1)
  with explicit rationale.
- The seven §6 Phase 1 questions are addressed in §2.1–§2.7 with
  rationale.
- The §12 rejection list is concrete and rationale-bearing.

---

*End of implementation specification.*
