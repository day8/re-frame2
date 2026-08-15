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
| Naming-ledger **row 18** — retire `hframe` | Ruled by the operator (2026-08-11); **unexecuted**. The seam it retires in favour of is `rf2-t32wg`, **open and awaiting an operator spec ruling** — zero-arity `rf/capture-frame` refuses inside a Hicasso body, and admitting it contradicts two normative sentences in `spec/002-Frames.md`. | 152 `hframe` occurrences across 39 files stay put by `rf2-t32wg`'s own instruction. That is the ledger's header rule working, not drift — and it means the public surface is not final. |
| Four `:recovery` keywords still spelling `h-fn` | `rf2-15bqc`, bead-closed against **PR #8311, which is still open**. Beads here close on PR-open, so closed is not landed. | These are error-message recovery keywords: they are precisely the *source-coordinate / error-shape* family's subject matter. Certifying that family against a tree that does not yet carry them would certify the wrong tree. |

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

[`decision-brief.md`](decision-brief.md), the remaining lanes, and the `mount!`/`root!` residue were
checked and found **already correct** — earlier sweeps (`rf2-ewn6y`, `rf2-mha1r`, `rf2-wxqhh`,
`rf2-jj15h`) had reached them. Hits in [`authoring-report-slice.md`](authoring-report-slice.md),
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
them rather than re-deriving them. **The status column is this bead's, and three of the five are
deliberately not green-or-red here.**

| # | Family | Where it runs | Needs | Status here |
|---|---|---|---|---|
| 1 | bundle-isolation / rent sentinels | `npm run build:hicasso-release` (bundle half: `check_production_erasure.cjs`, `check_bundle_isolation.cjs`); `implementation/hicasso/scripts/check_optional_module_reachability.py` (source half) | bundle half: `node_modules` + JVM | **source half RE-RUN, green.** Bundle half not run — needs a release build. |
| 2 | SSR server bytes + hydration witnesses | `npm run test:cljs`, `npm run test:browser`, `cd implementation/ssr && clojure -M:test` | `node_modules` + JVM; browser arm Chromium | **Not run — the surface is mid-flight** (PR #8308 rewrites `implementation/ssr`). |
| 3 | native macro-expansion parity | `three_way_parity_cljs_test.cljs` and `expansion_probe.clj`, via `npm run test:cljs` / `test:browser` | `node_modules` + JVM | Not run — clean-checkout work. |
| 4 | source-coordinate capture | `error_shape_cljs_test.cljs` (dev, coordinate present); `npm run test:browser-prod-elision` → `check_source_coord_elision.cjs` (coordinate erased) | `node_modules` + JVM + Chromium | **Not run — the surface is mid-flight** (PR #8311 moves four `:recovery` keywords in the very refusals this family reads). |
| 5 | the pinned regression gate | — | — | **DOES NOT EXIST.** See below. |

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
`h/event` moved. Family 3 is what would catch a macro-expansion fault, and family 3 was not run.

**The three unrun families are a refusal located at its source, not a deferral.** The re-run belongs
after `rf2-t32wg` is ruled and PRs #8308 and #8311 have landed, on the clean checkout `rf2-hic-064` §2
already specifies — running them from a worktree with no `node_modules` would report the worktree
rather than the tree.
