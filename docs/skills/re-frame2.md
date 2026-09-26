# re-frame2 (authoring)

> Writes re-frame2 ClojureScript application code — events, subscriptions, effects, frames, state machines, schemas, stories, routing, and the canonical patterns.

## What it does

The `re-frame2` skill maps ideas you already know — events, state machines, HTTP retry, optimistic updates — onto re-frame2's actual API, and copies the canonical declaration shape rather than inventing one. For a known pattern (remote data, forms, managed HTTP and the rest) it works from a short pattern note and the worked example under `examples/` that the note links to.

A few rules shape the code it writes. It reads and changes state through `dispatch` and `subscribe`, never through frame internals. It uses the `reg-*` macros, which record source locations for tools, rather than their runtime-function forms. And it gives application keywords a feature prefix such as `:cart/…`, because `:rf/*` is reserved.

## When to reach for it

Use it for **writing or editing re-frame2 application source** — `.cljs` / `.cljc` files. You don't have to name re-frame2; any of these load it:

- References to `reg-event`, `reg-sub`, `reg-fx`, `reg-cofx`, `reg-flow`, `reg-view`, `reg-machine`, `reg-route`, `reg-story`, `reg-app-schema`.
- Mentions of `dispatch`, `subscribe`, `app-db`, frames, regions, tags, the nine UI states.
- Pattern names: RemoteData, Resources, ResourcesMutations, Forms, Boot, WebSocket, NineStates, ManagedHTTP, AsyncEffect, LongRunningWork, StaleDetection, ReusableComponents, StatefulComponents, FormAction.
- "Write a test for a re-frame2 handler / sub / machine."

Use a different skill for:

- Greenfield project setup → [re-frame2-setup](re-frame2-setup.md). When the counter mounts, switch back here.
- Migrating a v1 codebase → [re-frame-migration](re-frame-migration.md). A **v1 name in the prompt routes there**: `reg-event-db`, `reg-event-fx`, `reg-event-ctx`, `reg-sub-raw`, `reg-global-interceptor`, `re-frame.db`, `^:flush-dom` and the rest were removed in re-frame2, and a stale call raises a hard error.
- Inspecting a *running* app → [re-frame2-pair](re-frame2-pair.md).
- Rewriting existing Reagent views into Fresco → [reagent-migration](reagent-migration.md).
- Building a new re-frame2 implementation in another host language → [re-frame2-implementor](re-frame2-implementor.md).

## Kickoff

Ask for what you want in your own words, in a project that has the skill installed:

> *Add a `:todo/toggle` event that flips a todo's `:done?` flag, a subscription for the count of open todos, and a test for both.*

The skill loads itself on requests like that. To load it explicitly, type `/re-frame2`.

For Story work it can also drive the optional `re-frame2-story-mcp` server's tools for listing, previewing and registering variants; running variants against a live app is a [re-frame2-pair](re-frame2-pair.md) job. The server and its setup are in [`tools/story-mcp/README.md`](https://github.com/day8/re-frame2/blob/main/tools/story-mcp/README.md); the skill's side is [`references/tooling/story-mcp-loop.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/references/tooling/story-mcp-loop.md).

## When it stops

When the code is written, it finds the project's nearest noninteractive check — from `deps.edn`, `shadow-cljs.edn`, `package.json` or the README — runs it, and reports the exact command and result. It hands a check to you only when that check is interactive or visual, needs a live runtime (that is [re-frame2-pair](re-frame2-pair.md)), or the project has no such check, and it says which.

## Where the skill lives

- Source: [`skills/re-frame2/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2)
- `SKILL.md`: [`skills/re-frame2/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/SKILL.md) — its §Where the depth lives section maps each question to a reference note.
- Pattern notes: [`skills/re-frame2/patterns/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2/patterns) — one per canonical pattern, each with a short declaration and a link to its worked example.
- Decision trees: [`skills/re-frame2/decision-trees/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2/decision-trees) — short "which shape do I reach for?" walks.
- Worked example map: [`skills/re-frame2/examples-map.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/examples-map.md).
- API and design rationale beyond the skill: [`SKILL-REDIRECT.md`](https://github.com/day8/re-frame2/blob/main/SKILL-REDIRECT.md).
