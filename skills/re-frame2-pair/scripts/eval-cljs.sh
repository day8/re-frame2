#!/usr/bin/env bash
# eval-cljs.sh — evaluate a ClojureScript form in the connected browser
# runtime via shadow-cljs's cljs-eval. Prints edn on stdout.
#
# DEPRECATED: prefer the MCP tool `eval-cljs` from
# `@day8/re-frame2-pair-mcp` (tools/re-frame2-pair-mcp/). The MCP server holds
# one persistent nREPL connection per session and drops per-op latency
# from ~700ms to ~5-50ms. This bash shim is kept for back-compat with
# sessions where the MCP server isn't configured.
#
# Usage:
#   scripts/eval-cljs.sh '(+ 1 2)' [--build=:app]
#   scripts/eval-cljs.sh '(re-frame2-pair.runtime/snapshot)'
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}
exec bb "$HERE/ops.clj" eval "$@"
