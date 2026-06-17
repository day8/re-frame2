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
#   1. python scripts/check_readme_links.py     — README anchor + target validator
#   2. python scripts/check_doc_slugs.py        — docs corpus anchor validator
#   3. python scripts/check_ep_status_sync.py   — docs/EP index status-sync guard
#   4. mkdocs build --strict (when mkdocs on PATH; soft-skip on Windows when not)

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

# Foundation-order guard (rf2-708nm): the re-frame2-implementor skill must keep
# Spec 015 (Data Classification — v1-required) inside the core-complete gate.
# Catches the drift where an entry point reads "001 -> ... -> 009 -> optional"
# (gate BEFORE 015), which would let a fresh session declare v1-core-complete
# without the required privacy/large-payload elision surface.  Self-test first
# (proves the guard fires on the drift shapes), then the live scan.
run "implementor order-guard self-test" "python scripts/check_skill_implementor_order.py --self-test" \
  python "$repo_root/scripts/check_skill_implementor_order.py" --self-test

run "implementor foundation-order" "python scripts/check_skill_implementor_order.py --verbose --ci" \
  python "$repo_root/scripts/check_skill_implementor_order.py" --verbose --ci

# Eval-docs drift guard (rf2-r2xswa; generalised rf2-xw7ra9): each packaged
# skill's eval harness README carries a hand-maintained coverage table, total
# count, and per-axis breakdowns that silently fell behind evals.json (the
# re-frame2 harness: a 7th eval while the README still said "Six evals …"; the
# re-frame2-improver harness: 26/10 while prose still said "9 behavioural").
# The gate is multi-target — it scans the re-frame2 AND re-frame2-improver
# harnesses.  Self-test first (proves it fires on count/name/tally drift across
# both README shapes), then the live scan asserts each README agrees with its
# JSON.
run "eval-docs guard self-test" "python scripts/check_skill_eval_docs.py --self-test" \
  python "$repo_root/scripts/check_skill_eval_docs.py" --self-test

run "skill eval-docs match evals.json" "python scripts/check_skill_eval_docs.py --verbose --ci" \
  python "$repo_root/scripts/check_skill_eval_docs.py" --verbose --ci

# README inventory ratchet (rf2-198k3): layout-map<->disk bijection +
# 'N <noun>' count-numeral claims.  Runs unconditionally (not gated on a
# markdown diff) because the drift it catches can be triggered by a *dir*
# add/remove that never touches an .md file.  Fast + pure-Python.
run "README inventory ratchet" "python scripts/check_readme_inventories.py" \
  python "$repo_root/scripts/check_readme_inventories.py"

# EP-0007 §Enforcement retired-spelling gate (rf2-ziak6w): a retired spelling
# reappearing in framework source is a CI failure, not a doc note.  Catches
# the bare `:frame` event-context coeffect read (retired by rf2-1m6rf1 for
# `:rf.frame/id`) and the `:url` / `:to` redirect-target key on an SSR
# redirect fx (retired by rf2-vngir for `:location`).  Scoped precisely to
# the retired SHAPES so it never fires on the sanctioned public `:frame` opt,
# the trace `:frame` tag, the fx-handler ctx `:frame`, or client-navigation
# `:url`.  Runs unconditionally (the surface is implementation/ source, not
# markdown).  Self-test first (proves the gate fires on each retired shape and
# stays green on every sanctioned counterpart), then the live scan asserts
# framework source is clean.  See scripts/check_retired_spellings.py.
run "retired-spelling gate self-test" "python scripts/check_retired_spellings.py --self-test" \
  python "$repo_root/scripts/check_retired_spellings.py" --self-test

run "retired-spelling gate (EP-0007)" "python scripts/check_retired_spellings.py --verbose" \
  python "$repo_root/scripts/check_retired_spellings.py" --verbose

# Thrown-error human-message corpus gate (rf2-6bb3pg, Spec 009 §The thrown-error
# shape / rf2-vvixub): a framework `(ex-info …)` whose MESSAGE is a bare
# `:rf.*` discriminator keyword is the keyword-only shape rf2-vvixub abolished —
# a CI failure, not a doc note. Replaces the curated allow-list conformance
# test (thrown_error_message_conformance_cljs_test) with a CORPUS sweep so a
# new/never-converted keyword-only throw-site cannot ride back in invisibly.
# Self-test first (proves it FIRES on each bare-keyword shape + stays GREEN on
# the conformant counterparts), then the live scan asserts framework source is
# clean.  See scripts/check_thrown_error_messages.py.
run "thrown-error message gate self-test" "python scripts/check_thrown_error_messages.py --self-test" \
  python "$repo_root/scripts/check_thrown_error_messages.py" --self-test

run "thrown-error message gate (rf2-vvixub)" "python scripts/check_thrown_error_messages.py --verbose" \
  python "$repo_root/scripts/check_thrown_error_messages.py" --verbose

# EP-0010 §Validation/Conformance ambient-durable-read gate (rf2-f2t151): a
# direct ambient host read (clock / RNG / browser fact) written into a DURABLE
# frame-state field inside a durable-write namespace (resource reducers,
# work-ledger writers, reply handlers, mutation handlers, restore/hydration
# installers, machine snapshot writers) is a CI failure — durable state must be
# a function of prior frame-state plus explicit causal tokens (EP-0010).  Scoped
# precisely to the violating SHAPE (a durable field key whose value is an
# ambient read) so it never fires on the sanctioned sites: trace/perf code,
# effect interpreters before they dispatch a reply token, timer scheduling,
# host-transient side tables, diagnostics, and effect-side crypto.  Self-test
# first (proves it FLAGS each planted ambient durable read + PASSES every
# sanctioned counterpart), then the live scan asserts the durable-write
# namespaces are clean.  See scripts/check_ambient_durable_reads.py.
run "ambient-durable-read gate self-test" "python scripts/check_ambient_durable_reads.py --self-test" \
  python "$repo_root/scripts/check_ambient_durable_reads.py" --self-test

run "ambient-durable-read gate (EP-0010)" "python scripts/check_ambient_durable_reads.py --verbose" \
  python "$repo_root/scripts/check_ambient_durable_reads.py" --verbose

run "core JVM" "cd implementation/core && clojure -M:test" \
  bash -lc "cd '$repo_root/implementation/core' && clojure -M:test"

run "implementation JS harness helpers" "cd implementation && npm run test:script-policy && npm run test:script-helpers" \
  bash -lc "cd '$repo_root/implementation' && npm run test:script-policy && npm run test:script-helpers"

run "CLJS node integration" "cd implementation && npm run test:cljs" \
  bash -lc "cd '$repo_root/implementation' && npm run test:cljs"

# Per-namespace test-isolation gate (rf2-32siq3.44).  The consolidated
# node-test bundle shares ONE runtime, so a test ns whose fixture forgets to
# install its own substrate adapter is masked: it passes whenever a sibling
# left an adapter installed (suite ORDERING hides a self-incomplete fixture).
# This gate re-runs the curated EP-0023 image/frame runtime-construction
# namespaces EACH ALONE; a fixture leaning on bundle pollution goes red
# standalone.  Self-test first (proves the red/green classification), then the
# live run (reuses the bundle test:cljs just compiled).
run "per-ns isolation self-test" "cd implementation && node scripts/check-per-ns-isolation.cjs --self-test" \
  bash -lc "cd '$repo_root/implementation' && node scripts/check-per-ns-isolation.cjs --self-test"

run "per-ns test isolation" "cd implementation && node scripts/check-per-ns-isolation.cjs" \
  bash -lc "cd '$repo_root/implementation' && node scripts/check-per-ns-isolation.cjs"

# ---- conditional markdown gates ----
if [ "$markdown_changed" = "true" ]; then
  printf '\n--- markdown changes detected → running link/anchor gates ---\n'

  run "README link/anchor validator" "python scripts/check_readme_links.py --ci" \
    python "$repo_root/scripts/check_readme_links.py" --ci

  run "docs corpus anchor validator" "python scripts/check_doc_slugs.py" \
    python "$repo_root/scripts/check_doc_slugs.py"

  # EP index status-sync guard (rf2-8cw3m7): docs/EP/README.md restates each
  # EP's Status: line in its index table; the two drift by hand (EP-0001 sat at
  # `accepted` while the index still said `proposal`).  Self-test first (proves
  # the guard fires on mismatch / missing-row / orphan-row), then the live scan.
  run "EP status-sync self-test" "python scripts/check_ep_status_sync.py --self-test" \
    python "$repo_root/scripts/check_ep_status_sync.py" --self-test

  run "EP status-sync" "python scripts/check_ep_status_sync.py" \
    python "$repo_root/scripts/check_ep_status_sync.py"

  # Runtime-subsystem grading drift guard (rf2-ba5acq, EP-0006): every reserved
  # `:rf.runtime/*` key (spec/Conventions.md §Reserved runtime-db keys) MUST
  # have a complete five-clause grading subsection in spec/Runtime-Subsystems.md
  # §Grading table.  The two surfaces drift by hand (PR #3817 had to hand-add
  # the `:rf.runtime/mutations` row after it was reserved without a grading
  # subsection).  Self-test first (proves the guard fires on missing-row /
  # extra-row / missing-clause / ungraded-clause / clause-order), then the live
  # scan.  See scripts/check_runtime_subsystem_grading.py.
  run "runtime-subsystem grading self-test" "python scripts/check_runtime_subsystem_grading.py --self-test" \
    python "$repo_root/scripts/check_runtime_subsystem_grading.py" --self-test

  run "runtime-subsystem grading drift" "python scripts/check_runtime_subsystem_grading.py" \
    python "$repo_root/scripts/check_runtime_subsystem_grading.py"

  run_soft "mkdocs --strict build" "mkdocs build --strict" mkdocs \
    bash -lc "cd '$repo_root' && mkdocs build --strict"
else
  printf '\n--- no markdown changes → skipping link/anchor gates (override with --with-docs) ---\n'
fi

printf 'PASS fast PR spine\n'
