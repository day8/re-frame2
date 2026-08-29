# `read_profile` phase-A/B transcripts — rf2-07rnj's measurement window

Raw driver output from the 3 runs of `rf2-07rnj`'s measurement window
(2026-08-16), one file per run, in run order. The window is written up in
`docs/design/hicasso/studio/the-cold-read-mount-term.md`; these are the
transcripts that page's refusal is read off.

The window declined to publish a decomposition. 4 of the 5 phase-B
ablation terms resolve with one sign on every run; reader membership reads
`+0.0047 / −0.0141 / +0.0125` and straddles zero, and a negative delta is
arithmetically impossible when `c-noreaders` does strictly less work than
`c-local`. Nothing here restates a published figure, and no share is computed
from these numbers.

## What produced them

| | |
|---|---|
| **Commit** | `a43aa8609f` |
| **Instrument blob** | `6d2b67b5b033d57c20a993732fd7f5664c3188ac` (`read_profile_app.cljs`, identical before and after the window) |
| **Window shape** | 32 frames/window, 8 × (2 + 8) = 64 kept samples per arm, grid 0.0015625 ms/commit — chosen once and held for every arm of every run |
| **Build** | `:advanced`, `goog.DEBUG false`, lane cache cleared per run |
| **Runtime** | HeadlessChrome 147.0.7727.15 (Playwright), node v24.13.0 |
| **Outcome** | `exit 0` on all three; both arm-order guards reportable; phase-A positive control passing; phase-B residue gate never fired |

## Reproduction

```bash
HICASSO_INIT_FN=re-frame.bench.hicasso.read-profile-app/-main \
HICASSO_OUT_DIR=out/hicasso-readprof \
  node implementation/hicasso/test/re_frame/bench/hicasso/run.cjs
```

## The one redaction, stated so nobody reads these as doctored

Each file is the driver's output verbatim except for exactly one line, the
`shadow-cljs - config:` banner, whose absolute path is replaced by
`<worktree>` and marked inline as redacted. Nothing else was touched — no
figure, no guard verdict, no exit line.

The path was the window's proof that every build and every gate read the
worker's own worktree rather than the mayor checkout, which is why the line is
kept rather than deleted. It cannot be committed as it stood: the portability
gate (`scripts/check-no-hardcoded-paths.sh`, run by
`.github/workflows/portability.yml`) refuses any tracked file carrying a
personal home path, correctly and with no escape hatch. Re-running the command
above prints the same banner with the reader's own checkout in it, which is the
better proof anyway — a path in a transcript proves only where somebody else
once stood.
