#!/usr/bin/env bash
# inject-runtime.sh — (re-)inject scripts/runtime.cljs into the running
# browser runtime. Returns the health map from
# `re-frame-pair2.runtime/health`.
#
# DEPRECATED: prefer the MCP tool `inject-runtime` from
# `@day8/re-frame-pair2-mcp` (tools/pair2-mcp/). Kept for back-compat.
#
# Usage:
#   scripts/inject-runtime.sh [--build=:app]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}
exec bb "$HERE/ops.clj" inject "$@"
