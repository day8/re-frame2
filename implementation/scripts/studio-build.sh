#!/usr/bin/env sh
# SCAFFOLDING for rf2-lnecd's ELISION ABLATION — deleted before the PR.
#
# The first pass carried its probe build as an extra entry in
# implementation/shadow-cljs.edn. That file is HOT-ZONE and fenced for this
# run (a CI/test-lane worker owns it), so the same bundle is produced with
# `--config-merge` over a build that already has the exact compiler settings
# the measurement needs: :advanced, :infer-externs :auto, goog.DEBUG false.
# Only :output-dir and the module entry move.
#
#   sh scripts/studio-build.sh          -> out/studio-probe        (measured)
#   sh scripts/studio-build.sh named    -> out/studio-probe-named  (profiled)

set -e
cd "$(dirname "$0")/.."

case "${1:-probe}" in
  probe)
    exec npx shadow-cljs release freehand-release-reachability-control \
      --config-merge '{:output-dir "out/studio-probe" :modules {:main {:init-fn re-frame.freehand.studio.probe/-main}}}'
    ;;
  named)
    exec npx shadow-cljs release freehand-release-compiled \
      --config-merge '{:output-dir "out/studio-probe-named" :compiler-options {:pseudo-names true} :modules {:main {:init-fn re-frame.freehand.studio.probe/-main}}}'
    ;;
  *)
    echo "usage: studio-build.sh [probe|named]" >&2
    exit 2
    ;;
esac
