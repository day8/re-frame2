# re-frame2 — Design

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The design rationale and locked decisions for the `re-frame2` skill. A future agent could re-author this skill from this folder alone.

## 1. Goal

Help an AI write working re-frame2 ClojureScript application code while spending as little context as possible. Output is `.cljs` / `.cljc` source that compiles, runs, and passes the author's tests. The skill is not a conceptual overview, not a learning guide, not marketing — every decision is judged against: *does this make it more likely the AI produces correct, idiomatic re-frame2 code on the first attempt without burning more context than the task warrants?*

## 2. Pillars (locked)

1. **Correctness — recipes over explanations.** Operationalised guidance ("use a machine when X") over abstract principles. The AI reaches for a canonical shape, doesn't derive one. **Q14 lock applies: NO verification module** — no `references/verify.md`, no "verify before claiming done" hard rule. The author runs tests; the skill stops at writing the code.
2. **Idiomaticness — verified against `implementation/**` + `examples/**`.** The CLJS reference is the source of truth for *what the API is*. The spec corpus is *why*; it's never quoted for surface claims.
3. **Context economy — distillation discipline.** `SKILL.md` is a router; one-level-deep leaves carry the depth. Every line costs context every time it loads. SKILL.md targets ~180 lines (well under Anthropic's 500-line ceiling); reference / pattern leaves target ~150, ceiling 250.
4. **Assume training knowledge — teach only the re-frame2 binding.** The AI already knows what WebSockets, FSMs, optimistic updates, and HTTP retry are. The skill's job is to bridge that to the specific re-frame2 features (`reg-machine`, `:rf.http/managed`, `:fsm/parallel-regions`, etc.). The **cut-test**: if a sentence could be written about React, Vue, or Elm unchanged, it belongs in training data, not this skill.

## 3. Locked decisions

These are not up for re-litigation. A future authoring pass MUST preserve them unless explicitly unlocked by Mike.

### L1 — Implementation is ground truth

For every code snippet in a leaf, the surface (function name, arity, options keyword set) is verified against `implementation/**`. If the spec says X and `implementation/<feature>/src/...` says Y, the implementation wins.

### L2 — Examples in `examples/**` are canonical

When a pattern has a worked example, the leaf points at it and matches its shape. The example reflects the implementation as currently shipped.

### L3 — Q14 — NO verification module

Per `ai/findings/re-frame2-skill-design-v2.md` §Q14: the skill does not teach the agent to verify its own output. No `references/verify.md`, no "verification mandatory before done" hard rule in SKILL.md. The AI applies the recipes; the author runs the tests, the compiler, the app. Running tests is general software practice — Pillar 4 says don't teach what the AI already knows.

### L4 — Two-level routing only

SKILL.md → reference leaf (or pattern leaf, or decision-tree leaf). No SKILL → A → B chains. Every leaf is one level deep so it can be reached with one read.

### L5 — Patterns are recipes, not tutorials

Each `patterns/*.md` opens with load triggers, gives the canonical mini-declaration, names the re-frame2 features used, lists trade-offs, and links to the worked example. No conceptual overviews of HTTP / WebSockets / forms — Pillar 4 forbids it.

### L6 — `:rf/*` namespace is reserved

Application keywords use the app's own namespace (`:cart/`, `:auth/`); `:rf/*` and `:rf.machine/*` / `:rf.epoch/*` / `:rf.http/*` / `:rf.error/*` are framework-owned. This rule lands in SKILL.md cardinal rules so it's read on every load.

### L7 — `reg-*` macros over the runtime-fn forms

The macros capture source-coords that Xray and re-frame2-pair rely on. The functional counterparts — the `*`-suffixed twins for `reg-view*` / `reg-machine*`, or the same name in value position for everything else (`reg-event`, `reg-sub`, `reg-interceptor`, …) — exist for programmatic / generated cases; recipes always reach for the macro.

### L8 — Frames before globals

Code talks to a frame — an explicitly registered, descriptively-named one (EP-0002 — no ambient `:rf/default`; the frame is carried, never inferred); multi-frame apps pass `{:frame :stories}`. Recipes never import frame internals, never bypass `dispatch` / `subscribe` to mutate state.

### L9 — Schemas at boundaries, not everywhere

`reg-app-schema` is registered for the paths that cross trust boundaries (incoming HTTP payloads, persisted state on restore, machine snapshot restores). Internal slices are not schema-fenced by default.

### L10 — No issue-tracker ids in user-facing skill content

`SKILL.md` and the `references/` / `patterns/` / `decision-trees/` leaves carry no issue-tracker ids — they would distract the agent using the skill, and mean nothing on a consumer codebase.

### L11 — Findings stay local

Per Mike's standing memory rule "Findings is local-only" — design exploration happens in `ai/findings/`; never committed. This skill's commits contain only `skills/re-frame2/**`.

## 4. Audience and scope

### In scope

- ClojureScript application authors writing re-frame2 code.
- The `reg-*` family — `reg-event` (the one public event registrar), `reg-sub`, `reg-fx`, `reg-cofx`, `reg-flow`, `reg-interceptor`, `reg-view`, `reg-machine`, `reg-route`, `reg-story`, `reg-app-schema`, `reg-resource`, `reg-mutation`.
- The canonical patterns — RemoteData, Forms, Boot, WebSocket, NineStates, ManagedHTTP, AsyncEffect, LongRunningWork, StaleDetection.
- Frames, regions, tags, machine snapshots, the event-state cycle.
- Test-authoring (`make-reset-runtime-fixture`, `dispatch-sync`, `compute-sub`, `with-frame`).

### Out of scope

- **Framework implementation** (how the registrar dispatches, how the machine compiler works) — routes through `SKILL-REDIRECT.md` to EP design.
- **Greenfield project setup** — `skills/re-frame2-setup/`.
- **v1→v2 migration** — `skills/re-frame-migration/`.
- **Live-runtime inspection** — `skills/re-frame2-pair/`.
- **Building re-frame2 in a different host** — `skills/re-frame2-implementor/`.
- **Non-CLJS hosts** — the spec is host-agnostic; the skill is not.

## 5. File structure (locked)

```
skills/re-frame2/
├── SKILL.md                     (router; ~180 lines)
├── README.md                    (human-facing intro)
├── LICENSE                      (MIT)
├── package.json                 (npm metadata)
├── examples-map.md              (pattern → worked-example cross-ref)
├── references/
│   ├── fundamentals/            (events, fx, cofx, subs, views, flows, schemas, frames, images, event-state-cycle, project-structure)
│   ├── state-machines/          (reg-machine, xstate-translation, machine-schemas, regions, tags, spawn, history, cancellation)
│   ├── tooling/                 (stories, routing, story-recorder, story-mcp-loop, xray)
│   └── cross-cutting/           (testing, api-cheatsheet, privacy-and-elision, production-observability, ssr-authoring, path-and-identity)
├── patterns/                    (one leaf per canonical pattern)
├── decision-trees/              (pick-a-pattern, slice-or-machine)
├── evals/                       (eval harness inputs)
└── spec/
    ├── design.md                (this file)
    ├── inputs.md                (canonical inputs)
    └── authoring-prompt.md      (one-shot reauthor prompt)
```

Each reference / pattern / decision-tree leaf targets ~150 lines; in practice they range ~80–260, with `testing.md` the one deliberate over-ceiling leaf (it owns the full view-test recipe SKILL.md routes to). A typical authoring session reads SKILL.md (~180) + one reference leaf + one pattern leaf ≈ ~480 LoC — well under any reasonable context budget.

## 6. Discovery surface (frontmatter `description`)

The `description` is "pushy" per Anthropic best practice — it lists every re-frame2 surface that should trigger discovery: `reg-event` (the one event form — EP-0018), `reg-sub`, `reg-fx`, `reg-cofx`, `reg-flow`, `reg-view`, `reg-machine`, `reg-route`, `reg-story`, `reg-app-schema`, `dispatch`, `subscribe`, `app-db`, `flows`, `frames`, `regions`, `tags`, `managed HTTP`, `RemoteData lifecycles`, plus the natural-language framing "writing tests for a re-frame2 app". The description also explicitly carves out the adjacent skills (`re-frame2-pair`, `re-frame2-setup`) so the AI routes correctly.

## 7. Anti-patterns the skill explicitly resists

- **Re-deriving canonical shapes from first principles.** Cardinal rule "recipes over explanations" + Pillar 1.
- **Loading three or more leaves for one task.** SKILL.md's loading-map rule: at most two leaves; if a task seems to need three, the request likely spans patterns and should be broken up.
- **Quoting spec text for API surface.** L1 — the spec is *why*, not *what*.
- **Using `:rf.*` for application keywords.** Cardinal rule L6.
- **Schema-fencing every internal key.** Cardinal rule L9.
- **Bypassing `dispatch` / `subscribe` to mutate state.** Cardinal rule L8 — frames before globals.
- **Authoring against re-frame v1 idioms by recall.** SKILL.md "How re-frame2 differs from re-frame v1" section: don't re-derive v1 mappings; route to `skills/re-frame-migration/`.

## 8. Why this design diverges from `re-frame-migration`

- **Patterns are first-class** — the migration skill is a workflow over a rule corpus; this skill is a library of authoring recipes.
- **Decision trees are first-class** — pick-a-pattern + slice-or-machine are the two recurring decisions; the migration skill has only "Type A or Type B?".
- **`examples-map.md`** — pattern → example cross-reference; the migration skill has no examples surface.
- **No kickoff prompt** — the AI is already engaged in authoring when this skill loads; there's nothing to bootstrap.

## 9. Open questions (deferred to Mike)

### OQ1 — Should the skill ship eval cases as part of `evals/`?

A growing `evals/` set of input/output pairs would let the skill be regression-tested as the implementation evolves. Status: deferred — `evals/` is bootstrapped but not yet load-bearing.

### OQ2 — When to split per-feature skills out of this one?

Some patterns (state machines, managed HTTP) are growing. A future split into `re-frame2-machines/` or `re-frame2-http/` is possible. Status: not until any single leaf exceeds ~400 LoC consistently.

## 10. Derivation/process algebra — how the skill handles it

The derivation/process algebra (`spec/Derivations.md`) is the one *inspection/specification* view that subscriptions, flows, resources, route facts, and machine selectors lower to (inputs / output / storage class / evaluation policy / lifecycle; superkinds `:derivation` / `:process`). It mints **no authoring API** — an author never writes against this shape; it's a view, not an API.

The skill therefore carries **no top-level orienting paragraph** in SKILL.md (the router stays decision-oriented, and Pillar 4 says teach the binding, not the theory). The narrow authoring payoff lands at two leaves:

- `references/fundamentals/flows.md` — the flow-vs-subscription decision turns on storage / durability / lifecycle. A "same function, different policy" anchor names why the decision is subtle (both are derivations; you choose policy, not math) and cross-links the algebra.
- `references/fundamentals/frames.md` — the where-state-lives material (two durable partitions + the ephemeral sub-cache) is the four **storage classes** (`:app-db` / `:runtime-db` / `:ephemeral` / `:host-transient`). The cross-link names the vocabulary and pins the split: a *remote* fact still has a **local** storage class — "remote" is its `:authority`, never where it's stored.

Both cross-links route through `SKILL-REDIRECT.md` → *Derivations and processes (the algebra)* and flag the divergence ("you don't author against this shape — it's a view, not an API"). Tool-facing siblings (`re-frame2-xray`, `re-frame2-pair`) and the implementor skill carry the heavier vocabulary; this authoring skill stays light. The graph accessor is **internal-only** (no public accessor name, no `re-frame.core` facade export in this slice).

## 11. Composition vocabulary in the authoring skill

The public composition model is `image → frame → event stream` (`rf/image` + `rf/make-frame`). There is **no public realm / app / module composition vocabulary** for an author to learn. The skill teaches only the positive current model — construct image values, create/own frames, target frames, reload images — with frame isolation as the whole isolation story. A single-frame author spells none of it; a multi-frame author reaches for explicit `rf/image` values. The leaf content (`references/fundamentals/frames.md`, `references/cross-cutting/testing.md`, `references/cross-cutting/api-cheatsheet.md`) carries this model.
