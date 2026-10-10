---
name: re-frame-migration
description: >
  Migrates a re-frame v1.x ClojureScript codebase to re-frame2 in place: swaps
  re-frame/re-frame for day8/re-frame2 + a view adapter, applies the
  mechanical (Type A) rules, runs the project's own gates, and holds every
  judgment-call (Type B) site for the author. Use when asked to migrate or upgrade a re-frame v1 app, explain v1-to-v2
  breaking changes, or repair post-upgrade failures involving re-frame.db,
  dispatch-with, reg-event-db / reg-event-fx, reg-global-interceptor,
  reg-sub-raw, legacy sub sugar, ^:flush-dom, re-frame.alpha, re-frame-test, top-level
  :dispatch / :dispatch-n keys, http-fx / :http-xhrio, async-flow-fx,
  re-frame-10x. Not for new re-frame2 code (re-frame2), greenfield setup
  (re-frame2-setup), Fresco views (reagent-fresco-migration), or a running
  app (re-frame2-pair).
allowed-tools:
  - Bash(rg *)
  # The project's OWN noninteractive install / compile / test gates, for every
  # supported build-tool shape (deps.edn / shadow-cljs / npm / Leiningen / bb) —
  # the routine wildcards skills/README.md §Published-skill allowed-tools
  # baseline blesses. Cardinal rule 5 owns what the skill does with them.
  # `clojure *` also runs the M-73 codemod, an ordinary `clojure -Sdeps … -M -m`
  # git-dependency run from the project's root that needs no re-frame2 checkout.
  - Bash(npm *)
  - Bash(npx *)
  - Bash(clojure *)
  - Bash(shadow-cljs *)
  - Bash(lein *)
  - Bash(bb *)
  - Bash(git -C * rev-parse *)
  - Bash(git -C * remote get-url *)
  - Bash(git -C * ls-tree *)
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

Takes a re-frame v1.x ClojureScript codebase to re-frame2. When it finishes, the project depends on `day8/re-frame2` + a substrate adapter, every mechanical (Type A) rewrite has been applied, every judgment call (Type B) has been decided by the author, the app has booted clean, and a report records what changed.

**What it does to the project.** It edits source and dependency files in place, in announced sweeps. It runs the project's own install, compile and test commands. It never decides a Type B site on its own: those are collected and put to the author in one batch. It files nothing upstream without the author's approval.

## The mental model

Most of a v1→v2 migration is M-0 (the dependency swap) plus mechanical rewrites; bump the dependency and a large part of the codebase already runs. The hazard is elsewhere: v2 moves a class of failures the compiler cannot see to runtime. A `{:db fresh}` boot carrying a retired `:rf/runtime` key throws `:rf.error/legacy-runtime-root`; a positional signal-fn `reg-sub` is rejected at namespace load; an unfolded top-level `:dispatch` refuses its whole event with `:rf.error/effect-map-shape`. All of them compile clean.

So the discipline is: **grep every site of the compile-silent rules up front** — the compile gives you no error to march towards — and then **smoke-test the booted app live**, reading `app-db` and machine snapshots. A clean compile starts verification; it does not end it. Two axes tell you what each rule needs: Type A/B (apply, or ask?) and loud/silent (will anything tell me if I miss it?).

The rule corpus — required M-rules and opt-in O-rules — is [`MIGRATION.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md). "MIGRATION.md" is this skill's shorthand for `migration/from-re-frame-v1/README.md`; that URL is the citation. Read it from a **pinned local checkout** of `day8/re-frame2` at an author-supplied path, verified by structure rather than by name ([`references/setup.md` §Pin the migration corpus before reading it](references/setup.md#pin-the-migration-corpus-before-reading-it)), not from GitHub at runtime, so the rules you apply match the version you are landing on. The corpus does not ship in this package, and this skill does not restate it.

## When not to use

Not for greenfield bootstrap, writing code on an already-v2 project, live-runtime inspection, porting re-frame2 itself, or spec / design-rationale reading — the full matrix is [`skills/README.md` §Skill routing — single source](https://github.com/day8/re-frame2/blob/main/skills/README.md#skill-routing--single-source).

Exit when the [done checklist](#done-checklist) is satisfied.

## Cardinal rules (the invariants)

1. **[`MIGRATION.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md) is the source of truth — don't invent rules.** Every rewrite cites a rule id (`M-N` or `O-N`), so the author can audit it. A call site that matches no rule and is not on the preserved list is flagged for the author, never guessed at. A genuinely ambiguous rule becomes an upstream `day8/re-frame2` issue ([`issue-filing.md`](references/issue-filing.md)); don't edit `MIGRATION.md`.
2. **Type A is applied; Type B is asked first.** Type A is mechanical, unambiguous and observably identical. Type B depends on intent the code does not reveal — identify the site, explain the risk, and wait for an unresolved decision. Classify each **site**, not the rule: where the corpus splits a rule by case (M-16, M-42, M-71), a site shown to be in its mechanical case is Type A, and a site not shown to be stays held. A held decision holds only the sites that depend on it — M-0's exclusion of a v1 `re-frame/re-frame` edge proceeds while the author decides what becomes of the library that carried it ([`setup.md` §Edge cases](references/setup.md#edge-cases)). Apply decisions the author has already supplied for that site or rule; include them in the report rather than asking again. Revisit a decision only when new evidence changes its consequences. (The loud/silent axis in [`breaking-changes.md`](references/breaking-changes.md) tells you how a miss will surface, not whether to ask.)
3. **Smallest correct diff.** No style refactors, no renames the author didn't ask for, no new features (frames, schemas, machines, `reg-view`) unless the author asked for the O-rules. Apply M-rules in order, M-0 first. JVM interop (`.clj` test runners, fixtures) is in scope.
4. **Announce, then sweep.** Type A rewrites edit source in place, so before a multi-file sweep post the rule (*"M-8 — fold top-level `:dispatch` into `:fx`"*), the number of files matched and a one-line example diff, then proceed. The request to migrate already authorised Type A work; the announcement lets the author scope-limit a sweep they can see coming, and needs no second acknowledgement. Unresolved Type B decisions go to the author in **one batch** at the end of the sweep ([`sequencing.md`](references/sequencing.md)), not one interruption per site.
5. **Run the gates you discover.** Find the project's own noninteractive install / compile / test commands (`deps.edn` aliases, `shadow-cljs.edn` builds, `package.json` scripts, `project.clj`, `bb.edn`, the README), run them, and record each command and result in the report — never hand the author a command to run and paste back. The host agent's permission model governs execution; run nothing destructive, privileged, deploy-shaped or unrelated to the migration. The one gate that may need a person is the Phase-4 boot smoke-test: drive it yourself when a runtime is connected (a `re-frame2-pair` MCP, a shadow-cljs nREPL); otherwise hand over the checklist in [`runtime-smoke-test.md`](references/runtime-smoke-test.md) and report the smoke as **pending**, not the migration as complete. The corpus-provenance checks (`rev-parse` / `remote get-url` / `ls-tree`, [`setup.md` §Pin the migration corpus](references/setup.md#pin-the-migration-corpus-before-reading-it)), the `rg` inventories and the M-73 codemod (a `clojure` git-dependency run, [`auto-call-site-rewrites.md` §M-73](references/auto-call-site-rewrites.md#m-73--one-event-registration-form-reg-event)) run under the same allow-list.

## The migration workflow

Two pre-flight phases, then six. Each points to the leaf that holds the detail.

**Phase 0a — Inventory and plan.** Read-only, and before any dependency edit or compile: inventory the v1 add-on libraries and app features, scan the add-ons' **source** for removed or moved v2 surfaces, grep the app for the compile-silent rules, and write a per-item plan. A compile reaches broken dependency source one namespace at a time; planning the sweep up front replaces that march. → [`inventory-and-plan.md`](references/inventory-and-plan.md).

**Phase 0b — The React-19 / Reagent-2 floor gate.** Before any dependency edit. re-frame2's adapters target React 19 (the Reagent bridge runs on Reagent 2.x); for a React-17/18 + Reagent-1.x project this is the largest, riskiest step, and its blocking case should surface here rather than inside a failed compile. Run the leaf's checks and record its go/no-go; the bumps it approves ride into M-0. → [`floor-gate.md`](references/floor-gate.md#the-react-19--reagent-2-floor-gate-pre-flight--run-before-m-0).

**Phase 1 — Orient.** Read the project's dependency file (`deps.edn` / `project.clj` / `shadow-cljs.edn` / `bb.edn`), `MIGRATION.md` Part 1, and the shape of the test suite. → [`setup.md`](references/setup.md).

**Phase 2 — Swap the dependency (M-0).** Replace `re-frame/re-frame` with `day8/re-frame2` + a substrate adapter (`day8/re-frame2-reagent` unless told otherwise) at the author-supplied version — never an auto-picked "latest". Carry every Phase-0b bump into the same pass. Then install, apply the plan's approved forced blocker fixes, prove the classpath clean, and make the first compile attempt — a diagnostic that need not pass ([`sequencing.md` §The first compile](references/sequencing.md#the-first-compile)). → [`setup.md`](references/setup.md).

**Devtools — 10x → Xray.** If Phase 0a found `day8.re-frame/re-frame-10x`, replacing it with Xray is a required deliverable with no `M-N` id, so nothing in the rule sweep will remind you. Track it as one two-stage item: drop the dead 10x preload at M-0 so the compile can run, mount Xray after M-40. Done means the app is on Xray. Without 10x, Xray is optional. → [`xray-replaces-10x.md`](references/xray-replaces-10x.md).

**Phase 3 — Apply the planned sweep.** Apply the rest of the Phase-0a plan whether or not Phase 2's compile attempt passed — the compile-silent rules and M-70 never show up as compile failures, and a failed attempt holds only the rows its errors touch. If the attempt or the tests surfaced failures, walk those rules too. Two items are forced:

- **v1 add-ons do not compile on v2.** `http-fx`, `async-flow-fx`, `undo` and `forward-events-fx` fail the build as soon as re-frame2 is on the classpath; convert or remove each before the first compile attempt. The idiomatic replacement is the opt-in Phase-5 step. → [`breaking-changes.md` §v1 add-on libraries](references/breaking-changes.md#v1-add-on-libraries-fail-to-compile-on-v2--replacementremoval-is-forced-not-opt-in).
- **Every subscribing plain-fn view needs a frame (M-11).** A bare `subscribe` / `dispatch` in a `(defn …)` view's render raises `:rf.error/no-frame-context` — compile-clean, crashing at first render. It is usually the largest conversion in the migration: size it in Phase 0a and convert subscribers only. → [`guided-views-m11.md`](references/guided-views-m11.md#executing-the-conversion-subscribing-views-from-defn-to-reg-view).

Walk the rules in [`sequencing.md`](references/sequencing.md) order and find each recipe through the [reference map](#reference-map--load-on-demand). If Phase 0a found pre-release-v2 surfaces (the `*-listener!` verbs, `:invoke` machine keys, the legacy frame-affordance family), the codebase is a pre-release v2 build and [`pre-rename-upgrades.md`](references/pre-rename-upgrades.md) governs.

**Phase 4 — Verify.** A clean compile means the rewrites parse, not that the app boots: several clean-compiling rewrites fail at boot or first dispatch ([`silent-runtime-failures.md`](references/silent-runtime-failures.md)). [`runtime-smoke-test.md`](references/runtime-smoke-test.md) owns the boot smoke-test loop and [the gates completion requires](references/runtime-smoke-test.md#the-done-bar-is-more-than-the-local-dev-build). They are not a run order: run each as soon as its own inputs exist, report each result separately, and keep one that cannot run yet **pending**, naming the input it lacks ([§Required to finish is not required to run](references/runtime-smoke-test.md#required-to-finish-is-not-required-to-run)). When a gate fails, find the rule, apply it, re-run the gate.

**Phase 5 — Opt-in modernisations (only when asked).** Walk the `O-N` rules. The highest-value ones replace the add-ons Phase 3 forced you to act on: O-16 (`async-flow-fx` → `reg-machine` state machines, [`async-flow-to-machines.md`](references/async-flow-to-machines.md)), O-17 (`http-fx` / `:http-xhrio` → `:rf.http/managed`, [`http-fx-to-managed-http.md`](references/http-fx-to-managed-http.md)), and O-18 (security and logging sweep over the M-13 / M-17 observer sites). `shipclojure/re-frame-query` and hand-rolled RemoteData caches can move to re-frame2 resources (`reg-resource`, Spec 016) — no `O-N` id, its own guide: [`re-frame-query-to-resources.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/re-frame-query-to-resources.md). Never apply an O-rule unasked.

**Phase 6 — Report.** Produce the report per `MIGRATION.md` Part 2 §"Output format for your report" → [`output-format.md`](references/output-format.md).

## Scaling to a large migration (opt-in)

The phases assume one session, which suits most migrations. When Phase 0a finds ~30+ source files with rule families colliding inside the same files, split work goes wrong without a scheduling layer: merge conflicts and silent reverts. [`orchestrating-a-large-migration.md`](references/orchestrating-a-large-migration.md) covers the one-file-one-owner partition, the Wave-0 id contract, producer retargeting through each event's owner, wave sequencing and the single post-sweep compile gate. It schedules the Phase-3 sweep rather than replacing the phases, and the partition plan doubles as the cardinal-rule-4 announcement.

## Boot and init

v2 changes app boot structurally, so boot is where migrations hit the most friction. Get these four facts right together:

1. **`init!` + an app frame come first (M-40) — a v1 app has neither.** `(rf/init! <adapter>)` installs the runtime but creates no frame. Create an explicit app frame and establish it as a scope (`make-frame` + `with-frame` / `frame-provider {:frame …}`, or mount the root under `frame-root {:id …}`) before the first `dispatch` or render; a scope-less boot dispatch throws `:rf.error/no-frame-context`. → [`auto-cross-cutting.md` §Boot-sequence invariant](references/auto-cross-cutting.md#boot-sequence-invariant--init-must-run-before-the-first-dispatch-and-the-first-render).
2. **Seed through the frame's `:initial-events` (M-15)**, e.g. `[:rf/set-db {…}]`, not a top-level `(reset! re-frame.db/app-db …)` — they run inside the frame's scope. → [`guided-handlers-state.md` §M-15](references/guided-handlers-state.md#m-15--top-level-app-db-seeding).
3. **A wholesale `{:db fresh}` replace is safe — strip any `:rf/runtime` key (M-15b).** Framework runtime lives in a separate partition a `:db` return cannot touch; a `:db` carrying the retired `:rf/runtime` root throws `:rf.error/legacy-runtime-root`. → [`guided-handlers-state.md` §M-15b](references/guided-handlers-state.md#m-15b--wholesale-app-db-replace--the-retired-rfruntime-root).
4. **A singleton boot machine needs an explicit start and address.** If O-16 turned boot orchestration into a machine, start it from the frame's `:initial-events` (the reserved `:rf.machine/start` marker) and address it by its registered id — `(rf/dispatch [:app/boot …])`; a singleton is not `:spawn`ed. → [`spec/Pattern-Boot.md` §Worked example](https://github.com/day8/re-frame2/blob/main/spec/Pattern-Boot.md#worked-example--the-singleton-boot-machine) and the O-16 corpus companion [`async-flow-fx-to-reg-machine.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/async-flow-fx-to-reg-machine.md).

Facts 1 and 4 are silent at compile and fact 3 is loud at runtime; the boot smoke-test is what confirms them.

**One v1 habit fails frame creation.** v1 let a later registration of an id replace an earlier one, so an application namespace could override a library's. A single-frame target names no image and resolves the default one, which refuses two namespaces registering one kind and id with `:rf.error/image-duplicate-id`. So inventory duplicates before boot: fix an accidental one at its source, and keep an intended override — the definition v1 actually ran, the last one loaded — in a later image. The ways out are [`docs/core/images.md` §When two registrations collide](https://github.com/day8/re-frame2/blob/main/docs/core/images.md#when-two-registrations-collide); composing the override image is the `re-frame2` skill's [`images.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/references/fundamentals/images.md#composing-patterns).

## Reference map — load on demand

Load a leaf when its phase or rule comes up; none needs reading up front.

| Load | When |
|---|---|
| [`inventory-and-plan.md`](references/inventory-and-plan.md) | Phase 0a — the add-on source scan, the app-source grep for silent rules, the per-item plan and the per-rule completeness gate. |
| [`floor-gate.md`](references/floor-gate.md) | Phase 0b — the React-19 / Reagent-2 checks and the go/no-go. |
| [`setup.md`](references/setup.md) | Phase 1–2 — pinning the corpus checkout, the M-0 coord swap per build tool, picking the adapter, the version. |
| [`xray-replaces-10x.md`](references/xray-replaces-10x.md) | The project uses re-frame-10x — the two-stage swap to Xray. |
| [`breaking-changes.md`](references/breaking-changes.md) | Any "is `X` covered by a rule?" question — the index of every M-/O-rule by v1 trigger surface, the façade-removal registers, Type A/B and loud/silent at a glance. |
| [`sequencing.md`](references/sequencing.md) | Phase 3 — the sweep order, the end-of-sweep Type-B batch, resuming an interrupted migration. |
| [`auto-call-site-rewrites.md`](references/auto-call-site-rewrites.md) | Per-call-site recipes — ns requires (M-1, M-23, M-25, M-38), subscriptions (M-75, `:->` / `:=>`), effect maps (M-8), fx arity (M-51), dispatch shapes (M-4, M-9, M-16), the `reg-event` codemod (M-73), the test layer. |
| [`auto-cross-cutting.md`](references/auto-cross-cutting.md) | Cross-cutting recipes — keyword renames (M-20), interceptor cleanup (M-21, M-70), views (M-22, M-24), M-26 drops, `init!` and the boot sequence (M-40), per-feature artefact adds (M-27–M-33). |
| [`guided-handlers-state.md`](references/guided-handlers-state.md) | M-3, M-5, M-10, M-12, M-13, M-14, M-15, M-15b, M-34, M-42. |
| [`guided-views-m11.md`](references/guided-views-m11.md) | M-11 — subscribing plain `defn` views → `reg-view`, and the async listener / timer class. |
| [`guided-interceptors-subs.md`](references/guided-interceptors-subs.md) | M-17, M-18, M-19, M-21, M-23, M-26, M-71. |
| [`causal-world-inputs.md`](references/causal-world-inputs.md) | M-72 — `inject-cofx` removal and which host reads (`Date.now`, `random-uuid`, `localStorage`, …) must become recorded coeffects. |
| [`error-events.md`](references/error-events.md) | Wiring error observability after M-13 / M-17 / M-26: the production `:observability :errors` sink versus the dev-only `:trace` listener. There is no frame-level `:on-error` recovery policy. |
| [`silent-runtime-failures.md`](references/silent-runtime-failures.md), [`runtime-smoke-test.md`](references/runtime-smoke-test.md), [`release-compile-gate.md`](references/release-compile-gate.md) | Phase 4 — what compiles clean and still breaks, the boot smoke-test loop, the optimized compile. |
| [`async-flow-to-machines.md`](references/async-flow-to-machines.md), [`http-fx-to-managed-http.md`](references/http-fx-to-managed-http.md) | Phase 5 (or the forced Phase-3 add-on step) — routers to the O-16 / O-17 corpus guides. |
| [`output-format.md`](references/output-format.md) | Phase 6 — the report format with a filled-in example. |
| [`orchestrating-a-large-migration.md`](references/orchestrating-a-large-migration.md) | ~30+ source files with colliding rule families — partitioning and wave sequencing. |
| [`issue-filing.md`](references/issue-filing.md) | A rule is genuinely ambiguous and the author approves filing an upstream issue. |
| [`pre-rename-upgrades.md`](references/pre-rename-upgrades.md) | The codebase is a pre-release v2 build, not v1 (the `*-listener!` verbs, `:invoke` machine keys, the legacy frame-affordance family). |
| [`kickoff-prompt.md`](references/kickoff-prompt.md) | Delegating the migration to a fresh session opened in the v1 project's root. |

## Done checklist

- [ ] Phase 0a plan written and its [per-rule completeness gate](references/inventory-and-plan.md#step-5--the-per-rule-completeness-gate) passed.
- [ ] Phase 0b go/no-go recorded ([`floor-gate.md` Check 6](references/floor-gate.md#check-6--explicit-go--no-go)).
- [ ] `re-frame/re-frame` removed from every dependency file; `day8/re-frame2` + adapter at one matching version.
- [ ] Every tripped M-rule applied (Type A) or decided by the author (Type B).
- [ ] The test suite passes, or fails exactly as it did before the migration.
- [ ] Every Phase-4 gate ([`runtime-smoke-test.md` §The done-bar](references/runtime-smoke-test.md#the-done-bar-is-more-than-the-local-dev-build)) passed, or reported **pending** with the input it lacks.
- [ ] For a 10x project, **both** halves of the [Xray swap](references/xray-replaces-10x.md) landed; dropping the dead preload alone leaves the author with no devtools. (No 10x → nothing to check.)
- [ ] Report written per `MIGRATION.md` Part 2 / [`output-format.md`](references/output-format.md), with every item held for the author listed.

Report any unresolved Type B site or pending gate as remaining work, with the next action; the migration remains incomplete. Once the [done checklist](#done-checklist) is satisfied, hand off: *"Migration complete. Switch to **`re-frame2`** for new application code, or **`re-frame2-pair`** for live inspection. If you want Fresco views, **`reagent-fresco-migration`** is the optional second step — staying on Reagent is a first-class, fully-supported choice. The opt-in modernisations (`O-N` rules) are available whenever you want them — not required to be on v2."*

## Anti-patterns

- **Applying a Type B rewrite silently.** Type B exists because those changes can break working code (M-3's run-to-completion drain, for one); asking is cheaper than rolling back.
- **Bumping unrelated dependencies.** Keep the diff to what the migration forces. The bumps the Phase-0b floor gate approved ride into M-0, as do the Xray npm peer-deps when the 10x swap applies; `MIGRATION.md` Part 2 carries the same carve-out. Record every non-re-frame dependency you changed, and the gate that justified it, in the report.
- **Adding `-schemas` / `-machines` / `-routing` "to be safe".** The artefact split is pay-as-you-go (M-27–M-33).
- **Converting plain Reagent fns to `reg-view` reflexively.** That is O-2, opt-in. The forced case is M-11, a *subscribing* plain `defn` ([`guided-views-m11.md`](references/guided-views-m11.md)); a non-subscribing fn needs no change. (A callback that escapes the render scope is a separate async hazard.)
- **Rewriting test bodies eagerly.** `re-frame.test` → `re-frame.test-support` (M-25) is a mechanical pass; touch a test body only when it trips another rule.
- **Treating a clean compile as done**, or claiming "migrated" before the report is written — the smoke-test is the done-bar and the report is the contract.

---

*Rule corpus: [`MIGRATION.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md). v1: [re-frame](https://github.com/day8/re-frame). Skill routing: [`skills/README.md` §Skill routing — single source](https://github.com/day8/re-frame2/blob/main/skills/README.md#skill-routing--single-source).*
