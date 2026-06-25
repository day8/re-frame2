# The reference map

The guide teaches you the *model*; the spec defines the exact *contracts*. This page is the bridge between them — a map of *where each kind of precise answer lives*, not a copy of those answers.

Here's the situation it solves. You're productive. Then one day you hit a sharp edge: you need the exact shape of an [effect map](glossary.md#effect-map) (the data an [event handler](glossary.md#event-handler) returns to describe what should change), the full list of ways an HTTP request can fail, a function's complete signature. The guide's prose got you this far, but prose isn't where that precision lives. So which drawer do you open? This page is the index card that answers that, one surface at a time.

We won't reproduce the contracts here — they'd rot the moment the spec moved. Instead each section below points you at the *one* place that owns each kind of answer.

> **For JavaScript developers.** You already have this instinct. The *tutorial* gets you writing components; the day you hit `useEffect`'s exact dependency-array rules, or the precise return shape of Redux's `combineReducers`, you go straight to the API reference. Same move here — this page just tells you which reference to reach for.

## Start here: three layers, four entry points

Before the surface-by-surface map, one orientation. The docs come in three layers, and it helps to know which one you're standing in.

- **This guide** (tutorial, how-to, explanation) teaches you the *model* — the why and the how, prose-first. It splits three ways by *intent*: the [tutorial](tutorial/index.md) walks one app end to end; the [how-to recipes](how-to/index.md) answer "I need to do X" in isolation; the [concepts pages](concepts/index.md) explain one idea at a time, prose-first (app-db, events, subscriptions, effects, frames, machines, flows, routing, http, server-state, ssr, observability, errors). When you half-remember a concept and want the *explanation* rather than the *signature*, the concepts pages are the bridge between this map and the API reference.
- The **[API reference](../api/README.md)** is the signature lookup, organised by domain. Go there once you know the concept and just need the call shape.
- The **[spec](../../spec/README.md)** is the normative source: exhaustive, written for AI and implementors, and the thing every other layer is downstream of.

The rule when two sources seem to disagree: **the spec wins.** It's the artefact; everything else is a friendlier reading of it.

Four spec documents are *entry points* rather than contracts — they don't define a surface so much as help you find the one that does. Worth knowing by name:

- [Ownership](../../spec/Ownership.md) — the "where does X live?" matrix. Every contract surface maps to exactly one owning spec document, so when you don't know which document answers your question, start here. It's the spec's own table-of-contents-by-concept.
- [Conventions](../../spec/Conventions.md) — the reserved `:rf/*` namespace scheme, reserved fx-ids, reserved [app-db](glossary.md#app-db) keys, and packaging conventions. The "which names are mine and which are the framework's?" reference.
- [Principles](../../spec/Principles.md) — the nine practical principles the design serves, so you can see the *reasoning* behind a rule, not just the rule. Read this when a constraint feels arbitrary; it usually isn't.
- [The spec index](../../spec/README.md) — the full catalogue. It includes the `Pattern-*` documents, which name canonical shapes — [app boot](../../spec/Pattern-Boot.md), [websockets](../../spec/Pattern-WebSocket.md), [stale-reply detection](../../spec/Pattern-StaleDetection.md), [the nine render states](../../spec/Pattern-NineStates.md), [remote data](../../spec/Pattern-RemoteData.md), [forms](../../spec/Pattern-Forms.md), [long-running work](../../spec/Pattern-LongRunningWork.md), [reusable components](../../spec/Pattern-ReusableComponents.md), and more — handy when your problem turns out to be a recurring one with a known answer.

Two further documents are *companion indexes* — not contracts, but the place to land for a cross-cutting concern that no single owning spec fully holds:

- [Privacy](../../spec/Privacy.md) — the discoverability index for [data classification](glossary.md#data-classification): where every privacy primitive lives across the artefacts, the composition order from handler-exit to off-box wire, and what you declare to keep a value out of egress. It defers to [015-Data-Classification](../../spec/015-Data-Classification.md) for the normative contract; read it when you want the *map* of the privacy surface rather than one corner of it. The classification model is the path-based `:sensitive` / `:large` one (no taint, no propagation, fail-open hygiene — *not* a security boundary).
- [Security](../../spec/Security.md) — the threat model and the pattern-level "what is defended, and where each defense's contract lives." The mirror of Ownership: Ownership names *where* a surface lives, Security names *what* it protects. The CLJS-reference specifics (named functions, numeric defaults, the exact `:rf.error/*` keyword each safety check emits) live downstream in [implementation/SECURITY.md](../../implementation/SECURITY.md).

## The API surface, by domain

Now the map itself. Every public surface gets one row below. The API page gives signatures with intuition notes; the owning spec gives the normative contract for when intuition isn't enough.

| Domain | What it covers | API page | Owning spec |
|---|---|---|---|
| Core | `reg-event`, `reg-sub`, `dispatch`, `subscribe`, frames — the loop's registration and verb surface | [01 — Core](../api/01-core.md) | [001-Registration](../../spec/001-Registration.md), [002-Frames](../../spec/002-Frames.md) |
| Views | `reg-view` and the substrate-agnostic pure-view contract | [02 — Views](../api/02-views.md) | [004-Views](../../spec/004-Views.md) |
| Effects and interceptors | The closed `:db` + `:fx` effect map, `reg-fx` / `reg-cofx`, interceptors, fx-overrides | [03 — Effects](../api/03-effects.md) | [002-Frames](../../spec/002-Frames.md) |
| State machines | `reg-machine` and the transition-table grammar — hierarchy, `:after`, `:spawn`, parallel regions | [04 — Machines](../api/04-machines.md) | [005-StateMachines](../../spec/005-StateMachines.md) |
| Flows | `reg-flow` — derived values materialised into app-db so handlers can read them | [05 — Flows](../api/05-flows.md) | [013-Flows](../../spec/013-Flows.md) |
| Routing | `reg-route`, navigation events, the `:route` sub, blocking and not-found | [06 — Routing](../api/06-routing.md) | [012-Routing](../../spec/012-Routing.md) |
| HTTP | `:rf.http/managed` — decode pipeline, retry, abort, the closed failure taxonomy | [07 — HTTP](../api/07-http.md) | [014-HTTPRequests](../../spec/014-HTTPRequests.md) |
| Schemas and classification | `:schema` metadata, `reg-app-schema`, the `:sensitive` / `:large` classification effects | [08 — Schemas](../api/08-schemas.md) | [010-Schemas](../../spec/010-Schemas.md), [015-Data-Classification](../../spec/015-Data-Classification.md) |
| SSR | `render-to-string`, hydration, streaming boundaries | [09 — SSR](../api/09-ssr.md) | [011-SSR](../../spec/011-SSR.md) |
| Testing | Fixtures, `dispatch-sequence`, `compute-sub`, the helper namespaces below | [10 — Testing](../api/10-testing.md) | [008-Testing](../../spec/008-Testing.md) |
| Instrumentation | The dev trace bus, the always-on event / error emit substrates, the epoch buffer | [11 — Instrumentation](../api/11-instrumentation.md) | [009-Instrumentation](../../spec/009-Instrumentation.md), [Tool-Pair](../../spec/Tool-Pair.md) |
| Registrar queries | `registrations`, `handler-meta` — the read-side query API tools build on | [12 — Registrar](../api/12-registrar.md) | [001-Registration](../../spec/001-Registration.md) |
| Lifecycle | `init!` adapter selection at boot, adapter inspection, teardown | [13 — Lifecycle](../api/13-lifecycle.md) | [006-ReactiveSubstrate](../../spec/006-ReactiveSubstrate.md) |
| Adapters | The Reagent / UIx / Helix / reagent-slim substrate surfaces, `use-subscribe`, `frame-provider` | [14 — Adapters](../api/14-adapters.md) | [006-ReactiveSubstrate](../../spec/006-ReactiveSubstrate.md) |
| Removed / not shipped | What's gone since v1 and what replaced it | [15 — Removed](../api/15-removed.md) | [Migration rules](../../migration/from-re-frame-v1/README.md) |
| Resources | `reg-resource` / `reg-mutation` — declarative server state and the invalidate-then-refetch loop | [16 — Resources](../api/16-resources.md) | [016-Resources](../../spec/016-Resources.md) |

Want the same surface on *one* page — every signature, status, and tier in a single `Ctrl-F` target? That's [spec/API.md](../../spec/API.md). Think of the table above as the domain-by-domain reading and `API.md` as the flat search index over the very same rows.

## Looking up a failure mode

The intro promised "the full failure list," so here's where it lives. re-frame2 [fails loud and structured](glossary.md#fail-loud-not-silent): when something goes wrong the runtime doesn't throw a bare string — it surfaces an [error record](glossary.md#error-record), a map keyed by a reserved `:rf.error/<kebab-id>` category (think of that keyword as a stable error code you can match on, the way you'd match an event). Because every failure has a known code, "what does this error mean, and what does the framework do next?" becomes a *lookup*, not a guess.

Two documents own that lookup, and they split along the same Conventions-vs-009 seam as everything else:

- [Conventions §Error and warning ids](../../spec/Conventions.md) *reserves* the namespaces — `:rf.error/*` and `:rf.warning/*` follow the `:rf.<prefix>/<category>` shape, single-segment kebab-case under the reserved sub-namespace.
- [009-Instrumentation §Error namespace convention](../../spec/009-Instrumentation.md#error-namespace-convention--five-prefix-shapes) and its [Error event catalogue](../../spec/009-Instrumentation.md#error-event-catalogue-single-source-of-truth) own the *grammar*: the closed set of categories, what each one means, and the trace `:operation` it maps to. Crucially, the catalogue also names the **default recovery per category** — what the framework does *after* the failure. Does it roll back the whole [event cascade](glossary.md#event-cascade) the dispatch set off? Log the problem and skip the offending step? Treat it as a benign no-op? That catalogue is the single source of truth; the per-domain specs reference it rather than redefining it.

So `:rf.error/set-db-bad-value` (you handed `[:rf/set-db]` a non-map), `:rf.error/image-zero-match` (a `:select-ns :include` glob matched no loaded namespace), and `:rf.error/invalid-image` (an image carrying a retired key) all resolve to one row in that catalogue — code, meaning, and recovery, side by side.

> **Why this matters.** Errors are *dossiers*, not log lines — a structured record you can match on, route, and recover from. And because error records fan out to your always-on `:errors` listeners, they **survive production**, unlike the dev-only trace surface. The narrative version (why re-frame2 makes that choice, and how that always-on stream surfaces failures in the wild) is the guide's [Errors](concepts/errors.md) concept page; the production observability channel that carries them is [11 — Instrumentation](../api/11-instrumentation.md), with the working recipe at [how-to: Report errors in production](how-to/report-errors-in-production.md).

## The test-helper namespaces

There are three test namespaces, and the trick to remembering which to require is the *axis each one asserts against*. The first two ship in the core artefact; the third ships with HTTP.

| Namespace | Asserts against | Representative helpers |
|---|---|---|
| `re-frame.test-support` | Runtime state — frames, the [registrar](glossary.md#registrar), app-db, the dispatch queue [draining to quiescence](glossary.md#drain--run-to-completion) | `with-fresh-registrar`, `make-reset-runtime-fixture`, `dispatch-sequence`, `assert-path-equals` / `assert-db-equals`, `poll-until` |
| `re-frame.test-helpers` | The view tree — [hiccup](glossary.md#hiccup) data, `:data-testid` selectors, attached handlers | the `find-by-testid` family, `text-content`, `extract-handler` / `invoke-handler`, the `with-app-fixture` / `expect-text` / `wait-until` trio, `testid` |
| `re-frame.http.test-support` | The HTTP boundary | canned-reply stub fxs, `with-managed-request-stubs` |

The rule of thumb: a test that drives *events, subs, or machines* reaches for `re-frame.test-support`; a test that asserts *what the user sees in the rendered tree* reaches for `re-frame.test-helpers`; a test that does both requires both.

> **From re-frame v1.** "test-helpers" was once the catch-all noun for the *whole* testing surface, so your fingers may type `re-frame.test-helpers` reaching for `dispatch-sequence` or a registrar fixture — and find nothing. Those moved to `re-frame.test-support`. The names now carry the *axis* (runtime state vs view tree), not the old audience grouping. When a helper seems missing, you're probably in the wrong one of the two — switch axes.

The full inventory is in [10 — Testing](../api/10-testing.md), and the working recipes are [Test an event handler](how-to/test-an-event-handler.md) and [Test a full cascade](how-to/test-a-cascade.md).

## Images and frames: the composition model

One model carries the whole composition story: **`image → frame → event stream`**. Two nouns sit at the front of it, and they divide the labour cleanly — *behaviour* on one side, *state* on the other.

An [**image**](glossary.md#image) is a *value* naming a set of [registrations](glossary.md#registration) (events, subs, fx, …) — built with `rf/image`, either by selecting registrations from loaded namespaces (`:select-ns`) or by listing them inline (`:registrations`). Think of it as the recipe: what's in the app, but not yet running.

A [**frame**](glossary.md#frame) is the live, isolated execution context — the running dish. It owns the [app-db](glossary.md#app-db) and [runtime-db](glossary.md#runtime-db), the subscription cache, the [trace surface](glossary.md#trace-stream), the [adapter](glossary.md#adapter) binding, and the resolved set of registrations its images add up to (re-frame2 calls one such resolved set a *generation* — hot-reload an image and you get a new generation, like a fresh build of the recipe). You build a frame from images with `rf/make-frame`:

```clojure
(rf/make-frame {:id      :app/main
                :images  [app-image]
                :adapter :reagent})
```

A frame *is* the natural unit for sealed-off tests and multi-frame inspection. Two frames built from *different* images can hold different handlers for the same id, because each resolves against its own generation; two frames built from the *same* image share every registration, and what's isolated is their state, not their behaviour (see [concepts/images.md](concepts/images.md)). And when you stack images, a later one can *shadow* an earlier one — register over the same id, so its handler wins. That's not silently lost: composition records each shadowed id, and you read the report with `rf/frame-shadows`.

Most apps never touch any of this by hand. A single-app process just `reg-*`s into the global registrar, and the runtime assembles the standard image for it. You reach for explicit images and `make-frame` only when you want isolation: a test that needs a clean slate, a tool inspecting several frames at once, or a hot-reloaded image generation swapped into a running frame.

> **The address is always the frame id.** There is no public container constructor and no container-scoped dispatch option in the `image → frame → event stream` model. You target a frame by its id (or, in tests and tools, by the frame *value* `make-frame` returns — read its id back with `frame-value->id`), never by some enclosing substrate. This is the everyday face of [frame identity is carried, not found](glossary.md#frame-identity-is-carried-not-found).

> **From re-frame v1.** If you came looking for the older app / realm / module composition vocabulary — `rf/app`, `rf/module`, `rf/realm`, `install!`, and their inspectors — it has left the public facade entirely. The image/frame model replaced it: a feature namespace registers ordinary `reg-*` forms, an `rf/image` selects them, and `make-frame` runs them. Nothing addresses a realm anymore.

The contract rows for `rf/make-frame` / `rf/image` are in [spec/API.md §Registration](../../spec/API.md#registration), and the composition rules — image order, the shadow report, collisions — are owned by [002-Frames](../../spec/002-Frames.md#the-multi-frame-surface--choose-by-intent).

## The worked examples

The [examples catalogue](../../examples/README.md) is the runnable canon. When you're starting something new, the fastest path is usually to fork the one closest to what you're building and delete what you don't need — half a working app beats a blank file every time. The tree:

- **Pedagogical sketches** — [`counter`](../../examples/reagent/counter/), [`login`](../../examples/reagent/login/), `routing`, `ssr`, `managed_http_counter`, `state_machine_walkthrough`, `boot`, `flows`, `websocket`, `long_running_work`. Each isolates one surface, composed end-to-end. Read these to *learn* a feature.
- **Benchmarks** — `todomvc`, the `seven_guis` cluster, `nine_states`. Same primitives, fuller compositions — read these to see the pieces fit together under load.
- **Server state** — [`resources`](../../examples/reagent/resources/), `resources_ssr`, `ssr_streaming`.
- **The RealWorld pair** — [`realworld`](../../examples/reagent/realworld/) on `:rf.http/managed`, and [`realworld_resources`](../../examples/reagent/realworld_resources/) on resources + mutations. This is the "what does a *real* one look like?" answer, and the app this guide's [tutorial](tutorial/index.md) builds.
- **Other substrates** — `uix/` and `helix/` each carry counter + login (the dataflow is *identical*; only the view layer differs — which is rather the point), plus one design-led example each. `reagent-slim/` carries the slim adapter's counter fixture.

## Tools, and where their docs live

- **[Xray](../xray/index.md)** — the dev inspector: events, sub runs, app-db diffs, machine transitions, [time-travel](glossary.md#time-travel), per frame. Ten doc pages plus an API reference. The guide's working introduction is [Debug with Xray](how-to/debug-with-xray.md).
- **[Story](../story/index.md)** — the frame-aware component playground. It's built on re-frame2's own primitives, so a story *is* a [frame](glossary.md#frame) you can dispatch into. Nine doc pages plus an API reference.
- **The pair MCP** — [`tools/re-frame2-pair-mcp`](../../tools/re-frame2-pair-mcp/) lets an AI agent attach to your running app: inspect a frame, dispatch, hot-swap handlers, time-travel. You drive it through the skill below.

> **For JavaScript developers.** If you've used the Redux DevTools, Xray is that mental picture plus frames, subs, and machines — the same time-travel and action log, extended to re-frame2's richer dataflow. And Story is Storybook-flavoured: the same isolated-component playground, except a story is a live frame you can dispatch [events](glossary.md#event) into rather than a static prop fixture.

The [skills](../skills/index.md) are Claude Code skills for putting an agent to work on a re-frame2 app: [`re-frame2-setup`](../../skills/re-frame2-setup/) scaffolds a new app, [`re-frame2`](../../skills/re-frame2/) is the authoring skill, [`re-frame-migration`](../../skills/re-frame-migration/) drives a v1 port, [`re-frame2-pair`](../../skills/re-frame2-pair/) pairs against a running app, and [`re-frame2-xray`](../../skills/re-frame2-xray/) drives the inspection surface.

## Coming from re-frame v1

[From re-frame v1](25-from-re-frame-v1.md) is the narrative delta — what carries over (almost everything), what changed and why. The mechanical rule set is the [migration reference](../../migration/from-re-frame-v1/README.md), and for any real port the [`re-frame-migration`](../../skills/re-frame-migration/) skill is the recommended driver — it knows the rule set so you don't have to memorise it. When a name you remember has simply *vanished*, [15 — Removed](../api/15-removed.md) is where to look: it lists what's gone and what replaced it, so a missing symbol turns into a one-line lookup rather than a mystery.
