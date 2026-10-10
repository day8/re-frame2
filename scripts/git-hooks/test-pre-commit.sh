#!/usr/bin/env sh
# scripts/git-hooks/test-pre-commit.sh
#
# Tests for the local-durability surface the pre-commit hook belongs to. The
# file is named for the hook because `.github/workflows/test.yml` runs it by
# name on every pull request, which is how every layer here reaches CI.
# Layer numbers are stable: README.md, the CI arms and test.yml cite them.
#
#   2. The mayor commit boundary, end to end in a mayor + worker sandbox.
#   3. The beads path classifier (lib/check-beads-boundary.sh), as a library.
#   4. The worker beads boundary, end to end in the layer-2 sandbox.
#   5. The beads CI arm (check-beads-pr-boundary.sh) on DIVERGED history.
#   6. The installer: install, worktree inheritance, the bite, drift detection.
#   7. The staleness advisory on REAL pulls, rebasing and merging.
#   8. The checkpoint helper (beads-checkpoint.sh) against a stub `bd`.
#   9. The truncation floor in the hook, from the layer-2 PRIMARY worktree.
#  10. The AI-attribution guard: detector, commit-msg hook, CI arm, PR body,
#      and the author/committer identity.
#  11. post-merge's MCP-staleness block, through post-merge-hook-test.cjs.
#
# Usage:
#   sh scripts/git-hooks/test-pre-commit.sh
#
# Exit code: 0 if all scenarios pass, 1 otherwise.

set -eu

# The beads guard honours RF2_MAYOR_ROOT, which would point at the REAL mayor
# checkout and misclassify every sandbox below.
unset RF2_MAYOR_ROOT || true

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
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
# Layer 2: the mayor commit boundary, end to end.
# ----------------------------------------------------------------------------

printf '\n[2] end-to-end smoke (mayor + worker worktree)\n'

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
  mkdir -p .beads
  printf '{"id":"seed","title":"seed"}\n' > .beads/issues.jsonl
  git add .beads/issues.jsonl
  git commit -q -m 'seed'
  git worktree add -q -b worker/test "$WORKER"
) >/dev/null

# The hook and marker are staged by hand, so layers 2, 4 and 9 grade hook
# BEHAVIOUR; layer 6 grades the installer.
COMMON_DIR=$(git -C "$MAYOR" rev-parse --git-common-dir)
case "$COMMON_DIR" in /*|[A-Za-z]:[\\/]*) ;; *) COMMON_DIR="$MAYOR/$COMMON_DIR" ;; esac
HOOKS_DIR="$COMMON_DIR/hooks"
mkdir -p "$HOOKS_DIR"
# BOTH libs go into BOTH trees: the hook resolves them against each worktree's
# own toplevel, and a block whose lib is missing no-ops — so a missing copy
# would make the worker scenarios pass vacuously.
mkdir -p "$MAYOR/scripts/git-hooks/lib" "$WORKER/scripts/git-hooks/lib"
cp "$LIB" "$BEADS_LIB" "$MAYOR/scripts/git-hooks/lib/"
cp "$LIB" "$BEADS_LIB" "$WORKER/scripts/git-hooks/lib/"
cp "$HOOK" "$HOOKS_DIR/pre-commit"
chmod +x "$HOOKS_DIR/pre-commit"
printf 'sandbox marker\n' > "$COMMON_DIR/mayor-marker"

SMOKE_ERR=/tmp/rf2-pc-smoke.err

# scenario_rc FN [ARG...] — run FN in a subshell and echo its exit code;
# stderr lands in $SMOKE_ERR.
scenario_rc() {
  ( "$@" ) >/dev/null 2>"$SMOKE_ERR" && echo 0 || echo $?
}

# (a)/(h): the mayor commits the tracker. The mayor block permits it, and the
# beads block no-ops in the primary worktree — that IS the checkpoint flow.
scenario_a() {
  cd "$MAYOR"
  printf '{"id":"a","title":"a"}\n' > .beads/issues.jsonl
  git add .beads/issues.jsonl
  git commit -q -m 'mayor: bd checkpoint (a)'
}
rc=$(scenario_rc scenario_a)
if [ "$rc" = "0" ]; then
  pass "(a)(h) mayor commit: .beads/issues.jsonl only -> passed (both blocks)"
else
  fail "(a)(h) mayor commit of the tracker was refused (exit $rc)"
  cat "$SMOKE_ERR" >&2 || true
fi

# (b): the mayor commits source -> refused.
scenario_b() {
  cd "$MAYOR"
  mkdir -p tools/xray
  echo '(ns foo)' > tools/xray/foo.cljs
  git add tools/xray/foo.cljs
  git commit -q -m 'mayor: refused'
}
rc=$(scenario_rc scenario_b)
if [ "$rc" != "0" ] && grep -q 'mayor checkout cannot commit' "$SMOKE_ERR"; then
  pass "(b) mayor commit: tools/xray/foo.cljs -> refused (exit $rc)"
else
  fail "(b) refused-zone mayor commit was NOT blocked (exit $rc)"
  cat "$SMOKE_ERR" >&2 || true
fi
( cd "$MAYOR" && git reset -q HEAD && rm -rf tools ) || true

# (c)/(f): a worker commits ordinary source -> passes. No mayor-marker in the
# worker's git dir, and nothing under .beads/.
scenario_c() {
  cd "$WORKER"
  mkdir -p tools/xray
  echo '(ns bar)' > tools/xray/bar.cljs
  git add tools/xray/bar.cljs
  git commit -q -m 'worker: source change'
}
rc=$(scenario_rc scenario_c)
if [ "$rc" = "0" ]; then
  pass "(c)(f) worker commit: ordinary source -> passed (both blocks)"
else
  fail "(c)(f) ordinary worker commit was refused (exit $rc)"
  cat "$SMOKE_ERR" >&2 || true
fi

# (d): one refused path among permitted ones refuses the whole commit.
scenario_d() {
  cd "$MAYOR"
  mkdir -p tools/xray
  echo '(ns mix)' > tools/xray/mix.cljs
  printf '{"id":"d","title":"d"}\n' > .beads/issues.jsonl
  git add .beads/issues.jsonl tools/xray/mix.cljs
  git commit -q -m 'mayor: mixed'
}
rc=$(scenario_rc scenario_d)
if [ "$rc" != "0" ] && grep -q 'tools/xray/mix.cljs' "$SMOKE_ERR"; then
  pass "(d) mayor mixed commit -> refused (any-refused triggers) (exit $rc)"
else
  fail "(d) mixed-zone mayor commit was NOT blocked (exit $rc)"
  cat "$SMOKE_ERR" >&2 || true
fi
( cd "$MAYOR" && git reset -q HEAD && rm -rf tools ) || true

# ----------------------------------------------------------------------------
# Layer 3: check-beads-boundary.sh, as a library.
# ----------------------------------------------------------------------------

printf '\n[3] check-beads-boundary.sh library tests\n'

run_beads_lib() {
  # stdin: newline-separated paths. Echoes EXIT=<n>.
  (
    # `set +e` is load-bearing: callers capture a non-zero return, and dash
    # (the ubuntu runner's `sh`) does not suspend errexit inside the command
    # substitution as bash does, so without it every refusal reads as empty.
    set +e
    . "$BEADS_LIB"
    check_beads_boundary commit
    echo "EXIT=$?"
  )
}

BERR=/tmp/rf2-beads-test.err

# 3e: the human-authored beads config surface stays committable from anywhere.
out=$(printf '.beads/README.md\n.beads/config.yaml\n.beads/.gitignore\n.beads/PRIME.md\n.beads/hooks/pre-commit\n' \
      | run_beads_lib 2>"$BERR") || true
case "$out" in
  *EXIT=0*) pass "human-authored beads config -> exit 0" ;;
  *) fail "human-authored beads config -> wrongly refused: $out"; cat "$BERR" >&2 ;;
esac

# ...and the PRIME.md arm is EXACT, not a `.beads/PRIME*` or `.beads/*.md` hole.
lookalikes_ok=1
for p in .beads/PRIME.md.bak .beads/PRIME.jsonl .beads/prime/export.jsonl .beads/NOTES.md; do
  out=$(printf '%s\n' "$p" | run_beads_lib 2>"$BERR") || true
  case "$out" in
    *EXIT=1*) ;;
    *) lookalikes_ok=0; fail "PRIME lookalike WRONGLY permitted: $p (exit: $out)" ;;
  esac
done
if [ "$lookalikes_ok" = "1" ]; then
  pass "PRIME lookalikes still refused (.bak, .jsonl, prime/, NOTES.md)"
fi

rm -f "$BERR"

# ----------------------------------------------------------------------------
# Layer 4: the worker beads boundary, end to end, in the layer-2 sandbox.
# ----------------------------------------------------------------------------

printf '\n[4] end-to-end smoke (worker beads boundary)\n'

# (e): a worker stages the tracker database -> REFUSED, naming the file and
# the remedy. The remedy never suggests `--skip-worktree`, which hides the edit
# from `git status` yet still aborts `git pull`.
scenario_e() {
  cd "$WORKER"
  printf '{"id":"stale","title":"stale worktree snapshot"}\n' > .beads/issues.jsonl
  git add .beads/issues.jsonl
  git commit -q -m 'worker: stale beads snapshot'
}
rc=$(scenario_rc scenario_e)
if [ "$rc" != "0" ] \
   && grep -q '\.beads/issues\.jsonl' "$SMOKE_ERR" \
   && grep -q 'STALE WORKER-SNAPSHOT' "$SMOKE_ERR" \
   && grep -q 'git checkout HEAD -- .beads' "$SMOKE_ERR" \
   && ! grep -q 'skip-worktree' "$SMOKE_ERR"; then
  pass "(e) worker commit: .beads/issues.jsonl -> refused, names file + remedy, no skip-worktree (exit $rc)"
else
  fail "(e) worker commit staging the tracker was NOT blocked as specified (exit $rc)"
  cat "$SMOKE_ERR" >&2 || true
fi
( cd "$WORKER" && git reset -q HEAD && git checkout -q -- .beads ) || true

# (g): a worker commits human-authored beads CONFIG -> passes.
scenario_g() {
  cd "$WORKER"
  printf 'auto_export: true\n' > .beads/config.yaml
  git add .beads/config.yaml
  git commit -q -m 'worker: beads config (human-authored)'
}
rc=$(scenario_rc scenario_g)
if [ "$rc" = "0" ]; then
  pass "(g) worker commit: .beads/config.yaml -> passed (allow-listed)"
else
  fail "(g) human-authored beads config was refused (exit $rc)"
  cat "$SMOKE_ERR" >&2 || true
fi

# ----------------------------------------------------------------------------
# Layer 5: the CI arm, on DIVERGED HISTORY.
#
# The mayor checkpoints `.beads/issues.jsonl` to main on nearly every loop
# tick, so a two-endpoint `git diff BASE HEAD` would blame every branch that
# forked before the last checkpoint. Endpoint-only fixtures cannot see that;
# only the sequence can — fork, advance the base with a beads-only commit,
# then assert.
# ----------------------------------------------------------------------------

printf '\n[5] CI arm on diverged history\n'

PR_GUARD="$REPO_ROOT/scripts/check-beads-pr-boundary.sh"
CIBOX=$(mktemp -d "${TMPDIR:-/tmp}/rf2-beads-ci-XXXXXX")
CIERR=$(mktemp "${TMPDIR:-/tmp}/rf2-beads-ci-err-XXXXXX")

(
  cd "$CIBOX"
  git init -q -b main
  git config user.email 'precommit-test@example.invalid'
  git config user.name 'precommit-test'
  git config commit.gpgsign false
  # The guard resolves its classifier relative to its OWN location.
  mkdir -p scripts/git-hooks/lib .beads implementation/core/src
  cp "$PR_GUARD" scripts/
  cp "$BEADS_LIB" scripts/git-hooks/lib/
  printf '{"id":"seed"}\n' > .beads/issues.jsonl
  printf '(ns seed)\n' > implementation/core/src/seed.cljc
  git add -A
  git commit -q -m 'seed'

  git branch worker/clean
  git branch worker/contaminated
  git branch worker/renamed-out

  # Only THEN does the base advance, with a mayor beads-only checkpoint.
  printf '{"id":"seed"}\n{"id":"filed-after-the-fork"}\n' > .beads/issues.jsonl
  git commit -q -am 'chore(beads): mayor heartbeat AFTER the branches forked'
  git update-ref refs/remotes/origin/main "$(git rev-parse main)"

  git checkout -q worker/clean
  printf '(ns seed)\n;; ordinary work\n' > implementation/core/src/seed.cljc
  git commit -q -am 'worker: ordinary source change, no tracker'

  git checkout -q worker/contaminated
  printf '(ns seed)\n;; ordinary work\n' > implementation/core/src/seed.cljc
  printf '{"id":"seed"}\n{"id":"claimed-by-this-worker","status":"in_progress"}\n' \
    > .beads/issues.jsonl
  git add -A
  git commit -q -m 'worker: real work + bd auto-staged tracker snapshot'

  # An EXACT rename out of the protected tree: git scores it R100, and a
  # rename-detecting `--name-only` would report the destination alone.
  git checkout -q worker/renamed-out
  git mv .beads/issues.jsonl tracker-snapshot.jsonl
  git commit -q -m 'worker: move the tracker out of .beads/'
) >/dev/null 2>&1

run_ci_guard() {
  # $1 = branch, $2 = base ref. Echoes EXIT=<n>; stderr lands in $CIERR.
  ( cd "$CIBOX" \
    && git checkout -q "$1" \
    && GITHUB_EVENT_NAME=pull_request sh scripts/check-beads-pr-boundary.sh "$2" \
       >/dev/null 2>"$CIERR" ) && echo "EXIT=0" || echo "EXIT=$?"
}

# 5a: THE FALSE-RED CASE. The clean branch never touched the tracker; the BASE
# did, after the fork.
out=$(run_ci_guard worker/clean origin/main)
case "$out" in
  EXIT=0) pass "(5a) clean branch forked before a mayor beads checkpoint -> passes" ;;
  *)
    fail "(5a) FALSE RED: a clean branch was blamed for the base's beads checkpoint ($out)"
    cat "$CIERR" >&2 || true
    ;;
esac

# 5b: from the same history, real contamination still fails, naming the path
# and the branch-repair remedy, and never suggesting skip-worktree.
out=$(run_ci_guard worker/contaminated origin/main)
case "$out" in
  EXIT=0)
    fail "(5b) FALSE GREEN: a branch that committed the tracker was not blocked"
    ;;
  *)
    if grep -q '\.beads/issues\.jsonl' "$CIERR" \
       && grep -q 'STALE WORKER-SNAPSHOT' "$CIERR" \
       && grep -q 'git rebase -i' "$CIERR" \
       && ! grep -q 'skip-worktree' "$CIERR"; then
      pass "(5b) branch that DID commit the tracker -> refused, names path + remedy"
    else
      fail "(5b) refused, but the diagnostic is missing the path or the remedy"
      cat "$CIERR" >&2 || true
    fi
    ;;
esac

# 5f: RENAME ENDPOINTS. Merging the rename deletes the tracker from its
# canonical location, so the DELETED endpoint must reach the classifier.
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

# 5c: an unresolvable branch point FAILS CLOSED — a gate that cannot see the
# branch delta certifies nothing. A shallow clone is the usual cause.
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

# 5d: a missing base ref fails closed too.
out=$( ( cd "$CIBOX" && git checkout -q worker/clean \
         && GITHUB_EVENT_NAME=pull_request sh scripts/check-beads-pr-boundary.sh \
            >/dev/null 2>"$CIERR" ) && echo "EXIT=0" || echo "EXIT=$?")
case "$out" in
  EXIT=0) fail "(5d) missing base ref passed vacuously" ;;
  *) pass "(5d) missing base ref -> fails closed" ;;
esac

# 5e: THE MAYOR CHECKPOINT PATH. Off pull_request the guard no-ops: the mayor
# commits the tracker to main on every heartbeat.
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
# Layer 6: the INSTALLER, end to end.
#
# The installed hooks are copies, so a source block the installer does not
# carry guards nothing while every checkout believes it is guarded. This layer
# drives `scripts/install-git-hooks.sh` for real.
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
  # A miniature of the repo: installer, hook sources and libs, tracked, at the
  # paths the installer and the hooks resolve against. Layer 7 pulls from it.
  cp "$INSTALLER" scripts/
  [ -f "$REPO_ROOT/scripts/install-git-hooks.ps1" ] \
    && cp "$REPO_ROOT/scripts/install-git-hooks.ps1" scripts/
  for h in post-merge post-rewrite pre-commit commit-msg; do
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

# 6a/6b: a fresh checkout installs clean, and --check then certifies it.
out=$(run_in_repo "$IREPO" sh scripts/install-git-hooks.sh)
if [ "$out" = "EXIT=0" ]; then
  out=$(run_in_repo "$IREPO" sh scripts/install-git-hooks.sh --check)
fi
case "$out" in
  EXIT=0) pass "(6a)(6b) installer runs clean on a fresh checkout, and --check certifies it" ;;
  *) fail "(6a)(6b) install or --check failed on a fresh checkout ($out)"; cat "$IERR" >&2 ;;
esac

# 6c: every managed block in the hook SOURCES reached the installed hooks —
# a block the installer does not register is the partial install --check
# cannot see.
: > "$IBOX/blocks.txt"
for h in pre-commit post-merge post-rewrite commit-msg; do
  grep '^# --- BEGIN re-frame2 ' "$IREPO/scripts/git-hooks/$h" | sed "s|^|$h:|" >> "$IBOX/blocks.txt"
done
missing=$(while IFS= read -r spec; do
  grep -Fq "${spec#*:}" "$IREPO/.git/hooks/${spec%%:*}" 2>/dev/null || printf '  %s\n' "$spec"
done < "$IBOX/blocks.txt")
if [ -s "$IBOX/blocks.txt" ] && [ -z "$missing" ]; then
  pass "(6c) every source block reached the installed hooks"
else
  fail "(6c) blocks absent from the installed hooks:"
  printf '%s\n' "$missing" >&2
fi

# 6d: a linked worktree created AFTER the install inherits the guard, so one
# install is enough.
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

# 6f: NO FALSE POSITIVE. A guard that costs every commit gets bypassed with
# --no-verify.
out=$(run_in_repo "$IWORKER" sh -c 'mkdir -p implementation/core/src && echo "(ns foo)" > implementation/core/src/foo.cljc && git add implementation/core/src/foo.cljc && git commit -q -m "worker: ordinary source commit"')
case "$out" in
  EXIT=0) pass "(6f) ordinary source commit from the same worktree passes" ;;
  *) fail "(6f) FALSE POSITIVE: an ordinary source commit was refused ($out)"; cat "$IERR" >&2 ;;
esac

# 6g: DRIFT IS DETECTED. Strip one block from the installed hook, as a stale
# copy would.
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

# 6h: and the post-merge advisory says so, unprompted.
out=$(run_in_repo "$IREPO" sh .git/hooks/post-merge)
if grep -q 'install-git-hooks.sh' "$IERR"; then
  pass "(6h) post-merge advisory reports the stale install and names the repair"
else
  fail "(6h) post-merge stayed silent about a stale install ($out)"
  cat "$IERR" >&2
fi

# 6i: re-running the installer repairs it...
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

# 6j: ...and the advisory goes quiet. One that fires on a healthy checkout is
# a nag, and nags get muted.
out=$(run_in_repo "$IREPO" sh .git/hooks/post-merge)
if [ -s "$IERR" ]; then
  fail "(6j) post-merge advisory fires on a healthy install (nag)"
  cat "$IERR" >&2
else
  pass "(6j) post-merge advisory silent on a healthy install"
fi

# 6k: THE TWO INSTALLERS AGREE. They share one hooks directory and one
# mayor-marker, and the post-merge advisory runs the .sh --check, so a marker
# naming its writer would turn one .ps1 run into a permanent nag. Skipped where
# no PowerShell is installed: the .sh installer must not need one.
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

# ----------------------------------------------------------------------------
# Layer 7: the advisory on REAL pulls — rebase AND merge.
#
# A `git pull --rebase` with a local commit performs a real rebase, which never
# invokes post-merge; git's hook for that path is post-rewrite. A rebasing pull
# with nothing to replay, and a merging pull, fire post-merge. So this layer
# drives real pulls from layer 6's repo rather than calling hooks.
# ----------------------------------------------------------------------------

printf '\n[7] the advisory on real pulls: rebase (post-rewrite) and merge (post-merge)\n'

RCL="$IBOX/clone"
git clone -q "$IREPO" "$RCL" >/dev/null 2>&1
(
  cd "$RCL"
  git config user.email 'hookpull-test@example.invalid'
  git config user.name 'hookpull-test'
  git config commit.gpgsign false
) >/dev/null 2>&1

# Upstream commits use --no-verify: layer 6 left IREPO a guarded mayor
# checkout, which refuses source commits by design.
#
# drift_upstream_hook_source TAG — land a change inside a managed block of the
# post-merge source, leaving the clone's installed copies stale. The inserted
# `:` line keeps the hook valid sh.
drift_upstream_hook_source() {
  (
    cd "$IREPO"
    awk -v tag="$1" '
      {print}
      /^# --- BEGIN re-frame2 MCP-staleness check \(rf2-6jj3r\) ---$/ {
        print ": " tag
      }' scripts/git-hooks/post-merge > post-merge.drifted
    mv -f post-merge.drifted scripts/git-hooks/post-merge
    git add scripts/git-hooks/post-merge
    git commit -q --no-verify -m "upstream: change a managed hook block ($1)"
  ) >/dev/null 2>&1
}

local_commit() {
  # A commit of the clone's own, so `git pull --rebase` really rebases.
  # --no-verify: the installer made the clone a mayor checkout, whose boundary
  # (layer 2's subject) refuses this path.
  ( cd "$RCL" && echo "$1" > "$1.txt" && git add "$1.txt" \
      && git commit -q --no-verify -m "local: $1" ) >/dev/null 2>&1
}

# The clone starts from a clean, certified install, so what follows measures
# a DRIFT.
out=$(run_in_repo "$RCL" sh scripts/install-git-hooks.sh)
if [ "$out" = "EXIT=0" ]; then
  out=$(run_in_repo "$RCL" sh scripts/install-git-hooks.sh --check)
fi
case "$out" in
  EXIT=0) : ;;
  *) fail "(7-setup) the clone's fresh install failed or was not certified ($out)"; cat "$IERR" >&2 ;;
esac

local_commit mine
drift_upstream_hook_source rf2-drift-one
out=$(run_in_repo "$RCL" git pull --rebase origin main)
pull_err_rebase=$(cat "$IERR" 2>/dev/null || true)

# 7b: it really was a REBASE — the local commit was replayed on top. If this
# ever fast-forwards instead, 7c stops testing the rebase path.
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

# 7c: THE REBASE PATH reports the drift it just landed.
if [ "$rebase_ok" = "1" ]; then
  case "$pull_err_rebase" in
    *install-git-hooks.sh*)
      pass "(7c) a rebasing pull reports the stale install and names the repair" ;;
    *)
      fail "(7c) a rebasing pull landed hook drift SILENTLY (no advisory)"
      printf '%s\n' "$pull_err_rebase" >&2 ;;
  esac
fi

# 7d: NO NAG. Repair, then a rebasing pull touching no hook source is quiet.
out=$(run_in_repo "$RCL" sh scripts/install-git-hooks.sh)
case "$out" in
  EXIT=0) : ;;
  *) fail "(7d) repair install failed ($out)"; cat "$IERR" >&2 ;;
esac
( cd "$IREPO" && echo ordinary >> readme.txt && git add readme.txt \
    && git commit -q --no-verify -m 'upstream: an ordinary source commit' ) >/dev/null 2>&1
local_commit mine-again
out=$(run_in_repo "$RCL" git pull --rebase origin main)
case "$(cat "$IERR" 2>/dev/null || true)" in
  *'[re-frame2]'*)
    fail "(7d) the advisory fired on a rebasing pull with a healthy install (nag)"
    cat "$IERR" >&2 ;;
  *)
    if [ "$out" = "EXIT=0" ]; then
      pass "(7d) a rebasing pull is silent when the install is current"
    else
      fail "(7d) the control pull failed ($out)"; cat "$IERR" >&2
    fi ;;
esac

# 7e: the MERGE path still reports drift, through post-merge.
drift_upstream_hook_source rf2-drift-two
local_commit mine-third
out=$(run_in_repo "$RCL" git pull --no-rebase --no-edit origin main)
case "$(cat "$IERR" 2>/dev/null || true)" in
  *install-git-hooks.sh*)
    pass "(7e) a merging pull still reports the stale install (post-merge arm intact)" ;;
  *)
    fail "(7e) the merge path lost its advisory ($out)"
    cat "$IERR" >&2 ;;
esac

rm -rf "$IBOX"

# ----------------------------------------------------------------------------
# Layer 8: the checkpoint helper, against a stub `bd`.
#
# `git checkout HEAD -- .beads` before a pull reverts a `bd close` that lives
# only in the database, and a checkpoint that committed the working file would
# write that revert back. `scripts/beads-checkpoint.sh` re-exports from the
# database instead. The stub `bd` keeps every case hermetic.
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

# The "database" is whatever $CBOX/db.jsonl holds. The stub models `bd export`
# from v1.1.2: memory rows ride ONLY behind --include-memories.
mkdir -p "$CBIN" "$CREPO/scripts/git-hooks/lib" "$CREPO/.beads"
cat > "$CBIN/bd" <<EOF
#!/usr/bin/env sh
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

# The database, one `bd close rf2-b` later.
sed 's/"id":"rf2-b","status":"open"/"id":"rf2-b","status":"closed"/' \
  "$CBOX/head.jsonl" > "$CBOX/db-closed.jsonl"

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

# 8a: THE CORE CASE. Revert the working file exactly as the pre-pull cleanup
# does, then checkpoint: the commit carries the close, because it came from
# the database — and both memories, because the export runs
# --include-memories.
cp -f "$CBOX/db-closed.jsonl" "$CBOX/db.jsonl"
git -C "$CREPO" checkout -q HEAD -- .beads
out=$(run_checkpoint "$CREPO")
committed=$(git -C "$CREPO" show HEAD:.beads/issues.jsonl 2>/dev/null || true)
case "$out" in
  EXIT=0)
    case "$committed" in
      *'"id":"rf2-b","status":"closed"'*)
        if [ "$(printf '%s\n' "$committed" | grep -c '"_type":"memory"')" = "2" ]; then
          pass "(8a) a close survives the pre-pull checkout, and both memory rows ride with it"
        else
          fail "(8a) the commit DROPPED memory rows: the export is running bare"
          printf '%s\n' "$committed" >&2
        fi ;;
      *)
        fail "(8a) the close EVAPORATED: the checkpoint committed the reverted file"
        printf '%s\n' "$committed" >&2 ;;
    esac ;;
  *) fail "(8a) checkpoint failed ($out)"; cat "$CERR" >&2 ;;
esac

# 8b: --pre-pull REFUSES while the working export carries state HEAD lacks, and
# names the remedy — before the checkout, not after the close is gone.
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

# 8c: and it is SILENT once the tracker is checkpointed.
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

# 8d: A FAILED EXPORT COMMITS NOTHING; the working file is no fallback.
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

# 8e: AN EMPTY EXPORT IS REFUSED.
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
# so a reorder is not a change.
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

# 8g: WORKER WORKTREES ARE REFUSED the commit — the tracker is the mayor's to
# commit — while the read-only --pre-pull question still answers there.
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

out=$(run_checkpoint "$CWORKER" --pre-pull)
case "$out" in
  EXIT=0)
    pass "(8g) but --pre-pull still answers from a worker worktree: read-only is not gated" ;;
  *)
    fail "(8g) --pre-pull refused to answer from a worker worktree ($out)"
    cat "$CERR" >&2 ;;
esac
git -C "$CREPO" worktree remove --force "$CWORKER" >/dev/null 2>&1 || true

# 8h: A REAL CHANGE COMMITS THE REAL CHANGE ONLY. rf2-a closes while four
# untouched memories are shuffled: the diff is the two rf2-a lines and nothing
# else, and the committed file is still the export's row set, none lost.
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
    # Diff body only: added/removed rows, not the +++/--- headers.
    cdiff=$(git -C "$CREPO" diff "$before" "$after" -- .beads/issues.jsonl \
              | grep '^[+-]' | grep -v '^[+-][+-]' || true)
    churn=$(printf '%s\n' "$cdiff" | grep '"_type":"memory"' || true)
    real=$(printf '%s\n' "$cdiff" | grep '"id":"rf2-a"' || true)
    if [ "$before" = "$after" ]; then
      fail "(8h) a real row edit produced no commit"
      cat "$COUT" >&2
    elif [ -n "$churn" ]; then
      fail "(8h) the commit carried memory-row churn alongside the real edit"
      printf '%s\n' "$churn" >&2
    elif [ -z "$real" ] || [ "$(printf '%s\n' "$cdiff" | awk 'END{print NR}')" != "2" ]; then
      fail "(8h) the commit did not carry exactly the two rf2-a lines"
      printf '%s\n' "$cdiff" >&2
    elif [ "$(git -C "$CREPO" show HEAD:.beads/issues.jsonl | LC_ALL=C sort)" \
           != "$(LC_ALL=C sort < "$CBOX/db.jsonl")" ]; then
      fail "(8h) the minimal-diff rewrite changed the committed ROW SET"
    else
      pass "(8h) a real edit commits ONLY the changed rows, and every exported row"
    fi ;;
  *) fail "(8h) checkpoint failed on a real edit + reordered memories ($out)"; cat "$CERR" >&2 ;;
esac

# 8i: THE SHRINK GUARD. A >1/10 shrink — a deliberate `bd gc` among them — is
# refused, sending the operator to a hand commit.
before=$(git -C "$CREPO" rev-parse HEAD)
printf '{"_type":"issue","id":"rf2-a","status":"closed"}\n' > "$CBOX/db.jsonl"
out=$(run_checkpoint "$CREPO")
after=$(git -C "$CREPO" rev-parse HEAD)
case "$out" in
  EXIT=0) fail "(8i) a 5-of-6-row shrink was checkpointed; the guard is gone" ;;
  *)
    if [ "$before" = "$after" ] && grep -q 'tenth of the' "$CERR" \
       && grep -q 'untouched' "$CERR"; then
      pass "(8i) a >1/10 shrink is still refused, and the tracker is untouched"
    else
      fail "(8i) the shrink was refused for the wrong reason, or the tree moved"
      cat "$CERR" >&2
    fi ;;
esac

# 8j-8l: EQUAL COUNTS ARE NOT EQUALITY. The merged-PR audit and `git pull`
# bring issue rows straight to Git, so Git and the database can diverge one
# row for one row while the row floor sees nothing. Every row is load-bearing:
#
#   rf2-a   unchanged on both sides
#   rf2-b   NEWER ON GIT   — closed at 03:00; the export still has it open
#   rf2-c   NEWER ON DOLT  — closed at 02:00; HEAD still has it open
#   rf2-g1  GIT ONLY       — the export has never heard of it
#   rf2-d1  DOLT ONLY      — HEAD has never heard of it
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
} > "$CBOX/db.jsonl"

(
  cd "$CREPO"
  cp -f "$CBOX/head-diverged.jsonl" .beads/issues.jsonl
  git add -- .beads/issues.jsonl
  git commit -q -m 'seed: HEAD and the database have diverged at equal row count'
) >/dev/null 2>&1

# 8j: refused, naming what would be lost WITH its fields — an id-set
# comparison proves presence and nothing more — and nothing of the Dolt side.
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

# 8j-remedy: the named file holds exactly the Git-only and Git-newer rows, so
# `bd import` of it is the whole recovery.
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

# 8l: THE AMBIGUOUS ROW. Same `updated_at`, different `status`: neither side is
# newer, and no import can adjudicate a tie, so none is offered.
sed 's/"id":"rf2-c","status":"open"/"id":"rf2-c","status":"closed"/' \
  "$CBOX/head-diverged.jsonl" > "$CBOX/db.jsonl"
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

# 8m-8p: THE MEMORY POPULATION IS RECONCILED, AND THE GUARD WARNS.
#
# The row floor is dominated by issue rows and the divergence guard reads issue
# rows only, so a memory-only deletion passes both: 20 issues + 10 memories at
# HEAD against an export missing 2 memories is 28 of 30 rows, above the floor.
# The guard must WARN and still commit: the mayor runs it several times an
# hour, and a false refusal would halt the dispatch loop.
{
  awk 'BEGIN{for(i=1;i<=20;i++) printf "{\"_type\":\"issue\",\"id\":\"rf2-m%02d\",\"status\":\"open\",\"updated_at\":\"2026-09-01T00:00:00Z\"}\n", i}'
  awk 'BEGIN{for(i=1;i<=10;i++) printf "{\"_type\":\"memory\",\"key\":\"mem-key-%02d\",\"value\":\"body %d\"}\n", i, i}'
} > "$CBOX/head-mem.jsonl"

grep -v '"key":"mem-key-03"' "$CBOX/head-mem.jsonl" \
  | grep -v '"key":"mem-key-07"' > "$CBOX/db-mem-culled.jsonl"

(
  cd "$CREPO"
  cp -f "$CBOX/head-mem.jsonl" .beads/issues.jsonl
  git add -- .beads/issues.jsonl
  git commit -q -m 'seed: 20 issues and 10 memories'
) >/dev/null 2>&1

# 8m: a memory-only deletion warns loudly, names exactly the lost keys, counts
# both populations on both sides, says it is not a refusal — and commits.
cp -f "$CBOX/db-mem-culled.jsonl" "$CBOX/db.jsonl"
before=$(git -C "$CREPO" rev-parse HEAD)
out=$(run_checkpoint "$CREPO")
after=$(git -C "$CREPO" rev-parse HEAD)
case "$out" in
  EXIT=0)
    if [ "$before" = "$after" ]; then
      fail "(8m) the memory-loss warning became a refusal: nothing was committed"
      cat "$CERR" >&2
    elif ! grep -q 'MEMORY RECONCILIATION FAILED' "$CERR"; then
      fail "(8m) a 2-of-10 memory deletion was checkpointed in SILENCE"
      cat "$CERR" >&2
    elif ! grep -q 'mem-key-03' "$CERR" || ! grep -q 'mem-key-07' "$CERR"; then
      fail "(8m) warned, but did not NAME the lost keys"
      cat "$CERR" >&2
    elif grep -q 'mem-key-05' "$CERR"; then
      fail "(8m) named a key that was never lost"
      cat "$CERR" >&2
    elif ! grep -q 'WARNING, NOT A REFUSAL' "$CERR"; then
      fail "(8m) warned without saying the checkpoint continues"
      cat "$CERR" >&2
    elif ! grep -q 'export  28 rows = 20 issues + 8 memories' "$CERR" \
         || ! grep -q 'HEAD    30 rows = 20 issues + 10 memories' "$CERR"; then
      fail "(8m) the warning did not report the two populations against HEAD"
      cat "$CERR" >&2
    else
      pass "(8m) a memory-only deletion WARNS, names the lost keys and counts, and still checkpoints"
    fi ;;
  *) fail "(8m) the memory reconciliation REFUSED the checkpoint ($out); it must only warn"
     cat "$CERR" >&2 ;;
esac

# The printed recovery lookup must name the pre-checkpoint commit by oid:
# `HEAD` is the commit that removed the rows by the time anyone pastes it, and
# a lookup against it exits 0 and prints nothing.
rec_lines=$(grep -c '^ *git show ' "$CERR" || :)
rec_ref=$(sed -n 's/^ *git show \([^:]*\):.*/\1/p' "$CERR" | sed -n '1p')
if [ "$rec_lines" != "1" ]; then
  fail "(8m) expected exactly one recovery lookup in the warning, found $rec_lines"
  cat "$CERR" >&2
elif [ "$rec_ref" != "$before" ]; then
  fail "(8m) the recovery lookup names '$rec_ref', not the pre-checkpoint commit $before"
  cat "$CERR" >&2
else
  pass "(8m) the recovery lookup names the immutable pre-checkpoint commit, not \`HEAD\`"
fi

# 8o: a row of an unknown `_type` is reported — the two populations no longer
# sum to the row count — and still commits.
{
  cat "$CBOX/head-mem.jsonl"
  printf '{"_type":"sprint","id":"s1"}\n'
} > "$CBOX/db.jsonl"
before=$(git -C "$CREPO" rev-parse HEAD)
out=$(run_checkpoint "$CREPO")
after=$(git -C "$CREPO" rev-parse HEAD)
case "$out" in
  EXIT=0)
    if ! grep -q 'neither an issue nor a memory' "$CERR"; then
      fail "(8o) a row of an unknown _type was not reported; the populations do not sum"
      cat "$CERR" >&2
    elif [ "$before" = "$after" ]; then
      fail "(8o) the unknown-type report became a refusal"
    else
      pass "(8o) a row that is neither an issue nor a memory is reported, and still commits"
    fi ;;
  *) fail "(8o) an unknown _type row REFUSED the checkpoint ($out); it must only warn"
     cat "$CERR" >&2 ;;
esac

# 8m, the race: A CONCURRENT COMMIT BETWEEN THE CAPTURE AND THE COPY.
#
# In a quiet repo every read of `HEAD` returns the same oid, so a script that
# reads it twice looks identical to one that reads it once. A `git` shim on the
# child's PATH lands one concurrent commit right after the checkpoint's FIRST
# read of the tracker at HEAD, whichever read that is, so one fixture grades
# both orders:
#
#   A  the baseline commit          30 rows = 20 issues + 10 memories
#   B  the concurrent commit        28 rows, mem-key-03 and mem-key-07 culled
#   E  this checkpoint's export     29 rows, B's rows plus a new mem-key-11
#
#   fixed      oid A, bytes A  → warns, prints A
#   two reads  oid B, bytes A  → warns, prints B
#   reversed   oid A, bytes B  → E matches B on memories, so it does not warn
#
# E is B plus a row so this checkpoint has something to commit on top of B.
REAL_GIT=$(command -v git)
CSEAM="$CBOX/seam-bin"
mkdir -p "$CSEAM"
cat > "$CSEAM/git" <<EOF
#!/usr/bin/env sh
case "\$*" in
  'rev-parse --verify HEAD'|'show '*':.beads/issues.jsonl')
    if [ -f "$CBOX/seam-armed" ]; then
      "$REAL_GIT" "\$@"
      st=\$?
      rm -f "$CBOX/seam-armed"
      cp -f "$CBOX/race-concurrent.jsonl" "$CREPO/.beads/issues.jsonl"
      "$REAL_GIT" -C "$CREPO" add -- .beads/issues.jsonl >/dev/null 2>&1
      "$REAL_GIT" -C "$CREPO" commit -q -m 'a concurrent checkpoint lands between the two reads' >/dev/null 2>&1
      exit \$st
    fi ;;
esac
exec "$REAL_GIT" "\$@"
EOF
chmod +x "$CSEAM/git"

(
  cd "$CREPO"
  cp -f "$CBOX/head-mem.jsonl" .beads/issues.jsonl
  git add -- .beads/issues.jsonl
  git commit -q -m 'seed: 20 issues and 10 memories, ahead of the race'
) >/dev/null 2>&1
race_a=$(git -C "$CREPO" rev-parse HEAD)

cp -f "$CBOX/db-mem-culled.jsonl" "$CBOX/race-concurrent.jsonl"
{
  cat "$CBOX/db-mem-culled.jsonl"
  printf '{"_type":"memory","key":"mem-key-11","value":"a new lesson"}\n'
} > "$CBOX/db.jsonl"

: > "$CBOX/seam-armed"
out=$( ( cd "$CREPO" && PATH="$CSEAM:$CBIN:$PATH" sh scripts/beads-checkpoint.sh \
           >"$COUT" 2>"$CERR" ) && echo "EXIT=0" || echo "EXIT=$?")
# `if`, not `[ ... ] && x`: under `set -e` a failing AND-list test exits.
seam_fired=1
if [ -f "$CBOX/seam-armed" ]; then seam_fired=0; fi
rm -f "$CBOX/seam-armed"

# The seam must have fired, or the case grades a quiet repo.
race_b=$(git -C "$CREPO" rev-parse HEAD~1 2>/dev/null || true)
race_b_subject=$(git -C "$CREPO" log -1 --format=%s "${race_b:-HEAD}" 2>/dev/null || true)
if [ "$seam_fired" != "1" ] || [ -z "$race_b" ] || [ "$race_b" = "$race_a" ] \
   || [ "$race_b_subject" != "a concurrent checkpoint lands between the two reads" ]; then
  fail "(8m-setup) the race seam did not fire; the race case would pass vacuously"
  printf 'seam_fired=%s race_a=%s race_b=%s subject=%s\n' \
    "$seam_fired" "$race_a" "$race_b" "$race_b_subject" >&2
fi

race_ref=$(sed -n 's/^ *git show \([^:]*\):.*/\1/p' "$CERR" | sed -n '1p')
case "$out" in
  EXIT=0)
    if ! grep -q 'MEMORY RECONCILIATION FAILED' "$CERR" \
       || ! grep -q 'mem-key-03' "$CERR" || ! grep -q 'mem-key-07' "$CERR"; then
      fail "(8m) with a commit racing the capture the warning was lost or named no keys"
      cat "$CERR" >&2
    elif [ "$race_ref" != "$race_a" ]; then
      fail "(8m) the printed reference is '$race_ref'; the compared baseline was $race_a (the racer was $race_b)"
      cat "$CERR" >&2
    else
      pass "(8m) through a racing commit it still warns, and prints the commit it COMPARED against"
    fi ;;
  *) fail "(8m) the racing checkpoint exited $out; it must still warn-and-commit"
     cat "$CERR" >&2 ;;
esac

# 8p: THE NO-FALSE-POSITIVE CASE. Ordinary forward motion — an issue closes, a
# memory is ADDED, the rest are shuffled — commits without a murmur. HEAD is
# re-seeded first so this grades a clean baseline, not the race's leftovers.
(
  cd "$CREPO"
  cp -f "$CBOX/head-mem.jsonl" .beads/issues.jsonl
  git add -- .beads/issues.jsonl
  git commit -q -m 'seed: back to 20 issues and 10 memories'
) >/dev/null 2>&1
{
  awk 'BEGIN{for(i=1;i<=20;i++){s=(i==4?"closed":"open"); u=(i==4?"2026-09-02T00:00:00Z":"2026-09-01T00:00:00Z"); printf "{\"_type\":\"issue\",\"id\":\"rf2-m%02d\",\"status\":\"%s\",\"updated_at\":\"%s\"}\n", i, s, u}}'
  awk 'BEGIN{for(i=10;i>=1;i--) printf "{\"_type\":\"memory\",\"key\":\"mem-key-%02d\",\"value\":\"body %d\"}\n", i, i}'
  printf '{"_type":"memory","key":"mem-key-11","value":"a new lesson"}\n'
} > "$CBOX/db.jsonl"
git -C "$CREPO" checkout -q HEAD -- .beads
before=$(git -C "$CREPO" rev-parse HEAD)
out=$(run_checkpoint "$CREPO")
after=$(git -C "$CREPO" rev-parse HEAD)
case "$out" in
  EXIT=0)
    if [ "$before" = "$after" ]; then
      fail "(8p) ordinary forward motion produced no commit"
      cat "$COUT" >&2
    elif grep -q 'MEMORY RECONCILIATION' "$CERR"; then
      fail "(8p) the reconciliation CRIED WOLF on a close plus a new memory plus a reorder"
      cat "$CERR" >&2
    elif ! git -C "$CREPO" show HEAD:.beads/issues.jsonl | grep -q '"key":"mem-key-11"'; then
      fail "(8p) the new memory did not reach the commit"
    else
      pass "(8p) a close, a NEW memory and a full memory reorder warn about nothing"
    fi ;;
  *) fail "(8p) ordinary forward motion was refused ($out)"; cat "$CERR" >&2 ;;
esac

rm -rf "$CBOX"

fi

# ----------------------------------------------------------------------------
# Layer 9: the truncation floor in the pre-commit hook.
#
# Layer 8's helper refuses an empty export, but a plain `git add` from the
# MAYOR checkout never goes through it — and the worker-beads block no-ops
# there by design. So the floor also lives in the hook, driven here from the
# layer-2 sandbox's PRIMARY worktree.
# ----------------------------------------------------------------------------

printf '\n[9] truncation floor: the mayor checkout cannot commit an emptied tracker\n'

TERR=/tmp/rf2-pc-trunc.err

# write_tracker COUNT PATH [TAG] — COUNT distinct JSONL rows.
write_tracker() {
  awk -v n="$1" -v tag="${3:-seed}" \
    'BEGIN{for(i=1;i<=n;i++) printf "{\"_type\":\"issue\",\"id\":\"%s-%04d\"}\n", tag, i}' \
    > "$2"
}

# stage_tracker COUNT [TAG] — write and `git add` in the mayor checkout, and
# verify the INDEX carries it before any verdict is read.
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

# trunc_rc [ARG...] — commit the staged tracker in the mayor checkout; echo the
# exit code, stderr to $TERR. Extra args pass through (9d's --no-verify).
trunc_rc() {
  ( cd "$MAYOR" && git commit -q -m 'mayor: tracker' "$@" -- .beads/issues.jsonl ) \
    >/dev/null 2>"$TERR" && echo 0 || echo $?
}

mayor_rows() {
  git -C "$MAYOR" show HEAD:.beads/issues.jsonl 2>/dev/null | awk 'END{print NR}'
}

reset_tracker() {
  ( cd "$MAYOR" && git reset -q HEAD -- .beads/issues.jsonl && git checkout -q HEAD -- .beads ) || true
}

# Seed a 200-row HEAD — a growth from the 1-row tracker layer 2 left behind.
if stage_tracker 200 base; then
  rc=$(trunc_rc)
  if [ "$rc" != "0" ] || [ "$(mayor_rows)" != "200" ]; then
    fail "(9-setup) could not seed a 200-row HEAD (exit $rc, HEAD $(mayor_rows) rows)"
    cat "$TERR" >&2 || true
  fi
fi

# 9a: THE EMPTIED EXPORT, staged by a plain `git add` in the primary worktree.
# Refused, carrying the two row counts, the regeneration rule, the repair and
# the named escape.
before=$(git -C "$MAYOR" rev-parse HEAD)
if stage_tracker 0 empty; then
  rc=$(trunc_rc)
  after=$(git -C "$MAYOR" rev-parse HEAD)
  if [ "$rc" = "0" ]; then
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
fi
reset_tracker

# 9b: A FLOOR, NOT A RATCHET. 179 of 200 loses more than a tenth and is
# refused — without the empty-only regeneration stanza, which would be wrong
# advice here.
before=$(git -C "$MAYOR" rev-parse HEAD)
if stage_tracker 179 base; then
  rc=$(trunc_rc)
  after=$(git -C "$MAYOR" rev-parse HEAD)
  if [ "$rc" = "0" ]; then
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
fi
reset_tracker

# 9c: 180 of 200 is exactly the checkpoint script's threshold, so this pins the
# boundary rather than a comfortable margin.
before=$(git -C "$MAYOR" rev-parse HEAD)
if stage_tracker 180 base; then
  rc=$(trunc_rc)
  after=$(git -C "$MAYOR" rev-parse HEAD)
  if [ "$rc" = "0" ] && [ "$before" != "$after" ] && [ "$(mayor_rows)" = "180" ]; then
    pass "(9c) a genuine export at exactly 9/10 of HEAD commits normally"
  else
    fail "(9c) FALSE POSITIVE: an export at the threshold was refused (exit $rc, HEAD $(mayor_rows) rows)"
    cat "$TERR" >&2
  fi
fi

# 9d: THE ESCAPE. A genuine mass delete is the operator's call: re-seed a full
# HEAD, then empty it through the escape the message names.
if stage_tracker 200 base; then
  rc=$(trunc_rc)
fi
before=$(git -C "$MAYOR" rev-parse HEAD)
if stage_tracker 0 empty; then
  rc=$(trunc_rc --no-verify)
  after=$(git -C "$MAYOR" rev-parse HEAD)
  if [ "$rc" = "0" ] && [ "$before" != "$after" ] && [ "$(mayor_rows)" = "0" ]; then
    pass "(9d) the named escape works: --no-verify lands a deliberate mass delete"
  else
    fail "(9d) the escape named in the refusal message does not work (exit $rc, HEAD $(mayor_rows) rows)"
    cat "$TERR" >&2
  fi
fi

# 9e: NO NAG ON A FRESH CHECKOUT. A HEAD with no rows can lose none. The 0-row
# HEAD is established here, not inherited from 9d, so a 9d regression cannot
# surface as a misleading 9e failure.
( cd "$MAYOR" && write_tracker 0 .beads/issues.jsonl empty \
    && git add .beads/issues.jsonl \
    && git commit -q --no-verify -m 'mayor: establish an empty HEAD' -- .beads/issues.jsonl ) \
  >/dev/null 2>&1 || true
before=$(git -C "$MAYOR" rev-parse HEAD)
if stage_tracker 3 fresh; then
  rc=$(trunc_rc)
  after=$(git -C "$MAYOR" rev-parse HEAD)
  if [ "$rc" = "0" ] && [ "$before" != "$after" ]; then
    pass "(9e) a 3-row tracker over a 0-row HEAD passes: nothing to lose, no nag"
  else
    fail "(9e) FALSE POSITIVE: the floor fired over an empty HEAD (exit $rc)"
    cat "$TERR" >&2
  fi
fi

# 9f: a mayor commit that stages no tracker path is untouched (and MEMORY.md
# is on the mayor allow-list). The index is cleared of the tracker first: the
# block keys on what is STAGED.
reset_tracker
scenario_9f() {
  cd "$MAYOR"
  printf 'operator memory\n' > MEMORY.md
  git add MEMORY.md
  git commit -q -m 'mayor: memory only'
}
rc=$( ( scenario_9f ) >/dev/null 2>"$TERR" && echo 0 || echo $?)
if [ "$rc" = "0" ]; then
  pass "(9f) a commit staging no tracker path is untouched by the floor"
else
  fail "(9f) FALSE POSITIVE: an unrelated permitted commit was refused (exit $rc)"
  cat "$TERR" >&2
fi
rm -f "$TERR" "$SMOKE_ERR"

# ----------------------------------------------------------------------------
# Layer 10: the AI-ATTRIBUTION guard.
#
# Every layer above grades staged PATHS; this one grades the commit MESSAGE,
# the PR BODY and the AUTHOR/COMMITTER. Each permitted case is paired with a
# refused one of the SAME SHAPE, because a detector that matches nothing passes
# every "there must be none here" clause silently. The CI arm grades the branch
# delta, so an offender on the BASE must not red a clean branch (10k): such
# commits are on main and trunk history is not rewritten.
# ----------------------------------------------------------------------------

printf '\n[10] AI-attribution guard: the message surface\n'

ATTR_LIB="$REPO_ROOT/scripts/git-hooks/lib/check-commit-attribution.sh"
ATTR_HOOK="$REPO_ROOT/scripts/git-hooks/commit-msg"
ATTR_CI="$REPO_ROOT/scripts/check-commit-attribution.sh"

AERR=/tmp/rf2-attr-test.err

# A quote assertion asks whether the diagnostic carries the line's exact BYTES,
# so its fixed-string grep runs under LC_ALL=C: a locale-aware grep can miss a
# needle carrying the marker's leading emoji.
#
# The forbidden shapes, built at runtime so the marker carries its real emoji
# without a non-ASCII byte in this file. Both co-author addresses are here
# because rule 2 matches the ADDRESS FAMILY.
TRAILER_COAUTHOR='Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>'
TRAILER_COAUTHOR_SHORT='Co-Authored-By: Claude <claude@anthropic.com>'
TRAILER_SESSION='Claude-Session: https://claude.ai/code/session_01MAi87DChEUnjARRXTV1pZX'
TRAILER_GENWITH=$(printf '\360\237\244\226 Generated with [Claude Code](https://claude.com/claude-code)')
# The bare session URL, as the harness writes it into a PR body.
TRAILER_SESSION_URL='https://claude.ai/code/session_01MAi87DChEUnjARRXTV1pZX'
# git's scissors line: `git commit -v` writes the diff below it, and the
# commit-msg hook reads the message before git strips it.
SCISSORS='# ------------------------ >8 ------------------------'

run_attr_lib() {
  # stdin: a commit message. Echoes EXIT=<n>; the refusal lands on stderr.
  # `set +e` for the dash reason given at run_beads_lib.
  (
    set +e
    . "$ATTR_LIB"
    check_commit_attribution "${1:-commit}"
    echo "EXIT=$?"
  )
}

# 10a: each forbidden shape is refused, and the refusal QUOTES the line.
for t in "$TRAILER_COAUTHOR" "$TRAILER_COAUTHOR_SHORT" "$TRAILER_SESSION" \
         "$TRAILER_GENWITH" "$TRAILER_SESSION_URL"; do
  key=$(printf '%s' "$t" | cut -c1-24)
  out=$(printf 'fix(thing): a real change\n\n%s\n' "$t" | run_attr_lib 2>"$AERR") || true
  case "$out" in
    *EXIT=1*)
      if LC_ALL=C grep -Fq "$t" "$AERR" && grep -q 'AI attribution' "$AERR"; then
        pass "(10a) refused and quoted: $key..."
      else
        fail "(10a) refused but the diagnostic never quoted the line: $key..."
        cat "$AERR" >&2
      fi
      ;;
    *) fail "(10a) FALSE GREEN: attribution line not detected: $key... ($out)" ;;
  esac
done

# 10b: a HUMAN co-author stays permitted — including one whose NAME contains
# "claude". Same key, same column as 10a's first two; only the ADDRESS differs.
for t in 'Co-Authored-By: Mike Thompson <mike@example.invalid>' \
         'Co-Authored-By: Claude Martin <claude.martin@example.invalid>' \
         'Co-Authored-By: Jean-Claude Martin <jcm@example.invalid>'; do
  key=$(printf '%s' "$t" | cut -c1-40)
  out=$(printf 'fix(thing): a real change\n\n%s\n' "$t" | run_attr_lib 2>"$AERR") || true
  case "$out" in
    *EXIT=0*)
      if [ ! -s "$AERR" ]; then
        pass "(10b) permitted, silently: $key..."
      else
        fail "(10b) a permitted co-author line produced diagnostics: $key..."
        cat "$AERR" >&2
      fi
      ;;
    *) fail "(10b) FALSE POSITIVE: a human co-author was refused: $key... ($out)" ;;
  esac
done

# 10c: THE ESCAPE HATCH. The same line indented by one space is permitted, so
# a message can document the rule or cite an offending commit.
out=$(printf 'docs: record the attribution rule\n\n %s\n' "$TRAILER_COAUTHOR" \
  | run_attr_lib 2>"$AERR") || true
case "$out" in
  *EXIT=0*) pass "(10c) an INDENTED quotation of the trailer is permitted" ;;
  *) fail "(10c) the escape hatch is closed: an indented quotation was refused ($out)"
     cat "$AERR" >&2 ;;
esac

# 10d: git's own furniture cannot trip it. The marker under a `#` is the case
# that matters: rule 3 admits decoration in front, so only the `#` exemption
# stops `git commit -v` on a diff touching this guard being refused.
out=$(printf 'feat: thing\n\n# %s\n+%s\n# %s\n' \
  "$TRAILER_COAUTHOR" "$TRAILER_SESSION" "$TRAILER_GENWITH" \
  | run_attr_lib 2>"$AERR") || true
case "$out" in
  *EXIT=0*) pass "(10d) template comment lines are permitted, the marker included" ;;
  *) fail "(10d) FALSE POSITIVE: git's own message furniture was refused ($out)"
     cat "$AERR" >&2 ;;
esac

# ...and nothing below the scissors line is graded, including `+` lines that
# ADD the trailers.
out=$(printf 'docs: describe the guard\n\n%s\n# Do not modify or remove the line above.\ndiff --git a/x b/x\n+%s\n+%s\n' \
  "$SCISSORS" "$TRAILER_GENWITH" "$TRAILER_COAUTHOR" \
  | run_attr_lib 2>"$AERR") || true
case "$out" in
  *EXIT=0*) pass "(10d) a git commit -v scissors tail is not graded as message" ;;
  *) fail "(10d) FALSE POSITIVE: the scissors tail was graded as message ($out)"
     cat "$AERR" >&2 ;;
esac

# --- End-to-end: the hook, driven by real `git commit` -----------------------

ABOX=$(mktemp -d "${TMPDIR:-/tmp}/rf2-attr-sandbox-XXXXXX")
AREPO="$ABOX/repo"

(
  mkdir -p "$AREPO/scripts/git-hooks/lib"
  cd "$AREPO"
  git init -q -b main
  git config user.email 'attr-test@example.invalid'
  git config user.name 'attr-test'
  git config commit.gpgsign false
  cp "$ATTR_LIB" scripts/git-hooks/lib/
  echo seed > seed.txt
  git add .
  git commit -q -m 'seed'
) >/dev/null 2>&1

ACOMMON=$(git -C "$AREPO" rev-parse --git-common-dir)
case "$ACOMMON" in /*|[A-Za-z]:[\\/]*) ;; *) ACOMMON="$AREPO/$ACOMMON" ;; esac
mkdir -p "$ACOMMON/hooks"
cp "$ATTR_HOOK" "$ACOMMON/hooks/commit-msg"
chmod +x "$ACOMMON/hooks/commit-msg"

# attr_commit MESSAGE [GIT-COMMIT-ARG...] — commit in the sandbox with the
# given message; echoes EXIT=<n>, stderr to $AERR.
attr_commit() {
  _msg="$1"; shift
  printf '%s\n' "$_msg" > "$ABOX/msg.txt"
  (
    cd "$AREPO"
    date +%s%N > churn.txt 2>/dev/null || echo churn > churn.txt
    git add churn.txt
    git commit "$@" -q -F "$ABOX/msg.txt"
  ) >/dev/null 2>"$AERR" && echo "EXIT=0" || echo "EXIT=$?"
}

# 10f: THE BITE. A real `git commit` carrying the trailer is refused, quoting
# the line and citing the rule.
out=$(attr_commit "$(printf 'fix: something\n\n%s\n%s\n' "$TRAILER_COAUTHOR" "$TRAILER_SESSION")")
case "$out" in
  EXIT=0) fail "(10f) FALSE GREEN: git commit with AI attribution was allowed" ;;
  *)
    if grep -Fq "$TRAILER_SESSION" "$AERR" && grep -q 'CLAUDE.md' "$AERR"; then
      pass "(10f) commit-msg hook BITES: refused, quotes the line, cites the rule"
    else
      fail "(10f) refused, but the diagnostic is missing the line or the rule"
      cat "$AERR" >&2
    fi
    ;;
esac

# 10g: NO FALSE POSITIVE. An ordinary commit in the same repo is untouched.
out=$(attr_commit 'fix: an ordinary change')
case "$out" in
  EXIT=0) pass "(10g) an ordinary commit passes with the hook installed" ;;
  *) fail "(10g) FALSE POSITIVE: an ordinary commit was refused ($out)"; cat "$AERR" >&2 ;;
esac

# 10h: `--no-verify` is the operator escape, and it works. It also plants the
# offender on the base that 10k needs.
out=$(attr_commit "$(printf 'chore: deliberate override\n\n%s\n' "$TRAILER_COAUTHOR")" --no-verify)
case "$out" in
  EXIT=0) pass "(10h) --no-verify lands a deliberate override" ;;
  *) fail "(10h) the documented escape does not work ($out)"; cat "$AERR" >&2 ;;
esac

# --- The CI arm --------------------------------------------------------------
#
# Invoked at its real path from the sandbox, as CI runs it: the detector
# resolves beside the script, the commits from the current repository.

run_attr_ci() {
  ( cd "$AREPO" && GITHUB_EVENT_NAME="${ATTR_EVENT:-pull_request}" \
      sh "$ATTR_CI" "$@" ) >/dev/null 2>"$AERR" && echo "EXIT=0" || echo "EXIT=$?"
}

git -C "$AREPO" branch -f base main >/dev/null 2>&1

# 10i: a branch that INTRODUCES an offending commit is refused, naming it.
git -C "$AREPO" checkout -q -b feature/dirty base >/dev/null 2>&1
out=$(attr_commit "$(printf 'feat: branch work\n\n%s\n' "$TRAILER_SESSION")" --no-verify)
case "$out" in
  EXIT=0) : ;;
  *) fail "(10i-setup) could not plant the offending commit ($out)"; cat "$AERR" >&2 ;;
esac
dirty_sha=$(git -C "$AREPO" rev-parse --short=10 HEAD)
out=$(run_attr_ci base)
case "$out" in
  EXIT=0) fail "(10i) FALSE GREEN: the CI arm passed a branch carrying attribution" ;;
  *)
    if grep -Fq "$dirty_sha" "$AERR" && grep -Fq "$TRAILER_SESSION" "$AERR"; then
      pass "(10i) CI arm refuses the branch and names the commit ($dirty_sha)"
    else
      fail "(10i) refused, but the report names neither the sha nor the line"
      cat "$AERR" >&2
    fi
    ;;
esac

# 10j: a clean branch off the same base passes.
git -C "$AREPO" checkout -q -b feature/clean base >/dev/null 2>&1
out=$(attr_commit 'feat: clean branch work')
case "$out" in
  EXIT=0) : ;;
  *) fail "(10j-setup) could not make the clean commit ($out)"; cat "$AERR" >&2 ;;
esac
out=$(run_attr_ci base)
case "$out" in
  EXIT=0) pass "(10j) CI arm passes a clean branch" ;;
  *) fail "(10j) FALSE POSITIVE: a clean branch was refused ($out)"; cat "$AERR" >&2 ;;
esac

# 10k: THE RANGE. The base carries 10h's offender, and this branch is still
# green: the gate grades the branch delta, not all of history.
base_offender=$(git -C "$AREPO" log base --format='%H' -1)
if git -C "$AREPO" log -1 --format=%B "$base_offender" \
     | grep -Fq "$TRAILER_COAUTHOR"; then
  out=$(run_attr_ci base)
  case "$out" in
    EXIT=0) pass "(10k) an offending commit on the BASE does not red a clean branch" ;;
    *) fail "(10k) the gate graded history behind the merge base ($out)"
       cat "$AERR" >&2 ;;
  esac
else
  fail "(10k-setup) the base does not carry the planted offender — test is vacuous"
fi

# 10l: no base ref -> FAILS CLOSED, rather than inspecting nothing and
# reporting the same silent zero as a clean branch.
out=$(run_attr_ci)
case "$out" in
  EXIT=0) fail "(10l) FALSE GREEN: the CI arm passed with no base ref" ;;
  *)
    if grep -q 'certifies nothing' "$AERR"; then
      pass "(10l) no base ref -> fails closed and says why"
    else
      fail "(10l) failed, but not with the fail-closed diagnostic"; cat "$AERR" >&2
    fi
    ;;
esac

# 10m: an unresolvable base ref -> fails closed too (the shallow-clone shape).
out=$(run_attr_ci no/such/ref)
case "$out" in
  EXIT=0) fail "(10m) FALSE GREEN: the CI arm passed on an unresolvable base" ;;
  *) pass "(10m) an unresolvable base ref -> fails closed" ;;
esac

# 10n: enforcement is pull_request only. A prefix assignment on a FUNCTION call
# persists in some shells and not others, so the variable is set and cleared.
ATTR_EVENT=push
out=$(run_attr_ci base)
ATTR_EVENT=pull_request
case "$out" in
  EXIT=0) pass "(10n) a non-pull_request event passes with an explanatory line" ;;
  *) fail "(10n) the guard enforced outside a pull request ($out)"; cat "$AERR" >&2 ;;
esac

# 10p: THE PR-BODY ARM, the only arm that can see a body. The body arrives on
# STDIN as data, as test.yml passes it through an `env:` value.
run_attr_body() {
  ( GITHUB_EVENT_NAME="${ATTR_EVENT:-pull_request}" sh "$ATTR_CI" --pr-body ) \
    >/dev/null 2>"$AERR" && echo "EXIT=0" || echo "EXIT=$?"
}

out=$(printf 'Fixes the thing.\n\n%s\n%s\n' "$TRAILER_GENWITH" "$TRAILER_SESSION_URL" \
  | run_attr_body)
case "$out" in
  EXIT=0) fail "(10p) FALSE GREEN: a PR body carrying AI attribution was allowed" ;;
  *)
    if LC_ALL=C grep -Fq "$TRAILER_GENWITH" "$AERR" && grep -Fq "$TRAILER_SESSION_URL" "$AERR" \
       && grep -q 'pull request body' "$AERR"; then
      pass "(10p) PR-body arm refuses a body and quotes BOTH offending lines"
    else
      fail "(10p) refused, but the report misses a line or the PR-body context"
      cat "$AERR" >&2
    fi
    ;;
esac

# A clean body passes — an ordinary URL alone at column 0 included, so rule 4
# is a rule about one URL and not about `https://`.
out=$(printf 'Fixes the thing.\n\nhttps://github.com/day8/re-frame2/pull/9317\n' \
  | run_attr_body)
case "$out" in
  EXIT=0)
    if [ ! -s "$AERR" ]; then
      pass "(10p) PR-body arm passes a clean body, an ordinary URL at column 0 included"
    else
      fail "(10p) a clean PR body produced diagnostics"; cat "$AERR" >&2
    fi
    ;;
  *) fail "(10p) FALSE POSITIVE: a clean PR body was refused ($out)"; cat "$AERR" >&2 ;;
esac

# An EMPTY body passes: unlike a missing commit range, a missing body really
# contains nothing to grade.
out=$(printf '' | run_attr_body)
case "$out" in
  EXIT=0) pass "(10p) PR-body arm passes an empty body" ;;
  *) fail "(10p) FALSE POSITIVE: an empty PR body was refused ($out)"; cat "$AERR" >&2 ;;
esac

# 10q: PROSE ABOUT THE TRAILERS IS NOT A TRAILER. A trailer is a line that IS
# the attribution; prose MENTIONS it — and a worker's compliance statement
# lands at column 0 of its PR body by design. Each permitted row takes a
# different exit from the detector:
#
#   COMPLIANCE   letters before `Generated with` (rule 3's head anchor)
#   URL_NAMED    the session URL, then more words (rule 4's whole-line anchor)
#   TAIL         no head, ends on a FILENAME carrying `claude` (the tail anchor)
#   POLICY_LINK  ends on the tool's own link, but carries no marker
#   POLICY_URL   no head, ends on a URL whose PATH, not host, names CLAUDE.md
#
# The refused pair holds the tail anchor to the documented rule: a line opening
# on `Generated with` that ends on the tool's own HOST is the marker, whatever
# the path.
PROSE_COMPLIANCE='No Co-Authored-By: Claude and no Generated with [Claude Code] trailer, in the commit message or in this description.'
PROSE_URL_NAMED="$TRAILER_SESSION_URL is the bare URL the harness writes, and this body does not carry one."
PROSE_COMPLIANCE_TAIL='Generated with [Claude Code] was declined per CLAUDE.md.'
PROSE_POLICY_LINK='Trailers declined per https://claude.com/claude-code'
PROSE_POLICY_URL='Generated with [Claude Code] was declined per https://github.com/day8/re-frame2/blob/main/CLAUDE.md.'
MARKER_BARE_URL='Generated with Claude Code https://claude.com/claude-code'
MARKER_URL_PATHED='Generated with Claude Code https://claude.com/day8/re-frame2/blob/main/README.md'

for t in "$PROSE_COMPLIANCE" "$PROSE_URL_NAMED" "$PROSE_COMPLIANCE_TAIL" \
         "$PROSE_POLICY_LINK" "$PROSE_POLICY_URL"; do
  key=$(printf '%s' "$t" | cut -c1-40)
  out=$(printf 'Fixes the thing.\n\n%s\n' "$t" | run_attr_body)
  case "$out" in
    EXIT=0)
      if [ ! -s "$AERR" ]; then
        pass "(10q) a PR body that NAMES the trailers is permitted: $key..."
      else
        fail "(10q) permitted, but it produced diagnostics: $key..."; cat "$AERR" >&2
      fi
      ;;
    *) fail "(10q) FALSE POSITIVE: prose about the trailers reds the PR: $key... ($out)"
       cat "$AERR" >&2 ;;
  esac
done

for t in "$MARKER_BARE_URL" "$MARKER_URL_PATHED"; do
  key=$(printf '%s' "$t" | cut -c1-40)
  out=$(printf 'Fixes the thing.\n\n%s\n' "$t" | run_attr_body)
  case "$out" in
    EXIT=0) fail "(10q) DISARMED: a body CARRYING the marker was allowed: $key..." ;;
    *)
      if grep -Fq "$t" "$AERR"; then
        pass "(10q) a body that CARRIES the marker is refused, and quotes it: $key..."
      else
        fail "(10q) refused, but the diagnostic never quoted the marker: $key..."
        cat "$AERR" >&2
      fi
      ;;
  esac
done

# 10r: THE MARKER WITH EITHER VERB, AND DECORATION ON BOTH SIDES. Platforms
# write `Generated by` as well as `Generated with`, often italicised; a closing
# mark written APART from the link becomes the last word and must read as
# decoration, not prose.
MARKER_ITALIC_BY="_Generated by [Claude Code](${TRAILER_SESSION_URL})_"
MARKER_TRAILING_DECOR='_Generated with [Claude Code](https://claude.com/claude-code) _'
PROSE_TAIL_DECORATED='Generated by [Claude Code] was declined per CLAUDE.md. _'

for t in "$MARKER_ITALIC_BY" "$MARKER_TRAILING_DECOR"; do
  key=$(printf '%s' "$t" | cut -c1-32)
  out=$(printf 'Fixes the thing.\n\n%s\n' "$t" | run_attr_body)
  case "$out" in
    EXIT=0) fail "(10r) FALSE GREEN: a PR body carrying the marker was allowed: $key..." ;;
    *)
      if grep -Fq "$t" "$AERR"; then
        pass "(10r) PR-body arm refuses the marker and quotes it: $key..."
      else
        fail "(10r) refused, but the diagnostic never quoted the marker: $key..."
        cat "$AERR" >&2
      fi
      ;;
  esac
  # The same line through the detector in the commit context, which is what
  # the commit-msg hook calls.
  out=$(printf 'fix(thing): a real change\n\n%s\n' "$t" | run_attr_lib 2>"$AERR") || true
  case "$out" in
    *EXIT=1*) pass "(10r) the commit-message detector refuses it too: $key..." ;;
    *) fail "(10r) FALSE GREEN: the commit-message detector allowed the marker: $key... ($out)" ;;
  esac
done

# And through a real `git commit`, so the installed hook is shown seeing it.
out=$(attr_commit "$(printf 'fix: something\n\n%s\n' "$MARKER_ITALIC_BY")")
case "$out" in
  EXIT=0) fail "(10r) FALSE GREEN: git commit carrying the italic marker was allowed" ;;
  *)
    if grep -Fq "$MARKER_ITALIC_BY" "$AERR"; then
      pass "(10r) commit-msg hook refuses the italic marker and quotes it"
    else
      fail "(10r) refused, but the hook's diagnostic never quoted the marker"
      cat "$AERR" >&2
    fi
    ;;
esac
git -C "$AREPO" reset -q --hard >/dev/null 2>&1

# The permitted half: `Generated by`, decoration after it, and a FILENAME as
# the last word with letters — the tail loop must stop there.
out=$(printf 'Fixes the thing.\n\n%s\n' "$PROSE_TAIL_DECORATED" | run_attr_body)
case "$out" in
  EXIT=0)
    if [ ! -s "$AERR" ]; then
      pass "(10r) a PR body NAMING the marker, decoration after it, is permitted"
    else
      fail "(10r) permitted, but it produced diagnostics"; cat "$AERR" >&2
    fi
    ;;
  *) fail "(10r) FALSE POSITIVE: prose about the marker reds the PR ($out)"
     cat "$AERR" >&2 ;;
esac

# The bare session URL alone on its line stays refused.
out=$(printf 'Fixes the thing.\n\n%s\n' "$TRAILER_SESSION_URL" | run_attr_body)
case "$out" in
  EXIT=0) fail "(10r) FALSE GREEN: the bare session URL alone on its line was allowed" ;;
  *) pass "(10r) the bare session URL alone on its line is still refused" ;;
esac

# 10s: THE IDENTITY. A commit recorded as `Claude <noreply@anthropic.com>`
# reads as the assistant's work whatever its message says. Paired as the text
# rules are: a human, a human NAMED Claude and another address at the same
# domain pass, and so does a MESSAGE naming the address in prose.
ASSISTANT_IDENT='Claude <noreply@anthropic.com>'
HUMAN_IDENT='Mike Thompson <mike@example.invalid> 1790000000 +1000'
PROSE_IDENT_MSG=$(printf 'docs: name the platform identity\n\nCommits recorded as %s are refused; this one is not.\nnoreply@anthropic.com is that identity'"'"'s address.\n' "$ASSISTANT_IDENT")

run_attr_ident() {
  (
    set +e
    . "$ATTR_LIB"
    check_commit_identity "$1" "$2" "${3:-commit}"
    echo "EXIT=$?"
  )
}

out=$(run_attr_ident 'Claude <NoReply@Anthropic.COM> 1790000000 +1000' "$HUMAN_IDENT" 2>"$AERR")
case "$out" in
  *EXIT=1*) pass "(10s) the address is compared case-insensitively" ;;
  *) fail "(10s) FALSE GREEN: a case-varied assistant address was allowed ($out)" ;;
esac

for id in "$HUMAN_IDENT" \
          'Claude Martin <claude.martin@example.invalid> 1790000000 +1000' \
          'Jane Doe <jane@anthropic.com> 1790000000 +1000'; do
  key=$(printf '%s' "$id" | cut -c1-40)
  out=$(run_attr_ident "$id" "$id" 2>"$AERR")
  case "$out" in
    *EXIT=0*)
      if [ ! -s "$AERR" ]; then
        pass "(10s) permitted, silently: $key..."
      else
        fail "(10s) a permitted identity produced diagnostics: $key..."; cat "$AERR" >&2
      fi
      ;;
    *) fail "(10s) FALSE POSITIVE: an ordinary identity was refused: $key... ($out)"
       cat "$AERR" >&2 ;;
  esac
done

# --- The identity through the hook, driven by real `git commit` --------------

git -C "$AREPO" checkout -q -b feature/ident-hook base >/dev/null 2>&1

out=$(attr_commit 'feat: an ordinary message' --author="$ASSISTANT_IDENT")
case "$out" in
  EXIT=0) fail "(10s) FALSE GREEN: git commit --author naming the assistant was allowed" ;;
  *)
    if grep -Fq "author:    $ASSISTANT_IDENT" "$AERR" && ! grep -Fq 'committer:' "$AERR" \
       && grep -Fq -- '--reset-author' "$AERR"; then
      pass "(10s) commit-msg hook refuses an assistant --author, naming only it, and the fix"
    else
      fail "(10s) refused, but the diagnostic misnames the identity or misses the fix"; cat "$AERR" >&2
    fi
    ;;
esac
git -C "$AREPO" reset -q --hard >/dev/null 2>&1

out=$(
  GIT_COMMITTER_NAME=Claude
  GIT_COMMITTER_EMAIL=noreply@anthropic.com
  export GIT_COMMITTER_NAME GIT_COMMITTER_EMAIL
  attr_commit 'feat: an ordinary message'
)
case "$out" in
  EXIT=0) fail "(10s) FALSE GREEN: a commit whose COMMITTER is the assistant was allowed" ;;
  *)
    if grep -Fq "committer: $ASSISTANT_IDENT" "$AERR" && ! grep -Fq 'author:' "$AERR"; then
      pass "(10s) commit-msg hook refuses an assistant committer, naming only it"
    else
      fail "(10s) refused, but the diagnostic misnames the committer"; cat "$AERR" >&2
    fi
    ;;
esac
git -C "$AREPO" reset -q --hard >/dev/null 2>&1

out=$(attr_commit "$PROSE_IDENT_MSG")
case "$out" in
  EXIT=0) pass "(10s) a commit whose MESSAGE names the address in prose passes" ;;
  *) fail "(10s) FALSE POSITIVE: prose naming the address was refused ($out)"; cat "$AERR" >&2 ;;
esac

# An amend keeps the inherited assistant author, so it is refused.
out=$(attr_commit 'feat: platform-authored work' --author="$ASSISTANT_IDENT" --no-verify)
case "$out" in
  EXIT=0) : ;;
  *) fail "(10s-setup) could not plant an assistant-authored commit ($out)"; cat "$AERR" >&2 ;;
esac
out=$( (cd "$AREPO" && git commit -q --amend --no-edit) >/dev/null 2>"$AERR" \
  && echo "EXIT=0" || echo "EXIT=$?")
case "$out" in
  EXIT=0) fail "(10s) FALSE GREEN: amending kept the assistant as author and was allowed" ;;
  *) pass "(10s) amending an assistant-authored commit is refused" ;;
esac

# --- The identity through the CI arm ------------------------------------------

# 10t: a branch introducing a commit recorded as the assistant is refused,
# naming the commit, the identity and the fix — and that fix turns it green.
git -C "$AREPO" checkout -q -b feature/ident-author base >/dev/null 2>&1
out=$(attr_commit 'feat: platform-authored work' --author="$ASSISTANT_IDENT" --no-verify)
case "$out" in
  EXIT=0) : ;;
  *) fail "(10t-setup) could not plant an assistant-authored commit ($out)"; cat "$AERR" >&2 ;;
esac
ident_sha=$(git -C "$AREPO" rev-parse --short=10 HEAD)
out=$(run_attr_ci base)
case "$out" in
  EXIT=0) fail "(10t) FALSE GREEN: the CI arm passed a commit authored as the assistant" ;;
  *)
    if grep -Fq "$ident_sha" "$AERR" && grep -Fq "author:    $ASSISTANT_IDENT" "$AERR" \
       && grep -Fq -- '--reset-author' "$AERR"; then
      pass "(10t) CI arm refuses an assistant author, naming commit, identity and fix ($ident_sha)"
    else
      fail "(10t) refused, but the report misses the sha, the identity or the fix"
      cat "$AERR" >&2
    fi
    ;;
esac

out=$( (cd "$AREPO" && git rebase -q --exec 'git commit -q --amend --no-edit --reset-author' base) \
  >/dev/null 2>"$AERR" && echo "EXIT=0" || echo "EXIT=$?")
case "$out" in
  EXIT=0) : ;;
  *) fail "(10t-setup) the documented rebuild did not run ($out)"; cat "$AERR" >&2 ;;
esac
out=$(run_attr_ci base)
case "$out" in
  EXIT=0) pass "(10t) the rebuild the report names turns the branch green" ;;
  *) fail "(10t) the branch is still refused after the documented rebuild ($out)"; cat "$AERR" >&2 ;;
esac

git -C "$AREPO" checkout -q -b feature/ident-committer base >/dev/null 2>&1
out=$(
  GIT_COMMITTER_NAME=Claude
  GIT_COMMITTER_EMAIL=noreply@anthropic.com
  export GIT_COMMITTER_NAME GIT_COMMITTER_EMAIL
  attr_commit 'feat: platform-committed work' --no-verify
)
case "$out" in
  EXIT=0) : ;;
  *) fail "(10t-setup) could not plant an assistant-committed commit ($out)"; cat "$AERR" >&2 ;;
esac
out=$(run_attr_ci base)
case "$out" in
  EXIT=0) fail "(10t) FALSE GREEN: the CI arm passed a commit committed as the assistant" ;;
  *)
    if grep -Fq "committer: $ASSISTANT_IDENT" "$AERR" && ! grep -Fq "author:    $ASSISTANT_IDENT" "$AERR"; then
      pass "(10t) CI arm refuses an assistant committer, and names only the committer"
    else
      fail "(10t) refused, but the report misnames the identity"; cat "$AERR" >&2
    fi
    ;;
esac

# Planted with --no-verify so this grades the CI arm on its own.
git -C "$AREPO" checkout -q -b feature/ident-prose base >/dev/null 2>&1
out=$(attr_commit "$PROSE_IDENT_MSG" --no-verify)
case "$out" in
  EXIT=0) : ;;
  *) fail "(10t-setup) could not make the prose commit ($out)"; cat "$AERR" >&2 ;;
esac
out=$(run_attr_ci base)
case "$out" in
  EXIT=0) pass "(10t) CI arm passes a commit whose MESSAGE names the address in prose" ;;
  *) fail "(10t) FALSE POSITIVE: prose naming the address red the branch ($out)"; cat "$AERR" >&2 ;;
esac

# THE RANGE, for the identity: a base carrying a commit recorded as the
# assistant does not red a clean branch.
git -C "$AREPO" checkout -q -b base-ident base >/dev/null 2>&1
out=$(attr_commit 'chore: platform-authored history' --author="$ASSISTANT_IDENT" --no-verify)
case "$out" in
  EXIT=0) : ;;
  *) fail "(10t-setup) could not plant an assistant-authored base commit ($out)"; cat "$AERR" >&2 ;;
esac
git -C "$AREPO" checkout -q -b feature/clean-ident base-ident >/dev/null 2>&1
out=$(attr_commit 'feat: clean work on that base')
case "$out" in
  EXIT=0) : ;;
  *) fail "(10t-setup) could not make the clean commit ($out)"; cat "$AERR" >&2 ;;
esac
if [ "$(git -C "$AREPO" log -1 --format=%ae base-ident)" = 'noreply@anthropic.com' ]; then
  out=$(run_attr_ci base-ident)
  case "$out" in
    EXIT=0) pass "(10t) a commit recorded as the assistant on the BASE does not red a clean branch" ;;
    *) fail "(10t) the gate graded an identity behind the merge base ($out)"; cat "$AERR" >&2 ;;
  esac
else
  fail "(10t-setup) the base does not carry the planted identity — test is vacuous"
fi

git -C "$AREPO" checkout -q main >/dev/null 2>&1 || true
rm -rf "$ABOX"
rm -f "$AERR"

# ----------------------------------------------------------------------------
# Layer 11: the MCP-staleness block in post-merge, through
# post-merge-hook-test.cjs. A missing `node` fails rather than skips, so the
# layer cannot pass without running.
# ----------------------------------------------------------------------------

printf '\n[11] post-merge MCP-staleness block\n'
if ! command -v node >/dev/null 2>&1; then
  fail "(11) node is not on PATH, so post-merge-hook-test.cjs cannot run"
elif node "$SCRIPT_DIR/post-merge-hook-test.cjs"; then
  pass "(11) post-merge-hook-test.cjs passed"
else
  fail "(11) post-merge-hook-test.cjs reported a failure"
fi

# ----------------------------------------------------------------------------
# Summary
# ----------------------------------------------------------------------------

printf '\nSummary: %d passed, %d failed\n' "$pass_count" "$fail_count"
if [ "$fail_count" -ne 0 ]; then
  exit 1
fi
exit 0
