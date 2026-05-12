# kickoff-prompt

The paste-ready prompt for kicking off a re-frame v1 → re-frame2 migration in a fresh Claude Code session.

## When to use this

The author has an existing re-frame v1.x project. They want to delegate the migration to a focused Claude Code session — keeping their own primary session free — and they're sitting at the project root.

Steps:

1. The author installs this skill in the project (project-level `.claude/skills/re-frame-migration/`) or globally (`~/.claude/skills/re-frame-migration/`).
2. The author opens a fresh Claude Code session in the project root.
3. The author pastes the prompt below verbatim. The session loads the `re-frame-migration` skill on its own (the description triggers it) and walks the six phases from `SKILL.md`.

---

## The prompt (paste this verbatim)

> *I'm migrating this ClojureScript codebase from re-frame v1.x to re-frame2. Walk the migration end-to-end per the `re-frame-migration` skill in this session.*
>
> *Phase 1 — Orient. Read the dep file (whichever exists: `deps.edn` / `project.clj` / `shadow-cljs.edn` / `bb.edn`), confirm we're actually on `re-frame/re-frame` today, and identify the substrate (assume Reagent unless the codebase shows otherwise). Then load [`MIGRATION.md`](../../../spec/MIGRATION.md) from the re-frame2 repo (clone it locally if you don't already have it; `https://github.com/day8/re-frame2` → [`MIGRATION.md`](../../../spec/MIGRATION.md)) and skim Part 1's rule index so you know what's available.*
>
> *Phase 2 — Apply M-0. Swap `re-frame/re-frame` for `day8/re-frame2` + the substrate-adapter coord (`day8/re-frame2-reagent` for Reagent). Then **stop**. Run a compile (`shadow-cljs compile <build>` or `clj -M:dev` or whatever the project's equivalent is). If it compiles, run the tests. Most codebases require no other changes — verify that before doing more work.*
>
> *Phase 3 — If anything broke. Sweep the failures against the M-rules in [`MIGRATION.md`](../../../spec/MIGRATION.md), in the order they're listed. Apply Type A (mechanical) rules without asking. For Type B (judgment-call) rules, identify every affected call site, explain the risk the rule documents, and **wait for my approval** before rewriting. Cite the rule id (`M-N`) for every change.*
>
> *Phase 4 — Re-verify. Re-compile, re-run tests, smoke-test the running app. Iterate until everything passes.*
>
> *Phase 5 — Do NOT apply opt-in modernisations (the `O-N` rules — `reg-view`, frames, schemas, state machines, ...) unless I explicitly ask. The goal is "v1 code compiles and runs on v2," not "v1 code rewritten in v2 style."*
>
> *Phase 6 — Report. Produce the migration report per [`MIGRATION.md`](../../../spec/MIGRATION.md) Part 2 §"Output format for your report": coord before/after, files modified, M-rules applied, items flagged for human review, anything unexpected. Keep it under 300 words.*
>
> *Standing rules for this migration:*
>
> *- The smallest correct diff. No stylistic refactoring. No renamings I didn't ask for.*
> *- Apply rules in their listed order — later rules sometimes depend on earlier ones.*
> *- JVM-side tests (anything in `.clj` test runners) are in scope. Don't silently CLJS-only the project.*
> *- If you hit a v1 surface that doesn't match any rule, **stop and ask**. Don't invent migration rules.*
> *- Don't bump any other deps as part of this migration — only re-frame.*
> *- Don't add per-feature artefacts (`-schemas`, `-machines`, `-routing`, `-http`, ...) unless the codebase actually uses that feature today.*
>
> *Begin with Phase 1.*

---

## Variations

Two common amendments the author may add:

**"Also modernise."** Append: *"After Phase 4 verifies clean, walk the `O-N` rules in [`MIGRATION.md`](../../../spec/MIGRATION.md) and apply the ones that match this codebase: rich registration metadata (O-1), `reg-view` adoption (O-2), Malli schemas at the boundaries (O-3), `:invoke-all` (O-15) if there's hand-rolled spawn-and-join, framework keyword consolidation (M-20 has an opt-in half). Same Type A / Type B rules — Type B asks first. Stop after each O-rule and let me decide whether to keep the change or revert."*

**"Migrate in feature-branch slices."** Append: *"Land each rule-group as its own commit with the M-rule ids in the message. Don't squash. I want the history to read as `M-0`, `M-1+M-5 (mechanical sweep)`, `M-3 (Type B, approved)`, `M-22 (Type B, approved)`, `M-21 mechanical pass`, etc. Each commit should leave the project compiling — break the migration into compilable bisects."*

---

## Why a kickoff prompt at all

The skill description triggers on a wide range of v1→v2 phrasings, but the **opening shape of the migration** is identical regardless of phrasing — the six phases above. Giving the author a paste-ready prompt:

- Locks the workflow shape the first time, so the session doesn't drift mid-migration.
- Makes the Type B "ask first" rule explicit upfront — the session can't silently rewrite timing-sensitive code.
- Frames the migration as "bump and verify first" — matching [`MIGRATION.md`](../../../spec/MIGRATION.md)'s headline expectation that most codebases need no further changes.
- Reuses the report format from [`MIGRATION.md`](../../../spec/MIGRATION.md) Part 2 so the report stays consistent across migrations.

The prompt is short (under 400 words) so the author doesn't lose anything by pasting it verbatim, but rigid enough that a fresh session executing it walks the migration the same way every time.
