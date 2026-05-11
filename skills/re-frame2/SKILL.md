---
name: re-frame2
description: Writes re-frame2 ClojureScript application code — events, subscriptions, effects, frames, state machines (reg-machine, parallel regions, tags, invoke), schemas, stories, routing, tests, and the canonical patterns (RemoteData, Forms, Boot, WebSocket, NineStates, ManagedHTTP, AsyncEffect, LongRunningWork, StaleDetection). Use this skill whenever the user mentions re-frame2, reg-event-db, reg-event-fx, reg-sub, reg-fx, reg-cofx, reg-view, reg-machine, reg-route, reg-story, reg-app-schema, dispatch, subscribe, app-db, frames, regions, tags, the nine UI states, managed HTTP, RemoteData lifecycles, writing tests for a re-frame2 app, or state-machine-for-HTTP shapes — even when re-frame2 is not named explicitly. Authoring only (writing new code). For live-app inspection use re-frame-pair2; for greenfield project bootstrap use re-frame2-setup.
---

# re-frame2

Authors re-frame2 ClojureScript application code: events, subscriptions, effects, frames, state machines, schemas, stories, routing, and the canonical patterns. This is a router skill — it carries the cardinal rules and decision shortcuts, and points at one-level-deep reference and pattern leaves for depth.

## When to load this skill

Load when the task is **writing or editing re-frame2 application source** (`.cljs` / `.cljc`): a new event handler, a subscription graph, a state machine, a view, a schema, a route, a story, or any of the canonical patterns. The user does not have to name re-frame2 — references to `reg-event-*`, `reg-sub`, `reg-fx`, `reg-machine`, `dispatch`, `subscribe`, `app-db`, frames, regions, tags, or pattern names are sufficient triggers.

## When NOT to use this skill

| Prompt shape | Route to |
|---|---|
| Inspect, debug, or modify a running re-frame2 app | `skills/re-frame-pair2/` |
| Set up a new re-frame2 project from scratch (deps.edn, shadow-cljs, boot scaffolding) | `skills/re-frame2-setup/` |
| Migrate an existing re-frame v1 app to v2 | `SKILL-REDIRECT.md` → *Migration from re-frame v1* |
| Understand how the registrar / machine compiler / reactive substrate is implemented | `SKILL-REDIRECT.md` → EP design entries |
| Read the full API reference, EP rationale, or pattern spec | `SKILL-REDIRECT.md` |

Migration and deep-dive content are deliberately outside this skill — they live behind one redirect file (`SKILL-REDIRECT.md` at repo root), so leaves stay focused on the canonical authoring recipes.

## Cardinal rules

These hold across every leaf in this skill.

1. **Implementation is ground truth.** When the spec and `implementation/**` disagree, the implementation wins. Recipes here are verified against `implementation/**` and `examples/reagent/**`, not against `spec/`. Spec is *why*; impl is *what*.
2. **Recipes over explanations.** Reach for a canonical shape; do not derive from first principles. If a pattern leaf exists, use the shape it gives; do not invent a parallel one.
3. **Distinguish orchestration from state.** Use **state machines** (`reg-machine`) when the answer to "what can the system do next" depends on the current mode (connecting / connected / disconnected; idle / submitting / submitted; loading / loaded / error). Use **slices** (a key in `app-db` driven by `reg-event-db`) when state is a field, not a mode. See `decision-trees/slice-or-machine.md`.
4. **Schemas at boundaries, not everywhere.** Register `reg-app-schema` for the paths that cross trust boundaries (incoming HTTP payloads, persisted state on restore, machine snapshot restores). Do not schema-fence every internal key.
5. **Examples in `examples/reagent/<x>/` are canonical.** When a pattern has a worked example, prefer the example's shape over a synthesised one. The example reflects the implementation as currently shipped.
6. **Frames before globals.** Code talks to a frame (`(rf/dispatch [:foo])` against the default frame; or `(rf/dispatch {:frame :stories} [:foo])` to target one). Do not import frame internals; do not bypass `dispatch` / `subscribe` to mutate state.
7. **Reserved namespaces are reserved.** The `:rf/*` keyword namespace (including `:rf.machine/*`, `:rf.epoch/*`, `:rf.http/*`, `:rf.error/*`) belongs to the framework. Application keywords pick their own feature prefix.
8. **`reg-*` macros over `register-*` functions.** The macros capture source coordinates that tools (and `re-frame-pair2`) rely on. The functional registrations exist for advanced cases (programmatic registration, generated registrations) — reach for them only when the macro shape cannot express what you need.
9. **Pillar 4 — assume training knowledge.** This skill teaches the *re-frame2-specific binding*: which feature implements which idea, the shape of the canonical declaration, the gotcha unique to this framework. It does not re-teach FSM theory, HTTP retry, optimistic updates, or React rendering — that knowledge is assumed.

## Decision: state machine, slice, or region?

Quick rule (see `decision-trees/slice-or-machine.md` for the worked rules):

- **Slice** — a single key in `app-db` updated by `reg-event-db`. Use for fields, lists, flags whose value evolves but whose *legal transitions* do not need enforcement.
- **State machine** (`reg-machine`) — a top-level orchestrator. Use when the legal next transitions depend on the current state; when a feature has more than two interesting modes; when concurrent independent sub-modes exist (use `:fsm/parallel-regions`); when a step needs cancellation semantics (use `:fsm/tags` + cascade).
- **Region** (a region inside an existing machine, not a top-level `reg-machine`) — Use when the sub-concern is *part of* a larger feature's lifecycle (e.g. a form's submission status inside a screen's load-then-edit lifecycle). The form is a region of the screen; not its own top-level machine.

If unsure between slice and machine, the dominant tell is: *does the prompt mention transitions or modes?* If yes, machine. If it mentions field values or filters or a counter, slice.

## Decision: which pattern fits?

(See `decision-trees/pick-a-pattern.md` for the matrix.)

- **HTTP request with a request/response lifecycle** → Pattern-RemoteData (`patterns/remote-data.md`).
- **HTTP request that needs status-aware retries, error projection, or batching** → Pattern-ManagedHTTP (`patterns/managed-http.md`) — choose the fx form by default; choose the invokable-machine form when the HTTP call participates in a larger machine's `:invoke` contract.
- **Form input with validation and submit lifecycle** → Pattern-Forms (`patterns/forms.md`).
- **Long-running browser-side work (file processing, large reduction)** → Pattern-LongRunningWork (`patterns/long-running-work.md`).
- **Background-fire-and-forget side effect, no response** → Pattern-AsyncEffect (`patterns/async-effect.md`).
- **App boot sequence (configure, hydrate, navigate)** → Pattern-Boot (`patterns/boot.md`).
- **Real-time bidirectional connection lifecycle** → Pattern-WebSocket (`patterns/websocket.md`).
- **A view that needs to render every legal lifecycle state distinctly** → Pattern-NineStates (`patterns/nine-states.md`).
- **Long-cached resource whose freshness may need to be checked** → Pattern-StaleDetection (`patterns/stale-detection.md`).

If two patterns apply, prefer the one whose worked example most closely matches the prompt. Patterns compose: a screen can drive Pattern-Forms on submit, fire a Pattern-RemoteData request, and consume a Pattern-WebSocket push, all at once.

## Where the depth lives — loading map

Read the leaf that matches the task. Each leaf is ≤250 lines, target ~150. Read no more than two leaves to start a task; if a task seems to need three or more leaves, the request probably spans patterns and should be broken up.

### Fundamentals — `reference/fundamentals/`

| Task shape | Leaf |
|---|---|
| Author an event handler (`reg-event-db` / `reg-event-fx` / `reg-event-ctx`) | `reference/fundamentals/events.md` |
| Author a custom effect (`reg-fx`), shape the `:fx` vector | `reference/fundamentals/fx.md` |
| Author a coeffect (`reg-cofx`), attach via `inject-cofx` | `reference/fundamentals/cofx.md` |
| Author a subscription, layered subs, dynamic args, machine subs | `reference/fundamentals/subs.md` |
| Register an app-db schema; validate at the boundary | `reference/fundamentals/schemas.md` |
| Understand frames, frame ids, default frame, per-frame config | `reference/fundamentals/frames.md` |
| Walk the event-state cycle end to end (mental model anchor) | `reference/fundamentals/event-state-cycle.md` |
| Lay out the source tree — where each kind of file goes | `reference/fundamentals/project-structure.md` |

### State machines — `reference/state-machines/`

| Task shape | Leaf |
|---|---|
| Author a `reg-machine` with `:states`, `:initial`, `:guards`, `:actions` | `reference/state-machines/reg-machine.md` |
| Compose parallel regions (single-region + `:type :parallel`) | `reference/state-machines/regions.md` |
| Declare `:tags` on states, query with `has-tag?` | `reference/state-machines/tags.md` |
| Use `:invoke` to spawn a child machine; consume its result; `:invoke-all` | `reference/state-machines/invoke.md` |
| Understand the cancellation cascade — what fires on actor destroy | `reference/state-machines/cancellation.md` |

For the slice / region / top-level-machine decision, see `decision-trees/slice-or-machine.md`.

### Tooling — `reference/tooling/`

| Task shape | Leaf |
|---|---|
| Author a story (`reg-story` / variants); use stories as a unit-test substrate | `reference/tooling/stories.md` |
| Register a route, navigate, gate with `:can-leave?` | `reference/tooling/routing.md` |

### Cross-cutting — `reference/cross-cutting/`

| Task shape | Leaf |
|---|---|
| Write a test (`with-frame`, `dispatch-sync`, `compute-sub`, schema-aware fixtures) | `reference/cross-cutting/testing.md` |
| Look up a `reg-*` family signature without loading a fundamentals leaf | `reference/cross-cutting/api-cheatsheet.md` |

### Patterns — `patterns/`

One leaf per canonical pattern. Each leaf opens with load triggers, gives the canonical mini-declaration (verified against `implementation/**` + `examples/reagent/**`), names the re-frame2 features the pattern uses, lists trade-offs, and links to the worked example.

| Pattern | Leaf | Worked example |
|---|---|---|
| RemoteData | `patterns/remote-data.md` | (mini-example inline; example app pending) |
| Forms | `patterns/forms.md` | `examples/reagent/login/` |
| Boot | `patterns/boot.md` | `examples/reagent/boot/` |
| WebSocket | `patterns/websocket.md` | `examples/reagent/websocket/` (pending) |
| NineStates | `patterns/nine-states.md` | `examples/reagent/nine_states/` |
| ManagedHTTP | `patterns/managed-http.md` | `examples/reagent/managed_http_counter/` |
| AsyncEffect | `patterns/async-effect.md` | (mini-example inline) |
| LongRunningWork | `patterns/long-running-work.md` | `examples/reagent/long_running_work/` (pending) |
| StaleDetection | `patterns/stale-detection.md` | (mini-example inline) |

For the cross-reference of pattern → example app, see `examples-map.md`.

### Decision trees — `decision-trees/`

| Question | File |
|---|---|
| "I want to build X — which pattern?" | `decision-trees/pick-a-pattern.md` |
| "Should this be a slice, a region, or a top-level machine?" | `decision-trees/slice-or-machine.md` |

## Authoring workflow (every task)

A short checklist that applies regardless of which leaf you load.

1. **Identify the surface.** What is being registered: event? sub? fx? cofx? view? machine? route? story? schema? More than one?
2. **Load at most two leaves.** The relevant fundamentals or pattern leaf, plus a second leaf only if the task spans two surfaces (e.g. a sub plus its machine).
3. **Read the canonical declaration in the leaf.** Match its shape — do not re-derive.
4. **Pick the feature prefix.** Application keywords use the app's own namespace (e.g. `:cart/...`, `:auth/...`). Never start an application keyword with `:rf/` or any `:rf.*` namespace — those are reserved.
5. **Cross-check `examples/reagent/<x>/`.** If the pattern has a worked example, scan it for the shape this leaf describes. The example reflects the implementation as shipped.
6. **Add a schema only when crossing a boundary.** Incoming HTTP payloads, persisted state on restore, machine snapshot restores — schema these. Do not schema-fence every internal key.
7. **Write the code.** Use the `reg-*` macros (not `register-*` functions) unless the macro shape cannot express what you need.
8. **Apply the cut-test (Pillar 4).** Every comment line: would I write this same comment in a React, Vue, or Elm app? If yes, cut it. Comments earn their tokens by saying something specific to re-frame2.

## How re-frame2 differs from re-frame v1

A one-line redirect for v1-trained context: the migration guide and v1→v2 deltas live at `SKILL-REDIRECT.md` → *Migration from re-frame v1*. Do not re-derive v1 mappings from training memory — the v1→v2 surface drift is large enough that confident recall is unreliable.

## Background reading (optional)

These do not need to be loaded for routine authoring tasks. Reach for them when the user asks "why does it work this way?" or when designing a feature whose shape isn't obvious from a single leaf.

- `SKILL-REDIRECT.md` → *Principles* — the AI-first principles the framework was designed around.
- `SKILL-REDIRECT.md` → *Conventions* — naming, keyword namespaces, source-coord conventions.
- `SKILL-REDIRECT.md` → *Construction prompts* — the AI-shaped templates used during framework authoring.
- `SKILL-REDIRECT.md` → *EP design rationale* — the per-feature design documents.

## Done checklist

Before considering an authoring task complete:

- [ ] The registered ids do not collide with existing ones (`grep` the codebase for the chosen id).
- [ ] Schema is registered for any boundary the new code crosses.
- [ ] No `:rf.*` application keywords leaked in.
- [ ] The cut-test passed on comments (no React/Vue-shaped explanations).
- [ ] The shape matches the canonical declaration in the leaf (no re-derived shape).
- [ ] If a worked example exists for this pattern, the new code's shape matches it.

The user runs the test suite, the compiler, and the app. This skill does not run them; that is outside its scope.

---

*This skill targets re-frame2 (the v2 line). For the v1 line, see [re-frame](https://github.com/day8/re-frame). For live-app inspection, see `skills/re-frame-pair2/`. For greenfield bootstrap, see `skills/re-frame2-setup/`. All deep-dive links route through `SKILL-REDIRECT.md` at the repo root.*
