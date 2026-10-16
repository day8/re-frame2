#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# First line names the tree this run resolved.  A relative
# invocation resolves `${BASH_SOURCE[0]}` against the shell's actual cwd, which
# a backgrounded `cd <worktree> && sh scripts/…` does not reliably set — so a
# gate can grade another worktree's diff and look entirely normal doing it.
# Invoke backgrounded gates by ABSOLUTE path.
printf 'gate root: %s\n' "$repo_root"

# The roster is the implementation/ rows of scripts/jvm-lanes.txt, in row
# order.  That file is the one list of JVM lanes, and says why each artefact
# has one; CI runs the same rows as legs of test.yml's `jvm` job.
artefacts="$(bash "$repo_root/scripts/jvm-lanes.sh" dirs implementation)"

while IFS= read -r artefact; do
  printf '==> JVM %s\n' "$artefact"
  if ! (cd "$repo_root/$artefact" && clojure -M:test); then
    printf '\nFAIL JVM %s\nrepro: cd %s && clojure -M:test\n' "$artefact" "$artefact" >&2
    exit 1
  fi
done <<< "$artefacts"

printf 'PASS implementation JVM artefacts\n'
