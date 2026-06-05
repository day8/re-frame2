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
  re-frame-test, old top-level :dispatch / :dispatch-n effect-map keys),
  or a v1 add-on library (http-fx / :http-xhrio, async-flow-fx /
  :async-flow).
  **Do not use** for: greenfield bootstrap, writing v2 application code,
  live v2-app inspection, static critique, devtools tours, or porting
  re-frame2 itself — see the full routing table in `skills/README.md`
  §Skill routing for the right sibling skill.
allowed-tools:
  - Bash(rg *)
  - Bash(rg -l *)
  - Bash(gh issue list *)
  - Bash(gh issue view *)
  - Bash(gh issue create *)
  - Read
  - Edit
  - Write
  - Grep
  - Glob
---

# re-frame-migration

Helps an author migrate a ClojureScript codebase from re-frame v1.x to re-frame2. When done, the project depends on `day8/re-frame2` + a substrate adapter, Type A rewrites have been applied, and every Type B call site has been surfaced with the relevant rule cited.

The authoritative rule corpus — M-rules (required) and O-rules (opt-in modernisations) — lives in [`MIGRATION.md`](../../migration/from-re-frame-v1/README.md). **Do not duplicate that content here.** Load `MIGRATION.md` when you start the migration and treat it as the source of truth.

## When NOT to use

Full skill-disambiguation matrix lives at [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source). In brief: not for greenfield bootstrap, authoring on an already-v2 project, live-runtime inspection, porting re-frame2 itself, or spec / design-rationale reading.

Exit this skill when the project compiles, tests pass, the **boot smoke-test comes back clean** (compiling is not the done-bar — v2 moves a large class of failures to runtime; see Phase 4), and Type B items have been resolved.

## Cardinal rules

1. **[`MIGRATION.md`](../../migration/from-re-frame-v1/README.md) is the source of truth.** Every rewrite cites a rule id (`M-N` or `O-N`). If a call site doesn't match any rule, **stop and ask** — do not invent a rule.
2. **Type A is automatic; Type B is asked-first.** Type A is mechanical, unambiguous, observably identical — apply without prompting. Type B depends on intent — identify, explain the risk, wait for the author's decision.
3. **Smallest correct diff.** Do not refactor for style; do not rename what the author didn't ask to rename; do not add features (frames, schemas, machines, `reg-view`) unless the author asked for the O-rules.
4. **Apply rules in order.** Walk the rules top-to-bottom as listed in `MIGRATION.md`; M-0 (coord swap) is precondition for the rest.
5. **JVM interop is in scope.** Migrate `.clj` test runners and JVM-side fixtures alongside the CLJS code.
6. **Single-import contract for new code.** Application namespaces require `[re-frame.core :as rf]`. Direct requires of `re-frame.db`, `.router`, `.subs`, `.events`, `.registrar`, or `.alpha` get rewritten or flagged per M-1 / M-23.
7. **Ambiguous rule? File a GitHub issue against `day8/re-frame2` — don't edit `MIGRATION.md` inline.** This skill consumes that doc. Announce the cross-repo filing first. Compose the issue body with the **`Write` tool** (e.g. `/tmp/issue-body.md`), then file it with a bare `gh issue create --body-file /tmp/issue-body.md` — never interpolate the body inline (a `--body "$(…)"` subshell or a `cat >` here-doc would expand `$` / `` ` `` / `\` in any text drawn from the migration and is not a bare `gh issue` invocation). See [`../README.md` §Published-skill `allowed-tools` baseline](../README.md#published-skill-allowed-tools-baseline-security-policy) and the shared [`skills/shared/issue-filing.md`](../shared/issue-filing.md) recipe.
8. **Do not invent migration rules.** Leave the unmatched alone and flag for human review.
9. **Warn before a mass file rewrite.** Migration is destructive — Type A rewrites edit the author's source in place. **Before touching any file**, the skill announces the upcoming sweep: the rule it's about to apply (e.g. *"M-8 — fold top-level `:dispatch` keys into `:fx`"*), the count of files matched, and a one- or two-line example of the diff shape on a representative call site. Then pause for the author to Ctrl-C or acknowledge. The author should always have a real window to abort or scope-limit before the edits land. Per-call-site approval is not required (trust the explicit invoker); the gate is the sweep-level announcement, not per-file confirmation.
10. **The author runs builds, tests, and smoke-tests — not the skill.** Compile / `npm test` / `clj -M:test` / `shadow-cljs watch` / browser smoke-tests are arbitrary-code execution against the author's machine and dependencies. The skill **prints the exact command** for the author to run and waits for them to paste the result. It never invokes those commands itself. This holds for both freshly cloned and long-standing repos — a v1 project may still pull a compromised transitive dep at compile time. Verification is the author's loop, not the skill's. (`Bash(rg *)` is in `allowed-tools` because rg is a read-only search; build/test commands are not.)
11. **The migration corpus must be pinned.** [`MIGRATION.md`](../../migration/from-re-frame-v1/README.md) is the contract for every rewrite. Load it from a **local checkout pinned to a specific `day8/re-frame2` commit or tag** — verify `git rev-parse HEAD` and `remote get-url origin` before reading. Do not fetch `MIGRATION.md` from GitHub at runtime; an unpinned remote fetch makes every migration depend on whatever happens to be on `main` that minute. Record the pinned hash in the migration report (`references/output-format.md`). Same rule for the VERSION pick — record the chosen v2 release in the report, never silently select "latest".

## The migration workflow

Two pre-flight phases plus six phases. Each links to a leaf for the detail; the SKILL.md carries only the workflow shape.

**Phase 0a — Pre-flight: INVENTORY-AND-PLAN (run before everything — before any dep edit, before any compile, before the floor gate).** Before touching code or attempting a compile, (1) **inventory** the v1 re-frame add-on libraries on the classpath *and* the v1 re-frame features the app uses, (2) **scan their source** — add-on jars, git/source deps, vendored code — for removed/moved v2 surfaces, and (3) produce a **per-item migration plan** (each item → its `M-N`/`O-N` rule(s) + forced-vs-optional + disposition + replacement target). This turns the "march the wall" whack-a-mole — swap coord, compile, hit one broken namespace, fix, recompile, hit the next — into a single planned sweep, because the breakages live in **dependency source** that a compile reaches one namespace at a time. This phase is the **umbrella** that unifies the other pre-flights: it *drives* the React-19 floor gate (Phase 0b), the off-contract-namespace principle (M-1), the removed-`re-frame.core/console` add-on compile-gate, and the classpath-clean verification as the per-item checks each inventoried item runs through — it does not restate them. → [`references/inventory-and-plan.md`](references/inventory-and-plan.md). This phase precedes the floor gate; run it first.

**Phase 0b — Pre-flight: the React-19 / Reagent-2 floor gate (run before any dep edit).** re-frame2's substrate adapters target React 19 (the Reagent bridge runs on Reagent 2.x). For a project already on React 19 with a React-19-ready component library this is a fast pass; for the rest of the v1 population (React 17/18 + Reagent 1.x) it is the single largest and riskiest part of the migration, and the blocking case must be discovered *here* — not deep inside a failed compile loop after the coord swap. Run four checks before touching any dep coord: (1) **downstream React-lib compat audit** — enumerate `package.json` deps with a `react`/`react-dom` peer dependency and bucket each as React-19-ready / needs-bump / needs-replacement; (2) **component/substrate-library check** — confirm the project's UI component library has a React-19 (and, if Reagent-based, Reagent-2) release — **if it does not, STOP: this is a go/no-go BLOCKER, surface it to the author before any edit**; (3) **legacy-API scan** — flag surviving `ReactDOM.render` / `react-dom` legacy call sites (removed in React 18, still gone in 19) for the `createRoot` rewrite; (4) **explicit go/no-go** — GO carries the React/Reagent bump into M-0; NO-GO stops the migration until the blocker resolves. → [`references/setup.md`](references/setup.md#the-react-19--reagent-2-floor-gate-pre-flight--run-before-m-0) for the full gate. The Phase-0a inventory feeds this gate (the downstream-React-lib dimension is one of the per-item checks the inventory drives); this is a gate, not a footnote — clear it before Phase 1.

**Phase 1 — Orient.** Read the project's dep file (`deps.edn` / `project.clj` / `shadow-cljs.edn` / `bb.edn`), then [`MIGRATION.md`](../../migration/from-re-frame-v1/README.md) Part 1, then the project's test-suite shape. → [`references/setup.md`](references/setup.md) for the M-0 dep swap.

**Phase 2 — Bump the dep (M-0).** Swap `re-frame/re-frame` → `day8/re-frame2` + a substrate-adapter artefact (`day8/re-frame2-reagent` unless told otherwise), at the author-supplied `<v2-version>` (never auto-pick "latest"). Then ask the author to **compile** before applying any other rules — most codebases need no further changes. The skill prints the compile command for the project's build tool; the author runs it. → [`references/setup.md`](references/setup.md) for per-build-tool shapes and adapter picker.

**If the project's dev deps hold `day8.re-frame/re-frame-10x` (the v1 devtools panel), the Xray swap is a STANDARD, expected step — Xray IS the v2 devtools replacement for 10x.** The done-state for a 10x app is the app **on Xray**, not just the dead 10x preload removed. **Rule: 10x present ⇒ swap to Xray (standard); no 10x ⇒ Xray optional** — never force devtools on an app that never had them. → [`references/xray-replaces-10x.md`](references/xray-replaces-10x.md). It carries no `M-N` id (no application code triggers it), but **"not an M-rule" ≠ "optional"** for a 10x app.

**The swap is a two-part / two-timing operation straddling the M-rule sweep:** (1) **at M-0, neutralize the dead 10x preload now** (see the next paragraph) — drop the 10x coord + `:preloads` entry so the post-M-0 compile gate is reachable; (2) **post-M-40, mount Xray** — add `day8/re-frame2-xray` + its preload + the `[data-rf-xray-host]` layout host, because the preload auto-opens *after* `(rf/init!)` and so cannot mount until boot wiring is in place. The post-M-40 timing is a **sequencing detail** (Xray needs `init!` first), **not a downgrade to optional**: the "stop after M-0" rule (Phase 2) still holds for the M-rule sweep, and the Xray mount rides on top once boot wiring is in. The prerequisite is restated in [`references/xray-replaces-10x.md` §Prerequisites](references/xray-replaces-10x.md#prerequisites--apply-before-this-swap).

But **neutralize the 10x preload *now*, as part of M-0** — don't wait for the post-M-40 Xray swap. If the project preloads `day8.re-frame-10x.preload`, M-0's v1-`re-frame` exclusion makes that preload uncompilable, and the dev build still references it — so the post-M-0 *"stop and compile"* gate fails on the dead preload, not on application code. Remove the `:preloads` entry (and any 10x dev-module / `closure-defines` 10x flag) in the same M-0 dep-file edit so the compile gate is reachable; the Xray preload that *restores* devtools comes later (post-M-40). → [`references/setup.md` §Neutralize the re-frame-10x preload as part of M-0](references/setup.md#neutralize-the-re-frame-10x-preload-as-part-of-m-0).

**Phase 3 — Sweep for breakage.** If Phase 2's compile/test surfaced failures, walk the rules in order.
- [`references/sequencing.md`](references/sequencing.md) — recommended order, restated so an interrupted migration can resume.
- [`references/auto-call-site-rewrites.md`](references/auto-call-site-rewrites.md) — Type A: per-call-site mechanical rewrites (ns requires, effect-map, dispatch shapes).
- [`references/auto-cross-cutting.md`](references/auto-cross-cutting.md) — Type A: cross-cutting renames, interceptor cleanup, view / hiccup rewrites, init wiring, per-feature artefact adds.
- [`references/guided-handlers-state.md`](references/guided-handlers-state.md) — Type B: handler / view / db-seeding / error-handler / machine-spawn / Reagent-surface walkthroughs (M-3, M-5, M-10, M-11, M-12, M-13, M-14, M-15, M-34, M-42).
- [`references/guided-interceptors-subs.md`](references/guided-interceptors-subs.md) — Type B: interceptor / subscription / payload / observer walkthroughs (M-17, M-18, M-19, M-21, M-23, M-26).
- [`references/error-events.md`](references/error-events.md) — pointer to [`spec/009-Instrumentation.md` §Error event catalogue](../../spec/009-Instrumentation.md#error-event-catalogue) as the single source of truth for `:rf.error/*` / `:rf.warning/*` / `:rf.fx/*` / `:rf.cofx/*` / `:rf.ssr/*` / `:rf.epoch/*` / `:rf.http/*` categories. Load when writing `:on-error` / `register-listener!` (M-13, M-17, M-26).
- [`references/breaking-changes.md`](references/breaking-changes.md) — one-page index of every M-/O-rule by trigger surface; grep here to find the rule id.

**Phase 4 — Verify. "Compiles" is NOT the done-bar.** v2 moves a large class of v1 failures from **compile-time to RUNTIME** — a clean compile means "the rewrites parse," not "the app boots and runs." Several legitimate-looking, cleanly-compiling rewrites fail **silently at boot** (signal-fn `reg-sub` throwing at ns-load, a missing per-feature artefact, a `{:db fresh}` boot handler clobbering a live machine snapshot, a dropped M-8 top-level key, a `(when …)` nil-thread losing seed state) — none of these show in the build log; all need **live `app-db` inspection** to find. So Phase 4 is three steps, not one:

1. **The author recompiles** (the skill prints the exact compile command for the project shape — `shadow-cljs compile app`, `clj -M:test`, the npm script — and waits for the pasted output; cardinal rule 10).
2. **The author re-runs unit tests** (re-baseline render counts per M-12; no new failures).
3. **The author runs a BOOT SMOKE-TEST with live introspection** — boot the app in a dev build, then read the live frame's `app-db` + machine snapshots (`[:rf/runtime :machines :snapshots]`), deref the first-screen subs, dispatch one event per feature surface and re-read the affected slot, and scan the boot trace for `:rf.error/*` / `:rf.warning/*`. The cheapest tool is the **`re-frame2-pair` MCP / a shadow-cljs nREPL** — every silent failure above is invisible in the build log and shows only in the running runtime's state. **The migration is done when this loop comes back clean, not when it compiles.** → [`references/runtime-smoke-test.md`](references/runtime-smoke-test.md) for the silent-failure checklist (symptom → cite → confirming live read) and the smoke-test loop.

If any step fails, find the rule, apply it, ask the author to re-verify. The skill never executes build/test commands — see cardinal rule 10.

**Phase 5 — Opt-in modernisations (only if asked).** Walk the `O-N` rules in `MIGRATION.md` (O-1 rich metadata, O-2 `reg-view`, O-3 Malli, O-4 frames, O-8/O-9 machines, O-13/O-14 substrate moves, O-15 `:spawn-all`). The three **v1 add-on-library** modernisations are the highest-value O-rules for a real migration: O-16 (`day8.re-frame/async-flow-fx` / `:async-flow` → `reg-machine` **state machines** — translation guide + worked before→after in [`references/async-flow-to-machines.md`](references/async-flow-to-machines.md)), O-17 (`day8.re-frame/http-fx` / `:http-xhrio` → `:rf.http/managed` — translation guide + worked before→after in [`references/http-fx-to-managed-http.md`](references/http-fx-to-managed-http.md)), and O-18 (security + operational logging sweep on the observer surfaces M-13/M-17 hand off). Each is Type B (ask first) and detected by Maven coord + fx-key fingerprint; see `references/breaking-changes.md`. The *conversion path* of O-16 / O-17 is opt-in, but **doing something about the add-on is NOT** — `http-fx`, `async-flow-fx`, `undo`, and `forward-events-fx` `:refer` / call the removed `re-frame.core/console` and **fail to compile the moment re-frame2 is on the classpath**, so the broken add-on must be **removed or converted before the project compiles** (a forced compile-gate pre-step, surfaced in Phase 3 / Phase 4, not deferrable to "modernise later"). There is **no `re-frame.core/console` back-compat shim** — these v1 add-ons are superseded, not propped up. See [`references/breaking-changes.md` §v1 add-on libraries fail to COMPILE on v2](references/breaking-changes.md#v1-add-on-libraries-fail-to-compile-on-v2--replacementremoval-is-forced-not-opt-in). The remaining O-rules are never auto-applied as part of a routine migration. (O-5 was promoted to M-51 — binary fx is now required, not opt-in.)

**Phase 6 — Report.** Produce the migration report per `MIGRATION.md` Part 2 §"Output format for your report". → [`references/output-format.md`](references/output-format.md) — the format restated with one filled-in example.

## Kickoff (paste-ready)

For delegating the migration to a fresh Claude session: [`references/kickoff-prompt.md`](references/kickoff-prompt.md). The author drops it into a session opened in the root of their v1 project; the session loads this skill and walks the two pre-flight phases plus six phases, surfacing Type B checkpoints.

## Done checklist

- [ ] Inventory-and-plan produced (Phase 0a): every v1 re-frame add-on (incl. transitives, git/source deps, vendored) and every v1 re-frame app feature inventoried, each add-on's source scanned for v2-broken surfaces (off-contract `re-frame.*`, removed `console`, `unwrap`→`unwrap-interceptor`, React-19 coupling, classpath collision), and a per-item plan written (item → rule(s) → forced-vs-optional → disposition → replacement target → ordering).
- [ ] React-19 / Reagent-2 floor gate cleared (Phase 0b): downstream React-lib compat audited, component-library React-19 build confirmed (or the blocker resolved/the migration held), legacy `ReactDOM.render` call sites flagged, go/no-go recorded.
- [ ] `re-frame/re-frame` removed from every dep file; `day8/re-frame2` + adapter at a matching VERSION.
- [ ] Project compiles cleanly with re-frame2 on the classpath.
- [ ] Every tripped M-rule has been applied (Type A) or resolved by the author (Type B).
- [ ] Existing test suite passes (or fails identically to pre-migration — no new failures introduced).
- [ ] **Boot smoke-test passed (Phase 4 step 3) — "compiles" is not the done-bar.** App booted in a dev build; live `app-db` + machine snapshots read (`[:rf/runtime :machines :snapshots]` carries every expected boot/singleton machine); first-screen subs deref to real values (not `nil`); one event per feature surface dispatched and the affected slot re-read; boot trace scanned for `:rf.error/*` / `:rf.warning/*` (incl. `:rf.warning/runtime-state-dropped`). None of the silent-runtime-failure modes ([`references/runtime-smoke-test.md`](references/runtime-smoke-test.md)) present.
- [ ] **Xray replaces 10x** — for a project that used `day8.re-frame/re-frame-10x`: 10x dep + preload dropped at M-0, Xray dep + preload + `[data-rf-xray-host]` host wired post-M-40, panel verified (`Ctrl+Shift+C`). The app is **on Xray**. (No re-frame-10x in the project ⇒ nothing to check; don't add devtools the app never had.)
- [ ] Migration report (per `MIGRATION.md` Part 2 / `references/output-format.md`) produced and shared.
- [ ] Items flagged for human review are explicitly listed in the report.

Hand off: *"Migration complete. Switch to **`re-frame2`** for new application code, or **`re-frame2-pair`** for live inspection. The opt-in modernisations (`O-N` rules) are available whenever you want them — not required to be on v2."*

## Reference files (all one level deep)

- [`references/kickoff-prompt.md`](references/kickoff-prompt.md) — fresh-session kickoff prompt.
- [`references/inventory-and-plan.md`](references/inventory-and-plan.md) — Phase 0a: inventory the v1 add-ons + app features, scan their source for v2-broken surfaces, produce the per-item migration plan. The umbrella that drives the floor gate / off-contract-ns / console-gate / classpath-clean checks.
- [`references/setup.md`](references/setup.md) — M-0 operational detail: dep-file shapes, substrate-adapter picker, VERSION discovery, artefact-split implications.
- [`references/xray-replaces-10x.md`](references/xray-replaces-10x.md) — devtools swap: drop `day8.re-frame/re-frame-10x`, add `day8/re-frame2-xray` (preload, true-inline host, `--rf-xray-inline-width`, keybindings, 10x→Xray parity matrix).
- [`references/breaking-changes.md`](references/breaking-changes.md) — compressed index of every M-/O-rule by trigger surface.
- [`references/async-flow-to-machines.md`](references/async-flow-to-machines.md) — O-16 translation guide: `async-flow-fx` async sequences → `reg-machine` state machines, with a worked boot/login before→after.
- [`references/http-fx-to-managed-http.md`](references/http-fx-to-managed-http.md) — O-17 translation guide: `http-fx` (`:http-xhrio`) → managed HTTP (`:rf.http/managed`), with a worked GET-with-JSON before→after.
- [`references/sequencing.md`](references/sequencing.md) — recommended walk order.
- [`references/auto-call-site-rewrites.md`](references/auto-call-site-rewrites.md) — Type A: per-call-site mechanical rewrites.
- [`references/auto-cross-cutting.md`](references/auto-cross-cutting.md) — Type A: cross-cutting renames, view / hiccup, init, per-feature artefacts.
- [`references/guided-handlers-state.md`](references/guided-handlers-state.md) — Type B: handler / view / db-seeding / error-handler walkthroughs.
- [`references/guided-interceptors-subs.md`](references/guided-interceptors-subs.md) — Type B: interceptor / subscription / payload / observer walkthroughs.
- [`references/error-events.md`](references/error-events.md) — pointer to Spec 009's error-event catalogue (single source); load when writing `:on-error` policies or `register-listener!` listeners.
- [`references/output-format.md`](references/output-format.md) — migration-report shape with worked examples.
- [`references/runtime-smoke-test.md`](references/runtime-smoke-test.md) — Phase 4 detail: "compiles" is not the done-bar; the silent-runtime-failure checklist (signal-fn `reg-sub` / artefact-missing / `:rf/runtime` clobber / dropped M-8 key / nil-thread) + the boot smoke-test loop with live `app-db` introspection.

## Anti-patterns

- **Don't apply Type B rewrites silently** — the Type A / Type B distinction exists precisely because Type B changes can break working code (e.g. M-3 run-to-completion semantics).
- **Don't bump every dep at once** — only the re-frame coord (M-0). Other updates are separate tasks with separate failure modes.
- **Don't add `-schemas` / `-machines` / `-routing` "to be safe"** — the artefact split is pay-as-you-go (M-27 through M-32).
- **Don't migrate plain-Reagent fns to `reg-view`** — that's O-2 (opt-in), never required. Plain Reagent fns work in v2 with a runtime warning only under non-default frames (M-11).
- **Don't touch `re-frame-test` namespaces eagerly** — renamed to `re-frame.test-support` (M-25); apply as a mechanical pass. Don't rewrite test bodies unless they trip a separate rule.
- **Don't treat a clean compile as "done."** v2 moves a large class of v1 failures from compile-time to RUNTIME — signal-fn `reg-sub`, missing per-feature artefacts, a `{:db fresh}` boot handler clobbering a machine snapshot, dropped M-8 top-level keys, `(when …)` nil-threads. These compile clean and break silently at boot; only a live-introspection boot smoke-test ([`references/runtime-smoke-test.md`](references/runtime-smoke-test.md)) catches them. The done-bar is a clean boot smoke-test, not a clean compile.
- **Don't claim "migrated" before the report is written** — the report is the contract.

(The "announce before a mass rewrite" and "author runs builds/tests, not the skill" rules are Cardinal rules 9 and 10 — owned there, not restated here.)

---

*Authoritative breaking-change list: [`MIGRATION.md`](../../migration/from-re-frame-v1/README.md). v1 line: [re-frame](https://github.com/day8/re-frame). Full skill-disambiguation matrix: [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source).*
