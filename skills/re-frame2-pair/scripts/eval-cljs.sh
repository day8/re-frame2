#!/usr/bin/env bash
# eval-cljs.sh — evaluate a ClojureScript form in the connected browser
# runtime via shadow-cljs's cljs-eval. Prints edn on stdout.
#
# RETIRED from the skill's tool surface: the skill drives `eval-cljs`
# via the MCP server (`@day8/re-frame2-pair-mcp`, tools/re-frame2-pair-mcp/),
# the only skill-facing transport. The MCP server holds one persistent
# nREPL connection per session and drops per-op latency from ~700ms to
# ~5-50ms. This shim is on disk only for the project's own test harness
# (tests/shim/, tests/e2e/) and ad-hoc shell use outside the skill — NOT
# a skill-facing fallback transport.
#
# Usage:
#   scripts/eval-cljs.sh '(+ 1 2)' [--build=app]
#   scripts/eval-cljs.sh '(re-frame2-pair.runtime/snapshot)'
# (--build accepts both `app` and `:app`; default build is `app` or
#  $SHADOW_CLJS_BUILD_ID.)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}
exec bb "$HERE/ops.clj" eval "$@"
