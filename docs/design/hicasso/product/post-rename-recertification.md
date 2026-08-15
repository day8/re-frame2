# Post-rename recertification — what was re-certified, and what could not be

`rf2-hic-090`'s record. The bead re-certifies the evidence after `rf2-hic-066`'s naming sweep, on the
reasoning that a repo-wide rename can move bundle bytes, source coordinates, macro output and server
bytes, so tests and greps are not enough.

**This page returns a PARTIAL certification, and the partiality is the finding rather than a shortfall
in the run.** Two things have to be said before any row below is read.

## 1. The headline change was a CONTRACT change, not a rename

`h/mount!` is the door that moved most visibly in this window, and it did not move as a rename.
`impl.mount/root!` **still exists**, at its own name and its own positional shape
`(container frame-kw hiccup opts)`. What changed is the facade door: `h/mount!` is now a `defn` over
`(node config view)` — [`naming-ledger.md`](naming-ledger.md) row 20's ratified contract — whose config
carries `:frame` and `:initial-events`, and it calls `impl.mount/ensure-frame!` to seed the frame
through `rf/make-frame` before `createRoot`.

The facade says so in its own words — *"it was never a rename"* — and row 13 records the same verdict:
**"The door was not renamed; it was re-shaped."** Row 20 had already ruled the signature *keep as
taught*, so **the code moved to meet the guide** rather than the guide being swept to meet the code.

This matters to every family below, because the rename-risk premise those families exist to test does
not apply to `mount!` at all. The names that genuinely moved are `hfn` → `h/event` and `hm/rerender!`.

**`h/hydrate!` deliberately does not ensure the frame the way `h/mount!` now does**, because
`re-frame.ssr/hydrate!` must install the server's state first. That asymmetry is by design; a
recertification that smoothed it into symmetry would be recording a fault as a fix.

## 2. The sweep is NOT complete, so nothing here certifies it as complete

`rf2-hic-090` depends on `rf2-hic-066`, and `rf2-hic-066` is closed — but it closed having deliberately
**stopped** on part of its own surface. Two remainders are live:

| Remainder | Where it stands | Why it blocks certification |
|---|---|---|
| Naming-ledger **row 18** — retire `hframe` | Ruled by the operator (2026-08-11); **still unexecuted at 2026-08-15**. The seam it retires in favour of is `rf2-t32wg`, **open and awaiting an operator spec ruling** — zero-arity `rf/capture-frame` refuses inside a Hicasso body, and admitting it contradicts two normative sentences in `spec/002-Frames.md`. | **142 `hframe` occurrences across 40 files** stay put by `rf2-t32wg`'s own instruction — re-measured 2026-08-15 by line count of `git grep -o -h -E '\bhframe\b' -- . ':(exclude).beads'` (the row first recorded 152/39, on a differently-scoped count). That is the ledger's header rule working, not drift — and it means the public surface is not final. |
| Four `:recovery` keywords still spelling `h-fn` — **DISCHARGED 2026-08-15** | `rf2-15bqc`'s **PR #8311 merged at 08:23:16Z**. `grep -rn 'h-fn' implementation/hicasso/src/` returns nothing. | This remainder was the *source-coordinate / error-shape* family's blocker, and it is gone: family 4 was re-run against the landed tree in §5 below. |

**No score on any checkpoint page was recomputed by this bead, and no
[`correction-ledger.md`](correction-ledger.md) row was transitioned.** Checkpoint 4 stands where it
stood — 19 met / 1 not met, overall NOT MET — and its failing conjunct fails on rows unrelated to
naming. Moving it is a re-run, not a recertification, and it is not this bead's.

## 3. What WAS re-certified

The records were read against the shipped door and four were found asserting a callback-form spelling
the door does not carry. Naming-ledger row 1 was **ruled `h/event`** (operator, 2026-08-11) and swept by
`rf2-hic-066`, with `h/handler` rejected as a cross-adaptor false friend; these four had not followed.

| Record | What it asserted falsely | What it now says |
|---|---|---|
| [`specification.md`](specification.md) §4, §4.1 | the facade table row read `h/handler`; §4.1 read *"`h/handler` has one meaning everywhere"* | `h/event`, and the position selects the contract — HD-024 tabulates three, so position-invariance was false independently of the name. `rf2-0fd3b` owns making that table travel with the name. |
| [`lanes/ergonomics-api.md`](lanes/ergonomics-api.md) | the same two faults, plus law 5 and the surface-exclusions row | `h/event` throughout; the position-invariance clause corrected and attributed. |
| [`architecture-census.md`](architecture-census.md) | the **M2 macro census** listed `hfn` as one of the six macros the package defines — a row pinned to a re-runnable command | `event`. The pinned command was re-run: **anchored 6, unanchored 6, still agreeing**. Only the name moved; no count was recomputed. |
| [`facade-freeze.md`](facade-freeze.md) §5 | `hfn` as a live door name; `h/handler` as an open spelling question; row 18 as a *recommendation* | annotated where §1's `root!`/`mount!` row was annotated. **The `hframe` row is deliberately left open** and now says the ruling is unexecuted. |

The remaining lanes and the `mount!`/`root!` residue were checked and found **already correct** —
earlier sweeps (`rf2-ewn6y`, `rf2-mha1r`, `rf2-wxqhh`, `rf2-jj15h`) had reached them.
[`decision-brief.md`](decision-brief.md) **was certified here as already correct, and that reading was
wrong** — corrected 2026-08-15 under `rf2-0fd3b`, on the audit of PR #8317. Its Part II ergonomics
summary still read *"`h/fn` becomes `h/handler` with one invariant meaning"*: a name with no referent
on the door, carrying a contract HD-024 contradicts. It now names `h/event` and the position-selected
contract. The miss was this page's rather than the sweep's — the record was certified without being
read down to the clause. Hits in [`authoring-report-slice.md`](authoring-report-slice.md),
[`authoring-report-todo.md`](authoring-report-todo.md), [`naming-findings-cp2.md`](naming-findings-cp2.md)
and [`prototype-suite-triage.md`](prototype-suite-triage.md) were **kept**: they record what two witness
applications typed, what a checkpoint found, and what the frozen prototype suite tests, and a record
that aged is not a record that was wrong. [`callback-identity-verdict.md`](callback-identity-verdict.md)'s
`:hfn-inline` / `:hfn-hoisted` rows were kept because they quote **arm keywords that are live in the
test source today**; correcting the page would make it disagree with the code it reports. **Both
halves have since moved together** (`rf2-0ftho`, 2026-08-15): the arm keywords in
`retaining_host_callbacks_dom_cljs_test.cljs` are `:event-inline` / `:event-hoisted`, the var they
bind is `hoisted-event`, and the verdict page's rows follow them — so the page still quotes the
source verbatim, at the new spelling.

## 4. The re-run roster, and what it was possible to re-run

The bead names five evidence families. This is where each one actually lives, so `rf2-hic-064` re-runs
them rather than re-deriving them.

**This table's status column was written on 2026-08-15 at 08:33Z, when three of the five were
deliberately not green-or-red because their surfaces were mid-flight. That reason expired the same
morning** — `#8308` merged at 08:23:01Z, `#8311` at 08:23:16Z, both *before* this page landed, and the
page did not follow. The four runnable families were re-run against the landed tree later that day and
the status column now carries those results; §5 records the captured exit codes.

| # | Family | Where it runs | Needs | Status |
|---|---|---|---|---|
| 1 | bundle-isolation / rent sentinels | `npm run build:hicasso-release` (bundle half: `check_production_erasure.cjs`, `check_bundle_isolation.cjs`); `implementation/hicasso/scripts/check_optional_module_reachability.py` (source half) | bundle half: `node_modules` + JVM | **RE-RUN 2026-08-15, both halves green.** (Was: source half only.) |
| 2 | SSR server bytes + hydration witnesses | `npm run test:cljs`, `npm run test:browser`, `cd implementation/ssr && clojure -M:test` | `node_modules` + JVM; browser arm Chromium | **RE-RUN 2026-08-15, all three arms green.** (Was: not run, `#8308` mid-flight — `#8308` has merged.) |
| 3 | native macro-expansion parity | `three_way_parity_cljs_test.cljs` and `expansion_probe.clj`, via `npm run test:cljs` / `test:browser` | `node_modules` + JVM | **RE-RUN 2026-08-15, green.** `expansion_probe.clj` has no JVM lane by design — it is a macro namespace `native_grammar_cljs_test` and `native_surface_cljs_test` consume via `:require-macros`, so the Node lane *is* where it runs. (Was: not run.) |
| 4 | source-coordinate capture | `error_shape_cljs_test.cljs` (dev, coordinate present); `npm run test:browser-prod-elision` → `check_source_coord_elision.cjs` (coordinate erased) | `node_modules` + JVM + Chromium | **RE-RUN 2026-08-15, both arms green**, against a tree that carries `#8311`'s four moved `:recovery` keywords. (Was: not run, `#8311` mid-flight.) |
| 5 | the pinned regression gate | — | — | **DOES NOT EXIST.** See below — unchanged, and the correction stands. |

**Family 5 cannot be re-run because it was never built, and that is a correction to the bead's own
premise rather than a miss.** The 5% same-instrument regression line is ledger row **`C1`**, and
[`budgets.md`](budgets.md) §9.3–§9.4 record it `UNPINNED` and deliberately unbuilt: a heap figure is
distributional, and §7's rule is that a distributional row is never converted into a flaky PR
threshold. It was repointed to `rf2-85og2`. The nearest re-runnable gates are the two below.

### What was re-run, with captured exit codes

| Gate | Self-test | Gate | Result |
|---|---|---|---|
| `implementation/hicasso/scripts/check_freeze.py` | `0` | `0` | 1 frozen row matches; each package file is its donor moved. **Read the caveat below.** |
| `implementation/hicasso/scripts/check_budget_ledger.py` | `0` | `0` | 49 rows — 31 MET, 5 BREACH, 3 UNRESOLVED, 10 UNPINNED. **Identical to the pre-sweep tally**, so the rename moved no budget row. |
| `implementation/hicasso/scripts/check_optional_module_reachability.py` | — | `0` | motion, overlay, native, forms and server all unreachable from the public door; UIx required by no `src/` namespace. |

**The freeze gate's green is narrower than it looks, and must not be quoted as certifying the rename.**
`check_freeze.py` is the gate a repo-wide rename is most likely to redden, because it reconstructs each
package file from its donor by applying a `:renames` table at symbol boundaries. But its
`arm1/lang.clj` row — the row carrying `defview`, `event` and `defhost` — was **retired** under
`rf2-0xgk`, and `frozen-sources.edn` says so in its own words: the authoring surface *"is now the
least-checked thing here."* So the gate passes on **one** row, and that row is not the surface `hfn` →
`h/event` moved. Family 3 is what would catch a macro-expansion fault — **and family 3 has since been
run, green** (§5). The freeze gate's narrowness stands as a caveat on the freeze gate; it is no longer
the only thing standing between the rename and a macro-expansion fault.

**The refusal recorded here was located at its source, and it has now been discharged rather than
merely aged.** It named two conditions: that PRs #8308 and #8311 land, and that the re-run happen on a
checkout with real `node_modules` rather than one that would report the worktree. Both are met — see §5.

## 5. The re-run, 2026-08-15, against the landed tree

`rf2-hic-090` was reopened by the audit of its own PR (`#8312`, merged 08:33:32Z): the topology that
justified §4's three "mid-flight" statuses had already changed when the page landed. This section is
that re-run. It was performed on a fresh worktree off `origin/main` at `f167edd4bc` with a real
`npm ci` (not a junction), each gate foregrounded to completion one at a time — concurrent shadow-cljs
builds share one cache — and each exit code captured from the runner rather than read off a log.

| Family | Command | Captured exit | What it reported |
|---|---|---|---|
| 2, 3, 4 (source half) | `npm run test:cljs` | `0` | Ran **13944 tests / 70239 assertions, 0 failures, 0 errors**. Carries `three_way_parity_cljs_test`, `error_shape_cljs_test`, and the two `expansion-probe` consumers. |
| 2, 3 (browser arm) | `npm run test:browser` | `0` | Ran **1552 tests / 9850 assertions, 0 failures, 0 errors**. |
| 2 (SSR JVM arm) | `cd implementation/ssr && clojure -M:test` | `0` | Ran **618 tests / 2971 assertions, 0 failures, 0 errors**. |
| 1 (bundle half) | `npm run build:hicasso-release` | `0` | Release build 162 files / 107 compiled / 0 warnings. Production erasure: self-test OK, **5 sentinels absent, 3 positive controls present**. Bundle isolation: self-test OK, **8 sentinels absent, 4 positive controls present**. |
| 4 (elision arm) | `npm run test:browser-prod-elision` | `0` | **No `defview`/`defhost` source coordinate in the advanced bundle, positive control present**; 129 tests / 547 assertions, 0 failures, 0 errors. |

**What this re-run does and does not certify.** It certifies the four runnable families against the
tree as it stands, which is what the bead asked for. It does **not** certify the sweep as complete:
§2's row-18 remainder is still live, `rf2-t32wg` is still open and awaiting an operator spec ruling,
and 142 `hframe` occurrences stay put by that bead's own instruction. Family 5 remains a correction to
the bead's premise rather than a gap — the distributional `C1` row was deliberately never made a PR
gate, and that reading is unchanged.
