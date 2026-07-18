#!/usr/bin/env bash
#
# Resilient Clojure CLI install for GitHub Actions (rf2-9sgj8, extracted rf2-e7ja9).
#
# # Why this is not `DeLaGuardo/setup-clojure`
#
# setup-clojure's internal linux-install.sh curl has no retry, so a transient
# curl-35 / socket-hang-up reds a JVM job in *setup*, before any test runs.
# Under load that flaked repeatedly across the matrix. This runs the official
# linux-install.sh directly, wrapping it in the repo's clj-kondo curl-retry
# idiom (lint.yml) plus a 3x backoff loop over the whole download+install.
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
# # Failure boundary
#
# Three whole download+install attempts with bounded linear backoff. If all
# three fail the loop falls through to `clojure --version`, which fails the
# step — the caller never proceeds with a half-installed toolchain.
#
set -euo pipefail

for attempt in 1 2 3; do
  curl -fsSL --retry 5 --retry-all-errors --retry-delay 3 -o /tmp/linux-install.sh \
    https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh \
    && sudo bash /tmp/linux-install.sh && break
  echo "Clojure CLI install attempt ${attempt}/3 failed; retrying in $((attempt * 10))s"
  sleep "$((attempt * 10))"
done
clojure --version
