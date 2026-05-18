# re-frame2 — Design

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The design rationale and locked decisions for the `re-frame2` skill. A future agent could re-author this skill from this folder alone.

## 1. Goal

Help an AI write working re-frame2 ClojureScript application code while spending as little context as possible. Output is `.cljs` / `.cljc` source that compiles, runs, and passes the author's tests. The skill is not a conceptual overview, not a learning guide, not marketing — every decision is judged against: *does this make it more likely the AI produces correct, idiomatic re-frame2 code on the first attempt without burning more context than the task warrants?*

## 2. Pillars (locked)

1. **Correctness — recipes over explanations.** Operationalised guidance ("use a machine when X") over abstract principles. The AI reaches for a canonical shape, doesn't derive one. **Q14 lock applies: NO verification module** — no `reference/verify.md`, no "verify before claiming done" hard rule. The author runs tests; the skill stops at writing the code.
2. **Idiomaticness — verified against `implementation/**` + `examples/reagent/**`.** The CLJS reference is the source of truth for *what the API is*. The spec corpus is *why*; it's never quoted for surface claims.
3. **Context economy — distillation discipline.** `SKILL.md` is a router; one-level-deep leaves carry the depth. Every line costs context every time it loads. SKILL.md targets ~300-400 lines (under Anthropic's 500-line ceiling); reference / pattern leaves target ~150, ceiling 250.
4. **Assume training knowledge — teach only the re-frame2 binding.** The AI already knows what WebSockets, FSMs, optimistic updates, and HTTP retry are. The skill's job is to bridge that to the specific re-frame2 features (`reg-machine`, `:rf.http/managed`, `:fsm/parallel-regions`, etc.). The **cut-test**: if a sentence could be written about React, Vue, or Elm unchanged, it belongs in training data, not this skill.

## 3. Locked decisions

These are not up for re-litigation. A future authoring pass MUST preserve them unless explicitly unlocked by Mike.

### L1 — Implementation is ground truth

For every code snippet in a leaf, the surface (function name, arity, options keyword set) is verified against `implementation/**`. If the spec says X and `implementation/<feature>/src/...` says Y, the implementation wins and a `bd` bead gets filed against the spec.

### L2 — Examples in `examples/reagent/<x>/` are canonical

When a pattern has a worked example, the leaf points at it and matches its shape. The example reflects the implementation as currently shipped.

### L3 — Q14 — NO verification module

Per `ai/findings/re-frame2-skill-design-v2.md` §Q14: the skill does not teach the agent to verify its own output. No `reference/verify.md`, no "verification mandatory before done" hard rule in SKILL.md. The AI applies the recipes; the author runs the tests, the compiler, the app. Running tests is general software practice — Pillar 4 says don't teach what the AI already knows.

### L4 — Two-level routing only

SKILL.md → reference leaf (or pattern leaf, or decision-tree leaf). No SKILL → A → B chains. Every leaf is one level deep so it can be reached with one read.

### L5 — Patterns are recipes, not tutorials

Each `patterns/*.md` opens with load triggers, gives the canonical mini-declaration, names the re-frame2 features used, lists trade-offs, and links to the worked example. No conceptual overviews of HTTP / WebSockets / forms — Pillar 4 forbids it.

### L6 — `:rf/*` namespace is reserved

Application keywords use the app's own namespace (`:cart/`, `:auth/`); `:rf/*` and `:rf.machine/*` / `:rf.epoch/*` / `:rf.http/*` / `:rf.error/*` are framework-owned. This rule lands in SKILL.md cardinal rules so it's read on every load.

### L7 — `reg-*` macros over `register-*` functions

The macros capture source-coords that Causa and re-frame2-pair rely on. Functional registrations exist for programmatic / generated cases; recipes always reach for the macro.

### L8 — Frames before globals

Code talks to a frame. Default is `:rf/default`; multi-frame apps pass `{:frame :stories}`. Recipes never import frame internals, never bypass `dispatch` / `subscribe` to mutate state.

### L9 — Schemas at boundaries, not everywhere

`reg-app-schema` is registered for the paths that cross trust boundaries (incoming HTTP payloads, persisted state on restore, machine snapshot restores). Internal slices are not schema-fenced by default.

### L10 — No bead-ids in user-facing skill content

`SKILL.md` and the `reference/` / `patterns/` / `decision-trees/` leaves carry no `rf2-XXXX` references. Bead ids are workflow-tracking and would distract the agent using the skill. This `spec/` folder may reference beads; user-facing leaves do not.

### L11 — Findings stay local

Per Mike's standing memory rule "Findings is local-only" — design exploration happens in `ai/findings/`; never committed. This skill's commits contain only `skills/re-frame2/**`.

## 4. Audience and scope

### In scope

- ClojureScript application authors writing re-frame2 code.
- The `reg-*` family — `reg-event-{db,fx,ctx}`, `reg-sub`, `reg-fx`, `reg-cofx`, `reg-view`, `reg-machine`, `reg-route`, `reg-story`, `reg-app-schema`.
- The canonical patterns — RemoteData, Forms, Boot, WebSocket, NineStates, ManagedHTTP, AsyncEffect, LongRunningWork, StaleDetection.
- Frames, regions, tags, machine snapshots, the event-state cycle.
- Test-authoring (`reset-runtime-fixture`, `dispatch-sync`, `compute-sub`, `with-frame`).

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
├── reference/
│   ├── fundamentals/            (events, fx, cofx, subs, schemas, frames, event-state-cycle, project-structure)
│   ├── state-machines/          (reg-machine, regions, tags, invoke, cancellation)
│   ├── tooling/                 (stories, routing)
│   └── cross-cutting/           (testing, api-cheatsheet)
├── patterns/                    (one leaf per canonical pattern)
├── decision-trees/              (pick-a-pattern, slice-or-machine)
├── evals/                       (eval harness inputs)
└── spec/
    ├── design.md                (this file)
    ├── inputs.md                (canonical inputs)
    └── authoring-prompt.md      (one-shot reauthor prompt)
```

Each reference / pattern / decision-tree leaf is ≤250 lines, target ~150. A typical authoring session reads SKILL.md (~180) + one reference leaf (~150) + one pattern leaf (~150) ≈ ~480 LoC — well under any reasonable context budget.

## 6. Discovery surface (frontmatter `description`)

The `description` is "pushy" per Anthropic best practice — it lists every re-frame2 surface that should trigger discovery: `reg-event-db`, `reg-event-fx`, `reg-sub`, `reg-fx`, `reg-cofx`, `reg-view`, `reg-machine`, `reg-route`, `reg-story`, `reg-app-schema`, `dispatch`, `subscribe`, `app-db`, `frames`, `regions`, `tags`, `managed HTTP`, `RemoteData lifecycles`, plus the natural-language framing "writing tests for a re-frame2 app". The description also explicitly carves out the adjacent skills (`re-frame2-pair`, `re-frame2-setup`) so the AI routes correctly.

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
