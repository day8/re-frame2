#!/usr/bin/env sh
# SCAFFOLDING for rf2-xu6rx — deleted before the PR.
#
# rf2-lnecd carried its probe builds as three extra entries in
# implementation/shadow-cljs.edn. That file is fenced for this bead (a live
# testbed worker owns it), so the same two bundles are produced with
# `--config-merge` over builds that already have the exact compiler settings
# the measurement needs: :advanced, :infer-externs :auto, goog.DEBUG false.
# Only :output-dir and the module entry move.
#
# Two DIFFERENT base build ids, deliberately: shadow keys its disk cache on
# the build id, and the profile bundle differs by :pseudo-names. Sharing one
# id would invalidate the cache on every alternation of the A/B loop.
#
#   sh scripts/studio-build.sh probe   -> out/studio-probe        (measured)
#   sh scripts/studio-build.sh named   -> out/studio-probe-named  (profiled)

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
