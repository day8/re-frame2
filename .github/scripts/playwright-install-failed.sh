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
# So this file ships the (c)-half of the sibling remedy and refuses the
# (a)-half, on the evidence. If a transient 5xx or ECONNRESET is ever measured
# at this site — as it was at the other three on 2026-08-13 — Playwright's own
# five attempts are the thing to widen, and an outer loop belongs here then,
# sized under the tightest `timeout-minutes` of its call sites.
#
# Exit status stays a plain 1, as in `install-clojure-cli.sh` and
# `resolve-clojure-deps.sh`: the annotation is the machine-readable carrier
# the checks API exposes, and a bespoke code would be a second convention with
# no consumer.
#
set -euo pipefail

echo "::error title=Playwright browser install failed — CI infrastructure — not this diff::NO GATE RAN IN THIS JOB. This step provisions the browser, so the step that would have tested this change never started and this red says nothing whatever about the diff. Playwright already retries the download five times internally (playwright-core browserFetcher.js), so the remedy is a re-run, which lands on a different runner. Two modes have been measured (rf2-swos): a Google Cloud Storage 403 'this service is not available in your location' on the Chrome-for-Testing bucket that cdn.playwright.dev redirects to, which is scoped to this runner's egress IP; and a slow azure.archive.ubuntu.com apt hop under --with-deps. Neither is affected by anything in this PR UNLESS it changes the Playwright pin in implementation/package-lock.json or edits .github/scripts/playwright-install-failed.sh, in which case read those first."
exit 1
