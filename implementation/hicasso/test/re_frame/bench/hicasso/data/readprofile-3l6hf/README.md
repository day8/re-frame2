# `read_profile` phase-A/B transcripts — rf2-3l6hf's null-control window

Raw driver output from the three runs of `rf2-3l6hf`'s measurement window
(2026-08-16), one file per run, in run order. This is the first window taken
on the instrument with a **measured negative control** (`c-null`), which is
what the merged-PR audit of #8328 named as the thing that would settle the
reader-membership term. The window is written up in
`docs/design/hicasso/studio/the-cold-read-mount-term.md`.

**Reader membership is still UNRESOLVED, and now on direct evidence rather
than on the arithmetic-impossibility argument.** The null control — an arm
whose true cost is exactly zero by construction — read `+0.0234 / +0.0219 /
+0.0234` ms/commit. Reader membership read `+0.0422 / +0.0219 / +0.0359`. In
run 2 the two are equal to the last digit: the instrument reported the same
number for reader membership as it reported for nothing at all. **No bound on
the term is published, and none can be read off these numbers** — a null
spread says what the instrument cannot see, not how large the invisible thing
is.

The other four ablation terms clear the null on all three runs.

## What produced them

| | |
|---|---|
| **Commit** | `926dd471d3` |
| **Instrument blob** | `ef7a0d3787400734eafbc9b18ad96bd34b5fee8a` (`read_profile_app.cljs`, identical before and after the window — the working tree was clean at both ends) |
| **Window shape** | 32 frames/window, 8 × (2 + 8) = 64 kept samples per arm, grid 0.0015625 ms/commit — unchanged from `rf2-07rnj`'s window, chosen once and held for every arm of every run |
| **Arms** | 9, one more than the predecessor window: `c-null` was added **before** the window opened |
| **Build** | `:advanced`, `goog.DEBUG false` |
| **Runtime** | HeadlessChrome (Playwright), see each file's own runtime banner |
| **Outcome** | `exit 0` on all three; both arm-order guards reportable on each; phase-A positive control passing on each; phase-B residue gate never firing |

## The estimator these files report

Each `p50` in the phase-B arm table is a **pooled median over the 64 kept
samples of that arm**, each sample first divided by the 32-frame window — and
because 64 is even, that median is the mean of the 32nd and 33rd order
statistics. It is **not** a mean of per-round medians, and the two differ
here: run 1's null control reads 0.0234 as a pooled-p50 delta and 0.0174 as a
mean of its own per-round deltas.

Each delta is the difference of two such pooled p50s.

`:read-profile-commit-per-round` and `:read-profile-commit-per-round-deltas`
carry the per-round values — a within-round p50 over that round's 8 kept
samples, and its delta against `c-local`. They are recorded so this window can
be re-adjudicated without being re-taken.

## Reproduction

```bash
HICASSO_INIT_FN=re-frame.bench.hicasso.read-profile-app/-main \
HICASSO_OUT_DIR=out/hicasso-readprof \
  node implementation/hicasso/test/re_frame/bench/hicasso/run.cjs
```

## The one redaction, stated so nobody reads these as doctored

Each file is the driver's output verbatim **except for exactly one line**, the
`shadow-cljs - config:` banner, whose absolute path is replaced by
`<worktree>` and marked inline as redacted. Nothing else was touched — no
figure, no guard verdict, no exit line.

The path was the window's proof that every build and every gate read the
worker's own worktree rather than the mayor checkout, which is why the line is
kept rather than deleted. It cannot be committed as it stood: the portability
gate (`scripts/check-no-hardcoded-paths.sh`) refuses any tracked file carrying
a personal home path, correctly and with no escape hatch. Re-running the
command above prints the same banner with the reader's own checkout in it.
