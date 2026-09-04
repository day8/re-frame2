# Architecture census — the kill rules, held or not

**rf2-hic-087**, 2026-08-14. Three censuses against the finished package, each published with the command that produced it so the final audit ([`rf2-hic-064`](correction-ledger.md)) can re-derive rather than trust.

The [decision brief](decision-brief.md#part-iii--the-plan) states four kill rules about mechanism and one about tooling, and every one of them is a claim about *absence*. Absence is the hardest thing to publish honestly, because the evidence for it is a search that found nothing — and a search that found nothing is indistinguishable from a search that was never going to find anything. So each arm below is recorded with what it excluded and what a looser form of the same search would have returned.

| Census | The claim | Result |
|---|---|---|
| [1. Mechanism](#1-the-mechanism-census) | No ViewCell-class dependency graph, no second emitter, no compiled-hiccup mode, no per-boundary callback-cell table | **Held.** Four recognisers, zero hits |
| [2. Retained tooling](#2-the-retained-tooling-census) | Every retained tool or diagnostic surface names its real daily consumer | **Held, with one obligation filed — and since discharged.** 36 rows — 26 mechanically derived, 10 by hand; every row names a consumer. The condition that had fired is now answered (`rf2-wehh0`, PR #8272), and the one checker no lane ran is now a required CI job (`rf2-st1x5`, PR #8279); both cells carry amendments below |
| [3. Mutable globals](#3-the-post-kernel-mutable-global-re-sweep) | Later work introduced no unjustified global | **Held, and the page that records it is short.** 14 commits and 1,200 inserted lines of runtime since the roster was taken added **zero** owners; the roster's own derivation is missing an arm and its second table is missing a row |

Two corrections are filed against [`globals.md`](globals.md) and one against [`prototype-suite-triage.md`](prototype-suite-triage.md); they are in [the ledger](correction-ledger.md#the-ledger) and listed [below](#what-was-filed).

## How to read a census on this tree

Two rules, both learned on this tree by the bead this one depends on.

**Anchor the search, and say what the unanchored form returns.** [`release-scans.md`](release-scans.md)'s native-share census returned 2 anchored and 9 unanchored over one corpus, five of the extra hits being docstring prose and two being `:tags ["react"]` data. Hicasso's sources are unusually exposed to this: they carry very long docstrings that quote code, so `renderToString` appears 38 times in `implementation/hicasso/src` and **six** of those are calls. Every arm below is therefore run over the sources with **string literals and comments blanked out**, keeping line structure, and every count is given both ways.

That is a structural fix for a problem [`globals.md`](globals.md#what-the-searches-return-that-is-not-an-owner) currently answers by naming four docstring hits by hand. It also disposes of a trap this census fell into and had to back out of: **a Clojure predicate name ends in `?`, which is a regex quantifier**, so `grep '\bscope?\b'` searches for `scop` followed by an optional `e` and reports `re-frame.hicasso.evidence/scope?` as dead code. It is not dead — it is `projection-fields`'s `:valid?` for the scope axis, referenced unqualified, three lines of the same file away. Anchoring alone would not have caught that; only re-reading the source did.

**A rebase can change a census rather than reconfirm it.** [`release-scans.md`](release-scans.md) rebased past 18 commits mid-bead, `re-frame.hicasso.server` landed, and one of its censuses changed outright — the sentence it replaced would have shipped false. Every figure on this page was re-taken on the base this branch merges from, and the third census is one where the tree really did move: `server.cljs` is 389 of the 1,200 lines counted below and it landed the day this census was taken.

**One figure moved between the two takes, and it moved because the first take was wrong.** The hook call-site count below read 14 and reads 16. Nothing in the tree changed — `implementation/hicasso/src` is byte-identical across the rebase — and the whole of the difference is the instrument: the first take used `git grep -E`, which on this checkout silently missed a hit that `rg` returns, and the count was then read off the screen by hand and undercounted by one more. Both arms of that are the reason every figure on this page is now taken with one tool and counted by `wc -l` rather than by eye. It is recorded rather than quietly corrected because a census that revises a number without saying so is asking to be believed on exactly the ground it just failed on.

## 1. The mechanism census

Four rejected mechanisms, from [`decision-brief.md`](decision-brief.md) and the [charter](../charter.md):

> **Kill rules**: no compiler or dual mode for the hiccup language (`n/$` is a visibly distinct second language, never a second mode of `[...]`), no ViewCell-class graph, no second emitter, for any gate.

and [`lanes/hot-path-architecture.md`](lanes/hot-path-architecture.md)'s fourth:

> No `:fast` flag, compiler fork, automatic promotion, profiling-dependent semantics, **per-boundary callback-cell table**, second state owner, or performance claim based only on render timing.

**Each is recognised by the thing it would need, not by the words it would be described in.** A gate that fired on the word *compiler* is a gate authors route around by rewording; a gate that fires on a seventh `defmacro` cannot be reworded, because a build-time pass over body forms has to be one.

### 1.1 No second emitter

An emitter of Hicasso semantics either **calls** a renderer that is not React's or **requires** a namespace that is one. Both are enumerable.

```sh
# M1a — every render or mount call site, with the alias it is called on.
#       The alias is the whole discriminator: the claim is not that no HTML
#       is produced, it is that React produces all of it.
rg -n "\((rdom|rdom-server|react-dom|dom)[a-z-]*/(renderToString|renderToStaticMarkup|renderToPipeableStream|renderToReadableStream|createRoot|hydrateRoot)\b" implementation/hicasso/src

# M1b — every require of a namespace that emits a view tree by another route.
rg -n "\[(re-frame\.ssr\.emit|re-frame\.ssr\.head|re-frame\.ui\.|re-frame\.freehand\.)" implementation/hicasso/src
```

**M1a returns 6, all on a `react-dom` alias; M1b returns 0.** The unanchored form of M1a — the bare tokens anywhere in the tree, `rg -n "renderToString|renderToStaticMarkup|createRoot|hydrateRoot"` — returns **38**, so five in six hits are prose. The six calls:

| Site | Call | Alias, and where it comes from |
|---|---|---|
| `impl/mount.cljs` ×2 | `createRoot` | `react-dom-client`, from `["react-dom/client" :as react-dom-client]` |
| `impl/mount.cljs` ×2 | `hydrateRoot` | as above |
| `server.cljs` ×2 | `renderToString` | `rdom-server`, from `["react-dom/server" :as rdom-server]` |

The two arms of one renderer, which is the design: `server.cljs` runs **this** runtime under `react-dom/server`, and its own opening says so — *"No JVM string emitter, no parallel hiccup walker."* Hydration parity holds by construction only while one runtime renders both halves, which is why [`naming-ledger.md`](naming-ledger.md) row 22 had to be corrected when it read *JVM-side*.

**What M1b excludes, and why it is not a hole.** `server.cljs` requires three `re-frame.ssr.*` namespaces — `constants`, `html-helpers`, `payload-policy` — and none is an emitter. `html-helpers` is an *escaping* library (`escape-html`, `escape-attr`, `escape-edn-script-body`) shared with the SSR emitter so that entity rules live in one place; the emitter itself is `re-frame.ssr.emit`, and nothing under `implementation/hicasso/src` requires it.

**One string of HTML in this package is not React's, and it is recorded rather than argued away.** `server/document` writes the document envelope — `<html>`, `<head>`, the `<div id="app">`, the payload `<script>` — by concatenation. That is not a second emitter under the specification's own term (*"Emitters of Hicasso semantics"*): it emits no view tree, no props and no hiccup, and every byte inside the app element comes from `renderToString`. It is named here because a census that only reports what it excluded on a technicality has not been checked.

### 1.2 No compiled-hiccup mode

The [charter](../charter.md#constraints) permits `defn`-class macro sugar with function fallbacks and forbids *"any build-time pass that classifies, lowers, or refuses body forms; any grammar; any proof system; any second body emitter."* In ClojureScript a build-time pass is a macro, so the macro roster is the census.

```sh
# M2 — every macro the package defines.
rg -n --sort path "^\s*\(defmacro\b" implementation/hicasso/src
```

**Anchored: 6. Unanchored (`rg -n "defmacro"`): 6** — this is the one arm where the two agree, because no docstring in the package quotes the word.

<!-- census:mechanism-macros -->

| Macro | Namespace | What it expands to | Why it is not a compiler |
|---|---|---|---|
| `defview` | `re-frame.hicasso` | a `defn` plus one `mint-view!` call | the body is an ordinary function; nothing reads its forms |
| `event` | `re-frame.hicasso` | an anonymous view, same shape | as above |
| `defhost` | `re-frame.hicasso` | a `def` of a `mint-host!` declaration | takes a data map, not body forms |
| `props` | `re-frame.hicasso.native` | a `#js` object literal | a literal, at one visible escape |
| `$` | `re-frame.hicasso.native` | one `react/createElement` | the visibly distinct second language the kill rule names, never a second mode of `[…]` |
| `defcomponent` | `re-frame.hicasso.native` | a `def` of a React function component | as `defhost` |

Six macros, six rows, and each captures a source coordinate for refusal messages. **Not one takes a body form as data.** The mode-flag half of the same rule is empty by inspection: `rg -n ":fast\b|:compiled\b|compile-mode|promotion|:optimize\b|analyzer"` returns three hits and all three are docstring prose saying there is no compiler and no analyzer.

### 1.3 No ViewCell-class graph, and no per-boundary callback-cell table

These are one census, because they are one shape. [`resource-demand-criteria.md` §C5](resource-demand-criteria.md) pre-registers the recogniser so it cannot be defined away later:

> A **second per-read ledger** is any retained structure that holds one entry per read, or per read-and-boundary pair, that has a lifecycle of its own requiring maintenance in step with commit and disconnect, and that can therefore drift from the committed read membership.

A callback-cell table is the same recogniser with *callback* in place of *read*. Both need a **retained structure that does not exist today**, so the arm is the module-level owner roster — [census 3](#3-the-post-kernel-mutable-global-re-sweep) — read a second way, and the gate treats it as one check rather than two. What the roster returns is thirteen tables, two counters, two caches, a dedupe map, two render-extent slots, two dynamic vars and one arming latch. Every one is either **shared** or **frame-keyed**; not one is keyed by boundary.

Three facts finish it, each checkable at source.

**The shell is two hooks and no `useRef`.** `impl/collector.cljs`'s `shell` calls `react/useContext` for the frame and `react/useSyncExternalStore` for the subscription epoch, in that order, and `shell-hook-ledger` declares exactly that pair so a third hook fails a test rather than a review. Anchored, `rg -n "\((react/|\.-)use[A-Z][A-Za-z]*"` finds **16** hook call sites in the whole package; unanchored, the bare hook names return **83**, so four in five are prose. The [HD-020](../architecture.md) ceiling is `≤ 2 per boundary` and *"a ViewCell-class per-boundary object graph appearing means the arm has failed"*.

**The two tables a boundary reaches are shared, and their keys say so.** `!cells` is keyed `[frame-kw query-v]` — one cell per *(frame, query)*, with a reader list, however many boundaries read it. `!entries` is keyed by an order-sensitive hash of the whole read *sequence*, so two boundaries with the same reads share one entry. Neither is a *second* ledger under C5's recogniser, and the distinction is not a technicality: C5's harm is a structure that can **drift from** the committed read membership, and these two **are** it. There is nothing for them to drift from.

**Callbacks are lowered per render and retained nowhere.** `impl/intent.cljs` holds no module-level table at all — its only module state is the two dynamic vars, which are render-extent context and `nil` outside it. `lower-prop`'s callback gate allocates one `volatile!` per lowered callback, inside the function, and it dies with the render. `impl.codec`'s `keywarn` is the one map keyed by anything callback-shaped, and it is a **dev-only console dedupe**, `nil` in production and folded away under `:advanced`.

## 2. The retained-tooling census

> Tools without daily consumers build no retained machinery. — [`decision-brief.md`](decision-brief.md#part-iii--the-plan)

**The population is mechanical for 26 rows and a judgement for 10.** The mechanical part is what the gate enforces: every checker under `implementation/hicasso/scripts/`, every npm script whose name contains `hicasso`, and every tool or diagnostic namespace in `implementation/hicasso/src`. The ten hand-added rows are the surfaces no naming rule reaches — the spikes, the testbeds, the kit, the lint export — and the judgement is which of them counts as retained machinery at all.

**"Real daily consumer" is read strictly.** A CI job that runs on every PR is one. A shipped tool panel is one. A suite in a standing lane is one. An operator command that a human runs when a decision needs re-reading is **named as what it is** rather than dressed up, and two rows below say so.

<!-- census:tooling -->

| Surface | What it is | Its real daily consumer | Verdict |
|---|---|---|---|
| ~~`implementation/hicasso/scripts/check_budget_ledger.py`~~ | ~~every budget line has a gate~~ | ~~`test:hicasso-invariants`, required `test.yml` job, every PR~~ — **retired 2026-08-30 in PR #8775 (`rf2-6c12m.8`)**: the script, its CI job and its chain entry are deleted with `budgets.md`'s demotion to design history; the ledger it reconciled was a closed programme record | ~~KEEP~~ RETIRED |
| `implementation/hicasso/scripts/check_bundle_isolation.cjs` | no `tools/` code in a production bundle | `build:hicasso-release`, `test.yml`'s `cljs` job | KEEP |
| ~~`implementation/hicasso/scripts/check_complaint_catalogue.py`~~ | ~~every complaint id has a Spec 009 row~~ | ~~`test:hicasso-invariants`, every PR~~ — **retired 2026-08-29 in PR #8753 (`rf2-6c12m.7`)**: the script and its CI job are deleted, and the repo-wide `scripts/check_keyword_catalogue_drift.py` reconciles every `:rf.error/*` id against Spec 009 and the emitters, in both directions | ~~KEEP~~ RETIRED |
| ~~`implementation/hicasso/scripts/check_example_fence_coverage.py`~~ | ~~every witness application carries an import fence~~ | ~~`test:hicasso-invariants`, every PR~~ — **retired 2026-08-29 in PR #8744 (`rf2-6c12m.10`)**: the script and its chain entry are deleted with the per-package `*surface-cljs-test*` suites whose presence it policed and the `examples/require_graph.clj` macro they read the ClojureScript analyzer through. One `:node-test` suite succeeds them, `re-frame.hicasso.examples.fence-cljs-test`, which derives its package population from the `examples/` directory on every run and reads each `ns` form with `cljs.tools.reader`, so the coverage question this checker answered — *is every package fenced?* — can no longer be asked. It is a four-family blocklist rather than a positive roster, and that narrowing is recorded on the pages that reported it (`rf2-60jv`, `rf2-cahl`) | ~~KEEP~~ RETIRED |
| ~~`implementation/hicasso/scripts/check_facade_inventory.py`~~ | ~~every facade export is classified~~ | ~~`test:hicasso-invariants`, every PR~~ — **retired 2026-08-30 in PR #8775 (`rf2-6c12m.8`)**: deleted with `dispositions.md`'s demotion; the 43-row surface → policy table beside the code is `implementation/hicasso/spec/server-policy.md`, ungated | ~~KEEP~~ RETIRED |
| `implementation/hicasso/scripts/check_guide_samples.py` | every hicasso verb a guide code block names resolves to a `def*` head in that namespace's source — **amended 2026-09-04 (`rf2-gn17`)**: this cell read *every guide code block hashes to its source* until `rf2-6c12m.9` (2026-08-29, landed on main as `0e77c4c519`) reduced the gate to the verbs-resolve rule and deleted its digest roster. The script's own docstring is now explicit — *"It pins nothing: a prose edit, or a sample edit that names no new verb, is a one-file change with no roster to regenerate"* — and it imports no hashing primitive at all | `test:hicasso-invariants`, every PR | KEEP |
| `implementation/hicasso/scripts/check_modules_compile.cjs` | the optional modules and the two core attribution instruments compile warnings-fatal | `test:hicasso-compile`, required `test.yml` `cljs` job, every PR | KEEP |
| `implementation/hicasso/scripts/check_modules_compile.test.cjs` | that gate's entry-source refusals fire | `test:script-helpers`, unconditional `js-harness-self-tests` job, every PR | KEEP |
| `implementation/hicasso/scripts/check_lint_export.py` | the clj-kondo export's fixtures still fire | `test:hicasso-lint`, required `lint.yml` `clj-kondo` job | KEEP |
| `implementation/hicasso/scripts/check_naming_census.py` | every public name in the package has a naming-ledger row — 105 names across ten shipped namespaces, read as code rather than grepped, and reaching the nine doors `check_facade_inventory.py` deliberately does not | a required CI job on every PR — `test.yml` runs its `--self-test` and then the real census, and `scripts/test-fast-pr.sh` runs both arms in the pre-checkin spine; behind that stand its own `--self-test` (10 checks, including a seeded public export seen to redden and then go green once rostered) and [`naming-packet.md`](naming-packet.md), whose §7 census it converts from a one-shot measurement into a standing one. **[Amended 2026-08-15, `rf2-uvazt`: this cell read *"not wired into a lane, and named as what it is"* until `rf2-st1x5` armed the checker in PR #8279.]** **[Amended 2026-08-30, `rf2-6c12m.8`: retired in PR #8775 — the script, its `hicasso-naming-census` job and its spine block are deleted with `naming-ledger.md`'s demotion to design history, every row of which was already dispositioned.]** | ~~KEEP~~ RETIRED |
| `implementation/hicasso/scripts/check_optional_module_reachability.py` | an absent optional module is zero reachable code | `test:hicasso-invariants`, every PR | KEEP |
| `implementation/hicasso/scripts/check_production_erasure.cjs` | dev-only machinery folds away under `:advanced` | `build:hicasso-release`, `test.yml`'s `cljs` job | KEEP |
| `implementation/hicasso/scripts/check_source_coord_elision.cjs` | source coordinates leave the production bundle | `test:browser-prod-elision`, `test.yml` + `expensive-tests.yml` | KEEP |
| `bench:hicasso` | the benchmark runner | the budget and ladder pages it feeds; **not a gate**, and `check_gate_scheduling.py` carries that disposition in writing | KEEP |
| `build:hicasso-release` | the release build plus its two bundle gates | `test.yml`'s `cljs` job, every PR | KEEP |
| `ssr:hicasso-bake` | bakes the prototype SSR corpus | `bake_bytes.test.cjs`, in `test:script-helpers`, every PR — the command itself is operator-run | KEEP, and see [the obligation below](#the-one-row-whose-condition-has-fired) |
| `ssr:hicasso-serve` | serves the prototype SSR entry | the same driver, the same byte test; the command is operator-run | KEEP, as above |
| `test:hicasso-compile` | the compile gate | `test.yml` + `scripts/test-fast-pr.sh`, every PR | KEEP |
| `test:hicasso-controlled` | the three-engine controlled-input testbed | required `cljs-hicasso-controlled` job, every PR | KEEP |
| `test:hicasso-hmr` | 36 real hot reloads through `shadow-cljs watch`, three engines | required `cljs-hicasso-hmr` job, every PR | KEEP |
| `test:hicasso-invariants` | the seven Python checkers, chained | required `test.yml` job, every PR | KEEP |
| `test:hicasso-lint` | the clj-kondo export gate | required `lint.yml` job + `test-fast-pr.sh` | KEEP |
| `witness:hicasso-native-ime` | a real Windows IME, driven from a script | **not a CI gate and cannot be one** — its own header says so and names the standing synthetic witness (`testbed/spec.cjs`) that is; its `--self-test` runs in `test:script-helpers` every PR | KEEP |
| `re-frame.hicasso.evidence` | the adapter-neutral evidence schema | `tools/xray` (three panels), `tools/re-frame2-pair-mcp` (two wire tools), `evidence_schema_cljs_test`; **all 20 public vars consumed** | KEEP |
| `re-frame.hicasso.tool` | the four door reads | `tools/xray/panels/hicasso_reads.cljs`, `re-frame2-pair-mcp/tools/hicasso_tool.cljs`, `mcp-conformance` end-to-end | KEEP |
| ~~`re-frame.hicasso.impl.evidence`~~ | ~~the one-line sink seam~~ | ~~`impl/collector.cljs` taps it; the bench `arm1` suites attach to it~~ — **retired 2026-08-29 in PR #8745 (`rf2-6c12m.17`)**: the namespace and the collector's two taps are deleted; nothing in src attached, and the Xray projection `re-frame.hicasso.evidence` (its own row above) reads the collector's tables directly | ~~KEEP~~ RETIRED |
| ~~`re-frame.hicasso.impl.inventory`~~ | ~~the declared and measured retained census~~ | ~~20+ package and bench suites, all in the always-on `:node-test` lane~~ — **retired 2026-08-29 in PR #8745 (`rf2-6c12m.17`)**: the census readers now live on the test kit's runtime door, `re-frame.hicasso.test.runtime` (`stats`, `residue`, `quiesced!`), outside the artefact's published `:paths`, and the same suites read them there | ~~KEEP~~ RETIRED |
| `implementation/hicasso/test_kit/` | `re-frame.hicasso.test`, the public test kit | 40+ package suites plus four guide chapters, whose named `ht/` and `hm/` verbs `check_guide_samples.py` resolves against this source — a naming check that pins no content, per its row above | KEEP |
| `implementation/hicasso/testbed/spec.cjs` | the controlled-input smoke, three engines | `test:hicasso-controlled` | KEEP |
| `implementation/hicasso/testbed/hmr_spec.cjs` | the hot-reload smoke, three engines | `test:hicasso-hmr` | KEEP |
| `implementation/hicasso/testbed/native-ime-witness.cjs` | the IME witness's page half | `witness:hicasso-native-ime`, operator-run | KEEP |
| `implementation/hicasso/resources/clj-kondo.exports/` | the published lint export | every consumer's `.clj-kondo`, and this repo's own; gated by `check_lint_export.py` | KEEP |
| `bench/hicasso/ssr/` (the SSR spike, **graduated**) | the prototype the shipped `server.cljs` is the product form of | five suites in the `:node-test`/`:browser-test` lanes; the driver's bytes in `test:script-helpers` | KEEP, with [an obligation](#the-one-row-whose-condition-has-fired) |
| `capsule_spike_cljs_test.cljs` (**stopped**) | the replayable-capsule spike | [`capsule-replay-verdict.md`](capsule-replay-verdict.md) — the reproduction its STOP verdict rests on | KEEP as evidence |
| `pull_reads_spike_cljs_test.cljs` (**stopped**) | the pull-shaped-reads spike | [`pull-shaped-reads-verdict.md`](pull-shaped-reads-verdict.md), likewise | KEEP as evidence |
| `genspike_cljs_test.cljs` (**stopped**) | the schema-driven generative spike | its own DO-NOT-GRADUATE verdict; its docstring states the case — *"a negative verdict is worth only as much as its reproduction"* | KEEP as evidence |
| `mcp_runtime_query_spike_cljs_test.cljs` (**part-graduated**) | the MCP runtime-query spike | [`mcp-runtime-query-spike.md`](mcp-runtime-query-spike.md); three of its doors ship as wire tools today | KEEP |

**Thirty-six rows, no removals.** That is the honest result and not a comfortable one to report, because a census whose every row passes invites the suspicion that the bar moved. Two things carried it: the npm-script half of the population is **already** gated — `scripts/check_gate_scheduling.py` asks every `test:`/`bench:`/`build:` command where it runs and currently reports *51 gate commands, 43 scheduled, 8 declared, 0 of them known holes* — and the four stopped spikes are retained under an explicit rule, that a negative verdict without its reproduction is unfalsifiable.

The rows that reach furthest are the ones this census would have removed if the bar were "runs in CI": `witness:hicasso-native-ime` and `ssr:hicasso-serve` are operator commands. Both are kept, and the cells say why rather than claiming a job that does not exist — which is the whole discipline of the Consumer column.

**[Amended 2026-08-15, `rf2-uvazt`.]** A third row stood here when this census was taken: `check_naming_census.py`, then a checker no lane ran, whose cell named `rf2-st1x5` as the bead that would change its answer rather than writing the answer it expected to have. `rf2-st1x5` landed in PR #8279 while this page's own closure re-run was in flight, so the row no longer reaches — the checker is a required CI job on every PR, and its cell above records what it became. The discipline held: the cell was written to be falsified, and was.

**[Amended 2026-08-29, `rf2-6c12m.17`, after the merged-PR audit of #8745.]** Three rows above are struck. PR #8745 (landed on main as `428793e1fe`) moved the bench and witness instruments out of the production namespaces: `re-frame.hicasso.impl.evidence` and `re-frame.hicasso.impl.inventory` are deleted from `implementation/hicasso/src`, the collector no longer taps an evidence sink, and the doors those suites read — `stats`, `residue`, `quiesced!`, `cell-reaction`, `cell-readers`, `boundary-reads`, `reads-of`, `snapshot-of`, `body-runs`, `reset-body-runs!` and `shell-hook-ledger` — now live on `re-frame.hicasso.test.runtime`, the test kit's own runtime door, in the kit's source root and outside the artefact's published `:paths`. PR #8753 (`rf2-6c12m.7`) retired `check_complaint_catalogue.py` and its CI job: Spec 009's rows are the single record of which ids exist and which are retired, and the repo-wide keyword-drift gate holds them. So the population is thirty-three rows rather than thirty-six, and the `test:hicasso-invariants` row's *seven* checkers now chain four (`implementation/package.json`, read 2026-08-29). Every verdict on a row that stands is unchanged. The [gate](#the-gate) below still holds as written, with one sentence now historical: the collision it cites between two `evidence` namespaces cannot recur on this tree, because only `re-frame.hicasso.evidence` remains.

### The one row whose condition has fired

[`prototype-suite-triage.md`](prototype-suite-triage.md) disposes of the five bench SSR suites with a conditional:

> Two of them (`hframe_ssr`, `instance_key_payload`) assert real package behaviour and would be worth re-expressing **if** the package ever gets its own SSR entry.

**The package now has its own SSR entry.** `re-frame.hicasso.server` landed on 2026-08-14, and when this census was taken the two suites still asserted against the prototype (`bench/hicasso/ssr/entry.cljs`) rather than the product door. That was not a stale row — the suites run, in a standing lane, every PR — it was a disposition whose condition had been met with nobody having looked. Filed as `rf2-wehh0`, sequenced after `rf2-lb1xi`, whose repair to the package's own SSR witness decided how much was left to re-express.

**[Amended 2026-08-15, `rf2-uvazt`.]** The obligation is discharged. `rf2-wehh0` landed in PR #8272 and both suites are re-expressed on `re-frame.hicasso.server` — `hframe_ssr_cljs_test.cljs` whole, and `instance_key_payload_ssr_dom_cljs_test.cljs` its subject only. All five bench SSR suites correctly **stay**: their subject is the prototype entry the bake is taken from, so re-expression added a package witness beside them and moved nothing. The KEEP verdicts in the table above are unchanged and remain correct. [`prototype-suite-triage.md`](prototype-suite-triage.md) §(iii) now records what happened per suite, with the subtraction and the no-loss argument.

## 3. The post-kernel mutable-global re-sweep

[`globals.md`](globals.md) took the roster at `rf2-hic-017` and found twenty-one owners with zero migrations. This section re-runs its census against the tree that ships and asks the one question the bead states: **did later work introduce an unjustified global?**

**It did not.** Since the roster commit, `implementation/hicasso/src` has taken **14 commits and 1,200 inserted lines**, including the whole of `server.cljs`. Not one added a module-level owner: every hit the arms return is on the roster, and `server.cljs` returns none at all.

### Method

`globals.md`'s six arms, transcribed, plus a seventh this census had to add. Every arm is run over the sources with **string literals and comments blanked**, and each hit is attributed to its **enclosing top-level form** rather than its line — which is what makes multi-line definitions countable (`error.cljc` writes `(defonce ^:private !sources` three lines above its `(atom {})`) and what retires the hand-kept exclusion list (an allocation inside a `defn` has no enclosing `def` and is not a candidate).

```sh
# C1-C6 — as recorded in globals.md, verbatim. See that page for each arm's
#         own comment; they are not restated here, because two copies of a
#         search is one copy too many.

# C7 — every top-level defonce. A `defonce` is a process-global COMMITMENT
#      whether or not its value is mutable, and C1-C6 can only see one whose
#      INIT they recognise. This arm is new; the section below is why.
rg -n --sort path "^\(defonce\b" implementation/hicasso/src
```

**C7 anchored returns 13; unanchored (`rg -n "\(defonce\b"`) it returns 14.** The one extra hit is `mount.cljs:129`, the `!root` inside the `render!` docstring that [`globals.md`](globals.md#the-false-positive-worth-its-own-section) already gives a section of its own — so the anchoring and the docstring-blanking agree, by two independent routes, about exactly which hit is not real.

### Result

| | Owners |
|---|---|
| Found by the seven arms, attributed to a top-level form | **23** |
| On `globals.md`'s mutable roster | 21 — all 21 found |
| On `globals.md`'s identities roster | 1 found (`adoption-context`); the other 6 are plain `def`s no textual arm distinguishes |
| Found and on **neither** roster | **1** — `impl.codec/raw-crossing` |
| In `server.cljs`, the largest post-kernel addition | **0** |

### The two things the re-run found

**The stated derivation regenerates twenty of the twenty-one rows.** `impl.collector/first-registration-armed` is found by none of `globals.md`'s six arms. Its init is not a mutable constructor (C1 blind), not a `#js` literal (C2 blind), not a dynamic var (C5 blind), and it is never written (C3, C4 and C6 blind). The blindness is structural in exactly the way that page describes the `:dynamic` blindness it had already corrected once: a `defonce` is a process-global commitment whether or not its value is mutable, and no widening of the six reaches one. C7 closes it — with C7 the union regenerates all twenty-one.

This matters more than one row, and `globals.md`'s own opening says why: *"a roster whose stated derivation cannot regenerate it is the same defect as no derivation at all."* Filed as `rf2-9ccio`.

**The identities roster is short by one.** `impl.codec/raw-crossing` (`codec.cljs:3150`) is a module-level `#js` object written at construction and never again — the same class, the same file and three lines above `impl.codec/raw-gate`, which *is* on that roster with the note *"A `#js` object carrying `displayName`, written at construction and never again."* It predates the page (it arrived with `rf2-hic-001`), so this is an omission rather than drift. Same bead.

**And the page's own deferred question is now live.** [`globals.md`](globals.md) closes by recording that SSR request scope is vacuous *"because this package publishes no server-render door"*, and says: *"The day a server-render entry lands, all twenty-one become request-scope questions at once … whoever files that bead should start here."* That day was 2026-08-14. No defect is claimed and none is known — `renderToString` is synchronous and nothing on the render path awaits — but the disposition the page asks for is owed, and the two dynamic vars are where it starts, because a global `set!` restored in a `finally` is exactly as safe as the call being synchronous and no more. Filed as `rf2-8ylqp`.

## The gate

`scripts/check_architecture_census.py` re-runs all three populations against the working tree and refuses when the tree and this page disagree. It exists because a census is a claim about a moment, and [`tool-consumer-census.md`](tool-consumer-census.md) records what happens without one: a patch whose every hunk applied cleanly while the claim underneath it went false, with nothing in a diff review able to catch it.

| Arm | What reds it |
|---|---|
| `mechanism` | a seventh `defmacro`; a render call on an alias that is not a `react-dom` require; a `:require` of an emitter namespace |
| `retained-tooling` | a checker, a `hicasso` npm script or a tool namespace with no row above, or a row whose Consumer cell is empty |
| `mutable-globals` | a module-level owner the seven arms find whose `impl.<ns>/name` identity has no row in [`globals.md`](globals.md) |

Each arm is shown to bite by `--self-test`, which plants the smallest edit that would land the mechanism the arm refuses and asserts the arm reports it.

**Its own consumer, by the standard this page holds everything else to, is [`rf2-hic-064`](correction-ledger.md)** — the final audit, which re-derives rather than trusts. It is deliberately not wired into a workflow, and the precedent is its sibling: [`release-scans.md`](release-scans.md)'s `scripts/check_allocation_non_claim.py` is a census gate on the same footing and is scheduled nowhere either. A census re-run belongs to the audit that needs it rather than to every PR, and `.github/**` was fenced from this bead in any case. It is named here so the row is not missing.

**The mutable-globals arm enforces one direction only**, and the reason is on the page rather than in the script's silence: the reverse check — every rostered row is found by some arm — is what produced this census's own correction, and it cannot be automated honestly, because six identities-roster rows are plain `def`s of React classes and components that no textual arm distinguishes from any other `def`. Enforcing it would mean either a sixth of the roster permanently red or an allowlist that fails open. The direction that *is* enforced is the one the kill rules need: a new owner arrives with no row, and reds.

**And it compares fully qualified identities**, `impl.<ns>/name` on both sides, with the declaring namespace read off the source's path — in ClojureScript the path *is* the declaration, so no parser is involved. The gate shipped comparing bare `def` names, and the merged-PR audit of #8258 showed what that costs: a planted `impl.planted/!cells` inherited `impl.collector/!cells`'s row and returned no finding at all. This tree has two `evidence` namespaces and two `overlay` namespaces, so the collision is not hypothetical. The `--self-test` agreed with the broken gate, because its control only ever planted a name nothing else owned — a control that cannot tell *the arm bit* from *the arm matched the wrong row*. It now discriminates in both directions: the duplicate reds while the rostered `impl.collector/!cells` stays accepted in the same run. Re-running the sweep under qualified identities surfaced no owner the bare matcher had been accepting, so every count above stands as published.

## What was filed

An owner named in this table is one the [gate](#the-gate)'s mutable-globals arm accepts without a `globals.md` row, for as long as the row stands. That is not an allowlist and it does not fail open: the entry costs a published row naming the finding and its bead, and it goes when the corrective PR adds the roster row. A new owner nobody has filed anything about still reds.

<!-- census:open-corrections -->

| Finding | Census | Bead | Severity |
|---|---|---|---|
| `globals.md`'s six arms regenerate 20 of its 21 rows; a seventh (`defonce`) arm closes it | 3 | `rf2-9ccio` | coverage |
| `globals.md`'s identities roster omits `impl.codec/raw-crossing` | 3 | `rf2-9ccio` | coverage |
| The SSR request-scope disposition `globals.md` deferred is now live for all 21 owners | 3 | `rf2-8ylqp` | coverage |
| `prototype-suite-triage.md`'s re-expression condition for two bench SSR suites has fired | 2 | `rf2-wehh0` | coverage |

The rows are in [`correction-ledger.md`](correction-ledger.md#the-ledger) and each closes the way that page requires: by re-running the section that produced it against the landed fix, not by the fix merging.
