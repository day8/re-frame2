# The reference map

The guide teaches you the model; the exact contracts live in the spec. This page is the bridge between them. When you need the *precise* answer — the exact shape of an effect map (the data an event handler returns to describe what should change), the full failure list, a function's complete signature — you've left the guide and walked into the reference. Rather than copy all of that here (it would rot the moment the spec moved), this page tells you *where* each kind of precise answer lives. It's a map of the reference surfaces, not a copy of them.

> **Coming from a big framework's docs?** You already know this instinct: the *tutorial* gets you productive, but the day you hit a sharp edge you go straight to the API reference — `useEffect`'s exact dependency-array rules, the precise return shape of a Redux `combineReducers`. This page is the index card that tells you which drawer to open.

## Three layers, four entry points

The docs come in three layers, and it helps to know which one you're standing in.

- **This guide** (tutorial, how-to, explanation) teaches you the *model* — the why and the how, prose-first.
- The **[API reference](../api/README.md)** is the signature lookup, organised by domain. Go there once you know the concept and just need the call shape.
- The **[spec](../../spec/README.md)** is the normative source: exhaustive, written for AI and implementors, and the thing every other layer is downstream of.

So when two sources seem to disagree, the spec wins — it's the artefact; everything else is a friendlier reading of it.

Four spec documents are *entry points*, not contracts. They don't define a surface so much as help you find the one that does — worth knowing by name:

- [Ownership](../../spec/Ownership.md) — the "where does X live?" matrix. Every contract surface maps to exactly one owning spec document, so when you don't know which document answers your question, start here. It's the spec's own table of contents-by-concept.
- [Conventions](../../spec/Conventions.md) — the reserved `:rf/*` namespace scheme, reserved fx-ids, reserved app-db keys (app-db is your app's single state map), and packaging conventions. The "which names are mine and which are the framework's?" reference.
- [Principles](../../spec/Principles.md) — the nine practical principles the design serves, so you can see the *reasoning* behind a rule, not just the rule. Read this when a constraint feels arbitrary; it usually isn't.
- [The spec index](../../spec/README.md) — the full catalogue. It includes the `Pattern-*` documents, which name canonical shapes (app boot, websockets, stale-reply detection, the nine render states, and more) — handy when your problem turns out to be a recurring one with a known answer.

## The API surface, by domain

Every public surface gets one row below. The API page gives signatures with intuition notes; the owning spec gives the normative contract for when intuition isn't enough.

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

## The test-helper namespaces

There are three test namespaces, split by *what each one asserts against* — and that split is the whole trick to remembering which to require. The first two ship in the core artefact; the third ships with HTTP.

| Namespace | Asserts against | Representative helpers |
|---|---|---|
| `re-frame.test-support` | Runtime state — frames, the registrar, app-db, the dispatch drain | `with-fresh-registrar`, `make-reset-runtime-fixture`, `dispatch-sequence`, `assert-path-equals` / `assert-db-equals`, `poll-until` |
| `re-frame.test-helpers` | The view tree — hiccup data, `:data-testid` selectors, attached handlers | the `find-by-testid` family, `text-content`, `extract-handler` / `invoke-handler`, the `with-app-fixture` / `expect-text` / `wait-until` trio, `testid` |
| `re-frame.http.test-support` | The HTTP boundary | canned-reply stub fxs, `with-managed-request-stubs` |

The rule of thumb: a test that drives *events, subs, or machines* reaches for `re-frame.test-support`; a test that asserts *what the user sees in the rendered tree* reaches for `re-frame.test-helpers`; a test that does both requires both.

> **A v1 muscle-memory trap.** In re-frame v1, "test-helpers" was the catch-all noun for the whole testing surface, so your fingers may type `re-frame.test-helpers` reaching for `dispatch-sequence` or a registrar fixture — and find nothing. Those live in `re-frame.test-support`. The names now carry the *axis* (runtime state vs view tree), not the old audience grouping. When a helper seems missing, you're probably in the wrong one of the two; switch axes.

The full inventory is in [10 — Testing](../api/10-testing.md), and the working recipes are [Test an event handler](how-to/test-an-event-handler.md) and [Test a full cascade](how-to/test-a-cascade.md).

## Images and frames: the composition model

The public model is `image → frame → event stream`. Two nouns carry it:

An **image** is a *value* naming a set of registrations (events, subs, fx, …) — built with `rf/image`, either by selecting registrations from loaded namespaces (`:select-ns`) or by listing them inline. Think of it as the recipe: what's in the app, but not yet running.

A **frame** is the live, isolated execution context — the running dish. It owns the app state, the subscription cache, the trace surface, the adapter binding, and one resolved image generation. You build a frame from images with `rf/make-frame`:

```clojure
(rf/make-frame {:id      :app/main
                :images  [app-image]
                :adapter :reagent})
```

A frame *is* the natural unit for hermetic tests and multi-frame inspection: each frame runs its own *sealed* registration set, so two frames can hold different handlers for the same id without collision. Most apps never touch any of this by hand — a single-app process just `reg-*`s into the global registrar and the runtime assembles the standard image for it. You reach for explicit images and `make-frame` when you want isolation: a test that needs a clean slate, a tool inspecting several frames at once, or a hot-reloaded image generation.

> **The address is always the frame id.** There is no public container constructor and no container-scoped dispatch option in the `image → frame → event stream` model. You target a frame by its id (or, in tests and tools, by the frame *value* `make-frame` returns — read its id back with `frame-value->id`), never by some enclosing substrate. If you came looking for the older app/realm/module composition vocabulary: it has left the public facade entirely. The image/frame model replaced it.

The public image/frame model is owned by [EP-0023](../EP/EP-0023-image-loaded-frames.md); the contract rows for `rf/make-frame` / `rf/image` are in [spec/API.md §Registration](../../spec/API.md#registration).

## The worked examples

The [examples catalogue](../../examples/README.md) is the runnable canon. When you're starting something new, the fastest path is usually to fork the one closest to what you're building and delete what you don't need — half a working app beats a blank file every time. The tree:

- **Pedagogical sketches** — [`counter`](../../examples/reagent/counter/), [`login`](../../examples/reagent/login/), `routing`, `ssr`, `managed_http_counter`, `state_machine_walkthrough`, `boot`, `flows`, `websocket`, `long_running_work`. Each isolates one surface, composed end-to-end. Read these to *learn* a feature.
- **Benchmarks** — `todomvc`, the `seven_guis` cluster, `nine_states`. Same primitives, fuller compositions — read these to see the pieces fit together under load.
- **Server state** — [`resources`](../../examples/reagent/resources/), `resources_ssr`, `ssr_streaming`.
- **The RealWorld pair** — [`realworld`](../../examples/reagent/realworld/) on `:rf.http/managed`, and [`realworld_resources`](../../examples/reagent/realworld_resources/) on resources + mutations. This is the "what does a *real* one look like?" answer, and the app this guide's [tutorial](tutorial/index.md) builds.
- **Other substrates** — `uix/` and `helix/` each carry counter + login (the dataflow is *identical*; only the view layer differs — which is rather the point), plus one design-led example each. `reagent-slim/` carries the slim adapter's counter fixture.

## Tools, and where their docs live

- **[Xray](../xray/index.md)** — the in-app inspection panel: events, sub runs, app-db diffs, machine transitions, time-travel, per frame. Ten doc pages plus an API reference. The guide's working introduction is [Debug with Xray](how-to/debug-with-xray.md). If you've used the Redux DevTools, you already have the right mental picture — this is that, plus frames, subs, and machines.
- **[Story](../story/index.md)** — the frame-aware component playground. It's Storybook-flavoured, built on re-frame2's own primitives, so a story *is* a frame you can dispatch into. Nine doc pages plus an API reference.
- **The pair MCP** — [`tools/re-frame2-pair-mcp`](../../tools/re-frame2-pair-mcp/) lets an AI agent attach to your running app: inspect a frame, dispatch, hot-swap handlers, time-travel. You drive it through the skill below.

The [skills](../skills/index.md) are Claude Code skills for putting an agent to work on a re-frame2 app: [`re-frame2-setup`](../../skills/re-frame2-setup/) scaffolds a new app, [`re-frame2`](../../skills/re-frame2/) is the authoring skill, [`re-frame-migration`](../../skills/re-frame-migration/) drives a v1 port, [`re-frame2-pair`](../../skills/re-frame2-pair/) pairs against a running app, and [`re-frame2-xray`](../../skills/re-frame2-xray/) drives the inspection surface.

## Coming from re-frame v1

[From re-frame v1](25-from-re-frame-v1.md) is the narrative delta — what carries over (almost everything), what changed and why. The mechanical rule set is the [migration reference](../../migration/from-re-frame-v1/README.md), and for any real port the [`re-frame-migration`](../../skills/re-frame-migration/) skill is the recommended driver — it knows the rule set so you don't have to memorise it. When a name you remember has simply *vanished*, [15 — Removed](../api/15-removed.md) is where to look: it lists what's gone and what replaced it, so a missing symbol turns into a one-line lookup rather than a mystery.

---

You can now:

- find the owning spec document for any contract surface, starting from [Ownership](../../spec/Ownership.md) when unsure
- look up any public function by domain in the API reference, or flat-search the lot in [spec/API.md](../../spec/API.md)
- name which test-helper namespace a given test should require — by the axis it asserts against
- pick the worked example closest to what you're building, and know where Xray, Story, the pair MCP, and the skills are documented
