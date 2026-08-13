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
# And, since the merged-PR audit of #7132, not every resolution failure either.
# The first cut retried EVERY nonzero exit and then annotated it categorically
# as "INFRASTRUCTURE, not this diff" — which is a lie for the failures a diff
# owns. Driven locally against the real resolver, a nonexistent coordinate and
# a malformed `deps.edn` each earned three attempts, 45s of sleeping, and an
# annotation telling the author to re-run a deterministic failure and to look
# away from their own change. That is worse than no annotation: it is a
# confident wrong answer, and this script exists precisely because a confident
# wrong answer about a dependency failure costs diagnosis time.
#
# So the output is CLASSIFIED, and the classification is deliberately narrow —
# a short, explicit list of transport signatures, matched or not matched:
#
#   MATCHED    the failure LOOKS like the network path to the repository.
#              Retry (a throttle window may clear), and if it survives all
#              attempts, quote the signature and say what it does and does not
#              establish.
#
#              It earns a retry; it does NOT establish ownership. The merged-PR
#              audit of #7221 demonstrated the gap hermetically: a stub emitting
#              `ArtifactTransportException: authentication failed for repository
#              configured by this change` alongside `status code: 403` matched,
#              retried three times, and was then declared "INFRASTRUCTURE, not
#              this diff" / "not of any coordinate" — of a fault the diff owned
#              outright. A repository the change added that needs credentials, a
#              misspelled repository host, and a genuinely dead endpoint all
#              emit these same strings. Nothing available at this layer separates
#              them from throttling, so the exhausted verdict says "a re-run may
#              help, and here is why" rather than "not your diff". Narrowing the
#              signature list cannot fix this — the ambiguity is in the strings
#              themselves, not in the list's width.
#
#   UNMATCHED  say so, and say only that. No retry — a bad coordinate resolves
#              exactly as badly the third time, so the 45s is pure waste — and
#              no claim about whose fault it is beyond the honest one: nothing
#              here looks like a transport fault, so read the resolver output.
#
# The bias is that an unrecognised transient fault degrades to "we do not know"
# rather than to "not your diff". Widening the list when a new signature is
# actually observed is the intended maintenance; guessing at signatures that
# have never been seen is not.
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

log="$(mktemp)"
trap 'rm -f "$log"' EXIT

# The transport signatures that earn a retry. They earn ONLY that: see the
# MATCHED note above for why matching one does not establish the cause.
# Every entry is a fault of the network path to the repository, never of a
# coordinate: rate-limiting and throttling (the observed rf2-vm036 fault),
# server-side errors, and connection-level breakage.
#
# `Error building classpath` and `Failed to read artifact descriptor` are
# NOT here, deliberately. tools.deps prints them for every resolution failure
# whatever the cause, so they discriminate nothing — matching on them is how
# the first cut came to call a bad coordinate infrastructure.
#
# Nor is `Could not find artifact`: that is Maven's 404, which means the
# coordinate is wrong, which means it belongs to the diff.
TRANSIENT_SIGNATURES='status code: (403|408|429|5[0-9][0-9])|Too Many Requests|Service Unavailable|Bad Gateway|Gateway Time-?out|ArtifactTransportException|MetadataTransportException|Connect(ion)? (reset|refused|timed out)|Read timed out|SocketTimeoutException|ConnectTimeoutException|UnknownHostException|NoRouteToHostException|Premature end of Content-Length|Remote host terminated the handshake|Broken pipe'

for attempt in 1 2 3; do
  # `tee` so the resolver's own output still streams into the CI log live —
  # the classification reads a copy, it does not replace what a human sees.
  if clojure -P "$@" 2>&1 | tee "$log"; then
    exit 0
  fi

  signature="$(grep -m1 -E -o "$TRANSIENT_SIGNATURES" "$log" || true)"

  if [ -z "$signature" ]; then
    echo "::error title=Dependency resolution failed — no transient transport signature::\`clojure -P${*:+ $*}\` failed in $(pwd), and nothing in its output matches a known transient transport fault (rate-limiting, a 5xx, a dropped connection). A re-run is therefore unlikely to help, and this step is NOT claiming the cause is infrastructure. Read the resolver output above: a resolution failure that is not a transport fault is usually a dependency this change owns — a coordinate that does not exist, a malformed deps.edn, or an alias that is not defined (rf2-vm036)."
    exit 1
  fi

  if [ "$attempt" -lt "$attempts" ]; then
    delay=$((attempt * 15))
    echo "Clojure dependency resolution attempt ${attempt}/${attempts} failed in $(pwd) with a transient transport signature ('${signature}'); retrying in ${delay}s"
    sleep "$delay"
  fi
done

echo "::error title=Dependency resolution failed — transport-level — cause not established::\`clojure -P${*:+ $*}\` failed ${attempts} times in $(pwd), every time with a transient transport signature ('${signature}'). That signature is EVIDENCE A RE-RUN MAY HELP — Maven Central returns 403 Forbidden to CI runners under concurrent load (rf2-vm036), and that clears on its own — but it is NOT proof the cause is external. The same signature is emitted by a repository this change added that requires authentication, a misspelled repository host, and an endpoint that is simply down; this step cannot tell those apart from throttling. So: re-run the job first, and if it fails the same way again, read the resolver output above and check any repository or coordinate this change touched. Note only that no test and no server ran in this step — the resolution never got that far."
exit 1
