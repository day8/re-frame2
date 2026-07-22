#!/usr/bin/env sh
# scripts/git-hooks/test-pre-commit.sh
#
# Smoke + library tests for the pre-commit hook's TWO marker blocks:
# the mayor commit boundary (rf2-ydl2p) and the worker beads boundary
# (rf2-ia8o7). They are mirror images, so one harness covers both.
#
# Four layers:
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
#   3. Library unit tests for check-beads-boundary.sh — the beads
#      path classifier, same synthetic-stdin technique.
#
#   4. End-to-end smoke for the beads boundary, reusing the layer-2
#      sandbox, covering the rf2-ia8o7 acceptance scenarios:
#
#        (e) worker commit staging .beads/issues.jsonl -> REFUSED, and the
#            message names the file
#        (f) worker commit touching nothing under .beads -> passes
#        (g) worker commit staging .beads/config.yaml (human-authored
#            config) -> passes
#        (h) mayor commit staging .beads/issues.jsonl -> passes (the beads
#            block must no-op in the primary worktree; that IS the
#            checkpoint flow)
#
# Usage:
#   sh scripts/git-hooks/test-pre-commit.sh
#
# Exit code: 0 if all scenarios pass, 1 otherwise.

set -eu

# The beads guard honours RF2_MAYOR_ROOT as an override. If the ambient
# environment carries one (workers often do), it would point at the REAL
# mayor checkout and misclassify this sandbox. Drop it for the whole run.
unset RF2_MAYOR_ROOT || true

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
# $0 lives at scripts/git-hooks/test-pre-commit.sh; repo root is two up.
REPO_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
LIB="$REPO_ROOT/scripts/git-hooks/lib/check-mayor-commit-boundary.sh"
BEADS_LIB="$REPO_ROOT/scripts/git-hooks/lib/check-beads-boundary.sh"
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
docs/core/index.md
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
             docs/core/index.md \
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
#
# BOTH libs go into BOTH trees. The hook resolves its libs against
# `git rev-parse --show-toplevel`, which differs per worktree, and the
# beads block silently no-ops when its lib is missing — so omitting it from
# the worker tree would make scenarios (e)-(h) pass vacuously.
mkdir -p "$MAYOR/scripts/git-hooks/lib" "$WORKER/scripts/git-hooks/lib"
cp "$LIB" "$BEADS_LIB" "$MAYOR/scripts/git-hooks/lib/"
cp "$LIB" "$BEADS_LIB" "$WORKER/scripts/git-hooks/lib/"
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

# ----------------------------------------------------------------------------
# Layer 3: check-beads-boundary.sh library tests (rf2-ia8o7).
# ----------------------------------------------------------------------------

printf '\n[3] check-beads-boundary.sh library tests\n'

run_beads_lib() {
  # $1: context (commit|ci). stdin: newline-separated paths.
  (
    . "$BEADS_LIB"
    check_beads_boundary "${1:-commit}"
    echo "EXIT=$?"
  )
}

BERR=/tmp/rf2-beads-test.err

# 3a: empty stdin -> exit 0, silent.
out=$(printf '' | run_beads_lib commit 2>"$BERR") || true
case "$out" in
  *EXIT=0*)
    if [ ! -s "$BERR" ]; then
      pass "empty staged list -> exit 0, no stderr"
    else
      fail "empty staged list -> stderr non-empty"; cat "$BERR" >&2
    fi
    ;;
  *) fail "empty staged list -> wrong exit: $out" ;;
esac

# 3b: ordinary source paths are none of this guard's business.
out=$(printf 'implementation/core/src/re_frame/core.cljc\nspec/002-Frames.md\n' \
      | run_beads_lib commit 2>"$BERR") || true
case "$out" in
  *EXIT=0*) pass "paths outside .beads/ -> exit 0" ;;
  *) fail "paths outside .beads/ -> wrong exit: $out"; cat "$BERR" >&2 ;;
esac

# 3c: the incident path -> refused, and the message NAMES the file.
out=$(printf '.beads/issues.jsonl\n' | run_beads_lib commit 2>"$BERR") || true
case "$out" in
  *EXIT=1*)
    if grep -q '\.beads/issues\.jsonl' "$BERR" \
       && grep -q 'STALE WORKER-SNAPSHOT' "$BERR" \
       && grep -q 'git checkout HEAD -- .beads' "$BERR"; then
      pass ".beads/issues.jsonl -> refused, names the file + the remedy"
    else
      fail ".beads/issues.jsonl -> exited 1 but message incomplete"; cat "$BERR" >&2
    fi
    ;;
  *) fail ".beads/issues.jsonl -> wrong exit: $out" ;;
esac

# 3d: every other database-derived path is refused too (allow-list, not
# deny-list) — including artefacts that do not exist yet.
for p in .beads/metadata.json .beads/events.jsonl .beads/beads.db .beads/dolt/noms/foo; do
  out=$(printf '%s\n' "$p" | run_beads_lib commit 2>"$BERR") || true
  case "$out" in
    *EXIT=1*) pass "database-derived path refused: $p" ;;
    *) fail "database-derived path NOT refused: $p (exit: $out)" ;;
  esac
done

# 3e: the human-authored beads config surface stays committable from anywhere.
out=$(printf '.beads/README.md\n.beads/config.yaml\n.beads/.gitignore\n.beads/hooks/pre-commit\n' \
      | run_beads_lib commit 2>"$BERR") || true
case "$out" in
  *EXIT=0*) pass "human-authored beads config -> exit 0" ;;
  *) fail "human-authored beads config -> wrongly refused: $out"; cat "$BERR" >&2 ;;
esac

# 3f: mixed -> refused, and the listing names ONLY the beads path.
out=$(printf 'implementation/core/src/ok.cljc\n.beads/issues.jsonl\n' \
      | run_beads_lib commit 2>"$BERR") || true
case "$out" in
  *EXIT=1*)
    listing=$(awk '
      /eads-database paths/       {flag=1; next}
      /STALE WORKER-SNAPSHOT/     {flag=0}
      flag {print}
    ' "$BERR")
    if printf '%s' "$listing" | grep -q '\.beads/issues\.jsonl' \
       && ! printf '%s' "$listing" | grep -q 'ok\.cljc'; then
      pass "mixed staged -> listing contains only the refused beads path"
    else
      fail "mixed staged -> refused, but listing wrong"
      printf -- '----- listing -----\n%s\n----- end -----\n' "$listing" >&2
    fi
    ;;
  *) fail "mixed staged -> wrong exit: $out" ;;
esac

# 3g: the ci context swaps in the branch-repair remedy.
out=$(printf '.beads/issues.jsonl\n' | run_beads_lib ci 2>"$BERR") || true
case "$out" in
  *EXIT=1*)
    if grep -q 'PR diff' "$BERR" && grep -q 'git rebase -i' "$BERR"; then
      pass "ci context -> branch-repair remedy"
    else
      fail "ci context -> remedy stanza missing"; cat "$BERR" >&2
    fi
    ;;
  *) fail "ci context -> wrong exit: $out" ;;
esac

# 3h: REGRESSION GUARD. An earlier draft told the operator to run
# `git update-index --skip-worktree .beads/issues.jsonl`. Measured, that
# hides the edit from `git status` yet still aborts `git pull` — a frozen
# HEAD with nothing on screen to explain it. The remedy must never say it
# again, in either context.
skipwt_clean=1
for ctx in commit ci; do
  printf '.beads/issues.jsonl\n' | run_beads_lib "$ctx" 2>"$BERR" >/dev/null || true
  if grep -q 'skip-worktree' "$BERR"; then
    skipwt_clean=0
    fail "remedy ($ctx) recommends skip-worktree — measured harmful, see the lib header"
  fi
done
[ "$skipwt_clean" = "1" ] && pass "remedy never recommends skip-worktree (both contexts)"

rm -f "$BERR"

# ----------------------------------------------------------------------------
# Layer 4: end-to-end smoke for the beads boundary (rf2-ia8o7).
#
# Reuses the layer-2 sandbox. The hook now carries BOTH marker blocks, so
# these scenarios also prove the two guards coexist without interfering.
# ----------------------------------------------------------------------------

printf '\n[4] end-to-end smoke (worker beads boundary)\n'

# Scenario (e): worker stages the tracker database -> REFUSED.
scenario_e() {
  cd "$WORKER"
  printf '{"id":"stale","title":"stale worktree snapshot"}\n' > .beads/issues.jsonl
  git add .beads/issues.jsonl
  git commit -q -m 'worker: stale beads snapshot'
}
rc_e=$(mktemp); run_scenario "$rc_e" scenario_e
if [ "$(cat "$rc_e")" != "0" ] \
   && grep -q '\.beads/issues\.jsonl' /tmp/rf2-pc-smoke.err \
   && grep -q 'STALE WORKER-SNAPSHOT' /tmp/rf2-pc-smoke.err; then
  pass "(e) worker commit: .beads/issues.jsonl -> refused (exit $(cat "$rc_e"))"
else
  fail "(e) worker commit staging the tracker was NOT blocked (exit $(cat "$rc_e"))"
  cat /tmp/rf2-pc-smoke.err >&2 || true
fi
# Unstage unconditionally so a failure here cannot cascade into (f)/(g).
( cd "$WORKER" && git reset -q HEAD && git checkout -q -- .beads ) || true
rm -f "$rc_e"

# Scenario (f): worker commit touching nothing under .beads -> passes.
scenario_f() {
  cd "$WORKER"
  mkdir -p implementation/core/src
  echo '(ns ok)' > implementation/core/src/ok.cljc
  git add implementation/core/src/ok.cljc
  git commit -q -m 'worker: ordinary source change'
}
rc_f=$(mktemp); run_scenario "$rc_f" scenario_f
if [ "$(cat "$rc_f")" = "0" ]; then
  pass "(f) worker commit: ordinary source, no .beads -> passed"
else
  fail "(f) ordinary worker commit was refused (exit $(cat "$rc_f"))"
  cat /tmp/rf2-pc-smoke.err >&2 || true
fi
rm -f "$rc_f"

# Scenario (g): worker commit of human-authored beads CONFIG -> passes.
scenario_g() {
  cd "$WORKER"
  printf 'auto_export: true\n' > .beads/config.yaml
  git add .beads/config.yaml
  git commit -q -m 'worker: beads config (human-authored)'
}
rc_g=$(mktemp); run_scenario "$rc_g" scenario_g
if [ "$(cat "$rc_g")" = "0" ]; then
  pass "(g) worker commit: .beads/config.yaml -> passed (allow-listed)"
else
  fail "(g) human-authored beads config was refused (exit $(cat "$rc_g"))"
  cat /tmp/rf2-pc-smoke.err >&2 || true
fi
rm -f "$rc_g"

# Scenario (h): mayor commit of the tracker -> passes. The beads block must
# no-op in the primary worktree; that IS the checkpoint flow.
scenario_h() {
  cd "$MAYOR"
  printf '{"id":"h","title":"mayor checkpoint"}\n' > .beads/issues.jsonl
  git add .beads/issues.jsonl
  git commit -q -m 'mayor: bd checkpoint (h)'
}
rc_h=$(mktemp); run_scenario "$rc_h" scenario_h
if [ "$(cat "$rc_h")" = "0" ]; then
  pass "(h) mayor commit: .beads/issues.jsonl -> passed (guard no-ops in primary)"
else
  fail "(h) mayor checkpoint flow was broken by the beads guard (exit $(cat "$rc_h"))"
  cat /tmp/rf2-pc-smoke.err >&2 || true
fi
rm -f "$rc_h"

rm -f /tmp/rf2-pc-smoke.err

# ----------------------------------------------------------------------------
# Summary
# ----------------------------------------------------------------------------

printf '\nSummary: %d passed, %d failed\n' "$pass_count" "$fail_count"
if [ "$fail_count" -ne 0 ]; then
  exit 1
fi
exit 0
