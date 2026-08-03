#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# First line names the tree this run resolved — see scripts/test-fast-pr.sh
# (rf2-g2mxd): a relative invocation resolves `${BASH_SOURCE[0]}` against the
# shell's actual cwd, so a backgrounded gate can silently run in, and grade,
# another worktree.  Invoke backgrounded gates by ABSOLUTE path.
printf 'gate root: %s\n' "$repo_root"

tools=(
  tools/xray
  # rf2-wq17m — 632 tests / 2537 assertions. 29 of its 31 suites are
  # `*_cljs_test.*` and double-run in the CLJS lanes, but
  # `engine_grammar_parity_test.cljc` (the engine<->viz grammar drift ratchet)
  # and `mermaid_public_smoke_test.cljc` match neither CLJS selector, so this
  # roster and the `jvm-tools-machines-viz` CI job are their only lanes.
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
  # (the whole suite is seconds) so the lane exists; rf2-wq17m added the
  # matching PR-time job, `jvm-tools-testbed-support`.
  tools/testbed-support
  tools/mcp-conformance/wire-vocab
  # rf2-as6bg — the other direction of the same disagreement. tools/template
  # has a CI job (`jvm-tools-template`, gated on `template_expensive`) but was
  # on no local roster, so a template-only change had no local JVM lane at all.
  # Its expensive slice — `emitted_test_run_test.clj`, which compiles and runs
  # the emitted app — is already gated behind RF2_TEMPLATE_RUN_EMITTED_TESTS=1
  # precisely so a local run without Node stays green and quick, so the suite
  # was built to be listed here; nobody listed it. Runs last because it is the
  # slowest of the set even with the emitted slice skipped.
  tools/template
)

for tool in "${tools[@]}"; do
  printf '==> JVM %s\n' "$tool"
  if ! (cd "$repo_root/$tool" && clojure -M:test); then
    printf '\nFAIL JVM %s\nrepro: cd %s && clojure -M:test\n' "$tool" "$tool" >&2
    exit 1
  fi
done

printf 'PASS tools JVM artefacts\n'
