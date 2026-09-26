# re-frame2 (authoring)

> Writes re-frame2 ClojureScript application code — events, subscriptions, effects, frames, state machines, schemas, stories, routing, and the canonical patterns.

## What it does

The `re-frame2` skill maps ideas you already know — events, state machines, HTTP retry, optimistic updates — onto re-frame2's actual API, and copies the canonical declaration shape rather than inventing one. For a known pattern (remote data, forms, managed HTTP and the rest) it works from a short pattern note and the worked example under `examples/` that the note links to.

A few rules shape the code it writes. It reads and changes state through `dispatch` and `subscribe`, never through frame internals. It uses the `reg-*` macros, which record source locations for tools, rather than their runtime-function forms. And it gives application keywords a feature prefix such as `:cart/…`, because `:rf/*` is reserved.

Its views are written for the Reagent, reagent-slim and UIx adapters. For Fresco, re-frame2's own view layer, it carries a short note on what changes and where Fresco's contract lives; everything upstream of the view is the same either way.

For Story work it can also drive the optional `re-frame2-story-mcp` server's tools for listing, previewing and registering variants; running variants against a live app is a [re-frame2-pair](re-frame2-pair.md) job. The server and its setup are in [`tools/story-mcp/README.md`](https://github.com/day8/re-frame2/blob/main/tools/story-mcp/README.md); the skill's side is [`references/tooling/story-mcp-loop.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/references/tooling/story-mcp-loop.md).

## When to reach for it

Use it when you want the agent to write or change application code, tests included, in a project that already runs on re-frame2. You don't have to name re-frame2 for it to load. The `description` in its [`SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/SKILL.md) is the text the agent matches your request against.

Every skill is listed under [Which skill do I want?](index.md#which-skill-do-i-want). The ones most easily confused with this one:

- A project with no re-frame2 in it yet → [re-frame2-setup](re-frame2-setup.md). When the counter mounts, switch back here.
- Code still on re-frame v1 → [re-frame-migration](re-frame-migration.md). re-frame2 removed `reg-event-db`, `reg-event-fx` and `reg-event-ctx`: a leftover call raises `:rf.error/reg-event-db-removed` (or its `-fx-` / `-ctx-` twin) naming `reg-event`. Other v1-only names, such as `reg-sub-raw` and `re-frame.db`, no longer exist, and `^:flush-dom` metadata is ignored.
- A review of code you already have → [re-frame2-improver](re-frame2-improver.md).
- A question about the running app → [re-frame2-pair](re-frame2-pair.md).
- Rewriting existing Reagent views into Fresco → [reagent-migration](reagent-migration.md).

## Kickoff

Ask for what you want in your own words, in a project that has the skill installed:

> *Add a `:cart/remove` event that removes an item by id, a subscription for the number of items in the cart, and a test for both.*

The skill loads itself on requests like that. To load it explicitly, type `/re-frame2`.

## When it stops

When the code is written, it finds the project's nearest noninteractive check — from `deps.edn`, `shadow-cljs.edn`, `package.json` or the README — runs it, and reports the exact command and result. It hands a check to you only when that check is interactive or visual, needs a live runtime (that is [re-frame2-pair](re-frame2-pair.md)), or the project has no such check, and it says which.

## Where the skill lives

- Source: [`skills/re-frame2/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2)
- `SKILL.md`: [`skills/re-frame2/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/SKILL.md) — its §Where the depth lives section maps each question to a reference note.
- Pattern notes: [`skills/re-frame2/patterns/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2/patterns) — one per canonical pattern, each with a short declaration and a link to its worked example.
- Decision trees: [`skills/re-frame2/decision-trees/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2/decision-trees) — short "which shape do I reach for?" walks.
- Worked example map: [`skills/re-frame2/examples-map.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/examples-map.md).
- API and design rationale beyond the skill: [`SKILL-REDIRECT.md`](https://github.com/day8/re-frame2/blob/main/SKILL-REDIRECT.md).
