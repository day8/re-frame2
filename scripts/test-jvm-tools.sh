#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# First line names the tree this run resolved: a relative invocation resolves
# `${BASH_SOURCE[0]}` against the shell's actual cwd, so a backgrounded gate
# can silently run in, and grade, another worktree.  Invoke backgrounded gates
# by ABSOLUTE path.
printf 'gate root: %s\n' "$repo_root"

# The roster is every row of scripts/jvm-lanes.txt outside implementation/ —
# the tools and the two codemods — in row order.  That file is the one list of
# JVM lanes, and says why each artefact has one.  tools/template's expensive
# slice stays behind RF2_TEMPLATE_RUN_EMITTED_TESTS=1, so a local run without
# Node stays green and quick.
tools="$(bash "$repo_root/scripts/jvm-lanes.sh" dirs tools)"

while IFS= read -r tool; do
  printf '==> JVM %s\n' "$tool"
  if ! (cd "$repo_root/$tool" && clojure -M:test); then
    printf '\nFAIL JVM %s\nrepro: cd %s && clojure -M:test\n' "$tool" "$tool" >&2
    exit 1
  fi
done <<< "$tools"

printf 'PASS tools JVM artefacts\n'
