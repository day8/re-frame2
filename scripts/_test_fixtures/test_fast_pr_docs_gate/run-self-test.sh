#!/usr/bin/env bash
# Self-test for the changed-surface tiering in scripts/test-fast-pr.sh
# (rf2-r6x1t, extends rf2-lwweq).
#
# It drives the REAL spine in `--plan` mode against disposable git repos —
# NOT a replicated copy of the detection logic (the previous harness copied
# the markdown-diff block inline, which could silently drift from the spine).
# `--plan` classifies the change set and prints one machine-readable line —
#   PLAN docs=<bool> jvm=<bool> node=<bool>
# — then exits without running any gate, so the assertions are fast and
# deterministic.  `--repo-root DIR` points the change-set gathering at a
# disposable fixture repo while the real gate scripts stay put.
#
# Cases:
#   committed docs / staged code / unstaged docs / untracked docs  — the four
#     git states the change-set gathering must handle deterministically;
#   unknown surface                — conservative fallback runs the full runtime;
#   no changes                     — static checks only;
#   no origin/main base            — conservative fallback (indeterminate);
#   --all / RF2_FAST_PR_ALL / --with-docs / --no-docs — the overrides;
#   gate-has-teeth (E/F)           — the doc validators exit non-zero on the
#     bundled broken fixtures;
#   motivating misses (#2232/#2233) — the pymdownx slug shapes that first
#     motivated the doc gate still trip check_doc_slugs.py.
#
# Run from any cwd:
#   bash scripts/_test_fixtures/test_fast_pr_docs_gate/run-self-test.sh
#
# Exit code:
#   0  all assertions hold
#   1  at least one assertion failed
#   2  setup error

set -u

fixture_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$fixture_dir/../../.." && pwd)"
spine="$repo_root/scripts/test-fast-pr.sh"

if [ ! -f "$spine" ]; then
  printf 'setup error: spine script not found at %s\n' "$spine" >&2
  exit 2
fi

fail_count=0
pass_count=0

assert() {
  local label="$1"
  local expected="$2"
  local actual="$3"
  if [ "$expected" = "$actual" ]; then
    printf '  PASS %s\n' "$label"
    pass_count=$((pass_count + 1))
  else
    printf '  FAIL %s: expected %q, got %q\n' "$label" "$expected" "$actual"
    fail_count=$((fail_count + 1))
  fi
}

tmp_root="$(mktemp -d 2>/dev/null || mktemp -d -t 'test-fast-pr-detect')"
trap 'rm -rf "$tmp_root"' EXIT

# The spine's PLAN line for a disposable repo (extra args pass through to it).
plan() {
  local root="$1"; shift
  bash "$spine" --plan --repo-root "$root" "$@" 2>/dev/null | grep '^PLAN ' || printf 'PLAN <none>\n'
}

# Disposable repo with origin/main pinned at an initial commit, so the
# committed-branch diff (origin/main...HEAD) and the working-tree/untracked
# signals are all exercisable.
mkrepo() {
  local r="$1"
  mkdir -p "$r"
  git -C "$r" init -q -b main 2>/dev/null
  git -C "$r" config user.email "self-test@local"
  git -C "$r" config user.name "self-test"
  # Silence CRLF auto-conversion warnings on Windows Git Bash.
  git -C "$r" config core.autocrlf false
  git -C "$r" config core.safecrlf false
  mkdir -p "$r/seed"
  printf 'x\n' > "$r/seed/f.txt"
  git -C "$r" add seed/f.txt
  git -C "$r" commit -q -m 'init'
  git -C "$r" update-ref refs/remotes/origin/main HEAD
}

# ---- Case A: committed docs-only diff vs origin/main → docs only ----
r="$tmp_root/committed-docs"; mkrepo "$r"
mkdir -p "$r/docs/core"; printf '# h\n' > "$r/docs/core/x.md"
git -C "$r" add docs/core/x.md; git -C "$r" commit -q -m docs
assert "A committed docs-only → docs only" "PLAN docs=true jvm=false node=false" "$(plan "$r")"

# ---- Case B: staged code diff → runtime suites ----
r="$tmp_root/staged-code"; mkrepo "$r"
mkdir -p "$r/implementation/core/src/re_frame"
printf 'x\n' > "$r/implementation/core/src/re_frame/core.cljc"
git -C "$r" add implementation/core/src/re_frame/core.cljc   # staged, not committed
assert "B staged core code → runtime" "PLAN docs=false jvm=true node=true" "$(plan "$r")"

# ---- Case C: unstaged docs change (tracked, modified, not staged) → docs ----
r="$tmp_root/unstaged-docs"; mkrepo "$r"
mkdir -p "$r/spec"; printf '# a\n' > "$r/spec/006.md"
git -C "$r" add spec/006.md; git -C "$r" commit -q -m addmd
git -C "$r" update-ref refs/remotes/origin/main HEAD
printf '# a\nmore\n' > "$r/spec/006.md"                       # unstaged modify
assert "C unstaged docs → docs only" "PLAN docs=true jvm=false node=false" "$(plan "$r")"

# ---- Case D: untracked docs file → docs ----
r="$tmp_root/untracked-docs"; mkrepo "$r"
printf '# u\n' > "$r/NOTES.md"                                # untracked, never added
assert "D untracked docs → docs only" "PLAN docs=true jvm=false node=false" "$(plan "$r")"

# ---- Case E: unknown surface → conservative full runtime ----
r="$tmp_root/unknown"; mkrepo "$r"
printf 'x\n' > "$r/weird.xyz"; git -C "$r" add weird.xyz
assert "E unknown surface → conservative runtime" "PLAN docs=false jvm=true node=true" "$(plan "$r")"

# ---- Case F: no changes vs origin/main, clean tree → static only ----
r="$tmp_root/clean"; mkrepo "$r"
assert "F no changes → static only" "PLAN docs=false jvm=false node=false" "$(plan "$r")"

# ---- Case G: no origin/main base → conservative fallback ----
r="$tmp_root/nobase"; mkdir -p "$r"
git -C "$r" init -q -b main 2>/dev/null
git -C "$r" config user.email "self-test@local"; git -C "$r" config user.name "self-test"
git -C "$r" config core.autocrlf false; git -C "$r" config core.safecrlf false
printf 'x\n' > "$r/f.txt"; git -C "$r" add -A; git -C "$r" commit -q -m init
assert "G no origin/main → conservative runtime" "PLAN docs=false jvm=true node=true" "$(plan "$r")"

# ---- Case H: mixed docs + code → both tiers ----
r="$tmp_root/mixed"; mkrepo "$r"
mkdir -p "$r/docs" "$r/implementation/core/src/re_frame"
printf '# h\n' > "$r/docs/x.md"
printf 'x\n' > "$r/implementation/core/src/re_frame/core.cljc"
git -C "$r" add -A; git -C "$r" commit -q -m mix
assert "H mixed docs+code → both tiers" "PLAN docs=true jvm=true node=true" "$(plan "$r")"

# ---- Case I: --all override runs the complete spine on a docs-only diff ----
r="$tmp_root/override-all"; mkrepo "$r"
mkdir -p "$r/docs"; printf '# h\n' > "$r/docs/x.md"; git -C "$r" add -A; git -C "$r" commit -q -m d
assert "I --all override → everything" "PLAN docs=true jvm=true node=true" "$(plan "$r" --all)"

# ---- Case J: RF2_FAST_PR_ALL=1 override runs the complete spine ----
assert "J RF2_FAST_PR_ALL=1 override → everything" "PLAN docs=true jvm=true node=true" \
  "$(RF2_FAST_PR_ALL=1 bash "$spine" --plan --repo-root "$r" 2>/dev/null | grep '^PLAN ')"

# ---- Case K: --with-docs forces docs on for a code-only diff ----
r="$tmp_root/with-docs"; mkrepo "$r"
mkdir -p "$r/implementation/core/src/re_frame"
printf 'x\n' > "$r/implementation/core/src/re_frame/core.cljc"; git -C "$r" add -A; git -C "$r" commit -q -m c
assert "K --with-docs on code diff → docs forced on" "PLAN docs=true jvm=true node=true" "$(plan "$r" --with-docs)"

# ---- Case L: --no-docs forces docs off for a docs diff ----
r="$tmp_root/no-docs"; mkrepo "$r"
mkdir -p "$r/docs"; printf '# h\n' > "$r/docs/x.md"; git -C "$r" add -A; git -C "$r" commit -q -m d
assert "L --no-docs on docs diff → docs forced off" "PLAN docs=false jvm=false node=false" "$(plan "$r" --no-docs)"

# ---------------------------------------------------------------------------
# Gate-has-teeth tests.  When the tiering decides "run the doc gates", the
# validators must actually catch the motivating regressions.
# ---------------------------------------------------------------------------
slugs_script="$repo_root/scripts/check_doc_slugs.py"
readme_script="$repo_root/scripts/check_readme_links.py"

broken_anchor_fixture="$repo_root/scripts/_test_fixtures/check_doc_slugs/broken_anchor"
if [ -d "$broken_anchor_fixture" ]; then
  python "$slugs_script" --repo-root "$broken_anchor_fixture" >/dev/null 2>&1
  assert "M check_doc_slugs catches broken anchor" "1" "$?"
else
  printf '  SKIP M: fixture %s not found\n' "$broken_anchor_fixture"
fi

broken_readme_fixture="$repo_root/scripts/_test_fixtures/check_readme_links/broken_internal_link"
if [ -d "$broken_readme_fixture" ]; then
  python "$readme_script" --repo-root "$broken_readme_fixture" --ci >/dev/null 2>&1
  assert "N check_readme_links catches broken target" "1" "$?"
else
  printf '  SKIP N: fixture %s not found\n' "$broken_readme_fixture"
fi

# ---------------------------------------------------------------------------
# Motivating-miss verification.  Reproduce #2232 (pymdownx `_1` disambiguation
# vs GitHub `-1`) and #2233 (anchor missing the `-rf2-XXX` heading suffix); both
# must trip check_doc_slugs.py.
# ---------------------------------------------------------------------------
case_2232="$tmp_root/case-2232"; mkdir -p "$case_2232/docs"
cat > "$case_2232/mkdocs.yml" <<'EOF'
site_name: test
EOF
cat > "$case_2232/docs/index.md" <<'EOF'
# Index

See [trace events](target.md#trace-events-1) for the second occurrence.
EOF
cat > "$case_2232/docs/target.md" <<'EOF'
# Target

## Trace events

First occurrence — slug is `trace-events`.

## Trace events

Second occurrence — pymdownx slug is `trace-events_1` (underscore N).
EOF
python "$slugs_script" --repo-root "$case_2232" >/dev/null 2>&1
assert "O #2232 (trace-events-1 vs trace-events_1)" "1" "$?"

case_2233="$tmp_root/case-2233"; mkdir -p "$case_2233/docs"
cat > "$case_2233/mkdocs.yml" <<'EOF'
site_name: test
EOF
cat > "$case_2233/docs/index.md" <<'EOF'
# Index

See [section](target.md#cljs-reference-helix-as-alternative-substrate) for details.
EOF
cat > "$case_2233/docs/target.md" <<'EOF'
# Target

## CLJS reference: Helix as alternative substrate (rf2-2qit)

Body — heading slug is `cljs-reference-helix-as-alternative-substrate-rf2-2qit`,
NOT `cljs-reference-helix-as-alternative-substrate`.
EOF
python "$slugs_script" --repo-root "$case_2233" >/dev/null 2>&1
assert "P #2233 (anchor missing -rf2-XXX suffix)" "1" "$?"

# ---- Summary ----
total=$((pass_count + fail_count))
printf '\n%s/%s self-test cases passed.\n' "$pass_count" "$total"
if [ "$fail_count" -gt 0 ]; then
  printf '%s FAILED.\n' "$fail_count" >&2
  exit 1
fi
exit 0
