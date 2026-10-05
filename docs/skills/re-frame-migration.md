# re-frame-migration (v1 → v2)

Migrates an existing re-frame v1.x ClojureScript codebase to re-frame2. Swaps the dependency, applies mechanical (Type A) rewrites automatically, and flags judgment-call (Type B) sites for you to decide.

## Kickoff

In the root of your v1 project, give the agent a migration request with the paths and revision filled in:

> Using re-frame-migration, migrate this app to re-frame2. Read the rules from `<checkout>` pinned at `<commit-or-tag>`. Use `<full-git-sha>` for the re-frame2 dependencies. Apply Type A rules and collect Type B decisions for me. Run the project checks and report any verification still pending.

A longer prompt is available in [`references/kickoff-prompt.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/kickoff-prompt.md).

The session loads the skill and walks the whole workflow on its own, coming back to you at the Type B decisions. The prompt also names two common additions: *"also modernise"* (apply the opt-in modernisation rules, numbered `O-N`) and *"migrate in feature-branch slices"* (one commit per rule group).

Fill in two values; the skill stops and asks if either is missing:

- **A pinned local checkout of re-frame2** — a path, and the commit or tag it should be at. The skill reads the rules from that checkout, never from GitHub at runtime. Before reading, it runs three read-only `git` checks: that `HEAD` matches the pin, that `origin` names `day8/re-frame2`, and that the pinned commit has the current multi-artefact layout (`implementation/core/deps.edn`, `implementation/adapters`), which an older single-artefact commit fails.
- **The re-frame2 version to land on** — used verbatim in every dependency coordinate; the skill never picks "latest". Until re-frame2 is on Clojars, give it a route instead: a `:git/sha` of a pushed commit, or a local checkout path for `:local/root`, which resolves only on your machine, so CI needs the `:git/sha`. The migration then runs exactly as it would against a release; the skill stops only when it has no route at all.

## What it does

The skill takes a v1 project to re-frame2 with the smallest correct diff — no stylistic refactoring, no renames you didn't ask for.

It plans before it edits anything. It inventories your v1 add-on libraries and app features, then checks that your component libraries can run on React 19 and Reagent 2, and surfaces a library that can't as an explicit go/no-go rather than a mid-compile surprise. Then it swaps the dependency and compiles; a large part of a codebase runs after that alone. It applies the planned sweep whether or not the compile passed, because the failures re-frame2 moves to run time never show up in a compile, then verifies the app boots and writes a short report. The phases are listed in full in [`SKILL.md` §The migration workflow](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/SKILL.md#the-migration-workflow).

The rules come from the breaking-change list in [`migration/from-re-frame-v1/README.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md); the skill never duplicates or invents one. **Type A** rewrites (mechanical, unambiguous, observably identical) are applied without asking. **Type B** rewrites (timing-sensitive, dynamic call sites, behaviour that changes at the edges) are flagged with the rule cited, and nothing is rewritten until you decide. JVM-side tests are migrated too; re-frame2 keeps `re-frame.interop` and still runs tests on the JVM.

One rule has a tool of its own. M-73 folds `reg-event-db`, `reg-event-fx` and `reg-event-ctx` into the single `reg-event`, and the skill applies it with a codemod: an ordinary Clojure CLI command, run from your project's root, that pulls the codemod as a git dependency, so the codemod itself needs no checkout. The pinned checkout supplied at kickoff is still required to read the migration rules.

A large codebase — roughly 30 or more source files, with rule families colliding inside the same files — can have the sweep split into waves, each file owned by one pass; see [`references/orchestrating-a-large-migration.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/orchestrating-a-large-migration.md).

## When to reach for it

Use it when you have a re-frame v1 codebase to move to re-frame2, when you want to know what would break before you start, or when a build fails on a v1-only name after the dependency bump.

For related work:

- Rewriting Reagent views into Fresco once you are on re-frame2 (optional) → [reagent-fresco-migration](reagent-fresco-migration.md).
- Writing new code once the migration report is signed off → [re-frame2](re-frame2.md).

## What completion includes

Expect the migration report to record the rewrites, Type B decisions, dependency coordinates and exact check results. Completion includes a clean boot smoke-test, the optimized/release compile, and CI green on a clean checkout. Local `:local/root` dependencies can help during development, but the final dependencies must resolve on that clean runner. Tests must pass or have only the same failures recorded before migration.

If the app used re-frame-10x, the report also accounts for its replacement with Xray: removing the old preload is only the first half; the Xray panel must be wired and verified after the app boots.

## When it stops

- **The React 19 check says NO-GO.** A component library with neither a declared React-19 release nor a passing runtime check stops the migration before any dependency edit, with four options for you: wait for a release, replace the library, vendor or patch it, or force React 19 and verify at runtime. Toolchain and CI-browser bumps are never NO-GOs; they go into the dependency swap. A CI-browser bump clears CI only: each other runtime host the app supports, such as a desktop, embedded or webview host, gets its own acceptance check, and one that cannot run yet is held with its remaining gate rather than stopping the migration.
- **A call site matches no rule.** The skill stops and asks rather than inventing a rewrite. A genuinely ambiguous rule becomes an upstream issue against `day8/re-frame2`: it searches for an existing one, shows the draft and waits for your explicit approval before filing.
- **Type B sites.** The judgment calls are collected and presented as one batch at the end of the sweep, and nothing in that batch is rewritten until you decide.
- **No runtime to drive.** "Compiles" is not done — done is a clean boot smoke-test. The skill runs compile and tests itself, and drives the smoke-test through a connected `re-frame2-pair` MCP server or shadow-cljs nREPL. Without one, it hands you a short checklist ([`references/runtime-smoke-test.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/runtime-smoke-test.md)) and reports the smoke-test as pending rather than the migration as complete.

The failures that v2 moves from compile time to run time — the ones a clean compile does not catch — are listed with their symptoms in [`references/silent-runtime-failures.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/silent-runtime-failures.md).

The [skill contract](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/SKILL.md) contains the full workflow and links to its reference notes.
