# Prototype suite triage — the 69 bench suites, one verdict each

**rf2-6ync** (the unmet third deliverable of rf2-hic-008), 2026-08-11 02:08 AUSEST.

`implementation/freehand/test/re_frame/bench/hicasso/` holds 69 `*_cljs_test.cljs` / `.cljc`
suites, 28,035 lines, all still on `re-frame.bench.hicasso.*`. rf2-hic-008's third deliverable
reads "all existing Hicasso CLJS test suites migrated to the new namespaces and green", and that
sentence assumed a mechanical rename. It is not one. This file is the verdict per suite, and the
verdict is the deliverable: **a smaller honest set of ported witnesses beats 69 files moved for
symmetry.**

Three verdicts, defined once:

| verdict | meaning |
|---|---|
| **PORT** | a real package-behaviour witness whose dependencies now exist under `implementation/hicasso/`. Move it; make it green. |
| **RE-AUTHORED** | the behaviour matters and the package does not assert it, but the suite is written against a runtime that no longer exists as one namespace. Record what it must assert; do not port the mechanics. |
| **STAYS** | it measures the benchmark, or the package already asserts it, or its move is blocked on a decision a triage may not take. It belongs where it is. |

**13 PORT (5,108 lines) · 9 RE-AUTHORED (3,236) · 47 STAYS (19,691).** The three sum to 69 files and
28,035 lines; every suite is named exactly once below, and line counts are `wc -l`.

**Two rows have moved since, and the counts below are the census as it landed rather than the
standing verdict.** rf2-b6ja found that `arm1/host_ssr_dom_cljs_test` (670) and
`arm1/fallback_contents_cljs_test` (376) were parked in STAYS on a block that does not exist, so the
standing verdict is **15 PORT (6,154) · 9 RE-AUTHORED (3,236) · 45 STAYS (18,645)** — still 69 files
and 28,035 lines. Both rows keep their single home in STAYS (vi), where the bullet now carries the
PORT verdict and the correction; see
[The three sequencing questions, settled](#the-three-sequencing-questions-settled). **Both have
since been ported** (PR #7842); a verdict says where a suite belongs, so executing one changes no
count here.

**And every count in this document is a dated census, not a live inventory.** It was taken over 69
files; read on `origin/main` at 2026-08-11 the bench tree holds **63** of them (25,268 lines),
because a PORT executes as a *move* and the six `front/*` rows — the first wave the PORT table's
sequencing paragraph names — have gone. The arithmetic closes exactly: their six line counts sum to
2,767, and 28,035 − 2,767 = 25,268. The counts are deliberately left at their census values rather
than tracked, because the verdicts are what this document is for and a decision record that
renumbers itself every time a row is acted on is a ledger nobody can audit. **Read the counts as of
the census; read the tree for what is in it now.**

## The three facts that decide most rows

### 1. All 69 already run on every PR

`implementation/shadow-cljs.edn` puts `freehand/test` on `:source-paths`, and two selectors reach
every one of these namespaces:

- `:node-test`'s `:ns-regexp "cljs-test$"` matches all 69 (`re-frame.bench.hicasso.*-cljs-test`);
- `:browser-test`'s `:ns-regexp "^(?!re-frame\\.freehand\\.bench\\.).*-dom-cljs-test$"` matches
  every `*_dom_cljs_test` among them — the exclusion is `re-frame.freehand.bench.*`, which is a
  different tree from `re-frame.bench.hicasso.*`.

So **nothing here is dark**, and a move buys no coverage of the prototype. What a move buys is
coverage of the **package**, which since rf2-hic-009 is a genuinely different code path. That is
the only thing a PORT verdict can be justified by, and every PORT row below names the package
surface it would newly cover.

### 2. The package's divergence is exactly enumerable

`implementation/hicasso/frozen-sources.edn` records what the package is made of, and its three
retirements record how it has since moved:

- `front/{codec,controlled,intent,presence,route_link,slot,state}` → `impl.{codec,controlled,intent,presence,route-link,slot,state}`, rename-only apart from rf2-hic-007's shared `fail!` and rf2-kjf5's additive `retain-body!`;
- `arm1/runtime.cljs` → the six modules `impl.{collector,generation,frames,roots,evidence,inventory}` (rf2-hic-009);
- `arm1/{boundary,mount,presence}` → `impl.{boundary,mount,presence-react}`;
- `arm1/lang.clj` → the public door `re-frame.hicasso`.

Two consequences run through the table. **A `front/*` suite ports by renaming its `:require`s and
nothing else** — rf2-hic-007's `fail!` is a superset that keeps the id, position, reason and
recovery a test asserts on, so the 108 refusal assertions across those suites survive the move.
**An `arm1/*` suite that names `arm1.runtime` cannot** — which of the six modules answers depends
on the var, not on the file, which is precisely why `frozen-sources.edn` retired those rows rather
than re-pinning them.

### 3. What the package already asserts

28 witness suites (11,957 lines) and 4 support namespaces live under `implementation/hicasso/test/`,
authored by rf2-hic-007/010/011/012/013/014/015/016/020/022/023 and
rf2-0oy4/0xgk/2l17/6tmu/kjf5/ouus/q9cf/hic-053. (rf2-hic-008's note counts "31 suites, 12,491
lines"; that count includes the support namespaces and the consumer app, which are not suites.)
**Not one is a migration.** Where a bench suite's behaviour is already asserted there, the verdict
is STAYS and the row names the witness — a second copy doubles the maintenance and the two drift.

---

## PORT — 13 suites, 5,108 lines

Every one covers a **public door surface with no package witness at all**: `h/frame`, `h/reg-state`,
`h/route-link`, `hfn`, the codec, the controlled converge, and intents lowered inside `h/boundary`
and presence.

| suite | lines | package surface it would newly cover |
|---|---|---|
| `front/route_link_cljs_test.cljs` | 263 | `impl.route-link` — the whole namespace is untested in the package. Route-link's grammar as data: a render is a pure value, the href is routing's own, the click decision is readable off the tree. |
| `front/presence_cljs_test.cljs` | 323 | `impl.presence` — the retention machine and phase transform, headless and clockless. `motion_presence_dom_cljs_test` (rf2-hic-053) asserts the mounted *posture*; the machine algebra beneath it is unasserted. |
| `front/state_cljs_test.cljs` | 337 | `impl.state` — `h/reg-state` against a real frame. A public door export with zero package coverage. |
| `front/controlled_dom_cljs_test.cljs` | 413 | `impl.controlled` — the converge's own mechanism: the trap it avoids, and the elements it refuses to touch. |
| `front/revision_dom_cljs_test.cljs` | 646 | `impl.controlled` + `impl.codec` — `::h/revision`'s explicit-caller-revision re-baseline, HD-019's reset law. |
| `front/intent_cljs_test.cljs` | 785 | `impl.intent` — intent lowering across the whole position table. The `hfn` door's contract. |
| `arm1/hframe_cljs_test.cljs` | 360 | `h/frame`, node half. Public export, no package witness. |
| `arm1/hframe_dom_cljs_test.cljs` | 458 | `h/frame` against a real React root — the four claims that are about React rather than about the read. |
| `arm1/state_dom_cljs_test.cljs` | 265 | `h/reg-state` mounted: costs no hook, two disclosures on one page. |
| `arm1/keywarn_dom_cljs_test.cljs` | 189 | the minted-key warning end to end — owner threading from the shell, which the codec cannot name. |
| `arm1/callback_form_dom_cljs_test.cljs` | 156 | the one callback form, driven by a real browser event rather than a stand-in. |
| `arm1/presence_intent_dom_cljs_test.cljs` | 460 | an intent on a presence child — `impl.presence-react` × `impl.intent`, a defect shape neither namespace's own witness can see. |
| `arm1/boundary_intent_dom_cljs_test.cljs` | 453 | an intent lowered inside `h/boundary`'s class component, on `:fallback` and on `:children`. |

**Sequencing.** The six `front/*` rows are rename-only and independent of each other and of
everything else; they are the first wave. The seven `arm1/*` rows each need `arm1.runtime` call
sites re-pointed at the six modules, and four of them additionally need `arm1.hook-probe` or
`arm1.lane/leave-act-environment!` — the latter already has a package counterpart in
`re-frame.hicasso.roots-frames-support/leave-act-environment!`, which cites the prototype by name.
They are ports rather than re-authorings because the *assertions* survive verbatim; only the
namespace a var is reached through changes.

---

## RE-AUTHORED — 9 suites, 3,236 lines

The behaviour matters, the package does not assert it, and the suite names `arm1.runtime` in a way
no rename can resolve. Each row states **what a package witness must assert**; none of the mechanics
should be transcribed.

| suite | lines | what a package witness must assert |
|---|---|---|
| `arm1/runtime_cljs_test.cljs` | 683 | Split across the six modules. `kernel_commit_owns_cljs_test` (I5) and `read_extent_cljs_test` (I7) already carry the acquisition and extent halves; what is left unasserted is **the cell table's wiring** and **HD-002's ownership state machine with its allowed edge-diff operation**. Those two belong to `impl.collector` and want one focused suite each, not a 35-deftest transcription. |
| `arm1/cell_table_laws_cljs_test.cljs` | 566 | **The six index laws** over the fused cell table (architecture.md, graduated from `spike-01`). Nothing in the package asserts them — `inventory_snapshot_cljs_test` asserts that `cell-readers` answers a snapshot, which is a different claim. This is the single largest genuine gap the triage found. Re-author against `impl.collector`'s published seam; the bench suite reaches `front.dogfood` for fixtures and a package version should bring its own. |
| `arm1/ambient_refusal_cljs_test.cljs` | 590 | That an ambient `rf/subscribe` / `rf/dispatch` written inside a body **refuses**, with a control row proving the same read succeeds one call outside. Cannot port: it asserts the prototype's refusal text, and rf2-hic-007 moved the package's refusals to `impl.error/fail!` under `check_complaint_catalogue.py`. A package witness asserts the catalogued shape instead. |
| `arm1/ambient_refusal_dom_cljs_test.cljs` | 130 | The same refusal under real React, where the risk is that it refuses **too much**. One bead with the row above. |
| `arm1/cold_read_cljs_test.cljs` | 199 | The cold probe's contract: reuse a live sub-cache reaction by deref alone, else compute pure against one render-scoped snapshot through one render-scoped memo — **no reaction build, no cache insert, no in-tick effect**. Now `impl.collector`'s `read-key!`. Unasserted in the package. |
| `arm1/disposed_cell_cljs_test.cljs` | 287 | The registry-epoch and node-key axes of `commit-basis` — now `impl.generation`'s. `hmr_registry_cljs_test` covers what a *save's* re-registration does; the disposal transition itself is uncovered. |
| `arm1/first_registration_cljs_test.cljs` | 308 | The other registry transition — an id that had **no** handler and got one. Same module, same gap, one bead with the row above. |
| `arm1/hook_ledger_dom_cljs_test.cljs` | 173 | **The ≤2-hook budget, counted at React's own dispatcher** (HD-020(b)). A hard architectural line with no package witness. Cannot port: it needs `arm1.hook-probe`, which proxies React's internal dispatcher slot and has no package counterpart. A package version must bring its own probe or the claim stays unmade. |
| `arm1/frame_prop_dom_cljs_test.cljs` | 300 | That the frame reaches a boundary as an ordinary prop, so the second hook is not structural. Same `hook-probe` blocker; same remedy; one bead with the row above. |

**Since: the `hook-probe` blocker in the last two rows is discharged.** rf2-wjag brought the package
its own probe — `re-frame.hicasso.hook-probe`, in `35e8fecc1d` — so "a package version must bring
its own probe" is now satisfied rather than pending, and the remedy those two rows name is available
to whoever takes them. The rows themselves are unchanged: they are still re-authorings, because what
made them re-authorings was never the probe alone.

---

## STAYS — 47 suites, 19,691 lines

Grouped by why. **This is the largest bucket and that is the correct outcome**, not a shortfall:
the bench tree is a measured artefact and most of what tests it is measurement.

### (i) It tests the benchmark instrument, not the package — 6 suites, 2,392 lines

`lane_bytes_cljs_test.cljs` (135) · `lane_release_dom_cljs_test.cljs` (103) ·
`lane_window_dom_cljs_test.cljs` (398) · `p0_converge_order_cljs_test.cljs` (1,426) ·
`read_profile_baseline_cljs_test.cljs` (173) · `walk_profile_baseline_cljs_test.cljs` (157)

These assert `lane/verified-write!`'s window shapes, `lane/release!`'s refusal, the segment-order
verdict's arithmetic, and two profiling baselines. Their subject is `re-frame.bench.hicasso.lane`
and the profile apps. There is no package counterpart because there should not be one.

### (ii) It is a tier-1 shape witness — the benchmark's own definition of done — 7 suites, 2,185 lines

`shapes/bulk_dom_cljs_test.cljs` (219) · `shapes/framework_subs_dom_cljs_test.cljs` (448) ·
`shapes/hook_budget_dom_cljs_test.cljs` (220) · `shapes/large_template_dom_cljs_test.cljs` (259) ·
`shapes/narrow_dom_cljs_test.cljs` (216) · `shapes/ordinary_dom_cljs_test.cljs` (521) ·
`shapes/route_link_dom_cljs_test.cljs` (302)

The charter's five tier-1 shapes are how **the benchmark** is judged. Each suite is anchored to
`shapes.model` — one census-real app behind all four shapes — and to element-count arithmetic taken
from the census. Porting them would move the census into a package that has no use for it.

### (iii) It is SSR-programme evidence — 5 suites, 2,036 lines

`ssr/entry_cljs_test.cljs` (481) · `ssr/hframe_ssr_cljs_test.cljs` (175) ·
`ssr/instance_key_payload_dom_cljs_test.cljs` (484) · `ssr/spike_cljs_test.cljs` (201) ·
`ssr/spike_dom_cljs_test.cljs` (695)

All five reach `ssr.entry` and `ssr.fixtures` — the bake driver's corpus, whose rows are the
requests `ssr/driver.cjs` bakes. Two of them (`hframe_ssr`, `instance_key_payload`) assert real
package behaviour and would be worth re-expressing **if** the package ever gets its own SSR entry.
`spike_cljs_test`'s own docstring settles the rest: *"No verdict is published here and none is
implied."*

### (iv) The package already asserts it — 13 suites, 4,269 lines

| bench suite | lines | the Phase-1 witness that covers it |
|---|---|---|
| `arm1/deferred_read_cljs_test.cljs` | 378 | `read_extent_cljs_test` (I7) — the deferred-read carriers, exactly. |
| `arm1/boundary_crossing_cljs_test.cljs` | 335 | `read_extent_cljs_test` — same family, the seam half. |
| `arm1/boundary_crossing_dom_cljs_test.cljs` | 160 | `read_extent_dom_cljs_test` (rf2-ouus) — "the carriers a node lane cannot drive". |
| `arm1/lifecycle_dom_cljs_test.cljs` | 399 | `kernel_commit_owns_dom_cljs_test` (StrictMode double-invoke, error-boundary throw-and-retry) plus `hmr_remount_cljs_test` (the body swap). All four of its claims. |
| `arm1/generation_fence_dom_cljs_test.cljs` | 318 | `kernel_commit_owns_cljs_test` and its DOM sibling — the fence, and the render-to-commit gap heal. |
| `generation_fence_coverage_cljs_test.cljs` | 232 | `reincarnation_cells_cljs_test` plus `reincarnation_routing_cljs_test` — it asked what the commit basis does *not* see, and same-id reincarnation is the answer the package now witnesses. |
| `arm1/staged_read_tear_cljs_test.cljs` | 280 | `reincarnation_paint_dom_cljs_test` (rf2-2l17) — the tear corrected before visible paint. |
| `arm1/presence_dom_cljs_test.cljs` | 243 | `motion_presence_dom_cljs_test` (rf2-hic-053) — the mounted retention posture. |
| `arm1/hydrate_cljs_test.cljs` | 187 | `roots_frames_hydration_dom_cljs_test` (rf2-hic-012, rf2-6tmu) — adoption window and reap horizon. |
| `arm1/hydrate_dom_cljs_test.cljs` | 623 | as above — independent adoption, independent presence, independent teardown. |
| `arm1/hydrate_recoverable_dom_cljs_test.cljs` | 301 | as above — "independent complaints" is the recoverable-error reporter. |
| `front/dogfood_cljs_test.cljs` | 220 | `test_kit_dogfood_cljs_test` (rf2-hic-020), whose own docstring names this file as the thing it re-expresses on the public kit. |
| `arm1/dogfood_dom_cljs_test.cljs` | 593 | as above for the composition — and this mount **starts the HD-014 six-week K7 clock**, with the operator on record accepting it. Moving it would move a dated commitment. |

Where a row says "covers", the package witness makes the same claim about the package's code path.
Where coverage is only partial the difference is filed above under RE-AUTHORED rather than hidden
here. `generation_fence_coverage` and `staged_read_tear` are the two closest calls, and both
resolved to STAYS because their open question — what the commit basis misses — is exactly what the
reincarnation family now answers.

### (v) It is a benchmark-programme commitment — 3 suites, 589 lines

`front/witnesses_cljs_test.cljs` (77) — the validation.md witness roster as data; its only reader is
the benchmark.

`arm1/render_measure_cljs_test.cljs` (212) and `arm1/render_measure_emit_nightly_test.cljs` (300) —
Spec 009's `:render` bucket. These do assert package behaviour (`mint-view!` wraps the component fn
in `performance/mark-and-measure`), but the ON half runs in the `:node-test-nightly` build via the
`-emit-nightly-test$` selector and the pair is written as a matched OFF/ON couple. Splitting them
across two trees would break the couple; moving both needs a nightly-lane decision that is not a
triage's to take. **Flagged for a follow-up bead rather than ported.** **ANSWERED (rf2-b6ja): there
was no lane decision to take — measurement is tier 3 and the ON half is already in it, and a port
would need no build, lane or workflow change.** See
[3. the `render_measure` couple](#3-the-render_measure-couple--already-in-the-tier-that-runs-measurements).

### (vi) It is a control arm, or its move is blocked — 13 suites, 8,220 lines

`controlled_restore_dom_cljs_test.cljs` (858) is the clearest case in the tree. It `:require`s **no
Hicasso namespace at all**: it is what correct means for a controlled input *on plain React*, the
control arm Arm 2's retirement left behind. A control arm belongs with the instrument.

`arm1/controlled_grid_dom_cljs_test.cljs` (1,374) and `arm1/controlled_burst_dom_cljs_test.cljs`
(464) are the `:controlled/grid-100` witness at its stated size, anchored to `arm1.grid` —
validation.md's model, not the package's. `arm1/props_bailout_dom_cljs_test.cljs` (397) is a
re-render census on the tier-1 feed shape. `arm1/ratom_activation_cljs_test.cljs` (239) and
`arm1/ratom_activation_dom_cljs_test.cljs` (200) run Arm 1 under the **stock Reagent adapter**,
while the package's test lane is UIx-only by a deliberate deps decision recorded in
`implementation/hicasso/deps.edn`; these need a substrate ruling before they can move.
`front/census_article_editor_cljs_test.cljs` (247) demonstrates the `:&` merge on a census-real
screen, and the merge's own contract is `front/codec_cljs_test`'s.

Four are blocked on something concrete, and the block is the interesting part:

- **`front/codec_cljs_test.cljs`** (1,593, 67 deftests) — the largest single package-behaviour
  witness in the tree, and a clean rename apart from one thing: it `:require`s
  `re-frame.bench.hicasso.front.slot-cljs-test`'s corpus, and that file cannot move (below). Port
  it once the slot question is settled, or inline the corpus.
- **`arm1/raw_escape_dom_cljs_test.cljs`** (403) — the `[:>]` raw escape against real React. Reads
  `front.codec-cljs-test`'s corpus; queued behind it for the same reason.
- **`arm1/host_ssr_dom_cljs_test.cljs`** (670) and **`arm1/fallback_contents_cljs_test.cljs`** (376)
  — `defhost`'s `:ssr` policy in all three places it has to hold, and the contract for what an
  `:ssr` fallback may contain. Genuine package behaviour, and at census time
  `implementation/hicasso/` had no `:ssr` witness of its own, so **this pair was the answer to
  rf2-6rw9** — and it is the answer that was taken: **both are now on the package** (PR #7842), and
  the package's `:ssr` gap is closed. ~~Blocked twice over: the two files `:require` each
  other, so they move together; and both reach `re-frame.bench.hicasso.ssr.entry`, which has no
  package counterpart. Porting them means giving the package an SSR entry first — which is
  rf2-6rw9's work, not a triage's.~~ **CORRECTED (rf2-b6ja): neither block exists. The files do not
  `:require` each other and neither reaches `ssr.entry`; both readings came from docstring
  `[[wiki-links]]`.** The pair is an ordinary `arm1/*` port and is **PORT**, not STAYS — see
  [2. the `:ssr` trio](#2-the-ssr-trio--not-blocked-and-the-third-member-is-a-different-bead).
  `arm1/host_hatch_dom_cljs_test.cljs` (1,259) is **not** the third member of that bead: it is the
  only one of the three that needs `arm1.hook-probe`, which is the RE-AUTHORED blocker
  `hook_ledger_dom_cljs_test` and `frame_prop_dom_cljs_test` already carry, and it belongs with
  those rather than with the `:ssr` pair. It stays here — no longer blocked on the probe, which
  rf2-wjag has since landed, but owned by that bead and not by this row.
- **`front/slot_cljs_test.cljc`** (140) — blocked on a hot-zone change, and the block is worth
  naming precisely. It is `.cljc` on purpose: `scripts/check_test_lane_bijection.py` rule B2
  requires a `.cljc` suite under a CLJS-owned test root to be selected by a CLJS lane as well as a
  JVM one. `implementation/hicasso/deps.edn`'s `:test` alias runs with `--probe` precisely because
  **zero JVM tests is the correct outcome there**, and its own comment states the consequence: *"If
  a JVM-runnable suite ever lands in `test/`, drop `--probe` and take the floor."* Taking the floor
  needs an artefact-roster entry, which is only legal with a matching `test.yml` job, and
  `.github/workflows/` is hot zone. **Sequential access required — this one file cannot move
  without an operator-sequenced workflow change.**
  **ANSWERED: no lane, and the row is closed rather than pending** — the claim is already armed on
  both hosts and the package's copy is held identical to the armed one. See
  [1. `front/slot_cljs_test.cljc` — no JVM lane](#1-frontslot_cljs_testcljc--no-jvm-lane).

---

## The three sequencing questions, settled

**rf2-b6ja**, 2026-08-11 04:01 AUSEST. The triage stopped at three rows rather than take decisions
that were not a triage's to take. All three are decided here, each with the measurement it rests on,
so that the next reader inherits the answer instead of the question. Two are a **no**; the third
turns out to rest on a premise that does not hold.

### 1. `front/slot_cljs_test.cljc` — no JVM lane

**The verdict: do not open a `jvm-hicasso` lane. The suite stays in the bench tree, and this row is
closed rather than pending.** The claim it makes is already asserted on both hosts, and the
package's copy of the rule is held identical to the copy being asserted — by a checker that is
stronger than the suite.

**The number that decides it is zero.** Nothing follows this file into a JVM lane. It is the *only*
`.cljc` test file among the 69; the other 62 are `.cljs`, and a `.cljs` file is not loadable by a
JVM lane at all — `check_test_lane_bijection.py`'s `loadable_by` gives JVM lanes `.clj` and `.cljc`
and nothing else. `implementation/hicasso/test/` carries not one `.cljc`: all 61 files under it are
`.cljs`, read on `origin/main` at 2026-08-11 — the triage counted 35 and the tree has grown since,
while the extension has not, which is the half of the claim that is load-bearing. So the
lane would open for one file and stay at one file until somebody *authors* a `.cljc` suite, and the
package offers only three namespaces to author one against — `impl/slot.cljc`, `impl/state.cljc`,
`impl/error.cljc` — of which only `slot` requires nothing but `clojure.string` (`state` reaches
`re-frame.events` and `re-frame.subs`; `error` is the complaint catalogue's, already gated by
`check_complaint_catalogue.py`). A whole CI job for one file, and no queue behind it.

**And the cheap answer holds, which is what makes the no comfortable rather than merely thrifty.**
The cross-host claim is armed four ways already, none of them a new lane:

- **The pin runs on both hosts today — measured from the compiler, not read off a path filter.**
  PR #7823's method is to enumerate a thing's inputs from the compiler's *own analysis cache* rather
  than guess them from predicates, and applied here the closure is exactly **two repo files**: the
  suite `front/slot_cljs_test.cljc` and the rule it pins, `front/slot.cljc`. `:node-test`'s
  `dev/ana` tree carries a cache entry for each, which is that build having compiled them; the only
  other namespaces in the closure are `cljs.core`, `cljs.test`, `cljs.pprint`, `clojure.string` and
  eight `goog.*`, every one of which comes from a jar rather than from the tree. Nothing in
  `implementation/core` is on the path at all. Hand those two paths to
  `.github/scripts/report-changed-surfaces.sh` — the classifier `test.yml` gates on — and they arm
  **both** `cljs_node_test=true` and `implementation_jvm=true`: the `cljs` job, whose
  `npm run test:cljs` is the compile that wrote the cache, and the per-artefact JVM jobs, which
  include `jvm-freehand`. The JVM half is confirmed by running it rather than by reading a lane
  model — `clojure -M:test -n re-frame.bench.hicasso.front.slot-cljs-test` in
  `implementation/freehand` gives **3 tests, 92 assertions, 0 failures, exit 0**. One corpus, one
  implementation, two runtimes, two jobs it already rides, and no predicate anybody maintains for
  it.
- **The package's copy is held identical to the pinned one.** `frozen-sources.edn` has exactly one
  surviving row and it is this rule: `front/slot.cljc` → `src/re_frame/hicasso/impl/slot.cljc`,
  `:whole-file?` unset, so `check_freeze.py`'s MOVED rule applies — it *reconstructs* the package
  file from donor text with `:renames` applied at symbol boundaries and compares line by line. That
  is not a digest of the donor alone; it is an equality between the two files. So a cross-host
  assertion about the bench rule is an assertion about the package rule, and the equality is checked
  rather than believed.
- **That checker is armed from the package's own surface.** `npm run test:hicasso-invariants` runs
  it, `implementation/hicasso/*` sets `cljs_node_test=true` in `report-changed-surfaces.sh`, and
  that arm's own comment names the invariants gate as one of the three things the output schedules.
  It also runs unconditionally in `scripts/test-fast-pr.sh`.
- **The JVM consumer the pin exists for is armed too.** The codemod's `deps.edn` puts
  `../../../implementation/freehand/test` on `:paths` and `shared_rule_test.clj` asserts
  `(identical? dest/canonical-slot slot/prop-name)` — plus that the file came off
  `implementation/freehand/test/…` and not a copy inside the tool. Since rf2-erjv that lane is armed
  from `implementation/hicasso/*` as well as from `implementation/freehand/*`.

**And the move is what would break the ride — the measurement that actually settles it.** Ask the
same classifier about the destination instead of the origin and the asymmetry is stark:
`implementation/hicasso/*` arms `cljs_node_test`, `cljs_browser`, `hicasso_controlled`,
`hicasso_hmr` and `migration_hicasso_codemod`, and it does **not** arm `implementation_jvm` —
there is no JVM job behind that tree, by the same deliberate `--probe` decision quoted above. So
porting this file would not merely fail to *gain* a JVM lane; it would **surrender the one it
already has**, and the only thing that could give it back is the job this verdict declines to open.
A port whose measured effect is to take a cross-host claim from two hosts down to one is not a port
worth sequencing a hot-zone change for. That is the difference between this row and the twelve other
PORT rows, and it is why the verdict here is not merely "later".

**The precedent is on the record, and it is the same wall.** rf2-hic-022 walked this exact chain and
backed out of it: `140620d291` landed a JVM-runnable suite in `implementation/hicasso/test/` and
dropped `--probe`; `b18bc8e1ad` found that `check_jvm_lane_rosters.py` R1 refuses a roster entry with
no `test.yml` job and reverted the roster half; `dd9f31bbc4` re-expressed the witness as
`scripts/check_lint_export.py`, in a lane it already had. The long comment at the foot of
`scripts/test-jvm-implementation.sh` still narrates the middle state and is the artefact of it. A
second bead arriving at the same wall is not new information about the wall.

**The revisit trigger, because a no without one is exactly the rf2-hic-021 defect restated.**
Everything above rests on one row in `frozen-sources.edn`, and that file's entire history is rows
retiring as the package diverges — five `front/*` rows and every `arm1/*` row are already gone. Its
own header anticipates the last one: *"when `front/slot.cljc` diverges for its own reasons"*. On the
day that row is retired the package's slot rule has no cross-host assertion left, and **nothing will
say so** — the freeze gate will go green having stopped looking. So: **whoever retires that row owns
the replacement**, and the replacement is still not a `jvm-hicasso` lane. The cheap shape is to
repoint the codemod's `:paths` entry at `implementation/hicasso/src` — a `migration/` artefact
reading a shared `.cljc` out of `implementation/` is the direction the rule was extracted to serve,
and the codemod's JVM lane already has a `test.yml` job and is already armed from
`implementation/hicasso/*`.

**What this leaves for the two rows queued behind it.** `front/codec_cljs_test.cljs` (1,593) and
`arm1/raw_escape_dom_cljs_test.cljs` (403) are queued behind this file's `corpus` var, not behind
its JVM half, and the corpus needs no lane. A namespace under `implementation/hicasso/test/` that
defines no `deftest` is not a test file, so B1 and B2 never reach it — the bijection gate's universe
is files that evaluate a test-defining form at the top level. Port the corpus as an ordinary support
namespace beside the codec suite and both rows unblock as plain CLJS ports. **That is a follow-up
dispatch, not this row.**

### 2. The `:ssr` trio — not blocked, and the third member is a different bead

**The verdict: `arm1/host_ssr_dom_cljs_test` + `arm1/fallback_contents_cljs_test` are PORT and are
not waiting on anything. `arm1/host_hatch_dom_cljs_test` is not their third member.** The two
blockers this triage recorded, and which rf2-6rw9's note then restated as that bead's real content,
are both misreadings. Read off the files on `origin/main`, 2026-08-11:

- **They do not `:require` each other.** `host_ssr_dom_cljs_test`'s `ns` form requires
  `cljs.test`, `re-frame.adapter.uix`, `arm1.mount`, `arm1.runtime`, `front.codec`, `lane`,
  `re-frame.core`, `re-frame.test-support`, `react`, `react-dom/client` and `react-dom/server`, with
  `defview` / `defhost` from `arm1.lang`. `fallback_contents_cljs_test`'s is the same list without
  `react-dom/client`. Neither names the other. Each *mentions* the other exactly once, in a
  docstring `[[wiki-link]]` — `host_ssr` line 31, `fallback_contents` line 57. A cross-reference in
  prose is not a dependency.
- **Neither reaches `re-frame.bench.hicasso.ssr.entry`.** Neither file mentions the namespace at
  all. `ssr.entry` has exactly **six** semantic consumers in the tree — six `:require` forms,
  counted outside strings and comments — and they are the five `ssr/*` suites already parked in
  STAYS (iii): `entry_cljs_test`, `hframe_ssr_cljs_test`, `instance_key_payload_dom_cljs_test`,
  `spike_cljs_test` and `spike_dom_cljs_test`, plus the entry's own `ssr/node.cljs` driver.
  **`ssr/fixtures.cljs` is not among them.** The corpus names `ssr.entry` twice, at lines 7 and 198,
  and both are docstring `[[wiki-links]]`; its `:require` vector does not contain the namespace.
  **These two suites render to string with React's own `react-dom/server`**, which they import
  directly. The bake driver is not on their path, and the package needs no SSR entry to receive
  them.

So the pair is an `arm1/*` port of exactly the shape the PORT table's sequencing paragraph
describes: re-point `arm1.runtime` at the six modules, `arm1.lang`'s macros at the public door
`re-frame.hicasso`, `arm1.mount` at `impl.mount`, `front.codec` at `impl.codec`. Their only `lane`
call is `lane/leave-act-environment!`, once each, and its package counterpart already exists —
`re-frame.hicasso.roots-frames-support/leave-act-environment!`, which cites the prototype by name.
**They still move together**, but for the reason their own docstrings give rather than for a
require: each is written as the other's arm, and the bare arm is the one that matters. A witness
that only ever supplies a fallback proves nothing about the default, and `:client-only` is what an
author gets by writing nothing.

**How that six was arrived at — and this section has now been caught by its own lesson.** The first
draft of the second bullet said *seven* and counted `ssr/fixtures.cljs`, whose two mentions are
docstring `[[wiki-links]]` — precisely the false-dependency class the two bullets exist to correct.
The same trap wrote a false premise onto rf2-6rw9 ("the two files `:require` each other and both
reach `ssr.entry`"), where both halves were wiki-links and the bead stalled for hours on a blocker
that was not there. `git grep` returns requires, docstrings, comments and prose identically, and
nothing in this repo distinguishes them mechanically. So, stated once for the whole document:
**every dependency count here counts `:require` forms, outside strings and comments** — and a count
that does not say which question it answers is not worth reading. Grep to find candidates; read each
hit before counting it.

**`arm1/host_hatch_dom_cljs_test.cljs` (1,259) was blocked, on something else — and that block has
since been discharged.** It is the only one of the three whose `ns` form requires `arm1.hook-probe`,
and it uses it: `probe/install!`, `probe/record!`. Hook-probe proxies React's internal dispatcher
slot, and when this section was written it had no package counterpart — precisely the blocker
RE-AUTHORED records for `hook_ledger_dom_cljs_test` and `frame_prop_dom_cljs_test`. **It belongs
with that bead**, whose deliverable was the probe, and that is exactly how it resolved:
`re-frame.hicasso.hook-probe` landed under **rf2-wjag** in `35e8fecc1d`, at
`implementation/hicasso/test/re_frame/hicasso/hook_probe.cljs`, and four package suites already
require it. So the third member is unblocked, its port is **rf2-wjag's and not this row's**, and
that bead is in flight — nothing here to sequence. What the split bought is what it was for:
bundling `host_hatch` with the `:ssr` pair would have held two unblocked suites behind a probe
neither of them needs, on top of an SSR entry none of the three needs.

**rf2-6rw9's content changes accordingly.** The bead read "give the package an SSR entry, then port
the pair onto it". The entry is not on the path: give the package the pair. Whether the package ever
wants an SSR entry of its own is a separate question, and the rows that would answer it are the five
`ssr/*` suites in STAYS (iii), which genuinely do reach one.

**Landed — and the blocker that was recorded never bound.** Both suites are on the package now, at
`implementation/hicasso/test/re_frame/hicasso/host_ssr_dom_cljs_test.cljs` and
`fallback_contents_cljs_test.cljs`, ported by rf2-6rw9 in `d5657428cf` and re-pointed at their
package siblings by rf2-a15c in `26f55428b3`, both in PR #7842. **The correction that outlasts the
status is the premise.** This trio was parked on "the package must gain an SSR entry first", and
`implementation/hicasso/src` *still* has no SSR namespace of any kind — a search of that tree for
any `*ssr*` file returns nothing — yet both witnesses landed anyway, Client-only, on
`react-dom/server` directly. A condition that the work satisfied without ever meeting it was never
the condition. **Nobody should wait on it**, and no bead should be written as though the entry were
a prerequisite for SSR-adjacent package coverage.

### 3. The `render_measure` couple — already in the tier that runs measurements

**The verdict: nightly, tier 3, and the couple is already there. There was no lane decision waiting
to be taken.** `TESTING.md`'s four tier scenarios put measurement in **tier 3, *Nightly / manual***
(`.github/workflows/expensive-tests.yml`), and the couple already sits astride that boundary in
exactly the way the split intends:

- **The OFF half**, `arm1/render_measure_cljs_test.cljs`, is selected by `implementation::node-test`
  and nothing else — `npm run test:cljs`, always-on, tier 1 and tier 2. Correct: with
  `re-frame.performance/enabled?` off there is no measurement to be noisy, only a claim about what
  `mint-view!` wraps.
- **The ON half**, `arm1/render_measure_emit_nightly_test.cljs`, is selected by
  `implementation::node-test-perf-nightly` and nothing else, run by `npm run
  test:cljs-perf-emit-nightly`, whose only scheduled home is `expensive-tests.yml`. The build's own
  comment gives the reason in a line: *"Perf-timing assertions are noisy under per-PR CI runners;
  nightly cadence reduces false negatives from runner load."* It is **not** one of the two rf2-65ajl
  PR exceptions (`test:story-feature-load`, `test:story-static`) and should not become one — those
  exist for gates whose own definition a PR can edit unrun, which is a different problem.

**And the port the row was actually holding is cheaper than it looked.** `:node-test-perf-nightly`
declares no `:source-paths`, so it inherits the config's, and `hicasso/src` and `hicasso/test` are
on that vector. Its selector is `-emit-nightly-test$`, applied with shadow's `re-find`. So a
package-side `re-frame.hicasso.render-measure-emit-nightly-test` would be selected by the existing
nightly build, and its OFF half by `:node-test`'s `cljs-test$` — **no new build, no new lane, no
`test.yml` change, and no double-selection** (`…-emit-nightly-test` does not end in `cljs-test`, and
`:node-test-hicasso`'s `^re-frame\.hicasso\..+-cljs-test$` does not reach it either). This is read
off the build config rather than run.

**Why the tier matters more than this one couple: the spine is deliberately clock-free.** Keeping a
measurement pair off the PR spine is not a scheduling preference, it is a standing repo position
with a written rationale — `tools/story/src/re_frame/story/budgets.cljc` says its latency budgets
are documented targets and that **"the CI gate does NOT assert wall-clock time (flaky in CI)"**,
enforcing instead "the *shape* that makes the targets achievable (bounded output, no O(n²) pass),
not the clock". A measurement couple on the spine would make every PR's wall-clock a gate and
reintroduce that flakiness by the back door, one artefact at a time. The same posture is what puts
rf2-hic-006 and rf2-hic-029 on a quiet machine rather than under concurrent CI load. So the answer
to "which tier owns a measurement pair" is settled repo-wide, and this row only had to be read
against it.

The couple is therefore portable whenever somebody wants the *package's* `mint-view!` measured
rather than the prototype's, and the port carries no hot-zone edge. **Until somebody wants that, it
stays**: what it measures today is the prototype, and the STAYS reasoning above is unchanged. **This
row needs no follow-up bead** — it needed the tier named, and the tier is named.

---

## What this triage does not do

- **It does not touch the frozen bench tree's sources.** `frozen-sources.edn` has one row left
  (`front/slot.cljc`); no row is added or retired, and `check_freeze.py` stays exit 0.
- **It does not pad the count.** 47 of 69 stay. The bench tree is a measured artefact whose readings
  the package's own budgets are set against, and most of what tests it is testing the measurement.
- **It leaves the four largest genuine gaps filed rather than closed**: the six index laws
  (`cell_table_laws`), the cold probe's contract (`cold_read`), the two registry-transition axes
  (`disposed_cell` with `first_registration`), and the ≤2-hook budget counted at React's dispatcher
  (`hook_ledger` with `frame_prop`). Those are re-authorings, and each wants its own bead.
- **It left three sequencing questions for the operator**: the `:ssr` trio (rf2-6rw9), the
  `render_measure` nightly couple, and `front/slot_cljs_test.cljc`'s workflow-gated `.cljc` lane.
  **All three are now settled** — rf2-b6ja, above. Two of them cost nothing to close: the
  `render_measure` couple was already in the tier it belongs to, and the `:ssr` pair was never
  blocked and has since been ported. The third is a documented no, and the measurement behind it is
  the one worth carrying forward — the file already rides two jobs where it sits, and the move is
  what would cost it one.
- **It does not distinguish a `[[wiki-link]]` from a `:require` by tooling, because nothing here
  can.** Three of this document's blockers turned out to be docstring cross-references read as
  dependencies, and one of them was in a correction of the other two. The counts are stated as
  `:require` counts for that reason; see
  [2. the `:ssr` trio](#2-the-ssr-trio--not-blocked-and-the-third-member-is-a-different-bead).
