---
name: re-frame2
description: >
  Writes re-frame2 ClojureScript application code — events, subscriptions,
  effects, frames, state machines (reg-machine, parallel regions, tags,
  invoke), schemas, stories, routing, tests, and the canonical patterns
  (RemoteData, Forms, Boot, WebSocket, NineStates, ManagedHTTP,
  AsyncEffect, LongRunningWork, StaleDetection). Use whenever the user
  mentions re-frame2, reg-event-db, reg-event-fx, reg-sub, reg-fx,
  reg-cofx, reg-view, reg-machine, reg-route, reg-story, reg-app-schema,
  dispatch, subscribe, app-db, frames, regions, tags, the nine UI
  states, managed HTTP, RemoteData lifecycles, writing tests for a
  re-frame2 app, or state-machine-for-HTTP shapes — even when re-frame2
  is not named explicitly. **Authoring only** (writing new code).
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
  # story-mcp authoring-side tools (HYBRID split): re-frame2 owns the
  # authoring loop (write/refine variant); re-frame2-pair owns the run side
  # (run-variant / read-failures / record-as-variant). Per
  # tools/story-mcp/spec/002-Tool-Registry.md.
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
  - mcp__re-frame2-story-mcp__get-docs-markdown
  - mcp__re-frame2-story-mcp__register-variant
  - mcp__re-frame2-story-mcp__unregister-variant
---

# re-frame2

Authors re-frame2 ClojureScript application code. Router skill: this file carries decision shortcuts; depth lives one level deep in `references/`, `patterns/`, and `decision-trees/`.

## When to load

`.cljs` / `.cljc` authoring of: event handlers, subscriptions, state machines, views, schemas, routes, stories, or the canonical patterns. References to `reg-event-*`, `reg-sub`, `reg-fx`, `reg-machine`, `dispatch`, `subscribe`, `app-db`, frames, regions, tags, or pattern names are sufficient triggers — re-frame2 need not be named.

## When NOT to use

Full skill-disambiguation matrix lives at [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source). In brief: not for live-runtime inspection, greenfield bootstrap, v1→v2 migration, porting re-frame2 itself, or spec / API / EP rationale reading.

## Cardinal rules

1. **Implementation is ground truth.** When spec and `implementation/**` disagree, the implementation wins. Recipes here are verified against `implementation/**` and `examples/reagent/**`.
2. **Recipes over explanations.** Use the canonical shape; do not re-derive from first principles.
3. **Distinguish orchestration from state.** State machines for *modes* (legal-transitions-depend-on-current-state); slices for *fields*. See [`decision-trees/slice-or-machine.md`](decision-trees/slice-or-machine.md). **For machines, the standing mental model is: think how you'd do it in xstate, then map onto re-frame2** — xstate is the widely-known FSM model in your training data, and most concepts translate cleanly (`:type :parallel`, `:tags`, `:guards`/`:actions`, `:always`, `:after`, final states). A handful of slots re-frame2 deliberately renames or omits (`invoke`→`:spawn`, `context`→`:data`, no `assign`/action-vectors/compound-guard-data); those divergences are the trap. The translation key + divergence flags live in [`references/state-machines/reg-machine.md`](references/state-machines/reg-machine.md).
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

**Cross-cutting concerns** — orthogonal to pattern choice; load alongside the primary leaf when the trigger fires.

| Concern | Cross-cutting leaf |
|---|---|
| Declaring a handler or schema slot as containing secrets / PII / large blobs | `references/cross-cutting/privacy-and-elision.md` |
| Wiring Datadog / Sentry / Honeycomb production listeners that survive `goog.DEBUG=false` | `references/cross-cutting/production-observability.md` |
| Authoring head/meta (`reg-head` / `render-head` / `active-head`) or a custom `:rf/hydrate` handler dispatching the version + schema-digest check fxs | `references/cross-cutting/ssr-authoring.md` |

## Testing your views

When the user asks how to test a re-frame2 view — "does the screen show the right thing?", "does the button dispatch the right event?" — suggest the **hiccup-walk pattern**, not a browser-mount.

1. Dispatch events via `rf/dispatch-sync` into the test frame (standard re-frame2 testing surface).
2. Call the view-fn directly. It returns hiccup; that's just data.
3. Walk the hiccup with `re-frame.test-helpers` (`find-by-testid` / `text-content` / `invoke-handler`).

```clojure
(:require [re-frame.core :as rf]
          [re-frame.test-helpers :as h])

(deftest counter-view-shows-and-fires
  (rf/with-frame [f (rf/make-frame {:on-create [:counter/init]})]
    (let [tree (counter-view {:n 0})
          btn  (h/find-by-testid tree "counter-inc")]
      (h/invoke-handler btn :on-click nil)
      (is (= 1 (:n (rf/get-frame-db f)))))))
```

**Single-frame vs. multi-frame.** Application tests use ONE frame — the host frame. Views, events, subs, asserts all reference the same frame. Multi-frame test harnesses exist (e.g. `tools/causa/.../e2e_multi_frame.cljs`) but they are for **observer / tool code** — code that runs in one frame and watches another. Do NOT propose a multi-frame harness for testing a regular application view.

**State alone is not enough.** State-only assertions (`(is (= 2 (:n db)))`) catch handler bugs but miss two classes:
- *State-correct, view-broken* — handler updated db, view reads wrong path / forgets a branch.
- *Wrong-frame dispatch* — `:on-click` dispatches into the wrong frame; host-frame state never changes.

The hiccup-walk + invoke-handler pattern catches both, on JVM and Node-CLJS, with no browser. Full pattern walkthrough lives at [`docs/guide/15-testing.md` §Asserting the view shows the right thing](../../docs/guide/15-testing.md).

The `h/testid` authoring helper standardises the attrs fragment at view call sites:

```clojure
[:button (h/testid "counter-inc" {:on-click #(rf/dispatch [:counter/inc])})
 "+"]
```

Use it whenever you write a new view that wants a test handle.

## Where the depth lives

Load at most two leaves per task. If a task seems to need three, it likely spans patterns and should be broken up.

**Fundamentals — `references/fundamentals/`**: `events.md`, `fx.md`, `cofx.md`, `subs.md`, `schemas.md`, `frames.md`, `event-state-cycle.md`, `project-structure.md`.

**State machines — `references/state-machines/`**: `reg-machine.md` (declaration + the xstate→re-frame2 translation key), `regions.md` (parallel), `tags.md`, `spawn.md` (child machines), `cancellation.md`. Standing mental model across all of these: think in xstate, then map onto re-frame2 — see `reg-machine.md` for the full mapping table and deliberate-divergence flags.

**Tooling — `references/tooling/`**: `stories.md`, `routing.md`, `story-recorder.md` (record canvas interactions as `:play-script`), `story-mcp-loop.md` (agent self-healing loop over MCP), `causa.md` (the devtools panel — mount strategy, launch modes, host-CSS-variable resize contract, popout, suppress-auto-open).

**Cross-cutting — `references/cross-cutting/`**: `testing.md` (with-frame, dispatch-sync, compute-sub), `api-cheatsheet.md`, `privacy-and-elision.md` (schema `:sensitive?` / `:large?` / `elide-wire-value`), `production-observability.md` (`register-event-listener!` / `register-error-listener!`), `ssr-authoring.md` (`reg-head` / `render-head` / `active-head` / `head-model->html` and the `:rf.ssr/check-version` + `:rf.ssr/check-schema-digest` fxs).

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

For v1-trained context: migration workflow + breaking-change rule index lives in `skills/re-frame-migration/`; the authoritative rule corpus is [`migration/from-re-frame-v1/README.md`](../../migration/from-re-frame-v1/README.md). Do not re-derive v1 mappings from training memory.

## Background reading (optional)

Reach for these when the user asks "why does it work this way?" or designs a feature whose shape isn't obvious. All route via `SKILL-REDIRECT.md` at the repo root: *Principles*, *Conventions*, *Construction prompts*, *EP design rationale*.

---

*re-frame2 (v2 line). v1: [re-frame](https://github.com/day8/re-frame). Full skill-disambiguation matrix: [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source). Deep-dive links route through `SKILL-REDIRECT.md`.*
