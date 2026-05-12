#!/usr/bin/env bash
# dispatch.sh — fire a re-frame2 event in the connected app, tagged
# with :origin :pair (Spec 002 §Dispatch origin tagging).
#
# DEPRECATED: prefer the MCP tool `dispatch` from
# `@day8/re-frame-pair2-mcp` (tools/pair2-mcp/). Kept for back-compat.
#
# Default mode is queued dispatch (`rf/dispatch`).
# --sync  forces `rf/dispatch-sync` for deterministic before/after.
# --trace dispatches synchronously and returns the assembled
#         `:rf/epoch-record` for the cascade.
#
# Targeting:
#   --frame :foo                   target a specific frame
#   --fx-override :http=:stub-http per-call fx redirect (repeatable)
#
# Usage:
#   scripts/dispatch.sh '[:cart/apply-coupon "SPRING25"]'
#   scripts/dispatch.sh '[:cart/apply-coupon "SPRING25"]' --sync
#   scripts/dispatch.sh '[:cart/apply-coupon "SPRING25"]' --trace
#   scripts/dispatch.sh '[:foo]' --frame :stories
#   scripts/dispatch.sh '[:cart/checkout]' --fx-override :http=:stub-http
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}
exec bb "$HERE/ops.clj" dispatch "$@"
