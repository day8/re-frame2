---
name: re-frame-migration
description: >
  Migrates an existing re-frame v1.x ClojureScript codebase to re-frame2.
  Swaps the artefact coord (re-frame/re-frame → day8/re-frame2 + a substrate
  adapter), applies the mechanical (Type A) rewrites from MIGRATION.md
  automatically, and flags the judgment-call (Type B) call sites for human
  review before touching them. Trigger on phrasing like "migrate to
  re-frame2", "upgrade re-frame", "v1 to v2", "what breaks under re-frame2",
  or any prompt referencing a v1 surface (re-frame.db, dispatch-with,
  reg-global-interceptor, reg-sub-raw, ^:flush-dom, re-frame.alpha,
  re-frame-test, old top-level :dispatch / :dispatch-n effect-map keys).
  Do not use for: greenfield bootstrap (use `re-frame2-setup`), writing v2
  application code (use `re-frame2`), live v2-app inspection
  (use `re-frame-pair2`), or porting re-frame2 itself
  (use `re-frame2-implementor`).
allowed-tools:
  - Bash(rg *)
  - Bash(rg -l *)
  - Read
  - Edit
  - Write
  - Grep
  - Glob
---

# re-frame-migration

Helps an author migrate a ClojureScript codebase from re-frame v1.x to re-frame2. When done, the project depends on `day8/re-frame2` + a substrate adapter, Type A rewrites have been applied, and every Type B call site has been surfaced with the relevant rule cited.

The authoritative rule corpus — M-rules (required) and O-rules (opt-in modernisations) — lives in [`MIGRATION.md`](../../spec/MIGRATION.md). **Do not duplicate that content here.** Load `MIGRATION.md` when you start the migration and treat it as the source of truth.

## When NOT to use

Full skill-disambiguation matrix lives at [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source). In brief: not for greenfield bootstrap, authoring on an already-v2 project, live-runtime inspection, porting re-frame2 itself, or spec / design-rationale reading.

Exit this skill when the project compiles, tests pass, and Type B items have been resolved.

## Cardinal rules

1. **[`MIGRATION.md`](../../spec/MIGRATION.md) is the source of truth.** Every rewrite cites a rule id (`M-N` or `O-N`). If a call site doesn't match any rule, **stop and ask** — do not invent a rule.
2. **Type A is automatic; Type B is asked-first.** Type A is mechanical, unambiguous, observably identical — apply without prompting. Type B depends on intent — identify, explain the risk, wait for the author's decision.
3. **Smallest correct diff.** Do not refactor for style; do not rename what the author didn't ask to rename; do not add features (frames, schemas, machines, `reg-view`) unless the author asked for the O-rules.
4. **Apply rules in order.** Walk the rules top-to-bottom as listed in `MIGRATION.md`; M-0 (coord swap) is precondition for the rest.
5. **JVM interop is in scope.** Migrate `.clj` test runners and JVM-side fixtures alongside the CLJS code.
6. **Single-import contract for new code.** Application namespaces require `[re-frame.core :as rf]`. Direct requires of `re-frame.db`, `.router`, `.subs`, `.events`, `.registrar`, or `.alpha` get rewritten or flagged per M-1 / M-23.
7. **Ambiguous rule? File a bead — don't edit `MIGRATION.md` inline.** This skill consumes that doc.
8. **Do not invent migration rules.** Leave the unmatched alone and flag for human review.

## The migration workflow

Six phases. Each links to a leaf for the detail; the SKILL.md carries only the workflow shape.

**Phase 1 — Orient.** Read the project's dep file (`deps.edn` / `project.clj` / `shadow-cljs.edn` / `bb.edn`), then [`MIGRATION.md`](../../spec/MIGRATION.md) Part 1, then the project's test-suite shape. → [`reference/setup.md`](reference/setup.md) for the M-0 dep swap.

**Phase 2 — Bump the dep (M-0).** Swap `re-frame/re-frame` → `day8/re-frame2` + a substrate-adapter artefact (`day8/re-frame2-reagent` unless told otherwise). Compile **before** applying any other rules — most codebases need no further changes. → [`reference/setup.md`](reference/setup.md) for per-build-tool shapes and adapter picker.

**Phase 3 — Sweep for breakage.** If Phase 2's compile/test surfaced failures, walk the rules in order.
- [`reference/sequencing.md`](reference/sequencing.md) — recommended order, restated so an interrupted migration can resume.
- [`reference/auto-call-site-rewrites.md`](reference/auto-call-site-rewrites.md) — Type A: per-call-site mechanical rewrites (ns requires, effect-map, dispatch shapes).
- [`reference/auto-cross-cutting.md`](reference/auto-cross-cutting.md) — Type A: cross-cutting renames, interceptor cleanup, view / hiccup rewrites, init wiring, per-feature artefact adds.
- [`reference/guided-handlers-state.md`](reference/guided-handlers-state.md) — Type B: handler / view / db-seeding / error-handler walkthroughs (M-3, M-5, M-10, M-11, M-12, M-13, M-14, M-15).
- [`reference/guided-interceptors-subs.md`](reference/guided-interceptors-subs.md) — Type B: interceptor / subscription / payload / observer walkthroughs (M-17, M-18, M-19, M-21, M-23, M-26).
- [`reference/error-events.md`](reference/error-events.md) — pointer to [`spec/009-Instrumentation.md` §Error event catalogue](../../spec/009-Instrumentation.md#error-event-catalogue) as the single source of truth for `:rf.error/*` / `:rf.warning/*` / `:rf.fx/*` / `:rf.cofx/*` / `:rf.ssr/*` / `:rf.epoch/*` / `:rf.http/*` categories. Load when writing `:on-error` / `register-trace-cb!` (M-13, M-17, M-26).
- [`reference/breaking-changes.md`](reference/breaking-changes.md) — one-page index of every M-/O-rule by trigger surface; grep here to find the rule id.

**Phase 4 — Verify.** Recompile, re-run unit tests, smoke-test boot / dispatch / sub / hot-reload. If a step fails, find the rule, apply it, re-verify. The skill does not run tests for the author.

**Phase 5 — Opt-in modernisations (only if asked).** Walk the `O-N` rules in `MIGRATION.md` (O-1 rich metadata, O-2 `reg-view`, O-3 Malli, O-4 frames, O-8/O-9 machines, O-13/O-14 substrate moves, O-15 `:invoke-all`, etc.). Never auto-applied as part of a routine migration. (O-5 was promoted to M-51 under rf2-j9cm2 — binary fx is now required, not opt-in.)

**Phase 6 — Report.** Produce the migration report per `MIGRATION.md` Part 2 §"Output format for your report". → [`reference/output-format.md`](reference/output-format.md) — the format restated with one filled-in example.

## Kickoff (paste-ready)

For delegating the migration to a fresh Claude session: [`reference/kickoff-prompt.md`](reference/kickoff-prompt.md). The author drops it into a session opened in the root of their v1 project; the session loads this skill and walks the six phases, surfacing Type B checkpoints.

## Done checklist

- [ ] `re-frame/re-frame` removed from every dep file; `day8/re-frame2` + adapter at a matching VERSION.
- [ ] Project compiles cleanly with re-frame2 on the classpath.
- [ ] Every tripped M-rule has been applied (Type A) or resolved by the author (Type B).
- [ ] Existing test suite passes (or fails identically to pre-migration — no new failures introduced).
- [ ] Migration report (per `MIGRATION.md` Part 2 / `reference/output-format.md`) produced and shared.
- [ ] Items flagged for human review are explicitly listed in the report.

Hand off: *"Migration complete. Switch to **`re-frame2`** for new application code, or **`re-frame-pair2`** for live inspection. The opt-in modernisations (`O-N` rules) are available whenever you want them — not required to be on v2."*

## Reference files (all one level deep)

- [`reference/kickoff-prompt.md`](reference/kickoff-prompt.md) — fresh-session kickoff prompt.
- [`reference/setup.md`](reference/setup.md) — M-0 operational detail: dep-file shapes, substrate-adapter picker, VERSION discovery, artefact-split implications.
- [`reference/breaking-changes.md`](reference/breaking-changes.md) — compressed index of every M-/O-rule by trigger surface.
- [`reference/sequencing.md`](reference/sequencing.md) — recommended walk order.
- [`reference/auto-call-site-rewrites.md`](reference/auto-call-site-rewrites.md) — Type A: per-call-site mechanical rewrites.
- [`reference/auto-cross-cutting.md`](reference/auto-cross-cutting.md) — Type A: cross-cutting renames, view / hiccup, init, per-feature artefacts.
- [`reference/guided-handlers-state.md`](reference/guided-handlers-state.md) — Type B: handler / view / db-seeding / error-handler walkthroughs.
- [`reference/guided-interceptors-subs.md`](reference/guided-interceptors-subs.md) — Type B: interceptor / subscription / payload / observer walkthroughs.
- [`reference/error-events.md`](reference/error-events.md) — pointer to Spec 009's error-event catalogue (single source); load when writing `:on-error` policies or `register-trace-cb!` listeners.
- [`reference/output-format.md`](reference/output-format.md) — migration-report shape with worked examples.

## Anti-patterns

- **Don't apply Type B rewrites silently** — the Type A / Type B distinction exists precisely because Type B changes can break working code (e.g. M-3 run-to-completion semantics).
- **Don't bump every dep at once** — only the re-frame coord (M-0). Other updates are separate tasks with separate failure modes.
- **Don't add `-schemas` / `-machines` / `-routing` "to be safe"** — the artefact split is pay-as-you-go (M-27 through M-32).
- **Don't migrate plain-Reagent fns to `reg-view`** — that's O-2 (opt-in), never required. Plain Reagent fns work in v2 with a runtime warning only under non-default frames (M-11).
- **Don't touch `re-frame-test` namespaces eagerly** — renamed to `re-frame.test-support` (M-25); apply as a mechanical pass. Don't rewrite test bodies unless they trip a separate rule.
- **Don't claim "migrated" before the report is written** — the report is the contract.

---

*Authoritative breaking-change list: [`MIGRATION.md`](../../spec/MIGRATION.md). v1 line: [re-frame](https://github.com/day8/re-frame). Full skill-disambiguation matrix: [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source).*
