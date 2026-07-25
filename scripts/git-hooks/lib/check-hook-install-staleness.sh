#!/usr/bin/env sh
# scripts/git-hooks/lib/check-hook-install-staleness.sh
#
# One advisory, one home (rf2-zt65l). The hooks in `<git-common-dir>/hooks`
# are COPIES of `scripts/git-hooks/*`; a pull updates the sources and leaves
# the copies behind. This library asks the installer whether the copies are
# current and, when they are not, prints the repair command.
#
# TWO HOOKS SOURCE IT, because git splits the pull paths between them:
#
#   post-merge    pulls that MERGE or FAST-FORWARD — `git pull`,
#                 `git pull --ff-only`, and `git pull --rebase` on a branch
#                 with no commits of its own (git takes a `--ff-only` merge
#                 shortcut there rather than running rebase).
#
#   post-rewrite  pulls that REBASE — `git pull --rebase` with a local commit
#                 to replay. A rebase NEVER invokes post-merge, so while the
#                 advisory lived only in post-merge it was silent on exactly
#                 the completion path AGENTS.md and CLAUDE.md mandate for
#                 every worker. That is the rf2-zt65l audit reopen (PR #6921):
#                 reproduced in two throwaway clones, `git pull --rebase`
#                 landed hook drift with zero output while the `--no-rebase`
#                 control printed the warning.
#
# Measured on git 2.53: a diverged `--rebase` pull fires `post-rewrite rebase`
# and not `post-merge`; a `--rebase` pull with nothing to replay fires
# `post-merge`. Between the two, every pull that lands a change is covered.
# `scripts/git-hooks/test-pre-commit.sh` layer 7 drives real pulls of both
# shapes rather than invoking the hooks by hand.
#
# ADVISORY ONLY. It never fails the git command it rides on: a pull or rebase
# that dies over a hook copy would be worse than the drift. It prints to
# stderr and returns 0 always.
#
# This file is a pure shell library (no `set -e`, no global state mutation).
#
# Cross-platform: POSIX sh; runs under Git Bash on Windows, macOS, Linux.
# No bashisms (`[[`, arrays, `<<<`).

# rf2_warn_stale_hook_install REPO_ROOT [TRIGGER]
#
# Runs the installer's own `--check` (the single source of truth for "is this
# checkout guarded?") and prints the repair block if it fails. TRIGGER names
# the git operation that just ran — `merge` or `rebase` — so the reader knows
# which pull shape created the drift. Silent, and cheap, on a healthy install.
rf2_warn_stale_hook_install() {
  _rf2_hs_root="${1:-}"
  _rf2_hs_trigger="${2:-pull}"

  if [ -z "$_rf2_hs_root" ]; then
    return 0
  fi

  _rf2_hs_installer="$_rf2_hs_root/scripts/install-git-hooks.sh"
  if [ ! -f "$_rf2_hs_installer" ]; then
    # Not a re-frame2 checkout, or a checkout that predates the installer.
    # Nothing to say.
    unset _rf2_hs_root _rf2_hs_trigger _rf2_hs_installer
    return 0
  fi

  # Invoke via `sh` — a Windows checkout may not carry the exec bit.
  if sh "$_rf2_hs_installer" --check >/dev/null 2>&1; then
    unset _rf2_hs_root _rf2_hs_trigger _rf2_hs_installer
    return 0
  fi

  printf '\n[re-frame2] git hooks on disk are stale or incomplete after this\n' >&2
  printf '  %s — the local commit guards may not be armed. Repair\n' "$_rf2_hs_trigger" >&2
  printf '  (idempotent, seconds; linked worktrees share one hooks directory,\n' >&2
  printf '  so a single run re-arms them all):\n\n' >&2
  printf '      sh scripts/install-git-hooks.sh\n\n' >&2
  printf '  What drifted:  sh scripts/install-git-hooks.sh --check\n\n' >&2

  unset _rf2_hs_root _rf2_hs_trigger _rf2_hs_installer
  return 0
}
