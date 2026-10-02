# re-frame2 (authoring)

Writes re-frame2 ClojureScript application code — events, subscriptions, effects, coeffects, interceptors, flows, frames, state machines, resources, schemas, routing, SSR, stories and tests.

## Kickoff

Ask for what you want in your own words, in a project that has the skill installed:

> Using re-frame2, add a `:cart/remove` event that removes an item by id, a subscription for the number of items in the cart, and a test for both.

Name `re-frame2` in the request, or use `/re-frame2` in Claude Code.

## What it does

For a larger request, name the behavior and the files in scope: *"Load the cart from the server, cache it across views, and invalidate it after removing an item."* The skill can combine resource reads, mutations and their tests. It also covers boundary validation, production observability and SSR; it loads the relevant recipes as each task needs them.

The `re-frame2` skill maps ideas you already know — events, state machines, HTTP retry, optimistic updates — onto re-frame2's actual API, and copies the canonical declaration shape rather than inventing one. For a known pattern (remote data, forms, managed HTTP and the rest) it works from a short pattern note and the worked example under `examples/` that the note links to.

A few rules shape the code it writes. It reads and changes state through `dispatch` and `subscribe`, never through frame internals. It uses the `reg-*` macros, which record source locations for tools, rather than their runtime-function forms. And it gives application keywords a feature prefix such as `:cart/…`, because `:rf/*` is reserved.

Its views are written for the Reagent, reagent-slim and UIx adapters. For Fresco, re-frame2's own view layer, it carries a short note on what changes and where Fresco's contract lives; everything upstream of the view is the same either way.

For Story work it can also drive the optional `re-frame2-story-mcp` server's tools for listing, previewing and registering variants; running variants against a live app is a [re-frame2-pair](re-frame2-pair.md) job. The server and its setup are in [`tools/story-mcp/README.md`](https://github.com/day8/re-frame2/blob/main/tools/story-mcp/README.md); the skill's side is [`references/tooling/story-mcp-loop.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/references/tooling/story-mcp-loop.md).

## When to reach for it

Use it when you want the agent to write or change re-frame2 application code, tests included. Adding re-frame2 to an existing app with substantial code or another state management library also belongs here. You don't have to name re-frame2 for it to load.

For related work:

- Starting a new project from an empty directory → [re-frame2-setup](re-frame2-setup.md). When the counter mounts, switch back here.
- Code still on re-frame v1 → [re-frame-migration](re-frame-migration.md). re-frame2 removed `reg-event-db`, `reg-event-fx` and `reg-event-ctx`: a leftover call raises `:rf.error/reg-event-db-removed` (or its `-fx-` / `-ctx-` twin) naming `reg-event`. Other v1-only names, such as `reg-sub-raw` and `re-frame.db`, no longer exist, and `^:flush-dom` metadata is ignored.
- A review of code you already have → [re-frame2-improver](re-frame2-improver.md).
- A question about the running app → [re-frame2-pair](re-frame2-pair.md).
- Rewriting existing Reagent views into Fresco → [reagent-migration](reagent-migration.md).

## Completion and limits

The result is source changes plus the check used to verify them. When the code is written, it finds the project's nearest noninteractive check — from `deps.edn`, `shadow-cljs.edn`, `package.json` or the README — runs it, and reports the exact command and result. It hands a check to you only when that check is interactive or visual, needs a live runtime (that is [re-frame2-pair](re-frame2-pair.md)), or the project has no such check, and it says which.

The [skill contract](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/SKILL.md) contains the full workflow and links to its reference notes.
