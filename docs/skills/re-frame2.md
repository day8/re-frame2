# re-frame2 (authoring)

> Writes re-frame2 ClojureScript application code — events, subscriptions, effects, frames, state machines, schemas, stories, routing, and the canonical patterns.

## What it does

The `re-frame2` skill is the **authoring** skill for re-frame2: it teaches an agent the re-frame2-specific binding for the ideas the user already knows (events, FSMs, HTTP retry, optimistic updates). It carries the cardinal rules — implementation-is-ground-truth, recipes-over-explanations, frames-before-globals, reserved `:rf/*` namespaces, `reg-*` macros over `register-*` functions — and a one-level-deep map of reference and pattern leaves. The agent loads at most two leaves per task and matches the canonical declaration verbatim rather than deriving a shape from first principles.

Worked examples in `examples/reagent/<x>/` are treated as canonical. Pattern leaves (`patterns/remote-data.md`, `patterns/forms.md`, `patterns/managed-http.md`, ...) name the features the pattern uses, give the canonical mini-declaration, and link to the worked example.

## When to reach for it

Load this skill when the prompt is about **writing or editing re-frame2 application source** — `.cljs` / `.cljc` files. The user does not have to name re-frame2; any of these are sufficient triggers:

- References to `reg-event-db`, `reg-event-fx`, `reg-event-ctx`, `reg-sub`, `reg-fx`, `reg-cofx`, `reg-view`, `reg-machine`, `reg-route`, `reg-story`, `reg-app-schema`.
- Mentions of `dispatch`, `subscribe`, `app-db`, frames, regions, tags, the nine UI states.
- Pattern names: RemoteData, Forms, Boot, WebSocket, NineStates, ManagedHTTP, AsyncEffect, LongRunningWork, StaleDetection.
- "Write a test for a re-frame2 handler / sub / machine."

Do **not** use this skill for:

- Greenfield project setup → use [re-frame2-setup](re-frame2-setup.md).
- Migrating a v1 codebase → use [re-frame-migration](re-frame-migration.md).
- Inspecting a *running* app → use [re-frame-pair2](re-frame-pair2.md).
- Reading the full API or EP rationale → follow [`SKILL-REDIRECT.md`](https://github.com/day8/re-frame2/blob/main/SKILL-REDIRECT.md).

## Kickoff

The skill auto-triggers on any of the surfaces above. No paste-ready prompt is needed for routine authoring tasks — open Claude Code in a project that has the skill installed, ask for what you want in your own words, and the skill loads itself.

For force-loading from a slash-command launcher:

```
/skill re-frame2
```

For greenfield-then-author, walk the [re-frame2-setup](re-frame2-setup.md) skill first; when the counter mounts, switch to this one.

## Where the skill lives

- Source: [`skills/re-frame2/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2)
- `SKILL.md`: [`skills/re-frame2/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/SKILL.md)
- Reference leaves: [`skills/re-frame2/reference/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2/reference) — fundamentals (events, fx, cofx, subs, schemas, frames, project-structure), state-machines (reg-machine, regions, tags, invoke, cancellation), tooling (stories, routing), cross-cutting (testing, API cheatsheet).
- Pattern leaves: [`skills/re-frame2/patterns/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2/patterns) — one leaf per canonical pattern, each with a mini-declaration and a link to the worked example.
- Decision trees: [`skills/re-frame2/decision-trees/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2/decision-trees) — *slice or machine?*, *pick a pattern*.
- Worked example map: [`skills/re-frame2/examples-map.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/examples-map.md).
