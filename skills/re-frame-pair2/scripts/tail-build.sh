#!/usr/bin/env bash
# tail-build.sh — coordinate with shadow-cljs hot-reload after a source
# edit.
#
# DEPRECATED: prefer the MCP tool `tail-build` from
# `@day8/re-frame-pair2-mcp` (tools/pair2-mcp/). Kept for back-compat.
#
# --wait-ms N       how long to wait for the reload to land (default 5000)
# --probe '<form>'  a CLJS form whose return value changes after the edit is
#                   live. When the form's return value flips, reload has
#                   landed. Without --probe, falls back to a fixed 300ms
#                   timer delay and returns :soft? true.
#
# Recommended probes for re-frame2:
#   - reg-* edits:        --probe '(re-frame-pair2.runtime/registrar-handler-ref :event :foo)'
#   - reg-machine edits:  --probe '(re-frame-pair2.runtime/registrar-handler-ref :event :auth)'
#   - view edits:         --probe '<form that derefs the edited code>'
#
# A successful probe-flip also coincides with a `:rf.registry/handler-replaced`
# trace event arriving in the buffer; that event's presence is an
# alternative confirmation.
#
# Note on the name: despite being called tail-build, this does NOT tail
# shadow-cljs's server stdout. Both the server-side "Build complete" event
# and the browser-side reload landing are driven through the probe-based
# pattern. See docs/initial-spec.md §4.5 for rationale.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}
exec bb "$HERE/ops.clj" tail-build "$@"
