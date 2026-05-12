#!/usr/bin/env bash
# trace-window.sh — return epochs added to the operating frame's
# epoch-history in the last N ms.
#
# DEPRECATED: prefer the MCP tool `trace-window` from
# `@day8/re-frame-pair2-mcp` (tools/pair2-mcp/). Kept for back-compat.
#
# Usage:
#   scripts/trace-window.sh 3000           # last 3 seconds of epochs
#   scripts/trace-window.sh 30000 --frame :stories
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}
exec bb "$HERE/ops.clj" trace-recent "$@"
