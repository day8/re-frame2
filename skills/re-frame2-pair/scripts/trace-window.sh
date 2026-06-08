#!/usr/bin/env bash
# trace-window.sh — return epochs added to the operating frame's
# epoch-history in the last N ms.
#
# RETIRED from the skill's tool surface: the skill drives `trace-window`
# via the MCP server (`@day8/re-frame2-pair-mcp`, tools/re-frame2-pair-mcp/),
# the only skill-facing transport. This shim is on disk only for the
# project's own test harness (tests/shim/, tests/e2e/) and ad-hoc shell
# use outside the skill — NOT a skill-facing fallback transport.
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
