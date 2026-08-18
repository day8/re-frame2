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

*This section was empty when §1–§6 were committed.*
