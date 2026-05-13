---
name: re-frame2
description: Writes re-frame2 ClojureScript application code — events, subscriptions, effects, frames, state machines (reg-machine, parallel regions, tags, invoke), schemas, stories, routing, tests, and the canonical patterns (RemoteData, Forms, Boot, WebSocket, NineStates, ManagedHTTP, AsyncEffect, LongRunningWork, StaleDetection). Use whenever the user mentions re-frame2, reg-event-db, reg-event-fx, reg-sub, reg-fx, reg-cofx, reg-view, reg-machine, reg-route, reg-story, reg-app-schema, dispatch, subscribe, app-db, frames, regions, tags, the nine UI states, managed HTTP, RemoteData lifecycles, writing tests for a re-frame2 app, or state-machine-for-HTTP shapes — even when re-frame2 is not named explicitly. Authoring only (writing new code). Do not use for: live-app inspection (use re-frame-pair2), greenfield project bootstrap (use re-frame2-setup), v1→v2 migration (use re-frame-migration), or porting re-frame2 itself (use re-frame2-implementor).
allowed-tools:
  - Read
  - Edit
  - Write
  - Grep
  - Glob
---

# re-frame2

Authors re-frame2 ClojureScript application code. Router skill: this file carries decision shortcuts; depth lives one level deep in `reference/`, `patterns/`, and `decision-trees/`.

## When to load

`.cljs` / `.cljc` authoring of: event handlers, subscriptions, state machines, views, schemas, routes, stories, or the canonical patterns. References to `reg-event-*`, `reg-sub`, `reg-fx`, `reg-machine`, `dispatch`, `subscribe`, `app-db`, frames, regions, tags, or pattern names are sufficient triggers — re-frame2 need not be named.

## When NOT to use

Full skill-disambiguation matrix lives at [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source). In brief: not for live-runtime inspection, greenfield bootstrap, v1→v2 migration, porting re-frame2 itself, or spec / API / EP rationale reading.

## Cardinal rules

1. **Implementation is ground truth.** When spec and `implementation/**` disagree, the implementation wins. Recipes here are verified against `implementation/**` and `examples/reagent/**`.
2. **Recipes over explanations.** Use the canonical shape; do not re-derive from first principles.
3. **Distinguish orchestration from state.** State machines for *modes* (legal-transitions-depend-on-current-state); slices for *fields*. See [`decision-trees/slice-or-machine.md`](decision-trees/slice-or-machine.md).
4. **Schemas at boundaries.** `reg-app-schema` for paths that cross trust boundaries (HTTP payloads, persisted state, snapshot restores). Do not schema-fence every internal key.
5. **`examples/reagent/<x>/` is canonical.** When a pattern has a worked example, match its shape.
6. **Frames before globals.** Talk to a frame via `dispatch` / `subscribe`. Do not import frame internals or bypass to mutate state.
7. **`:rf/*` is reserved.** Application keywords pick their own feature prefix (`:cart/...`, `:auth/...`).
8. **`reg-*` macros over `register-*` functions.** Macros capture source coordinates that tools rely on; functional registrations are for advanced cases only.
9. **Pillar 4 — assume training knowledge.** Teach the re-frame2-specific binding, not FSM theory / HTTP retry / React rendering.

## Decision shortcuts

**Slice vs machine vs region** — `decision-trees/slice-or-machine.md`. Tell: if the prompt names *transitions* or *modes*, machine. If it names *fields*, *flags*, or *counters*, slice. A sub-concern of a larger feature's lifecycle is a *region* inside that feature's machine, not its own top-level machine.

**Which pattern fits** — `decision-trees/pick-a-pattern.md`. Quick map:

| Need | Pattern leaf |
|---|---|
| HTTP request with request/response lifecycle | `patterns/remote-data.md` |
| HTTP with status-aware retries / error projection / batching | `patterns/managed-http.md` |
| Form input with validation and submit | `patterns/forms.md` |
| Long-running browser-side work | `patterns/long-running-work.md` |
| Fire-and-forget side effect, no response | `patterns/async-effect.md` |
| App boot (configure, hydrate, navigate) | `patterns/boot.md` |
| Real-time bidirectional connection | `patterns/websocket.md` |
| View rendering every legal lifecycle state | `patterns/nine-states.md` |
| Cached resource with freshness checks | `patterns/stale-detection.md` |

Patterns compose; a screen can use Forms on submit, RemoteData for the request, and WebSocket for a push.

## Where the depth lives

Load at most two leaves per task. If a task seems to need three, it likely spans patterns and should be broken up.

**Fundamentals — `reference/fundamentals/`**: `events.md`, `fx.md`, `cofx.md`, `subs.md`, `schemas.md`, `frames.md`, `event-state-cycle.md`, `project-structure.md`.

**State machines — `reference/state-machines/`**: `reg-machine.md`, `regions.md` (parallel), `tags.md`, `invoke.md` (child machines), `cancellation.md`.

**Tooling — `reference/tooling/`**: `stories.md`, `routing.md`, `story-recorder.md` (record canvas interactions as `:play`), `story-mcp-loop.md` (agent self-healing loop over MCP).

**Cross-cutting — `reference/cross-cutting/`**: `testing.md` (with-frame, dispatch-sync, compute-sub), `api-cheatsheet.md`.

**Patterns — `patterns/`**: one leaf per canonical pattern (see table above). Each leaf opens with load triggers, the canonical mini-declaration, the re-frame2 features it uses, trade-offs, and the worked-example link. Cross-reference of pattern → example app: `examples-map.md`.

**Decision trees — `decision-trees/`**: `pick-a-pattern.md`, `slice-or-machine.md`.

## Authoring workflow (every task)

1. Identify the surface — event? sub? fx? cofx? view? machine? route? story? schema?
2. Load at most two leaves (the relevant fundamentals or pattern; a second only if the task spans two surfaces).
3. Match the canonical declaration in the leaf; do not re-derive.
4. Pick the feature prefix (`:cart/...`, `:auth/...`) — never `:rf/*`.
5. Cross-check the worked example in `examples/reagent/<x>/` when one exists.
6. Schema only at boundaries.
7. Use `reg-*` macros unless the macro shape can't express the need.
8. Cut-test comments: would I write this same comment in a React / Vue / Elm app? If yes, cut it.

## Done checklist

- [ ] Ids do not collide (`grep` the codebase for the chosen id).
- [ ] Schema registered for any new boundary.
- [ ] No `:rf.*` application keywords.
- [ ] Cut-test passed on comments.
- [ ] Shape matches the canonical declaration in the leaf.
- [ ] If a worked example exists, the new code's shape matches it.

The user runs tests / compiler / app; this skill does not.

## How re-frame2 differs from re-frame v1

For v1-trained context: migration workflow + breaking-change rule index lives in `skills/re-frame-migration/`; the authoritative rule corpus is [`MIGRATION.md`](../../spec/MIGRATION.md). Do not re-derive v1 mappings from training memory.

## Background reading (optional)

Reach for these when the user asks "why does it work this way?" or designs a feature whose shape isn't obvious. All route via `SKILL-REDIRECT.md` at the repo root: *Principles*, *Conventions*, *Construction prompts*, *EP design rationale*.

---

*re-frame2 (v2 line). v1: [re-frame](https://github.com/day8/re-frame). Full skill-disambiguation matrix: [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source). Deep-dive links route through `SKILL-REDIRECT.md`.*
