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
| worktree | `C:/Users/miket/code/re-frame2-worktrees/recert-hic090`, printed by `scripts/assert-worker-worktree.sh` |
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

NOT YET RUN AT THIS COMMIT. This section is filled in by a later commit on the same branch, and until
then this page certifies families 1 and 5 and nothing else.

## 6. What this still does not certify

NOT YET WRITTEN AT THIS COMMIT.

## 7. The controls — which gates were shown to still bite

NOT YET WRITTEN AT THIS COMMIT.
