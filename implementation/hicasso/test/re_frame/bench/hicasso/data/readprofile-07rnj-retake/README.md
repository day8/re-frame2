# `read_profile` phase-A/B transcripts — `rf2-07rnj`'s whole re-take

Raw driver output from `rf2-07rnj`'s re-take of the commit-half
decomposition, one file per run, in run order. The window is written up in
`docs/design/hicasso/studio/the-cold-read-mount-term.md`; these are the
transcripts that page's republished table is read off.

## THE PRE-REGISTRATION — written and committed BEFORE run 1 started

Everything in this section was fixed before the first run and held for every
arm of every run. It is committed as its own commit so the ordering is
checkable in the history rather than asserted here.

| | |
|---|---|
| **Base commit** | `3de77d3c23` |
| **Instrument blob** | `c220a8c23c44ca6e19f9cab90528d932f271a784` (`read_profile_app.cljs`) |
| **Run count** | **3, fixed before run 1.** The window does not extend because an answer looks unsettled. |
| **Window shape** | 32 frames/window, 8 × (2 + 8) = 64 kept samples per arm, 11 arms, grid 0.0015625 ms/commit — the instrument's shape exactly as it stands. No gate, tolerance or knob is touched. |
| **Series** | The eleven-arm series `rf2-lo7uy` opened. Absolutes are not arm-by-arm comparable with the nine- or eight-arm windows before it. |

**The adjudication rule, fixed before run 1.** Each run carries its own three
nulls — `c-null` (slot 2), `c-null-twin` (slot 9), `c-null-curve` (slot 10) —
each of which is `c-local` again through the same constructor, so each null
delta has a true cost of **exactly zero by construction**. That run's **null
spread** is the closed interval spanned by its three null deltas.

- A term **RESOLVES IN A RUN** iff its delta is strictly greater than the
  maximum of that run's three null deltas.
- A term is **RESOLVED** iff it resolves in all three runs. It publishes as a
  figure: its three readings and its share of that run's `c-local`.
- A term that does not resolve in all three runs publishes as **UNRESOLVED AT
  THIS INSTRUMENT'S RESOLUTION**, with its readings quoted and **NO BOUND OF
  ANY KIND**, and **no share**. A null says what the instrument cannot see,
  never how large the invisible thing is (`rf2-lo7uy`'s close record; the
  withdrawn `< 0.006 ms/commit` is the error this forbids).
- No null is subtracted from any term, and no term is corrected by one.
- **Shares are quoted with the window shape beside them**, because they are not
  invariant to it: the reaction build reads 56% of `c-local` at 4 frames and
  67–68% at 32.

**Refusal is a result.** If the arm-order guards are not reportable, the
phase-A positive control fails, or the phase-B residue gate fires on any run,
the window publishes the refusal and its evidence rather than a number.

**The rig does not change mid-window.** A defect found mid-window stops the
window; no figure is published across a rig change.

## Reproduction

```bash
HICASSO_INIT_FN=re-frame.bench.hicasso.read-profile-app/-main \
HICASSO_OUT_DIR=out/hicasso-readprof \
  node implementation/hicasso/test/re_frame/bench/hicasso/run.cjs
```

## The one redaction, stated so nobody reads these as doctored

Each file is the driver's output verbatim except for exactly one line, the
`shadow-cljs - config:` banner, whose absolute path is replaced by
`<worktree>` and marked inline as redacted — the portability gate refuses a
tracked personal home path and is right to. Nothing else is touched: no
figure, no guard verdict, no exit line.
