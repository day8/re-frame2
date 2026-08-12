#!/usr/bin/env bash
#
# Resilient Clojure CLI install for GitHub Actions (rf2-9sgj8, extracted
# rf2-e7ja9, envelope widened and failure boundary made explicit rf2-xsfr).
#
# # Why this is not `DeLaGuardo/setup-clojure`
#
# setup-clojure's internal linux-install.sh curl has no retry, so a transient
# curl-35 / socket-hang-up reds a JVM job in *setup*, before any test runs.
# Under load that flaked repeatedly across the matrix. This runs the official
# linux-install.sh directly, wrapping it in the repo's clj-kondo curl-retry
# idiom (lint.yml) plus a backoff loop over the whole download+install — see
# the failure-boundary note below for how wide that loop is and why.
# No github-token is needed — the releases/latest/download redirect hits no
# GitHub API, so this does not consume the job's API budget.
#
# # Why one script instead of 59 copies
#
# rf2-9sgj8 hardened ~59 call sites by copying this body into each one. That
# put the download URL, curl policy, backoff, privilege, and the post-install
# validation boundary in 59 places where they could silently drift apart.
# This file is the single owner of that policy (rf2-e7ja9).
#
# # Calling it
#
# Every job that installs the CLI checks out the repository first, so:
#
#     - name: Set up Clojure CLI
#       run: "$GITHUB_WORKSPACE/.github/scripts/install-clojure-cli.sh"
#
# Use the $GITHUB_WORKSPACE-absolute form, not a relative path: several jobs
# set a `defaults.run.working-directory` (e.g. `implementation`), under which
# a relative `./.github/scripts/...` would not resolve.
#
# # Failure boundary (rf2-xsfr)
#
# The first cut made three whole attempts with 10s/20s/30s backoff — about two
# minutes of cover — and, when they were exhausted, simply fell through to
# `clojure --version`. Both halves failed on 2026-08-13, four times in one
# session (#8007, #8009, #8017, #8019):
#
#   * THE ENVELOPE WAS TOO NARROW. github.com served HTTP 503 in storms lasting
#     LONGER than the two minutes covered. #8017 logged eighteen 503s — three
#     whole rounds of curl's own `--retry 5`, i.e. every request the script is
#     willing to make — across 20:20:32 to 20:22:20, and lost.
#
#   * THE FALL-THROUGH MASKED THE CAUSE. `clojure --version` on a runner where
#     nothing was installed dies `clojure: command not found`, exit 127. That
#     stops the caller proceeding on a half-installed toolchain, which was the
#     point, but it reports a red step that is INDISTINGUISHABLE from the gate
#     the job exists to run having failed. Every instance cost a diagnosis
#     cycle to establish that the diff was never even compiled, and a red gate
#     that is usually infrastructure is how a merge criterion gets eroded.
#
# So, two changes:
#
#   * SIX attempts with 20s/40s/60s/80s/100s backoff plus a jitter term — about
#     seven minutes of cover, three times the longest storm observed. The
#     jitter matters because ~59 jobs install this CLI concurrently: without
#     it they retry in lockstep and arrive as one herd on every round. On the
#     healthy path (the first attempt succeeds) none of this costs anything.
#
#   * On exhaustion the step FAILS EXPLICITLY, with a `::error` annotation
#     naming the cause, rather than falling through to a misleading exit 127.
#     The annotation surfaces on the PR's checks page, so "the gate failed" and
#     "the gate never ran" are now distinguishable WITHOUT opening the raw log
#     — which was the manual step this whole change exists to delete. This is
#     the idiom `resolve-clojure-deps.sh` already uses one layer further down
#     the toolchain (rf2-vm036); the exit status stays 1 for the same reason it
#     does there — the annotation is the machine-readable carrier, and a novel
#     exit code would be a second convention with no consumer.
#
# Unlike that script this one can classify without hedging: the URL below is a
# fixed constant, so no diff can change what is fetched or from where. The one
# exception — a PR that edits THIS FILE — is named in the annotation.
#
# `clojure --version` remains the last line and the boundary for the surviving
# case: an install that reported success but did not produce a working CLI.
#
set -euo pipefail

attempts=6

attempt=0
while [ "$attempt" -lt "$attempts" ]; do
  attempt=$((attempt + 1))
  curl -fsSL --retry 5 --retry-all-errors --retry-delay 3 -o /tmp/linux-install.sh \
    https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh \
    && sudo bash /tmp/linux-install.sh && break
  if [ "$attempt" -ge "$attempts" ]; then
    echo "::error title=Clojure CLI install failed — CI infrastructure, not this diff::Downloading and running the official Clojure CLI installer failed ${attempts} times over roughly seven minutes. NO GATE RAN IN THIS JOB: this step provisions the toolchain, so the step that would have tested this change never started, and this red says nothing whatever about the diff. The usual cause is a github.com 5xx storm on the release download (four PRs on 2026-08-13, rf2-xsfr); the URL fetched is a fixed constant in .github/scripts/install-clojure-cli.sh, so no change can affect it UNLESS this PR edits that file, in which case read it first. Otherwise re-run the job."
    exit 1
  fi
  delay=$((attempt * 20 + RANDOM % 10))
  echo "Clojure CLI install attempt ${attempt}/${attempts} failed; retrying in ${delay}s"
  sleep "$delay"
done
clojure --version
