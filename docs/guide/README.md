# The re-frame2 Guide

This is the **human-facing** track. It tells the *why*, in narrative voice, with examples and opinions. It's marketing for the pattern.

If you're an AI agent or implementor, you want the [specification](../../spec/README.md) instead — drier, more precise, built for one-shot scaffolding.

> Coming straight to this folder? The site [front page](../index.md) has the three-pillar pitch (spec-first, views-derivative, tooling-first-class). This guide picks up from there and walks the pattern in narrative form, with ClojureScript code, end to end.

## Chapters

The guide is in two parts. The **core path** (chapters 01-11) builds the mental model in sequence — read these in order, end to end, and you have re-frame2. The **optional deep dives** (chapters 12-21) are *à la carte*: read them when the topic comes up, not as the next-link in the linear sequence.

Chapter **09 — Forms** sits between 08 and 10 as a side-track: not load-bearing for the mental model the way 08 is, but standard enough that most readers will want it before they reach 10. Skip it on a first read if you like; pick it up the first time you hit a non-trivial form.

Chapter **07 — Interceptors** is a similar side-track between 06 and 08: a deep-dive on the wrapping primitive every `:interceptors` slot in the guide bottoms out on. Most readers can skip it on a first pass — the core path doesn't require writing a custom interceptor. Pick it up the first time you want to wrap a handler (a logger, an undo interceptor, a recorder for tests).

Chapter **05 — Coeffects** is the matching side-track between 04 and 06: the *inputs* half of the handler's contract (`:db`, `:event`, and anything else `inject-cofx` injects). Skip it on a first read if your handlers only need `:db` and the event vector; pick it up the first time you hit `(inject-cofx :now)` in someone else's code, or want to test a handler that depends on the current time / a fresh UUID / a value from `localStorage`.

### Core path

Read these in order. Each chapter assumes the previous one.

| # | Chapter | What it covers |
|---|---|---|
| 01 | [Why re-frame2](01-why-re-frame2.md) | The argument. What problem this solves. Why it works. |
| 02 | [app-db](02-app-db.md) | The single immutable map every re-frame2 app pivots around — what it is, why immutable, the consequences. |
| 03 | [Your first app](03-your-first-app.md) | The counter, walked through in narrative. |
| 04 | [Events, state, and the cycle](04-events-state-cycle.md) | The core loop, with side-effects-as-data. |
| 04a | [Schemas](04a-schemas.md) | The Malli warmup — `reg-app-schema`, event `:spec`, dev-vs-production timing. Read before forms or HTTP, where schemas show up in volume. |
| 05 | [Coeffects](05-coeffects.md) | The matching *inputs* half — `reg-cofx`, `inject-cofx`, the side-causes (current time, GUIDs, localStorage). Optional side-track. |
| 06 | [Views and frames](06-views-and-frames.md) | What you put on the screen, and how you isolate state. |
| 06a | [Frames](06a-frames.md) | The dedicated frames chapter — what a frame is, when you need more than one, `reg-frame` / `make-frame` / `frame-provider`, `:rf/default`. Read before testing, stories, or SSR if multi-frame is new. |
| 07 | [Interceptors](07-interceptors.md) | The sandwich, the context map, custom `:before` / `:after`. Optional deep-dive. |
| 08 | [State machines](08-state-machines.md) | When the answer to a flow is a finite state machine. |
| 09 | [Forms](09-forms.md) | The standard form slice, seven-event lifecycle, error-visibility rule. |
| 10 | [Doing HTTP requests](10-doing-http-requests.md) | `:rf.http/managed` — the canonical request fx, end-to-end. |
| 11 | [The server side](11-server-side.md) | SSR and hydration without losing your mind. |

If you're impatient, read [01](01-why-re-frame2.md) and skip to [03](03-your-first-app.md). If you're skeptical, [01](01-why-re-frame2.md) is where the argument lives.

### Optional deep dives

Read these when the topic comes up — not as part of the linear sequence. They're independent of one another.

| # | Chapter | When to read it |
|---|---|---|
| 12 | [The dynamic-model story](12-the-dynamic-model.md) | You want the long-form essay on *why* less-powerful is more. Skippable for "I just want to write code" readers. |
| 13 | [Testing](13-testing.md) | You're about to write tests — `re-frame.test-support`, frame fixtures, JVM-vs-CLJS boundary, conformance. |
| 14 | [Errors and how to handle them](14-errors.md) | The `:rf.error/*` taxonomy, the trace-listener surface, `:on-error` policy per frame, recovery semantics, error projectors, and testing error paths. |
| 15 | [Tooling](15-devtools-and-pair-tools.md) | The third-pillar pitch: trace bus, epochs, time-travel, source-coords, and the tools that consume them (`re-frame-pair2`, `re-frame2-story`, Causa). |
| 16 | [Performance](16-performance.md) | Your page feels slow — the four shapes of slowness (big props, deep `=`, inline callbacks, expensive subs), the framework's answers, and the `rf:` User Timing surface. |
| 17 | [Routing](17-routing.md) | Your app needs URL ↔ state — `reg-route`, navigation, the `:rf/route` slice, `:on-match`, the not-found route. Tutorial half of the routing surface. |
| 17a | [Routing: reference and advanced topics](17a-routing-reference.md) | The per-topic reference half — `:on-error`, the full nav-token walkthrough, the `:can-leave` protocol, query-string defaults, multi-frame routing, the pure `match-url` / `route-url` helpers, and a RealWorld worked example. Read sections when the topic comes up. |
| 18 | [From re-frame v1](18-from-re-frame-v1.md) | You're migrating an existing re-frame v1 app. Skip if re-frame2 is your starting point. The chapter is appendix-shaped — deps to bump, the migration skill to run, and the broad categories of breakage to expect. |
| 19 | [Adapters](19-adapters.md) | You're choosing or wiring a view-layer substrate — Reagent, UIx, or Helix. Covers the substrate-agnostic story, the `init!` call shape, the three adapter packages, and the slim-Reagent option for ship-size builds. |
| 21 | [Stories](21-stories.md) | You want a Storybook-flavoured playground for your components — `reg-story`, `reg-variant`, the four-phase lifecycle, the `:rf.assert/*` vocabulary, and the agent-facing MCP surface. The narrative companion to [`tools/story/`](../../tools/story/). |
| 22 | [Trace forwarding to Datadog](22-trace-to-datadog.md) | You need observability on a running app — forward trace events to Datadog (or Honeycomb, Sentry, Mezmo, your in-house pipeline) via the trace bus, honouring `:sensitive?` and `:large?` elision through `rf/elide-wire-value`. Datadog-shaped; the recipe is generic. |
| 23 | [Privacy and size elision](23-privacy-and-elision.md) | The writer-side companion to ch.22 — declaring `:sensitive?` (drop) and `:large?` (elide-with-fetch) at the source so the trace stream can ride the firehose without leaking credentials, PII, or 5MB blobs. One primary site (Malli schema-slot meta), one escape hatch (handler-meta `:sensitive?` for cross-cutting cases), the `rf/elide-wire-value` walker, and the HTTP header / query-string denylists. |

### Close-out

| # | Chapter | What it covers |
|---|---|---|
| 20 | [Where to go next](20-where-next.md) | A one-screen exit pointer — examples, pattern docs, the API ref, the runtime companion docs, the spec. Read this when you finish the chapters and want to know "now what?" |

### Worked examples

Once you've finished the core path, read the [worked examples](../../examples/README.md) — pedagogical sketches first (counter, login, routing, ssr, managed-http-counter, state-machine-walkthrough), then benchmarks (todomvc, 7GUIs, nine-states), then the RealWorld scaffold. Fifteen examples total, each with a Playwright smoke spec; the catalogue maps each one to the Specs it exercises.

## After the chapters — the system-understanding bridge

The chapters give you the *story*. Two specification companion docs give you the *system* — they're written precisely (so the AI track can use them) but readable (so the human track can use them too). They're the bridge from narrative understanding to system understanding:

- **[Runtime-Architecture](../../spec/Runtime-Architecture.md)** — the runtime as eight components plus the interop layer, with an ASCII data-flow diagram tracing one event from `dispatch` to render.
- **[Cross-Spec-Interactions](../../spec/Cross-Spec-Interactions.md)** — the "if I do X while Y is happening" reference: twenty edge cases at the boundaries between specs.

These two are the natural next stop after the chapters and the examples. They're in the specification folder because the AI track also needs them, but they're written for both audiences.

## Voice

This guide is opinionated. It will tell you, with confidence, that a single source of truth is a good idea, that constrained execution models are easier to reason about than Turing-complete ones, and that putting state in 47 different React `useState` calls is a slow-motion accident. Where re-frame2 has made a choice, the chapter explains the choice and the alternatives we considered. Where the consensus in the broader SPA world is different from re-frame2's stance, we say so plainly.

If you want neutral coverage of every framework's tradeoffs, this isn't that. The [specification](../../spec/README.md) describes re-frame2 dispassionately; this guide is here to argue for it.

## What's not here

- **API reference.** API signatures and per-feature context live in the specification — reach for it once you finish the chapters; chapter 20 is the portal.
- **Migration prompts.** [`spec/MIGRATION.md`](../../spec/MIGRATION.md). If you're an AI doing a re-frame v1 → re-frame2 migration, that's the prompt.

## Pattern docs

The specification ships a family of **Pattern docs** — convention, not Spec, naming the canonical answer for recurring shapes that bottom out on the framework's primitives. They're closer in voice to this guide than to the Specs, and they're the right next stop when you're building a feature whose shape matches one of them. The chapters cross-reference each pattern at the natural introduction point; the full catalogue with one-line summaries lives at [chapter 20 — Where to go next](20-where-next.md#look-up-a-pattern-by-name).
