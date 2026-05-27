#!/usr/bin/env bash
# Self-test for the markdown-change detection in scripts/test-fast-pr.sh
# (rf2-lwweq).
#
# This isolates the detection-and-conditional-run logic — it does NOT
# invoke the full spine (which would re-run lockstep + CLJS tests for
# many minutes).  Instead we extract the detection block + the
# conditional invocations into a tiny harness and assert four cases:
#
#   case A — no markdown diff, --auto      → markdown gates skipped
#   case B — markdown diff present, --auto → markdown gates run
#   case C — --no-docs flag → markdown gates skipped regardless of diff
#   case D — --with-docs flag → markdown gates run regardless of diff
#
# We also assert the gates actually catch a known-broken anchor (case E)
# and a broken README target (case F), proving the gate has teeth.
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

# ---------------------------------------------------------------------------
# Detection-logic harness.
#
# We replicate the detection block from test-fast-pr.sh against a clean
# disposable git repo so the spine's behaviour on a real diff is testable
# without actually running the expensive gates.
# ---------------------------------------------------------------------------

tmp_root="$(mktemp -d 2>/dev/null || mktemp -d -t 'test-fast-pr-detect')"
trap 'rm -rf "$tmp_root"' EXIT

detect_md_change() {
  # Args: $1 = repo path; $2 = with_docs mode (auto|force|skip)
  local repo="$1"
  local with_docs="$2"
  if [ "$with_docs" = "force" ]; then
    printf 'true\n'
    return
  fi
  if [ "$with_docs" = "skip" ]; then
    printf 'false\n'
    return
  fi
  local markdown_changed=false
  if git -C "$repo" rev-parse --verify origin/main >/dev/null 2>&1; then
    if git -C "$repo" diff --name-only origin/main HEAD 2>/dev/null | grep -qE '\.md$'; then
      markdown_changed=true
    fi
  fi
  if git -C "$repo" status --porcelain 2>/dev/null | grep -qE '\.md$'; then
    markdown_changed=true
  fi
  printf '%s\n' "$markdown_changed"
}

# Build a fake repo with origin/main fixed at HEAD and one non-md committed
# change on top — represents a "no markdown diff vs origin/main, no
# unstaged md" state.
init_repo_no_md() {
  local r="$1"
  mkdir -p "$r"
  git -C "$r" init -q -b main 2>/dev/null
  git -C "$r" config user.email "self-test@local"
  git -C "$r" config user.name "self-test"
  # Silence CRLF auto-conversion warnings on Windows Git Bash.
  git -C "$r" config core.autocrlf false
  git -C "$r" config core.safecrlf false
  printf 'first\n' > "$r/initial.txt"
  git -C "$r" add initial.txt
  git -C "$r" commit -q -m 'initial'
  # Fake origin/main pointing at this commit.
  git -C "$r" update-ref refs/remotes/origin/main HEAD
  # One non-md change on top.
  printf 'second\n' >> "$r/initial.txt"
  git -C "$r" add initial.txt
  git -C "$r" commit -q -m 'non-md change'
}

# Build a fake repo with a committed .md change on top of origin/main.
init_repo_committed_md() {
  local r="$1"
  init_repo_no_md "$r"
  printf '# changed\n' > "$r/notes.md"
  git -C "$r" add notes.md
  git -C "$r" commit -q -m 'add markdown'
}

# Build a fake repo with an UNSTAGED .md change (worktree dirty, no
# commit beyond origin/main).
init_repo_unstaged_md() {
  local r="$1"
  init_repo_no_md "$r"
  printf '# unstaged\n' > "$r/dirty.md"
  # Deliberately do NOT git add; we want porcelain to show '??'.
}

# ---- Case A: no md, --auto ----
case_a="$tmp_root/case-a"
init_repo_no_md "$case_a"
assert "case A (no md, --auto)" "false" "$(detect_md_change "$case_a" auto)"

# ---- Case B1: committed md vs origin/main, --auto ----
case_b1="$tmp_root/case-b1"
init_repo_committed_md "$case_b1"
assert "case B1 (committed md vs origin/main, --auto)" "true" "$(detect_md_change "$case_b1" auto)"

# ---- Case B2: unstaged md, --auto ----
case_b2="$tmp_root/case-b2"
init_repo_unstaged_md "$case_b2"
assert "case B2 (unstaged md, --auto)" "true" "$(detect_md_change "$case_b2" auto)"

# ---- Case C: md present, --no-docs ----
case_c="$tmp_root/case-c"
init_repo_committed_md "$case_c"
assert "case C (md present, --no-docs)" "false" "$(detect_md_change "$case_c" skip)"

# ---- Case D: no md, --with-docs ----
case_d="$tmp_root/case-d"
init_repo_no_md "$case_d"
assert "case D (no md, --with-docs)" "true" "$(detect_md_change "$case_d" force)"

# ---------------------------------------------------------------------------
# Gate-has-teeth tests.
#
# We exercise the actual link/anchor validators against the bundled
# broken fixtures and assert they exit non-zero — proving that when the
# detection block decides "run the gates", the gates would actually
# catch the motivating regressions.
# ---------------------------------------------------------------------------

slugs_script="$repo_root/scripts/check_doc_slugs.py"
readme_script="$repo_root/scripts/check_readme_links.py"

# ---- Case E: check_doc_slugs catches a broken anchor ----
broken_anchor_fixture="$repo_root/scripts/_test_fixtures/check_doc_slugs/broken_anchor"
if [ -d "$broken_anchor_fixture" ]; then
  python "$slugs_script" --repo-root "$broken_anchor_fixture" >/dev/null 2>&1
  rc=$?
  assert "case E (check_doc_slugs catches broken anchor)" "1" "$rc"
else
  printf '  SKIP case E: fixture %s not found\n' "$broken_anchor_fixture"
fi

# ---- Case F: check_readme_links catches a broken target ----
broken_readme_fixture="$repo_root/scripts/_test_fixtures/check_readme_links/broken_internal_link"
if [ -d "$broken_readme_fixture" ]; then
  python "$readme_script" --repo-root "$broken_readme_fixture" --ci >/dev/null 2>&1
  rc=$?
  assert "case F (check_readme_links catches broken target)" "1" "$rc"
else
  printf '  SKIP case F: fixture %s not found\n' "$broken_readme_fixture"
fi

# ---------------------------------------------------------------------------
# Motivating-miss verification.
#
# Build a tiny mkdocs-rooted repo that reproduces #2232's defect shape
# (anchor #trace-events-1 vs heading-derived slug trace-events_1) and
# #2233's defect shape (anchor without the (rf2-XXX) suffix the heading
# carries), then assert the validators flag both.
# ---------------------------------------------------------------------------

# #2232 reproduction — duplicate "Trace events" heading where the
# second occurrence gets pymdownx's `_1` suffix; an author referencing
# it with GitHub-style `-1` would slip through GitHub preview but fail
# on the MkDocs build.
case_2232="$tmp_root/case-2232"
mkdir -p "$case_2232/docs"
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
rc_2232=$?
assert "#2232 motivating miss (trace-events-1 vs trace-events_1)" "1" "$rc_2232"

# #2233 reproduction — heading with bead suffix `## Foo (rf2-2qit)`,
# pymdownx slug is `foo-rf2-2qit`; an author who writes `#foo` (without
# the suffix) gets a broken anchor.
case_2233="$tmp_root/case-2233"
mkdir -p "$case_2233/docs"
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
rc_2233=$?
assert "#2233 motivating miss (anchor missing -rf2-XXX suffix)" "1" "$rc_2233"

# ---- Summary ----
total=$((pass_count + fail_count))
printf '\n%s/%s self-test cases passed.\n' "$pass_count" "$total"
if [ "$fail_count" -gt 0 ]; then
  printf '%s FAILED.\n' "$fail_count" >&2
  exit 1
fi
exit 0
