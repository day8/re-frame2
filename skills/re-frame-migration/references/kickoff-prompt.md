# kickoff-prompt

The paste-ready prompt for kicking off a re-frame v1 → re-frame2 migration in a fresh Claude Code session.

## When to use this

The author has an existing re-frame v1.x project. They want to delegate the migration to a focused Claude Code session — keeping their own primary session free — and they're sitting at the project root.

Steps:

1. The author installs this skill in the project (project-level `.claude/skills/re-frame-migration/`) or globally (`~/.claude/skills/re-frame-migration/`).
2. The author opens a fresh Claude Code session in the project root.
3. The author pastes the prompt below verbatim. The session loads the `re-frame-migration` skill on its own (the description triggers it) and walks the two pre-flight phases plus the six phases from `SKILL.md`.

---

## The prompt (paste this verbatim)

> *I'm migrating this ClojureScript codebase from re-frame v1.x to re-frame2. Walk the migration end-to-end per the `re-frame-migration` skill in this session.*
>
> *Migration-corpus pin (load-bearing). The re-frame2 corpus is at `<path-to-re-frame2>` (clone https://github.com/day8/re-frame2 locally and check out the pinned commit/tag below if you don't have it). Pinned commit/tag: `<sha-or-tag>`. Before reading `<path-to-re-frame2>/migration/from-re-frame-v1/README.md`, verify `git -C <path-to-re-frame2> rev-parse HEAD` matches the pin and that `git -C <path-to-re-frame2> remote get-url origin` is `https://github.com/day8/re-frame2`. These two are read-only provenance checks the session runs itself (they inspect the corpus checkout, they don't execute project code — distinct from the build/test/smoke commands you run); both are in the skill's allow-list. Treat that local pinned migration corpus as the contract — do not fetch the doc from GitHub at runtime.*
>
> *Target v2 version (load-bearing). The v2 release I want to land on is `<v2-version>`. Use that exact string in every dep coord. Do not auto-select "latest from GitHub"; if `<v2-version>` is unset, stop and ask me.*
>
> *Walk the skill's full workflow IN ORDER — the two pre-flight phases (Phase 0a INVENTORY-AND-PLAN, then Phase 0b the React-19 / Reagent-2 floor gate), then Phases 1–6 — exactly as `SKILL.md` and its reference leaves define each. You just loaded the skill; it is the single source for every phase's mechanics, so don't re-derive them from this prompt. The anchors I'm asserting up front:*
>
> *- Phase 0a (the add-on / feature inventory + written plan) and Phase 0b (the floor gate) both run BEFORE any dep edit or compile. Phase 0b is a go/no-go BLOCKER: a component library with no React-19 release — declared, or verified empirically by exercising its real screens — is a NO-GO surfaced as a decision, never a mid-compile surprise. GO carries the approved React/Reagent + toolchain + CI-browser bumps into the M-0 pass.*
> *- Apply Type A (mechanical) rules without asking; for Type B (judgment) rules, identify every affected site, explain the risk, and WAIT for my approval before rewriting. Cite the `M-N` id for every change.*
> *- "Compiles" is NOT the done-bar — v2 moves a large class of failures to runtime. Done = compile + tests + the boot smoke-test (live `app-db` / machine-snapshot reads + a boot-trace scan) all clean AND every Type B decision resolved.*
> *- You NEVER run build / test / smoke / install commands — print them and I run them (cardinal rule 5). The two read-only corpus-provenance git checks above are the only commands you run yourself.*
> *- If this project uses re-frame-10x, the Xray swap is a REQUIRED two-stage deliverable whose done-state is the app ON Xray (per the skill's `xray-replaces-10x` reference); no 10x ⇒ add no devtools I never had.*
> *- Phase 5 opt-in `O-N` modernisations stay OFF unless I explicitly ask — the goal is "v1 code compiles and runs on v2," not "rewritten in v2 style." Phase 6 is a <300-word report per [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md) Part 2 §"Output format for your report".*
>
> *Standing rules for this migration:*
>
> *- The smallest correct diff. No stylistic refactoring. No renamings I didn't ask for.*
> *- Apply rules in their listed order — later rules sometimes depend on earlier ones.*
> *- JVM-side tests (anything in `.clj` test runners) are in scope. Don't silently CLJS-only the project.*
> *- If you hit a v1 surface that doesn't match any rule, **stop and ask**. Don't invent migration rules.*
> *- Don't bump UNRELATED deps as part of this migration. The migration-forced bumps are exempt and expected: the Phase-0b GO-state floor/toolchain edits (React → 19, Reagent → 2.x if I pin it directly, the component libraries the floor gate approved, shadow-cljs per Check 4, the CI test-runner browser pin per Check 5, and any explicit ClojureScript pin) plus the Xray npm peer-deps (`@xyflow/react`, `elkjs`) when the 10x→Xray swap fires. What stays banned is an opportunistic "while I'm in here" bump of a dep the migration doesn't force. List every non-re-frame dependency you changed, and which gate justified it, in the Phase-6 report.*
> *- Don't add per-feature artefacts (`-schemas`, `-machines`, `-routing`, `-http`, ...) unless the codebase actually uses that feature today.*
>
> *Begin with Phase 0a — the inventory-and-plan pre-flight (NOT Phase 1). Phase 0a then Phase 0b (the floor gate) run before any dep edit or compile; only after Phase 0b returns GO do you reach Phase 1 (orient) and Phase 2 (the M-0 bump).*

---

## Variations

Two common amendments the author may add:

**"Also modernise."** Append: *"After Phase 4 verifies clean, walk the `O-N` rules in [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md) and apply the ones that match this codebase: rich registration metadata (O-1), `reg-view` adoption (O-2), Malli schemas at the boundaries (O-3), `:spawn-all` (O-15) if there's hand-rolled spawn-and-join, framework keyword consolidation (M-20 has an opt-in half). Same Type A / Type B rules — Type B asks first. Stop after each O-rule and let me decide whether to keep the change or revert."*

**"Migrate in feature-branch slices."** Append: *"Land each rule-group as its own commit with the M-rule ids in the message. Don't squash. I want the history to read as `M-0`, `M-1+M-5 (mechanical sweep)`, `M-3 (Type B, approved)`, `M-22 (Type B, approved)`, `M-21 mechanical pass`, etc. Each commit should leave the project compiling — break the migration into compilable bisects."*

---

## Why a kickoff prompt at all

The skill description triggers on a wide range of v1→v2 phrasings, but the **opening shape of the migration** is identical regardless of phrasing — the two pre-flight phases plus the six phases above. Giving the author a paste-ready prompt:

- Locks the workflow shape the first time, so the session doesn't drift mid-migration.
- Puts the inventory-and-plan first, so the full set of v2-broken add-on/app surfaces is known before the first compile — collapsing the "march the wall" whack-a-mole into one planned sweep.
- Puts the React-19 / Reagent-2 floor gate next, so the blocking component-library case is a go/no-go decision *before* any dep edit — not a surprise mid-compile.
- Makes the Type B "ask first" rule explicit upfront — the session can't silently rewrite timing-sensitive code.
- Frames the migration as "bump and verify first" — matching [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md)'s headline expectation that most codebases need no further changes.
- Reuses the report format from [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md) Part 2 so the report stays consistent across migrations.

The prompt is compact enough to paste verbatim without losing anything, but rigid enough that a fresh session executing it walks the migration the same way every time.
