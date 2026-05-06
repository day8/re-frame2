# re-frame2 Enhancement Proposals

This directory holds the design documents for **re-frame2** — a *specification for a re-frame-flavoured pattern for building SPAs*, plus a *Clojure/CLJS reference implementation*.

**Read [reorient.md](reorient.md) first.** It captures the orientation: AI-first amenability, simplest-possible-computational-model, Clojure ethos, language-agnostic pattern, SSR-capable. Every other document is shaped by it.

The **load-bearing decisions** live in [000-Vision.md](000-Vision.md). Each per-area EP consumes those decisions; downstream EPs must not reshape the foundation.

The **rationale** that justifies the pattern's shape lives across re-frame's existing doc set ([on-dynamics](https://day8.github.io/re-frame/on-dynamics/), [data-oriented-design](https://day8.github.io/re-frame/data-oriented-design/), [a-loop](https://day8.github.io/re-frame/a-loop/), [application-state](https://day8.github.io/re-frame/application-state/), etc.) — see [reorient.md §Rationale](reorient.md#rationale--built-on-established-re-frame-thinking) for the full list. reorient.md and the per-EP docs *operationalise* that rationale; they do not re-litigate it.

The original placeholder/drafting EPs at `docs/EPs/00X-*.md` are the historical drafts that motivated re-frame2; the documents in *this* directory supersede them for design purposes.

## Status legend

| Status | Meaning |
|---|---|
| **Placeholder** | Skeleton or stub. Captures intent but may be incoherent or empty. |
| **Drafting** | Real writing and thinking. Subject to revision; may be wrong in places. |
| **UnderReview** | Ready for broader discussion in a tracking issue. |
| **Accepted** / **Rejected** | Decision made; reasons recorded. |
| **Released** | Shipped in re-frame2. |

## Documents

| # | Title | Status | One-liner |
|---|---|---|---|
| — | [README](README.md) | — | This file. |
| — | [reorient](reorient.md) | Drafting (canonical orientation) | The pattern thesis. AI-first, simplest-computational-model, Clojure ethos, language-agnostic, SSR-capable. Read first. |
| 000 | [Vision](000-Vision.md) | Drafting (reframed per reorient) | Governing principles, locked decisions, scope, open questions. The contract every other doc consumes. |
| 001 | Registration | Contract locked in 000; standalone doc not yet drafted | The metadata-map shape for `reg-event-*`/`reg-sub`/`reg-fx`/`reg-cofx`; source-coord capture; Malli specs; named handler fns. **The contract is locked in [000-Vision.md §Registration shape](000-Vision.md#registration-shape-ep-001-territory) and consumed by 002 (frames), 005 (machines), 007 (stories).** A dedicated 001 doc will formalise the write-up; until then, 000 is authoritative for handler metadata. |
| 002 | [Frames](002-Frames.md) | Drafting (reframed per reorient) | The load-bearing EP. **Frame redefined as an isolated runtime boundary** (multi-instance, per-test, per-request, per-session). Pattern contract: explicit-frame addressing. CLJS reference: React context as an optimisation atop that contract. Keyword identity, `:re-frame/default` fallback, run-to-completion drain semantics, machines-as-event-handlers foundation hooks, id-valued override seam. |
| 003 | Reusable Components | Subsumed | Subsumed by 002 (frames + `frame-provider`) and 004 (view registration). No separate doc planned. |
| 004 | [Views](004-Views.md) | Drafting (v1-required, reframed per reorient) | Pattern view contract: pure `(state, props) → render-tree`; render-tree is serialisable data. CLJS reference: `reg-view` with frame-bound `dispatch`/`subscribe`; **Var reference is the canonical hiccup form**; Form-1 is canonical; Form-2/3 are escape hatches. Plain-Reagent-fn footgun now raises a loud trace warning. SSR-renderable per [011](011-SSR.md). |
| 005 | [State Machines](005-StateMachines.md) | Drafting | Patterns and grammar for state-machine-flavoured event handlers, layered on the foundation hooks in 002 (machines as event handlers; transition-table-as-handler-body; pure transition contract). Independent of EP 004 and EP 007 — stories may *use* machines but don't structurally depend on them. Disposition: post-v1. |
| 006 | Reactive Substrate | Not yet drafted (v1 territory; SSR forces it) | Decouple re-frame from Reagent at the architecture level (substrate-agnostic re-frame). Pulled toward v1 by EP 011 — substrate-agnostic rendering is what SSR demands. Reagent stays as the default CLJS adapter; the decoupling is structural. Cooperative-rendering-substrate variant remains post-v1. |
| 007 | [Stories, Variants, and Workspaces](007-Stories.md) | Drafting | Storybook/Histoire/devcards-class tooling. Three-way split: Story (topic) / Variant (state) / Workspace (layout). Disposition: framework hooks in v1, library post-v1. Builds on EP 008's testing infrastructure. |
| 008 | [Testing](008-Testing.md) | Drafting (v1-required) | Test fixtures (`make-frame`/`destroy-frame`/`with-frame`), synchronous triggers, per-test fx/interceptor stubbing, headless sub/machine evaluation, framework adapters (cljs.test, clojure.test, kaocha, re-frame-test compat), JVM-runnable test suites. v1 ships this. Forward-compatible with EP 007. |
| 009 | [Instrumentation, Tracing, Performance](009-Instrumentation.md) | Drafting (v1-required) | The trace event model, the `register-trace-cb` listener API with batched delivery, hot-path discipline, Chrome Performance API integration, **structured error contract with 10 categories**, `reg-event-error-handler` policy mechanism, and forward-compat with 10x / re-frame-pair. v1 ships this. |
| 010 | [Schemas (CLJS reference)](010-Schemas.md) | Drafting (v1-required for CLJS) | Malli-based schemas attached to every `reg-*` via `:spec`; path-based `app-db` schemas via `reg-app-schema`; validation timing, dev-vs-prod elision, boundary-validation interceptor. *Schemas are opt-in at the pattern level — dynamically typed hosts use schemas (Malli/Pydantic/Zod); statically typed hosts use types and may omit a runtime schema layer.* |
| 011 | [SSR & Hydration](011-SSR.md) | Drafting (v1-required) | Server frame lifecycle (per-request), pure hiccup → HTML emitter (JVM-runnable), `:platforms` metadata for fx, `:rf/hydrate` event, hydration-mismatch detection via trace events. View pure-fn requirement; id-valued override seam. Streaming SSR post-v1. |
| 012 | [Routing](012-Routing.md) | Drafting (v1-required) | URL ↔ frame state contract. Routes are registry entries; navigation is an event; `:route` is a sub. Bidirectional `match-url` / `route-url` are pure helpers. Same handler runs server- and client-side for SSR. Guards as interceptors; nested routes via id-prefix convention. |
| — | [MIGRATION](MIGRATION.md) | Living (CLJS reference only) | AI-agent prompt for migrating re-frame v1.x codebases to the re-frame2 CLJS reference. Other-language implementations are greenfield, no upgrade obligation. |
| — | [Construction Prompts](Construction-Prompts.md) | Drafting (CP-1 to CP-9 filled) | Per-kind templates for AI-driven scaffolding (event, sub, fx, view, machine, feature, route, schema, SSR). Sibling to MIGRATION.md. |
| — | [AI Audit](AI-Audit.md) | First pass | Score of every EP against the 8 AI-first properties; surfaces gaps and recommends next actions. |
| — | [Spec-Internal Schemas](Spec-Schemas.md) | Drafting | Malli (CLJS reference) shape descriptions for the spec's own runtime shapes — dispatch envelope, registration metadata, effect map, trace event, transition table, hydration payload, frame meta. Static hosts express the same shapes as types. |
| — | [Conformance Corpus](conformance/README.md) | Skeleton | Fixture-based test suite: canonical interactions in EDN, expected emissions in EDN. The mechanism by which "AI can one-shot the implementation in any host" is verified. |
| — | [Pattern — Remote Data](Pattern-RemoteData.md) | Drafting (v1 — convention) | Standard request-lifecycle slice (`:status`/`:data`/`:error`/`:loaded-at`/`:attempt`); the four standard events; SSR-friendly. Convention, not EP. |
| — | [Pattern — Forms](Pattern-Forms.md) | Drafting (v1 — convention) | Standard form slice (`:draft`/`:submitted`/`:status`/`:errors`/`:touched`); seven standard events; per-field error display only on touched. Convention, not EP. |
| — | [Worked examples](../../examples/) | Drafting | The [7GUIs series](../../examples/7guis/README.md) (counter / temperature / flight-booker / timer / CRUD / circle-drawer; cells deferred) and a [login feature](../../examples/login/core.cljs). Demonstrates every construction prompt in working code. |

## Reading order

For someone new to the re-frame2 design:

1. [reorient.md](reorient.md) — the orientation. AI-first, simplest-computational-model, Clojure ethos, language-agnostic, SSR-capable.
2. The rationale docs in re-frame's existing doc set — [on-dynamics](https://day8.github.io/re-frame/on-dynamics/), [data-oriented-design](https://day8.github.io/re-frame/data-oriented-design/), [a-loop](https://day8.github.io/re-frame/a-loop/), [application-state](https://day8.github.io/re-frame/application-state/). These justify *why* the pattern has the shape it does.
3. [000-Vision.md](000-Vision.md) — the principles, the pattern's minimal core, and the CLJS reference implementation choices.
4. [002-Frames.md](002-Frames.md) — the foundation.
5. [005-StateMachines.md](005-StateMachines.md) and [007-Stories.md](007-Stories.md) — examples of how downstream EPs consume the foundation.
6. [Construction-Prompts.md](Construction-Prompts.md) — what AI-scaffolded code looks like in this pattern.
7. [Spec-Schemas.md](Spec-Schemas.md) — the conformance contract.
8. [MIGRATION.md](MIGRATION.md) — the migration story (CLJS reference only).

For someone implementing this pattern in another language:

1. [reorient.md](reorient.md) — what the pattern is.
2. [000-Vision.md §The pattern](000-Vision.md) — the minimal core.
3. [Spec-Schemas.md](Spec-Schemas.md) — canonical shapes your runtime must produce. Schema-bearing hosts validate against these at runtime; statically typed hosts express the same shapes as types and catch mismatches at compile time. Either way, conformance.
4. [009 §Error contract](009-Instrumentation.md) — the structured error envelope.
5. [011-SSR.md](011-SSR.md) — the server/client lifecycle.
6. [Construction-Prompts.md](Construction-Prompts.md) — what the user-facing surface looks like; adapt to your host's idioms.

## Conventions

- **Cross-references between EPs use Markdown links** to make the dependency graph navigable.
- **Locked decisions** are stated as imperatives in 000's principles section; per-EP docs reference them rather than restating.
- **Open questions** are recorded in each doc with stable IDs (`OQ-N` in 000, `OQ-F-N` in 002, `OQ-M-N` in 005, `S-N` in 007). When an OQ resolves, mark the resolution in the doc; don't delete the OQ until a maintenance pass.
- **MIGRATION.md is updated in the same change** as any design decision that introduces, alters, or removes breakage. See its "Maintainer note" section.
- **Discipline:** downstream EPs (003, 004, 005, 006, 007) **consume** the 000+002 foundation; they **do not** introduce new global registries, dispatch types, effect substrates, or component substrates. If a downstream design seems to need such a primitive, refit the design rather than reshape the foundation.

## Versioning

These documents are versioned alongside the re-frame source in this repository. Each merge that updates a design decision also updates the relevant EP and MIGRATION.md.
