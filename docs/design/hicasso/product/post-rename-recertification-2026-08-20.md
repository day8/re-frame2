# Post-rename recertification — the 2026-08-20 re-run

`rf2-hic-090`'s fourth results record. [`post-rename-recertification.md`](post-rename-recertification.md)
is the parent page and carries the bead's roster of five evidence families;
[the 2026-08-18 page](post-rename-recertification-2026-08-18.md) is the third run and supplied every
command re-used here. **This page does not restate those runs and does not correct them**, with one
exception it states in full and defends at source: [§4](#4-family-5--the-pinned-regression-gate-and-the-clock-versus-ladder-question-settled)
overturns one sentence of the 2026-08-18 page's characterisation of family 5.

**Every figure below is a COUNTER or a BYTE COUNT, and none is a clock reading.** Three other workers
were alive on this machine while these gates ran, so an elapsed-time figure taken here would measure the
load rather than the tree. Counters and bytes read the same under load, which is why they are the only
estimands this page carries.

## 1. Why a fourth run — the trigger, re-derived rather than carried forward

The re-run condition is the mayor's 2026-08-16 ruling, quoted by the 2026-08-18 page: re-check the
families *if anything the size of PR #8322 lands again*. The dispatch reported the delta as 301 commits
and 472 files. **That was a claim and it is now understated**, because the trunk moved further between
the assessment and this branch's base.

| What was measured | Command | Result |
|---|---|---|
| trunk movement since the last certified base | `git rev-list --count f5b1f1e94f..HEAD` | **442** commits |
| its size | `git diff --shortstat f5b1f1e94f..HEAD` | **703** files, 837965 insertions, 11296 deletions |
| its size on the surfaces these families read | `git diff --name-only f5b1f1e94f..HEAD -- implementation .github` | **416** files |
| of those, bench measurement data rather than source | `… \| grep -c 'bench/hicasso/data/'` | **115** |
| so, non-data files under `implementation/` or `.github/` | `… \| grep -vc 'bench/hicasso/data/'` | **301** |

The insertion count is dominated by measurement data and must not be read as source churn: one file,
`implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-0gjqi/paired-run1.json`, is 20246 lines
of it. **The file count is the honest axis and it is larger than the 2026-08-18 trigger on every
reading** — 442 commits against 175, 703 files against 219, 416 family-surface files against 122.

**The paths are the stronger half of the test, and they are emphatic.** Every one of the four instrument
scripts this page runs has a moved neighbour, and one of them moved itself:
`implementation/hicasso/scripts/check_optional_module_reachability.py` is in the interval, as are
**every** file under `implementation/hicasso/src/re_frame/hicasso/impl/`, `hicasso.cljc` itself,
`native.cljc`, `motion.cljs`, `server.cljs` and `substrate.cljs`. A recertification whose instruments and
whose subject have both moved is due on its own terms.

**The parent page's own stop condition was re-tested and it still holds.** `rf2-t32wg` — the row-18 seam
the certification waits on — is `DEFERRED` to 2026-09-16 and unruled, read from `bd show` rather than
carried forward. So this run changes what is measured and changes nothing about what is certified
overall; see [§6](#6-what-this-still-does-not-certify).

## 2. The base, and the tree it was taken on

| | |
|---|---|
| base | `2f96ecc98c36ce0c6c845969e39566134351dea1`, `origin/main` at 2026-08-20 23:40 AUSEST. The gates began at 23:42 and ran past midnight; the page is dated for the run's start, as the dispatch was. |
| worktree | a dedicated worker worktree on `worker/recert-hic090`; `scripts/assert-worker-worktree.sh` ran there and exited `0`. **The literal path is deliberately not written down** — `scripts/check-no-hardcoded-paths.sh` reds a tracked file carrying a personal home path, and it reds correctly: a machine-specific string is not what makes the guard evidence. That the guard ran and passed is. |
| `node_modules` | a junction into the primary checkout's real one, **103** top-level entries by `Get-ChildItem \| Measure-Object`, removed as the last act of this work |
| open PRs touching a family 1–4 surface | **none**. Only two PRs are open: `#8559` is `docs/design/hicasso/product/dispositions.md`, and `#8555` is the bench trees plus `docs/design/hicasso/studio/`. `#8555` does touch `implementation/core/test/re_frame/bench/p0_run.cjs`, which is family **5**'s instrument and no other family's — that is part of [§4](#4-family-5--the-pinned-regression-gate-and-the-clock-versus-ladder-question-settled)'s finding rather than a caveat on families 1–4. |
| the box at the first heavy gate | free physical **17.30 GB of 63.43 GB**; `java` **0**, `node` 20, `chrome` 96, at 23:42:48 AUSEST |

**Every family was run one at a time and never in parallel**, and the reason is not politeness about
wall-clock. Two heavyweight suites here have once *wedged* rather than failed — each holding several
gigabytes, neither returning and neither reporting anything. That is contention for the machine, which no
naming discipline touches.

Each exit code below is the runner's own, captured by an `echo` on the same command line as the redirect.
No number here is one the harness reported about a run.

**Which tree each gate read is established two ways.** Every shadow-cljs invocation printed the absolute
path of the `shadow-cljs.edn` it loaded on its first line, and it named this worker's worktree on every
run, never a sibling's. Where a gate printed no root, [§7](#7-the-controls--which-gates-were-shown-to-still-bite)'s
planted faults supply the other route: each fault existed only in this worktree, so a run that had
wandered elsewhere would have come back green.

## 3. Family 1 — bundle isolation and the rent sentinels

The bead's phrasing is *interpreted-only zero-rent*, and it is two gates rather than one: the source half
decides reachability over `:require` forms, and the bundle half reads a real `:advanced` +
`goog.DEBUG=false` build. Neither answers the other's question, so both are run.

| Half | Command | Captured exit | What it reported |
|---|---|---|---|
| source | `python hicasso/scripts/check_optional_module_reachability.py --self-test` | `0` | self-test OK |
| source | `python hicasso/scripts/check_optional_module_reachability.py` | `0` | motion, overlay, native, forms, server **and substrate** all unreachable from the public door; UIx required by no `src/` namespace and named by no production coordinate |
| bundle | `npx shadow-cljs release hicasso-release` | `0` | **162 files, 107 compiled, 0 warnings** |
| bundle | `node hicasso/scripts/check_production_erasure.cjs --self-test` | `0` | self-test OK (5 sentinels, 3 positive controls) |
| bundle | `node hicasso/scripts/check_production_erasure.cjs` | `0` | no dev-only Hicasso surface in the bundle — **5 sentinels absent, 3 positive controls present** |
| bundle | `node hicasso/scripts/check_bundle_isolation.cjs --self-test` | `0` | self-test OK (8 sentinels, 4 positive controls) |
| bundle | `node hicasso/scripts/check_bundle_isolation.cjs` | `0` | no isolated surface reached the interpreted-only bundle — **8 sentinels absent, 4 positive controls present** |

**Verdict: GREEN.** The composite `npm run build:hicasso-release` chains all five bundle-half steps; it
was split into its steps here, as on 2026-08-18, so each verdict is a foreground capture of its own
rather than one status standing for five.

### 3.1 The reachability gate's population grew by one, and that is a finding

The 2026-08-18 run recorded five optional modules — *motion, overlay, native, forms and server*. This run
reads **six**: `substrate` has joined them. The gate script itself is in the interval
(`check_optional_module_reachability.py`, §1), and `implementation/hicasso/src/re_frame/hicasso/substrate.cljs`
is a file the interval added. So the gate is checking **more** than it was, not the same amount over a
changed tree, and the green is correspondingly wider. Stated because a roster that quietly grows is
exactly the kind of thing a certification exists to notice.

### 3.2 The bundle bytes MOVED, and this is the first byte comparison this corpus can make

| | 2026-08-18, at `f5b1f1e94f` | here, at `2f96ecc98c` | delta |
|---|---:|---:|---:|
| `out/hicasso-release/main.js` | 671290 B | **671269 B** | **−21 B**, −0.0031% |
| `out/hicasso-release/manifest.edn` | 4806 B | 4806 B | 0 |
| build population | 162 files / 107 compiled | 162 / 107 | 0 |

The 2026-08-18 page was explicit that its byte figure was **an anchor and not a comparison**, because no
earlier figure existed to difference it against. One does now, and this is the difference. **Twenty-one
bytes across 442 commits that rewrote most of `hicasso/src/` is the substantive shape of the result**:
the compile population did not move at all, and the emitted bundle moved by three thousandths of one
percent. Nothing here licenses a claim about *why* it moved by 21 bytes, and no attribution is offered.

## 4. Family 5 — the pinned regression gate, and the clock-versus-ladder question settled

The dispatch named settling this a deliverable, because two source pages appeared to be in tension and
the answer decides whether this run reports four families or five. **It is settled here, in two steps,
and the two steps do not point the same way.**

### 4.1 The 2026-08-18 page's "clock estimand" characterisation is WRONG, and `budgets.md` says so

[The 2026-08-18 page's §4](post-rename-recertification-2026-08-18.md#4-family-5--the-pinned-regression-gate)
says of family 5: *"what family 5 would be if it existed: a **clock** estimand."* That sentence does not
survive a read of the row's own registry.

- [`budgets.md`](budgets.md)'s §9.2 states `C1`'s blocker in as many words: *"`C1` compares a reading
  against the pinned ordinary-Hicasso benchmark … **Until the ladder is re-pinned**, 'the same
  instrument' names nothing."* The instrument `C1` is registered on is **the ladder**.
- The same section's roster of what each not-green row waits on separates the two kinds explicitly: *"The
  **user-visible gates** — `U1`–`U4`, and `C3`/`C4`"* are the rows waiting on a clock instrument, and
  *"The **5% same-instrument regression gate** — `C1`"* is a separate bullet whose blocker is the ladder
  re-pin, *"a run, not an edit"*.
- `budgets.md`'s §6 pins it harder still: *"`C1`: the supersession has widened rather than closed. **The
  ladder's provenance table pins eleven blobs**"*, six of them the P0 driver's.
- The contrast row is `C7`, and it is what shows the ledger *can* say "clock" about a comparative rule
  when it means to: *"`C7` stays `UNPINNED` and its **clock half** remains `rf2-hic-071`'s, along with the
  ladder re-pin"*. `C7` is written with two halves and the ledger names them separately; `C1` is written
  with one, and the half it has is the ladder's.

**A search was tried here first and it was a bad control, which is worth recording rather than hiding.**
A line-scoped fixed-string search for `C1` beside any spelling of *clock* returned nothing — but so did
the same search for `C7`, whose clock half is quoted above, because "C7" and "clock" sit on adjacent lines
rather than on one. **The search that found nothing about `C1` would have found nothing about a row that
plainly has a clock half**, so it discriminated nothing and none of the finding above rests on it. The
three quotations do, and each was read at source.

**So the expiry the dispatch suspected is REAL and it is not narrow.** The 2026-08-19 anchor was taken on
`p0_run.cjs --only ladder`, which is `C1`'s registered instrument and not a different estimand. Family
5's standing answer for three runs — *"the gate does not exist"* — has genuinely stopped being true.

### 4.2 And family 5 still reports NO VERDICT here, for a different and much narrower reason

Having established that the heap ladder **is** `C1`'s instrument, the next question is whether a reading
taken on this branch's base would be `C1`'s second reading. It would not, and the disqualification is
measured rather than argued.

`C1` is written *on the same witness **and instrument***. The instrument is the nine files
[the anchor page's §6](../studio/the-c1-anchor-on-the-package-arm.md) pins. Re-read at this base:

| instrument file | pinned by the 2026-08-19 anchor | at `2f96ecc98c` | same? |
|---|---|---|---|
| `p0_run.cjs` | `ce6363ff774d8049c07b58513d708687a73e937e` | `cf437c8f30debfa0d424f8dff517c1ae003163b5` | **no** |
| the other eight `p0_*` files | as pinned | identical blob for each | yes |

The mover is the driver itself, changed by `e27c2a3b26` — *"P0_ALLOC_SEG_ORDER=fixed breaks the
position/substrate confound (rf2-rs8q6)"*, 102 insertions and 2 deletions. **A reading taken here would
be the same witness on a different instrument**, which is precisely the comparison
[the anchor page's own §7.4](../studio/the-c1-anchor-on-the-package-arm.md#74-the-seven-day-comparison-and-why-it-is-reported-but-not-counted)
declined to count when it faced the identical situation against the 2026-08-12 rung. Taking it and
reporting it as `C1`'s second reading would manufacture the un-attributable comparison `C1` exists to
forbid.

Two further facts point the same way and neither is needed to reach the conclusion.

- **The instrument is still moving.** PR `#8555` is open at this base and touches `p0_run.cjs` again. A
  second reading taken now would be superseded by a merge that is already in the queue.
- **The box is not drained.** Three workers were alive throughout this run and this page's own gates were
  the heaviest thing on the machine. The anchor page's §2 refuses a contended reading *"which lands
  within a byte"* as evidence, so a reading taken alongside these suites could not have cleared its own
  admissibility bar even on an unchanged instrument.

**Verdict: family 5 reports NO VERDICT — not green, not red.** But the reason has changed in kind for the
first time in four runs, and the difference is worth stating precisely because it is what the next run
inherits:

| run | family 5's reason for no verdict |
|---|---|
| parent, 2026-08-15 | the gate was never built; `C1` deliberately unpinned |
| 2026-08-16 | unchanged |
| 2026-08-18 | unchanged, plus a mischaracterisation of the row as a clock estimand |
| **this run** | **the gate exists and has an anchor**; what is missing is a second reading on the *same* instrument, and the instrument moved after the anchor was taken |

### 4.3 What would make family 5 reportable, stated so the next run does not re-derive it

Three conditions, and all three are cheap to test:

1. `p0_run.cjs` and the other eight instrument files at the same blobs as some prior admissible reading —
   which today means either a re-pin of the ladder against the driver blob §4.2 records (or whatever
   `#8555` leaves) with a fresh anchor taken on it, or a reading taken on a checkout of the anchor's own
   `p0_run.cjs`.
2. A drained box, to the standard the anchor page's §2 and §5 set.
3. Two readings, across a change, on that one instrument.

The first is the only one that needs a decision rather than a window, and it is `rf2-85og2`'s: its
remaining scope is *the ladder re-pin plus the 5% comparison it makes meaningful*, which covers exactly
this. **Nothing here is `rf2-hic-090`'s to do** — this bead's surface line is *re-runs existing gates,
edits nothing*, and taking a bench measurement is not re-running an existing gate.

### 4.4 The ledger vocabulary: the gap is now WIDER, and it is still a ruling

The dispatch put the fifth status value in scope if the finding warranted it. It does not, and the reason
is that the finding moved past it.

`budgets.md` leaves `C1` at `UNPINNED` and records why: there is no value meaning *anchored, instrument
exists, awaiting a second reading*, and *"minting a fifth is a ruling rather than a worker's edit"*. That
was written on 2026-08-19. **Within one day the state it describes had already expired**: the instrument
has since moved, so the true cell today is nearer *anchored, instrument has since drifted, anchor not yet
comparable* — a fifth value minted for the 2026-08-19 state would already be wrong at this base. Minting
one for the 2026-08-20 state instead would be inventing a taxonomy to fit a state that is itself moving,
which is the thing the stance rejects and which `budgets.md`'s own §9.1 says `UNRESOLVED` was invented to
prevent.

**So no cell was moved and no value was minted.** The discrepancy is recorded here, one notch sharper
than `budgets.md` records it, and the ruling stays where it was.

## 5. Families 2, 3 and 4 — SSR bytes, macro parity, source coordinates

These three families share their runners, which is why they share a section rather than one each. The
parent page's §4 roster establishes the mapping; it was re-checked at source here and it still holds —
the witness namespaces `three_way_parity_cljs_test`, `error_shape_cljs_test`, `native_grammar_cljs_test`
and `native_surface_cljs_test` are each present in the built node-test bundle, found by a fixed-string
search of `out/node-test.js`. **That search was run once against a name that should NOT be there**
(`rf2_no_such_namespace_zzz`, 0 hits), because a search that looks nowhere and a search that finds
nothing print the same number.

Every composite `npm run` script below was split into its steps so that each verdict is its own
foreground capture rather than one status standing for a chain. **Every command is the 2026-08-18 page's,
re-used verbatim; none of them has ceased to exist.**

| Family | Command | Captured exit | What it reported, at `2f96ecc98c` |
|---|---|---|---|
| 2, 3, 4 — node lane, compile | `node scripts/compile-node-test.cjs node-test out/node-test.js` | `0` | **1990 files, 1989 compiled, 0 warnings** |
| 2, 3, 4 — node lane, run | `node out/node-test.js` | `0` | **11854 tests, 60048 assertions, 0 failures, 0 errors** |
| 2, 3 — browser lane, compile | `npx shadow-cljs compile browser-test` | `0` | **1076 files, 1075 compiled, 0 warnings** |
| 2, 3 — browser lane, run | `node scripts/serve-and-run-browser-tests.cjs` | `0` | **1019 tests, 5745 assertions, 0 failures, 0 errors** |
| 2 — SSR JVM arm | `clojure -M:test` in `implementation/ssr` | `0` | **599 tests, 2925 assertions, 0 failures, 0 errors** |
| 4 — elision arm, build | `npx shadow-cljs release browser-test-prod-elision` | `0` | **312 files, 252 compiled, 0 warnings**; bundle **1967892 bytes** |
| 4 — elision arm, coordinate check | `node hicasso/scripts/check_source_coord_elision.cjs` | `0` | no `defview`/`defhost` source coordinate in the advanced bundle, **positive control present** |
| 4 — elision arm, browser run | `node scripts/serve-and-run-browser-tests.cjs --root out/browser-test-prod-elision --port 8023 --duplicate-done-drift-unverifiable` | `0` | **92 tests, 295 assertions, 0 failures, 0 errors** |

**Verdict: GREEN for families 2, 3 and 4.**

### 5.1 The counts MOVED, and this run is the mirror image of the last one

The 2026-08-18 page's substantive finding was that every count was identical across 175 commits, and it
insisted on stating the identity rather than passing over it. **This run is the other case, so the same
discipline applies in reverse.**

| Arm | 2026-08-18, at `f5b1f1e94f` | here, at `2f96ecc98c` | moved? |
|---|---|---|---|
| node lane | 11771 tests / 59589 assertions | 11854 / 60048 | **+83 / +459** |
| browser lane | 1015 / 5724 | 1019 / 5745 | **+4 / +21** |
| SSR JVM | 599 / 2925 | 599 / 2925 | no |
| elision lane | 92 / 295 | 92 / 295 | no |
| `hicasso-release` build | 162 files / 107 compiled | 162 / 107 | no |
| node-lane compile | 1985 files / 1984 compiled | 1990 / 1989 | **+5 / +5** |
| browser-lane compile | 1075 files / 1074 compiled | 1076 / 1075 | **+1 / +1** |

**Read this as what it is: the suites GREW, and nothing shrank.** Not one arm lost a test or a file. That
is the healthy direction for a 442-commit interval whose character was features and fixes rather than
deletions — the contrast is the parent page's §6.4, where every suite came back *smaller* after PR #8322
and each drop was pinned to a landed deletion.

**No count here is attributed to a commit, and none should be.** 442 commits landed and the additions are
spread across them; this page measures the population, it does not bisect it. What the identity of the
three *unmoved* rows says is narrower and worth keeping: the SSR JVM lane and the `hicasso-release` build
population are the two surfaces the interval did not touch at all.

**The elision bundle also moved, and it moved the same way the release bundle did**: 1968527 bytes on
2026-08-18, **1967892** here — **−635 B**, −0.032%. Like §3.2's figure this is the first comparison
available for that bundle, its compile population is unmoved at 312 files / 252 compiled, and no cause is
attributed to it.

## 6. What this still does not certify

**The overall certification remains PARTIAL, and for the same reason as the parent page's.** Nothing here
disturbs the mayor's 2026-08-15 ruling: what holds `rf2-hic-090` open is not the five families but
naming-ledger row 18, and row 18 is uncertified because `rf2-t32wg` needs an operator spec ruling on
admitting zero-arity `rf/capture-frame` and `rf/current-frame-id` inside a Hicasso body.

That fence was re-tested at source for this run rather than carried forward. `rf2-t32wg` is `DEFERRED` to
2026-09-16 and **unruled** — a defer schedules the question rather than answering it, so the release
condition the mayor stated is unmet: this bead closes when `rf2-t32wg` is ruled and row 18 is executed, or
when the operator rules that row 18 need not block certification. Both are operator calls, and neither is
a worker's to make.

Three boundaries are worth stating plainly, because a certification record is exactly the document whose
careful sentences get quoted one notch stronger later.

- **Four families green is still not five.** Family 5 has no verdict — not a green one and not a red one.
  See [§4](#4-family-5--the-pinned-regression-gate-and-the-clock-versus-ladder-question-settled). What
  changed this run is the *reason*, not the count.
- **The two byte deltas are measurements, not attributions.** −21 B on `hicasso-release` and −635 B on
  the elision bundle say those bundles moved; they say nothing about which of 442 commits moved them, and
  no bisect was run.
- **Family 4's elision arm still carries no planted fault**, exactly as the 2026-08-18 page recorded. See
  [§7.2](#72-what-was-not-proved).

**Nothing was filed in [`correction-ledger.md`](correction-ledger.md), because there was no red to file.**
Every captured exit in §3 and §5 is the runner's own `0`.

## 7. The controls — which gates were shown to still bite

A certification's characteristic failure is a gate that returns green because it ran over nothing, so green
is only worth as much as the demonstration that red was available. Each control below planted a fault, ran
the gate, checked that the failure named the plant, restored, and verified the restore by **blob hash
against the committed object** — never by reading a diff, because this checkout translates line endings
and a plain byte digest reports a correct restore as failed.

| Family | What was planted | Gate under the plant | Captured exit | What the red said |
|---|---|---|---|---|
| 1, source half | one `:require` of `re-frame.hicasso.motion` in the public door's `ns` form | `check_optional_module_reachability.py` | `1` | named the file and the optional module by name, and told the author to require it in the application instead |
| 1, bundle half | a `:require` of `re-frame.hicasso.native` plus one reachable `n/marker` call in the consumer app's `-main` | `npx shadow-cljs release hicasso-release` then `check_bundle_isolation.cjs` | build `0`, gate `1` | `OPTIONAL SURFACE LEAKED: native tier`, quoting sentinel `"rf2:hicasso-native-tier"` |
| 2, SSR JVM arm | one string-literal marker prepended to text-node output in `ssr/emit.cljc`'s `emit-element` | `clojure -M:test` in `implementation/ssr` | `1` | **97 failures, 0 errors**, over the same **599 tests / 2925 assertions** as the control run — so no namespace crashed and the plant was scoped to assertions rather than to the lane. The marker appears 116 times in the failure output. |
| 3, macro expansion | `head-form` in `native.cljc` — the `n/$` macro's expansion-time lowering of a keyword head — made to append a marker to the element name | node lane, compile then run | compile `0`, run `1` | `three_way_parity_cljs_test.cljs:337:15`, **8 failures** on *the-three-routes-render-the-same-server-bytes*, and **7** at `:367:21` on *the-three-routes-build-the-same-element-shape* — the native arm's expansion no longer agrees with handwritten React |
| 4, source coordinate | `defview`'s captured coordinate in `hicasso.cljc` given `:line 0` at expansion | node lane, same run | run `1` | `error_shape_cljs_test.cljs:144:11` — expected `:line? true`, actual `:line? false`, on *a-refusal-from-a-body-carries-the-rendering-view-and-its-coordinate* |

| Family | Baseline blob | Under the plant | After restore |
|---|---|---|---|
| 1, source half — `hicasso/src/re_frame/hicasso.cljc` | `c64d048e4ff2d72756b5b52329f2a40b8e015dcf` | `d927b26b91a38304d4e86f98a15898149a110a4c` | `c64d048e4ff2d72756b5b52329f2a40b8e015dcf` |
| 1, bundle half — `hicasso/test/re_frame/hicasso/consumer_app.cljs` | `a1a4720d35311d2e7bb42b66faba0a0fa6c9cf72` | `7e4c65343afc0ef8fb6f0cd5bb88cd4eaf3bab29` | `a1a4720d35311d2e7bb42b66faba0a0fa6c9cf72` |
| 2 — `ssr/src/re_frame/ssr/emit.cljc` | `6ff941afd297192ae8a2a45ab27be0422482e02d` | `0c364040198726145d5be89df214bcf5fa71b8fb` | `6ff941afd297192ae8a2a45ab27be0422482e02d` |
| 3 — `hicasso/src/re_frame/hicasso/native.cljc` | `7e39d9a52bf9bdbdec1ec0ac2e48cba918bd0d30` | `8d0bbe6c9f8d0c799204e06fdff1ea7996e3b79a` | `7e39d9a52bf9bdbdec1ec0ac2e48cba918bd0d30` |
| 4 — `hicasso/src/re_frame/hicasso.cljc` | `c64d048e4ff2d72756b5b52329f2a40b8e015dcf` | `cd7bb172bd117d114e1eead2d6b2bce387309301` | `c64d048e4ff2d72756b5b52329f2a40b8e015dcf` |

Every value in the two hash columns above is a **blob** hash — `git rev-parse HEAD:<path>` for the
baseline, `git hash-object <path>` for the working file — never a commit id. **Each baseline equals the
object committed at `2f96ecc98c`, and each restored value equals its baseline**, so every plant is proved
to have applied rather than silently no-opped, and every restore is proved exact.

**The plants also answer a second question the greens cannot: which tree the gate read.** Each fault
existed only in this worker's worktree, so a run that had wandered into a sibling checkout would have come
back green. The shadow-cljs runs corroborate it a second way, by printing the absolute path of the
`shadow-cljs.edn` they loaded on their first line — it named this worker's worktree on every heavy run,
never a sibling's, and the browser runner printed the same root when it patched and served its bundle.

### 7.1 Families 3 and 4 shared one sabotage run, and the counts prove the scoping

Both plants went in together and the node lane was compiled and run **once** under both, exactly as on
2026-08-18. A shared run is only legitimate if the two reds are separable, and they are: family 3's
failures are in `three_way_parity_cljs_test` and family 4's in `error_shape_cljs_test`, at the files and
lines quoted above.

The run reported **47 failures and 8 errors over 11854 tests containing 60048 assertions**. That test and
assertion count is **identical to the green control run** in §5, which is the check that matters here: if
either plant had crashed a namespace, every namespace after it would have been skipped and the totals
would have dropped. They did not, so the plants were scoped to assertions and the lane ran whole. The
eight errors are downstream of the same two plants — the SSR-entry and identifier-prefix namespaces
consume the native tier's element names — and they are not a separate finding.

**One number on this run disagreed with itself, and believing the wrong one would have inverted this
page's conclusion.** The captured exit — the runner's own, echoed on the same command line as the
redirect — is `1`. The number the harness reported about the same run is `0`. A sabotage run that comes
back green reads as *the control does not bite*, which is a reason to stop rather than to proceed, so the
disagreement is not a curiosity here: it is the difference between a certification and a retraction. **The
number quoted throughout this page is the captured one in every case.**

## 8. The trunk moved while this was written, and it does not reach here

The two previous pages record the same check and it is worth keeping, because a record anchored to a
commit is only useful if a reader can see how far the trunk has travelled past it.

`origin/main` advanced **9** commits beyond `2f96ecc98c` while these gates ran.
`git diff --name-only 2f96ecc98c..origin/main` names exactly **two** paths — `.beads/issues.jsonl` and
`docs/design/hicasso/product/dispositions.md` — and `git diff --stat 2f96ecc98c..origin/main --
implementation` is **empty**. The tracker export is not a family surface and the dispositions page is
prose, so **nothing in the interval reaches any instrument or subject this page measures.**

This is a claim about a window that closed when the sentence was written, not a standing property. What
generalises is the anchor: **every figure on this page is stated of `2f96ecc98c`**, and a reader who needs
to know whether it still describes the trunk can re-run the two commands above rather than take this
paragraph's word for it.

### 7.2 What was NOT proved

**Family 4's elision arm carries no plant**, exactly as the 2026-08-18 page recorded, and the reason it
gave still holds: the *coordinate erased in production* half rests on `check_source_coord_elision.cjs`'s
own two positive controls, which are real evidence that the gate read a non-empty bundle but weaker than a
planted red. Stated so nobody reads §7's table as covering both arms.

**Family 5 has nothing to prove.** What it now lacks is a second reading rather than a gate, which is
[§4](#4-family-5--the-pinned-regression-gate-and-the-clock-versus-ladder-question-settled)'s finding rather
than a gap in this section. Sabotaging the ladder would demonstrate that the ladder reports — which the
2026-08-19 anchor already demonstrated three times — and would say nothing about the comparison that is
missing.

### 7.3 Two gates bit unplanned, and both are recorded because an accidental control is still a control

- **`scripts/check_provenance_pins.py` returned exit `1` on this page's first draft.** §4.3 originally
  carried a shortened driver blob in prose, and the checker classified it as a commit citation because the
  nearest describing word on its left was *re-pin*: `1 unresolvable`, naming the token and the line. The
  fix was to name the blob rather than to re-pin anything, and the second run reported **2 pages inspected,
  26 cited pins — 26 landed, 0 stranded, 0 unresolvable, 0 foreign, 0 findings**, exit `0`. This is the
  hazard that gate exists for and it caught it unprompted.
- **A fixed-string search of the browser runner reported that the elision arm's
  `--duplicate-done-drift-unverifiable` flag no longer existed, and the search was wrong.** The flag is
  live: `rf2-u0cy4` moved its literal into `scripts/lib/browser-runner-drift-env.cjs` and the runner now
  refers to it through the constant `DRIFT_UNVERIFIABLE_FLAG`, so a search of the runner file alone finds
  nothing while `package.json`'s `test:browser-prod-elision` still passes the flag verbatim. **A search
  that returns zero is not a check that passed**, and this one was caught by reading the script that
  invokes the runner rather than by trusting the count. No command in §5 has ceased to exist.
