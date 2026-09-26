# re-frame2 (authoring)

> Writes re-frame2 ClojureScript application code — events, subscriptions, effects, frames, state machines, schemas, stories, routing, and the canonical patterns.

## What it does

The `re-frame2` skill is the **authoring** skill for re-frame2: it teaches an agent the re-frame2-specific binding for the ideas the user already knows (events, FSMs, HTTP retry, optimistic updates). It carries the cardinal rules — implementation-is-ground-truth, recipes-over-explanations, frames-before-globals, reserved `:rf/*` namespaces, `reg-*` macros over the runtime-fn forms — and a one-level-deep map of reference and pattern leaves. The agent loads at most two leaves per task and matches the canonical declaration verbatim rather than deriving a shape from first principles.

When the code is written it discovers the project's nearest noninteractive gate — from `deps.edn`, `shadow-cljs.edn`, `package.json` or the README — runs it, and reports the exact command and result. It hands a check to you only when that check is interactive or visual, needs a live runtime (that is [re-frame2-pair](re-frame2-pair.md)), or the project has no such gate, and it says which.

Worked examples in `examples/` are treated as canonical. Pattern leaves (`patterns/remote-data.md`, `patterns/forms.md`, `patterns/managed-http.md`, ...) name the features the pattern uses, give the canonical mini-declaration, and link to the worked example.

## When to reach for it

Load this skill when the prompt is about **writing or editing re-frame2 application source** — `.cljs` / `.cljc` files. The user does not have to name re-frame2; any of these are sufficient triggers:

- References to `reg-event`, `reg-sub`, `reg-fx`, `reg-cofx`, `reg-flow`, `reg-view`, `reg-machine`, `reg-route`, `reg-story`, `reg-app-schema`.
- Mentions of `dispatch`, `subscribe`, `app-db`, frames, regions, tags, the nine UI states.
- Pattern names: RemoteData, Resources, ResourcesMutations, Forms, Boot, WebSocket, NineStates, ManagedHTTP, AsyncEffect, LongRunningWork, StaleDetection, ReusableComponents, StatefulComponents, FormAction.
- "Write a test for a re-frame2 handler / sub / machine."

Do **not** use this skill for:

- Greenfield project setup → use [re-frame2-setup](re-frame2-setup.md).
- Migrating a v1 codebase → use [re-frame-migration](re-frame-migration.md). A **v1 name in the prompt is that skill's trigger, not this one's** — `reg-event-db`, `reg-event-fx`, `reg-event-ctx`, `reg-sub-raw`, `reg-global-interceptor`, `re-frame.db`, `^:flush-dom` and the rest are surfaces re-frame2 removed (a stale call raises a hard error), so route them to `re-frame-migration` rather than loading this skill.
- Inspecting a *running* app → use [re-frame2-pair](re-frame2-pair.md).
- Rewriting existing Reagent views into Fresco → use [reagent-migration](reagent-migration.md).
- Building a new re-frame2 implementation in another host language → use [re-frame2-implementor](re-frame2-implementor.md).
- Reading the full API or EP rationale → follow [`SKILL-REDIRECT.md`](https://github.com/day8/re-frame2/blob/main/SKILL-REDIRECT.md).

## Kickoff

The skill auto-triggers on any of the surfaces above. No paste-ready prompt is needed for routine authoring tasks — open Claude Code in a project that has the skill installed, ask for what you want in your own words, and the skill loads itself.

To load it explicitly, type `/re-frame2`.

For Story work the skill can also drive the optional `re-frame2-story-mcp` server's author-and-refine tools (listing, previewing and registering variants); running variants against a live app is a [re-frame2-pair](re-frame2-pair.md) job. The server and its setup are described in [`tools/story-mcp/README.md`](https://github.com/day8/re-frame2/blob/main/tools/story-mcp/README.md); the skill's side of the loop is [`references/tooling/story-mcp-loop.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/references/tooling/story-mcp-loop.md).

For greenfield-then-author, walk the [re-frame2-setup](re-frame2-setup.md) skill first; when the counter mounts, switch to this one.

## Where the skill lives

- Source: [`skills/re-frame2/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2)
- `SKILL.md`: [`skills/re-frame2/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/SKILL.md)
- Reference leaves: [`skills/re-frame2/references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2/references) — [`SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/SKILL.md) §Where the depth lives is the leaf map, and the skill [`README.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/README.md) §Layout lists every leaf.
- Pattern leaves: [`skills/re-frame2/patterns/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2/patterns) — one leaf per canonical pattern, each with a mini-declaration and a link to the worked example.
- Decision trees: [`skills/re-frame2/decision-trees/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2/decision-trees) — the short "which shape do I reach for?" walks.
- Worked example map: [`skills/re-frame2/examples-map.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/examples-map.md).
