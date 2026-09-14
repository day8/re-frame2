# Local visual review over Story's :test-tagged variants — the experiment (rf2-ia2if)

> **What this is.** The experiment Mike resolved to run before deciding whether Story needs an everyday
> visual-review path (parity row B7): Playwright `toHaveScreenshot` over the `:test`-tagged variants,
> run locally, with explicit baselines kept outside the repository and the snapshot-identity content
> hash recorded as provenance only. It builds nothing in `tools/story` or `implementation`.
>
> **Headline.** It works, it is fast, and it did not flake: 13 unchanged runs, 130 comparisons, 0 false
> diffs, about 10 seconds for the 10-case suite. Both change controls held on two bases. It also found
> the one setting a recipe must not leave at its default: Playwright's per-pixel tolerance of 0.2
> passed a real merged change (PR #9827's text-colour reset) as clean on every case, while every content
> hash stayed the same. At `threshold: 0` that change differs on all 10 cases and three unchanged runs
> still differ on none.
>
> **Recommendation: (a)**, a recipe in `docs/story/08-snapshot-identity-and-sharing.md`. Reasons in §7.
>
> **Bead:** rf2-ia2if, under epic rf2-0ae7o. **Measured:** 2026-09-14 21:50–22:10 AUSEST, Windows 11,
> Playwright 1.59.1 with Chromium 147.0.7727.15. **Script:** `visual-review.pw.cjs` and
> `visual-review.config.cjs`, beside this report.

## 1. Bases, and why there are two passes

The brief said to check PR #9827 (rf2-w72ij) before capturing any baseline, because it changes the
subject's text styles and the shell backdrop. It read OPEN at 21:39:36 AUSEST and merged 18 seconds
later, at 21:39:54, ending at commit `0bf6c5f413`. The first baselines were taken at 21:50, so the
first pass does not include it.

- **Pass 1** at trunk commit `8ba0fb4516`, without #9827.
- **Pass 2** after rebasing onto trunk commit `1be7d839d0`, which carries #9827. The whole protocol ran
  again: approval, three unchanged runs, both controls and their restores.
- **Threshold-0 readings** at `1be7d839d0`, after pass 2 showed the default tolerance missing #9827's
  change (§5).

The content hashes were identical in both passes, as they should be: #9827 changed CSS and the view,
not any declared input.

A page was trusted only once the served `main.js` was byte-identical to the watch's own compile (three
watches, and again after every control recompile). Nothing listened on :8040, :8043 or :9630 before
each watch started, and each watch's process tree was stopped afterwards.

## 2. Premises checked at source

| Claim | Finding |
|---|---|
| The login-form testbed has five `:test`-tagged variants | Confirmed in the running page through the public `re-frame.story/variants-with-tags #{:test}`: `authenticated`, `error`, `idle`, `submitting`, `submitting-retry`. All five carry a `:script`, and every capture settled on a `pass` play status. |
| Modes come from the nine-states showcase on :8040 | Does not hold. `examples/patterns/nine_states/stories.cljs` has no `:test`-tagged variant and registers no mode. The theme axis lives on the login-form testbed itself, as `:Mode.login-form/light` and `:Mode.login-form/dark` (both `:axis :theme`), so :8043 carries the whole matrix and :8040 was never started. |
| The variant root is stamped | `data-rf-story-variant-root` in `ui/canvas.cljs`. The canvas section also stamps `data-snapshot-hash`, the snapshot-identity content hash under the active modes, substrate and cell overrides; the script reads provenance from there. |
| `:rf.assert/visual-snapshot` compares the key, not pixels | Confirmed. `tools/story/spec/017-Testing-Story.md` already says so in its "What `:rf.assert/visual-snapshot` compares today" paragraph, which names this experiment. |

Light and dark carry different content hashes for every variant, so active modes are identity-bearing.

## 3. The script

Two files, 123 lines, 74 of them code:

- `visual-review.pw.cjs` (88 lines, 59 code) is the review.
- `visual-review.config.cjs` (35 lines, 15 code) exists only because Playwright's CLI has no flag for
  the snapshot path, and the default puts baselines beside the spec, inside the repository. It sends
  baselines and diff output to `STORY_VISUAL_DIR`, refuses to run without it, and sets `threshold: 0`.

What the spec does, in order:

1. Opens the Story shell, waits for the registry, and enumerates the `:test`-tagged variants and the
   registered `:theme` modes from the running page. A new variant joins without an edit.
2. For each case `<variant-id>--<theme>--<viewport>--<browser>` (10 here: 5 variants, 2 themes,
   1280x800, chromium) it navigates to the share URL `?variant=…&modes=…#/stories`.
3. It settles on the variant's play-status chip reaching a terminal status, not on a sleep.
4. It reads the content hash from the canvas, then takes one soft `toHaveScreenshot` of the variant
   root. **Every case is captured and compared on every run**; nothing consults the hash first.
5. On an approval run (`-u`) it writes a sidecar beside each baseline holding the hash. On any other run
   it prints whether the hash moved against that sidecar, as provenance.

Run from `implementation/`, against `npx shadow-cljs watch :examples/login-form`:

```
STORY_VISUAL_DIR=<scratch dir> npx playwright test -c ../tools/story/spec/findings/parity-2026-09/fable/visual-review.config.cjs [-u]
```

A plain run with no baseline writes one and fails, so a first capture cannot pass silently; approval is
always an explicit `-u`.

Two things went wrong while building it, and both belong in any recipe. A CLJS keyword made from JS is
a fresh object, so `===` never matches one: the first approval run enumerated no themes until the
filter compared `.fqn`. And Playwright rewrites snapshot names (`.` becomes `-`), so the script spells
each name the way it lands on disk.

## 4. Results

Load is the CPU `LoadPercentage` at the start and end of each run. Another worker ran JVM and node gates
on the same machine throughout.

**Pass 1** — trunk `8ba0fb4516`, without #9827, default tolerance

| Run | Exit | Cases differing | Hashes moved | Wall | Load |
|---|---|---|---|---|---|
| approval (`-u`) | 0 | — | — | 10.9 s | 16 → 20 |
| unchanged 1 | 0 | 0 | 0 | 8.3 s | 27 → 7 |
| unchanged 2 | 0 | 0 | 0 | 8.4 s | 45 → 48 |
| unchanged 3 | 0 | 0 | 0 | 8.5 s | 60 → 21 |
| control 1, CSS only | 1 | 10 | 0 | 10.9 s | 94 → 24 |
| after restoring control 1 | 0 | 0 | 0 | 8.5 s | 23 → 11 |
| control 2, heading arg | 1 | 10 | 10 | 11.0 s | 9 → 10 |
| after restoring control 2 | 0 | 0 | 0 | 10.4 s | 22 → 3 |

**Pass 2** — trunk `1be7d839d0`, with #9827, default tolerance

| Run | Exit | Cases differing | Hashes moved | Wall | Load |
|---|---|---|---|---|---|
| approval (`-u`) | 0 | — | — | 11.5 s | 45 → 35 |
| unchanged 1 | 0 | 0 | 0 | 9.8 s | 63 → 72 |
| unchanged 2 | 0 | 0 | 0 | 10.8 s | 91 → 19 |
| unchanged 3 | 0 | 0 | 0 | 10.2 s | 8 → 5 |
| control 1, CSS only | 1 | 10 | 0 | 31.1 s | 5 → 31 |
| after restoring control 1 | 0 | 0 | 0 | 9.7 s | 17 → 8 |
| control 2, heading arg | 1 | 10 | 10 | 12.3 s | 9 → 7 |
| after restoring control 2 | 0 | 0 | 0 | 37.9 s | 19 → 74 |
| pass-1 baselines against this trunk | 0 | **0** | 0 | 11.4 s | 20 → 15 |

**Threshold 0** — trunk `1be7d839d0`, against the pass-2 baselines unless named

| Run | Exit | Cases differing | Hashes moved | Wall | Load |
|---|---|---|---|---|---|
| pass-1 baselines against this trunk | 1 | **10** | 0 | 12.6 s | 23 → 29 |
| unchanged 1 | 0 | 0 | 0 | 9.8 s | 29 → 16 |
| unchanged 2 | 0 | 0 | 0 | 9.6 s | 11 → 25 |
| unchanged 3 | 0 | 0 | 0 | 10.3 s | 15 → 34 |

### The controls

1. **CSS only: the hash stays, the capture must differ. Held on both bases.** One rule was planted,
   uncommitted, in the testbed page `tools/story/testbeds/login_form/index.html`:
   `[data-test="login-heading"]{letter-spacing:0.02em}`. The served page carried it before the run.
   Every case differed (161 pixels each in pass 1, 162 in pass 2), and every content hash equalled its
   approved sidecar. A runner that skipped captures on an unchanged hash would have reported this
   regression green.
2. **Real change: the capture differs and the hash moves. Held on both bases.** The parent story's
   `:args {:heading "Sign in"}` became `"Sign in now"` in `tools/story/testbeds/login_form/stories.cljs`,
   uncommitted. The watch recompiled in under a second, and the served `main.js` matched that compile
   and carried the new text before the run. Every case differed (144 pixels each in pass 1, 145 in
   pass 2), and all 10 content hashes moved.
3. **Flake: held.** 13 unchanged runs, 130 comparisons, 0 false diffs, with load readings between 3 and
   91.

Both plants were restored with `git checkout` and checked against the committed object by the default
`git hash-object` form: `index.html` back to blob hash `7ad7c4df47`, and `stories.cljs` back to blob
hash `77d7f21f31`. After each restore the served bytes were checked again, and the next run was clean.

## 5. A real change the default tolerance passed

#9827 is exactly the change control 1 stands in for, and it landed mid-experiment. It resets the
subject's text colour at the cell boundary and removes the login card's own `#1f2328`. No declared
input changed, so all 10 content hashes were identical before and after.

Decoded pixel by pixel, the pass-1 and pass-2 baselines differ on every capture: 1898 pixels on the two
`authenticated` cases and 508 on the other eight. Most are heading text going from `rgb(31, 35, 40)` to
`rgb(0, 0, 0)`; the largest channel change is 41 of 255.

At Playwright's default `threshold: 0.2` the pass-1 baselines compared **clean** against the merged
trunk, 0 of 10 cases. At `threshold: 0` the same comparison differed on all 10 (1012 and 268 pixels;
Playwright leaves anti-aliased pixels out of its count), and three unchanged runs still differed on
none. The config therefore sets `threshold: 0`.

So the one rule has two halves, and this experiment measured both. Never skip a capture because the
hash did not move, since the hash does not see CSS. And do not let the comparator's default tolerance
do the skipping instead.

## 6. Timings

- **Suite:** 8.3 to 12.6 seconds for the 10 cases, one Chromium and one worker, when no case stalled.
- **Per case**, navigation to settled capture: 480 to 900 ms steady, with the first case of each run at
  700 to 1160 ms.
- **Two stalls:** 18.5 s on the first case of pass-2 control 1 (load 5 → 31), and 25.4 s on one case of
  the pass-2 run after restoring control 2 (load rising to 74). Neither produced a false diff; they are
  why those two rows' wall times are long.
- **Once per session, the watch:** a cold compile of 760 files took 37.8 s, a warm restart 16 to 18 s,
  and the recompile after the arg edit under a second.

## 7. Recommendation: (a), a recipe in docs/story/08

The description's criteria are met: no flake, both controls held on two bases, and the suite runs in
seconds, not minutes.

**Why (a).** Chapter 08 already states the division of labour: Story "does not need to be the
pixel-diff service. It needs to hand the pixel service a stable, meaningful key." The experiment shows a
user can do the rest in about 75 lines of stock Playwright, with nothing Story-specific to install.
What a user needs from Story is what took measuring to learn, and that is documentation, not code:

- enumerate the `:test`-tagged variants with `re-frame.story/variants-with-tags`, and the `:theme`
  modes from `re-frame.story/registrations`;
- settle on the play status, and screenshot `[data-rf-story-variant-root]`;
- record the snapshot-identity content hash beside each baseline, but **never skip a capture because it
  did not move**;
- set `threshold: 0`, because the default passed a real colour regression on every case;
- keep baselines outside the repository, and approve only with `-u`.

The two files beside this report are the sample.

**Why not (b).** Its "one file" premise does not hold, because Playwright needs a config beside the spec
to keep baselines out of the repository. A `story:visual` script that is useful on its own would also
have to start a watch, wait for it and prove the served bundle is its own, which is the job of the
in-tree serve-and-run runners, at several times this size. And a package script no CI job runs rots
just as a doc sample does, while adding a surface somebody maintains.

**Why not (c).** Nothing measured argues for waiting.

**The cost of (a).** No gate will ever run the sample, and it leans on:

- the dev build's JS globals for `re-frame.story`, so it needs a watch rather than the `:advanced` static
  build;
- Story's test hooks: `data-test="story-play-status"`, `data-test-variant`, `data-snapshot-hash` and
  `data-rf-story-variant-root`;
- the share URL's `variant` and `modes` parameters;
- the help overlay's `re-frame.story/seen-help-v1` localStorage key.

Renaming any of those breaks the recipe silently.

**For the spec paragraph.** 017 already says `:rf.assert/visual-snapshot` compares the identity key, and
names this experiment. If (a) lands, the clause it still lacks is that pixels are compared by an
external runner the user brings, pointing at the recipe.

## 8. What stays untracked, and where

Baselines, hash sidecars and diff images are scratch, kept in the coordinating session's scratchpad
directory and not in the repository:

- `storyvisual-a-worker-visual/` holds pass 1: `baselines/`, plus the diff images in `control1-results/`
  and `control2-results/`.
- `storyvisual-a-worker-visual2/` holds pass 2, and the baselines the threshold-0 unchanged runs used.
- `storyvisual-a-worker-visual3/` holds the pass-1 baselines compared against the merged trunk, with
  `w72ij-compare-results/`.

Each `baselines/` directory holds one PNG and one `-hash.json` sidecar per case. The sidecar content
hashes, identical in both passes:

| Variant | dark | light |
|---|---|---|
| `:story.login-form/authenticated` | `9695a3e8` | `36c287b9` |
| `:story.login-form/error` | `5f90debc` | `1ca9334a` |
| `:story.login-form/idle` | `38e02271` | `32ccdbc5` |
| `:story.login-form/submitting` | `290c902f` | `18d39dd2` |
| `:story.login-form/submitting-retry` | `47a37375` | `813cecd3` |

With the control-2 heading the content hashes moved to these, also identical in both passes:

| Variant | dark | light |
|---|---|---|
| `:story.login-form/authenticated` | `7b83d447` | `8aa6a62c` |
| `:story.login-form/error` | `337d3100` | `e4beb81d` |
| `:story.login-form/idle` | `a4d56c39` | `0b4325cb` |
| `:story.login-form/submitting` | `14a78a11` | `a403cb81` |
| `:story.login-form/submitting-retry` | `3a3a3acc` | `ac4477cc` |

## 9. Limits

- One browser, one viewport, one testbed. Firefox and WebKit are installed but were not run, so
  cross-browser cost and flake are unmeasured.
- The screenshot is the variant root, so a regression in Story's own chrome is out of scope by design.
- Load was not controlled, since another worker ran gates throughout. That strengthens the flake
  result, but the timings are for a shared machine.
- The script relies on the dev-build globals and the DOM hooks listed in §7.
