# re-frame-migration — Design

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame-migration` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The design rationale and locked decisions for the `re-frame-migration` skill. A future agent could re-author this skill from this folder alone.

## 1. Goal

Help a programmer migrate an existing re-frame v1.x ClojureScript codebase to re-frame2 with the smallest correct diff. The skill is **guidance + workflow** layered on top of `migration/from-re-frame-v1/README.md` (the authoritative breaking-change list). The skill does not duplicate MIGRATION.md; it structures the migration around it.

The skill's success criterion: the author runs the migration, ends up on `day8/re-frame2`, the project compiles and tests pass, and every Type B decision the author had to make is documented in the final report.

## 2. Pillars (locked, derived from `ai/findings/re-frame2-skill-design-v2.md`)

The same four pillars as the `re-frame2` skill, adapted to the migration domain:

1. **Correctness** — recipes over explanations. The agent applies the M-rule it cites; it doesn't synthesise novel rewrites. **Q14 lock applies: NO verification module.** The agent doesn't run the author's tests; running tests is general software practice, not migration-specific.
2. **Idiomaticness** — verified against `migration/from-re-frame-v1/README.md` (which is itself verified against `implementation/**`). The skill is downstream of MIGRATION.md; if MIGRATION.md is authoritative, the skill is correct by construction.
3. **Context economy** — SKILL.md is a router; leaves are loaded on demand. The leaves point at MIGRATION.md for the per-rule full text; they don't quote it.
4. **Assume training knowledge** — the agent knows what re-frame is, what a Maven coord is, what a Reagent root is, what a state machine is. The skill teaches the **v1→v2 binding**: which v1 surface becomes which v2 surface, when the rewrite is automatic vs. when it needs human judgment.

## 3. Locked decisions

These are not up for re-litigation. A future authoring pass MUST preserve these unless explicitly unlocked by Mike.

### L1 — `migration/from-re-frame-v1/README.md` is the source of truth

The skill does **not** duplicate the rule content. Leaves point at MIGRATION.md by rule id (`M-N` / `O-N`). When the author asks "is `X` covered?", the leaf says which rule; the agent reads the full text from MIGRATION.md.

**Why**: MIGRATION.md is maintained as part of the spec corpus. Drift between the skill and the spec would manifest as confusing dual sources. The skill is consumer; the spec is producer.

### L2 — Type A / Type B distinction is operational, not advisory

Type A is applied automatically; Type B halts and asks. This dichotomy comes from MIGRATION.md and the skill enforces it. The agent never silently applies a Type B rewrite. Pre-authorised batch decisions ("apply X to every Y") are allowed but get banked in the report so the author can audit.

### L3 — Q14 — NO verification module

Per `ai/findings/re-frame2-skill-design-v2.md` §Q14: the skill does not teach the agent to verify its own output. No `references/verify.md`, no "verification mandatory before done" hard rule. The agent applies the rules; the author runs the tests. This matches the `re-frame2` skill's lock — consistent across the skill family.

**Why**: running tests is general software practice. Pillar 4 says don't teach what the AI already knows.

### L4 — No verification claim of "done"; the author confirms

The "Done checklist" in SKILL.md lists the conditions for completion. The skill does not assert completion; it presents the checklist and the author confirms. This is consistent with L3.

### L5 — Migration prompt = `migration/from-re-frame-v1/README.md` Part 2

Mike's bead surfaced an open question: "Where does the `setup and obviously the migration prompt` material actually live?" Answer: **`migration/from-re-frame-v1/README.md` itself.** Part 2 of that doc is "Execution procedure ... written in second person to an AI agent performing the migration." It already is the migration prompt — the skill consumes it directly, and the paste-ready kickoff prompt in `references/kickoff-prompt.md` is a thin wrapper that loads the skill and references MIGRATION.md.

### L6 — JVM interop is in scope

re-frame2 preserves `re-frame.interop`. The skill calls out JVM-side test runs in the cardinal rules and does not silently CLJS-only the project. M-1 / M-25 (test-support rename) etc. apply to `.clj` files the same way they apply to `.cljs`.

### L7 — Reagent v2 is the default substrate target

When the skill picks an adapter to recommend (M-0 substrate-adapter slot), the default is `day8/re-frame2-reagent` unless the codebase shows it has already migrated to UIx or Helix. Substrate migration (O-13 / O-14) is **never** part of a v1→v2 migration; it's a separate concern.

### L8 — Single-import contract for new / migrated code

User code uses `(:require [re-frame.core :as rf])`. The skill rewrites every private-namespace require (M-1, M-23, M-38) to land at this contract. Per-feature artefacts (`-machines`, `-routing`, `-flows`, `-http`, `-ssr`, `-schemas`, `-epoch`) require the per-feature namespace (`re-frame.machines`, etc.) **in addition** for hook registration — the contract there is "core single-import for the public API; per-feature requires for hook installation."

### L9 — Smallest correct diff

The skill applies what's required and nothing more. Stylistic refactoring, opt-in modernisations (O-rules), substrate moves — these are out of scope unless the author explicitly opts in. The smallest-diff rule is a cardinal rule in SKILL.md.

### L10 — Findings stay local

Per Mike's standing memory rule "Findings is local-only" — any exploration of the design happens in `ai/findings/`; never committed. This skill's commit contains only `skills/re-frame-migration/**`.

## 4. Audience and scope

### In scope

- Re-frame v1.x CLJS codebases moving to re-frame2.
- JVM-side test infrastructure (`.clj` runners, `re-frame.test` → `re-frame.test-support`).
- Both Reagent v2 (default) and pre-existing UIx / Helix codebases (the substrate is detected, not migrated).
- The full M-rule and O-rule surfaces in `migration/from-re-frame-v1/README.md`.

### Out of scope

- Migrating to a different substrate (Reagent → UIx is O-13; never part of v1→v2).
- Writing new application code on v2 — that's the `re-frame2` skill.
- Live-runtime inspection of the running v2 app — that's `re-frame2-pair`.
- Greenfield project bootstrap — that's `re-frame2-setup`.
- Authoring opt-in modernisations except when the author explicitly asks.
- Editing `migration/from-re-frame-v1/README.md` — gaps file GitHub issues against `day8/re-frame2`; the skill never patches MIGRATION.md inline. (`bd` is monorepo-internal and never invoked from a published skill — see `skills/README.md` baseline.)

## 5. File structure (locked)

```
skills/re-frame-migration/
├── SKILL.md                       (router; ~120 lines)
├── README.md                      (human-facing intro; ~80 lines)
├── LICENSE                        (MIT, mirrors re-frame2-setup)
├── package.json                   (npm metadata for distribution)
├── .claude-plugin/plugin.json     (Claude Code plugin metadata)
├── references/
│   ├── kickoff-prompt.md           (~70 lines)
│   ├── setup.md                    (~170 lines)
│   ├── causa-replaces-10x.md       (~240 lines; devtools swap — re-frame-10x → Causa)
│   ├── breaking-changes.md         (~150 lines)
│   ├── sequencing.md               (~150 lines)
│   ├── auto-call-site-rewrites.md  (~250 lines; Type A — ns / effect-map / dispatch)
│   ├── auto-cross-cutting.md       (~290 lines; Type A — keywords / interceptors / views / init / artefacts)
│   ├── guided-handlers-state.md    (~160 lines; Type B — handler / view / db-seeding / error-handler)
│   ├── guided-interceptors-subs.md (~155 lines; Type B — interceptor / sub / payload / observer)
│   ├── output-format.md            (~115 lines)
│   └── error-events.md             (~95 lines; pointer to Spec 009's error-event catalogue)
└── spec/
    ├── design.md                  (this file)
    ├── inputs.md                  (the canonical inputs the skill leans on)
    └── authoring-prompt.md        (one-shot reauthor prompt)
```

**Totals**: SKILL.md (~120) + 11 reference leaves (~1,850) + 3 spec files (~330) ≈ ~2,300 LoC across 15 markdown files. Most leaves sit under the 250-line soft ceiling; the two Type A catalogues (`auto-call-site-rewrites.md` ~250, `auto-cross-cutting.md` ~290) run to the cap or just over because their shape-catalogue content resists further splitting. SKILL.md is well under the 500-line Anthropic guideline.

**Type A / Type B split into two leaves each.** The 365L `automated-transforms.md` and 300L `guided-checklist.md` originals violated the 250-line soft ceiling. They've been split along natural cluster boundaries: Type A divides into per-call-site rewrites (ns / effect-map / dispatch shapes) and cross-cutting (keyword renames / interceptor cleanup / views / init / artefact adds); Type B divides into handler-state-shaped (M-3, M-5, M-10, M-11, M-12, M-13, M-14, M-15) and interceptor-sub-payload-shaped (M-17, M-18, M-19, M-21, M-23, M-26). All four leaves remain one level deep from SKILL.md — no SKILL → A → B chains.

## 6. Why the leaf split

The eleven reference leaves are sized to load on demand without spending context budget on irrelevant detail. Typical migration session loads:

- **Phase 2 (bump-only success)**: `setup.md` + `output-format.md`. ~285 LoC.
- **Phase 3 (sweep with Type A only)**: `auto-call-site-rewrites.md` + `auto-cross-cutting.md` + `breaking-changes.md` + `sequencing.md` + `output-format.md`. ~960 LoC.
- **Phase 3 (sweep with Type A + Type B)**: add the relevant `guided-*.md` (typically one; both for cross-surface migrations). ~1,120–1,280 LoC.
- **Full migration (rare)**: all eleven reference leaves. ~1,850 LoC.

Even the worst case is well under any reasonable context budget; the median case is ~25% of the total skill content. The Type A split lets a Phase-3 sweep that only trips per-call-site rules load `auto-call-site-rewrites.md` (~250L) without dragging in the cross-cutting catalogue (and vice versa). Likewise the Type B split lets a sub-only migration load just `guided-interceptors-subs.md`.

## 7. Anti-patterns the skill explicitly resists

- **Auto-applying Type B rewrites.** Cardinal rule #2 in SKILL.md.
- **Adding per-feature artefacts defensively.** Cardinal rule + setup.md's "pay-as-you-go" framing.
- **Migrating to `reg-view` as part of the required migration.** O-2 is opt-in.
- **Bumping other deps along with the re-frame coord.** Smallest-diff rule.
- **Inventing new migration rules.** Cardinal rule + setup.md "stop and ask" framing.
- **Silently CLJS-only-ing a project that had JVM tests.** Cardinal rule L6 + M-25 covers test-runner rename.

## 8. Discovery surface (frontmatter `description`)

The `description` field is the primary trigger. It is deliberately verbose and lists every v1 surface that should trigger discovery:

> `re-frame.db`, `dispatch-with`, `reg-global-interceptor`, `reg-sub-raw`, `^:flush-dom`, `re-frame.alpha`, `re-frame-test`, the old `:dispatch`/`:dispatch-n` effect-map shape

Plus the natural-language phrases: "migrate to re-frame2", "upgrade re-frame", "v1 to v2", "what breaks under re-frame2".

The description is "pushy" per Anthropic's best-practices guidance (use this skill whenever the user mentions ...).

## 9. Where this design diverges from `re-frame2`

- **No patterns/ directory.** The migration is a workflow, not a set of authoring recipes.
- **No decision-trees/ directory.** The single decision tree is "is this Type A or Type B?" and lives in the leaves themselves.
- **No examples-map.md.** Examples are inside `output-format.md` (filled-in reports).
- **The kickoff prompt is unique to this skill** — the `re-frame2` skill assumes the agent is already engaged in authoring; the migration skill needs to bootstrap a focused session.

## 10. Open questions (deferred to Mike)

These remain open at authoring time:

### OQ1 — Should the skill ship a runnable `migrate.bb` for mechanical transforms?

**Status**: deferred. The skill ships as pure guidance for v0.1. If post-launch experience shows the Type A leaves (`auto-call-site-rewrites.md` / `auto-cross-cutting.md`) are being applied identically across many migrations, a `migrate.bb` script would be a logical next bead — driven by real call-site data.

### OQ2 — Should there be a "before you start" diagnostic that profiles the codebase?

**Status**: deferred. Useful as v0.2 — a profiler that grep-counts every M-rule trigger surface and reports `<N> Type A sites + <M> Type B sites` so the author can size the migration. The shape would be a `references/profile.md` leaf and a corresponding script. Not blocking v0.1 because the agent can do the profiling inline during Phase 1 (Orient) without dedicated tooling.

### OQ3 — Where does the "migration prompt" material live?

**Resolved (L5 above)**: it's `migration/from-re-frame-v1/README.md` Part 2. The kickoff prompt in `references/kickoff-prompt.md` wraps it.
