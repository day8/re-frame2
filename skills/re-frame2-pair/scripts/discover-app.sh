#!/usr/bin/env bash
# discover-app.sh — locate shadow-cljs nREPL, connect, and verify the
# consumer build has the re-frame2-pair.runtime preload loaded. Prints a
# structured edn result. (The runtime ships via shadow-cljs :devtools
# :preloads; there is no per-session inject step — see SKILL.md §Setup.)
#
# RETIRED from the skill's tool surface: the skill drives `discover-app`
# via the MCP server (`@day8/re-frame2-pair-mcp`, tools/re-frame2-pair-mcp/),
# the only skill-facing transport. This shim is on disk only for the
# project's own test harness (tests/shim/, tests/e2e/) and ad-hoc shell
# use outside the skill — NOT a skill-facing fallback transport.
#
# Usage:
#   scripts/discover-app.sh [--build=app]
# (--build accepts both `app` and `:app`; default build is `app` or
#  $SHADOW_CLJS_BUILD_ID.)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}
exec bb "$HERE/ops.clj" discover "$@"
