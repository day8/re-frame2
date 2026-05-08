# The re-frame2 Guide

This is the **human-facing** track. It tells the *why*, in narrative voice, with examples and opinions. It's marketing for the pattern.

If you're an AI agent or implementor, you want the [specification](../../spec/) instead — drier, more precise, built for one-shot scaffolding.

> Coming straight to this folder? The [root README](../../README.md) is the front door — it covers what re-frame2 is, what exists today, and the two AI-first perspectives. This guide picks up from there.

## Chapters

| # | Chapter | What it covers |
|---|---|---|
| 01 | [Why re-frame2](01-why-re-frame2.md) | The argument. What problem this solves. Why it works. |
| 02 | [Your first app](02-your-first-app.md) | The counter, walked through in narrative. |
| 03 | [Events, state, and the cycle](03-events-state-cycle.md) | The core loop, with side-effects-as-data. |
| 04 | [Views and frames](04-views-and-frames.md) | What you put on the screen, and how you isolate state. |
| 05 | [State machines](05-state-machines.md) | When the answer to a flow is a finite state machine. |
| 06 | [The server side](06-server-side.md) | SSR and hydration without losing your mind. |
| 07 | [From re-frame v1](07-from-re-frame-v1.md) | What's the same, what's different, what to do. |
| 08 | [The dynamic-model story](08-the-dynamic-model.md) | The deeper essay on *why* less-powerful is more. |

After the chapters: read the [worked examples](../../examples/README.md) — pedagogical sketches first (counter, login), then benchmarks (7GUIs, nine-states), then the RealWorld scaffold.

If you're impatient, read [01](01-why-re-frame2.md) and skip to [02](02-your-first-app.md). If you're skeptical, [01](01-why-re-frame2.md) is where the argument lives.

## After the chapters — the system-understanding bridge

The chapters give you the *story*. Two specification companion docs give you the *system* — they're written precisely (so the AI track can use them) but readable (so the human track can use them too). They're the bridge from narrative understanding to system understanding:

- **[Runtime-Architecture](../../spec/Runtime-Architecture.md)** — the bird's-eye view of the runtime as eight components (registrar, frame container, router, drain loop, `do-fx`, sub-cache, substrate adapter, trace bus) plus the interop layer. ASCII data-flow diagram tracing one event from `dispatch` to render. Three lifecycles (boot, per-event drain, teardown). The "what's new vs re-frame v1" table maps every component to inherited-from-v1 or new-in-v2. If the chapters left you wondering "but what's actually running underneath?" — this is the doc that tells you.
- **[Cross-Spec-Interactions](../../spec/Cross-Spec-Interactions.md)** — twenty edge cases at the boundaries between Specs. Frame disposal with active machines. Machines under SSR. Routing in SSR. Plain Reagent fns under non-default frames. Machine action throws. Hot-reload mid-cascade. Each entry names which Specs meet, the scenario, the decided behaviour, the reason. The doc reads as the "if I do X while Y is happening" reference — useful both when you're learning and when you're debugging.

These two are the natural next stop after the chapters and the examples. They're in the specification folder because the AI track also needs them, but they're written for both audiences.

## Voice

This guide is opinionated. It will tell you, with confidence, that a single source of truth is a good idea, that constrained execution models are easier to reason about than Turing-complete ones, and that putting state in 47 different React `useState` calls is a slow-motion accident. Where re-frame2 has made a choice, the chapter explains the choice and the alternatives we considered. Where the consensus in the broader SPA world is different from re-frame2's stance, we say so plainly.

If you want neutral coverage of every framework's tradeoffs, this isn't that. The [specification](../../spec/) describes re-frame2 dispassionately; this guide is here to argue for it.

## What's not here

- **API reference.** Look in the [specification](../../spec/) — `API.md` for signatures, the per-Spec docs for context.
- **Construction prompts.** The AI-facing scaffolding templates live in [`spec/Construction-Prompts.md`](../../spec/Construction-Prompts.md). They generate code that conforms to the same patterns this guide explains.
- **Migration prompts.** Likewise — [`spec/MIGRATION.md`](../../spec/MIGRATION.md). If you're an AI doing a re-frame v1 → re-frame2 migration, that's the prompt.

## Pattern docs

The specification ships a family of **Pattern docs** — convention, not Spec, naming the canonical answer for recurring shapes that bottom out on the framework's primitives. They're closer in voice to this guide than to the Specs, and they're the right next stop when you're building a feature whose shape matches one of them:

- [Pattern-AsyncEffect](../../spec/Pattern-AsyncEffect.md) — the generic post-work-await-reply shape.
- [Pattern-RemoteData](../../spec/Pattern-RemoteData.md) — HTTP requests with a standard 5-key lifecycle slice.
- [Pattern-Forms](../../spec/Pattern-Forms.md) — draft / submitted / status / per-field errors as a standard slice.
- [Pattern-Boot](../../spec/Pattern-Boot.md) — chained init with progress UI and fail-fatal points.
- [Pattern-WebSocket](../../spec/Pattern-WebSocket.md) — long-lived connection lifecycle modelled as a state machine.
- [Pattern-LongRunningWork](../../spec/Pattern-LongRunningWork.md) — chunked yielding or worker offload for CPU-heavy work.
- [Pattern-StaleDetection](../../spec/Pattern-StaleDetection.md) — the epoch idiom for ignoring superseded async results.

The chapters cross-reference pattern docs at the points where the pattern's shape is most natural to introduce. If a chapter mentions a pattern, the pattern doc is where the full story lives.
