---
name: re-frame-migration
description: >
  Migrates an existing re-frame v1.x ClojureScript codebase to re-frame2.
  Swaps the artefact coord (re-frame/re-frame → day8/re-frame2 + a substrate
  adapter), applies the mechanical (Type A) rewrites from spec/MIGRATION.md
  automatically, and flags the judgment-call (Type B) call sites for human
  review before touching them. Use this skill whenever the user is migrating,
  upgrading, or porting a re-frame v1 project to re-frame2 — phrases like
  "migrate to re-frame2", "upgrade re-frame", "v1 to v2", "what breaks under
  re-frame2", or any prompt referencing a v1 surface (re-frame.db, dispatch-with,
  reg-global-interceptor, reg-sub-raw, ^:flush-dom, re-frame.alpha, re-frame-test,
  old top-level :dispatch/:dispatch-n effect-map keys). For greenfield
  bootstrap, use re-frame2-setup; for writing v2 application code, use the
  main re-frame2 skill; for live v2-app inspection, use re-frame-pair2.
---

# re-frame-migration

You are helping an author **migrate a ClojureScript codebase from re-frame v1.x to re-frame2**. The author has a project that depends on `re-frame/re-frame` today. When you are done, the project depends on `day8/re-frame2` + a substrate adapter, the mechanical (Type A) rewrites have been applied, and every judgment-call (Type B) call site has been surfaced to the author with the relevant rule cited.

This skill teaches the **migration workflow**. The authoritative list of what changes — the M-rules (required) and O-rules (opt-in modernisations) — lives in `spec/MIGRATION.md` in this repo. **Do not duplicate that content here.** Load `spec/MIGRATION.md` when you start the migration and treat it as the source of truth.

---

## When to use this skill

Use this skill when **any** of these are true:

- The author has an existing re-frame v1.x project and wants to move to re-frame2.
- The author mentions migrating, upgrading, porting, or v1→v2 in a re-frame context.
- The build fails after a dep bump and the cause looks v1-shaped (missing private namespaces, removed interceptors, `dispatch-with`, the alpha namespace, the old effect-map keys).
- The author asks "what breaks?" / "what changes?" / "is my v1 code compatible?" with re-frame2.

## When NOT to use this skill

Route elsewhere when:

| Author intent | Route to |
|---|---|
| Start a brand new re-frame2 project from scratch | `skills/re-frame2-setup/` |
| Write event handlers, subs, machines, schemas, views in a project that is already on v2 | `skills/re-frame2/` |
| Inspect / dispatch / debug / time-travel a *running* v2 app from the REPL | `skills/re-frame-pair2/` |
| Understand re-frame2's design rationale (EPs, principles, conventions) | `SKILL-REDIRECT.md` at repo root |

If the author has already finished migrating and is now writing new code, hand off to `re-frame2`. This skill exits when the project compiles, tests pass, and Type B items have been resolved.

---

## Cardinal rules

These hold across every step of the migration.

1. **`spec/MIGRATION.md` is the source of truth.** Every rewrite cites a rule id (`M-N` or `O-N`). If a call site doesn't match any rule, **stop and ask** — do not invent a rule.
2. **Type A is automatic, Type B is asked-first.** Type A rewrites are mechanical, unambiguous, and observably identical. Apply them without prompting. Type B rewrites depend on intent (timing-sensitive code, dynamic call sites, behaviour-changes-at-the-edge); identify every call site, explain the risk, and **wait for the author's decision** before rewriting.
3. **Smallest correct diff.** Do not refactor for style. Do not rename things the author didn't ask to rename. Do not add new features (frames, schemas, machines, `reg-view`) unless the author asked for the opt-in modernisations (the `O-N` rules).
4. **Apply rules in order.** Later rules sometimes depend on earlier ones (e.g. M-0 — the coord swap — is the precondition for everything else). Walk the rules top-to-bottom as listed in `spec/MIGRATION.md`; don't jump.
5. **JVM interop is in scope.** re-frame2 preserves `re-frame.interop` and JVM-side test runs. If the project has `.clj` test runners or JVM-side fixtures, migrate them alongside the CLJS code. Do not silently CLJS-only the project.
6. **Single-import contract for new code.** Application namespaces use `(:require [re-frame.core :as rf])`. Direct requires of `re-frame.db`, `re-frame.router`, `re-frame.subs`, `re-frame.events`, `re-frame.registrar`, or `re-frame.alpha` are out-of-contract and get rewritten or flagged per M-1 / M-23.
7. **If a rule looks ambiguous, file a bead — don't edit `spec/MIGRATION.md` inline.** This skill consumes that doc; it doesn't author it. Surface drift back to the maintainer.
8. **Do not invent migration rules.** The 40+ M- and O-rules in `spec/MIGRATION.md` were the result of cross-spec review. If something in the codebase doesn't trip a rule, leave it alone and flag for human review.

---

## The migration workflow

A six-phase walk. Each phase links to a leaf for the detail; the SKILL.md only carries the workflow shape.

### Phase 1 — Orient

Before touching anything, gather context. Read in this order:

1. **The codebase's dep file** — `deps.edn`, `project.clj`, `shadow-cljs.edn`, or `bb.edn` — to confirm the project is actually on `re-frame/re-frame` and identify the substrate (Reagent unless told otherwise).
2. **`spec/MIGRATION.md`** in the re-frame2 repo — the rule table. Skim Part 1 to know which rules exist; you'll reach for them by id during the migration.
3. **The project's test suite shape** — is it `re-frame-test`-based, `cljs.test`-based, JVM-side, or absent? This tells you what "verified" looks like at the end.

→ `reference/setup.md` covers the deps-coord swap (M-0) and the adapter-artefact addition (`day8/re-frame2-reagent` for a Reagent app); these are the precondition for everything else.

### Phase 2 — Bump the dep (M-0)

Apply M-0 from `spec/MIGRATION.md`: swap the coord from `re-frame/re-frame` to `day8/re-frame2`, **and** add a substrate-adapter artefact (`day8/re-frame2-reagent` for Reagent, `-uix` / `-helix` if the project has already moved off Reagent). Bump and **stop** — try a compile before applying any other rules. The headline expectation in MIGRATION.md is that *most codebases require no further changes*. Verify that against this project before doing more work.

→ `reference/setup.md` for the per-build-tool shape (`deps.edn` vs `project.clj` vs `shadow-cljs.edn` vs `bb.edn`), the substrate-adapter picker, and what to do if no v2 version has shipped yet.

### Phase 3 — Sweep for breakage

If Phase 2's compile / test run surfaced failures, sweep the codebase for each M-rule in order. Use the **automated-transforms leaf** for the mechanical (Type A) rewrites and the **guided checklist** for the judgment-call (Type B) ones.

→ `reference/sequencing.md` for the recommended order of rules (matches `spec/MIGRATION.md`'s ordering; restated here so an interrupted migration can pick up).

→ `reference/automated-transforms.md` for the Type A patterns — find-and-replace shapes, ns-import rewrites, effect-map key folds — that can be applied without asking.

→ `reference/guided-checklist.md` for the Type B walkthroughs — `:dispatch` run-to-completion (M-3), `apply`/Var-aliased registration (M-5 dynamic half), `reg-global-interceptor` in a multi-frame context (M-17), `^:flush-dom` (M-16), the `reg-view` macro rewrite (M-22), and the rest.

→ `reference/breaking-changes.md` for a one-page lookup of *every* M- and O-rule keyed to the v1 symbol or pattern that triggers it — useful when the author asks "is this thing I just hit covered by a rule?"

### Phase 4 — Verify

Run the project's own verification. This skill does not run the tests for the author — that's general software practice, not migration-specific. The standard sequence is: re-compile, re-run unit tests, smoke-test the running app for boot / dispatch / sub / hot-reload. If any step fails, identify which rule applies, apply it, and re-verify. Repeat.

### Phase 5 — Optional opt-in modernisations (only if the author asked)

If — and only if — the author explicitly asked to *modernise* the codebase (not just migrate it), walk the `O-N` rules in `spec/MIGRATION.md`: rich registration metadata (O-1), `reg-view` adoption (O-2), Malli schemas (O-3), frames (O-4), binary fx (O-5), the canonical event-vector shape (M-19 framed as opt-in), state machines (O-8 / O-9), substrate moves (O-13 / O-14), `:invoke-all` (O-15). These are **never** auto-applied as part of a routine migration — they are stylistic / structural upgrades the author opts into.

### Phase 6 — Report

Produce the migration report per `spec/MIGRATION.md` Part 2 §"Output format for your report" — coord before/after, files modified, M-rules applied, items flagged for human review, anything unexpected. Keep it under 300 words unless the migration was unusually complex.

→ `reference/output-format.md` restates the format with one filled-in example, so the report stays consistent across migrations.

---

## Kickoff prompt (paste-ready)

If the author wants to *delegate* the migration to a fresh Claude session — running this skill against their codebase — there is a paste-ready prompt at `reference/kickoff-prompt.md`. The author copies it into a new Claude Code session opened in the root of their v1 project; the session loads this skill and walks the six phases above autonomously, surfacing decisions back to the author at the Type B checkpoints.

This is the most common shape: the migration is non-trivial enough to want a focused agent context, and the project author wants to keep their own primary session free for the rest of their work.

---

## Done checklist

Tell the author the migration is complete when **all** of these are true:

- [ ] `re-frame/re-frame` is removed from every dep file; `day8/re-frame2` + a substrate-adapter artefact have replaced it at a matching VERSION.
- [ ] The project compiles cleanly with re-frame2 on the classpath.
- [ ] All M-rules that tripped in this codebase have been applied (Type A) or had their flagged-for-human-review items resolved by the author (Type B).
- [ ] The project's existing test suite passes (or, if a test failed pre-migration, it fails identically post-migration — no new test failures introduced).
- [ ] The migration report (per `spec/MIGRATION.md` Part 2 / `reference/output-format.md`) has been produced and shared with the author.
- [ ] Items flagged for human review are explicitly listed in the report — none are left as "todo" without the author's awareness.

When the checklist passes, hand off: *"Migration complete. From here, switch to the **`re-frame2`** skill to write new application code, or **`re-frame-pair2`** to inspect the running app from the REPL. The opt-in modernisations (the `O-N` rules in `spec/MIGRATION.md`) are available whenever you want to adopt them — they are not required to be on v2."*

---

## Deep dives — reference files

For depth on each phase, read the matching leaf — every leaf is one level deep so you can read it in full:

- **`reference/kickoff-prompt.md`** — A paste-ready prompt the author drops into a fresh Claude Code session to kick the migration off. ~80 lines.
- **`reference/setup.md`** — M-0 in operational detail: the dep-file shapes (`deps.edn`, `project.clj`, `shadow-cljs.edn`, `bb.edn`), the substrate-adapter picker, the VERSION discovery procedure, and the artefact-split implications (`day8/re-frame2-http` / `-machines` / `-routing` etc. are pay-as-you-go). ~120 lines.
- **`reference/breaking-changes.md`** — A compressed one-page index of every M-rule and O-rule by trigger surface: when the author asks "is `X` covered?" you grep here, find the rule id, then load that rule's full text from `spec/MIGRATION.md`. ~180 lines.
- **`reference/sequencing.md`** — The recommended order to walk the rules. Restated so a partial migration can resume cleanly. ~70 lines.
- **`reference/automated-transforms.md`** — Type A patterns: the unambiguous mechanical rewrites the agent applies without asking. Shape-by-shape — ns rewrites, effect-map key consolidation, dispatch-with rewrites, framework-keyword consolidation. ~150 lines.
- **`reference/guided-checklist.md`** — Type B walkthroughs: the judgment-call rewrites. One sub-section per Type B rule (M-3, M-5 dynamic half, M-11, M-17, M-22, ...). Each walks the author through identification, risk explanation, and the decision they need to make. ~140 lines.
- **`reference/output-format.md`** — The migration-summary shape (the final report the agent produces), with two fully-filled-in worked examples. ~120 lines.

---

## Anti-patterns to avoid

A few traps that come up often enough to flag at the top level.

- **Don't apply Type B rewrites silently.** The Type A / Type B distinction exists precisely because Type B changes can break working code in non-obvious ways. If you find yourself wanting to "just" rewrite a `:dispatch`-inside-a-handler call that depended on the v1 intermediate-render behaviour, stop — that's M-3 (Type B). Identify the call site, explain the run-to-completion change, wait for the author's decision.
- **Don't bump every dep at once.** Bump *only* the re-frame coord (M-0). Don't update Reagent, React, shadow-cljs, or anything else as part of the migration. If those need updating, they're a separate task with separate failure modes.
- **Don't add `day8/re-frame2-schemas` (or `-machines`, `-routing`, ...) "to be safe".** The artefact split is pay-as-you-go (per M-27 through M-32). Only add a per-feature artefact when the codebase actually uses that feature; otherwise you're inflating the classpath for no win.
- **Don't migrate plain-Reagent fns to `reg-view` as part of the v1→v2 migration.** That is O-2 — an opt-in modernisation, never part of the required path. Plain Reagent fns continue to work in v2 (with a runtime warning only if rendered under a non-default frame, per M-11).
- **Don't touch the `re-frame-test` namespaces eagerly.** They've been renamed to `re-frame.test-support` (M-25); apply the rename as a mechanical pass. Do not rewrite the test bodies themselves unless they trip a separate rule.
- **Don't claim "migrated" before the report is written.** The report is the contract — it's how the author knows what changed, what's flagged, and what they still own.

If you hit anything else surprising, surface it to the author rather than guessing. The migration agent's job is to do the mechanical work cleanly and ask sharp questions about the rest — not to make the project owner's decisions for them.

---

*Authoritative breaking-change list: `spec/MIGRATION.md` (in this repo). For the v1 line: [re-frame](https://github.com/day8/re-frame). For greenfield bootstrap: `skills/re-frame2-setup/`. For application authoring on v2: `skills/re-frame2/`. For live-app inspection: `skills/re-frame-pair2/`.*
