---
name: re-frame2
description: >
  Writes re-frame2 ClojureScript application code — events, subscriptions,
  effects, flows, frames, state machines (reg-machine, parallel regions,
  tags, spawn), schemas, stories, routing, tests, and the canonical patterns
  (RemoteData, Resources, Forms, Boot, WebSocket, NineStates, ManagedHTTP,
  AsyncEffect, LongRunningWork, StaleDetection, ReusableComponents,
  StatefulComponents, FormAction, SSR-Loaders). Use whenever the user
  mentions re-frame2, reg-event, reg-sub, reg-fx,
  reg-cofx, reg-flow, reg-view, reg-machine, reg-route, reg-story,
  reg-app-schema, reg-resource, reg-mutation, dispatch, subscribe, app-db,
  flows, frames, regions, tags, the nine UI states, managed HTTP,
  RemoteData lifecycles, cached server-state / query-cache / TanStack-Query
  shapes, writing tests for a re-frame2 app, or state-machine-for-HTTP
  shapes — even when re-frame2 is not named explicitly. **Authoring only**
  (writing new code).
  **Do not use** for: live-app inspection (use `re-frame2-pair`),
  greenfield project bootstrap (use `re-frame2-setup`), v1→v2 migration
  (use `re-frame-migration`), or porting re-frame2 itself (use
  `re-frame2-implementor`).
allowed-tools:
  - Read
  - Edit
  - Write
  - Grep
  - Glob
  # No Bash test/compile/lint surface: this is an authoring-only skill
  # (Q14 lock — see spec/design.md L3). The skill stops at writing the
  # code; the author runs the tests, the compiler, the app. Running gates
  # is general software practice, not a re-frame2 binding the skill teaches.
  # story-mcp authoring-side tools (HYBRID split): re-frame2 owns the
  # AUTHOR/REFINE side — write/refine a variant body (register/unregister),
  # preview one render (preview-variant), and read it back (get-variant /
  # explain-variant). re-frame2-pair owns the RUN side — execute against a
  # live runtime and self-heal off the failures (run-variant / read-failures
  # / record-as-variant / read-a11y-violations / snapshot-identity). The run loop is a
  # HANDOFF to re-frame2-pair, not a loop this skill executes. Per
  # tools/story-mcp/spec/002-Tool-Registry.md; pinned by
  # scripts/check_skill_mcp_drift.py.
  - mcp__re-frame2-story-mcp__get-story-instructions
  - mcp__re-frame2-story-mcp__list-stories
  - mcp__re-frame2-story-mcp__get-story
  - mcp__re-frame2-story-mcp__get-variant
  - mcp__re-frame2-story-mcp__variant->edn
  - mcp__re-frame2-story-mcp__list-tags
  - mcp__re-frame2-story-mcp__list-modes
  - mcp__re-frame2-story-mcp__list-decorators
  - mcp__re-frame2-story-mcp__list-assertions
  - mcp__re-frame2-story-mcp__list-substrates
  - mcp__re-frame2-story-mcp__preview-variant
  - mcp__re-frame2-story-mcp__explain-variant
  - mcp__re-frame2-story-mcp__get-docs-markdown
  - mcp__re-frame2-story-mcp__register-variant
  - mcp__re-frame2-story-mcp__unregister-variant
---

# re-frame2

Authors re-frame2 ClojureScript application code. Router skill: this file carries decision shortcuts; depth lives one level deep in `references/`, `patterns/`, and `decision-trees/`.

## When to load

`.cljs` / `.cljc` authoring of: event handlers, subscriptions, flows, state machines, views, schemas, routes, stories, or the canonical patterns. References to `reg-event`, `reg-sub`, `reg-fx`, `reg-flow`, `reg-machine`, `dispatch`, `subscribe`, `app-db`, flows, frames, regions, tags, or pattern names are sufficient triggers — re-frame2 need not be named.

## When NOT to use

Full skill-disambiguation matrix lives at [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source). In brief: not for live-runtime inspection, greenfield bootstrap, v1→v2 migration, porting re-frame2 itself, or spec / API / EP rationale reading.

## Cardinal rules

1. **Implementation is ground truth.** When spec and `implementation/**` disagree, the implementation wins. Recipes here are verified against `implementation/**` and `examples/**`.
2. **Recipes over explanations.** Use the canonical shape; do not re-derive from first principles.
3. **Distinguish orchestration from state.** State machines for *modes* (legal-transitions-depend-on-current-state); slices for *fields*. See [`decision-trees/slice-or-machine.md`](decision-trees/slice-or-machine.md). Standing mental model for machines: **think in xstate, then map onto re-frame2** — most concepts translate cleanly (`:type :parallel`, `:tags`, `:guards`/`:actions`, `:always`, `:after`, final states); a handful re-frame2 renames or omits (`invoke`→`:spawn`, `context`→`:data`, no `assign`/action-vectors/compound-guard-data) — those divergences are the trap. Translation key + divergence flags: [`references/state-machines/reg-machine.md`](references/state-machines/reg-machine.md).
4. **Schemas at boundaries.** `reg-app-schema` for paths that cross trust boundaries (HTTP payloads, persisted state, snapshot restores). Do not schema-fence every internal key.
5. **Match the canonical shape.** When a pattern has a worked example or reference declaration, match its shape; don't re-derive. (Repo: `examples/**`; consumer app: the relevant pattern/fundamentals leaf plus any reference views your project follows.)
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
| Cached server-state shared across views, with invalidation (TanStack-Query-shaped) | `patterns/resources.md` |
| Workflow after a write succeeds (navigate / toast / fold errors — mutation `:reply-to`), mixed-scope invalidation, or a reusable session/tenant scope (`reg-resource-scope`) | `patterns/resources.md` |
| HTTP with status-aware retries / error projection / batching | `patterns/managed-http.md` |
| Form input with validation and submit | `patterns/forms.md` |
| Long-running browser-side work | `patterns/long-running-work.md` |
| Fire-and-forget side effect, no response | `patterns/async-effect.md` |
| App boot (configure, hydrate, navigate) | `patterns/boot.md` |
| Real-time bidirectional connection | `patterns/websocket.md` |
| View rendering every legal lifecycle state | `patterns/nine-states.md` |
| Async result arrives after state moved on (epoch suppression idiom) | `patterns/stale-detection.md` |
| Parameterised widget rendered N times (entity-id idiom) | `patterns/reusable-components.md` |
| View wrapping a stateful JS library (chart / map / editor) | `patterns/stateful-components.md` |
| SSR form POST handling (progressive enhancement) | `patterns/form-action.md` |
| SSR parallel data fetch before render (fan-out) | `patterns/ssr-loaders.md` |

Patterns compose; a screen can use Forms on submit, RemoteData for the request, and WebSocket for a push.

**Cross-cutting concerns** — orthogonal to pattern choice; load alongside the primary leaf when the trigger fires.

| Concern | Cross-cutting leaf |
|---|---|
| Declaring data sensitive / large; the owner-classifies / framework-projects / sinks-consume egress model + six `:rf.egress/*` profiles | `references/cross-cutting/privacy-and-elision.md` |
| Datadog / Sentry / Honeycomb production listeners that survive `goog.DEBUG=false` | `references/cross-cutting/production-observability.md` |
| Head/meta (`reg-head` / `render-head` / `active-head`); extending the shipped `:rf/hydrate` handler (re-register only to change merge policy) | `references/cross-cutting/ssr-authoring.md` |
| Path overlap / valid segments / `[:rf.path/param …]` templates; canonical EDN identity for a resource key / route param / work id | `references/cross-cutting/path-and-identity.md` |

## Testing your views

To test a re-frame2 view — "does the screen show the right thing?", "does the button dispatch the right event?" — use the **hiccup-walk pattern** (call the view-fn, walk the returned hiccup by `:data-testid` with `re-frame.test-helpers`), not a browser-mount: state-only assertions miss the view-broken and wrong-frame-dispatch bugs the walk catches. Full recipe (`with-app-fixture` / `expect-text` / `wait-until` trio, the walk helpers, single-frame discipline, the `h/testid` helper): [`references/cross-cutting/testing.md`](references/cross-cutting/testing.md).

## Where the depth lives

Load at most two leaves per task. If a task seems to need three, it likely spans patterns and should be broken up.

**Fundamentals — `references/fundamentals/`**: `events.md`, `fx.md`, `cofx.md` (value-returning `reg-cofx` + `:rf.cofx/requires`, EP-0017; durable facts from recordable coeffects/`:rf/time-ms` or the payload, ambient cofx only for diagnostics; `inject-cofx` removed), `subs.md`, `views.md` (`reg-view` defn-shape macro, auto-injected `dispatch`/`subscribe`, `reg-view`-vs-`reg-view*`, the plain-fn `:rf.error/no-frame-context` trap), `flows.md` (`reg-flow` materialised state; flow-vs-sub), `schemas.md`, `frames.md`, `event-state-cycle.md`, `project-structure.md`.

**State machines — `references/state-machines/`**: `reg-machine.md` (declaration + the xstate→re-frame2 translation key + divergence flags), `regions.md` (parallel), `tags.md`, `spawn.md` (child machines), `history.md` (`:type :history` re-entry), `cancellation.md`. Standing model across all: think in xstate, then map onto re-frame2.

**Tooling — `references/tooling/`**: `stories.md`, `routing.md`, `story-recorder.md` (record canvas interactions as a `:script` body), `story-mcp-loop.md` (story-mcp **author/refine** side this skill owns; **handoff** to `re-frame2-pair` for the run/self-heal loop), `xray.md` (devtools panel — mount, launch modes, host-CSS resize, popout, suppress-auto-open). Standing model for Story: think in Storybook JS, then map onto Story (`stories.md` has the concept map).

**Cross-cutting — `references/cross-cutting/`**: `testing.md` (with-frame, dispatch-sync, compute-sub), `api-cheatsheet.md`, `privacy-and-elision.md` (path-based fail-open egress: owner classifies / framework projects / sinks consume — `:sensitive`/`:large` commit-plane effects for durable app-db, projection-relative on subsystems, registration `:sensitive`, per-slot schema `:sensitive?` for `:decode` bodies, the six `:rf.egress/*` profiles + `project-egress`; no propagation), `production-observability.md` (`rf/register-listener!` on `:events`/`:errors`), `ssr-authoring.md` (`reg-head`/`render-head`/`active-head`/`head-model->html` + `:rf.ssr/check-version`/`:rf.ssr/check-schema-digest` fxs), `path-and-identity.md` (the one `:rf/path` algebra + canonical EDN identity: root `[]`, segment domain, overlap, `[:rf.path/param …]` templates, `CEDN-1`, fail-closed host values, digest-as-derived).

**Patterns — `patterns/`**: one leaf per canonical pattern (see table above). Each opens with load triggers, the canonical mini-declaration, the features it uses, trade-offs, and the worked-example link. Pattern → example app: `examples-map.md`.

**Decision trees — `decision-trees/`**: `pick-a-pattern.md`, `slice-or-machine.md`.

## Authoring workflow (every task)

1. Identify the surface — event? sub? fx? cofx? view? machine? route? story? schema?
2. Load at most two leaves (the relevant fundamentals or pattern; a second only if the task spans two surfaces).
3. Match the canonical declaration in the leaf; do not re-derive.
4. Pick the feature prefix (`:cart/...`, `:auth/...`) — never `:rf/*`.
5. Cross-check a worked example or reference view that uses the same shape when one exists (in the re-frame2 repo, that's `examples/**`; in a consumer app, your project's own reference views).
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
- [ ] Hand off the gate to run — name the nearest relevant one concretely (e.g. "run `clojure -M:test` in `src/app/`"). This skill writes the code; the author runs the tests, the compiler, the app.

## How re-frame2 differs from re-frame v1

Do not re-derive v1 mappings from training memory. Migration workflow + breaking-change rule index: `skills/re-frame-migration/`; authoritative rule corpus: [`migration/from-re-frame-v1/README.md`](../../migration/from-re-frame-v1/README.md).

## Background reading (optional)

For "why does it work this way?" or a feature whose shape isn't obvious. All route via `SKILL-REDIRECT.md` at the repo root: *Principles*, *Conventions*, *Construction prompts*, *EP design rationale*.

---

*re-frame2 (v2 line). v1: [re-frame](https://github.com/day8/re-frame). Full skill-disambiguation matrix: [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source). Deep-dive links route through `SKILL-REDIRECT.md`.*
