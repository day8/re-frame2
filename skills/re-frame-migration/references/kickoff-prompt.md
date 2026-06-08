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
> *Migration-corpus pin (load-bearing). The re-frame2 corpus is at `<path-to-re-frame2>` (clone https://github.com/day8/re-frame2 locally and check out the pinned commit/tag below if you don't have it). Pinned commit/tag: `<sha-or-tag>`. Before reading `<path-to-re-frame2>/migration/from-re-frame-v1/README.md`, verify `git -C <path-to-re-frame2> rev-parse HEAD` matches the pin and that `git -C <path-to-re-frame2> remote get-url origin` is `https://github.com/day8/re-frame2`. Treat that local pinned migration corpus as the contract — do not fetch the doc from GitHub at runtime.*
>
> *Target v2 version (load-bearing). The v2 release I want to land on is `<v2-version>`. Use that exact string in every dep coord. Do not auto-select "latest from GitHub"; if `<v2-version>` is unset, stop and ask me.*
>
> *Phase 0a — Pre-flight: INVENTORY-AND-PLAN. Before everything else — before any dep edit, before any compile, before the floor gate — produce a written inventory + plan. (1) Inventory every v1 re-frame add-on library on the FULL resolved dep tree (run `clojure -Stree` / `lein deps :tree` / `npx shadow-cljs classpath` under each build alias; I run it, you read the output) — including transitives, git/source deps, and vendored code, not just the top-level dep file — and inventory the v1 re-frame features my own app uses. (2) Scan each add-on's SOURCE (the jar / git checkout / vendored files) for removed-or-moved v2 surfaces using the generic principles: off-contract `re-frame.*` requires outside `re-frame.core` + the artefact namespaces (M-1), the removed `re-frame.core/console` symbol (compile-blocker), `unwrap`→`unwrap-interceptor` (M-59), React-19/Reagent-2 coupling for view-shipping add-ons, and any transitive `re-frame/re-frame` coord (classpath collision). (3) Produce a per-item table: item → what breaks → governing M-N/O-N rule(s) → forced-vs-optional (does it compile unchanged?) → disposition (CONVERT / PATCH / DROP / REPLACE / UPSTREAM / FIX-IN-PLACE) → replacement target → recommended ordering (which removals unblock the compile). This collapses the "swap coord, compile, hit one broken namespace, fix, recompile, hit the next" whack-a-mole into one planned sweep — the breakages live in dependency source a compile reaches one namespace at a time. Do NOT add a bespoke migration path per library; the generic rules drive every add-on, recognised or not. See the `inventory-and-plan` reference. This phase feeds Phase 0b (the floor gate) and the M-rule sweep.*
>
> *Phase 0b — Pre-flight: the React-19 / Reagent-2 floor gate. Before touching ANY dep coord, clear this gate (re-frame2's adapters target React 19; the Reagent bridge runs on Reagent 2.x). Run five read-only checks and report each: (1) downstream React-lib compat audit — bucket each `package.json` dep with a `react`/`react-dom` peer dependency as React-19-ready / needs-bump / needs-replacement; (2) component/substrate-library check — confirm my UI component library supports React 19 (Reagent 2 if Reagent-based); if it has no *declared* React-19 release, STOP and surface the four-option go/no-go decision (wait for a release / replace it / vendor-or-patch it / force React 19 at the app level and verify the library EMPIRICALLY at runtime by exercising its real screens — an empirical pass is a valid GO, recorded with the screens tested); only a library with neither a declared release nor an empirical pass is a NO-GO BLOCKER, not a mid-compile surprise; (3) legacy-API scan — flag surviving `ReactDOM.render` / `react-dom` legacy call sites; (4) CLJS/shadow-cljs/Closure toolchain-skew check — compare my `shadow-cljs` + ClojureScript pins against the versions re-frame2 builds with (its `implementation/package.json` + `implementation/core/deps.edn`); if my shadow-cljs is older, flag a bump to the reference version — an older shadow-cljs breaks on the newer Closure compiler with a cryptic `java.lang.NoSuchFieldError` in `shadow.build.closure.JsInspector` at COMPILE that looks like a migration bug but is pure toolchain skew (not a NO-GO, just a known mechanical bump); (5) go/no-go — GO carries the React/Reagent bump + the shadow-cljs bump into Phase 2's M-0 pass, NO-GO stops until I resolve the blocker. I run any `npm install`/upgrade. Already on React 19 with a React-19-ready component library and a current toolchain? Fast pass — confirm and move on.*
>
> *Phase 1 — Orient. Read the dep file (whichever exists: `deps.edn` / `project.clj` / `shadow-cljs.edn` / `bb.edn`), confirm we're actually on `re-frame/re-frame` today, and identify the substrate (assume Reagent unless the codebase shows otherwise). Then load the pinned [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md) from the local checkout above and skim Part 1's rule index so you know what's available.*
>
> *Phase 2 — Apply M-0 (only if Phase 0 returned GO). Carry the Phase-0 React/Reagent bump (and any component-lib bumps) into this pass, then swap `re-frame/re-frame` for `day8/re-frame2` + the substrate-adapter coord (`day8/re-frame2-reagent` for Reagent), at `<v2-version>`. Then **stop**. Print the exact compile command for this project's build tool (`shadow-cljs compile <build>`, `clj -M:dev`, the npm script — whatever fits) and ask me to run it and paste the output. Do **not** run compile/test/smoke commands yourself — that's my loop, not yours (see cardinal rule 5). If the compile is clean, ask me to run the tests. Most codebases require no other changes — verify that before doing more work.*
>
> *Devtools (Xray replaces re-frame-10x — STANDARD step if I use 10x). Xray is the v2 devtools replacement for re-frame-10x; if this project's dev deps hold `day8.re-frame/re-frame-10x`, treat the swap as a standard, expected part of the migration whose done-state is the app **on Xray**, not just the dead preload removed. Rule: 10x present ⇒ swap to Xray (standard); no 10x ⇒ Xray optional, do NOT add devtools I never had. Two timings: (1) at M-0, neutralize the dead 10x preload now — drop the 10x coord + its `:preloads` entry so the post-M-0 compile gate is reachable; (2) post-M-40 (after `(rf/init!)` is wired and a clean reload is verified), mount Xray — add `day8/re-frame2-xray` + its preload + a `[data-rf-xray-host]` layout host. The post-M-40 timing is a sequencing detail (Xray's preload auto-opens after `init!`), not a reason to skip it. See the `xray-replaces-10x` reference.*
>
> *Phase 3 — Sweep. Sweep both (a) whatever the compile surfaced and (b) the SILENT-fail hits the Phase-0a app-source grep already found — the `{:db fresh}` boots (M-15b), top-level `:dispatch` keys (M-8), the signal-fn `reg-sub` (M-71), `^:flush-dom` (M-16), unary `reg-fx` handlers (M-51 — `(fn [args] …)` silently drops the real fx args on CLJS; rewrite to `(fn [_ args] …)`) compile clean and never appear as a compile error, so they're applied or triaged **regardless of whether the compile failed**, not skipped on a clean compile. Walk against the M-rules in [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md), in the order they're listed. Apply Type A (mechanical) rules without asking. For Type B (judgment-call) rules, identify every affected call site, explain the risk the rule documents, and **wait for my approval** before rewriting. Cite the rule id (`M-N`) for every change.*
>
> *Phase 4 — Re-verify. "Compiles" is NOT the done-bar — v2 moves a large class of failures to runtime. Print three things and I run them: (1) the re-compile command, (2) the re-test command, (3) the BOOT SMOKE-TEST loop — boot the app in a dev build and read the live frame's `app-db` + machine snapshots (`[:rf/runtime :machines :snapshots]`), deref the first-screen subs, dispatch one event per feature surface, and scan the boot trace for `:rf.error/*` / `:rf.warning/*` (cheapest tool: the `re-frame2-pair` MCP or a shadow-cljs nREPL). The migration is "all green" only when compile + tests + the smoke-test all come back clean AND every Type B decision is resolved — not when it merely compiles. Iterate until that holds. The skill never invokes build/test/smoke commands itself.*
>
> *Phase 5 — Do NOT apply opt-in modernisations (the `O-N` rules — `reg-view`, frames, schemas, state machines, ...) unless I explicitly ask. The goal is "v1 code compiles and runs on v2," not "v1 code rewritten in v2 style."*
>
> *Phase 6 — Report. Produce the migration report per [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md) Part 2 §"Output format for your report": coord before/after, files modified, M-rules applied, items flagged for human review, anything unexpected. Keep it under 300 words.*
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
