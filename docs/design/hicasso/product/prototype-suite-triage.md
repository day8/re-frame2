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
[The three sequencing questions, settled](#the-three-sequencing-questions-settled).

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
triage's to take. **Flagged for a follow-up bead rather than ported.**

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
  `:ssr` fallback may contain. Genuine package behaviour, and `implementation/hicasso/` has no
  `:ssr` witness of its own, so **this pair is the answer to rf2-6rw9** and whoever takes that bead
  should start here rather than write a new one. ~~Blocked twice over: the two files `:require` each
  other, so they move together; and both reach `re-frame.bench.hicasso.ssr.entry`, which has no
  package counterpart. Porting them means giving the package an SSR entry first — which is
  rf2-6rw9's work, not a triage's.~~ **CORRECTED (rf2-b6ja): neither block exists. The files do not
  `:require` each other and neither reaches `ssr.entry`; both readings came from docstring
  `[[wiki-links]]`.** The pair is an ordinary `arm1/*` port and is **PORT**, not STAYS — see
  [2. the `:ssr` trio](#2-the-ssr-trio--not-blocked-and-the-third-member-is-a-different-bead).
  `arm1/host_hatch_dom_cljs_test.cljs` (1,259) is **not** the third member of that bead: it is the
  only one of the three that needs `arm1.hook-probe`, which is the RE-AUTHORED blocker
  `hook_ledger_dom_cljs_test` and `frame_prop_dom_cljs_test` already carry, and it belongs with
  those rather than with the `:ssr` pair. It stays here, blocked on the probe.
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
and nothing else. `implementation/hicasso/test/` is 35 test files, every one of them `.cljs`. So the
lane would open for one file and stay at one file until somebody *authors* a `.cljc` suite, and the
package offers only three namespaces to author one against — `impl/slot.cljc`, `impl/state.cljc`,
`impl/error.cljc` — of which only `slot` requires nothing but `clojure.string` (`state` reaches
`re-frame.events` and `re-frame.subs`; `error` is the complaint catalogue's, already gated by
`check_complaint_catalogue.py`). A whole CI job for one file, and no queue behind it.

**And the cheap answer holds, which is what makes the no comfortable rather than merely thrifty.**
The cross-host claim is armed four ways already, none of them a new lane:

- **The pin runs on both hosts today.** Asking the bijection gate's own lane model which lanes
  select this file — `select()` in `check_test_lane_bijection.py`, roots and all — answers exactly
  two: `implementation::node-test` (the always-on CLJS lane) and `implementation/freehand` (the JVM
  lane, on `scripts/test-jvm-implementation.sh`'s roster with its matching `test.yml` job). One
  corpus, one implementation, two runtimes — which is the whole mechanism the file's own docstring
  claims, and it is intact.
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
  all. `ssr.entry` has exactly seven requirers in the tree, and they are the five `ssr/*` suites
  already parked in STAYS (iii) — `entry_cljs_test`, `hframe_ssr_cljs_test`,
  `instance_key_payload_dom_cljs_test`, `spike_cljs_test`, `spike_dom_cljs_test` — plus the entry's
  own `ssr/node.cljs` driver and `ssr/fixtures.cljs` corpus. **These two render to string with
  React's own `react-dom/server`**, which they import directly. The bake driver is not on their
  path, and the package needs no SSR entry to receive them.

So the pair is an `arm1/*` port of exactly the shape the PORT table's sequencing paragraph
describes: re-point `arm1.runtime` at the six modules, `arm1.lang`'s macros at the public door
`re-frame.hicasso`, `arm1.mount` at `impl.mount`, `front.codec` at `impl.codec`. Their only `lane`
call is `lane/leave-act-environment!`, once each, and its package counterpart already exists —
`re-frame.hicasso.roots-frames-support/leave-act-environment!`, which cites the prototype by name.
**They still move together**, but for the reason their own docstrings give rather than for a
require: each is written as the other's arm, and the bare arm is the one that matters. A witness
that only ever supplies a fallback proves nothing about the default, and `:client-only` is what an
author gets by writing nothing.

**`arm1/host_hatch_dom_cljs_test.cljs` (1,259) is blocked, on something else.** It is the only one
of the three whose `ns` form requires `arm1.hook-probe`, and it uses it — `probe/install!`,
`probe/record!`. Hook-probe proxies React's internal dispatcher slot and has no package
counterpart, which is precisely the blocker RE-AUTHORED already records for
`hook_ledger_dom_cljs_test` and `frame_prop_dom_cljs_test`. **It belongs with that bead**, whose
deliverable is the probe. Bundling it with the `:ssr` pair would have held two unblocked suites
behind a probe neither of them needs — on top of an SSR entry none of the three needs.

**rf2-6rw9's content changes accordingly.** The bead reads "give the package an SSR entry, then port
the pair onto it". The entry is not on the path: give the package the pair. Whether the package ever
wants an SSR entry of its own is a separate question, and the rows that would answer it are the five
`ssr/*` suites in STAYS (iii), which genuinely do reach one.

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
- **It leaves three sequencing questions for the operator**: the `:ssr` trio (rf2-6rw9), the
  `render_measure` nightly couple, and `front/slot_cljs_test.cljc`'s workflow-gated `.cljc` lane.
