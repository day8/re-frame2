# re-frame2 — Authoring Prompt

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

A self-contained prompt that re-authors the `re-frame2` skill from this `spec/` folder alone. Drop into a fresh Claude Code session in the re-frame2 repo root.

## The prompt

> *I'm re-authoring the `re-frame2` skill at `skills/re-frame2/`. The skill teaches an AI to write working re-frame2 ClojureScript application code while spending as little context as possible. It is **authoring-only** — writing new code on the CLJS reference. Adjacent skills handle setup (`re-frame2-setup`), v1→v2 migration (`re-frame-migration`), live-app inspection (`re-frame2-pair`), and porting re-frame2 itself (`re-frame2-implementor`).*
>
> *Read these first (in this order):*
>
> *1. `skills/re-frame2/spec/design.md` — the locked design decisions (L1 through L11). Pillars 1-4 in §2 are non-negotiable. Q14 lock applies (NO verification module).*
> *2. `skills/re-frame2/spec/inputs.md` — the canonical inputs the skill leans on (`implementation/**`, `examples/reagent/**`, `spec/**` for design rationale).*
> *3. `implementation/core/src/re_frame/core.cljc` + `frame.cljc` + `fx.cljc` + `events.cljc` + `subs.cljc` + `test_support.cljc` — the surfaces the skill teaches. Every code snippet in a leaf is verified against these.*
> *4. `examples/reagent/{counter,login,boot,nine_states,managed_http_counter}/` — the worked examples. The pattern leaves match these shapes.*
> *5. `skills/re-frame-migration/SKILL.md` + `skills/re-frame2-implementor/SKILL.md` — the voice / density / load-bearing-rules style to mirror. SKILL.md cardinal-rules framing comes from these.*
> *6. `spec/000-Vision.md` + `spec/Conventions.md` — the AI-first design principles and naming conventions.*
>
> *Then write the skill at `skills/re-frame2/` with this exact file structure:*
>
> ```
> skills/re-frame2/
> ├── SKILL.md                     (~180 lines; the router + cardinal rules)
> ├── README.md                    (human-facing intro)
> ├── LICENSE                      (MIT)
> ├── package.json                 (npm metadata; mirror re-frame-migration pattern)
> ├── examples-map.md              (pattern → worked-example table)
> ├── references/
> │   ├── fundamentals/
> │   │   ├── events.md            (reg-event-{db,fx,ctx})
> │   │   ├── fx.md                (reg-fx, :fx vector shape)
> │   │   ├── cofx.md              (reg-cofx, inject-cofx)
> │   │   ├── subs.md              (reg-sub, layered subs, dynamic args, machine subs)
> │   │   ├── schemas.md           (reg-app-schema, boundary validation)
> │   │   ├── frames.md            (frame ids, default frame, per-frame config)
> │   │   ├── event-state-cycle.md (the 12-step walk)
> │   │   └── project-structure.md (source-tree conventions)
> │   ├── state-machines/
> │   │   ├── reg-machine.md       (states, initial, guards, actions)
> │   │   ├── regions.md           (single-region + :type :parallel)
> │   │   ├── tags.md              (state tags, machine-has-tag?)
> │   │   ├── invoke.md            (:spawn, :spawn-all, child-machine result)
> │   │   └── cancellation.md      (destroy cascade, cleanup contract)
> │   ├── tooling/
> │   │   ├── stories.md           (reg-story, story frames)
> │   │   └── routing.md           (reg-route, :can-leave?, navigation)
> │   └── cross-cutting/
> │       ├── testing.md           (reset-runtime-fixture-factory, dispatch-sync, compute-sub)
> │       └── api-cheatsheet.md    (one-page reg-* signature index)
> ├── patterns/
> │   ├── remote-data.md
> │   ├── forms.md
> │   ├── boot.md
> │   ├── websocket.md
> │   ├── nine-states.md
> │   ├── managed-http.md
> │   ├── async-effect.md
> │   ├── long-running-work.md
> │   └── stale-detection.md
> ├── decision-trees/
> │   ├── pick-a-pattern.md        ("I want to build X — which pattern?")
> │   └── slice-or-machine.md      ("Should this be a slice, region, or top-level machine?")
> └── spec/
>     ├── design.md
>     ├── inputs.md
>     └── authoring-prompt.md
> ```
>
> *Every reference / pattern / decision-tree leaf is ≤250 lines (target ~150). SKILL.md is ~180 lines (under Anthropic's 500-line ceiling). All leaves are one level deep — no SKILL → A → B chains.*
>
> *Cardinal rules to bake in (these go in SKILL.md):*
>
> *1. **Implementation is ground truth.** When `spec/` and `implementation/**` disagree, the implementation wins. Recipes are verified against `implementation/**` + `examples/reagent/**`.*
> *2. **Recipes over explanations.** The AI reaches for a canonical shape; doesn't derive from first principles.*
> *3. **Distinguish orchestration from state.** Machines when "what can happen next" depends on mode; slices when state is a field.*
> *4. **Schemas at boundaries, not everywhere.** `reg-app-schema` for trust-boundary paths.*
> *5. **Examples in `examples/reagent/**` are canonical.** When a pattern has a worked example, match its shape.*
> *6. **Frames before globals.** Code talks to a frame; never bypasses `dispatch` / `subscribe`.*
> *7. **Reserved namespaces are reserved.** `:rf/*` is framework-owned.*
> *8. **`reg-*` macros over `register-*` functions.** Macros capture source-coords.*
> *9. **Pillar 4 — assume training knowledge.** Teach only the re-frame2-specific binding.*
>
> *Locks to preserve verbatim:*
>
> *- **L3 — NO verification module.** No `references/verify.md`; no "verify before claiming done" hard rule. The author runs tests.*
> *- **L10 — No bead-ids in user-facing skill content.** `SKILL.md` + `references/` + `patterns/` + `decision-trees/` carry no `rf2-XXXX` references.*
> *- **L11 — Findings stay local.** Don't commit `ai/` or `findings/` content.*
>
> *Frontmatter — the `description` is "pushy" per Anthropic best practice. List every re-frame2 surface that should trigger discovery: `reg-event-db`, `reg-event-fx`, `reg-sub`, `reg-fx`, `reg-cofx`, `reg-view`, `reg-machine`, `reg-route`, `reg-story`, `reg-app-schema`, `dispatch`, `subscribe`, `app-db`, frames, regions, tags, managed HTTP, RemoteData lifecycles. Plus natural-language phrases: "writing tests for a re-frame2 app", "state-machine-for-HTTP shapes". Carve out the adjacent skills (`re-frame2-pair`, `re-frame2-setup`) explicitly so the AI routes correctly.*
>
> *Voice: tight, declarative, recipe-shaped. Use tables for routing; use code blocks for canonical shapes. Cite `implementation/<file>:<line>` in leaves where a surface claim might surprise an AI working from training memory.*
>
> *Don't:*
>
> *- Don't quote spec text for API surface — that's L1.*
> *- Don't write `*.md` documentation outside `skills/re-frame2/`.*
> *- Don't commit `ai/` or `findings/` content.*
> *- Don't claim AI authorship anywhere — commits and PR title/body read as Mike Thompson's work.*
> *- Don't write a verification leaf or a verify-before-done hard rule.*
> *- Don't include bead-ids in user-facing leaves — only in `spec/` (workflow tracking).*
>
> *Open the PR with title `feat(skills): re-frame2 — authoring-only skill for writing re-frame2 CLJS code`. PR body lists: the skill structure, the file LoC table, the locks applied, the relationship to the adjacent skills (setup / migration / re-frame2-pair / implementor).*

## Notes on the reauthoring contract

- The prompt above is a one-shot — feed it to a fresh session, it produces the skill.
- The prompt assumes the session has read access to the repo. It does not assume any out-of-repo context.
- The prompt does **not** ask the session to verify the resulting skill — Mike reads the PR and comments.
- If `implementation/**` has changed significantly between authoring passes, the leaves' code snippets need re-verification. A reauthoring session walks the implementation afresh.

## When to re-author

- A new registry kind ships in re-frame2 (e.g. a new `reg-X` surface) → the existing leaves' organisation may be wrong; rebuild the routing.
- A new canonical pattern is named in `spec/Pattern-*.md` → add to `patterns/` and `decision-trees/pick-a-pattern.md`.
- The Q14 lock or any of L1-L11 changes → the design itself changes; this `spec/` folder needs updating first, then the skill.
- Anthropic skill conventions change materially → reauthor against the new conventions.

Otherwise, edit the existing leaves directly; reauthoring from scratch is for major-version updates.
