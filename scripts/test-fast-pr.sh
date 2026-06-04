#!/usr/bin/env bash
set -euo pipefail

# Fast pre-checkin spine for the canonical agent quality gate.
#
# Mirrors CI's PR-gate matrix so workers see local failures BEFORE pushing.
# When the working tree's diff vs origin/main (or unstaged changes) touches
# any .md file, the spine additionally runs the markdown link/anchor
# validators that CI runs.  Workers were repeatedly pushing green-locally
# branches that failed on CI link/anchor breaks (#2232 + #2233 in one
# hour on 2026-05-27) — this gate closes that gap (rf2-lwweq).
#
# Usage:
#   scripts/test-fast-pr.sh              auto-detect markdown diff
#   scripts/test-fast-pr.sh --with-docs  force-run the markdown gates
#   scripts/test-fast-pr.sh --no-docs    skip markdown gates even if .md changed
#
# Markdown-gate components:
#   1. python scripts/check_readme_links.py — README anchor + target validator
#   2. python scripts/check_doc_slugs.py    — docs corpus anchor validator
#   3. mkdocs build --strict (when mkdocs on PATH; soft-skip on Windows when not)

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

with_docs="auto"
for arg in "$@"; do
  case "$arg" in
    --with-docs) with_docs="force" ;;
    --no-docs)   with_docs="skip" ;;
    -h|--help)
      cat <<EOF
fast PR pre-checkin spine

Usage:
  $(basename "$0") [--with-docs|--no-docs]

Flags:
  --with-docs   always run the markdown link/anchor + mkdocs --strict gates
  --no-docs     skip markdown gates even when the diff touches .md
  (default)     auto-detect: run markdown gates iff diff vs origin/main
                OR unstaged changes touch any .md file
EOF
      exit 0
      ;;
    *)
      printf 'unknown flag: %s\n' "$arg" >&2
      exit 2
      ;;
  esac
done

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

# Soft-run: log + continue when the binary is unavailable.  Used for mkdocs
# on Windows machines where mkdocs is not always installed in PATH but the
# CI Linux runners always have it (mkdocs.yml + requirements.txt pinned).
run_soft() {
  local surface="$1"
  local repro="$2"
  local probe="$3"
  shift 3
  printf '==> %s\n' "$surface"
  if ! command -v "$probe" >/dev/null 2>&1; then
    printf '    SKIP %s: %s not on PATH (CI will run this; local soft-skip OK)\n' \
      "$surface" "$probe"
    return 0
  fi
  if ! "$@"; then
    printf '\nFAIL %s\nrepro: %s\n' "$surface" "$repro" >&2
    return 1
  fi
}

# ---- markdown-change detection ----
# Two signals:
#   1. committed diff vs origin/main touches *.md
#   2. unstaged or staged working-tree changes touch *.md
# Either signal → markdown gates run (unless --no-docs).
markdown_changed=false
if [ "$with_docs" = "force" ]; then
  markdown_changed=true
elif [ "$with_docs" = "skip" ]; then
  markdown_changed=false
else
  if git -C "$repo_root" rev-parse --verify origin/main >/dev/null 2>&1; then
    if git -C "$repo_root" diff --name-only origin/main HEAD 2>/dev/null | grep -qE '\.md$'; then
      markdown_changed=true
    fi
  fi
  if git -C "$repo_root" status --porcelain 2>/dev/null | grep -qE '\.md$'; then
    markdown_changed=true
  fi
fi

# ---- existing gates ----
run "lockstep version drift" "./.github/scripts/verify-version-lockstep.sh" \
  bash -lc "tmp='$repo_root/.github/scripts/.verify-version-lockstep.tmp.'\$\$; trap 'rm -f \"\$tmp\"' EXIT; tr -d '\r' < '$repo_root/.github/scripts/verify-version-lockstep.sh' > \"\$tmp\"; bash \"\$tmp\""

run "skill/MCP allowed-tools drift" "python scripts/check_skill_mcp_drift.py --verbose --ci" \
  python "$repo_root/scripts/check_skill_mcp_drift.py" --verbose --ci

# Cite-integrity guard (rf2-1nb8k): every M-id the re-frame-migration skill
# cites must name a consistent rule in MIGRATION.md.  Catches the id-collision /
# phantom-cite class (skill cited M-66/M-67 for rules MIGRATION.md assigned to
# History-states / Xray-static-mode).  Runs unconditionally — fast pure-Python,
# and the drift can be introduced by a MIGRATION.md *heading* edit that the
# markdown-diff gate below also sees but doesn't semantically check.
run "skill migration cite-integrity" "python scripts/check_skill_migration_cites.py --verbose --ci" \
  python "$repo_root/scripts/check_skill_migration_cites.py" --verbose --ci

# README inventory ratchet (rf2-198k3): layout-map<->disk bijection +
# 'N <noun>' count-numeral claims.  Runs unconditionally (not gated on a
# markdown diff) because the drift it catches can be triggered by a *dir*
# add/remove that never touches an .md file.  Fast + pure-Python.
run "README inventory ratchet" "python scripts/check_readme_inventories.py" \
  python "$repo_root/scripts/check_readme_inventories.py"

run "core JVM" "cd implementation/core && clojure -M:test" \
  bash -lc "cd '$repo_root/implementation/core' && clojure -M:test"

run "implementation JS harness helpers" "cd implementation && npm run test:script-policy && npm run test:script-helpers" \
  bash -lc "cd '$repo_root/implementation' && npm run test:script-policy && npm run test:script-helpers"

run "CLJS node integration" "cd implementation && npm run test:cljs" \
  bash -lc "cd '$repo_root/implementation' && npm run test:cljs"

# ---- conditional markdown gates ----
if [ "$markdown_changed" = "true" ]; then
  printf '\n--- markdown changes detected → running link/anchor gates ---\n'

  run "README link/anchor validator" "python scripts/check_readme_links.py --ci" \
    python "$repo_root/scripts/check_readme_links.py" --ci

  run "docs corpus anchor validator" "python scripts/check_doc_slugs.py" \
    python "$repo_root/scripts/check_doc_slugs.py"

  run_soft "mkdocs --strict build" "mkdocs build --strict" mkdocs \
    bash -lc "cd '$repo_root' && mkdocs build --strict"
else
  printf '\n--- no markdown changes → skipping link/anchor gates (override with --with-docs) ---\n'
fi

printf 'PASS fast PR spine\n'
