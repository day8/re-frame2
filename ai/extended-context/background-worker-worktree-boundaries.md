---
name: background-worker-worktree-boundaries
summary: Keep background workers inside their assigned git worktree.
when_to_use: Before dispatching or resuming any worker that may edit files.
---

# Background Worker Worktree Boundaries

## Trigger

Use this whenever the mayor dispatches, resumes, or redirects a background
worker that might edit files in a non-primary git worktree.

Do not rely on "the worker knows its worktree". Workers can understand the
assignment and still leak edits if the edit tool is not scoped to the shell
working directory.

## Root Cause

Workers understood that they were assigned to separate worktrees, and their shell
commands generally used the right `workdir`. The failure mode was subtler:
`apply_patch` has no explicit `workdir`, so relative patch paths can resolve
against the session/default checkout, not the shell worktree the worker just used.

The practical lesson: "use your worktree" is not a strong enough prompt. Patch
tools and shell tools do not share the same scoping guarantees.

## Procedure

1. Give the worker one absolute assigned worktree path.
2. Tell the worker the mayor checkout path is forbidden for edits.
3. Require a toplevel check immediately before every edit.
4. Require edit-tool paths that explicitly target the assigned worktree.
5. Tell the worker to stop and report if any edit lands outside the worktree.

## The Safe Delegation Pattern

The reliable pattern is not "cd to the worktree, then patch repo-relative
paths". That failed. The reliable pattern is:

- shell commands use `workdir` / `cd` inside the assigned worktree;
- edit tools use paths that are relative to the session root and explicitly
  walk into the assigned worktree;
- the worker checks both the worker worktree and the mayor checkout immediately
  after the first edit.

For this repo, Codex sessions normally start at the mayor checkout:

```text
C:\Users\miket\code\re-frame2
```

So an edit in a sibling worker worktree should use patch filenames like:

```text
../re-frame2-worktrees/<WORKTREE_NAME>/tools/causa/src/...
```

not:

```text
tools/causa/src/...
```

Repo-relative paths are forbidden for worker `apply_patch` calls unless the
agent session root is itself the assigned worker worktree.

## Mandatory Prompt

Paste this block into every editing-worker dispatch or resume:

```text
WORKTREE BOUNDARY - MANDATORY

Your assigned worktree is:
<ASSIGNED_WORKTREE>

The mayor checkout is:
C:\Users\miket\code\re-frame2

Never edit the mayor checkout.

Shell workdir is not enough protection. apply_patch has no workdir, so relative
patch paths can land in the mayor checkout.

Before every file edit, run:

Get-Location; git rev-parse --show-toplevel; git status --short --branch

Only edit if git rev-parse --show-toplevel prints exactly:
<ASSIGNED_WORKTREE>

When using apply_patch or any edit tool, use absolute file paths under
<ASSIGNED_WORKTREE> if the tool accepts them.

If the edit tool does not accept absolute paths, use paths relative to the
session root that explicitly target the worker worktree. In this repo, that
usually means:

../re-frame2-worktrees/<WORKTREE_NAME>/<repo-relative-path>

Example:

../re-frame2-worktrees/causa-mcp-server-split-rf2-abc12/tools/causa/src/day8/re_frame2_causa/panels/mcp_server.cljs

Never use repo-relative edit paths such as:

tools/causa/src/day8/re_frame2_causa/panels/mcp_server.cljs

unless `git rev-parse --show-toplevel` for the agent session root itself is the
assigned worktree.

After the first edit, immediately run:

git -C <ASSIGNED_WORKTREE> status --short --branch
git -C C:\Users\miket\code\re-frame2 status --short --branch

Continue only if the worker worktree is dirty and the mayor checkout did not
receive code edits.

If any edit lands outside <ASSIGNED_WORKTREE>, stop immediately and report it.
Do not repair, restore, clean up, commit, or push until the mayor tells you what
to do.
```

## Mayor Checks

- Check the mayor checkout after worker dispatches: `git status --short --branch`.
- If mayor checkout gains unexpected code changes, interrupt the worker before
  doing more work.
- Preserve accidental changes into the worker worktree before restoring the mayor
  checkout.
- Only restore specific known files after preservation. Do not use broad
  destructive reset commands.

## Mayor Merge Protocol

When merging a worker PR, do not ask `gh` to delete the branch as part of the
merge. A worker branch is usually checked out in that worker's git worktree, so
local branch deletion can fail even after the GitHub merge succeeds.

Use this sequence instead:

1. Inspect changed files and confirm the selected CI jobs match the new testing
   scheme.
2. Merge without branch deletion: `gh pr merge <number> --squash --admin`.
3. Verify the PR is merged: `gh pr view <number> --json state,mergedAt,mergeCommit`.
4. Delete only the remote branch when safe:
   `git push origin --delete <headRefName>`.
5. Leave local worker worktree and branch cleanup until the worker is closed and
   the worktree is known clean.
