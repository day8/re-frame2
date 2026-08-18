# The `C1` anchor on the package arm, pre-registered

**`rf2-85og2`, 2026-08-19.** The bead carries three budget gates, each said to
need its own quiet-box window. This page is that window's pre-registration and
its record. It is written in two halves and the first half was **committed
before the runner was invoked once**, so the declared run count is a
commitment rather than a description of what happened.

The three gates, in the bead's own words, are *the user-visible gates plus the
U-row gates a package-resident clock instrument makes possible* (gate 1,
`U1`–`U4` with `C3`/`C4`), *the ladder re-pin plus the 5% comparison it makes
meaningful* (gate 2, `C1`), and *the escape-benefit rule once a
benefit-claiming escape site lands* (gate 3, `C8`).

---

## 1. Declared run counts, per gate

| gate | ledger rows | declared invocations | runner |
|---|---|---:|---|
| 1 | `U1`–`U4`, `C3`, `C4` | **0** | none exists — see §2 |
| 2 | `C1` | **3** | `p0_run.cjs --only ladder`, six rounds, defaults |
| 3 | `C8` | **0** | none applicable — see §4 |

**Why gate 2 is three and not one.** The direct precedent on this rung is
[the package rung](reads-per-boundary-heap-ladder.md#the-package-itself-priced-on-this-rung-at-last-rf2-fe0l),
whose terms were *a single invocation, and no second run was taken*. Three is
declared here for a reason that is specific to `C1` rather than to taste: `C1`
is a **5% regression gate**, and a 5% line cannot be read against an anchor
whose own run-to-run spread is unknown. If three invocations of one unchanged
instrument on one unchanged tree disagree by more than 5%, the line is not
measurable by this instrument and saying so is the finding. That is precisely
the bead's *the 5% comparison it makes meaningful*: the comparison is made
meaningful by knowing the anchor's reproducibility, not merely by having a
number to compare against.

**A series that stops early is not a measurement.** All three are taken. A run
that fails a control is **excluded and reported**, never silently replaced by a
fourth.

## 2. What makes a window admissible here, read out of the rig rather than assumed

**An exit code is not the verdict.** `p0_run.cjs` documents four exit-bearing
checks, and the reason it documents them is that it once did not have them:
a run in which no write reached the page *"printed the count beside `VERDICT:
reportable` and exited 0. A count that is displayed and not gated is
decoration."* The four, in the order the driver takes them:

1. the **arm-order guard's self-test**, in the page, before anything is
   measured — the heap page refuses to install if it fails;
2. **`N unverified of M`** — the structural read-back, exit 1 on any nonzero
   count;
3. the **positive control**, adjudicated by `lane/control-verdict` and exit 1
   when it is not `ok`;
4. the **arm-order verdict** over the samples — exit **2**, figures not
   quotable.

So a window is admissible here when **all four are read from the run's own
output and each is affirmative**, and the captured exit code corroborates them
rather than standing in for them. The positive control is a dense array of
587,500 unboxed doubles, **4,700,000 B fixed by arithmetic before the run**;
`lane/control-verdict` applies a ±25% slack, which is deliberately generous
because the claim being gated is *the instrument has signal* and not *the model
is exact*. This page therefore reports the measured control value **and its
deviation** rather than the word `ok`, because the slack is far wider than the
quantity being measured.

Two further conditions are the ladder's own, not the driver's, and they are
what make a row on this instrument evidence at all:

- **Six rounds**, which is what the min–max bands are taken across.
- **A quiet box.** The ladder records a deliberately contended trial that
  landed within **about 0.07%** of a quiet six-round range and was refused as
  evidence anyway, on the ground that a contended reading which lands within a
  byte is still a contended reading. The bracket is in §5.

## 3. Gate 1 is refused before any run, and no runner was invoked

`U1`–`U4` and `C3`/`C4` are stated at `p95` or `p99` over **a slice
application's own interactions**, through to the paint that follows one
discrete interaction.

- The **estimator** half is no longer missing. `lane/summarise` answers
  `{:n :min :max :p50 :p95 :p99}` and `lane/quantile` is a linear-interpolated
  quantile at `h = (n-1)q` — landed by `rf2-xa8wo`, verified here at source.
- The **population** half is missing, and it is the half that governs. No
  driver under `implementation/hicasso/test/re_frame/bench/` requires anything
  under `re-frame.hicasso.examples`, and the lane's measured window is a
  commit bracketed by `react-dom/flushSync` — a **mount, not a paint**.

A `p95` taken over the window this lane can currently drive would be a `p95` of
a mount published against a line written about a paint, which is worse than no
`p95` because it is quotable. There is no runner to invoke, so **zero
invocations were declared and zero were taken**. `UNPINNED` stands on all six
rows. The blocker is `rf2-xa8wo`'s deliverable 2, and it is edit-shaped work
that builds on a loud box: **a quiet box cannot clear this gate and none was
spent trying.**

## 4. Gate 3 is refused before any run, and its population is empty

`C8` governs an escape **taken for a benefit**. Across the witness
applications under
`implementation/hicasso/test/re_frame/hicasso/examples/`, shipping view code
carries exactly **one** `h/as-element` call — `ledger/views.cljs`'s row
renderer, handing a boundary to the vendor virtualizer, which the rule itself
places **outside** the population as interoperability — and **no
`re-frame.hicasso.native` island at all**.

The census was run with a positive control in both directions, because a search
that returns zero and a search that looks nowhere print the same thing: the
same pattern finds `as-element` across nine files under
`implementation/hicasso/src/`, and `re-frame.hicasso.native` exists as
`native.cljc` in the package, so both names resolve and the absence is in the
**population** rather than in the pattern.

`C8`'s blocker is a landed site in an application. **No quantity of machine
time supplies one**, so zero invocations were declared and zero were taken.
`UNPINNED` stands.

## 5. The box at the window's opening

Measured on 24 logical cores, standalone, never sampled inside anything:

| quantity | reading |
|---|---|
| `\System\Processor Queue Length` | **0** on every sample |
| real CPU occupancy, two 5 s brackets | **7.12%**, **6.63%** |
| `java` processes | **0** |
| `shadow-cljs` / bench processes | **0** |
| `node` / `chrome` / total processes | 20 / 90 / 529 |
| free physical memory | 21.09 GB |

Occupancy is stated as summed per-process CPU-time deltas over a five-second
window divided by the core count, never `LoadPercentage`. It is higher than the
3–4% the 2026-08-12 package rung recorded, and **the attribution is measured
rather than assumed**: the two largest consumers are the operator's editor at
4.09% and the terminal rendering this session's own output at 3.53%, together
more than the whole reading. No compile, no browser suite and no bench is
running, and the queue length is the decisive number because it says whether
anything was *waiting* for a core. Nothing was.

## 6. The instrument this window is taken on

Whole-tree anchor **`2833213919`**, which is `origin/main` at the window's
opening — the measured tree and the published tree are the same tree. Object
ids are the committed objects (`git rev-parse HEAD:<path>`) rather than a byte
digest of the working file, which on a checkout with `core.autocrlf=true` is
the only reading that means anything; both routes were taken and they agree.

The instrument, all under `implementation/core/test/re_frame/bench/`:

| file | blob |
|---|---|
| `p0_run.cjs` | `ce6363ff774d8049c07b58513d708687a73e937e` |
| `p0_heap.cljs` | `5e174327ac17feac2f46ccbdf2bc4f89accf624f` |
| `p0_hicasso.cljs` | `355ffb0d5da5bdadcea4d97f0509d5b814fcbf1b` |
| `p0_reagent.cljs` | `c4fa66532f7155fb1f1f9996287f597a6d30235e` |
| `p0_uix.cljs` | `554259ddbd4b76da12ed299f4a2d6b0f43b73961` |
| `p0_fixture.cljc` | `1f066a05365e9f47b76b887a3d98e7cd8a9152e8` |
| `p0_arms.cljs` | `beced24315f740eede28cf5f32f855ff91bbd854` |
| `p0_harness.cljs` | `e18c2f50d4f5985d7bc81ff99dfd173ae296f82b` |
| `p0_floor.cljs` | `3a14ff96414f9a77a7612f56181444155b582620` |

The candidate arm is the package, `re-frame.hicasso` under
`implementation/hicasso/src/`. Its doors:

| file | blob |
|---|---|
| `impl/mount.cljs` | `dd82c2ca467bf458493a9b9073eedb9cd0b73fc9` |
| `impl/collector.cljs` | `6fecb70f6905003bc36ef3acfed97fbe957c6ed6` |
| `impl/inventory.cljs` | `10d4d2bb23f673b4bb23b952cb8e7851bf78d81c` |
| `hicasso.cljc` (the facade) | `8641b387629974f2564fe9cbf16748ce1473bfa7` |

**Seven of the nine instrument files have moved since the 2026-08-12 package
rung, and six of them moved within the last day.** Only `p0_arms.cljs` and
`p0_harness.cljs` are byte-identical to that run's pins. The movement is not
noise: `408dfb0aa8`, `f31ac4e94e`, `397c789db1`, `82e8c65184`, `cd8d00c511` and
`1eb77f126a` are the allocation lane building on the same shared driver.
Whatever this window reads is therefore a **new** anchor and not a repair of
the published one — the ladder already says no edit can restore that
comparability, because run 3 priced the prototype and this instrument prices
the package. This section records the drift so that the anchor's shelf life is
a stated property rather than a discovery made later.

Reproduce:

```
node implementation/core/test/re_frame/bench/p0_run.cjs --only ladder
```

(defaults `P0_LADDER_ROUNDS=6 P0_LADDER_RUNGS=0,1,3,7,20 P0_ROOTS=4`; exits
**1** on an unverified mount, a failed positive control or a failed structural
read-back, and **2** if the arm-order guard refuses.)

---

## 7. The runs

**Three declared, three taken, three admissible, none excluded.** Each is a
separate invocation that cleared the build id's cache entry and recompiled the
`:advanced` bundle before measuring, so no two share a build.

### 7.1 The four exit-bearing checks, per run

| check | run 1 | run 2 | run 3 |
|---|---|---|---|
| arm-order guard self-test (12 cases) + ladder-fit self-test (3 cases) | all `ok` | all `ok` | all `ok` |
| structural read-back, `N unverified of M` | **0 of 154** | **0 of 154** | **0 of 154** |
| positive control, predicted `4,700,000 B` | **4,700,284** [4,698,928–4,700,936] | **4,700,230** [4,699,042–4,700,872] | **4,700,230** [4,699,042–4,700,872] |
| control deviation from prediction | +0.006% | +0.005% | +0.005% |
| `lane/control-verdict` at ±25% slack | `OK` | `OK` | `OK` |
| structural witness over the samples | expected counts | expected counts | expected counts |
| arm-order verdict | `reportable` | `reportable` | `reportable` |
| rounds completed | 6 | 6 | 6 |
| captured exit code | `0` | `0` | `0` |

The exit codes are quoted **last and as corroboration**, because on this driver
they are the weakest of the nine lines above: the driver's own header records a
run that printed `VERDICT: reportable` and exited `0` while no write had
reached the page. Every check above was read out of the run's own output.

Runs 2 and 3 report a **byte-identical** positive control, and it is the same
range the 2026-08-12 package rung recorded. That is a property of the control —
a fixed dense array read by a collector that is deterministic once warm — and
run 1, the coldest of the three, is the one that differs.

### 7.2 The anchor: the package arm, three runs, one instrument

`B` = 1,200 boundaries, 4 roots × 300 cells, rungs 0/1/3/7/20, six rounds.
`slope` is the **marginal** read; `shell` is the directly measured `R=0` rung
and never the fitted intercept. Bands are min–max across the six rounds.

| `reagent-subs` \| **hicasso** | slope B/read | shell B (R=0) | r² |
|---|---:|---:|---:|
| run 1 | 1,417 [1,414–1,418] | 1,098 [1,089–1,102] | 0.99833 |
| run 2 | 1,416 [1,413–1,418] | 1,100 [1,092–1,107] | 0.99834 |
| run 3 | 1,417 [1,417–1,417] | 1,101 [1,091–1,111] | 0.99831 |

| `uix-subs` \| **hicasso** | slope B/read | shell B (R=0) | r² |
|---|---:|---:|---:|
| run 1 | 2,116 [2,114–2,118] | 1,096 [1,094–1,102] | 0.99956 |
| run 2 | 2,116 [2,110–2,119] | 1,096 [1,087–1,103] | 0.99956 |
| run 3 | 2,116 [2,110–2,118] | 1,097 [1,088–1,105] | 0.99956 |

The donors were taken in the same runs and are the instrument's own control on
these figures: `reagent` answered **948** B/read in all three, and `uix`
**2,979 / 2,980 / 2,979**.

### 7.3 What this establishes, and it is the point of taking three

**The anchor's own run-to-run spread, on one unchanged instrument and one
unchanged tree:**

| quantity | across the three runs | spread |
|---|---|---:|
| `reagent-subs` \| hicasso, slope | 1,416 – 1,417 B/read | **0.07%** |
| `reagent-subs` \| hicasso, shell | 1,098 – 1,101 B | **0.27%** |
| `uix-subs` \| hicasso, slope | 2,116 – 2,116 B/read | **0.00%** |
| `uix-subs` \| hicasso, shell | 1,096 – 1,097 B | **0.09%** |

**So the `C1` line is measurable by this instrument, and that is what three
runs bought that one could not have.** The widest disagreement between two
readings of the *same* software on the *same* instrument is **0.27%**, which is
roughly one eighteenth of the 5% the rule is written at. A future reading that
moves this arm by more than 5% is therefore attributable to the software rather
than to the instrument — which is exactly the claim `C1` has to be able to make
and, until this window, could not.

Had the three disagreed by more than 5%, the finding would have been that the
rule is unmeasurable here and the line rather than the reading needed the
ruling. They did not.

### 7.4 The seven-day comparison, and why it is reported but not counted

The [2026-08-12 package rung](reads-per-boundary-heap-ladder.md#the-package-itself-priced-on-this-rung-at-last-rf2-fe0l)
measured the same arm on the same rung. Against it:

| quantity | 2026-08-12 | this window | deviation |
|---|---:|---:|---:|
| `reagent-subs` \| hicasso, slope | 1,417 | 1,416 – 1,417 | ≤ 0.07% |
| `reagent-subs` \| hicasso, shell | 1,100 | 1,098 – 1,101 | ≤ 0.18% |
| `uix-subs` \| hicasso, slope | 2,115 | 2,116 | ≤ 0.05% |
| `uix-subs` \| hicasso, shell | 1,095 | 1,096 – 1,097 | ≤ 0.18% |

**This is not a `C1` verdict and must not be quoted as one.** `C1` is written
*on the same witness **and instrument***, and the instrument is not the same:
seven of its nine files moved between those two runs, six of them within the
last day (§6). What the agreement does establish is narrower and still worth
recording — the allocation lane's edits to the shared driver did **not** move
what this rung reads, which is a fact about that lane rather than about
Hicasso.

### 7.5 The verdict, and the one thing this window deliberately did not do

**Gate 2 is CERTIFIED as a measurement and `C1`'s ledger status is NOT moved.**
Those are consistent, and the reason is the rule's own wording rather than
caution.

An anchor now exists: a pinned reading of the package arm, on a named
instrument, with three admissible runs and a stated reproducibility. `C1`'s
blocker has therefore changed in kind — from *"the same instrument" names
nothing*, which is what §6 recorded, to *one anchor exists and a second
same-instrument reading has not been taken*. The second is a far weaker
blocker, but it is still a blocker, because a **regression** rule needs two
readings of the same instrument across a change and this window produced one
reading of an unchanged tree. Nothing was decided against the 5% line, so
writing `MET` would be recording a verdict no comparison reached.

Neither is `UNPINNED` still accurate on its own definition — *no instrument for
it exists on the governed population* — because one now does. **The ledger's
four-valued vocabulary has no cell for *anchored, instrument exists, awaiting
a second reading*, and minting a fifth value is a ruling rather than a
worker's edit.** The cell is therefore left exactly as it was and the
discrepancy is written down here instead of being resolved by whichever
neighbouring value looked closest. That choice is the same one §9.1 records the
`UNRESOLVED` value being invented for: a vocabulary that cannot say what
happened will otherwise round it to something that did not.

No threshold was guessed, no band widened, no figure restated and no ledger
count moved.

## 8. Conditions

Three invocations between **01:03 and 01:19 on 2026-08-19**, back to back on
one drained fleet, each ~3.5–8.5 minutes (run 1 carried a cold compile).
Captured exits `0`, `0`, `0`. React 19.2.0, Reagent 2.0.1, UIx 1.4.4,
`:advanced` with `goog.DEBUG false`, headless Chromium via Playwright,
Windows 11, 24 logical cores.

The box was bracketed at both ends, standalone, never sampled inside a run:

| bracket | queue length | occupancy | `java` | processes | free |
|---|---|---|---:|---|---|
| open, 00:56 | **0** on every sample | 7.12% / 6.63% | **0** | 20 node / 90 chrome / 529 | 21.09 GB |
| close, 01:19 | **0** on every sample | 5.99% / 6.03% | **0** | 20 node / 90 chrome / 528 | 20.94 GB |

No worker was dispatched against this box while the window was open, and
nothing else was run alongside the runs — the cheap source checks behind §3 and
§4 were taken **before** the first invocation and the gate runs **after** the
last.
