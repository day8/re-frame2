#!/usr/bin/env bash
#
# Bounded-retry Clojure dependency RESOLUTION for GitHub Actions (rf2-vm036).
#
# # What went wrong
#
# Under concurrent-PR load Maven Central starts returning `403 Forbidden` to
# the runner — throttling, not a broken coordinate: the same job passes on a
# plain re-run minutes later. Observed twice on 2026-07-26/27 on two different
# artefacts (`org.jsoup:jsoup:1.15.2` on the story lane, PR #7117;
# `com.cognitect:transit-cljs:0.8.280` on wire-vocab, PR #7123, with an
# explicit "status code: 403, reason phrase: Forbidden" from
# https://repo1.maven.org/maven2/). tools.deps surfaces it as
# `Error building classpath` / `ArtifactDescriptorException` /
# `ArtifactTransportException`, and does not retry.
#
# Dependency caching was NOT the missing piece — every MCP conformance lane
# already restores `~/.m2/repository` + `~/.gitlibs` + `~/.deps.clj`. A cache
# MISS (a `deps.edn` touched by the PR, a cold key) is exactly when the lane
# has to go to Central, which is exactly when this fires.
#
# # Why the symptom was worth money
#
# On the story lane the classpath is built INSIDE the server the Node
# conformance harness spawns (`clojure -M -m re-frame.story-mcp.server`). A 403
# there kills the server before it speaks, so the presenting symptom is the
# CLIENT's `FAIL: MCP error -32000: Connection closed` — which reads like a
# code fault in the diff and cost real diagnosis time twice.
#
# So this script is called as its own step, BEFORE the harness. Two effects,
# and the first matters more than the second:
#
#   1. the resolve now happens in a step whose NAME says it is dependency
#      resolution, so an infrastructure fault can no longer masquerade as a
#      protocol error — and on success `~/.m2` is warm, so the spawned server
#      resolves from disk and cannot fail this way at all;
#
#   2. three attempts with linear backoff give a short throttle window a
#      chance to clear inside the run instead of costing a re-run.
#
# # What it deliberately does NOT retry
#
# Only `clojure -P` — dependency preparation. Never a test or server
# invocation: re-running a suite until it passes is how a flaky gate becomes a
# useless one. If resolution succeeds and the suite then fails, that failure
# stands, first time, unretried.
#
# This mirrors `install-clojure-cli.sh`, the repo's existing single owner of
# "transient network failure in CI setup reds a job before any test runs"
# (rf2-9sgj8 / rf2-e7ja9) — same class of fault, one layer further down the
# toolchain, same shape of answer, and one file rather than a copy per lane.
#
# # Calling it
#
# Arguments are forwarded verbatim to `clojure -P`, so pass whatever exec opts
# the real invocation uses (nothing for a bare `-M -m ...` spawn, `-M:test` for
# an aliased suite). Use the $GITHUB_WORKSPACE-absolute form — callers set a
# `working-directory`, under which a relative path would not resolve:
#
#     - name: Pre-resolve the <x> classpath
#       working-directory: tools/<x>
#       run: "$GITHUB_WORKSPACE/.github/scripts/resolve-clojure-deps.sh -M:test"
#
set -euo pipefail

attempts=3

for attempt in 1 2 3; do
  if clojure -P "$@"; then
    exit 0
  fi
  if [ "$attempt" -lt "$attempts" ]; then
    delay=$((attempt * 15))
    echo "Clojure dependency resolution attempt ${attempt}/${attempts} failed in $(pwd); retrying in ${delay}s"
    sleep "$delay"
  fi
done

echo "::error title=Dependency resolution failed — INFRASTRUCTURE, not this diff::\`clojure -P${*:+ $*}\` failed ${attempts} times in $(pwd). This step only downloads dependencies; it runs no test and no server, so the cause is the dependency infrastructure, not the code under review. Maven Central returns 403 Forbidden to CI runners under concurrent load (rf2-vm036) and tools.deps reports it as 'Error building classpath'. Re-run the job."
exit 1
