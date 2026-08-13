#!/usr/bin/env bash
#
# The failure branch of every `npx playwright install` step in CI (rf2-swos).
#
# # What this is for
#
# Twenty-one CI steps provision browsers with `npx playwright install
# --with-deps <browsers>`. When that fails the job dies with a bare
#
#     ##[error]Process completed with exit code 1
#
# which, at the checks rollup, is INDISTINGUISHABLE from the gate the job
# exists to run having failed. Measured 2026-08-12 on PR #8055, job
# 94299983393: the diff under test was a corpus fix and a docstring
# correction, no test ran, and establishing that cost a diagnosis cycle
# reading the raw log. That is the cost rf2-swos was filed against, and it is
# the same cost rf2-xsfr (#8030) and rf2-gfvy (#8036) deleted at the Clojure
# CLI, clj-kondo and Babashka installs. This is the same remedy at the fourth
# site: on failure the step now says, on the checks page, that no gate ran.
#
# "No gate ran" is the whole of the claim. It is deliberately NOT "not this
# diff" — see the next section, which is the correction the merged-PR audit of
# #8061 reopened this bead for.
#
# Every call site is one line:
#
#     - name: Install Playwright (Chromium + system deps)
#       run: npx playwright install --with-deps chromium || "$GITHUB_WORKSPACE/.github/scripts/playwright-install-failed.sh"
#
# Use the $GITHUB_WORKSPACE-absolute form, not a relative path: several jobs
# set a `defaults.run.working-directory` (e.g. `implementation`), under which
# `./.github/scripts/...` would not resolve. Keep the `||` on the SAME logical
# line as the `npx` — `scripts/check_fast_pr_gap.py` classifies a step as
# toolchain setup only when EVERY logical line of the body matches one of its
# `SETUP_PATTERNS`, and the pattern that covers these steps is
# `^npx playwright install\b`. A separate `echo` line, or a `for` loop, would
# drop the body out of that set and make the gap map report the job as an
# UNRUN GATE — a fail-open in the very map the merge criterion consults.
# Appending `|| <script>` behind the existing command keeps the logical line
# starting with `npx playwright install`, so nothing reclassifies. It is also
# what keeps the literal `playwright install --with-deps <browsers>` text that
# `implementation/scripts/_changed-surfaces.test.cjs` and
# `tools/template/test/day8/re_frame2_template/release_gate_test.clj` assert
# on intact, so this change needs no edit outside `.github/`.
#
# # What the annotation can support — and why it no longer says "not this diff"
#
# The first cut of this file (#8061) titled EVERY nonzero exit "CI
# infrastructure — not this diff" and said in the body that the red "says
# nothing whatever about the diff". That is a claim about the diff, and this
# script has no way to reach it. It knows three things and no more:
#
#   * the install exited nonzero — it is the `||` branch, so that is a given;
#   * therefore the browser is absent, the gate step never started, and NO GATE
#     RAN IN THIS JOB. True whatever the cause, and worth saying, because the
#     bare `##[error]Process completed with exit code 1` at the rollup does not;
#   * its own environment — the working directory it was invoked in, which is
#     the one fact that tells the reader WHICH Playwright root failed.
#
# It cannot see the diff. It cannot even see the install's output: the call
# site is `npx playwright install … || <this script>`, so the fetcher's stderr
# goes to the job log and never to this process. A signature classifier of the
# `resolve-clojure-deps.sh` kind is therefore not available here without
# changing the call-site shape, and the shape is pinned by the gap-map coupling
# described above.
#
# And the claim was not merely unsupported, it was wrong for a real class. A PR
# can break this install itself: by editing the browser arguments on the `npx
# playwright install` line, the step's `working-directory`, `env` or download
# host, the runner image or `setup-node`/cache inputs, this script, or the
# Playwright pin the job resolves. Under the old wording all of those reds
# arrived titled "not this diff" — a confident wrong answer telling the author
# to look away from the change that caused it. That is the same fault the
# merged-PR audits of #7132 and #7221 found in `resolve-clojure-deps.sh`, whose
# comment states the rule this file now follows: the bias is that a fault it
# cannot classify degrades to "we do not know" rather than to "not your diff".
#
# So the title states only what is certain — the install failed, no gate ran —
# and the body keeps every useful measured fact as CONDITIONAL guidance: first
# the provisioning inputs a diff can reach, then the re-run remedy for the case
# where the diff reaches none of them. The reader loses nothing; the annotation
# stops deciding for them.
#
# # The two Playwright roots — the pin depends on which root the step ran in
#
# The old caveat named `implementation/package-lock.json` alone. There are two
# roots, resolving different pins from different lockfiles, and `npx` resolves
# whichever one the step's working directory sits in:
#
#   * `implementation` — `package.json` pins `playwright` to an exact `1.59.1`;
#     19 of the 21 sites run here, each after an `npm ci` in the same root.
#   * `docs/tools/playground` — `package.json` asks for `^1.60.0`, and its own
#     `package-lock.json` resolves `1.60.0`; the two playground smokes run here
#     (`docs.yml`'s post-merge `build` job and `test.yml`'s `tools-playground`).
#
# Different pins fetch different Chrome-for-Testing revisions, which is why
# rf2-169n (#8065) keys the two browser caches on the two lockfiles separately.
# The annotation therefore prints `$PWD` and names both locks, leaving the
# reader one obvious step — match the directory to its lockfile — instead of a
# caveat that is silently inapplicable in two of twenty-one jobs.
#
# # Why there is no retry loop here — the siblings have one and this does not
#
# The bead's premise was that Playwright's browser fetch "has no retry
# envelope". Read at source, it has one: `playwright-core`'s
# `lib/server/registry/browserFetcher.js` runs `const retryCount = 5` and
# loops `for (let attempt = 1; attempt <= retryCount; ++attempt)`. So the
# (a)-half of the sibling remedy — widen a too-narrow envelope — is already
# half-present, and the two failures actually measured are not failures a
# wider envelope would have caught:
#
#   * 2026-08-12, job 94299983393 — five attempts in 3.85 seconds, every one
#     answered `403 AccessDenied` with the body "We're sorry, but this service
#     is not available in your location". `https://cdn.playwright.dev/builds/
#     cft/<ver>/linux64/chrome-linux64.zip` is a 307 REDIRECTOR to
#     `https://storage.googleapis.com/chrome-for-testing-public/...` (verified
#     by request), so that 403 is Google Cloud Storage denying the runner's
#     egress IP by region. The IP does not change for the life of a job, so
#     retrying it — for four seconds or for seven minutes — asks the same
#     bucket the same question from the same address. What clears it is a
#     re-run, which lands on a different runner. A jittered backoff loop would
#     have converted a 4-second red into a 7-minute red and changed nothing
#     else.
#
#   * 2026-08-11, job 93920468065 — `##[error]The action 'Install Playwright
#     (Chromium + system deps)' has timed out after 5 minutes`, spent entirely
#     inside the `--with-deps` apt hop fetching 21MB of fonts from
#     azure.archive.ubuntu.com. The browser download was never reached. Three
#     of the twenty-one sites carry `timeout-minutes: 5`; an envelope wider
#     than that cap cannot finish, and a step the runner guillotines never
#     prints its annotation at all. There, a wider loop is not merely useless
#     but actively worse than what it replaces.
#
# Playwright's own knobs were preferred to hand-rolling, per the brief, and
# both were checked at source and rejected on measurement, not on taste:
#
#   * MIRROR ROTATION. `registry/index.js` normally hands the fetcher three
#     hosts (`PLAYWRIGHT_CDN_MIRRORS`) and the retry loop rotates them,
#     `downloadURLs[(attempt - 1) % downloadURLs.length]`. Chromium is the
#     exception: `cftUrl()` overrides `mirrors` to the single element
#     `["https://cdn.playwright.dev"]`, which is why all five attempts in the
#     measured failure hit one URL. Firefox and WebKit keep the rotation.
#
#   * `PLAYWRIGHT_CHROMIUM_DOWNLOAD_HOST` / `PLAYWRIGHT_DOWNLOAD_HOST`. The
#     knob REPLACES the mirror list with one host, and the only other host in
#     Playwright's own list answers `400` for the Chrome-for-Testing path
#     (`https://playwright.download.prss.microsoft.com/dbazure/download/
#     playwright/builds/cft/<ver>/linux64/chrome-linux64.zip`, verified by
#     request): the ESRP CDN does not carry CfT builds. Nor can the knob be
#     pointed at the Google bucket directly, because it is prepended to the
#     path `builds/cft/<ver>/...`, a layout only Playwright's own redirector
#     understands. There is no working alternative value, so the knob is not
#     set.
#
# So this file ships the (c)-half of the sibling remedy — an explicit
# annotation and a non-zero exit, rather than falling through to a later
# "command not found" — and refuses the (a)-half, the widened retry envelope,
# on the evidence. It parts company with the siblings on one point only: their
# annotations classify the failure as infrastructure in the title, which they
# can nearly support because the only diff-reachable input at those three sites
# is a constant URL in the very file the caveat names. This site has a dozen
# such inputs across two roots, so the title classifies nothing.
#
# If a transient 5xx or ECONNRESET is ever measured at this site — as it was at
# the other three on 2026-08-13 — Playwright's own five attempts are the thing
# to widen, and an outer loop belongs here then, sized under the tightest
# `timeout-minutes` of its call sites.
#
# Exit status stays a plain 1, as in `install-clojure-cli.sh` and
# `resolve-clojure-deps.sh`: the annotation is the machine-readable carrier
# the checks API exposes, and a bespoke code would be a second convention with
# no consumer.
#
set -euo pipefail

echo "::error title=Playwright browser install failed — no gate ran::npx playwright install exited nonzero in ${PWD}. This step provisions the browser, so the gate this job exists to run never started: the red reports PROVISIONING and carries no verdict on the code under test. WHICH KIND of failure it is depends on the diff, and this script cannot tell you — it is the || branch of the install and sees neither the diff nor the install's output. BEFORE RE-RUNNING, check whether this PR touches a provisioning input: the browser arguments on the npx playwright install line, the step's working-directory or env or download host, the runner image or setup-node or cache inputs, this script itself, or the Playwright pin in the lockfile of the root shown above — implementation/package-lock.json pins 1.59.1, docs/tools/playground/package-lock.json pins 1.60.0. If it touches any of them, read this step's raw log first: a diff-caused install failure lands here looking exactly like an outage. If it touches none of them, this is infrastructure and the remedy is a re-run, which lands on a different runner. Playwright already retries the download five times internally (playwright-core browserFetcher.js), and both modes measured under rf2-swos need a different runner rather than more attempts: a Google Cloud Storage 403 'this service is not available in your location' on the Chrome-for-Testing bucket that cdn.playwright.dev redirects to, scoped to this runner's egress IP; and a slow azure.archive.ubuntu.com apt hop under --with-deps."
exit 1
