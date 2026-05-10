#!/usr/bin/env bash
# watch-epochs.sh — pull-mode live watch of the operating frame's
# epoch-history.
#
# Emits one edn line per matching epoch, plus a final {:finished?} summary.
#
# Modes (pick one or combine; first to fire wins):
#   --window-ms N    Run for N ms with no count limit.
#   --count N        Run until N matches with no window timeout.
#   --window-ms N --count M
#                    Race: terminate when N ms elapse OR M matches emit.
#   (neither flag)   Defaults to a 30 s window.
#   --stream         Run until disconnect, idle (default 30s), or --hard-ms.
#   --stop           No-op for pull-mode; simply terminate the running shell.
#
# Predicates (any combination, AND-ed):
#   --event-id :foo
#   --event-id-prefix :cart/
#   --effects :http
#   --touches-path [:a :b]
#   --sub-ran :cart/total
#   --render 'my.ns/foo'
#   --origin :pair|:app|:ui|:timer|:http
#   --frame  :stories
#
# Stopping defaults: idle-ms 30000, hard-ms 300000.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}
exec bb "$HERE/ops.clj" watch "$@"
