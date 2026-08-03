#!/usr/bin/env sh
# scripts/git-hooks/test-pre-commit.sh
#
# Smoke + library tests for the pre-commit hook's TWO marker blocks:
# the mayor commit boundary (rf2-ydl2p) and the worker beads boundary
# (rf2-ia8o7). They are mirror images, so one harness covers both.
#
# The harness has since grown to cover the whole local-durability surface those
# two blocks belong to — NINE layers. Layers 1-4 are the pre-commit hook
# itself; 5 is the CI arm that shares its classifier; 6-7 are the installer
# that puts the hooks on disk and the advisory that notices when they go stale;
# 8 is the checkpoint helper on the other side of the same boundary; 9 is the
# truncation floor the hook grew after that helper's guard was routed around
# twice. It keeps its name because `.github/workflows/test.yml` runs it by name,
# unconditionally, on every pull request.
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
#   5. The CI arm (scripts/check-beads-pr-boundary.sh) on DIVERGED history —
#      the branch-point selection it depends on (rf2-5z20y).
#
#   6. The INSTALLER, end to end: install, worktree inheritance, the bite, and
#      drift detection (rf2-zt65l).
#
#   7. The staleness advisory on REAL pulls of both shapes — rebasing
#      (post-rewrite) and merging (post-merge). Layer 6 invokes the hook by
#      hand; this layer runs `git pull` and is the regression net for the
#      rf2-zt65l audit reopen.
#
#   8. The checkpoint helper (scripts/beads-checkpoint.sh, rf2-51uz1), driven
#      against a stub `bd`: a close that lives only in the database survives the
#      pre-pull checkout, a broken export commits nothing, and a memory reorder
#      is not a commit — nor does one ride along with a real change
#      (rf2-51uz1.1), while the >1/10 shrink guard still refuses.
#
#   9. The TRUNCATION FLOOR in the hook (rf2-or8te) — layer 8's guard repeated
#      where no committer can route around it. Layer 8 proves the checkpoint
#      helper refuses an empty export; twice that was not enough, because the
#      commit that emptied the tracker was a plain `git add` from the MAYOR
#      checkout and never went through the helper. Driven in the layer-2
#      sandbox's PRIMARY worktree, which is where both incidents happened.
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
    # `set +e` is LOAD-BEARING, and it is a portability fix, not a style
    # choice. This helper exists to CAPTURE a non-zero return, so errexit
    # would kill the subshell before `echo "EXIT=$?"` ever ran. Callers wrap
    # the capture in `|| true`, and bash extends that "errexit suspended"
    # state into the command substitution — which is why every refusal case
    # passed under Git Bash for as long as this harness only ever ran locally
    # on Windows. dash does not extend it, so on the ubuntu runner (`sh` is
    # dash) the subshell died at the refusal and `$out` came back EMPTY: ten
    # silent failures, all of them the exit-1 cases. Suspending errexit here
    # makes both shells agree (rf2-3mh2f wired this harness into CI, which is
    # how a Linux-only break in the guard's own tests finally surfaced).
    set +e
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
    # `set +e` for the same portability reason as run_lib above — without it
    # every refusal case is silently unobservable under dash.
    set +e
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
# Layer 5: the CI arm, on DIVERGED HISTORY (rf2-5z20y).
#
# scripts/check-beads-pr-boundary.sh is the pull-request half of the guard.
# Its defect was not in WHAT it classifies — the layer-3 tests cover that —
# but in WHICH PATHS it hands the classifier. It used a two-endpoint
# `git diff "$BASE" HEAD`, which reports every path where the two trees
# differ, including paths only the BASE moved.
#
# In this repository the mayor checkpoints `.beads/issues.jsonl` to main on
# essentially every loop tick, so a branch that forked before the last
# checkpoint got told it had committed tracker contamination it never
# touched — a false RED with the wrong remedy, on most open branches.
#
# Endpoint-only good/bad fixtures CANNOT see this: both endpoints are
# individually well-formed. Only the SEQUENCE exposes it — fork, advance the
# base with a beads-only commit, then assert. Layer 5 is that sequence, and
# it fails under the two-endpoint implementation.
# ----------------------------------------------------------------------------

printf '\n[5] CI arm on diverged history (rf2-5z20y)\n'

PR_GUARD="$REPO_ROOT/scripts/check-beads-pr-boundary.sh"
CIBOX=$(mktemp -d "${TMPDIR:-/tmp}/rf2-beads-ci-XXXXXX")
CIERR=$(mktemp "${TMPDIR:-/tmp}/rf2-beads-ci-err-XXXXXX")

(
  cd "$CIBOX"
  git init -q -b main
  git config user.email 'precommit-test@example.invalid'
  git config user.name 'precommit-test'
  git config commit.gpgsign false
  # The guard resolves its classifier lib relative to its OWN location, so
  # ship both into the sandbox exactly as the repo lays them out.
  mkdir -p scripts/git-hooks/lib .beads implementation/core/src
  cp "$PR_GUARD" scripts/
  cp "$BEADS_LIB" scripts/git-hooks/lib/
  printf '{"id":"seed"}\n' > .beads/issues.jsonl
  printf '(ns seed)\n' > implementation/core/src/seed.cljc
  git add -A
  git commit -q -m 'seed'

  # Four branches fork HERE, from the same base commit.
  git branch worker/clean
  git branch worker/contaminated
  git branch worker/renamed-out
  git branch worker/renamed-to-config

  # ...and only THEN does the base advance, with a mayor beads-only
  # checkpoint. This commit is the whole point of the fixture.
  printf '{"id":"seed"}\n{"id":"filed-after-the-fork"}\n' > .beads/issues.jsonl
  git commit -q -am 'chore(beads): mayor heartbeat AFTER both branches forked'
  # Mirror the CI runner's remote-tracking ref.
  git update-ref refs/remotes/origin/main "$(git rev-parse main)"

  git checkout -q worker/clean
  printf '(ns seed)\n;; ordinary work\n' > implementation/core/src/seed.cljc
  git commit -q -am 'worker: ordinary source change, no tracker'

  git checkout -q worker/contaminated
  printf '(ns seed)\n;; ordinary work\n' > implementation/core/src/seed.cljc
  # The worker's own `bd` calls (a claim, a close) rewrote the export, so it
  # differs from the branch point — and, having forked before the heartbeat,
  # it silently DROPS the bead filed on main. That is the time travel.
  printf '{"id":"seed"}\n{"id":"claimed-by-this-worker","status":"in_progress"}\n' \
    > .beads/issues.jsonl
  git add -A
  git commit -q -m 'worker: real work + bd auto-staged tracker snapshot'

  # An EXACT rename out of the protected tree (rf2-ajbgq). Content is
  # untouched, so git scores it R100 and `--name-only` reports the
  # destination alone — the deleted `.beads/issues.jsonl` endpoint simply is
  # not in the guard's input.
  git checkout -q worker/renamed-out
  git mv .beads/issues.jsonl tracker-snapshot.jsonl
  git commit -q -m 'worker: move the tracker out of .beads/'

  # The same move onto an ALLOW-LISTED beads config path. The destination is
  # permitted; the source endpoint is still the database leaving its
  # canonical location.
  git checkout -q worker/renamed-to-config
  git mv .beads/issues.jsonl .beads/config.yaml
  git commit -q -m 'worker: move the tracker onto an allow-listed config path'
) >/dev/null 2>&1

run_ci_guard() {
  # $1 = branch, $2 = base ref. Echoes EXIT=<n>; stderr lands in $CIERR.
  ( cd "$CIBOX" \
    && git checkout -q "$1" \
    && GITHUB_EVENT_NAME=pull_request sh scripts/check-beads-pr-boundary.sh "$2" \
       >/dev/null 2>"$CIERR" ) && echo "EXIT=0" || echo "EXIT=$?"
}

# 5a: THE REGRESSION. The clean branch never touched the tracker; the BASE
# did, after the fork. Two-endpoint selection reds this. Branch-delta
# selection passes it.
out=$(run_ci_guard worker/clean origin/main)
case "$out" in
  EXIT=0) pass "(5a) clean branch forked before a mayor beads checkpoint -> passes" ;;
  *)
    fail "(5a) FALSE RED: a clean branch was blamed for the base's beads checkpoint ($out)"
    cat "$CIERR" >&2 || true
    ;;
esac

# 5b: from that SAME diverged history, real contamination must still fail,
# naming the path and the branch-repair remedy. Without this, 5a could be
# satisfied by a guard that simply stopped working.
out=$(run_ci_guard worker/contaminated origin/main)
case "$out" in
  EXIT=0)
    fail "(5b) FALSE GREEN: a branch that committed the tracker was not blocked"
    ;;
  *)
    if grep -q '\.beads/issues\.jsonl' "$CIERR" \
       && grep -q 'STALE WORKER-SNAPSHOT' "$CIERR" \
       && grep -q 'git rebase -i' "$CIERR"; then
      pass "(5b) branch that DID commit the tracker -> refused, names path + remedy"
    else
      fail "(5b) refused, but the diagnostic is missing the path or the remedy"
      cat "$CIERR" >&2 || true
    fi
    ;;
esac

# 5f: RENAME ENDPOINTS (rf2-ajbgq). A rename presents as a delete plus an add,
# but git's default rename detection collapses the pair and `--name-only`
# prints only the destination. An exact rename OUT of `.beads/` therefore
# reached the classifier as an ordinary top-level file — permitted — while
# merging the PR deletes the tracker database from its canonical location.
#
# Pin the premise first: if git ever stops scoring this R100 the fixture would
# pass for the wrong reason, and a guard test that cannot fail is not a test.
rename_premise=$( cd "$CIBOX" \
  && git checkout -q worker/renamed-out \
  && git diff --name-status "$(git merge-base origin/main HEAD)" HEAD )
case "$rename_premise" in
  *R100*.beads/issues.jsonl*tracker-snapshot.jsonl*)
    pass "(5f-premise) the fixture really is a git-detected R100 rename" ;;
  *)
    fail "(5f-premise) fixture is not a detected rename, so 5f proves nothing"
    printf '%s\n' "$rename_premise" >&2
    ;;
esac

out=$(run_ci_guard worker/renamed-out origin/main)
case "$out" in
  EXIT=0)
    fail "(5f) FALSE GREEN: renaming .beads/issues.jsonl out of the tree was certified"
    ;;
  *)
    if grep -q '\.beads/issues\.jsonl' "$CIERR"; then
      pass "(5f) rename out of .beads/ -> refused, names the DELETED endpoint"
    else
      fail "(5f) refused, but the diagnostic never names the deleted .beads path"
      cat "$CIERR" >&2 || true
    fi
    ;;
esac

# 5g: the same move onto an ALLOW-LISTED destination. The allow-list covers
# `.beads/config.yaml`, so only the source endpoint can carry the refusal.
out=$(run_ci_guard worker/renamed-to-config origin/main)
case "$out" in
  EXIT=0)
    fail "(5g) FALSE GREEN: the tracker was renamed onto an allow-listed path unchallenged"
    ;;
  *)
    if grep -q '\.beads/issues\.jsonl' "$CIERR"; then
      pass "(5g) rename onto an allow-listed beads path -> refused on the old endpoint"
    else
      fail "(5g) refused, but not on the protected source path"
      cat "$CIERR" >&2 || true
    fi
    ;;
esac

# 5c: an unresolvable branch point FAILS CLOSED. A shallow clone that does
# not contain the fork is the usual cause, and a gate that cannot see the
# branch delta certifies nothing.
( cd "$CIBOX" && git checkout -q --orphan orphan/unrelated \
  && git commit -q --allow-empty -m 'unrelated history' ) >/dev/null 2>&1
out=$(run_ci_guard orphan/unrelated origin/main)
case "$out" in
  EXIT=0) fail "(5c) unresolvable branch point passed vacuously" ;;
  *)
    if grep -q 'no merge base' "$CIERR" && grep -q 'fetch-depth: 0' "$CIERR"; then
      pass "(5c) unresolvable branch point -> fails closed, names the likely cause"
    else
      fail "(5c) failed, but without a didactic diagnostic"
      cat "$CIERR" >&2 || true
    fi
    ;;
esac

# 5d: a missing base ref still fails closed (unchanged behaviour, re-pinned
# here so the merge-base work cannot quietly swallow it).
out=$( ( cd "$CIBOX" && git checkout -q worker/clean \
         && GITHUB_EVENT_NAME=pull_request sh scripts/check-beads-pr-boundary.sh \
            >/dev/null 2>"$CIERR" ) && echo "EXIT=0" || echo "EXIT=$?")
case "$out" in
  EXIT=0) fail "(5d) missing base ref passed vacuously" ;;
  *) pass "(5d) missing base ref -> fails closed" ;;
esac

# 5e: THE MAYOR CHECKPOINT PATH. On a non-pull_request event the guard must
# no-op, whatever is in the diff. The mayor commits the tracker to main on
# every heartbeat; blocking that would be worse than the bug.
out=$( ( cd "$CIBOX" && git checkout -q main \
         && GITHUB_EVENT_NAME=push sh scripts/check-beads-pr-boundary.sh \
            >"$CIERR" 2>&1 ) && echo "EXIT=0" || echo "EXIT=$?")
case "$out" in
  EXIT=0)
    if grep -q 'not a pull request' "$CIERR"; then
      pass "(5e) push event -> guard no-ops and says so (mayor checkpoint intact)"
    else
      fail "(5e) push event passed but printed no explanation"
    fi
    ;;
  *) fail "(5e) the mayor checkpoint path was blocked by the CI guard ($out)"; cat "$CIERR" >&2 ;;
esac

rm -rf "$CIBOX"
rm -f "$CIERR"

# ----------------------------------------------------------------------------
# Layer 6: the INSTALLER, end to end (rf2-zt65l).
#
# Layers 2 and 4 stage the hook by hand, deliberately, so that they test hook
# BEHAVIOUR rather than installation. That left the installer itself with no
# coverage — and rf2-zt65l is precisely an installation failure. The source
# hook grew the beads-boundary block on 2026-07-22; nobody re-ran the
# installer; every checkout kept running a 2026-06-01 copy that lacked the
# guard. For seven weeks the boundary was documented, tested, and absent.
#
# So this layer drives `scripts/install-git-hooks.sh` for real and asserts the
# property the whole exercise is about: after one install a checkout is
# guarded, a linked worktree created afterwards inherits that guard, an
# ordinary commit is untouched, and when the install later drifts something
# says so.
# ----------------------------------------------------------------------------

printf '\n[6] installer end-to-end: install, inherit, bite, detect drift\n'

IBOX=$(mktemp -d "${TMPDIR:-/tmp}/rf2-hookinstall-XXXXXX")
IERR="$IBOX/stderr.txt"
IREPO="$IBOX/repo"
IWORKER="$IBOX/worker"
INSTALLER="$REPO_ROOT/scripts/install-git-hooks.sh"

(
  mkdir -p "$IREPO/scripts/git-hooks/lib" "$IREPO/.beads"
  cd "$IREPO"
  git init -q -b main
  git config user.email 'hookinstall-test@example.invalid'
  git config user.name 'hookinstall-test'
  git config commit.gpgsign false
  # A faithful miniature of the repo: installer, hook sources and libs,
  # tracked, at the paths the installer and the hooks resolve against.
  cp "$INSTALLER" scripts/
  # The .ps1 sibling too: case 6k checks that the two installers certify each
  # other's work, which is the property that broke when the mayor-marker text
  # named whichever installer had written it.
  [ -f "$REPO_ROOT/scripts/install-git-hooks.ps1" ] \
    && cp "$REPO_ROOT/scripts/install-git-hooks.ps1" scripts/
  for h in post-merge post-rewrite pre-commit; do
    [ -f "$REPO_ROOT/scripts/git-hooks/$h" ] \
      && cp "$REPO_ROOT/scripts/git-hooks/$h" scripts/git-hooks/
  done
  cp "$REPO_ROOT"/scripts/git-hooks/lib/*.sh scripts/git-hooks/lib/
  printf '{"id":"seed","title":"seed"}\n' > .beads/issues.jsonl
  git add scripts .beads/issues.jsonl
  git commit -q -m 'seed: installer + hook sources'
) >/dev/null 2>&1

run_in_repo() {
  # $1 = directory, rest = command. Echoes EXIT=<n>; stderr lands in $IERR.
  d="$1"; shift
  ( cd "$d" && "$@" >/dev/null 2>"$IERR" ) && echo "EXIT=0" || echo "EXIT=$?"
}

# 6a: a fresh checkout installs clean, and --check then certifies it.
out=$(run_in_repo "$IREPO" sh scripts/install-git-hooks.sh)
case "$out" in
  EXIT=0) pass "(6a) installer runs clean on a fresh checkout" ;;
  *) fail "(6a) installer failed on a fresh checkout ($out)"; cat "$IERR" >&2 ;;
esac

out=$(run_in_repo "$IREPO" sh scripts/install-git-hooks.sh --check)
case "$out" in
  EXIT=0) pass "(6b) --check certifies the install it just made" ;;
  *) fail "(6b) --check rejected a fresh install ($out)"; cat "$IERR" >&2 ;;
esac

# 6c: every source block reached disk. The bead's failure mode was an
# install that looked fine because SOME blocks were present.
installed_ok=1
for spec in \
  "pre-commit:# --- BEGIN re-frame2 mayor commit boundary (rf2-ydl2p) ---" \
  "pre-commit:# --- BEGIN re-frame2 worker beads boundary (rf2-ia8o7) ---" \
  "pre-commit:# --- BEGIN re-frame2 beads truncation floor (rf2-or8te) ---" \
  "post-merge:# --- BEGIN re-frame2 MCP-staleness check (rf2-6jj3r) ---" \
  "post-merge:# --- BEGIN re-frame2 hook-install staleness check (rf2-zt65l) ---" \
  "post-rewrite:# --- BEGIN re-frame2 hook-install staleness check, rebase path (rf2-zt65l) ---"; do
  hook_file="$IREPO/.git/hooks/${spec%%:*}"
  marker="${spec#*:}"
  if ! grep -Fq "$marker" "$hook_file" 2>/dev/null; then
    installed_ok=0
    fail "(6c) block absent from installed ${spec%%:*}: $marker"
  fi
done
[ "$installed_ok" = "1" ] && pass "(6c) every registered block reached the installed hooks"

# 6d: a linked worktree created AFTER the install inherits the guard. This is
# the property that makes one install enough: worktrees share the primary's
# hooks directory (no core.hooksPath indirection), so nobody has to remember
# to re-install per worktree.
git -C "$IREPO" worktree add -q -b worker/hooks-test "$IWORKER" >/dev/null 2>&1
prim_hooks=$( cd "$IREPO" && cd "$(git rev-parse --git-path hooks)" && pwd )
wt_hooks=$( cd "$IWORKER" && cd "$(git rev-parse --git-path hooks)" && pwd )
if [ "$prim_hooks" = "$wt_hooks" ]; then
  pass "(6d) a worktree created after install shares the primary's hooks dir"
else
  fail "(6d) worktree hooks dir diverged: '$wt_hooks' vs '$prim_hooks'"
fi

# 6e: THE BITE. From that inherited install, a worker commit of the tracker
# database is refused, and the message names the file.
out=$(run_in_repo "$IWORKER" sh -c 'printf "{\"id\":\"worker-edit\"}\n" > .beads/issues.jsonl && git add .beads/issues.jsonl && git commit -q -m "worker: commit the tracker"')
case "$out" in
  EXIT=0) fail "(6e) FALSE GREEN: worker commit of .beads/issues.jsonl was allowed" ;;
  *)
    if grep -q '\.beads/issues\.jsonl' "$IERR"; then
      pass "(6e) inherited guard BITES: worker tracker commit refused, names the file"
    else
      fail "(6e) refused, but the diagnostic never names the tracker"
      cat "$IERR" >&2
    fi
    ;;
esac
git -C "$IWORKER" reset -q HEAD >/dev/null 2>&1 || true
git -C "$IWORKER" checkout -q -- .beads/issues.jsonl >/dev/null 2>&1 || true

# 6f: NO FALSE POSITIVE. An ordinary source commit from the same worktree is
# untouched. A guard that costs every commit gets bypassed with --no-verify,
# which is worse than no guard.
out=$(run_in_repo "$IWORKER" sh -c 'mkdir -p implementation/core/src && echo "(ns foo)" > implementation/core/src/foo.cljc && git add implementation/core/src/foo.cljc && git commit -q -m "worker: ordinary source commit"')
case "$out" in
  EXIT=0) pass "(6f) ordinary source commit from the same worktree passes" ;;
  *) fail "(6f) FALSE POSITIVE: an ordinary source commit was refused ($out)"; cat "$IERR" >&2 ;;
esac

# 6g: DRIFT IS DETECTED. Reproduce rf2-zt65l exactly — strip the beads block
# from the installed hook, leaving the others intact, as a stale copy would.
sed '/# --- BEGIN re-frame2 worker beads boundary (rf2-ia8o7) ---/,/# --- END re-frame2 worker beads boundary (rf2-ia8o7) ---/d' \
  "$IREPO/.git/hooks/pre-commit" > "$IBOX/pre-commit.stale"
cp "$IBOX/pre-commit.stale" "$IREPO/.git/hooks/pre-commit"
chmod +x "$IREPO/.git/hooks/pre-commit"

out=$(run_in_repo "$IREPO" sh scripts/install-git-hooks.sh --check)
case "$out" in
  EXIT=0) fail "(6g) --check certified a hook missing the beads boundary block" ;;
  *)
    if grep -q 'pre-commit' "$IERR"; then
      pass "(6g) --check detects the stale hook and names it"
    else
      fail "(6g) --check failed but did not name the stale hook"
      cat "$IERR" >&2
    fi
    ;;
esac

# 6h: and the post-merge advisory SAYS so, unprompted, on the next pull —
# the arm that would have caught this bead's seven-week gap.
out=$(run_in_repo "$IREPO" sh .git/hooks/post-merge)
if grep -q 'install-git-hooks.sh' "$IERR"; then
  pass "(6h) post-merge advisory reports the stale install and names the repair"
else
  fail "(6h) post-merge stayed silent about a stale install ($out)"
  cat "$IERR" >&2
fi

# 6i: re-running the installer repairs it, and the advisory then goes quiet.
# An advisory that fires on a healthy checkout is a nag, and nags get muted.
out=$(run_in_repo "$IREPO" sh scripts/install-git-hooks.sh)
case "$out" in
  EXIT=0)
    out=$(run_in_repo "$IREPO" sh scripts/install-git-hooks.sh --check)
    case "$out" in
      EXIT=0) pass "(6i) re-running the installer repairs the drift" ;;
      *) fail "(6i) install did not repair the drift ($out)"; cat "$IERR" >&2 ;;
    esac
    ;;
  *) fail "(6i) repair install failed ($out)"; cat "$IERR" >&2 ;;
esac

out=$(run_in_repo "$IREPO" sh .git/hooks/post-merge)
if [ -s "$IERR" ]; then
  fail "(6j) post-merge advisory fires on a healthy install (nag)"
  cat "$IERR" >&2
else
  pass "(6j) post-merge advisory silent on a healthy install"
fi

# 6k: THE TWO INSTALLERS AGREE. They write to one hooks directory and one
# mayor-marker, and each certifies what the other wrote. When the marker text
# named the installer that wrote it, running the .ps1 once made the .sh --check
# report "mayor-marker content drifted" for ever — and the post-merge advisory
# runs the .sh --check, so the whole apparatus degraded into a permanent nag.
# Skipped, not failed, where no PowerShell is installed: the .sh installer is
# the primary and must not need one.
PWSH=""
for candidate in pwsh powershell; do
  if command -v "$candidate" >/dev/null 2>&1; then PWSH="$candidate"; break; fi
done
if [ -z "$PWSH" ]; then
  printf '  SKIP  (6k) cross-installer parity: no pwsh/powershell on PATH\n'
else
  out=$(run_in_repo "$IREPO" "$PWSH" -ExecutionPolicy Bypass -File scripts/install-git-hooks.ps1)
  case "$out" in
    EXIT=0) : ;;
    *) fail "(6k) the .ps1 installer failed ($out)"; cat "$IERR" >&2 ;;
  esac
  out=$(run_in_repo "$IREPO" sh scripts/install-git-hooks.sh --check)
  case "$out" in
    EXIT=0) pass "(6k) the POSIX --check certifies what the .ps1 installer wrote" ;;
    *) fail "(6k) the installers disagree about a healthy install ($out)"; cat "$IERR" >&2 ;;
  esac
fi

git -C "$IREPO" worktree remove --force "$IWORKER" >/dev/null 2>&1 || true
rm -rf "$IBOX"

# ----------------------------------------------------------------------------
# Layer 7: the advisory on the REAL pull paths — rebase AND merge (rf2-zt65l).
#
# Layer 6 invokes `.git/hooks/post-merge` by hand. That proves the advisory
# TEXT is right and says nothing about whether git ever runs it, which is where
# the bead's audit reopen (PR #6921) found the hole:
#
#   `git pull --rebase` with a commit of your own performs a REAL rebase, and a
#   rebase never invokes post-merge. Reproduced in two throwaway clones: the
#   `--rebase` pull that landed hook drift printed NOTHING; the `--no-rebase`
#   control printed the repair warning. `git pull --rebase` is the completion
#   path AGENTS.md and CLAUDE.md mandate for every worker, so the advisory was
#   silent on the one path everybody takes.
#
# git's hook for that path is `post-rewrite` (argument `rebase`). Measured on
# git 2.53: diverged `--rebase` fires post-rewrite and NOT post-merge; a
# `--rebase` pull with no local commit fast-forwards through git's merge
# shortcut and fires post-merge. Between the two hooks, every pull that lands a
# change is covered — and this layer drives real `git pull`s to prove it,
# rather than calling hooks directly.
# ----------------------------------------------------------------------------

printf '\n[7] the advisory on real pulls: rebase (post-rewrite) and merge (post-merge)\n'

RBOX=$(mktemp -d "${TMPDIR:-/tmp}/rf2-hookpull-XXXXXX")
RERR="$RBOX/stderr.txt"
RUP="$RBOX/upstream"
RCL="$RBOX/clone"

# A faithful miniature of the repo, as the UPSTREAM this clone pulls from.
(
  mkdir -p "$RUP/scripts/git-hooks/lib"
  cd "$RUP"
  git init -q -b main
  git config user.email 'hookpull-test@example.invalid'
  git config user.name 'hookpull-test'
  git config commit.gpgsign false
  cp "$INSTALLER" scripts/
  for h in post-merge post-rewrite pre-commit; do
    [ -f "$REPO_ROOT/scripts/git-hooks/$h" ] \
      && cp "$REPO_ROOT/scripts/git-hooks/$h" scripts/git-hooks/
  done
  cp "$REPO_ROOT"/scripts/git-hooks/lib/*.sh scripts/git-hooks/lib/
  git add scripts
  git commit -q -m 'seed: installer + hook sources'
) >/dev/null 2>&1

git clone -q "$RUP" "$RCL" >/dev/null 2>&1
(
  cd "$RCL"
  git config user.email 'hookpull-test@example.invalid'
  git config user.name 'hookpull-test'
  git config commit.gpgsign false
) >/dev/null 2>&1

run_in_clone() {
  # Echoes EXIT=<n>; stderr (including git's own progress) lands in $RERR.
  ( cd "$RCL" && "$@" >/dev/null 2>"$RERR" ) && echo "EXIT=0" || echo "EXIT=$?"
}

# drift_upstream_hook_source TAG — land a change under scripts/git-hooks/ that
# leaves the installed COPIES stale, exactly as an ordinary upstream commit
# does. The inserted line is a no-op `:` statement inside a managed marker
# block, so the hook stays valid sh and `--check` sees the block differ.
drift_upstream_hook_source() {
  (
    cd "$RUP"
    awk -v tag="$1" '
      {print}
      /^# --- BEGIN re-frame2 MCP-staleness check \(rf2-6jj3r\) ---$/ {
        print ": " tag
      }' scripts/git-hooks/post-merge > post-merge.drifted
    mv -f post-merge.drifted scripts/git-hooks/post-merge
    git add scripts/git-hooks/post-merge
    git commit -q -m "upstream: change a managed hook block ($1)"
  ) >/dev/null 2>&1
}

local_commit() {
  # A commit of the clone's own — the precondition that makes `git pull
  # --rebase` do a real rebase instead of a fast-forward.
  #
  # `--no-verify` deliberately: the installer also dropped a mayor-marker in
  # this clone, so the rf2-ydl2p pre-commit block correctly treats it as a
  # mayor checkout and refuses ordinary source paths. That boundary is layer
  # 2's subject; here it is just scaffolding in the way, and bypassing it keeps
  # this layer measuring the one thing it is about — whether a pull that lands
  # hook drift says so.
  ( cd "$RCL" && echo "$1" > "$1.txt" && git add "$1.txt" \
      && git commit -q --no-verify -m "local: $1" ) >/dev/null 2>&1
}

# 7a: the clone installs clean, and --check certifies it. Everything after
# this measures a DRIFT that starts from a known-good install.
out=$(run_in_clone sh scripts/install-git-hooks.sh)
case "$out" in
  EXIT=0) : ;;
  *) fail "(7a) installer failed in the clone ($out)"; cat "$RERR" >&2 ;;
esac
out=$(run_in_clone sh scripts/install-git-hooks.sh --check)
case "$out" in
  EXIT=0) pass "(7a) the clone starts from a clean, certified install" ;;
  *) fail "(7a) --check rejected the clone's fresh install ($out)"; cat "$RERR" >&2 ;;
esac

# The audit's exact shape: one local commit, then a pull that lands hook drift.
local_commit mine
drift_upstream_hook_source rf2-drift-one
out=$(run_in_clone git pull --rebase origin main)
pull_err_rebase=$(cat "$RERR" 2>/dev/null || true)

# 7b: it really was a REBASE — the local commit was replayed on top of the
# upstream commit. If this ever fast-forwards instead, 7c stops testing the
# path the bead is about, so assert it rather than assume it.
rebase_ok=0
if [ "$out" = "EXIT=0" ] \
   && [ "$(git -C "$RCL" log -1 --format=%s 2>/dev/null)" = "local: mine" ] \
   && git -C "$RCL" merge-base --is-ancestor origin/main HEAD 2>/dev/null; then
  rebase_ok=1
  pass "(7b) git pull --rebase completed a real rebase (local commit replayed)"
else
  fail "(7b) the sandbox pull did not rebase as intended ($out)"
  printf '%s\n' "$pull_err_rebase" >&2
fi

# 7c: THE AUDIT FINDING. That completed rebase must report the drift it just
# landed. Before the post-rewrite arm existed this printed nothing at all.
if [ "$rebase_ok" = "1" ]; then
  case "$pull_err_rebase" in
    *install-git-hooks.sh*)
      pass "(7c) a rebasing pull reports the stale install and names the repair" ;;
    *)
      fail "(7c) a rebasing pull landed hook drift SILENTLY (no advisory)"
      printf '%s\n' "$pull_err_rebase" >&2 ;;
  esac
fi

# 7d: NO NAG. Repair, then take another rebasing pull that touches no hook
# source: the advisory must stay quiet. An advisory that fires on ordinary work
# gets muted, and a muted advisory is the bead all over again.
out=$(run_in_clone sh scripts/install-git-hooks.sh)
case "$out" in
  EXIT=0) : ;;
  *) fail "(7d) repair install failed ($out)"; cat "$RERR" >&2 ;;
esac
( cd "$RUP" && echo ordinary >> readme.txt && git add readme.txt \
    && git commit -q -m 'upstream: an ordinary source commit' ) >/dev/null 2>&1
local_commit mine-again
out=$(run_in_clone git pull --rebase origin main)
case "$(cat "$RERR" 2>/dev/null || true)" in
  *'[re-frame2]'*)
    fail "(7d) the advisory fired on a rebasing pull with a healthy install (nag)"
    cat "$RERR" >&2 ;;
  *)
    if [ "$out" = "EXIT=0" ]; then
      pass "(7d) a rebasing pull is silent when the install is current"
    else
      fail "(7d) the control pull failed ($out)"; cat "$RERR" >&2
    fi ;;
esac

# 7e: the MERGE path still works. The rebase arm is an addition, not a
# migration: `git pull` without --rebase, and `git pull --ff-only`, still go
# through post-merge, and this is the control the audit used.
drift_upstream_hook_source rf2-drift-two
local_commit mine-third
out=$(run_in_clone git pull --no-rebase --no-edit origin main)
case "$(cat "$RERR" 2>/dev/null || true)" in
  *install-git-hooks.sh*)
    pass "(7e) a merging pull still reports the stale install (post-merge arm intact)" ;;
  *)
    fail "(7e) the merge path lost its advisory ($out)"
    cat "$RERR" >&2 ;;
esac

rm -rf "$RBOX"

# ----------------------------------------------------------------------------
# Layer 8: the checkpoint helper (rf2-51uz1).
#
# The guards above stop the tracker database leaving the mayor checkout. This
# layer covers the other half of the same durability surface: what the mayor
# commits when it does check the tracker in.
#
# THE FAULT. `git checkout HEAD -- .beads` before a pull is correct — an
# uncommitted export makes the pull abort and freezes HEAD at a stale base. But
# a `bd close` after the last export-commit lives only in the database and in
# the working file, so the checkout reverts it, and a checkpoint that commits
# the working file writes that revert back. The close evaporates: rf2-5e8zv was
# reopened exactly this way, and commit e80786e007 records three more.
#
# `scripts/beads-checkpoint.sh` re-exports from the database instead of
# trusting the working file, which makes the revert unreachable. The cases
# below drive it against a stub `bd` so the assertions are hermetic and the
# real tracker is never touched.
#
# WHAT THE COMMIT CARRIES is the second axis (rf2-51uz1.1). `bd export` does not
# fix the order of the memory rows, so a checkpoint that copies the raw export
# buries the rows that changed under a few hundred relocation lines. 8f pins the
# reorder-ONLY export producing no commit at all; 8h pins the normal case — one
# real edit commits exactly that edit — and 8i pins the shrink guard that the
# ordering work must not cost.
#
# WHETHER THE MEMORIES RIDE AT ALL is the third (rf2-fifk0). bd v1.1.2 made the
# bare `bd export` EXCLUDE the `bd remember` rows that v1.0.3 always carried,
# so the stub models that contract and 8a asserts the committed tracker still
# carries its memories — a checkpoint that loses --include-memories fails here
# before it can silently drop every memory on main.
# ----------------------------------------------------------------------------

printf '\n[8] checkpoint helper: export from the database, never the working file\n'

CHECKPOINT="$REPO_ROOT/scripts/beads-checkpoint.sh"

if [ ! -f "$CHECKPOINT" ]; then
  fail "(8) scripts/beads-checkpoint.sh is missing"
else

CBOX=$(mktemp -d "${TMPDIR:-/tmp}/rf2-bdchk-XXXXXX")
CERR="$CBOX/stderr.txt"
COUT="$CBOX/stdout.txt"
CREPO="$CBOX/repo"
CBIN="$CBOX/bin"

# The "database": whatever the stub `bd` prints. The tests move this file
# around to say what the tracker knows, which is exactly the axis the fault
# turns on — database state versus working-file state.
mkdir -p "$CBIN" "$CREPO/scripts/git-hooks/lib" "$CREPO/.beads"
cat > "$CBIN/bd" <<EOF
#!/usr/bin/env sh
# Stub bd for the layer-8 checkpoint tests. Prints the "database" on stdout
# the way \`bd export\` does under bd v1.1.2: memory rows ride ONLY behind
# --include-memories (rf2-fifk0 — a bare export silently drops every one).
# Fails when told to.
if [ -f "$CBOX/bd-fails" ]; then
  printf 'stub bd: export failed\n' >&2
  exit 1
fi
for arg in "\$@"; do
  if [ "\$arg" = "--include-memories" ]; then
    cat "$CBOX/db.jsonl"
    exit 0
  fi
done
grep -v '"_type":"memory"' "$CBOX/db.jsonl" || :
EOF
chmod +x "$CBIN/bd"

# HEAD's copy of the tracker: two open issues and two memories.
{
  printf '{"_type":"issue","id":"rf2-a","status":"open"}\n'
  printf '{"_type":"issue","id":"rf2-b","status":"open"}\n'
  printf '{"_type":"memory","key":"m1","value":"one"}\n'
  printf '{"_type":"memory","key":"m2","value":"two"}\n'
} > "$CBOX/head.jsonl"

# The database, one `bd close rf2-b` later. This is the row whose survival the
# bead's acceptance criterion is about.
{
  printf '{"_type":"issue","id":"rf2-a","status":"open"}\n'
  printf '{"_type":"issue","id":"rf2-b","status":"closed"}\n'
  printf '{"_type":"memory","key":"m1","value":"one"}\n'
  printf '{"_type":"memory","key":"m2","value":"two"}\n'
} > "$CBOX/db-closed.jsonl"

(
  cd "$CREPO"
  git init -q -b main
  git config user.email 'bdchk-test@example.invalid'
  git config user.name 'bdchk-test'
  git config commit.gpgsign false
  cp "$CHECKPOINT" scripts/
  cp "$REPO_ROOT/scripts/git-hooks/lib/check-beads-boundary.sh" scripts/git-hooks/lib/
  cp -f "$CBOX/head.jsonl" .beads/issues.jsonl
  git add scripts .beads/issues.jsonl
  git commit -q -m 'seed: tracker at HEAD, two open issues'
) >/dev/null 2>&1

run_checkpoint() {
  # $1 = directory; rest = args to the helper. Echoes EXIT=<n>.
  d="$1"; shift
  ( cd "$d" && PATH="$CBIN:$PATH" sh scripts/beads-checkpoint.sh "$@" \
      >"$COUT" 2>"$CERR" ) && echo "EXIT=0" || echo "EXIT=$?"
}

# 8a: THE ACCEPTANCE. A close that exists only in the database must survive the
# standard pre-pull cleanup. Revert the working file exactly as CLAUDE.md's
# `git checkout HEAD -- .beads` does, then checkpoint: the commit must carry the
# close, because it came from the database and not from the reverted file.
cp -f "$CBOX/db-closed.jsonl" "$CBOX/db.jsonl"
git -C "$CREPO" checkout -q HEAD -- .beads
out=$(run_checkpoint "$CREPO")
committed=$(git -C "$CREPO" show HEAD:.beads/issues.jsonl 2>/dev/null || true)
case "$out" in
  EXIT=0)
    case "$committed" in
      *'"id":"rf2-b","status":"closed"'*)
        pass "(8a) a close survives the pre-pull checkout: the checkpoint re-exported it" ;;
      *)
        fail "(8a) the close EVAPORATED: the checkpoint committed the reverted file"
        printf '%s\n' "$committed" >&2 ;;
    esac
    # The memories must ride the same commit (rf2-fifk0). The stub models bd
    # v1.1.2, where only `bd export --include-memories` carries them — a
    # checkpoint that goes back to the bare export commits an issues-only
    # tracker here and this assertion catches it.
    if [ "$(printf '%s\n' "$committed" | grep -c '"_type":"memory"')" = "2" ]; then
      pass "(8a) and both memory rows survive: the export runs --include-memories"
    else
      fail "(8a) the commit DROPPED memory rows: the export is running bare (rf2-fifk0)"
      printf '%s\n' "$committed" >&2
    fi ;;
  *) fail "(8a) checkpoint failed ($out)"; cat "$CERR" >&2 ;;
esac

# 8b: --pre-pull REFUSES while the working export carries state HEAD lacks, and
# names the fault and the remedy. This is the warning arm: the operator gets
# told before the checkout, not after the close is gone.
printf '{"_type":"issue","id":"rf2-c","status":"open"}\n' >> "$CREPO/.beads/issues.jsonl"
out=$(run_checkpoint "$CREPO" --pre-pull)
case "$out" in
  EXIT=0) fail "(8b) --pre-pull certified a working export that is ahead of HEAD" ;;
  *)
    if grep -q 'beads-checkpoint' "$CERR" && grep -q 'AHEAD of HEAD' "$CERR"; then
      pass "(8b) --pre-pull refuses a working export ahead of HEAD, names the remedy"
    else
      fail "(8b) --pre-pull refused but said nothing useful"
      cat "$CERR" >&2
    fi ;;
esac

# 8c: and it is SILENT once the tracker is checkpointed. A pre-flight check that
# fires every tick is one the loop learns to ignore.
git -C "$CREPO" checkout -q HEAD -- .beads
out=$(run_checkpoint "$CREPO" --pre-pull)
case "$out" in
  EXIT=0)
    if [ -s "$CERR" ]; then
      fail "(8c) --pre-pull passed but still printed a warning"; cat "$CERR" >&2
    else
      pass "(8c) --pre-pull is silent when HEAD already carries the tracker"
    fi ;;
  *) fail "(8c) --pre-pull refused a checkpointed tracker ($out)"; cat "$CERR" >&2 ;;
esac

# 8d: A FAILED EXPORT COMMITS NOTHING. If the database cannot be read, the
# working file is not a fallback — that is the whole point.
before=$(git -C "$CREPO" rev-parse HEAD)
: > "$CBOX/bd-fails"
out=$(run_checkpoint "$CREPO")
after=$(git -C "$CREPO" rev-parse HEAD)
rm -f "$CBOX/bd-fails"
case "$out" in
  EXIT=0) fail "(8d) a failed bd export was treated as success" ;;
  *)
    if [ "$before" = "$after" ] && grep -q 'untouched' "$CERR"; then
      pass "(8d) a failed export commits nothing and says the tracker is untouched"
    else
      fail "(8d) failed export left the repo in an unexpected state"
      cat "$CERR" >&2
    fi ;;
esac

# 8e: AN EMPTY EXPORT IS REFUSED. A `git add` that caught the JSONL mid-rewrite
# put an empty tracker on main once already (2026-06-10, commit 7aea52459).
before=$(git -C "$CREPO" rev-parse HEAD)
: > "$CBOX/db.jsonl"
out=$(run_checkpoint "$CREPO")
after=$(git -C "$CREPO" rev-parse HEAD)
case "$out" in
  EXIT=0) fail "(8e) an empty export was checkpointed" ;;
  *)
    if [ "$before" = "$after" ] && grep -q '0 rows' "$CERR"; then
      pass "(8e) an empty export is refused, naming the row count"
    else
      fail "(8e) empty export was rejected for the wrong reason"
      cat "$CERR" >&2
    fi ;;
esac

# 8f: NO CHURN COMMIT. `bd export` does not fix the order of the memory rows,
# so a reorder is not a change. If it committed one, every heartbeat would
# produce a few hundred lines of diff that mean nothing — and this repo has
# already learned what committed churn does to a merge queue.
before=$(git -C "$CREPO" rev-parse HEAD)
{
  git -C "$CREPO" show HEAD:.beads/issues.jsonl | grep '"_type":"issue"'
  git -C "$CREPO" show HEAD:.beads/issues.jsonl | grep '"_type":"memory"' | sort -r
} > "$CBOX/db.jsonl"
out=$(run_checkpoint "$CREPO")
after=$(git -C "$CREPO" rev-parse HEAD)
case "$out" in
  EXIT=0)
    if [ "$before" = "$after" ] && grep -q 'nothing to checkpoint' "$COUT"; then
      pass "(8f) a memory reorder is not a change: no churn commit"
    else
      fail "(8f) a pure reorder produced a commit"
      cat "$COUT" >&2
    fi ;;
  *) fail "(8f) checkpoint failed on a reordered export ($out)"; cat "$CERR" >&2 ;;
esac

# 8g: WORKER WORKTREES ARE REFUSED. The tracker database is the mayor
# checkout's to commit; the helper derives that the same way the pre-commit
# guard does, so one rule has one home.
CWORKER="$CBOX/worker"
git -C "$CREPO" worktree add -q -b worker/bdchk-test "$CWORKER" >/dev/null 2>&1
cp -f "$CBOX/db-closed.jsonl" "$CBOX/db.jsonl"
out=$(run_checkpoint "$CWORKER")
case "$out" in
  EXIT=0) fail "(8g) the helper checkpointed the tracker from a worker worktree" ;;
  *)
    if grep -q 'mayor checkout' "$CERR"; then
      pass "(8g) a worker worktree is refused, and told whose job it is"
    else
      fail "(8g) refused in a worker worktree, but not for the stated reason"
      cat "$CERR" >&2
    fi ;;
esac

# The READ-ONLY arm is not gated (rf2-fifk0): any worktree may ask whether
# clearing `.beads` is safe before its own pull. The COMMIT is the mayor's;
# the question is everyone's. The fresh worktree matches its HEAD, so the
# answer here is a silent yes.
out=$(run_checkpoint "$CWORKER" --pre-pull)
case "$out" in
  EXIT=0)
    pass "(8g) but --pre-pull still answers from a worker worktree: read-only is not gated" ;;
  *)
    fail "(8g) --pre-pull refused to answer from a worker worktree ($out)"
    cat "$CERR" >&2 ;;
esac
git -C "$CREPO" worktree remove --force "$CWORKER" >/dev/null 2>&1 || true

# 8h: A REAL CHANGE COMMITS THE REAL CHANGE ONLY (rf2-51uz1.1). 8f covers the
# reorder-ONLY export. The case that actually bit was the NORMAL one: a genuine
# row edit makes the checkpoint commit, and the raw export then carried every
# unrelated memory reorder along with it — 200 of 211 staged additions were
# byte-identical to removed lines, so the eleven that mattered were invisible.
#
# Here rf2-a closes (the one real edit) while the four untouched memories are
# shuffled. The commit must show the two rf2-a lines and NOTHING else: no
# memory row may appear on either side of the diff.
before=$(git -C "$CREPO" rev-parse HEAD)
{
  printf '{"_type":"issue","id":"rf2-a","status":"open"}\n'
  printf '{"_type":"issue","id":"rf2-b","status":"closed"}\n'
  printf '{"_type":"memory","key":"m1","value":"one"}\n'
  printf '{"_type":"memory","key":"m2","value":"two"}\n'
  printf '{"_type":"memory","key":"m3","value":"three"}\n'
  printf '{"_type":"memory","key":"m4","value":"four"}\n'
} > "$CBOX/db.jsonl"
out=$(run_checkpoint "$CREPO")
case "$out" in
  EXIT=0) : ;;
  *) fail "(8h-seed) could not establish the four-memory baseline ($out)"; cat "$CERR" >&2 ;;
esac

# Now: rf2-a closes, and the four untouched memories come back in a different
# order — exactly what `bd export` does on every invocation.
{
  printf '{"_type":"issue","id":"rf2-a","status":"closed"}\n'
  printf '{"_type":"issue","id":"rf2-b","status":"closed"}\n'
  printf '{"_type":"memory","key":"m3","value":"three"}\n'
  printf '{"_type":"memory","key":"m1","value":"one"}\n'
  printf '{"_type":"memory","key":"m4","value":"four"}\n'
  printf '{"_type":"memory","key":"m2","value":"two"}\n'
} > "$CBOX/db.jsonl"
before=$(git -C "$CREPO" rev-parse HEAD)
out=$(run_checkpoint "$CREPO")
after=$(git -C "$CREPO" rev-parse HEAD)
case "$out" in
  EXIT=0)
    if [ "$before" = "$after" ]; then
      fail "(8h) a real row edit produced no commit"
      cat "$COUT" >&2
    else
      # Diff body only: added/removed rows, not the +++/--- headers.
      cdiff=$(git -C "$CREPO" diff "$before" "$after" -- .beads/issues.jsonl \
                | grep '^[+-]' | grep -v '^[+-][+-]')
      churn=$(printf '%s\n' "$cdiff" | grep '"_type":"memory"' || true)
      real=$(printf '%s\n' "$cdiff" | grep '"id":"rf2-a"' || true)
      if [ -n "$churn" ]; then
        fail "(8h) the commit carried memory-row churn alongside the real edit"
        printf '%s\n' "$churn" >&2
      elif [ -z "$real" ]; then
        fail "(8h) the commit did not carry the real edit"
        printf '%s\n' "$cdiff" >&2
      elif [ "$(printf '%s\n' "$cdiff" | awk 'END{print NR}')" != "2" ]; then
        fail "(8h) the commit carried more than the two rf2-a lines"
        printf '%s\n' "$cdiff" >&2
      else
        pass "(8h) a real edit commits ONLY the changed rows; shuffled memories do not move"
      fi
      # The committed file must still be the export's row SET, whole — a
      # cosmetic reordering that loses a row would be the worse bug.
      if [ "$(git -C "$CREPO" show HEAD:.beads/issues.jsonl | LC_ALL=C sort)" \
           = "$(LC_ALL=C sort < "$CBOX/db.jsonl")" ]; then
        pass "(8h) and the committed rows are the export's rows exactly, none lost"
      else
        fail "(8h) the minimal-diff rewrite changed the committed ROW SET"
      fi
    fi ;;
  *) fail "(8h) checkpoint failed on a real edit + reordered memories ($out)"; cat "$CERR" >&2 ;;
esac

# 8i: THE SHRINK GUARD STILL BITES. Not a new behaviour — a regression net for
# the one it would be tempting to relax while making 8h pass. It fired for real
# on a deliberate `bd gc` (2642 -> 2372 rows) and correctly refused, sending the
# operator to a hand commit. A cosmetic-ordering change must not cost that.
before=$(git -C "$CREPO" rev-parse HEAD)
printf '{"_type":"issue","id":"rf2-a","status":"closed"}\n' > "$CBOX/db.jsonl"
out=$(run_checkpoint "$CREPO")
after=$(git -C "$CREPO" rev-parse HEAD)
case "$out" in
  EXIT=0) fail "(8i) a 6-of-7-row shrink was checkpointed; the guard is gone" ;;
  *)
    if [ "$before" = "$after" ] && grep -q 'tenth of the' "$CERR" \
       && grep -q 'untouched' "$CERR"; then
      pass "(8i) a >1/10 shrink is still refused, and the tracker is untouched"
    else
      fail "(8i) the shrink was refused for the wrong reason, or the tree moved"
      cat "$CERR" >&2
    fi ;;
esac

# ----------------------------------------------------------------------------
# 8j-8l: EQUAL COUNTS ARE NOT EQUALITY (rf2-rjqtj).
#
# 8i's floor answers "is the export big enough?". It cannot answer "does the
# export still contain what HEAD contains?" — and there is a second writer that
# makes the difference matter: the merged-PR audit commits issue rows straight
# to Git, and `git pull` brings other checkouts' rows the same way. When both
# sides move they diverge one row for one row, the count does not budge, and
# the floor waves through an export that deletes the Git-only rows.
#
# OBSERVED: commit 667c744dc875 passed at 1938 == 1938 and still dropped
# rf2-3jw04, rf2-jv36i and rf2-lhdp0 and reverted rf2-2rtt6.52/.63.
#
# The fixture is the bead's own acceptance, and every row below is load-bearing:
#
#   rf2-a   unchanged on both sides
#   rf2-b   NEWER ON GIT   — closed at 03:00; the export still has it open
#   rf2-c   NEWER ON DOLT  — closed at 02:00; HEAD still has it open
#   rf2-g1  GIT ONLY       — the export has never heard of it
#   rf2-d1  DOLT ONLY      — HEAD has never heard of it
#
# Four issue rows plus two memories a side: six against six. Neither side's
# facts may be lost, and 8j proves the refusal while 8k proves the recovery.
# ----------------------------------------------------------------------------
{
  printf '{"_type":"issue","id":"rf2-a","status":"open","updated_at":"2026-08-01T00:00:00Z"}\n'
  printf '{"_type":"issue","id":"rf2-b","status":"closed","updated_at":"2026-08-02T03:00:00Z"}\n'
  printf '{"_type":"issue","id":"rf2-c","status":"open","updated_at":"2026-08-01T00:00:00Z"}\n'
  printf '{"_type":"issue","id":"rf2-g1","status":"open","updated_at":"2026-08-02T01:00:00Z"}\n'
  printf '{"_type":"memory","key":"m1","value":"one"}\n'
  printf '{"_type":"memory","key":"m2","value":"two"}\n'
} > "$CBOX/head-diverged.jsonl"
{
  printf '{"_type":"issue","id":"rf2-a","status":"open","updated_at":"2026-08-01T00:00:00Z"}\n'
  printf '{"_type":"issue","id":"rf2-b","status":"open","updated_at":"2026-08-01T12:00:00Z"}\n'
  printf '{"_type":"issue","id":"rf2-c","status":"closed","updated_at":"2026-08-02T02:00:00Z"}\n'
  printf '{"_type":"issue","id":"rf2-d1","status":"open","updated_at":"2026-08-02T01:30:00Z"}\n'
  printf '{"_type":"memory","key":"m1","value":"one"}\n'
  printf '{"_type":"memory","key":"m2","value":"two"}\n'
} > "$CBOX/db-diverged.jsonl"

(
  cd "$CREPO"
  cp -f "$CBOX/head-diverged.jsonl" .beads/issues.jsonl
  git add -- .beads/issues.jsonl
  git commit -q -m 'seed: HEAD and the database have diverged at equal row count'
) >/dev/null 2>&1

# 8j: THE ACCEPTANCE. Equal counts, disjoint one-for-one substitution, one
# newer state on each side. The only safe answer is to refuse and name what
# would be lost — with the FIELDS, because an id-set comparison proves presence
# and nothing more (an interrupted Dolt GC reverted a close in the field while
# every id stayed intact).
cp -f "$CBOX/db-diverged.jsonl" "$CBOX/db.jsonl"
before=$(git -C "$CREPO" rev-parse HEAD)
out=$(run_checkpoint "$CREPO")
after=$(git -C "$CREPO" rev-parse HEAD)
case "$out" in
  EXIT=0)
    fail "(8j) an equal-count divergence was checkpointed: rf2-g1 and rf2-b's close are GONE"
    cat "$COUT" >&2 ;;
  *)
    if [ "$before" != "$after" ]; then
      fail "(8j) the divergence was refused but something was still committed"
    elif ! grep -q 'EQUAL COUNTS ARE NOT EQUALITY' "$CERR"; then
      fail "(8j) refused, but not for the equal-count reason"
      cat "$CERR" >&2
    elif ! grep -q 'GONE .*rf2-g1' "$CERR"; then
      fail "(8j) did not name the Git-only bead that would be DELETED"
      cat "$CERR" >&2
    elif ! grep -q 'REVERT .*rf2-b' "$CERR"; then
      fail "(8j) did not name the Git-newer bead that would be REVERTED"
      cat "$CERR" >&2
    elif ! grep -q '2026-08-02T03:00:00Z' "$CERR"; then
      fail "(8j) named the ids but not the FIELDS; presence is not state"
      cat "$CERR" >&2
    elif grep -qE 'rf2-c|rf2-d1' "$CERR"; then
      fail "(8j) cried wolf over the DOLT-side facts; forward motion is not a divergence"
      cat "$CERR" >&2
    elif ! grep -q 'rf2-g1' "$CREPO/.beads/issues.jsonl"; then
      fail "(8j) the tracker was overwritten despite the refusal"
    else
      pass "(8j) an equal-count divergence is refused, naming both lost facts and their fields"
    fi ;;
esac

# 8j-remedy: a refusal nobody can act on gets bypassed. The message names a file
# holding exactly the Git-only and Git-newer rows, so `bd import` of it is the
# whole recovery — the bead's own verified mechanism, and the reason this guard
# does not need a sync service.
remedy=$(sed -n 's/^ *bd import \(.*\)$/\1/p' "$CERR" | head -1)
if [ -n "$remedy" ] && [ -s "$remedy" ]; then
  if [ "$(awk 'END{print NR}' "$remedy")" = "2" ] \
     && grep -q 'rf2-g1' "$remedy" && grep -q 'rf2-b' "$remedy"; then
    pass "(8j) and it stages exactly the two rows an import must carry"
  else
    fail "(8j) the remedy file did not hold exactly the Git-only/Git-newer rows"
    cat "$remedy" >&2
  fi
else
  fail "(8j) no remedy file was written, so the refusal is not actionable"
fi
if [ -n "$remedy" ]; then rm -f "$remedy"; fi

# 8k: AND THE RECOVERY COMPLETES. The operator runs that import, so the database
# becomes the UNION. The next checkpoint must commit, and the committed tracker
# must carry ALL FOUR facts — the bead's "neither fact may be lost", end to end.
{
  printf '{"_type":"issue","id":"rf2-a","status":"open","updated_at":"2026-08-01T00:00:00Z"}\n'
  printf '{"_type":"issue","id":"rf2-b","status":"closed","updated_at":"2026-08-02T03:00:00Z"}\n'
  printf '{"_type":"issue","id":"rf2-c","status":"closed","updated_at":"2026-08-02T02:00:00Z"}\n'
  printf '{"_type":"issue","id":"rf2-d1","status":"open","updated_at":"2026-08-02T01:30:00Z"}\n'
  printf '{"_type":"issue","id":"rf2-g1","status":"open","updated_at":"2026-08-02T01:00:00Z"}\n'
  printf '{"_type":"memory","key":"m1","value":"one"}\n'
  printf '{"_type":"memory","key":"m2","value":"two"}\n'
} > "$CBOX/db.jsonl"
before=$(git -C "$CREPO" rev-parse HEAD)
out=$(run_checkpoint "$CREPO")
after=$(git -C "$CREPO" rev-parse HEAD)
case "$out" in
  EXIT=0)
    committed=$(git -C "$CREPO" show HEAD:.beads/issues.jsonl)
    if [ "$before" = "$after" ]; then
      fail "(8k) the post-import checkpoint committed nothing"
    elif ! printf '%s\n' "$committed" | grep -q 'rf2-g1'; then
      fail "(8k) the Git-only bead was lost after the import"
    elif ! printf '%s\n' "$committed" | grep -q '"id":"rf2-b","status":"closed"'; then
      fail "(8k) the Git-side close was reverted after the import"
    elif ! printf '%s\n' "$committed" | grep -q 'rf2-d1'; then
      fail "(8k) the Dolt-only bead was lost"
    elif ! printf '%s\n' "$committed" | grep -q '"id":"rf2-c","status":"closed"'; then
      fail "(8k) the Dolt-side close was lost"
    elif ! printf '%s\n' "$committed" | grep -q '"key":"m1"'; then
      fail "(8k) the memory rows did not ride along"
    else
      pass "(8k) after the import the checkpoint commits, and neither side's facts are lost"
    fi ;;
  *) fail "(8k) the checkpoint still refused a database that is now a superset ($out)"
     cat "$CERR" >&2 ;;
esac

# 8l: THE AMBIGUOUS ROW. Same `updated_at`, different `status`: neither side is
# newer, so neither may be chosen automatically — and no import can adjudicate a
# tie, so none is offered. This is the class the field data insisted on: an
# id-set comparison would call it clean.
sed 's/"id":"rf2-c","status":"closed"/"id":"rf2-c","status":"open"/' \
  "$CBOX/db.jsonl" > "$CBOX/db-ambig.jsonl"
cp -f "$CBOX/db-ambig.jsonl" "$CBOX/db.jsonl"
before=$(git -C "$CREPO" rev-parse HEAD)
out=$(run_checkpoint "$CREPO")
after=$(git -C "$CREPO" rev-parse HEAD)
case "$out" in
  EXIT=0) fail "(8l) a same-timestamp status conflict was silently resolved by the export" ;;
  *)
    if [ "$before" != "$after" ]; then
      fail "(8l) the ambiguous row was refused but something was committed"
    elif ! grep -q 'AMBIG .*rf2-c' "$CERR"; then
      fail "(8l) refused, but did not name the row as ambiguous"
      cat "$CERR" >&2
    elif grep -q 'bd import' "$CERR"; then
      fail "(8l) offered an import for a tie an import cannot adjudicate"
      cat "$CERR" >&2
    else
      pass "(8l) a same-timestamp status conflict is refused, and no import is offered"
    fi ;;
esac

rm -rf "$CBOX"

fi

# ----------------------------------------------------------------------------
# Layer 9: the truncation floor in the pre-commit hook (rf2-or8te).
#
# Layer 8 proves the CHECKPOINT HELPER refuses an empty export. It has refused
# one since 2026-06-10. Twice, that was not enough, because the commit that
# emptied the tracker never went through the helper:
#
#     2026-06-10  7aea52459   7172 rows deleted
#     2026-07-26  4d8042d80d  2573 rows deleted
#
# Both were a plain `git add` from the MAYOR checkout — the one place the
# rf2-ia8o7 block deliberately no-ops, because committing the tracker there is
# the intended flow. So the floor now also lives in the hook, and this layer
# drives it from the PRIMARY worktree of the layer-2 sandbox: the same
# checkout, the same guard-blind path, the same `git add`.
#
# The three cases the bead names are 9a (a truncated export is refused), 9c (a
# genuine one passes) and 9d (a real mass delete gets through the named
# escape). 9b pins that the floor is a FLOOR and not "any shrink", and 9e/9f
# pin the two no-false-positive cases — a fresh checkout whose HEAD carries no
# rows, and a commit that does not touch the tracker at all. A guard that costs
# ordinary commits gets bypassed with --no-verify, which is worse than none.
# ----------------------------------------------------------------------------

printf '\n[9] truncation floor: the mayor checkout cannot commit an emptied tracker\n'

TERR=/tmp/rf2-pc-trunc.err

# write_tracker COUNT PATH [TAG] — COUNT distinct JSONL rows.
write_tracker() {
  awk -v n="$1" -v tag="${3:-seed}" \
    'BEGIN{for(i=1;i<=n;i++) printf "{\"_type\":\"issue\",\"id\":\"%s-%04d\"}\n", tag, i}' \
    > "$2"
}

# stage_tracker COUNT [TAG] — write and `git add` in the mayor checkout,
# exactly as the two incidents did. Verifies the mutation actually landed in
# the INDEX before any verdict is read: a planted edit that silently failed to
# apply is indistinguishable from a guard that missed the defect.
stage_tracker() {
  write_tracker "$1" "$MAYOR/.beads/issues.jsonl" "${2:-seed}"
  git -C "$MAYOR" add .beads/issues.jsonl
  staged_rows=$(git -C "$MAYOR" show :.beads/issues.jsonl 2>/dev/null | awk 'END{print NR}')
  if [ "$staged_rows" != "$1" ]; then
    fail "(9-setup) index carries $staged_rows rows, expected $1 — the mutation did not apply"
    return 1
  fi
  return 0
}

trunc_commit() {
  # Commit whatever is staged in the mayor checkout. Extra args pass through
  # (that is how 9d exercises --no-verify).
  cd "$MAYOR"
  git commit -q -m 'mayor: tracker' "$@" -- .beads/issues.jsonl
}

# Seed a substantial HEAD. A GROWTH from the 1-row tracker layer 4 left behind
# must not be refused, so this doubles as the first control.
if stage_tracker 200 base; then
  rc_s=$(mktemp); run_scenario "$rc_s" trunc_commit
  head_rows=$(git -C "$MAYOR" show HEAD:.beads/issues.jsonl 2>/dev/null | awk 'END{print NR}')
  if [ "$(cat "$rc_s")" = "0" ] && [ "$head_rows" = "200" ]; then
    pass "(9-seed) a 1 -> 200 row growth commits: the floor only looks downward"
  else
    fail "(9-seed) could not seed a 200-row HEAD (exit $(cat "$rc_s"), HEAD $head_rows rows)"
    cat /tmp/rf2-pc-smoke.err >&2 || true
  fi
  rm -f "$rc_s"
fi

# 9a: THE INCIDENT. An emptied export, staged with a plain `git add` in the
# primary worktree. Refused, and the message has to carry four things: the two
# row counts, the regeneration rule, the repair, and the named escape.
before=$(git -C "$MAYOR" rev-parse HEAD)
if stage_tracker 0 empty; then
  rc_t=$(mktemp); ( trunc_commit 2>"$TERR" ) && echo 0 > "$rc_t" || echo $? > "$rc_t"
  after=$(git -C "$MAYOR" rev-parse HEAD)
  if [ "$(cat "$rc_t")" = "0" ]; then
    fail "(9a) FALSE GREEN: a 0-row tracker was committed over a 200-row HEAD"
  elif [ "$before" != "$after" ]; then
    fail "(9a) refused, but HEAD moved anyway"
  elif grep -q 'TRUNCATED' "$TERR" \
       && grep -q 'staged: 0 rows' "$TERR" \
       && grep -q 'HEAD:   200 rows' "$TERR" \
       && grep -q 'REGENERATION event' "$TERR" \
       && grep -q 'TIME-TRAVELS' "$TERR" \
       && grep -q 'beads-checkpoint.sh' "$TERR" \
       && grep -q 'git commit --no-verify' "$TERR"; then
    pass "(9a) an emptied tracker is refused in the PRIMARY worktree, with counts, rule, repair, escape"
  else
    fail "(9a) refused, but the diagnostic is incomplete"
    cat "$TERR" >&2
  fi
  rm -f "$rc_t"
fi
( cd "$MAYOR" && git reset -q HEAD -- .beads/issues.jsonl && git checkout -q HEAD -- .beads ) || true

# 9b: A FLOOR, NOT A RATCHET. 179 of 200 rows loses more than a tenth and is
# refused; the empty-only regeneration stanza must NOT appear, because this is
# not an empty export and calling it one would be wrong advice.
before=$(git -C "$MAYOR" rev-parse HEAD)
if stage_tracker 179 base; then
  rc_t=$(mktemp); ( trunc_commit 2>"$TERR" ) && echo 0 > "$rc_t" || echo $? > "$rc_t"
  after=$(git -C "$MAYOR" rev-parse HEAD)
  if [ "$(cat "$rc_t")" = "0" ]; then
    fail "(9b) FALSE GREEN: a 179-of-200 shrink was committed"
  elif [ "$before" != "$after" ]; then
    fail "(9b) refused, but HEAD moved anyway"
  elif grep -q 'staged: 179 rows' "$TERR" \
       && ! grep -q 'REGENERATION event' "$TERR"; then
    pass "(9b) a >1/10 shrink is refused, and is not mislabelled an empty export"
  else
    fail "(9b) refused, but with the wrong diagnostic"
    cat "$TERR" >&2
  fi
  rm -f "$rc_t"
fi
( cd "$MAYOR" && git reset -q HEAD -- .beads/issues.jsonl && git checkout -q HEAD -- .beads ) || true

# 9c: A GENUINE EXPORT PASSES. 180 of 200 is exactly the threshold the
# checkpoint script uses (export_rows * 10 < head_rows * 9), so this pins the
# boundary rather than a comfortable margin.
before=$(git -C "$MAYOR" rev-parse HEAD)
if stage_tracker 180 base; then
  rc_t=$(mktemp); ( trunc_commit 2>"$TERR" ) && echo 0 > "$rc_t" || echo $? > "$rc_t"
  after=$(git -C "$MAYOR" rev-parse HEAD)
  head_rows=$(git -C "$MAYOR" show HEAD:.beads/issues.jsonl 2>/dev/null | awk 'END{print NR}')
  if [ "$(cat "$rc_t")" = "0" ] && [ "$before" != "$after" ] && [ "$head_rows" = "180" ]; then
    pass "(9c) a genuine export at exactly 9/10 of HEAD commits normally"
  else
    fail "(9c) FALSE POSITIVE: an export at the threshold was refused (exit $(cat "$rc_t"), HEAD $head_rows rows)"
    cat "$TERR" >&2
  fi
  rm -f "$rc_t"
fi

# 9d: THE ESCAPE. A genuine mass delete is the operator's call, not the hook's.
# Re-seed a full HEAD, then empty it through the escape the message names.
if stage_tracker 200 base; then
  rc_t=$(mktemp); ( trunc_commit 2>"$TERR" ) && echo 0 > "$rc_t" || echo $? > "$rc_t"
  rm -f "$rc_t"
fi
before=$(git -C "$MAYOR" rev-parse HEAD)
if stage_tracker 0 empty; then
  rc_t=$(mktemp); ( trunc_commit --no-verify 2>"$TERR" ) && echo 0 > "$rc_t" || echo $? > "$rc_t"
  after=$(git -C "$MAYOR" rev-parse HEAD)
  head_rows=$(git -C "$MAYOR" show HEAD:.beads/issues.jsonl 2>/dev/null | awk 'END{print NR}')
  if [ "$(cat "$rc_t")" = "0" ] && [ "$before" != "$after" ] && [ "$head_rows" = "0" ]; then
    pass "(9d) the named escape works: --no-verify lands a deliberate mass delete"
  else
    fail "(9d) the escape named in the refusal message does not work (exit $(cat "$rc_t"), HEAD $head_rows rows)"
    cat "$TERR" >&2
  fi
  rm -f "$rc_t"
fi

# 9e: NO NAG ON A FRESH CHECKOUT. A HEAD with no rows can lose none, so a
# first-ever add of a small or empty tracker must not be refused — otherwise
# every fresh clone meets the guard before it meets the tracker.
#
# The 0-row HEAD is established here rather than inherited from 9d. Sharing
# 9d's side effect made a 9d regression cascade into a MISLEADING 9e failure:
# with HEAD left at 200 rows, a 3-row stage is a genuine shrink and refusing it
# is correct, yet 9e would report a false positive.
( cd "$MAYOR" && write_tracker 0 .beads/issues.jsonl empty \
    && git add .beads/issues.jsonl \
    && git commit -q --no-verify -m 'mayor: establish an empty HEAD' -- .beads/issues.jsonl ) \
  >/dev/null 2>&1 || true
before=$(git -C "$MAYOR" rev-parse HEAD)
if stage_tracker 3 fresh; then
  rc_t=$(mktemp); ( trunc_commit 2>"$TERR" ) && echo 0 > "$rc_t" || echo $? > "$rc_t"
  after=$(git -C "$MAYOR" rev-parse HEAD)
  if [ "$(cat "$rc_t")" = "0" ] && [ "$before" != "$after" ]; then
    pass "(9e) a 3-row tracker over a 0-row HEAD passes: nothing to lose, no nag"
  else
    fail "(9e) FALSE POSITIVE: the floor fired over an empty HEAD (exit $(cat "$rc_t"))"
    cat "$TERR" >&2
  fi
  rm -f "$rc_t"
fi

# 9f: and a commit that never touches the tracker is untouched by the block.
# The index is cleared of the tracker first, deliberately: the block keys on
# what is STAGED, so a tracker left in the index from an earlier case would
# make this pass or fail for a reason that has nothing to do with MEMORY.md.
( cd "$MAYOR" && git reset -q HEAD -- .beads/issues.jsonl \
    && git checkout -q HEAD -- .beads ) >/dev/null 2>&1 || true
scenario_9f() {
  cd "$MAYOR"
  printf 'operator memory\n' > MEMORY.md
  git add MEMORY.md
  git commit -q -m 'mayor: memory only'
}
rc_t=$(mktemp); ( scenario_9f 2>"$TERR" ) && echo 0 > "$rc_t" || echo $? > "$rc_t"
if [ "$(cat "$rc_t")" = "0" ]; then
  pass "(9f) a commit staging no tracker path is untouched by the floor"
else
  fail "(9f) FALSE POSITIVE: an unrelated permitted commit was refused (exit $(cat "$rc_t"))"
  cat "$TERR" >&2
fi
rm -f "$rc_t" "$TERR"

# ----------------------------------------------------------------------------
# Summary
# ----------------------------------------------------------------------------

printf '\nSummary: %d passed, %d failed\n' "$pass_count" "$fail_count"
if [ "$fail_count" -ne 0 ]; then
  exit 1
fi
exit 0
