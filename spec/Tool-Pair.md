# Tool-Pair — runtime contract for pair-shaped AI tools

> **Type:** Reference
> The runtime surface re-frame2 commits to so that pair-shaped tools — equivalents of [day8/re-frame-pair](https://github.com/day8/re-frame-pair) — can attach to a running re-frame2 application and let an AI agent inspect, dispatch, hot-swap, and time-travel against it.

## What this Spec is and isn't

**This Spec is** the *runtime contract* — the set of public capabilities re-frame2 exposes that pair-shaped tools rely on. It tells an implementer "ship these capabilities and a pair tool can be built against you."

> **Audit lineage.** Several surfaces below (`register-epoch-cb`, the structured `:sub-runs` / `:renders` / `:effects` slots on `:rf/epoch-record`, `:dispatch-id` / `:parent-dispatch-id` correlation, the `:origin` dispatch opt, `app-schemas` introspection, and the §Source-mapping helper enumeration) were added to this Spec following a cross-reference audit against [day8/re-frame-pair](https://github.com/day8/re-frame-pair)'s actual source — the upstream tool consumed surfaces this contract had not yet committed to. The audit is single-sourced here; downstream Specs (009 / 010 / 002 / Spec-Schemas) carry the additive normative text without re-citing the audit.

> **Source-of-truth note:** Tool-Pair.md is the **canonical surface contract** for the time-travel / epoch-history capabilities and the trace-stream consumption shape. [API.md](API.md) reproduces these signatures (under §Epoch history) for fast lookup, but the *normative* descriptions live here; if the two drift, this Spec wins. The `epoch-history`, `restore-epoch`, and `(rf/configure :epoch-history {:depth N})` surface, plus the `:rf.epoch/snapshotted` / `:rf.epoch/restored` / `:rf.registry/handler-replaced` trace events, are pinned here and referenced from API.md.

**This Spec is not** the pair tool itself. The actual pair tool — the Claude integration, the prompt design, the nREPL middleware — lives outside the spec, in a separate repository (the upstream is [day8/re-frame-pair](https://github.com/day8/re-frame-pair)). re-frame2 ships *its half* of the contract; the tool ships its half.

The architecture mirrors how re-frame2 relates to [re-frame-10x](https://github.com/day8/re-frame-10x): the spec defines a stable contract (the trace stream, the registrar query API, the public envelope shape); the tool consumes it. Multiple tools can consume the same contract.

## What pair-shaped tools do

(Summarising the capability surface of [day8/re-frame-pair](https://github.com/day8/re-frame-pair) so the contract makes sense.)

A pair tool is an AI/REPL companion that attaches to a running re-frame2 app. It lets the agent:

- **Inspect** — read the current state of any frame's `app-db`, any subscription, any registration.
- **Trace** — observe the trace stream (live or historical), per-event domino-by-domino.
- **Dispatch** — fire events into any frame, synchronously or async.
- **Hot-swap** — replace a registered handler with new code, observe the effect.
- **Time-travel** — walk backward through the epoch history of a frame, snapshot state at each point, restore an earlier state.
- **Stub effects** — temporarily redirect a registered fx (e.g., `:http`) to a stub, run experiments, restore.
- **Map source** — given a registration id or a UI event, locate the source coordinates.
- **REPL-eval** — execute arbitrary expressions in the runtime's namespace context.
- **Watch / narrate** — set up subscriptions to a stream of trace events and report each one as it fires.

The "9-step empirical loop" (observe → inspect → hypothesise → probe → compare → edit) is the dominant interaction shape; pair-shaped tools are designed to make that loop fast.

## What re-frame2 commits to (the runtime contract)

Each capability the pair tool needs maps to a re-frame2 surface. The contract has two parts:

- **Existing-surface map (this section).** Every capability except time-travel is already specified in other Specs (001 / 002 / 008 / 009). The table below maps capability → surface → source-of-truth. No new commitments here.
- **Time-travel commitments (§Time-travel below).** Epoch recording, query, and restore are new in re-frame2 and locked here. This is the only section adding surface; everything else is reproduction.

The two parts together form the **consolidated contract** — the complete set of surfaces a pair-shaped tool may consume. [§How AI tools attach](#how-ai-tools-attach) reproduces the same surfaces re-organised by *what the tool needs to do* (rather than *what re-frame2 commits to*); it is a view, not additional commitments.

| Capability | re-frame2 surface | Spec |
|---|---|---|
| **Read `app-db`** | `(rf/get-frame-db frame-id)` returns the current `app-db` value (a plain map) | [002 §The public registrar query API](002-Frames.md#the-public-registrar-query-api) |
| **Read sub values** | `(rf/compute-sub query-v db-value)` runs a sub against an `app-db` value | [008](008-Testing.md) |
| **Read registry** | `(rf/handlers kind)`, `(rf/handler-meta kind id)`, `(rf/frame-ids)`, `(rf/frame-meta id)` | [001-Registration](001-Registration.md), [002](002-Frames.md) |
| **Dispatch** | `(rf/dispatch ev opts)`, `(rf/dispatch-sync ev opts)` with `:frame` opt | [002 §Routing](002-Frames.md#routing-the-dispatch-envelope) |
| **Trace stream** | `(rf/register-trace-cb! key callback)` plus structured trace events | [009](009-Instrumentation.md) |
| **Hot-swap handlers** | Re-registration replaces; emits `:rf.registry/handler-replaced` trace | [001 §Hot-reload semantics](001-Registration.md#hot-reload-semantics) |
| **Stub fx** | `:fx-overrides` map (id-valued at the pattern level) on `dispatch` opts or `reg-frame` metadata | [002 §Per-frame and per-call overrides](002-Frames.md#per-frame-and-per-call-overrides) |
| **Source coordinates** | `:ns`/`:line`/`:file`/`:column` on every registration's metadata; mandatory `data-rf2-source-coord` DOM annotation per Spec 006 | [001 §Source-coordinate capture](001-Registration.md#source-coordinate-capture-cljs-reference), [006 §Source-coord annotation](006-ReactiveSubstrate.md#source-coord-annotation-mandatory-rf2-z7f7--rf2-z9n1) |
| **Inspect registered schemas** | `(rf/app-schemas frame-id)`, `(rf/app-schema-at path opts)`, `(rf/app-schemas-digest opts)` | [010 §Schemas as a tooling and agent surface](010-Schemas.md#schemas-as-a-tooling-and-agent-surface) |
| **Errors** | Structured `:rf.error/*` trace events with category + tags | [009 §Error contract](009-Instrumentation.md#error-contract) |

This much is **already specified**. A pair tool built against re-frame2 (and conforming with [day8/re-frame-pair](https://github.com/day8/re-frame-pair)) needs nothing more than these surfaces to do everything in the capability list above except time-travel.

The capability that requires *new* commitments is **time-travel**, addressed below.

## Time-travel: epoch snapshots and undo

The runtime contract for time-travel:

**Recording.** Every event-cascade settle (drain reaching empty queue) marks an epoch boundary. The runtime records, per frame, an `:rf/epoch-record` (per [Spec-Schemas](Spec-Schemas.md#rfepoch-record)) consisting of `:epoch-id`, `:frame`, `:committed-at`, `:event-id`, `:trigger-event`, `:db-before`, `:db-after`, and (optionally) `:trace-events`, plus the structured per-epoch projections `:sub-runs`, `:renders`, and `:effects` (each pre-derived from `:trace-events`; see [Spec-Schemas §`:rf/epoch-record`](Spec-Schemas.md#rfepoch-record) for shapes). Pair tools route diagnostics off the structured slots — cache-hit-vs-rerun analysis (`:sub-runs[*].:recomputed?`), render-key attribution (`:renders[*].:render-key`, the tuple `[<view-id> <instance-token>]` per [004 §Render-tree primitives](004-Views.md#render-tree-primitives) — rf2-t5tx Option C / rf2-piag), and fx cascade outcome (`:effects[*].:outcome`) — without re-folding the raw trace stream each epoch.

**Ordering.** Epochs within a frame are totally ordered by drain-completion time. Across frames, ordering is per-frame only — there is no global epoch sequence.

**Bounded history.** The runtime keeps the last *N* epochs per frame (default 50, configurable via `(rf/configure :epoch-history {:depth N})`). Older epochs are discarded.

**Query.** `(rf/epoch-history frame-id)` returns the vector of `:rf/epoch-record` values for the frame, oldest-first.

**Restore.** `(rf/restore-epoch frame-id epoch-id)` rewinds the frame's `app-db` to the named epoch's `:db-after` value. Emits `:rf.epoch/restored`.

**Restore failure modes.** `restore-epoch` is a query against a finite per-frame history; the restore can fail for distinct, named reasons. Each is an error trace event with a stable `:operation` key under the reserved `:rf.epoch/*` namespace; the call is a no-op on failure (the frame's `app-db` is unchanged):

| Failure | `:operation` | When it fires | `:tags` |
|---|---|---|---|
| **Unknown frame** | `:rf.error/no-such-handler` (kind `:frame`) | `frame-id` does not name a registered frame. | `{:kind :frame, :frame-id <id>}` |
| **Unknown epoch** | `:rf.epoch/restore-unknown-epoch` | `epoch-id` is not in the frame's current epoch history (either never recorded or aged out by `:depth`). | `{:frame <id>, :epoch-id <id>, :history-size <n>}` |
| **Schema mismatch** | `:rf.epoch/restore-schema-mismatch` | The recorded `:db-after` no longer validates against the currently-registered `app-schemas` set (a schema was added, tightened, or replaced since the snapshot was taken). | `{:frame <id>, :epoch-id <id>, :schema-digest-recorded <s>, :schema-digest-current <s>, :failing-paths [<path> ...]}` |
| **Missing handler** | `:rf.epoch/restore-missing-handler` | The recorded `app-db` references a registered-id (e.g. an active machine at `[:rf/machines <id>]`, a registered route currently in `:route`) that is no longer present in the registrar. Restoring would leave the frame referencing dangling ids. | `{:frame <id>, :epoch-id <id>, :missing [{:kind <kind>, :id <id>} ...]}` |
| **Version mismatch** | `:rf.epoch/restore-version-mismatch` | The frame's recorded `:rf/snapshot-version` (per [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot)) is incompatible with the currently-loaded machine definition. Hot-reload moved the machine forward; the older snapshot can no longer be interpreted. | `{:frame <id>, :epoch-id <id>, :machine-id <id>, :version-recorded <int>, :version-current <int>}` |
| **Concurrent-drain rejection** | `:rf.epoch/restore-during-drain` | `restore-epoch` was called while the frame's run-to-completion drain is still in flight (per [002 §Run-to-completion dispatch](002-Frames.md#run-to-completion-dispatch-drain-semantics)). Restore is rejected; the user retries after settle. | `{:frame <id>, :epoch-id <id>}` |

All six failures have `:op-type :error` and `:recovery :no-recovery`. Pair tools display the `:operation` and `:tags` to the user; the reserved `:rf.epoch/*` namespace lets tools route restore failures distinctly from frame-lookup errors. The failure surface is closed for v1 — additional categories require a Spec-ulation increment.

**Restore caveat.** Even a *successful* restore rewinds `app-db` only; effects already fired (HTTP requests sent, navigation pushed, localStorage written) are not reversed. Pair-shaped tools surface this caveat in their UI before applying a restore.

**Production elision.** Per [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code) the trace surface, schema validation, registrar trace emit, and epoch-history machinery share a single compile-time gate (`re-frame.interop/debug-enabled?`, alias of `goog.DEBUG`); production builds (`:advanced` + `goog.DEBUG=false`) elide all of it. CI's `npm run test:elision` job (Spec 009 §Production-elision verification) asserts the contract holds for every gated surface, including the epoch-history primitives once they land.

## REPL-eval

The pair tool's "execute arbitrary expression" capability is the **host's REPL** (CLJS: nREPL via cider; Python: IPython; etc.) — re-frame2 doesn't ship an evaluator, just exposes its data structures. An nREPL session attached to a running re-frame2 app can already see `re-frame.db/app-db` (or its substrate-agnostic equivalent), the registrar, and any namespace-resolvable function.

The CLJS reference's commitment: **public APIs** (everything in `re-frame.core`) are stable for pair-tool consumption. **Private namespaces** (`re-frame.db`, `re-frame.router`, `re-frame.subs`, `re-frame.events`, `re-frame.registrar`) are off-contract — they may change between versions. Per [MIGRATION §M-1](MIGRATION.md), tools that reach into private namespaces will need to migrate.

The pair tool is encouraged to use only public APIs. If it needs something not public, file a Spec issue.

## Source-mapping UI clicks back to code

The "which button is at `src/app/profile/view.cljs:84`?" capability requires every render-tree node — every registered view, every hiccup tag — to carry source coords. re-frame2's view registrations include `:ns`/`:line`/`:file`. The CLJS reference additionally:

- Captures source coords at every `reg-view` macro expansion (`:ns` / `:file` / `:line` / `:column`).
- **Annotates rendered DOM** with a `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` attribute pointing back to the registration that produced it. This is **mandatory** in re-frame2 per [Spec 006 §Source-coord annotation](006-ReactiveSubstrate.md#source-coord-annotation-mandatory-rf2-z7f7--rf2-z9n1) — every substrate adapter whose host has a DOM-attribute concept MUST inject the attribute. Annotation is dev-only and gated on `interop/debug-enabled?` (the CLJS mirror of `goog.DEBUG`); production builds elide the attribute via dead-code elimination so there is no DOM-bytes cost in shipped bundles.

With the annotation in place, a pair tool can take a click position, read the nearest annotation, and resolve back to a source coordinate. Documented exemption (per Spec 006 §Source-coord annotation): components returning React Fragments, host-component heads (`:>`), or other non-DOM roots are exempt; pair tools fall back to `(rf/handler-meta :view id)` for those nodes.

### Where the DOM-to-source helpers live (re-frame2 vs tool)

The audit found the upstream pair tool ships `dom/source-at`, `dom/find-by-src`, and `dom/fire-click-at-src` helpers (it currently parses re-com's `data-rc-src` attribute, but the shape is general). Pair-shaped tools need *some* DOM-to-source bridge; the question is whether the helpers themselves are part of re-frame2's contract or live in the consuming tool.

**re-frame2's commitment is the attribute, not the helpers.** Specifically:

- The runtime emits the `data-rf2-source-coord` attribute on rendered DOM nodes when source-annotation is enabled. The attribute's *value format* is the contract (an opaque string the tool parses to recover `:ns` / `:line` / `:file` — schema in [Spec-Schemas](Spec-Schemas.md)).
- The framework does **not** ship `dom-source-at` / `find-by-src` / `fire-click-at-src` style helpers. These are tool-side: the pair tool reads the attribute via its own host's DOM access (`document.querySelector` in CLJS, `page.locator` in Playwright-driven flows, etc.) and resolves the source coordinate locally.

**Why tool-side, not framework-side:** the helpers depend on host-specific DOM access that re-frame2 the framework does not assume — a pair tool driving a browser via CDP, a server-rendered diagnostic dump, or a static analyzer all want different "lookup the attribute" implementations. Pinning a single helper signature here would either over-constrain consumers or under-serve them. The framework commits to the attribute (stable, cross-host, parseable); the consuming tool ships the host-appropriate query primitives on top.

A future re-frame2 minor version may introduce framework-side helpers if the ecosystem converges on a single shape; the attribute contract is forward-compatible with that addition.

## How AI tools attach

The runtime contract above is **complete and self-contained.** A pair-shaped tool — re-frame-pair, a Claude integration, a custom debug panel, a story tool, a future pair-improver — attaches to a running re-frame2 application using only the framework primitives listed below. **No re-frame-10x dependency is required**, and none should be assumed.

The full attachment surface, from the tool's point of view:

| Need | Surface | Spec |
|---|---|---|
| Receive live trace events | `(rf/register-trace-cb! :my-tool callback)` | [009 §The listener API](009-Instrumentation.md#the-listener-api) |
| Receive per-drain assembled epoch records | `(rf/register-epoch-cb :my-tool callback)` | [009 §The listener API](009-Instrumentation.md#the-listener-api) |
| Read recent trace history (events that already fired) | `(rf/trace-buffer)` (with optional filter map) | [009 §Retain-N trace ring buffer](009-Instrumentation.md#retain-n-trace-ring-buffer-dev-only) |
| Read epoch history per frame | `(rf/epoch-history frame-id)` | [§Time-travel](#time-travel-epoch-snapshots-and-undo) |
| Restore an epoch | `(rf/restore-epoch frame-id epoch-id)` | [§Time-travel](#time-travel-epoch-snapshots-and-undo) |
| Configure history depth | `(rf/configure :epoch-history {:depth N})` and `(rf/configure :trace-buffer {:depth N})` | [API.md](API.md) |
| Inspect registered app-db schemas | `(rf/app-schemas frame-id)` | [010 §Schemas as a tooling and agent surface](010-Schemas.md#schemas-as-a-tooling-and-agent-surface) |
| Tag dispatches by actor (e.g. tool vs app) | `:origin` opt on `(rf/dispatch event opts)` | [002 §Dispatch origin tagging](002-Frames.md#dispatch-origin-tagging) |
| Correlate a dispatch cascade | `:dispatch-id` + `:parent-dispatch-id` on `:event/dispatched` traces | [009 §Dispatch correlation](009-Instrumentation.md#dispatch-correlation-dispatch-id--parent-dispatch-id) |
| Enumerate frames | `(rf/frame-ids)`, `(rf/frame-meta id)` | [002 §Public registrar query API](002-Frames.md#the-public-registrar-query-api) |
| Read a frame's app-db | `(rf/get-frame-db frame-id)` / `(rf/snapshot-of path opts)` | [002 §Public registrar query API](002-Frames.md#the-public-registrar-query-api) |
| Inspect the registry | `(rf/handlers kind)`, `(rf/handler-meta kind id)` | [001](001-Registration.md), [002](002-Frames.md) |
| Enumerate machines | `(rf/machines)`, `(rf/machine-meta id)` | [005 §Querying machines](005-StateMachines.md#querying-machines) |
| Inspect the sub-cache (CLJS-only) | `(rf/sub-cache frame-id)` | [002 §Public registrar query API](002-Frames.md#the-public-registrar-query-api) |
| Source coords for any registration | `:ns`/`:line`/`:file` keys on `(handler-meta ...)` return | [001 §Source-coordinate capture](001-Registration.md#source-coordinate-capture-cljs-reference) |
| Dispatch | `(rf/dispatch event opts)` / `(rf/dispatch-sync event opts)` | [002 §Routing](002-Frames.md#routing-the-dispatch-envelope) |
| Stub fx for an experiment | `:fx-overrides {:http stub-id}` on `dispatch` opts | [002 §Per-frame and per-call overrides](002-Frames.md#per-frame-and-per-call-overrides) |
| Hot-swap a handler | Re-call `(rf/reg-event-fx id ...)`; `:rf.registry/handler-replaced` trace fires | [001 §Hot-reload semantics](001-Registration.md#hot-reload-semantics) |
| REPL eval against the runtime | The host's REPL (nREPL+CIDER for CLJS); private namespaces are off-contract | [§REPL-eval](#repl-eval) |

The consumption pattern is therefore:

> **A pair-shaped tool registers as a trace listener (and/or as an epoch listener for assembled per-cascade records), reads recent history from the trace buffer, queries the registrar for shape, walks the epoch history for time-travel, and dispatches into frames to drive experiments. That's the entire surface.**

Two listener shapes coexist by design: `register-trace-cb!` is the **raw** stream — every event the runtime emits, fine-grained — used by tools that need per-emit detail (custom recorders, error-monitor forwarders, timing aggregators). `register-epoch-cb` is the **assembled** stream — one fully-shaped `:rf/epoch-record` per drain-settle, with the structured `:sub-runs` / `:renders` / `:effects` projections already computed — used by tools that route diagnostics off "what just happened in this cascade" rather than reconstructing it from the raw trace each time. Pair-shaped tools typically prefer the assembled stream for routing and reach for the raw stream only when they need detail the projection drops.

This is **dev-only** end-to-end — every primitive listed above elides in production builds (per [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code)). Pair-shaped tools do not ship in production binaries.

### Implications for downstream tools

- **re-frame-pair** (the upstream nREPL companion) consumes only the surfaces above. It depends on re-frame2; it does not depend on re-frame-10x.
- **A future re-frame-10x v2** can be rewritten as a renderer of the same surfaces — a registered trace listener, a consumer of `epoch-history`, a query consumer of the registrar, a UI on top. 10x and pair share the substrate; one does not depend on the other.
- **Custom debug panels, story tools (Spec 007), and pair-improver-style skills** consume the same surface. Multi-tool coexistence is the expected default — multiple `register-trace-cb!` keys, multiple readers of the trace buffer, multiple consumers of the registrar. Listener ordering is not contract (per [009 §Listener ordering](009-Instrumentation.md#listener-ordering)).

The framework is **infrastructure-complete** for AI-tool consumption: data shapes, query APIs, retention policies, configuration knobs, production elision. Downstream tools own *presentation and orchestration*; they do not need to ship infrastructure that should live in the framework.

## What pair-shaped tools NOT to ship as part of re-frame2

- **The Claude integration** itself (prompts, retrieval, model selection). Lives in the pair tool.
- **The nREPL middleware** that exposes the runtime to the agent. Specific to the host environment.
- **The conversational interface** ("Tell me about every `:checkout/*` event"). The pair tool's job to prompt-engineer; re-frame2 just ships data.
- **Skill-shaped retrospective analysis.** That is a separate, post-v1 artefact — a Claude skill (not a runtime tool) that reviews pair sessions and proposes improvements to the pair tool itself. Reference: [re-frame-pair-improver](https://github.com/day8/re-frame-pair-improver).

## Future-compat commitments

Per the philosophy of [Spec-ulation](Principles.md), the pair-tool runtime contract grows additively:

- **Trace event categories** are stable; new categories are added with new `:operation` keywords.
- **Registry query API** signatures are stable; new query functions are additive.
- **Epoch history fields** can grow new keys (open map), never remove.
- **The `:rf.epoch/*` op-types** are reserved for re-frame2's epoch machinery.

The pair tool can rely on all of these surviving across re-frame2 minor versions. Major versions will document any changes.

## Cross-references

- [day8/re-frame-pair (upstream)](https://github.com/day8/re-frame-pair) — the original tool this contract is shaped to support.
- [001-Registration.md](001-Registration.md) — the registrar surface for inspecting and hot-swapping.
- [002-Frames.md](002-Frames.md) — frame-targeted dispatch, frame inspection.
- [009-Instrumentation.md](009-Instrumentation.md) — the trace stream and error contract.
- [011-SSR.md](011-SSR.md) — server-side runtime is the same contract; pair tools work there too.
- [Spec-Schemas §`:rf/epoch-record`](Spec-Schemas.md#rfepoch-record) — the recorded shape.
- [re-frame-pair-improver](https://github.com/day8/re-frame-pair-improver) — post-v1 companion (Claude skill).

---

## Design notes (non-normative)

The runtime contract above is fixed; the notes below capture open design questions that do not affect the contract but inform tool authors.

- **Multi-frame pair UX.** With re-frame2's multi-frame, the pair tool needs an "operating frame" concept. Lean: hybrid (explicit context-set, implicit until reset).
- **Snapshot serialisation cost.** Persistent data structures share structure; configurable history depth lets users tune. Lazy serialisation is an optimisation.
- **Pair-improver feedback loop.** The [re-frame-pair-improver](https://github.com/day8/re-frame-pair-improver) skill (post-v1) consumes a structured session log; format is EDN/JSON with a schema in [Spec-Schemas](Spec-Schemas.md).
