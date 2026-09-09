# Post-rename recertification — the 2026-08-18 re-run

`rf2-hic-090`'s third results record. [`post-rename-recertification.md`](post-rename-recertification.md)
is the parent page: it carries the bead's roster of five evidence families, §5's run at `f167edd4bc`
and §6's run at `7304e825c9`. **This page does not restate those runs and does not correct them.** It
records one thing — the five families re-run against `f5b1f1e94f`, with each exit code captured from
the runner — and it says which of them can be shown to still bite.

**Every figure below is a COUNTER or a BYTE COUNT, and none is a clock reading.** Five other workers
were running gates on this machine throughout, so an elapsed-time figure taken here would be a
measurement of the load rather than of the tree. Counters and bytes read the same under load, which is
why they are the only estimands this page carries. Where the parent's tables quote a compile duration,
this one deliberately does not.

## 1. Why a third run — the trigger, tested rather than assumed

The mayor's 2026-08-16 ruling set the re-run condition in as many words: the four green families

> do not expire in the meantime — but re-check them if anything the size of PR #8322 lands again,
> because this run PROVED they are not stable across such a change.

So the first question is whether the trunk has moved onto the surfaces these families measure. It has.

| What was measured | Command | Result |
|---|---|---|
| trunk movement since the last certified base | `git rev-list --count 7304e825c9..origin/main` | **175** commits |
| its size | `git diff --shortstat 7304e825c9..origin/main` | **219** files, 8365 insertions, 3307 deletions |
| its size on the surfaces these families read | `git diff --stat 7304e825c9..origin/main -- implementation docs` | **122** files, 4774 insertions, 707 deletions |

That is smaller than PR #8322, which deleted 607 files — but the count is the weaker half of the test
and the paths are the stronger one. **The gate scripts themselves moved.**
`git diff --name-only 7304e825c9..origin/main` names `implementation/scripts/check-bundle-isolation.cjs`,
`implementation/shadow-cljs.edn`, five files under `implementation/ssr/src/re_frame/ssr/`
(`boot`, `emit`, `install`, `manifest`, `payload_policy`) and eight under
`implementation/hicasso/src/re_frame/hicasso/` including `hicasso.cljc` itself, `impl/boundary.cljs`,
`impl/intent.cljs` and `impl/mount.cljs`. A recertification whose instruments and whose subject have
both moved is due on its own terms, independently of any file count.

**The parent page's own stop condition was re-tested and it still holds.** `rf2-t32wg` — the row-18
seam the certification waits on — is `DEFERRED` to 2026-09-16 and unruled, read from `bd show` rather
than carried forward from the parent. So this run changes what is measured and changes nothing about
what is certified overall; see [§6](#6-what-this-still-does-not-certify).

## 2. The base, and the tree it was taken on

| | |
|---|---|
| base | `f5b1f1e94fbfe440de2fa5b70170c34dc46a61de`, `origin/main` at 2026-08-18 00:03 AUSEST |
| worktree | a dedicated worker worktree on `worker/recert-hic090`; `scripts/assert-worker-worktree.sh` ran there and exited `0`. **The literal path is deliberately not written down** — `scripts/check-no-hardcoded-paths.sh` reds a tracked file carrying a personal home path, and it reds correctly: a machine-specific string is not what makes the guard evidence. That the guard ran and passed is. |
| `node_modules` | a junction into the primary checkout's real one, **103** top-level entries by `Get-ChildItem \| Measure-Object`, removed as the last act of this work |
| open PRs touching a family surface | none — `#8440` is `tools/xray`, `#8443` is `implementation/core/test/re_frame/bench`, `#8442` is `docs/design/hicasso/studio`, `#8444` is `.github/workflows` |

**Every family was run one at a time and never in parallel**, and the reason is not politeness about
wall-clock. Two heavyweight suites here have once *wedged* rather than failed — each holding several
gigabytes, neither returning and neither reporting anything. That is contention for the machine, which
no naming discipline touches. Free physical memory was **15.94 GB of 63.43 GB** when the first heavy
gate started, with one peer JVM at 4.88 GB and one peer Node process at 2.68 GB.

Each exit code below is the runner's own, captured by an `echo` on the same command line as the
redirect. No number here is one the harness reported about a run.

## 3. Family 1 — bundle isolation and the rent sentinels

The bead's phrasing is *interpreted-only zero-rent*, and it is two gates rather than one: the source
half decides reachability over `:require` forms, and the bundle half reads a real `:advanced` +
`goog.DEBUG=false` build. Neither answers the other's question, so both are run.

| Half | Command | Captured exit | What it reported |
|---|---|---|---|
| source | `python hicasso/scripts/check_optional_module_reachability.py --self-test` | `0` | self-test OK |
| source | `python hicasso/scripts/check_optional_module_reachability.py` | `0` | motion, overlay, native, forms and server all unreachable from the public door; UIx required by no `src/` namespace and named by no production coordinate |
| bundle | `npx shadow-cljs release hicasso-release` | `0` | **162 files, 107 compiled, 0 warnings** |
| bundle | `node hicasso/scripts/check_production_erasure.cjs --self-test` | `0` | self-test OK (5 sentinels, 3 positive controls) |
| bundle | `node hicasso/scripts/check_production_erasure.cjs` | `0` | no dev-only Hicasso surface in the bundle — **5 sentinels absent, 3 positive controls present** |
| bundle | `node hicasso/scripts/check_bundle_isolation.cjs --self-test` | `0` | self-test OK (8 sentinels, 4 positive controls) |
| bundle | `node hicasso/scripts/check_bundle_isolation.cjs` | `0` | no isolated surface reached the interpreted-only bundle — **8 sentinels absent, 4 positive controls present** |

**Verdict: GREEN.** The composite `npm run build:hicasso-release` chains all five bundle-half steps; it
was split into its steps here so that each verdict is a foreground capture of its own rather than one
status standing for five.

**The bundle byte count at this base is 671290 bytes** (`wc -c out/hicasso-release/main.js`), beside a
4806-byte `manifest.edn`. That figure is recorded because the bead exists on the premise that a
repo-wide change moves bundle bytes, and a certification that never states the byte count cannot be
compared against by the next one. **It is stated of `f5b1f1e94f` and of nothing else** — there is no
earlier byte figure in the parent page to difference it against, so this run establishes the anchor
rather than reporting a delta.

**The file and compile counts did not move**: 162 files and 107 compiled, exactly as §6.3 recorded at
`7304e825c9`. That is a finding rather than a null result, because the 122-file trunk movement in §1
includes eight files under `implementation/hicasso/src/`. The compile *population* is unchanged; what
moved inside it did not change its membership.

## 4. Family 5 — the pinned regression gate

**It still does not exist, and this was re-derived at source rather than carried forward.**
[`budgets.md`](budgets.md) line 1346 is the operative row:

| Row | Estimand | Status | Instrument | Owner |
|---|---|---|---|---|
| `C1` | ≤ 5% regression on the same witness and instrument | `UNPINNED` | `— (none)` | `rf2-85og2` |

`rf2-85og2` is open, titled as the remainder needing *a quiet-box window*, and the C1 row is one of the
three gates it names. **There is no runnable gate here, so there is no exit code to quote and no
verdict to give.** The honest reading is the parent page's and it is unchanged: the 5% same-instrument
line is distributional, §7 of `budgets.md` forbids converting a distributional row into a flaky PR
threshold, and the row was deliberately never built.

**This is the one family where the brief's own framing is worth quoting back**: a family with no
runnable gate at HEAD is a finding about the evidence base, and it is worth more than a substitute
invented to fill the row. Nothing was invented.

Note also what family 5 would be if it existed: a **clock** estimand. It could not honestly be run on
this machine on this day whatever its pinning status, because five workers were running gates
throughout. That is a second, independent reason this row stays empty here.

## 5. Families 2, 3 and 4 — SSR bytes, macro parity, source coordinates

These three families share their runners, which is why they share a section rather than one each. The
parent page's §4 roster is where that mapping is established; it was re-checked at source here and it
still holds — the witness namespaces `three_way_parity_cljs_test`, `error_shape_cljs_test`,
`native_grammar_cljs_test` and `native_surface_cljs_test` are all present in the built node-test
bundle, each found by a fixed-string search of `out/node-test.js`.

Every composite `npm run` script below was split into its steps so that each verdict is its own
foreground capture rather than one status standing for a chain.

| Family | Command | Captured exit | What it reported, at `f5b1f1e94f` |
|---|---|---|---|
| 2, 3, 4 — node lane, compile | `node scripts/compile-node-test.cjs node-test out/node-test.js` | `0` | **1985 files, 1984 compiled, 0 warnings** |
| 2, 3, 4 — node lane, run | `node out/node-test.js` | `0` | **11771 tests, 59589 assertions, 0 failures, 0 errors** |
| 2, 3 — browser lane, compile | `npx shadow-cljs compile browser-test` | `0` | **1075 files, 1074 compiled, 0 warnings** |
| 2, 3 — browser lane, run | `node scripts/serve-and-run-browser-tests.cjs` | `0` | **1015 tests, 5724 assertions, 0 failures, 0 errors** |
| 2 — SSR JVM arm | `clojure -M:test` in `implementation/ssr` | `0` | **599 tests, 2925 assertions, 0 failures, 0 errors** |
| 4 — elision arm, build | `npx shadow-cljs release browser-test-prod-elision` | `0` | **312 files, 252 compiled, 0 warnings**; bundle **1968527 bytes** |
| 4 — elision arm, coordinate check | `node hicasso/scripts/check_source_coord_elision.cjs` | `0` | no `defview`/`defhost` source coordinate in the advanced bundle, **positive control present** |
| 4 — elision arm, browser run | `node scripts/serve-and-run-browser-tests.cjs --root out/browser-test-prod-elision --port 8023 --duplicate-done-drift-unverifiable` | `0` | **92 tests, 295 assertions, 0 failures, 0 errors** |

**Verdict: GREEN for families 2, 3 and 4.**

### 5.1 The counts did not move, and that is this run's substantive finding

§6.4 of the parent page recorded the opposite outcome and drew the right lesson from it: every suite
came back smaller after PR #8322, each drop pinned to a landed deletion, and *a re-run that reproduced
the old counts would have been the finding*. This run is the other case, so the same discipline applies
in reverse — the identity has to be stated rather than passed over.

| Arm | parent §6.3, at `7304e825c9` | here, at `f5b1f1e94f` | moved? |
|---|---|---|---|
| node lane | 11771 tests / 59589 assertions | 11771 / 59589 | no |
| browser lane | 1015 / 5724 | 1015 / 5724 | no |
| SSR JVM | 599 / 2925 | 599 / 2925 | no |
| elision lane | 92 / 295 | 92 / 295 | no |
| `hicasso-release` build | 162 files / 107 compiled | 162 / 107 | no |
| node-lane compile | 1985 files / 1984 compiled | 1985 / 1984 | no |
| browser-lane compile | 1075 files / 1074 compiled | 1075 / 1074 | no |

**Read this as what it is.** 175 commits and 219 files landed in the interval (§1), and none of them
added or removed a test in these four suites or a file in these three builds. That is consistent with
the interval's character — fixes and records rather than deletions or new lanes — and it is *not*
evidence that the gates ran over a cached artefact, because
[§7](#7-the-controls--which-gates-were-shown-to-still-bite) plants **five** faults across **four** of
this page's gate runners — the reachability script, the bundle-isolation script, the SSR JVM lane and
the node lane — on this same base, and every one of them comes back red.

**One number DID move, and it moved to zero.** The browser lane compiled **4 warnings** at
`7304e825c9` and compiles **0** here. All four were `:infer-warning` on `(.-server (n/marker …))` in
`native_ssr_dom_cljs_test.cljs`; the previous recertification filed them as `rf2-wqalj`, and that bead
is now **closed** by PR `#8416`, landed as `5c7b0febe3` — the only commit to touch that file in the
interval. So the drop has exactly one candidate cause and it is a repair. `rf2-wqalj`'s own close
reason states the suite was unmoved at 1015 tests / 5724 assertions, which is independently what the
row above measures.

## 6. What this still does not certify

**The overall certification remains PARTIAL, and for the same reason as the parent page's.** Nothing
here disturbs the mayor's 2026-08-15 ruling: what holds `rf2-hic-090` open is not the five families but
naming-ledger row 18, and row 18 is uncertified because `rf2-t32wg` needs an operator spec ruling on
admitting zero-arity `rf/capture-frame` and `rf/current-frame-id` inside a Hicasso body.

That fence was re-tested at source for this run rather than carried forward. `rf2-t32wg` is `DEFERRED`
to 2026-09-16 and **unruled** — a defer schedules the question rather than answering it, so the release
condition the mayor stated is unmet: this bead closes when `rf2-t32wg` is ruled and row 18 is executed,
or when the operator rules that row 18 need not block certification. Both are operator calls, and
neither is a worker's to make.

Two further boundaries are worth stating plainly, because a certification record is exactly the
document whose careful sentences get quoted one notch stronger later.

- **Four families green is not five.** Family 5 has no gate, so it has no verdict — not a green one and
  not a red one. See [§4](#4-family-5--the-pinned-regression-gate).
- **The byte counts here are anchors, not comparisons.** 671290 bytes for `hicasso-release` and 1968527
  for the elision bundle are stated of `f5b1f1e94f`. No earlier byte figure exists in this corpus to
  difference them against, so nothing here licenses a claim about whether the bundle grew or shrank.

**Nothing was filed in [`correction-ledger.md`](correction-ledger.md), because there was no red to
file.** Every captured exit in §3 and §5 is the runner's own `0`.

## 7. The controls — which gates were shown to still bite

A certification's characteristic failure is a gate that returns green because it ran over nothing, so
green is only worth as much as the demonstration that red was available. Each control below planted a
fault, ran the gate, checked that the failure named the plant, restored, and verified the restore by
**content hash against the committed object** — never by reading a diff, because this checkout
translates line endings and a plain byte digest reports a correct restore as failed.

| Family | What was planted | Gate under the plant | Captured exit | What the red said |
|---|---|---|---|---|
| 1, source half | one `:require` of `re-frame.hicasso.motion` in the public door's `ns` form | `check_optional_module_reachability.py` | `1` | named the file and the optional module by name |
| 1, bundle half | a `:require` of `re-frame.hicasso.native` plus one reachable `n/marker` call in the consumer app's `-main` | `npx shadow-cljs release hicasso-release` then `check_bundle_isolation.cjs` | build `0`, gate `1` | `OPTIONAL SURFACE LEAKED: native tier`, quoting sentinel `"rf2:hicasso-native-tier"` |
| 2, SSR JVM arm | one string-literal marker prepended to text-node output in `ssr/emit.cljc`'s `emit-element` | `clojure -M:test` in `implementation/ssr` | `1` | **97 failures, 0 errors**, over the same **599 tests / 2925 assertions** as the control run — so no namespace crashed and the plant was scoped to assertions rather than to the lane |
| 3, macro expansion | `head-form` in `native.cljc` — the `n/$` macro's expansion-time lowering of a keyword head — made to append a marker to the element name | node lane, compile then run | compile `0`, run `1` | `three_way_parity_cljs_test.cljs:338:15`, **8 failures** on *the-three-routes-render-the-same-server-bytes* and **7** on *the-three-routes-build-the-same-element-shape* — the native arm's expansion no longer agrees with handwritten React |
| 4, source coordinate | `defview`'s captured coordinate in `hicasso.cljc` given `:line 0` at expansion | node lane, same run | run `1` | `error_shape_cljs_test.cljs:143:11` — expected `:line? true`, actual `:line? false` |

| Family | Baseline content hash | Under the plant | After restore |
|---|---|---|---|
| 1, source half — `hicasso/src/re_frame/hicasso.cljc` | `8641b387629974f2564fe9cbf16748ce1473bfa7` | `a3d28cb07e3a624915ca3f3378176885cc90b455` | `8641b387629974f2564fe9cbf16748ce1473bfa7` |
| 1, bundle half — `hicasso/test/re_frame/hicasso/consumer_app.cljs` | `2226b91b0c9579aa04bee412c3ef28b4886c844f` | `ff8ceed08d856dee11c3e72cc0c12f96f7febe1c` | `2226b91b0c9579aa04bee412c3ef28b4886c844f` |
| 2 — `ssr/src/re_frame/ssr/emit.cljc` | `484e5f9abdee993a841a46da0583006e22cd23b1` | `7fbc629c7eee7aafce8fa651fd45cf8197d7eb75` | `484e5f9abdee993a841a46da0583006e22cd23b1` |
| 3 — `hicasso/src/re_frame/hicasso/native.cljc` | `352f1e3d6342e334c39331186454fda8235a4116` | `d7f4272fafc8407cf4e9c49624873d633a4c2902` | `352f1e3d6342e334c39331186454fda8235a4116` |
| 4 — `hicasso/src/re_frame/hicasso.cljc` | `8641b387629974f2564fe9cbf16748ce1473bfa7` | `7c4659d24bc7791391854615bd9f0c47500a059f` | `8641b387629974f2564fe9cbf16748ce1473bfa7` |

**Each baseline hash equals the object committed at `f5b1f1e94f`, and each restored hash equals the
baseline** — so every plant is proved to have applied rather than silently no-opped, and every restore
is proved exact.

**The plants also answer a second question the greens cannot: which tree the gate read.** Each fault
existed only in this worker's worktree, so a run that had wandered into a sibling checkout would have
come back green. The shadow-cljs runs corroborate it a second way, by printing the absolute path of the
`shadow-cljs.edn` they loaded on their first line — it named this worker's worktree on every heavy run,
never a sibling's.

**Two further gates were shown to bite, unplanned, in the course of this work**, and they are recorded
because a control that arrives by accident is still a control.

- `scripts/check_doc_slugs.py` returned exit `1` on this page's first draft, naming a forward reference
  to a heading that did not yet exist; exit `0` after the heading was added.
- `scripts/check-no-hardcoded-paths.sh` returned exit `1` on a deliberately planted home path, firing
  both its literal-user rule and its personal-named-path rule; exit `0` after the restore, with the
  page's content hash back at `fd8ab39c33681ed5eb06788375792c03c8675768`.

`scripts/check_provenance_pins.py` supplied a third lesson of the same kind without being planted at
all: run before the new page was staged, it reported *0 pages inspected* and said in its own output
that **this exit 0 is not a verdict on the corpus**. Staged, it reported 1 page, 3 cited pins, 3 landed,
0 findings. A zero-result run is not a passing run, and this gate is honest enough to say so.

### 7.1 Families 3 and 4 shared one sabotage run, and the counts prove the scoping

Both plants went in together and the node lane was compiled and run **once** under both. That is worth
stating rather than glossing, because a shared run is only legitimate if the two reds are separable —
and they are: family 3's failures are all in `three_way_parity_cljs_test` and family 4's are in
`error_shape_cljs_test`, at the file and line quoted above.

The run reported **47 failures and 8 errors over 11771 tests containing 59589 assertions**. That test
and assertion count is **identical to the green control run** in §5, which is the check that matters
here: if either plant had crashed a namespace, every namespace after it would have been skipped and the
totals would have dropped. They did not, so the plants were scoped to assertions and the lane ran
whole. The eight errors are downstream of the same two plants — the SSR-entry and identifier-prefix
namespaces consume the native tier's element names — and they are not a separate finding.

### 7.2 What was NOT proved

**Family 4's elision arm carries no plant.** The plant above proves the *coordinate present in dev*
half of family 4; the *coordinate erased in production* half rests on
`check_source_coord_elision.cjs`'s own positive control, which is real evidence that the gate read a
non-empty bundle but is weaker than a planted red. Stated so nobody reads §7's table as covering both
arms.

**Family 5 has nothing to prove.** There is no gate to sabotage, which is the finding in
[§4](#4-family-5--the-pinned-regression-gate) rather than a gap in this section.

## 8. The trunk moved while this was written, and it does not reach here

The parent page's §6.2 records the same check and it is worth keeping, because a record anchored to a
commit is only useful if a reader can see how far the trunk has travelled past it.

`origin/main` advanced **12** commits beyond `f5b1f1e94f` during this run. `git diff --name-only
f5b1f1e94f..origin/main` names six paths and **not one of them is under `implementation/`** —
`git diff --stat f5b1f1e94f..origin/main -- implementation` is empty. The six are `.beads/issues.jsonl`,
`.github/workflows/test.yml`, `spec/009-Instrumentation.md` and three pages under
`docs/design/hicasso/studio/`.

**The workflow file is the one that would matter, and it is comment-only.**
`git diff -U0 f5b1f1e94f..origin/main -- .github/workflows/test.yml`, filtered to lines that are not
comments, returns **nothing**; the change is 8 insertions and 4 deletions, all inside one comment block.
The job roster is unmoved. So the gate SET this page certifies against did not change either.

This is a claim about a window that closed when the sentence was written, not a standing property. What
generalises is the anchor: **every figure on this page is stated of `f5b1f1e94f`**, and a reader who
needs to know whether it still describes the trunk can re-run the two commands above rather than take
this paragraph's word for it.
