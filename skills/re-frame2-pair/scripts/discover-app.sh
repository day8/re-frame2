#!/usr/bin/env bash
# discover-app.sh — locate shadow-cljs nREPL, connect, and verify the
# consumer build has the re-frame2-pair.runtime preload loaded. Prints a
# structured edn result. (The runtime ships via shadow-cljs :devtools
# :preloads; there is no per-session inject step — see SKILL.md §Setup.)
#
# DEPRECATED: prefer the MCP tool `discover-app` from
# `@day8/re-frame2-pair-mcp` (tools/re-frame2-pair-mcp/). Kept for back-compat.
#
# Usage:
#   scripts/discover-app.sh [--build=:app]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}
exec bb "$HERE/ops.clj" discover "$@"
