# The reference map

The guide teaches the model; the contracts live in spec — this page is the bridge. When you need the *exact* answer — the precise shape of an effect map, the full failure taxonomy, a function's complete signature — you've left guide territory. This page tells you where each kind of precise answer lives, and nothing more: it is a map of the reference surfaces, not a copy of them.

## Three layers, four entry points

re-frame2's documentation is deliberately layered. This guide (tutorial, how-to, explanation) teaches humans the model. The [API reference](../api/README.md) is the human-facing signature lookup, organised by domain. The [spec](../../spec/README.md) is the normative artefact — exhaustive, AI- and implementor-targeted, and the source every other layer is downstream of.

Four spec documents are entry points rather than contracts, and worth knowing by name:

- [Ownership](../../spec/Ownership.md) — the "where does X live?" matrix. Every contract surface mapped to exactly one owning spec document. When you don't know which document answers your question, start here.
- [Conventions](../../spec/Conventions.md) — the reserved `:rf/*` namespace scheme, reserved fx-ids, reserved app-db keys, packaging conventions.
- [Principles](../../spec/Principles.md) — the nine practical principles the design serves.
- [The spec index](../../spec/README.md) — the full catalogue, including the `Pattern-*` documents that name canonical shapes (app boot, websockets, stale-reply detection, the nine render states, and more) when your problem matches a recurring one.

## The API surface, by domain

Every public surface, one row each. The API page gives signatures with intuition notes; the owning spec gives the normative contract.

| Domain | What it covers | API page | Owning spec |
|---|---|---|---|
| Core | `reg-event-db` / `reg-event-fx`, `reg-sub`, `dispatch`, `subscribe`, frames — the loop's registration and verb surface | [01 — Core](../api/01-core.md) | [001-Registration](../../spec/001-Registration.md), [002-Frames](../../spec/002-Frames.md) |
| Views | `reg-view` and the substrate-agnostic pure-view contract | [02 — Views](../api/02-views.md) | [004-Views](../../spec/004-Views.md) |
| Effects and interceptors | The closed `:db` + `:fx` effect map, `reg-fx` / `reg-cofx`, interceptors, fx-overrides | [03 — Effects](../api/03-effects.md) | [002-Frames](../../spec/002-Frames.md) |
| State machines | `reg-machine` and the transition-table grammar — hierarchy, `:after`, `:spawn`, parallel regions | [04 — Machines](../api/04-machines.md) | [005-StateMachines](../../spec/005-StateMachines.md) |
| Flows | `reg-flow` — derived values materialised into app-db so handlers can read them | [05 — Flows](../api/05-flows.md) | [013-Flows](../../spec/013-Flows.md) |
| Routing | `reg-route`, navigation events, the `:route` sub, blocking and not-found | [06 — Routing](../api/06-routing.md) | [012-Routing](../../spec/012-Routing.md) |
| HTTP | `:rf.http/managed` — decode pipeline, retry, abort, the closed failure taxonomy | [07 — HTTP](../api/07-http.md) | [014-HTTPRequests](../../spec/014-HTTPRequests.md) |
| Schemas and classification | `:schema` metadata, `reg-app-schema`, sensitive / large marks | [08 — Schemas](../api/08-schemas.md) | [010-Schemas](../../spec/010-Schemas.md), [015-Data-Classification](../../spec/015-Data-Classification.md) |
| SSR | `render-to-string`, hydration, streaming boundaries | [09 — SSR](../api/09-ssr.md) | [011-SSR](../../spec/011-SSR.md) |
| Testing | Fixtures, `dispatch-sequence`, `compute-sub`, the helper namespaces below | [10 — Testing](../api/10-testing.md) | [008-Testing](../../spec/008-Testing.md) |
| Instrumentation | The dev trace bus, the always-on event / error emit substrates, the epoch buffer | [11 — Instrumentation](../api/11-instrumentation.md) | [009-Instrumentation](../../spec/009-Instrumentation.md), [Tool-Pair](../../spec/Tool-Pair.md) |
| Registrar queries | `registrations`, `handler-meta` — the read-side query API tools build on | [12 — Registrar](../api/12-registrar.md) | [001-Registration](../../spec/001-Registration.md) |
| Lifecycle | `init!` adapter selection at boot, adapter inspection, teardown | [13 — Lifecycle](../api/13-lifecycle.md) | [006-ReactiveSubstrate](../../spec/006-ReactiveSubstrate.md) |
| Adapters | The Reagent / UIx / Helix / reagent-slim substrate surfaces, `use-subscribe`, `frame-provider` | [14 — Adapters](../api/14-adapters.md) | [006-ReactiveSubstrate](../../spec/006-ReactiveSubstrate.md) |
| Removed / not shipped | What's gone since v1 and what replaced it | [15 — Removed](../api/15-removed.md) | [Migration rules](../../migration/from-re-frame-v1/README.md) |
| Resources | `reg-resource` / `reg-mutation` — declarative server state and the invalidate-then-refetch loop | [16 — Resources](../api/16-resources.md) | [016-Resources](../../spec/016-Resources.md) |

The dense single-page form of the same surface — every signature, status, and tier in one `Ctrl-F` target — is [spec/API.md](../../spec/API.md).

## The test-helper namespaces

Three test namespaces, split by what they assert against. The first two ship in the core artefact; the third ships with the HTTP artefact.

| Namespace | Asserts against | Representative helpers |
|---|---|---|
| `re-frame.test-support` | Runtime state — frames, the registrar, app-db, the dispatch drain | `with-fresh-registrar`, `make-reset-runtime-fixture`, `dispatch-sequence`, `assert-path-equals` / `assert-db-equals`, `poll-until` |
| `re-frame.test-helpers` | The view tree — hiccup data, `:data-testid` selectors, attached handlers | the `find-by-testid` family, `text-content`, `extract-handler` / `invoke-handler`, the `with-app-fixture` / `expect-text` / `wait-until` trio, `testid` |
| `re-frame.http-test-support` | The HTTP boundary | canned-reply stub fxs, `with-managed-request-stubs` |

The full inventory is in [10 — Testing](../api/10-testing.md); the working recipes are [Test an event handler](how-to/test-an-event-handler.md) and [Test a full cascade](how-to/test-a-cascade.md).

## Realms: what is public today

A **realm** is the container your registrations live in — the registrar, the adapter selection, the capability map, the frame registry. A single-realm app never spells one: absence of a realm means the default realm, as an explicit rule. The public constructors — `rf/realm`, `rf/module`, `rf/app`, `rf/install!`, `rf/reinstall!`, `rf/dispose-realm!` — plus the realm-targeted registrar queries ship from `re-frame.core` today, and a constructed realm isolates *installation and queries*: its hermetic registrar and capability map make it the natural unit for hermetic tests and multi-program inspection.

> **Honesty note.** Live dispatch through a non-default realm is a future slice — dispatch and subscribe still resolve through the default realm. Treat a constructed realm as an isolated registrar-and-capability container, not yet a second running program. The contract rows are in [spec/API.md §App values and composition](../../spec/API.md#app-values-and-composition-ep-0013); the model is owned by [Runtime-Subsystems](../../spec/Runtime-Subsystems.md).

## The worked examples

The [examples catalogue](../../examples/README.md) is the runnable canon — fork the one closest to what you're building. The shape of the tree:

- **Pedagogical sketches** — [`counter`](../../examples/reagent/counter/), [`login`](../../examples/reagent/login/), `routing`, `ssr`, `managed_http_counter`, `state_machine_walkthrough`, `boot`, `flows`, `websocket`, `long_running_work`. Each isolates one surface, composed end-to-end.
- **Benchmarks** — `todomvc`, the `seven_guis` cluster, `nine_states`. Same primitives, fuller compositions.
- **Server state** — [`resources`](../../examples/reagent/resources/), `resources_ssr`, `ssr_streaming`.
- **The RealWorld pair** — [`realworld`](../../examples/reagent/realworld/) on `:rf.http/managed`, and [`realworld_resources`](../../examples/reagent/realworld_resources/) on resources + mutations. The "what does a real one look like?" answer, and the app this guide's [tutorial](tutorial/index.md) builds.
- **Other substrates** — `uix/` and `helix/` each carry counter + login (the dataflow is identical; only the view layer differs), plus one design-led example each; `reagent-slim/` carries the slim adapter's counter fixture.

## Tools, and where their docs live

- **[Xray](../xray/index.md)** — the in-app inspection panel: events, sub runs, app-db diffs, machine transitions, time-travel, per frame. Ten doc pages plus an API reference; the guide's working introduction is [Debug with Xray](how-to/debug-with-xray.md).
- **[Story](../story/index.md)** — the frame-aware component playground (Storybook-flavoured, built on re-frame2's own primitives). Nine doc pages plus an API reference.
- **The pair MCP** — [`tools/re-frame2-pair-mcp`](../../tools/re-frame2-pair-mcp/) lets an AI agent attach to your running app: inspect a frame, dispatch, hot-swap handlers, time-travel. You drive it through the skill below.

The [skills](../skills/index.md) are Claude Code skills for putting an agent to work on a re-frame2 app: [`re-frame2-setup`](../../skills/re-frame2-setup/) scaffolds a new app, [`re-frame2`](../../skills/re-frame2/) is the authoring skill, [`re-frame-migration`](../../skills/re-frame-migration/) drives a v1 port, [`re-frame2-pair`](../../skills/re-frame2-pair/) pairs against a running app, and [`re-frame2-xray`](../../skills/re-frame2-xray/) drives the inspection surface.

## Coming from re-frame v1

[From re-frame v1](25-from-re-frame-v1.md) is the narrative delta — what carries over (almost everything), what changed and why. The mechanical rule set is the [migration reference](../../migration/from-re-frame-v1/README.md), the [`re-frame-migration`](../../skills/re-frame-migration/) skill is the recommended driver for any real port, and [15 — Removed](../api/15-removed.md) answers "a name I remember has vanished."

---

You can now:

- find the owning spec document for any contract surface, starting from Ownership when unsure
- look up any public function by domain in the API reference
- name which test-helper namespace a given test should require
- pick the worked example closest to what you're building, and know where Xray, Story, and the skills are documented

Next: [How-to guides](how-to/index.md) · [The spec index](../../spec/README.md)
