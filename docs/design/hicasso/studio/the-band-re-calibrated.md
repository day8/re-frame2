# The band, re-calibrated on the clock it judges

**Status: predictions registered, ladder running.** This section is committed
*before* the ladder's first result is read, so the order of the commits is the
pre-registration. Nothing below is a finding yet.

Owner: `rf2-ymi6j`. Companion question: `rf2-h8o80`.

---

## 0. Predictions, written before the run

`rf2-cvvb7`'s nineteen-run load ladder calibrated the band, its 25% ceiling and
the multiplicativity argument, and `rf2-yd52q` then showed that every one of
those figures was computed on `taskNet` — the frame with the operation's own
script subtracted out. The ladder cannot be recomputed: at its driver blob the
term `roundsTask` did not appear in the file and no dataset survives. So this is
a **re-run**, and these are its predictions.

The box: 24 cores (Intel Core Ultra 9 275HX), 68.1 GB, node v24.13.0,
Windows 11, sole occupant. Registered 2026-08-02 07:21 AUSEST.

**P1 — the two clocks' floors are not the same number.** The raw `TaskDuration`
floor absolute will exceed the `taskNet` floor absolute at *every* rung, by
roughly the floor arm's own in-page window plus the tare's protocol round trip:
`task floor ≈ taskNet floor + 0.8–1.5 ms`. *If true*, a floor stated on one
clock cannot be read against a range calibrated on the other — and `rf2-h8o80`'s
central evidence, an `M1` floor of 5.98–6.95 ms held against the ladder's
3.06–5.50 ms, is doing exactly that.

**P2 — the corrected-clock band runs tighter, not wider.** Two published
ensembles put the band at 8.8–19.7% on raw `TaskDuration` against 10.3–23.6% on
`taskNet` over the same sixteen row-runs. The median band across this ladder
will be **no wider** on raw `TaskDuration` than on `taskNet`. *If refuted* —
corrected band systematically wider — then the ceiling is too tight for the
figures it now judges, and that alone explains the firings.

**P3 — 25% is a tail threshold and nineteen runs cannot bound its tail.** The
ceiling fires on at least one run of this ladder on at least one clock, and a
bootstrap of the band's own run-level sampling distribution puts
`P(band > 25%)` between **2% and 15%** on the frame-only clock.

**P4 — multiplicativity is the finding most at risk.** `rf2-cvvb7` measured
`corr(ctl-2x/floor, floor) = +0.41` on `taskNet` and read the positive sign as
evidence against an additive perturbation. On the corrected clock an additive
per-sample constant `c ≈ 0.8–1.0 ms` is *known* to exist (`rf2-emvod` inverted
`ctl-2x` to it on four rows; `rf2-7iqb5` re-measured it). The corrected-clock
correlation will be **less positive than +0.41**, and a negative one would not
surprise me — negative is the additive signature.

**P5 — the seam still does not track load.** No monotone trend in the pooled
seam across rungs, on either clock.

**P6 — the instrument's blocks are longer than they were.** The instrument in
force adds three `ctl-3pt` arms to every bulk block (`rf2-7iqb5`), which the
calibration blob did not have. More arms between a `floor` sample and a
`ctl-2x` sample is more room for drift, so the rung-0 band at this blob is **at
or above** `rf2-cvvb7`'s rung-0 band of 9.3%.

**The discard rule, fixed in advance.** A run that dies part-way is discarded
and not reported. A run that *completes* and then fails a **gate** — the
control, the ceiling — is a completed measurement and is kept. The ceiling
firing is the datum under study, and dropping the runs that fire would
calibrate the ceiling on the runs that pass it.
