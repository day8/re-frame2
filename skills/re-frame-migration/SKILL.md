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
  - Bash(git -C * rev-parse *)
  - Bash(git -C * remote get-url *)
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

## The mental model (read this first)

**v1→v2 is mostly M-0 (the coord swap) plus mechanical rewrites — most apps need little.** Bump the dep, compile, and a large fraction of the codebase already runs. The real hazard is the other axis: v2 moves a class of failures the **compiler cannot catch** to *runtime* — a cleanly-compiling rewrite that boots wrong (a `{:db fresh}` boot clobbering framework state, a signal-fn `reg-sub` that registers as a `:parametric` sub then throws at first materialization, a dropped top-level `:dispatch`). So the discipline is: **sweep the silent-failure rules exhaustively up front** (grep every site — never march-the-wall, the compile gives you no wall to hit), then **smoke-test the booted app live** (read `app-db` + machine snapshots). "Compiles" is the start of verification, not the end. Everything below is organised around *where the danger is*: the cardinal rules are the invariants, the phases are the route, and two axes tell you what each rule needs — Type A/B (auto or ask?) and loud/silent (will anything tell me if I miss it?).

The authoritative rule corpus — M-rules (required) and O-rules (opt-in modernisations) — lives in [`MIGRATION.md`](../../migration/from-re-frame-v1/README.md). (**"MIGRATION.md" is this skill's shorthand, not a filename** — no file by that name exists; the link opens `migration/from-re-frame-v1/README.md`, which is the file you actually read. Every later "MIGRATION.md" mention means that one file.) **Do not duplicate that content here.** Load `MIGRATION.md` when you start the migration and treat it as the source of truth.

## When NOT to use

Full skill-disambiguation matrix lives at [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source). In brief: not for greenfield bootstrap, authoring on an already-v2 project, live-runtime inspection, porting re-frame2 itself, or spec / design-rationale reading.

Exit this skill when the project compiles, tests pass, the **boot smoke-test comes back clean** (compiling is not the done-bar — v2 moves a large class of failures to runtime; see Phase 4), and Type B items have been resolved.

## Cardinal rules (the invariants)

Five invariants. Hold them in your head; everything else is procedure that lives in a leaf.

1. **[`MIGRATION.md`](../../migration/from-re-frame-v1/README.md) is the source of truth — don't invent rules.** Every rewrite cites a rule id (`M-N` or `O-N`). If a call site doesn't match any rule and isn't on the preserved list, **stop and ask / flag for human review** — never guess a rule. (Genuinely ambiguous rule → file an upstream `day8/re-frame2` issue; don't edit `MIGRATION.md` inline.)
2. **Type A is automatic; Type B is asked-first.** Type A is mechanical, unambiguous, observably identical — apply without prompting (after the sweep-level announcement, below). Type B depends on intent the skill can't recover statically — identify, explain the risk, wait for the author's decision. *(The orthogonal loud/silent axis — see [`breaking-changes.md`](references/breaking-changes.md) — tells you how a missed rule will surface, not whether to ask.)*
3. **Smallest correct diff.** Don't refactor for style, don't rename what the author didn't ask to rename, don't add features (frames, schemas, machines, `reg-view`) unless the author asked for the O-rules. Apply M-rules in `MIGRATION.md` order (M-0 first); JVM interop (`.clj` test runners, fixtures) is in scope.
4. **Announce before a mass rewrite.** Migration is destructive — Type A rewrites edit source in place. Before touching files, announce the sweep: the rule (*"M-8 — fold top-level `:dispatch` into `:fx`"*), the count of files matched, and a one-line example diff shape; then pause for the author to abort or scope-limit. The gate is the *sweep-level* announcement, not per-file confirmation.
5. **The author runs builds, tests, and smoke-tests — not the skill.** Compile / `npm test` / `clj -M:test` / browser smoke-tests are arbitrary-code execution against the author's machine and deps (a v1 project may pull a compromised transitive dep at compile time). The skill **prints the exact command** and waits for the pasted result; it never invokes them. The trust boundary is *arbitrary-code execution*, not *any shell command*: read-only corpus-provenance commands are a separate, narrower class the skill DOES run itself — the migration-corpus pin checks (`git -C <path-to-re-frame2> rev-parse HEAD` and `git -C <path-to-re-frame2> remote get-url origin` — see [`references/setup.md` §Pin the migration corpus](references/setup.md#pin-the-migration-corpus-before-reading-it)) and codebase greps (`rg`) inspect state without executing project code, so they are allow-listed (`Bash(rg *)` + the two scoped `Bash(git -C * rev-parse *)` / `Bash(git -C * remote get-url *)` entries). Build / test / install / smoke are the author's loop; read-only provenance and search are the skill's.

> **Delegated / orchestrated / CI execution — the sandboxed-executor exception.** "The author" in rule 5 may be a human, a *sandboxed autonomous worker*, or a *CI runner* — and the trust boundary is the same for all three: it is *arbitrary-code execution*, not *who types the command*. **Interactive mode: the human is the boundary** — they run build / test / install / smoke on their own machine, so rule 5's default holds unchanged (print the exact command, wait for the pasted result; the skill never runs a build on a human author's machine). **Orchestrated / CI mode: the sandbox is the boundary** — an autonomous worker in an isolated git worktree, or a CI runner, *is itself* an isolated execution environment, so it runs the printed build / test / smoke commands **itself, inside its own worktree/sandbox** (never against a human's machine), which honours the *same* trust model the human satisfies by running them on theirs. In that mode the executor does not stall inline on a paste that no human is there to give — it **runs the printed command and posts command + result into the PR body (or the CI log)** for the human to ratify. **Type-B checkpoints and the boot-smoke verdict stay the human's decision** — surfaced *asynchronously at PR review* instead of an inline paste-loop, ratified at merge time. This is the sandboxed-executor exception only: it does **not** license running builds on an interactive author's machine — rule 5's default stands.

**Recipes (demoted from the rules — load when you need them):** the single-import contract + off-contract-ns rewrite is M-1 ([`breaking-changes.md`](references/breaking-changes.md)); the shell-injection-safe upstream-issue filing recipe is in [`skills/shared/issue-filing.md`](../shared/issue-filing.md) (+ the [`../README.md` §`allowed-tools` baseline](../README.md#published-skill-allowed-tools-baseline-security-policy)); pinning the migration corpus + recording the pinned hash / chosen VERSION in the report is in [`references/setup.md` §Pin the migration corpus](references/setup.md#pin-the-migration-corpus-before-reading-it).

## The migration workflow

Two pre-flight phases plus six phases. Each links to a leaf for the detail; the SKILL.md carries only the workflow shape.

**Phase 0a — Pre-flight: INVENTORY-AND-PLAN.** *Gate: run before everything — before any dep edit, any compile, the floor gate.* Inventory the v1 add-on libraries + app features, scan their **source** (jars, git/source deps, vendored) for removed/moved v2 surfaces, and write a **per-item plan** (item → rule(s) → forced-vs-optional → disposition → replacement). *Why:* the breakages live in dependency source a compile reaches one namespace at a time — planning the sweep up front beats marching the wall. This phase is the umbrella that drives the floor gate, the off-contract-ns principle (M-1), the console compile-gate, and the classpath-clean check. → [`references/inventory-and-plan.md`](references/inventory-and-plan.md).

**Phase 0b — Pre-flight: the React-19 / Reagent-2 floor gate.** *Gate: run before any dep edit; GO/NO-GO blocker.* re-frame2's adapters target React 19 (the Reagent bridge runs on Reagent 2.x). *Why:* for React-17/18 + Reagent-1.x projects this is the largest, riskiest part of the migration and the blocking case must surface *here*, not inside a failed post-swap compile. Run the five checks (downstream React-lib audit; component-library React-19 build — **no *declared* React-19 release ⇒ STOP and surface the four-option go/no-go decision** (wait for a release / replace / vendor-or-patch / force React 19 + verify the library *empirically* at runtime — an empirical pass is a valid GO; only a no-declared-release-*and*-no-empirical-pass library is a NO-GO BLOCKER); legacy `ReactDOM.render` scan; **CLJS/shadow-cljs/Closure toolchain-skew check** — an older shadow-cljs breaks on the newer Closure with a cryptic `NoSuchFieldError` that looks like a migration bug, bump shadow-cljs to the reference version; explicit go/no-go) before touching any coord. → [`references/setup.md`](references/setup.md#the-react-19--reagent-2-floor-gate-pre-flight--run-before-m-0). The Phase-0a inventory feeds this gate.

**Phase 1 — Orient.** Read the project's dep file (`deps.edn` / `project.clj` / `shadow-cljs.edn` / `bb.edn`), then [`MIGRATION.md`](../../migration/from-re-frame-v1/README.md) Part 1, then the project's test-suite shape. → [`references/setup.md`](references/setup.md) for the M-0 dep swap.

**Phase 2 — Bump the dep (M-0).** Swap `re-frame/re-frame` → `day8/re-frame2` + a substrate-adapter artefact (`day8/re-frame2-reagent` unless told otherwise), at the author-supplied `<v2-version>` (never auto-pick "latest"). **Carry every Phase-0b GO-state bump into this same pass** — the React/Reagent bump, any component-lib bumps, **and the shadow-cljs/CLJS toolchain bump (Check 4)** — so the post-M-0 compile runs against the fully-current toolchain (an older shadow-cljs left in place detonates the first compile with the cryptic `NoSuchFieldError`). Then ask the author to **compile** before applying any other rules — most codebases need no further changes. The skill makes the dep-file + `package.json` edits and prints the compile + `npm install` commands; the **author runs** them (cardinal rule 5). → [`references/setup.md`](references/setup.md) for per-build-tool shapes and adapter picker.

**Devtools:** if the project ships `day8.re-frame/re-frame-10x`, swap it to **Xray** (the v2 devtools replacement) — a standard, expected step whose done-state is the app *on Xray* (no 10x ⇒ Xray optional, never force it). The swap straddles the sweep at two timings (neutralize the dead 10x preload *at M-0* so the compile gate is reachable; mount Xray *post-M-40* once `init!` exists). Both timings + the drop/add/host shape + the 10x→Xray parity matrix live in [`references/xray-replaces-10x.md`](references/xray-replaces-10x.md).

**Phase 3 — Apply the planned sweep.** Always carry forward the Phase-0a plan: forced add-on/classpath blockers, silent-fail rules, and M-70 are applied or triaged whether Phase 2 compiled cleanly or not — they compile clean and never surface as a compile failure. If the compile/test *also* surfaced failures, additionally walk those M-rules in order. (A clean compile does NOT route straight to the report; the planned silent-fail hits still apply.)

> **Forced at this gate: a v1 add-on does NOT compile on v2 — remove or convert it now.** `http-fx`, `async-flow-fx`, `undo`, and `forward-events-fx` `:refer` / call the removed `re-frame.core/console` and **fail to compile the moment re-frame2 is on the classpath** (there is no back-compat shim). The project will not compile until each broken add-on is removed or replaced — this is a required compile-gate pre-step, *not* deferrable to "modernise later." (The idiomatic v2 *replacement* — machines for `async-flow`, managed-HTTP for `http-fx` — is the opt-in step at Phase 5; what's forced here is acting at all.) See [`references/breaking-changes.md` §v1 add-on libraries fail to COMPILE on v2](references/breaking-changes.md#v1-add-on-libraries-fail-to-compile-on-v2--replacementremoval-is-forced-not-opt-in).

- [`references/sequencing.md`](references/sequencing.md) — recommended order, restated so an interrupted migration can resume.
- [`references/auto-call-site-rewrites.md`](references/auto-call-site-rewrites.md) — Type A: per-call-site mechanical rewrites (ns requires, effect-map, dispatch shapes).
- [`references/auto-cross-cutting.md`](references/auto-cross-cutting.md) — Type A: cross-cutting renames, interceptor cleanup (incl. M-70 event interceptor chains → metadata `:interceptors` — mechanical, loud-at-runtime not loud-at-compile), view / hiccup rewrites, init wiring, per-feature artefact adds.
- [`references/guided-handlers-state.md`](references/guided-handlers-state.md) — Type B: handler / view / db-seeding / error-handler / machine-spawn / Reagent-surface walkthroughs (M-3, M-5, M-10, M-11, M-12, M-13, M-14, M-15, M-34, M-42).
- [`references/guided-interceptors-subs.md`](references/guided-interceptors-subs.md) — Type B: interceptor / subscription / payload / observer walkthroughs (M-17, M-18, M-71, M-19, M-21, M-23, M-26).
- [`references/error-events.md`](references/error-events.md) — pointer to [`spec/009-Instrumentation.md` §Error event catalogue](../../spec/009-Instrumentation.md#error-event-catalogue) as the single source of truth for `:rf.error/*` / `:rf.warning/*` / `:rf.fx/*` / `:rf.cofx/*` / `:rf.ssr/*` / `:rf.epoch/*` / `:rf.http/*` categories. Load when wiring error observability — the one stream-parameterized `register-listener!` verb: the `:errors` stream for always-on production egress, the `:trace` stream for the dev-only raw trace stream. There is **no app-steering frame-level `:on-error` recovery policy** in v2; recovery is framework-owned (M-13, M-17, M-26).
- [`references/causal-world-inputs.md`](references/causal-world-inputs.md) — EP-0010 recording rule + EP-0017 reshape (M-72): a durable write fed by an *ambient* host read (`Date.now` / `now-ms` / `random-uuid` / `rand*` / `js/location` / `localStorage`, or a non-recordable `:now` cofx) must move to a declared recordable coeffect (`:rf/time-ms`) / the event payload; and `inject-cofx` is removed, `:rf.world/inputs` → flat `:rf.cofx`, `reg-cofx` value-returning, declared via `:rf.cofx/requires`. **Silent** for the durable-read judgment (invisible to compile *and* boot smoke-test, replay/restore-only); **loud** for the removed `inject-cofx` / renamed opt. Run the up-front grep here — load when it finds host reads inside handlers, reducers, or reply handlers, or any `inject-cofx` / `:rf.world/inputs` site. Diagnostic + host-transient reads stay ambient.
- [`references/breaking-changes.md`](references/breaking-changes.md) — one-page index of every M-/O-rule by trigger surface; grep here to find the rule id.

**Phase 4 — Verify. "Compiles" is NOT the done-bar.** v2 moves a large class of v1 failures from **compile-time to RUNTIME** — a clean compile means "the rewrites parse," not "the app boots and runs." Several legitimate-looking, cleanly-compiling rewrites fail **silently at boot** (signal-fn `reg-sub`, a missing per-feature artefact, a `{:db fresh}` boot clobbering a machine snapshot, a dropped M-8 top-level key, a `(when …)` nil-thread losing seed state — full list in [`references/runtime-smoke-test.md`](references/runtime-smoke-test.md)); none show in the build log, all need **live `app-db` inspection** to find. So Phase 4 is three steps, not one:

1. **The author recompiles** (the skill prints the exact compile command for the project shape — `shadow-cljs compile app`, `clj -M:test`, the npm script — and waits for the pasted output; cardinal rule 5).
2. **The author re-runs unit tests** (re-baseline render counts per M-12; no new failures).
3. **The author runs a BOOT SMOKE-TEST with live introspection** — boot the app in a dev build, then read the live frame's `app-db` + machine snapshots (in runtime-db at `[:rf.runtime/machines :snapshots]`), deref the first-screen subs, dispatch one event per feature surface and re-read the affected slot, and scan the boot trace for `:rf.error/*` / `:rf.warning/*`. The cheapest tool is the **`re-frame2-pair` MCP / a shadow-cljs nREPL** — every silent failure above is invisible in the build log and shows only in the running runtime's state. **The migration is done when this loop comes back clean, not when it compiles.** → [`references/runtime-smoke-test.md`](references/runtime-smoke-test.md) for the silent-failure checklist (symptom → cite → confirming live read) and the smoke-test loop.

If any step fails, find the rule, apply it, ask the author to re-verify. The skill never executes build/test commands — see cardinal rule 5.

**Phase 5 — Opt-in modernisations (only if asked).** Walk the `O-N` rules in `MIGRATION.md` (O-1 rich metadata, O-2 `reg-view`, O-3 Malli, O-4 frames, O-8 routing, O-9 machines (`:system-id`), O-13/O-14 substrate moves, O-15 `:spawn-all`). The three **v1 add-on-library** conversions are the highest-value O-rules for a real migration — the *idiomatic v2 replacement* for the add-ons Phase 3 forced you to remove-or-convert: O-16 (`async-flow-fx` / `:async-flow` → `reg-machine` **state machines** — [`references/async-flow-to-machines.md`](references/async-flow-to-machines.md)), O-17 (`http-fx` / `:http-xhrio` → `:rf.http/managed` — [`references/http-fx-to-managed-http.md`](references/http-fx-to-managed-http.md)), and O-18 (security + operational logging sweep on the M-13/M-17 observer surfaces). Each is Type B (ask first), detected by Maven coord + fx-key fingerprint (`references/breaking-changes.md`). The conversion *path* is opt-in here; *acting* on the broken add-on was forced at Phase 3 (convert, or remove if the feature's gone). **One further opt-in modernisation has no `O-N` slot in `MIGRATION.md` yet but its own corpus conversion guide: `shipclojure/re-frame-query` queries (and hand-rolled Pattern-RemoteData server-state caches) → re-frame2 resources** (`reg-resource` / `:rf.resource/*`, Spec 016) — purely opt-in (a query lib does **not** break the compile; its `reg-event-fx` handlers migrate under M-73 regardless), Type B semantic re-modelling, surfaced from the Phase-0a inventory. Guide: [`migration/from-re-frame-v1/re-frame-query-to-resources.md`](../../migration/from-re-frame-v1/re-frame-query-to-resources.md). The remaining O-rules are never auto-applied as part of a routine migration. (O-5 was promoted to M-51 — binary fx is now required, not opt-in.)

**Phase 6 — Report.** Produce the migration report per `MIGRATION.md` Part 2 §"Output format for your report". → [`references/output-format.md`](references/output-format.md) — the format restated with one filled-in example.

## Boot & init (the one topic that crosses every phase)

Boot is where a real migration hits the most friction, because v2 changes app boot *structurally* — so the guidance that's spread across the phases above is gathered here as one coherent topic. Every non-trivial app boots; get these four facts right together and you prevent an entire class of silent runtime breakage. (Each links to the leaf that owns the detail; this is the map, not a restatement.)

1. **`init!` + an app frame are action 0 — a v1 app has NEITHER, so you ADD both (M-40).** `(rf/init! <adapter>)` installs the runtime but creates no frame; you also `reg-frame` an explicit app frame and establish it as a scope, all **before the first `dispatch`/`dispatch-sync` and before the first render** — a boot dispatch under no scope fails loudly with `:rf.error/no-frame-context`. → [`references/auto-cross-cutting.md` §Boot-sequence invariant](references/auto-cross-cutting.md#boot-sequence-invariant--init-must-run-before-the-first-dispatch-and-the-first-render).
2. **Seed via the frame's `:initial-events`, not a top-level `app-db` poke (M-15).** `:initial-events` run inside the frame's own scope, sidestepping the no-frame-context trap; seed a literal app-db with the standard `[:rf/set-db {…}]` event. The v1 top-level `(reset! re-frame.db/app-db …)` is also an M-1 off-contract-ns site. → [`references/guided-handlers-state.md` §M-15](references/guided-handlers-state.md#m-15--top-level-app-db-seeding).
3. **A wholesale `{:db fresh}` replace is now safe — but strip any `:rf/runtime` key (M-15b).** Framework runtime moved to a separate runtime-db partition a `:db` return can't touch, so the v1 "wholesale boot wipes the runtime" footgun is gone; the residual hazard is that a `:db` carrying the retired `:rf/runtime` app-db root throws `:rf.error/legacy-runtime-root` (loud). → [`references/guided-handlers-state.md` §M-15b](references/guided-handlers-state.md#m-15b--wholesale-app-db-replace--the-retired-rfruntime-root).
4. **A singleton boot-machine needs an explicit start + address.** If you converted v1 boot orchestration (`async-flow-fx`) to a machine (O-16), eager-start it in the boot fn and address it by `:system-id`. → [`spec/Pattern-Boot.md` §Worked example](../../spec/Pattern-Boot.md#worked-example--the-singleton-boot-machine); conversion mechanics in [`references/async-flow-to-machines.md`](references/async-flow-to-machines.md).

Facts #1 and #4 are **silent at compile** — fact #1 is also on the Phase 4 silent-failure checklist. Fact #3 is now **loud** (a `:rf/runtime`-bearing `:db` hard-errors with `:rf.error/legacy-runtime-root`); verify the silent ones with the boot smoke-test, not the compiler.

**The public multi-frame model is `image -> frame -> event stream`.** A v1 app has one global registrar and one implicit context; v2's public model for any app that needs *more than one* isolated context is: an **image** is the selected registration set a frame runs (its instruction set), a **frame** is the isolated execution context (its memory), and the **event stream** is the program. The v1 global registrar + implicit context becomes an explicit `rf/init!` plus one registered frame established as a scope (the M-40 / M-15 boot above is all you wire) — for a single-frame migration target you do not need to name an image: the ordinary `reg-*` path writes the default registration source and your one frame resolves the implicit default image over it. Reach for explicit images (`(rf/image {:select-ns {:include [...]}})` supplied via a frame's `:images`) only when the app genuinely runs two isolated surfaces on one page, or wants isolated test/story frames. The authoring detail lives in the `re-frame2` skill's `references/fundamentals/frames.md`. (If a v1 codebase you are migrating happens to carry pre-alpha `rf/app` / `rf/module` / `rf/realm` / `rf/install!` names — these were never the re-frame v1 model — do not adopt them: consult the EP-0023 §Backwards Compatibility migration mapping (the `rf/migration-map` / `rf/migration-explain` facade reads were removed under EP-0023/EP-0024) rather than this skill.)

## Kickoff (paste-ready)

For delegating the migration to a fresh Claude session: [`references/kickoff-prompt.md`](references/kickoff-prompt.md). The author drops it into a session opened in the root of their v1 project; the session loads this skill and walks the two pre-flight phases plus six phases, surfacing Type B checkpoints.

## Done checklist

- [ ] Inventory-and-plan produced (Phase 0a): every v1 re-frame add-on (incl. transitives, git/source deps, vendored) and every v1 re-frame app feature inventoried, each add-on's source scanned for v2-broken surfaces (off-contract `re-frame.*`, removed `console`, removed `re-frame.core/unwrap` `:refer`, React-19 coupling, classpath collision), and a per-item plan written (item → rule(s) → forced-vs-optional → disposition → replacement target → ordering).
- [ ] React-19 / Reagent-2 floor gate cleared (Phase 0b): downstream React-lib compat audited, component-library React-19 support confirmed (a *declared* React-19/Reagent-2 release **or** an *empirically-verified* runtime pass under forced React 19), legacy `ReactDOM.render` call sites flagged, CLJS/shadow-cljs/Closure toolchain skew checked (shadow-cljs bumped to the reference version if older), go/no-go recorded.
- [ ] `re-frame/re-frame` removed from every dep file; `day8/re-frame2` + adapter at a matching VERSION.
- [ ] Project compiles cleanly with re-frame2 on the classpath.
- [ ] Every tripped M-rule has been applied (Type A) or resolved by the author (Type B).
- [ ] Existing test suite passes (or fails identically to pre-migration — no new failures introduced).
- [ ] **Boot smoke-test passed (Phase 4 step 3) — "compiles" is not the done-bar.** App booted in a dev build; live `app-db` + machine snapshots read (runtime-db `[:rf.runtime/machines :snapshots]` carries every expected boot/singleton machine); first-screen subs deref to real values (not `nil`); one event per feature surface dispatched and the affected slot re-read; boot trace scanned for `:rf.error/*` / `:rf.warning/*` (incl. the hard error `:rf.error/legacy-runtime-root` if a handler still writes a `:rf/runtime` app-db root). None of the silent-runtime-failure modes ([`references/runtime-smoke-test.md`](references/runtime-smoke-test.md)) present.
- [ ] **Xray replaces 10x** — for a project that used `day8.re-frame/re-frame-10x`: 10x dep + preload dropped at M-0, Xray dep + preload + `[data-rf-xray-host]` host + the npm peer-deps (`@xyflow/react`, `elkjs` — compile-time deps of the Machine-inspector chart) wired post-M-40, panel verified (`Ctrl+Shift+C`). The app is **on Xray**. (No re-frame-10x in the project ⇒ nothing to check; don't add devtools the app never had.)
- [ ] Migration report (per `MIGRATION.md` Part 2 / `references/output-format.md`) produced and shared.
- [ ] Items flagged for human review are explicitly listed in the report.

Hand off: *"Migration complete. Switch to **`re-frame2`** for new application code, or **`re-frame2-pair`** for live inspection. The opt-in modernisations (`O-N` rules) are available whenever you want them — not required to be on v2."*

## Reference files (all one level deep)

- [`references/kickoff-prompt.md`](references/kickoff-prompt.md) — fresh-session kickoff prompt.
- [`references/inventory-and-plan.md`](references/inventory-and-plan.md) — Phase 0a: inventory add-ons + app features, scan their source for v2-broken surfaces, produce the per-item plan.
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
- [`references/error-events.md`](references/error-events.md) — pointer to Spec 009's error-event catalogue (single source); load when wiring error observability listeners.
- [`references/causal-world-inputs.md`](references/causal-world-inputs.md) — EP-0010 recording rule + EP-0017 reshape (M-72): durable ambient host reads → declared recordable coeffects. Silent failure — caught by an up-front grep, not the boot smoke-test.
- [`references/output-format.md`](references/output-format.md) — migration-report shape with worked examples.
- [`references/runtime-smoke-test.md`](references/runtime-smoke-test.md) — Phase 4 detail: the silent-runtime-failure checklist + the boot smoke-test loop with live `app-db` introspection.

## Anti-patterns

- **Don't apply Type B rewrites silently** — the Type A / Type B distinction exists precisely because Type B changes can break working code (e.g. M-3 run-to-completion semantics).
- **Don't bump *unrelated* dependencies** — keep the diff to what the migration forces. The **exemption is explicit**: the Phase-0b GO-state floor/toolchain edits ride into the M-0 pass — React → 19, Reagent → 2.x (and Reagent only if the project pins it directly), the component-library bumps the floor gate approved, the shadow-cljs (Check 4) bump, and any explicit ClojureScript pin — plus the Xray npm peer-deps (`@xyflow/react`, `elkjs`) when the 10x→Xray swap is triggered. Those are *forced by the migration*, not opportunistic upgrades. What stays banned: bumping a random utility/UI dep "while you're in there." Record every non-re-frame dependency you changed, and which gate justified it, in the migration report (cardinal rule 3 + [`output-format.md`](references/output-format.md)).
- **Don't add `-schemas` / `-machines` / `-routing` "to be safe"** — the artefact split is pay-as-you-go (M-27 through M-32).
- **Don't migrate plain-Reagent fns to `reg-view` reflexively** — that's O-2 (opt-in). But know the actual contract (M-11): under Reagent a plain `(defn …)` fn carries no `:contextType` wiring, so it **cannot read the surrounding `frame-provider`'s frame** — a bare `rf/subscribe` / `rf/dispatch` in it fails loudly with `:rf.error/no-frame-context` (EP-0002; no `:rf/default` fall-through). A `reg-view`-registered view DOES read the provider frame. So the fix for a plain fn that needs the frame is `reg-view` *or* a captured `(rf/capture-frame)` *or* a `with-frame` scope of its own; only a callback that escapes the render scope is a *separate* (and additive) async-frame-loss hazard. (A plain fn that establishes its own scope, or never touches the frame, needs no change.)
- **Don't touch `re-frame-test` namespaces eagerly** — renamed to `re-frame.test-support` (M-25); apply as a mechanical pass. Don't rewrite test bodies unless they trip a separate rule.
- **Don't treat a clean compile as "done."** The done-bar is a clean boot smoke-test, not a clean compile — v2 relocates a class of failures to runtime that only a live-introspection boot smoke-test catches (Phase 4 above; [`references/runtime-smoke-test.md`](references/runtime-smoke-test.md)).
- **Don't claim "migrated" before the report is written** — the report is the contract.

(The "announce before a mass rewrite" and "author runs builds/tests, not the skill" rules are Cardinal rules 4 and 5 — owned there, not restated here.)

---

*Authoritative breaking-change list: [`MIGRATION.md`](../../migration/from-re-frame-v1/README.md). v1 line: [re-frame](https://github.com/day8/re-frame). Full skill-disambiguation matrix: [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source).*
