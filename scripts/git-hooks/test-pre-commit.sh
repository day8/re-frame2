#!/usr/bin/env sh
# scripts/git-hooks/test-pre-commit.sh
#
# Smoke + library tests for the mayor pre-commit hook (rf2-ydl2p).
#
# Two layers:
#
#   1. Library unit tests — invoke
#      scripts/git-hooks/lib/check-mayor-commit-boundary.sh directly with
#      synthetic stdin streams and assert against stdout / stderr / exit.
#
#   2. End-to-end smoke — build a throwaway git repo + worktree pair in
#      $TMPDIR, install the hook + marker as the installer would, and
#      drive `git commit` on each side to verify the four acceptance
#      scenarios from rf2-ydl2p:
#
#        (a) mayor commit with only .beads/issues.jsonl staged -> passes
#        (b) mayor commit with tools/xray/foo.cljs staged       -> refused
#        (c) worker worktree commit with source staged          -> passes
#        (d) mayor commit with mixed staged paths               -> refused
#
# Usage:
#   sh scripts/git-hooks/test-pre-commit.sh
#
# Exit code: 0 if all scenarios pass, 1 otherwise.

set -eu

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
# $0 lives at scripts/git-hooks/test-pre-commit.sh; repo root is two up.
REPO_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
LIB="$REPO_ROOT/scripts/git-hooks/lib/check-mayor-commit-boundary.sh"
HOOK="$REPO_ROOT/scripts/git-hooks/pre-commit"

fail_count=0
pass_count=0

pass() {
  pass_count=$((pass_count + 1))
  printf '  PASS  %s\n' "$1"
}

fail() {
  fail_count=$((fail_count + 1))
  printf '  FAIL  %s\n' "$1" >&2
}

# ----------------------------------------------------------------------------
# Layer 1: library unit tests.
# ----------------------------------------------------------------------------

printf '\n[1] check-mayor-commit-boundary.sh library tests\n'

# Source the lib in a subshell wrapper so the trap inside does not affect us.
run_lib() {
  # stdin: newline-separated paths
  # stdout: lib stdout (should be empty)
  # stderr: lib stderr (the refusal block on refused)
  # echoes the exit code on stdout's last line for easy capture.
  (
    . "$LIB"
    check_mayor_commit_boundary
    echo "EXIT=$?"
  )
}

# Test 1a: empty stdin -> exit 0, no stderr.
out=$(printf '' | run_lib 2>/tmp/rf2-precommit-test.err) || true
case "$out" in
  *EXIT=0*)
    if [ ! -s /tmp/rf2-precommit-test.err ]; then
      pass "empty staged list -> exit 0, no stderr"
    else
      fail "empty staged list -> stderr non-empty"
      cat /tmp/rf2-precommit-test.err >&2
    fi
    ;;
  *) fail "empty staged list -> wrong exit: $out" ;;
esac

# Test 1b: only .beads/issues.jsonl -> exit 0.
out=$(printf '.beads/issues.jsonl\n' | run_lib 2>/tmp/rf2-precommit-test.err) || true
case "$out" in
  *EXIT=0*) pass "only .beads/issues.jsonl staged -> exit 0" ;;
  *) fail ".beads/issues.jsonl staged -> wrong exit: $out"
     cat /tmp/rf2-precommit-test.err >&2
     ;;
esac

# Test 1c: only MEMORY.md -> exit 0.
out=$(printf 'MEMORY.md\n' | run_lib 2>/tmp/rf2-precommit-test.err) || true
case "$out" in
  *EXIT=0*) pass "only MEMORY.md staged -> exit 0" ;;
  *) fail "MEMORY.md staged -> wrong exit: $out" ;;
esac

# Test 1d: tools/xray/foo.cljs -> exit 1 + error block on stderr.
out=$(printf 'tools/xray/foo.cljs\n' | run_lib 2>/tmp/rf2-precommit-test.err) || true
case "$out" in
  *EXIT=1*)
    if grep -q 'mayor checkout cannot commit' /tmp/rf2-precommit-test.err \
       && grep -q 'tools/xray/foo.cljs' /tmp/rf2-precommit-test.err; then
      pass "tools/xray/foo.cljs -> refused with error block"
    else
      fail "tools/xray/foo.cljs -> exited 1 but stderr missing expected text"
      cat /tmp/rf2-precommit-test.err >&2
    fi
    ;;
  *) fail "tools/xray/foo.cljs -> wrong exit: $out" ;;
esac

# Test 1e: mixed permitted + refused -> exit 1 (any-refused triggers).
# The refused listing should contain tools/xray/foo.cljs but NOT
# .beads/issues.jsonl. (.beads appears once more in the "Permitted in
# mayor commits" footer, which is expected — we test the refused-listing
# section by extracting the lines between "Staged files in refused
# zones:" and the blank-line-then-"Permitted" separator.)
out=$(printf '.beads/issues.jsonl\ntools/xray/foo.cljs\n' | run_lib 2>/tmp/rf2-precommit-test.err) || true
case "$out" in
  *EXIT=1*)
    refused_listing=$(awk '
      /Staged files in refused zones:/ {flag=1; next}
      /Permitted in mayor commits:/    {flag=0}
      flag {print}
    ' /tmp/rf2-precommit-test.err)
    if printf '%s' "$refused_listing" | grep -q 'tools/xray/foo.cljs' \
       && ! printf '%s' "$refused_listing" | grep -q '\.beads/issues\.jsonl'; then
      pass "mixed staged -> refused listing contains only the refused path"
    else
      fail "mixed staged -> refused, but listing wrong"
      printf '----- refused listing -----\n%s\n----- end -----\n' \
        "$refused_listing" >&2
    fi
    ;;
  *) fail "mixed staged -> wrong exit: $out" ;;
esac

# Test 1f: many surface examples all refused.
cases='implementation/core/src/re_frame/core.cljc
tools/xray/src/foo.cljs
spec/009-Instrumentation.md
docs/guide/index.md
examples/core/counter/main.cljs
skills/re-frame2-pair/SKILL.md
scripts/install-git-hooks.sh
migration/from-re-frame-v1/README.md
testbeds/parallel-frames/spec.cjs
README.md'
out=$(printf '%s\n' "$cases" | run_lib 2>/tmp/rf2-precommit-test.err) || true
case "$out" in
  *EXIT=1*)
    refused_all=1
    for p in implementation/core/src/re_frame/core.cljc \
             tools/xray/src/foo.cljs \
             spec/009-Instrumentation.md \
             docs/guide/index.md \
             examples/core/counter/main.cljs \
             skills/re-frame2-pair/SKILL.md \
             scripts/install-git-hooks.sh \
             migration/from-re-frame-v1/README.md \
             testbeds/parallel-frames/spec.cjs \
             README.md; do
      if ! grep -q "$p" /tmp/rf2-precommit-test.err; then
        refused_all=0
        fail "expected refused path absent from stderr: $p"
      fi
    done
    if [ "$refused_all" = "1" ]; then
      pass "all worker-tracked surface samples refused"
    fi
    ;;
  *) fail "broad refused set -> wrong exit: $out" ;;
esac

rm -f /tmp/rf2-precommit-test.err

# ----------------------------------------------------------------------------
# Layer 2: end-to-end smoke via throwaway repo + worktree.
# ----------------------------------------------------------------------------

printf '\n[2] end-to-end smoke (mayor + worker worktree)\n'

# Build the sandbox in $TMPDIR; clean on exit.
SANDBOX=$(mktemp -d "${TMPDIR:-/tmp}/rf2-precommit-sandbox-XXXXXX")
trap 'rm -rf "$SANDBOX"' EXIT INT TERM HUP

MAYOR="$SANDBOX/mayor"
WORKER="$SANDBOX/worker"

(
  mkdir -p "$MAYOR"
  cd "$MAYOR"
  git init -q -b main
  git config user.email 'precommit-test@example.invalid'
  git config user.name 'precommit-test'
  git config commit.gpgsign false
  # Seed commit so we can create a worktree.
  mkdir -p .beads
  printf '{"id":"seed","title":"seed"}\n' > .beads/issues.jsonl
  git add .beads/issues.jsonl
  git commit -q -m 'seed'
  # Add a worker worktree.
  git worktree add -q -b worker/test "$WORKER"
) >/dev/null

# Install the hook + lib + marker into the mayor's common dir hooks dir.
# We mimic the installer minimally — just copy the canonical files into
# place and drop the marker. That keeps the smoke test focused on hook
# behaviour rather than re-testing the installer.
COMMON_DIR=$(git -C "$MAYOR" rev-parse --git-common-dir)
case "$COMMON_DIR" in /*|[A-Za-z]:[\\/]*) ;; *) COMMON_DIR="$MAYOR/$COMMON_DIR" ;; esac
HOOKS_DIR="$COMMON_DIR/hooks"
mkdir -p "$HOOKS_DIR"
# The hook expects the lib at <repo-root>/scripts/git-hooks/lib/...; in
# this sandbox the "repo root" is $MAYOR, so we ship the lib there too.
mkdir -p "$MAYOR/scripts/git-hooks/lib"
cp "$LIB" "$MAYOR/scripts/git-hooks/lib/"
cp "$HOOK" "$HOOKS_DIR/pre-commit"
chmod +x "$HOOKS_DIR/pre-commit"
printf 'sandbox marker\n' > "$COMMON_DIR/mayor-marker"

# Run a scenario inside a subshell; capture exit status via temp file so
# `set -e` in the parent doesn't abort on a non-zero subshell exit.
run_scenario() {
  rc_file="$1"
  shift
  : > /tmp/rf2-pc-smoke.err
  ( "$@" 2>/tmp/rf2-pc-smoke.err ) && echo 0 > "$rc_file" || echo $? > "$rc_file"
}

# Scenario (a): mayor commit with only .beads/issues.jsonl -> passes.
scenario_a() {
  cd "$MAYOR"
  printf '{"id":"a","title":"a"}\n' > .beads/issues.jsonl
  git add .beads/issues.jsonl
  git commit -q -m 'mayor: bd closure (a)'
}
rc_a=$(mktemp); run_scenario "$rc_a" scenario_a
if [ "$(cat "$rc_a")" = "0" ]; then
  pass "(a) mayor commit: .beads/issues.jsonl only -> passed"
else
  fail "(a) mayor commit with permitted path was refused (exit $(cat "$rc_a"))"
  cat /tmp/rf2-pc-smoke.err >&2 || true
fi
rm -f "$rc_a"

# Scenario (b): mayor commit touching tools/xray/foo.cljs -> refused.
scenario_b() {
  cd "$MAYOR"
  mkdir -p tools/xray
  echo '(ns foo)' > tools/xray/foo.cljs
  git add tools/xray/foo.cljs
  git commit -q -m 'mayor: refused'
}
rc_b=$(mktemp); run_scenario "$rc_b" scenario_b
if [ "$(cat "$rc_b")" != "0" ] && grep -q 'mayor checkout cannot commit' /tmp/rf2-pc-smoke.err; then
  pass "(b) mayor commit: tools/xray/foo.cljs -> refused (exit $(cat "$rc_b"))"
  # Reset stage so subsequent tests aren't polluted.
  ( cd "$MAYOR" && git reset -q HEAD && rm -rf tools ) || true
else
  fail "(b) refused-zone mayor commit was NOT blocked (exit $(cat "$rc_b"))"
  cat /tmp/rf2-pc-smoke.err >&2 || true
fi
rm -f "$rc_b"

# Scenario (c): worker worktree commit with source staged -> passes
# (hook is no-op there because no mayor-marker in the worker's git-dir).
scenario_c() {
  cd "$WORKER"
  mkdir -p tools/xray
  echo '(ns bar)' > tools/xray/bar.cljs
  git add tools/xray/bar.cljs
  git commit -q -m 'worker: source change'
}
rc_c=$(mktemp); run_scenario "$rc_c" scenario_c
if [ "$(cat "$rc_c")" = "0" ]; then
  pass "(c) worker commit: tools/xray/bar.cljs -> passed (hook no-op)"
else
  fail "(c) worker commit was refused (hook should no-op without marker) (exit $(cat "$rc_c"))"
  cat /tmp/rf2-pc-smoke.err >&2 || true
fi
rm -f "$rc_c"

# Scenario (d): mayor commit with mixed (.beads/issues.jsonl + refused) -> refused.
scenario_d() {
  cd "$MAYOR"
  mkdir -p tools/xray
  echo '(ns mix)' > tools/xray/mix.cljs
  printf '{"id":"d","title":"d"}\n' > .beads/issues.jsonl
  git add .beads/issues.jsonl tools/xray/mix.cljs
  git commit -q -m 'mayor: mixed'
}
rc_d=$(mktemp); run_scenario "$rc_d" scenario_d
if [ "$(cat "$rc_d")" != "0" ] && grep -q 'tools/xray/mix.cljs' /tmp/rf2-pc-smoke.err; then
  pass "(d) mayor mixed commit -> refused (any-refused triggers) (exit $(cat "$rc_d"))"
  ( cd "$MAYOR" && git reset -q HEAD && rm -rf tools ) || true
else
  fail "(d) mixed-zone mayor commit was NOT blocked (exit $(cat "$rc_d"))"
  cat /tmp/rf2-pc-smoke.err >&2 || true
fi
rm -f "$rc_d"

rm -f /tmp/rf2-pc-smoke.err

# ----------------------------------------------------------------------------
# Summary
# ----------------------------------------------------------------------------

printf '\nSummary: %d passed, %d failed\n' "$pass_count" "$fail_count"
if [ "$fail_count" -ne 0 ]; then
  exit 1
fi
exit 0
