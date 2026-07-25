#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

tools=(
  tools/xray
  tools/machines-viz
  tools/story
  tools/story-mcp
  tools/mcp-base
  # rf2-4hc9p — found by the test-lane bijection gate
  # (scripts/check_test_lane_bijection.py) as an ORPHAN: the dev-only
  # open-in-editor server ships a JVM suite with a fully wired `:test`
  # alias, but no lane ran it. `open_in_editor_server_test.clj` is `.clj`,
  # so the `node-test-testbed-support` CLJS build that carries this tree on
  # its source paths cannot load it, and the artefact was on neither JVM
  # roster — its tests ran in no lane at all, in CI or locally. Listed here
  # (the whole suite is seconds) so the lane exists; the matching CI job is
  # tracked separately (rf2-as6bg).
  tools/testbed-support
  tools/mcp-conformance/wire-vocab
)

for tool in "${tools[@]}"; do
  printf '==> JVM %s\n' "$tool"
  if ! (cd "$repo_root/$tool" && clojure -M:test); then
    printf '\nFAIL JVM %s\nrepro: cd %s && clojure -M:test\n' "$tool" "$tool" >&2
    exit 1
  fi
done

printf 'PASS tools JVM artefacts\n'
