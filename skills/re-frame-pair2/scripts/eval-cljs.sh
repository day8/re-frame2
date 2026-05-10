#!/usr/bin/env bash
# eval-cljs.sh — evaluate a ClojureScript form in the connected browser
# runtime via shadow-cljs's cljs-eval. Prints edn on stdout.
#
# Usage:
#   scripts/eval-cljs.sh '(+ 1 2)' [--build=:app]
#   scripts/eval-cljs.sh '(re-frame-pair2.runtime/snapshot)'
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}
exec bb "$HERE/ops.clj" eval "$@"
