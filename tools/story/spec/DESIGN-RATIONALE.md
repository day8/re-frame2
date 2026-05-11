# Story — Design Rationale

> WHY each major design call was made. The capability docs say *what*
> the surface is; this document says *why* it's that shape and not
> some other plausible shape. The seven rf2-m6tu §6 decisions, the
> Phase-2 SOTA additions, plus the calls that emerged during the
> IMPL-SPEC drafting itself.

## The seven architectural decisions (rf2-m6tu §6, resolved 2026-05-11)

The seven open questions from Phase 1 (rf2-m6tu §6) resolved as
follows. Each is binding for Stages 2–8.

### §separate-mcp-jar — MCP server ships as separate jar

**Decision.** The MCP agent surface ships as `day8/re-frame2-story-mcp`
at `tools/story-mcp/`. The Story core jar (`day8/re-frame2-story`)
carries **no** stdio / JSON-RPC dependency.

**Rationale.** The MCP server depends on transport machinery (stdio
adapter, JSON-RPC framing, asynchronous-handler runtime) that the
vast majority of Story consumers never load. Splitting at the jar
boundary keeps the Story core lean and lets the MCP surface evolve on
its own cadence. The pattern mirrors `tools/machines-viz/` vs.
`tools/machines-viz-mcp/` (per [`tools/README.md`](../../README.md)).

**Implication.** Story's core jar exposes the *read* primitives the
MCP server consumes (`handlers`, `frame-meta`, `run-variant`,
`snapshot-identity`, `variant->edn`); the MCP server packages them as
tools per the Storybook MCP Dev / Docs / Testing toolset split. See
[`006-MCP-Surface.md`](006-MCP-Surface.md).

### §inline-substrate-failures — render inline

**Decision.** A variant may declare `:substrates #{:reagent :uix
:helix :reagent-slim}` (subset, default = the host frame's adapter);
when Story renders the variant against multiple substrates the
failure for each is rendered **inline** with the variant pane — not
auto-skipped.

**Rationale.** Substrate-portability gaps are the entire point of
multi-substrate rendering. Hiding failures hides the bugs Story
exists to surface. Stage 1 of any substrate-portability audit is
"look at the red panes."

**Implication.** The render shell treats per-substrate render in a
try/catch boundary; per-variant `:assertions` accumulates a
substrate-tagged failure entry; the multi-substrate pane shows the
error inline alongside the healthy substrates' renders. See
[`003-Render-Shell.md`](003-Render-Shell.md) §Multi-substrate.

### §record-not-throw — assertion failures record, don't throw

**Decision.** `:rf.assert/*` events **record** failures into the
variant's `:assertions` list and continue the play sequence. They do
not throw.

**Rationale.** Play sequences run to completion; the full picture of
"what went wrong" is more useful than "first failure halts
everything." Aligns with re-frame's run-to-completion drain semantics
([Spec 002](../../../spec/002-Frames.md)). Mirrors devcards' behaviour;
diverges from Storybook (which throws). Storybook's choice is
constrained by JavaScript's async-throw mess; we have no such
constraint.

**Implication.** Each `:rf.assert/*` handler returns a map describing
the assertion result; the play-runner concatenates these into the
variant's `:assertions` list. `run-variant`'s test-runner adapter
post-processes the `:assertions` list and translates failures into
the host test framework's failure signal — `cljs.test`'s `is`,
kaocha's reporter, etc. See [`004-Assertions.md`](004-Assertions.md).

### §loaders-complete-when-predicate — `:loaders-complete-when`

**Decision.** Variant body may include an optional
`:loaders-complete-when` event predicate. Default behaviour:

- HTTP-flavoured fx is "complete" when the response event has been
  dispatch-synced.
- Long-lived fx (`:websocket`, `:interval`, `:firestore`, etc.) is
  "complete" when the first message arrives.
- Authors override via a vector-of-event-vectors or a registered
  predicate-event id.

**Rationale.** The
[spec/007 §Loader-lifecycle](../../../spec/007-Stories.md) phrasing
— "no further events are in flight against the variant's frame" —
works for request/response fx but never satisfies for long-lived fx.
The default-plus-override design keeps simple cases simple and makes
the long-lived case explicit.

**Implication.** Stage 3 (runtime) implements the four-phase
lifecycle with the loader-complete check after each loader's drain.
The default predicate is "first non-loader event seen by the frame,
or loader's drain settles with no in-flight fx, whichever comes
first." Stage 2 macro validates that `:loaders-complete-when`
resolves to a registered event id or is a literal data form (vector
of event vectors). See [`002-Runtime.md`](002-Runtime.md).

**Open flag.** The default ("first non-loader event seen, or drain
settles") is a heuristic; the override is the safety valve. There's
a future world where the default misfires (e.g. a websocket's first
message is a heartbeat that isn't semantically "the data is ready").
The override exists for exactly that case; authors who hit it should
file a Pattern doc, not work around it in the variant body.

### §both-workspace-persistence — local + transit

**Decision.** Workspace layouts persist **both** ways:

- **Default: local-storage.** Interactive rearrangements auto-save
  to local storage keyed by `[workspace-id breakpoint]`. Per-user,
  per-browser.
- **Save-as registered artefact.** A "Save layout as `:Workspace.x/y`"
  button serialises the current layout to transit, re-registers it
  under the chosen id, and exports the transit blob for cross-machine
  sharing.

**Rationale.** Nubank's workspaces ships both for the same reason:
ephemeral edits should not require a registration ceremony;
deliberate-share edits need a durable artefact. Local-storage is
"where am I right now"; transit-exported is "this is the team layout
for Friday's review."

**Implication.** The render shell wires the local-storage save on
every layout change and adds the "Save layout" affordance.
`tools.story.workspace.transit/workspace->edn` returns the serialised
form. See [`003-Render-Shell.md`](003-Render-Shell.md) §Workspace
persistence.

### §DCE-dev-only — registration is dev-only

**Decision.** `reg-story`, `reg-variant`, `reg-workspace`, `reg-mode`,
`reg-story-panel`, `reg-decorator`, and `reg-tag` are **all
dev-only**. Under `:advanced` compiler builds, all seven macros elide
to `nil`.

**Rationale.** Cross-library `:extends` (where lib A registers
`:story.x/parent` and lib B has `:extends :story.x/parent`) becomes
irrelevant in production builds because no registrations exist.
There is no consumer code that cares about a story registration *at
runtime in production* — Story is a dev tool. Eliding the
registration form-by-form collapses an entire class of "this won't
dead-code-eliminate cleanly" bugs.

**Implication.** Stage 2 macros expand to one form in dev mode (the
registration call) and to `nil` in production. Compile-time flag is
`goog-define :rf.story/enabled?` (default `true`; downstream apps
override to `false` for prod builds). See
[`005-SOTA-Features.md`](005-SOTA-Features.md) §Production elision.

### §10x-embed — embed via `reg-story-panel`

**Decision.** Story does **not** reimplement an epoch UI. Story
registers re-frame-10x's existing epoch panel as a story panel via:

```clojure
(rf/reg-story-panel :rf.story/10x-epoch
  {:doc       "re-frame-10x's epoch buffer for the active variant."
   :title     "Epochs (10x)"
   :placement :bottom
   :render    :re-frame-10x.epoch-panel/view})
```

The 10x epoch view is consumed from `day8/re-frame2-10x` (per the
`tools/10x/` line in [`tools/README.md`](../../README.md)). Story's
panel is the **adapter**; 10x stays its own artefact, on its own
release cadence.

**Rationale.** The epoch panel's UX (time-travel scrubber, app-db
follower, event replay) is already best-in-class inside
re-frame-10x. Reimplementing it inside Story would (a) double the
implementation surface, (b) split the maintenance work, (c) drift
over time. Embedding keeps one source of truth.

**Implication.** Story's `:rf.story/10x-epoch` registration ships
with v1 but the panel only activates if `day8/re-frame2-10x` is on
the classpath (per the late-bind hook in spec/002). If 10x is
absent, the sidebar entry hides. The 10x artefact owns the actual
view; Story owns the *integration*. See
[`005-SOTA-Features.md`](005-SOTA-Features.md) §10x epoch panel
embed.

## Decisions surfaced during IMPL-SPEC drafting

These decisions emerged while writing the original 8,614-word
IMPL-SPEC; flagged for Mike's review per the "Mike's delegation
extends to additional decisions surfaced during this stage"
instruction.

### §reagent-ui-shell — UI shell is Reagent at v1

The Story tool's own chrome (sidebar, control panel, trace ribbon,
etc.) is rendered using Reagent
(`implementation/adapters/reagent/`). reagent-slim is still landing
(rf2-5djt); Story should not block on it. Stage 8 may revisit and
migrate once reagent-slim is GA. See
[`003-Render-Shell.md`](003-Render-Shell.md) §UI shell substrate.

The tension is that Story is *for* re-frame2 apps and most of those
will eventually move to reagent-slim. Stage 8 re-opens this
decision once reagent-slim hits GA; until then Reagent.

### §public-ns-root — `re-frame.story` for user-facing API

All public `reg-*` macros and the `run-variant` family live under
`re-frame.story`. Internal namespaces live under `tools.story.*` (see
[`003-Render-Shell.md`](003-Render-Shell.md) §Namespace layout). This
matches the convention from `re-frame.adapter.reagent`,
`re-frame.ssr`, etc.

### §reg-mode-in-v1 — `reg-mode` ships in v1

Per Phase 2 §5.2 #3, the Chromatic-style mode primitive (saved tuples
of global args) lands in v1 — not v1.1 — because the implementation
cost is small (it's a saved `args` map plus a snapshot-identity
contribution) and the agent-integration benefit is large (MCP can
iterate variants × modes without combinatorial registration).

### §variants-grid-in-v1 — `:variants-grid` workspace layout ships in v1

Per Phase 2 §5.2 #4. devcards-style multi-variant viewing has no JS
competitor; the implementation cost is layout-only.

### §qr-in-v1 — QR code in share menu — v1 polish

Per Phase 2 §5.2 #6. Tiny implementation, high signal. Adds `qr-code`
dep.

### §layout-debug-in-v1 — Layout-debug trio ships in v1

Per Phase 2 §5.2 #2. DOM-mutating utility; framework-agnostic; cheap.

### §perf-ribbon-in-v1.1 — Perf ribbon ships in v1.1

Per Phase 2 §5.2 #1. Live FPS/INP/CLS/memory + Reagent-render-profiling
at 50ms refresh; non-trivial implementation (requires
`PerformanceObserver`, frame-loop sampler, Reagent profile hooks).
Defer to first follow-up release.

### §design-tokens-in-v1.1 — Design-token panel ships in v1.1, conditional

Per Phase 2 §5.2 #5. Iff `re-com` or the host design system emits
Style-Dictionary-shaped tokens. Stage 6 ships the panel; activation
is conditional on token emission upstream.

### §10x-embed-inert-without-causa — 10x embed registration ships in v1 but stays inert if 10x absent

Per §10x-embed above. The `reg-story-panel` call is unconditional; the
panel's `:render` resolves via late-bind to a hidden no-op if 10x
isn't present. This keeps the user experience graceful when 10x isn't
on the classpath.

## Phase-2 SOTA additions — tier choices

Phase 2's six concrete additions to Phase 1's feature spec:

| Item | Tier | Rationale |
|---|---|---|
| In-canvas live performance ribbon | v1.1 | Non-trivial implementation; defer for first follow-up. SOTA-table-stakes per Phase 2 §5.2 #1. |
| Layout-debug overlay trio | v1 | Cheap; framework-agnostic; all three together are best-in-class. |
| `reg-mode` saved-tuple primitive | v1 | Small implementation cost; large agent-integration benefit. |
| `:variants-grid` workspace layout | v1 | Layout-only; unique to re-frame2 (no JS workshop ships it). |
| Design-token panel | v1.1 conditional | Iff upstream emits tokens; defer panel activation. |
| Per-variant QR sharing | v1 | Tiny implementation; high signal. |
| App-db snapshot diff (data-space VR) | v2 roadmap | Unique to re-frame2's data-centric model; no JS analogue. |

## Why a lifecycle machine via re-frame.machines?

(Anticipating future readers.) The four-phase loader lifecycle is
*not* implemented as a `reg-machine` state machine. Reasoning:

- The lifecycle is intra-frame coordination, not user-facing state.
- Each variant *is* its own frame; the lifecycle runs once per frame
  mount, not as a long-lived state of the application.
- `:loaders-complete-when` is a *predicate* over events seen on the
  frame, not a transition guard — re-frame's drain machinery already
  has the right primitive.

A machine would over-shape the lifecycle; the existing four-phase
sequence + drain-settle check is closer to the problem domain.

## Rejected — what Story deliberately doesn't ship

Each named with rationale so contributors have a clear "no" list.

### Rejected: CSF Factories (JS) — we use EDN-first

**Rejected.** Storybook v10 introduced CSF Factories for type-safe
story-as-test-fixture. CSF still permits inline JSX in `:render`.

**Why.** EDN-first variant bodies are *strictly stronger* than CSF
Factories (per Phase 2 §5.1 #10): they round-trip across the
network, feed the MCP pipeline cleanly, and contain no closures. The
data-only constraint eliminates an entire class of "your story works
but doesn't serialise" bugs. Accepting `:render` fn-slots would
re-import that complexity.

### Rejected: First-party visual-regression service

**Rejected.** Storybook + Chromatic, Percy, Argos. Backstop. Etc.

**Why.** Pixel capture, baseline storage, and PR-review UX are
*services* — they want infrastructure, billing, ops. Story should
not be in that business. The right shape is a hook
(`snapshot-identity`, stable iframes) that downstream services
consume. This is the dominant pattern across modern workshops
(Ladle, RC, Histoire all defer).

### Rejected: Component-co-located fixtures

**Rejected.** RC's file-system-fixture model wires sidebar structure
to file paths.

**Why.** re-frame2's registered artefacts are the canonical
structure mechanism; file-system convention duplicates the registry.
The [Spec 007](../../../spec/007-Stories.md) canonical id grammar
(`:story.<path>/<variant>`) already gives a hierarchical name; the
story-tool's sidebar is built from that namespace graph. File-system
colocation would be a second source of truth.

### Rejected: Statechart visualisation engine

**Rejected (delegated).** Phase 1 §6.8 split: Story ships a one-line
current-state indicator only; the full chart-rendering work lives in
`day8/re-frame2-machines-viz`.

**Why.** Auto-layout for hierarchical statecharts with parallel
regions is specialised work (XState invested years on Stately). The
bundle weight of layout engines (`@xyflow/react`, `d3-hierarchy`,
`elkjs`) shouldn't land on every Story consumer.

### Rejected: Pixel-scrubber UI

**Rejected.** BackstopJS's tactile pixel scrubber is a great UX for
pixel visual regression.

**Why.** Story's data-space scrubber via 10x's epoch panel covers
the same UX *better* for re-frame2 apps — scrub through events with
`app-db` following, not through static pixels. Pixel scrubbing is a
downstream visual-regression-service concern. Story does not host
pixels.

### Rejected: BackstopJS-style baseline storage

**Rejected.** Same rationale as the visual-regression service — services
handle baselines.

### Rejected: First-party SSR rendering pipeline

**Rejected (delegated).** Story exposes `:platforms #{:server
:client}` per variant; the server-side pane uses `re-frame.ssr`
([Spec 011](../../../spec/011-SSR.md)'s artefact). Story doesn't
ship its own JVM render path.

**Why.** SSR is owned by Spec 011 and `day8/re-frame2-ssr`; reusing
that artefact preserves single-source-of-truth for server-render
decisions.

### Rejected: MCP server in-process

**Rejected.** [Spec 007](../../../spec/007-Stories.md) doesn't
mention MCP; Phase 1 §6.1 proposed an external jar. Locked above
(§separate-mcp-jar).

**Why.** stdio + JSON-RPC dependencies are dead weight in a typical
production deploy. Splitting the jar keeps the Story core lean.

### Rejected: Built-in pixel diff under `:test` tag

**Rejected.** A `:test`-tagged variant runs `run-variant` and asserts
on `:assertions` + `:app-db`. It does **not** capture or diff pixels.

**Why.** Pixel diff is downstream. Stories-as-tests are
**state-space** tests — `app-db` reaches the expected state — not
pixel-space tests. This is the
[Spec 007 §Story-as-test-duality](../../../spec/007-Stories.md) lock.

### Rejected: Full re-frame-10x reimplementation

**Rejected (delegated).** Story embeds 10x's epoch panel; does not
own a parallel implementation. See §10x-embed above.

**Why.** 10x's UX is mature; replicating it would split maintenance
and drift. The right primitive is "10x is a peer artefact, Story
integrates."

## Open items (deliberate punts)

These were named in the original IMPL-SPEC §13.2 as deliberate
punts. They live closer to the implementation; future implementer
choices are auditable here.

1. **Async-result shape for `run-variant`.** Promise vs.
   `manifold.deferred` — Stage 3 picks based on how
   `:loaders-complete-when` interacts with re-frame's synchronous
   drain.

2. **Mode × Variant × Substrate snapshot-identity matrix.** Three
   options: nested hash (substrate is leaf); composite key
   (`[variant-id mode-id substrate]`); or substrate as a separate
   axis with its own hash slot. Stage 3 picks.

3. **Decorator argument shapes per `:kind`.** The three kinds
   (`:hiccup`, `:frame-setup`, `:fx-override`) — Stage 2's Malli
   schema for `reg-decorator` bodies covers per-kind required keys.

4. **Hot-reload semantics for `reg-decorator` re-registration.** If a
   `:hiccup` decorator's `:wrap` closure changes, do all variants
   using it re-render automatically? Reagent's reactive graph handles
   subscription changes; decorator changes need explicit propagation
   (mark variants stale, re-mount).

5. **`:rf.assert/effect-emitted` semantics under `force-fx-stub`.** If
   a variant stubs `:http` and then asserts
   `:rf.assert/effect-emitted :http`, does the assertion pass? The
   fx *is* emitted; the stub just intercepts. Stage 5 clarifies.

6. **MCP protocol version.** Landed at `2025-06-18` per
   [`tools/story-mcp/spec/`](../../story-mcp/spec/) §Wire Protocol.

## Verification

- All seven §rf2-m6tu §6 architectural decisions are documented as
  decided.
- The twelve Phase 2 §5.1 high-confidence ship items appear in
  [`005-SOTA-Features.md`](005-SOTA-Features.md) §v1 ship list.
- The six Phase 2 §5.2 additions appear in v1 or v1.1 with explicit
  rationale.
- The seven Phase 1 §6 questions are addressed above with rationale.
- The Rejected list is concrete and rationale-bearing.
