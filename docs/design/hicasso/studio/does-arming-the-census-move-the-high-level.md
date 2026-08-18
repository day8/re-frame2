# Does arming the census move the high level?

Seat: MEASUREMENT RECORD, EP-0038. Bead `rf2-c4hhk` — the floor arm is **multi-modal**,
and the elevated level has only ever been seen at **+2,532 B/write with the work census
ARMED** and at **+3,792 B/write with it UNARMED**. So the counter built to explain the
modes may be moving the level it was built to read, and if it is, **every high-mode
reading in the corpus is instrument-contaminated**.

Written 2026-08-19 on `worker/armconf-c4hhk`, off `7fbfa8bc96660f4124f7dfa2c86a84374486801e`,
which is `origin/main` at the time of writing and therefore an anchor a fresh clone resolves.

**This page is a MEASUREMENT WINDOW with a stopping rule fixed in advance.** The run
count below was written down and committed *before the runner was invoked once*, and
exactly that many runs were taken regardless of what they showed. The mode's low rate
makes the stop-when-it-looks-good failure the live one here, not a theoretical one.

## Pre-registration

**Declared before the first run, and committed in this file before the runner was
invoked once.**

| Field | Value |
|---|---|
| **Run count** | **70 runs — 35 ARMED and 35 UNARMED — taken regardless of outcome** |
| Order | strictly **alternating**, beginning ARMED, so no time-varying property of the machine aligns with an arm |
| Substrate revision | `implementation/core/src` at `4a1537cb717dc6660aa449642f198a2cc970c93b`, an ancestor of `origin/main` |
| Plan | `P0_ALLOC_PLAN=floor`, `P0_ALLOC_WRITE=all`, `P0_ROOTS=4`, `P0_ALLOC_CELLS=6`, `P0_ALLOC_ROUNDS=18` (so B = 24) — identical to `rf2-77gz8`'s window |
| **The one varied factor** | `P0_WORK_COUNT=1` on the armed arm, **unset** on the unarmed arm. Nothing else differs between the arms — not the plan, not the port, not the revision, not the rig. |
| Estimator | the **median**, over **certified** windows at **round index ≥ 6**, of that window's `legMedian` — per segment. `rf2-77gz8`'s estimator, unchanged. |
| High-mode criterion | either segment's estimator at or above **21,000 B/write**. `rf2-77gz8`'s criterion, unchanged. |
| Admissibility | `controlVerdict.ok === true` **and** `verification.unverified === 0`. The runner's **exit code is not a criterion** — see [Admissibility](#admissibility). |
| Stopping rule | **exactly 35 per arm**; the series does not stop early on a positive and does not extend on a null |

### The decision table, declared in advance

The levels are page-global and discrete, so the discriminator is a **byte comparison of
the estimator pair** against the two levels already in the corpus. Declared before any
run so that no reading is classified after the fact:

| Observation | Verdict |
|---|---|
| Both arms reach the mode; **every** armed high reads 21,632 / 22,072 and **every** unarmed high reads 22,892 / 23,332 | **ARMING SHIFTS THE HIGH LEVEL.** The two are ONE level under two builds, and every high-mode reading in the corpus — `rf2-77gz8`'s included — is instrument-dependent |
| **Any** unarmed high reads 21,632 / 22,072 | **THE LEVELS ARE DISTINCT.** Arming does not gate the +2,532 level; the arm has at least three levels of its own |
| **Any** armed high reads 22,892 / 23,332 | **THE LEVELS ARE DISTINCT.** Arming does not gate the +3,792 level either |
| A high appears in **one arm only** | **NULL ON THE COMPARISON — a refusal.** Report both arms' realised rates and state what the null bounds, and no more |
| **No** high in either arm | **NULL — a refusal.** Same |
| A high reads a level in neither set | **A FOURTH LEVEL.** Report it and refuse the two-level comparison |

### Why 35 per arm

The rate was **re-derived from the committed corpus**, not taken from prose. Every
18-round floor run ever committed at `4a1537cb71`, scored by the estimator above:

| Source | Runs | Arm | High-mode runs |
|---|---|---|---|
| `alloc-9jrhi` — `bisect-1`, `bisect-5`, `bisect-6` | 3 | unarmed | **1** (`bisect-1`) |
| `workcount-n1b9h` — `run3` … `run6` | 4 | armed | 0 |
| `alloc-77gz8` — `run01` … `run20` | 20 | armed | **2** (`run09`, `run19`) |
| **Total at this revision** | **27** | — | **3** |

So the pooled rate at the only revision the mode has ever appeared at is **3 in 27
(11.1%)**, or **3 in 25 (12.0%)** restricted to runs with a passing control. Note this
is *not* the 1-in-7 figure `rf2-77gz8` sized against: that was the corpus **before**
its own twenty runs were added, and adding them dilutes it.

**The binding constraint is the ARMED arm's own rate**, which is the lower of the two:
**2 in 24 (8.3%)** armed against **1 in 3** unarmed — and the unarmed figure rests on
three runs, so it is nearly uninformative and is not sized against.

At the armed rate of 8.7% (2 of 23 admissible), 35 runs give
P(at least one high) = 1 − (1 − 0.087)³⁵ ≈ **96%**, and **P(both arms reach it) ≈ 92%**.
At the pooled 12% the same 35 gives ≈ 99% per arm. A run costs about 62 seconds
including its `:advanced` build — re-derived from the `generatedAt` spacing of
`rf2-77gz8`'s twenty datasets — so 70 runs is roughly 75 minutes.

**Both arms must reach the mode for the comparison to exist.** A series long enough for
one arm and not the other answers nothing, which is why the count is symmetric and why
the null is pre-declared as a refusal rather than as a bound on the effect.

## Admissibility

`--only alloc` **exits non-zero as a matter of course** — it exits on any refused window
and on any collection falling inside a measured one, and both are routine at this page.
All twenty of `rf2-77gz8`'s runs exited 1, including the nineteen it accepted and the one
it excluded. **The exit code is therefore not the verdict here**, and neither is it
evidence of one. Admissibility is decided by the three things the driver's own header
names as exit-bearing checks and this window reads out of the committed record instead:

1. the **positive control**, adjudicated by `lane/control-verdict` — `controlVerdict.ok`;
2. the **read-back verification** — `verification.unverified`, which must be 0;
3. the **per-window certificate** — only windows with `certified: true` enter the
   estimator, which is inside the estimator rather than a run-level gate.

A run failing (1) or (2) is **excluded and named**, never silently replaced.

## The rig is not touched

Nothing under `implementation/core/test/re_frame/bench/` is modified by this window. The
rig blobs are the constancy guarantee the whole published allocation series rests on, and
the census is off at compile time by construction — `counting?` is a `goog-define`
defaulting to false and all three call sites are macros, so `:advanced` folds it away and
an unarmed build compiles the bundle this rig compiled before the counters existed. That
property is the *subject* of this window, so it is stated and not assumed.

| File | Blob at this page's base |
|---|---|
| `core/test/re_frame/bench/p0_run.cjs` | `ce6363ff774d8049c07b58513d708687a73e937e` |
| `core/test/re_frame/bench/p0_heap.cljs` | `5e174327ac17feac2f46ccbdf2bc4f89accf624f` |
| `core/test/re_frame/bench/p0_workcount.cljc` | `033f00470c380a17664a1dabffa0768f0e22c671` |
| `core/test/re_frame/bench/p0_fixture.cljc` | `1f066a05365e9f47b76b887a3d98e7cd8a9152e8` |
| `core/test/re_frame/bench/p0_floor.cljs` | `3a14ff96414f9a77a7612f56181444155b582620` |

Every one matches the table on
[The second mode: a pre-registered twenty-run window](the-second-mode-a-pre-registered-twenty-run-window.md),
and no bench file has changed since `408dfb0aa8`, so **this window runs the same
instrument that produced the `alloc-77gz8` datasets**.

## Reproduction

From the **repository root**. The substrate is checked out at the revision the mode has
been seen at; the file set is identical either side (88 files, none added and none
removed), so the plain checkout is exact and nothing is left behind.

```bash
git checkout 4a1537cb71 -- implementation/core/src

# ARMED, repeated for the odd-numbered runs
P0_WORK_COUNT=1 P0_PORT=8473 P0_ALLOC_PLAN=floor P0_ALLOC_WRITE=all \
P0_ROOTS=4 P0_ALLOC_CELLS=6 P0_ALLOC_ROUNDS=18 \
P0_RAW_OUT=implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-c4hhk/armed-01-a4a1537cb71.json \
  node implementation/core/test/re_frame/bench/p0_run.cjs --only alloc

# UNARMED, repeated for the even-numbered runs — P0_WORK_COUNT simply absent
P0_PORT=8473 P0_ALLOC_PLAN=floor P0_ALLOC_WRITE=all \
P0_ROOTS=4 P0_ALLOC_CELLS=6 P0_ALLOC_ROUNDS=18 \
P0_RAW_OUT=implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-c4hhk/unarmed-01-a4a1537cb71.json \
  node implementation/core/test/re_frame/bench/p0_run.cjs --only alloc

git checkout HEAD -- implementation/core/src
```

The datasets are committed beside this page under
`implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-c4hhk/`, on the convention
`rf2-2rtt6.138` set and `rf2-erre5` wrote down. Each retains every window's raw sample
stream, so the estimator can be re-derived without a browser.

## Related

- [The second mode: a pre-registered twenty-run window](the-second-mode-a-pre-registered-twenty-run-window.md)
  — `rf2-77gz8`, which found the +2,532 B level armed and left this confound open.
- [The work count is a constant, and the mode did not reproduce](the-work-count-is-a-constant-and-the-mode-did-not-reproduce.md)
  — `rf2-n1b9h`, which built the census and showed arming does not move the **low** level.
- [The bisect is flat and the floor has a second mode](the-bisect-is-flat-and-the-floor-has-a-second-mode.md)
  — `rf2-9jrhi`, whose `bisect-1` is the only unarmed high-mode run in the corpus.
- [The dispersion follows the controls, not the round](the-dispersion-follows-the-controls-not-the-round.md)
  — `rf2-rs8q6`, whose once-per-window **+748 B** rider is a different and much smaller
  term than these page-global steps; do not let one explain the other.
