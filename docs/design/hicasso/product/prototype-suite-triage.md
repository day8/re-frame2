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
  should start here rather than write a new one. Blocked twice over: the two files `:require` each
  other, so they move together; and both reach `re-frame.bench.hicasso.ssr.entry`, which has no
  package counterpart. Porting them means giving the package an SSR entry first — which is
  rf2-6rw9's work, not a triage's. `arm1/host_hatch_dom_cljs_test.cljs` (1,259), `defhost` end to
  end, is the natural third member of that bead: 21 deftests deep in `arm1.hook-probe` and
  `arm1.lane`, and pointless to move without the `:ssr` half.
- **`front/slot_cljs_test.cljc`** (140) — blocked on a hot-zone change, and the block is worth
  naming precisely. It is `.cljc` on purpose: `scripts/check_test_lane_bijection.py` rule B2
  requires a `.cljc` suite under a CLJS-owned test root to be selected by a CLJS lane as well as a
  JVM one. `implementation/hicasso/deps.edn`'s `:test` alias runs with `--probe` precisely because
  **zero JVM tests is the correct outcome there**, and its own comment states the consequence: *"If
  a JVM-runnable suite ever lands in `test/`, drop `--probe` and take the floor."* Taking the floor
  needs an artefact-roster entry, which is only legal with a matching `test.yml` job, and
  `.github/workflows/` is hot zone. **Sequential access required — this one file cannot move
  without an operator-sequenced workflow change.**

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
