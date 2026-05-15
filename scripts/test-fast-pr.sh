#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

run() {
  local surface="$1"
  local repro="$2"
  shift 2
  printf '==> %s\n' "$surface"
  if ! "$@"; then
    printf '\nFAIL %s\nrepro: %s\n' "$surface" "$repro" >&2
    return 1
  fi
}

run "lockstep version drift" "./.github/scripts/verify-version-lockstep.sh" \
  bash -lc "tmp='$repo_root/.github/scripts/.verify-version-lockstep.tmp.'\$\$; trap 'rm -f \"\$tmp\"' EXIT; tr -d '\r' < '$repo_root/.github/scripts/verify-version-lockstep.sh' > \"\$tmp\"; bash \"\$tmp\""

run "skill/MCP allowed-tools drift" "python scripts/check_skill_mcp_drift.py --verbose --ci" \
  python "$repo_root/scripts/check_skill_mcp_drift.py" --verbose --ci

run "core JVM" "cd implementation/core && clojure -M:test" \
  bash -lc "cd '$repo_root/implementation/core' && clojure -M:test"

run "implementation JS harness helpers" "cd implementation && npm run test:script-policy && npm run test:script-helpers" \
  bash -lc "cd '$repo_root/implementation' && npm run test:script-policy && npm run test:script-helpers"

run "CLJS node integration" "cd implementation && npm run test:cljs" \
  bash -lc "cd '$repo_root/implementation' && npm run test:cljs"

printf 'PASS fast PR spine\n'
