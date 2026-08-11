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

**Four rows have moved since, and the counts below are the census as it landed rather than the
standing verdict.** rf2-b6ja found that `arm1/host_ssr_dom_cljs_test` (670) and
`arm1/fallback_contents_cljs_test` (376) were parked in STAYS on a block that does not exist. STAYS
(vi) then records two more corrections of the same shape: `front/codec_cljs_test` (1,593) and
`arm1/raw_escape_dom_cljs_test` (403) were parked on the slot-corpus block, which rf2-b6ja settled
as well, and both are marked **PORT, executed** in their own bullets. So the standing verdict is
**17 PORT (8,150) · 9 RE-AUTHORED (3,236) · 43 STAYS (16,649)** — still 69 files and 28,035 lines.
All four keep their single home in STAYS (vi), where each bullet carries the PORT verdict and the
correction; see
[The three sequencing questions, settled](#the-three-sequencing-questions-settled). **All four have
since been ported** (PR #7842); a verdict says where a suite belongs, so executing one changes no
count here.

The original **13 PORT (5,108) · 9 RE-AUTHORED (3,236) · 47 STAYS (19,691)** above is the dated
census and stays as written — the two figures answer different questions, and this document's
convention is that a census is never rewritten by a later correction.

**And every count in this document is a dated census, not a live inventory.** It was taken over 69
files; read on `origin/main` at 2026-08-11 the bench tree holds **63** of them (25,268 lines). The
six `front/*` rows — the first wave the PORT table's sequencing paragraph names — have gone, and the
arithmetic closes exactly: their six line counts sum to 2,767, and 28,035 − 2,767 = 25,268. The two
`:ssr` rows have since gone the same way (rf2-c78g), taking the tree to **61** files and **24,223**
lines. And the nine of
[The nine ports whose originals still stand](#the-nine-ports-whose-originals-still-stand) have now
gone too (rf2-3ewp), taking it to **52** files and **19,885** lines — 24,223 − 4,338, measured
rather than derived. The counts are deliberately left at their census values rather than
tracked, because the verdicts are what this document is for and a decision record that renumbers
itself every time a row is acted on is a ledger nobody can audit. **Read the counts as of the
census; read the tree for what is in it now.**

**A membership subtlety that will otherwise be re-derived every time somebody re-counts.** The
sentence opening this document calls the 69 "`*_cljs_test.cljs` / `.cljc` suites", and that
description is one file wider than it reads: `arm1/render_measure_emit_nightly_test.cljs` (300) is
one of the 69 — it is triaged by name in STAYS (v) — but it is named `*_test.cljs` without the
`_cljs_test` infix, because its lane selects it on `-emit-nightly-test$`. A re-count with the
literal glob therefore returns **one fewer file and 300 fewer lines** at every point in the chain
above, which is exactly the gap between the 24,222 this paragraph used to carry (the arithmetic
25,268 − 670 − 376) and the 24,223 that `wc -l` returns. The measured figures are the ones kept.

## Executing a verdict: a port is a move

**rf2-c78g**, ruled 2026-08-11. A verdict says where a suite belongs. This section says what
*executing* one does to the original, and it is written here once so that no later port has to
re-derive it. The tree was briefly inconsistent about it — the six `front/*` ports deleted their
bench originals and the two `:ssr` ports did not — and this is the settlement.

**The default: a port deletes the original.** Executing a PORT verdict *moves* the suite; it does
not copy it. That matches what six of the eight ports already did, and it is the only option that
cannot rot: one copy, so there is nothing to drift out of step with.

**Why the default goes this way rather than the other.** A duplicated suite drifts **silently**. The
two copies compile in different lanes against different hosts, so a fix applied to one and not the
other leaves a green build with a stale witness still asserting the old behaviour — and the stale
one keeps **passing**. Nothing goes red to announce it, which is the worst shape of test rot. It is
the reasoning the STAYS table already runs on its own account: *"a second copy doubles the
maintenance and the two drift."*

**The exception: a port may leave the original standing, and it has to justify itself in the row.**
Both halves are required — **a recorded reason**, and **a named condition** under which the original
is deleted later. *"We might still want it"* is not a reason. A reason is **a capability the package
copy does not have**, and the capability that has actually occurred is a **host**:
`implementation/hicasso/*` does not arm `implementation_jvm`, so a bench original can be riding a
JVM lane the package copy has none for. That is what decided
[`front/slot_cljs_test.cljc`](#1-frontslot_cljs_testcljc--no-jvm-lane), and it is what the exception
exists for. An exception written down is cheap; a silent duplicate is not.

**And the exception is measured, not assumed.** Before leaving an original standing, measure both
copies' lanes rather than reading the path filters: derive which builds select each namespace from
`implementation/shadow-cljs.edn`'s own `:ns-regexp`s, hand both paths to
`.github/scripts/report-changed-surfaces.sh`, and where a JVM lane is in question, *run* it. The two
`:ssr` rows were put through exactly that; neither had a host of its own and both originals are
deleted. The measurements are in
[2. the `:ssr` trio](#2-the-ssr-trio--not-blocked-and-the-third-member-is-a-different-bead).

### The nine ports whose originals still stand

**rf2-hic-008, 2026-08-11 16:37 AUSEST.** The convention above was ruled while eleven ports were
already on the ground, and it resolved the two it was written about. **It was never applied to the
other nine.** rf2-a15c landed eleven ported suites in PR #7842; rf2-c78g deleted two of their bench
originals; the remaining nine still stand, and under the default stated above every one of them is a
duplicate that should have gone with its port.

They are duplicates by measurement rather than by name. **Eight of the nine package files carry
their bench original's `deftest` roster entire — same names, same count. The ninth carries eight
rows of which seven are identical and one is renamed**, and that one row is the drift this section
goes on to record:

| bench original | lines | deftests | package port | roster |
|---|---|---|---|---|
| `arm1/boundary_intent_dom_cljs_test.cljs` | 453 | 7 | `boundary_intent_dom_cljs_test.cljs` | identical |
| `arm1/callback_form_dom_cljs_test.cljs` | 156 | 3 | `callback_form_dom_cljs_test.cljs` | identical |
| `front/codec_cljs_test.cljs` | 1,593 | 67 | `codec_cljs_test.cljs` | identical |
| `arm1/hframe_cljs_test.cljs` | 360 | 8 | `hframe_cljs_test.cljs` | identical |
| `arm1/hframe_dom_cljs_test.cljs` | 458 | 5 | `hframe_dom_cljs_test.cljs` | identical |
| `arm1/keywarn_dom_cljs_test.cljs` | 190 | 3 | `keywarn_dom_cljs_test.cljs` | identical |
| `arm1/presence_intent_dom_cljs_test.cljs` | 460 | 8 | `presence_intent_dom_cljs_test.cljs` | **one row renamed** |
| `arm1/raw_escape_dom_cljs_test.cljs` | 403 | 6 | `raw_escape_dom_cljs_test.cljs` | identical |
| `arm1/state_dom_cljs_test.cljs` | 265 | 4 | `state_dom_cljs_test.cljs` | identical |

4,338 lines over nine files, out of the bench tree's 61 suites and 24,223 lines (`wc -l`, read on
`origin/main` at the timestamp above).

**The discriminator is a measurement because it also separates the other class.**
`arm1/first_registration_cljs_test.cljs` shares its basename with a package file too and is **not**
in the table: it carries five `deftest`s against the package file's one, and the claims differ. That
is correct and expected — it is a RE-AUTHORED row, rf2-wjag wrote the package's witness from the
behaviour rather than from the file, and a re-authoring leaves its original standing by definition.
A roster comparison puts the nine on one side of that line and `first_registration` on the other,
which a basename match cannot do.

**One pair has already drifted, which is the ruling's own predicted failure mode observed.**
`presence_intent_dom_cljs_test`'s eighth row is
`presence-costs-three-hooks-and-the-shell-still-costs-two` in the bench tree and
`presence-costs-four-hooks-and-the-shell-still-costs-two` on the package. The counts are both right
about their own subject: rf2-6tmu gave the package's presence a root-scoped adoption window — a
second `useContext` — and the prototype never received it. **Both copies are green**, because the
assertion beneath the name is over `(distinct tail)` and a second `useContext` collapses into a name
already in the list, so the whole divergence is carried by the `deftest` name and the prose around
it. That is precisely the shape this section warns about: *"the stale one keeps passing. Nothing goes
red to announce it."* Five days after the ruling, the tree has an instance.

**The reverse-dependency scan is clean, and the two prose references are not requires.** (There were
three, not two — see EXECUTED below.) Each of the
nine namespaces is named by its own `ns` form and by nothing else in the repo. Two docstring mentions
survive: `arm1/raw_escape_dom_cljs_test.cljs` line 6 wiki-links `front.codec-cljs-test`, and both
files are in the table, so they go together; and
`implementation/hicasso/test/re_frame/hicasso/roots_frames_support.cljs` line 53 cites
`arm1.hframe-dom-cljs-test` as the provenance of a technique it reimplements. That second one
dangles when the file goes, and whoever removes it owns re-pointing it — a citation into a deleted
file is a reference to git history, not a link. Neither is a dependency; see
[the `[[wiki-link]]` lesson](#2-the-ssr-trio--not-blocked-and-the-third-member-is-a-different-bead).

**No exception is available for any of the nine.** The only capability the exception has ever
recognised is a host, and rf2-c78g measured that one shut for exactly this file class:
`check_test_lane_bijection.py`'s `loadable_by` gives JVM lanes `.clj` and `.cljc` and nothing else,
so a `.cljs` suite rides no JVM host to lose. All nine are `.cljs`. The default therefore decides
every row.

**EXECUTED — rf2-3ewp, 2026-08-11.** All nine are deleted. The tree is 52 suites and 19,885 lines,
which is 61 − 9 and 24,223 − 4,338 measured rather than derived. The deletion waited on rf2-bl0j
because `compile_gate.cjs` **derives** the lane's namespace roster by walking the directory rather
than listing it — deliberately, in its own words, because *"a roster in this file would be the
staleness class the gate exists to catch"* — so removing nine files changes what that gate compiles,
and two branches changing one derived roster from opposite ends is a merge nobody should have to
referee. rf2-bl0j landed first (PR #7907). Executing behind it needed **no edit to the gate at
all**: the nine live inside the walked directory, so the walk simply narrows, and rf2-bl0j's new
`OUTSIDE_LANE_ENTRIES` roster names four files that all live outside `hicasso/` — none of them one
of these. The `MIN_NAMESPACES` floor was never the obstacle and should not be quoted as one: 40
against a walk that goes from 129 namespaces to 120.

**Three prose citations dangled, not two, and the third is why the count matters.** The scan
recorded above found two by searching for the dotted namespace form. A citation may also name a
suite by its *path*, and two did: `state_cljs_test.cljs` pointed at `arm1/state-dom-cljs-test` and
the Reagent codemod donor suite
(`implementation/adapters/reagent/test/re_frame/reagent_codemod_contract_donor_cljs_test.cljs`)
named the full bench path to `front/codec_cljs_test.cljs` as where its Hicasso half landed. All
three now name the landed package suite. **No gate could have caught any of them** — the package
may not `:require` the bench tree at all, so every reference into it is prose by construction, and
prose is exactly what compiles fine while pointing at nothing. A search for one spelling of a name
is not a search for the name.

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
  **LANDED (rf2-a15c, PR #7842), and the corpus was what unblocked it.** rf2-b6ja settled the slot
  question and found the queue behind it was never a lane question at all — a namespace that defines
  no `deftest` is not a test file, so the bijection gate never reaches a corpus. The corpus went
  across as an ordinary support namespace, `re-frame.hicasso.slot-corpus`, and both this row and the
  one below went with it. The verdict here is therefore **PORT, executed**; the bench original stood
  for a further five days and has since been deleted (rf2-3ewp), which is
  [The nine ports whose originals still stand](#the-nine-ports-whose-originals-still-stand).
- **`arm1/raw_escape_dom_cljs_test.cljs`** (403) — the `[:>]` raw escape against real React. Reads
  `front.codec-cljs-test`'s corpus; queued behind it for the same reason. **LANDED with it** — same
  bead, same PR, and its original deleted by the same rf2-3ewp.
- **`arm1/host_ssr_dom_cljs_test.cljs`** (670) and **`arm1/fallback_contents_cljs_test.cljs`** (376)
  — `defhost`'s `:ssr` policy in all three places it has to hold, and the contract for what an
  `:ssr` fallback may contain. Genuine package behaviour, and at census time
  `implementation/hicasso/` had no `:ssr` witness of its own, so **this pair was the answer to
  rf2-6rw9** — and it is the answer that was taken: **both are now on the package** (PR #7842), and
  the package's `:ssr` gap is closed. **The bench originals are deleted** (rf2-c78g): a port is a
  move, and neither copy carried a host the package copy could not reach. ~~Blocked twice over: the two files `:require` each
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
`.cljc` test file in the bench tree — the other 60 suites there are `.cljs`, and a `.cljs` file is
not loadable by a JVM lane at all: `check_test_lane_bijection.py`'s `loadable_by` gives JVM lanes
`.clj` and `.cljc` and nothing else. (1 + 60 = 61, the tree's live count, not the census's 69 — see
the note under the verdict totals at the top.) `implementation/hicasso/test/` carries not one `.cljc`: all 61 files under it are
`.cljs`, read on `origin/main` at 2026-08-11 — the triage counted 35 and the tree has grown since,
while the extension has not, which is the half of the claim that is load-bearing. So the
lane would open for one file and stay at one file until somebody *authors* a `.cljc` suite. The
package has **five** `.cljc` namespaces to author one against, read on `origin/main` at 2026-08-11 —
this triage counted three, and `re-frame.hicasso` (the public door) and `re-frame.hicasso.native`
have joined since — but the enumeration changes nothing, because the point was always which of them
is *cheap* to assert on the JVM. Still only `impl/slot.cljc` requires nothing but `clojure.string`:
`impl/state.cljc` reaches `re-frame.events` and `re-frame.subs`, `impl/error.cljc` is the complaint
catalogue's and already gated by `check_complaint_catalogue.py`, `native.cljc` reaches both of those
and React on its `:cljs` branch, and the door is macros. A whole CI job for one file, and no queue
behind it.

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
worth sequencing a hot-zone change for. That is what separates this row from every row in the PORT
table above — those gain package coverage and lose nothing — and it is why the verdict here is a no
rather than a "later".

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
`implementation/hicasso/test/re_frame/hicasso/hook_probe.cljs`, and **five** package suites already
`:require` it — `boundary_intent_dom`, `hframe_dom`, `hook_budget`, `presence_intent_dom` and
`state_dom`. So the third member is unblocked, its port is **rf2-wjag's and not this row's**, and
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

**And the bench originals are now deleted, which is what a port means (rf2-c78g).** Both were left
standing when the pair was ported, so for a few days each suite existed twice. Under
[the convention above](#executing-a-verdict-a-port-is-a-move) that is allowed only with a written
reason, and the only reason that counts is a capability the package copy lacks. Neither copy had
one, measured four ways:

- **Same claims, same size.** Each pair is one suite re-pointed at package namespaces: identical
  line counts (670 and 376) and identical `deftest` counts (12 and 7), and in the lane that ran
  both, identical results — each `host-ssr-dom-cljs-test` **12 tests, 43 assertions**, each
  `fallback-contents-cljs-test` **7 tests, 37 assertions**.
- **The package copy rides a strict SUPERSET of the bench copy's lanes.** Derived from
  `shadow-cljs.edn`'s own `:ns-regexp`s, the way
  `implementation/scripts/_browser-dom-lane-partition.test.cjs` derives them rather than by reading
  the patterns off the page: `host_ssr` bench is selected by `:node-test` and `:browser-test`,
  `host_ssr` package by those two **and** `:node-test-hicasso`; `fallback_contents` bench by
  `:node-test`, package by that **and** `:node-test-hicasso`. Every host the original reached, the
  port reaches, and one more.
- **The `implementation_jvm` case — the one the exception exists for — does not reach these two,
  because they are `.cljs`.** The bench paths do arm `implementation_jvm=true` in
  `report-changed-surfaces.sh`, deliberately coarsely, as that arm's own comment says. But arming a
  job is not running a file: `check_test_lane_bijection.py`'s `loadable_by` gives JVM lanes `.clj`
  and `.cljc` and nothing else. Run in `implementation/freehand`,
  `clojure -M:test -n re-frame.bench.hicasso.arm1.host-ssr-dom-cljs-test` answers **0 tests, 0
  assertions** and fails the test-quiet floor rather than passing vacuously, and so does the
  `fallback-contents-cljs-test` namespace — against **3 tests, 92 assertions** from the same command
  on `front/slot-cljs-test`. The whole difference between this row and that one is the file
  extension.
- **Neither closure reaches the other's tree.** Read off the compiler's own analysis cache
  (`.shadow-cljs/builds/node-test/dev/ana/**`) rather than off the `ns` forms, the two closures
  differ by exactly the prototype↔package re-pointing — `arm1.{runtime,mount}`, `front.codec` and
  `lane` against `impl.{collector,mount,codec}`, the door and `checkpoint-support` — and agree on
  every external: `re-frame.core`, `re-frame.adapter.uix`, `re-frame.test-support`,
  `react-dom/server`, plus `react-dom/client` for `host_ssr`. Neither names `ssr.entry`, which is
  the correction above arrived at a second way.

So both rows were the default case, and what the bench tree gives up is a second copy of a claim the
package makes better: on the package the claim is about the shipped code path, and it rides one more
lane while making it.

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
- **It does not execute the verdicts it records, and executing one is not finished until the
  original is gone.** Every PORT row is now across, but nine of the ports left their bench originals
  standing and one of those pairs has already drifted — see
  [The nine ports whose originals still stand](#the-nine-ports-whose-originals-still-stand). That is
  rf2-3ewp's, sequenced behind rf2-bl0j.
- **It does not distinguish a `[[wiki-link]]` from a `:require` by tooling, because nothing here
  can.** Three of this document's blockers turned out to be docstring cross-references read as
  dependencies, and one of them was in a correction of the other two. The counts are stated as
  `:require` counts for that reason; see
  [2. the `:ssr` trio](#2-the-ssr-trio--not-blocked-and-the-third-member-is-a-different-bead).
