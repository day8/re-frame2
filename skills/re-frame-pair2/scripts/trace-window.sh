#!/usr/bin/env bash
# trace-window.sh — return epochs added to the operating frame's
# epoch-history in the last N ms.
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
