# scripts/remove-worker-worktree.ps1 - remove a worker worktree WITHOUT
# deleting through its node_modules junction (Windows).
#
# Windows PowerShell sibling of the POSIX-sh PRIMARY at
# scripts/remove-worker-worktree.sh. IDENTICAL CONTRACT - same stdout lines,
# same exit codes, same refusals.
#
# WHY THIS SCRIPT EXISTS (rf2-rxkht - second recorded occurrence).
#   A worker worktree cannot compile without a node_modules, so the
#   established convention is to point `<worktree>\implementation\node_modules`
#   at the mayor checkout's REAL node_modules - a directory junction. `git
#   worktree remove` then walks the tree it is deleting, follows that reparse
#   point, and deletes the TARGET's contents: the mayor checkout's real
#   node_modules is emptied and every local build in the repo breaks until
#   `npm ci` restores it. Measured on Windows 11, against a throwaway junction
#   pointing at a dummy directory:
#
#     git worktree remove --force <wt>            target 5 entries -> 0
#     rm -rf <junction>            (Git Bash)     target 5 entries -> 0
#     cmd /c rmdir <junction>                     target 5 entries -> 5
#     [IO.Directory]::Delete(<junction>, $false)  target 5 entries -> 5
#
#   The hazard was written down twice - in the hygiene procedure and in an
#   agent memory - and recurred anyway. Prose does not disarm a junction, so
#   the guard lives here, in the tooling the hygiene path has to run through.
#
# WHAT IT DOES, in the one order that is safe:
#   1. Snapshot every real node_modules in the MAYOR checkout (the canary).
#   2. Find every node_modules under the worktree that is a LINK and remove
#      the LINK ONLY - a NON-RECURSIVE delete, never Remove-Item -Recurse.
#   3. Verify each link is actually gone before continuing.
#   4. THEN `git worktree remove`.
#   5. Re-snapshot the canary and FAIL LOUDLY if the signature moved.
#   Steps 2 and 4 are never chained into one command: the point is that the
#   removal runs against a tree with no live reparse point left in it.
#
# USAGE
#   powershell -ExecutionPolicy Bypass -File scripts/remove-worker-worktree.ps1 <path> [<path>...]
#   ... -DryRun     report what would be disarmed/removed; change nothing
#   ... -Force      force-remove a dirty worktree, DELETING whatever it holds.
#                   You have looked; you are sure.
#   ... -ForceDisposable
#                   force-remove ONLY when every untracked path is build or
#                   gate output; refuse the tree otherwise. This is the flag a
#                   bulk hygiene sweep wants.
#   ... -SelfTest   prove the disarm against a throwaway junction in a temp dir
#   -MayorRoot <path> / RF2_MAYOR_ROOT   override mayor-root derivation
#
# CONTRACT (identical to the POSIX primary; see its header for the full list).
#   CANARY_BEFORE=/CANARY_AFTER= carry a <signature>, which is
#   `<immediate-entries>/<recursive-files>/<sentinels-present>` e.g. `103/2933/2`.
#   The recursive count and the sentinels are load-bearing: the immediate-entry
#   count alone cannot see files vanishing from under packages whose
#   directories survive.
#
#   A FAILED removal is classified rather than guessed at (rf2-p0m6m). Nine
#   worktrees were read as file-locked and waited out for two days; every one
#   was simply dirty, and no amount of waiting adds a flag. So:
#     REMOVE_REFUSED_DIRTY=  git refused; the paths are listed and tagged
#                            [build output] or [KEEP].
#     REMOVE_REFUSED_KEEP=   -ForceDisposable stopped at unreviewed work.
#     REMOVE_FAILED=         the tree is CLEAN and INTACT, so this really is a
#                            file lock and really is worth retrying.
#     REMOVE_PARTIAL=        a HUSK (rf2-k3j2w) - see below. Exit 3.
#   -DryRun reports the same partition up front as WOULD_NEED_FORCE= (all
#   build output, sweepable) or WOULD_REFUSE_KEEP= (needs a human first).
#
#   THE FOURTH OUTCOME: A PARTIAL REMOVAL (rf2-k3j2w). `git worktree remove`
#   is not atomic on Windows. It can deregister the worktree and delete its
#   `.git`, then hit a file lock on the directory itself and stop - leaving a
#   HUSK: every source file still on disk, no worktree behind it. Measured on
#   Windows 11 against a throwaway worktree with one open file handle held
#   inside it:
#
#     git worktree remove --force <wt>   ->  exit 255, "failed to delete
#                                            '<wt>': Invalid argument"
#     git worktree list                  ->  <wt> ALREADY GONE
#     <wt>/.git                          ->  ALREADY DELETED
#     <wt>/**                            ->  every other file still present
#
#   The state is genuinely hard to see, which is why it earned a name here:
#     - `git worktree list` does not mention it - already deregistered;
#     - `git worktree prune` does not clear it - prune drops ADMIN records for
#       directories that vanished, and here the opposite happened;
#     - `git -C <husk> status --porcelain` FAILS and prints nothing, which the
#       clean/dirty split above read as "tree is CLEAN" and therefore as
#       REMOVE_FAILED, "safe to retry once it clears". Retrying is futile: the
#       registered-worktree check refuses it, forever;
#     - to a human `ls` it is indistinguishable from a live worktree.
#   And it is not a near-miss. A husk kills a running gate exactly as a
#   completed removal does - "the removal failed" is NOT "the worker was
#   unaffected". Both entry points report it: a removal that fails this way,
#   and a later invocation handed a path that is already a husk.
#
#   Exit codes split on WHAT THE CALLER SHOULD DO NEXT:
#     0  done.
#     1  refused or failed, worktree INTACT - this script is still the right
#        tool (retry when the lock clears; -ForceDisposable for build output;
#        a human for [KEEP] paths).
#     2  usage error.
#     3  PARTIAL - already gutted and deregistered; this script can do nothing
#        more with it. 3 wins over 1 when both occur in one run, because it is
#        the outcome needing a different remedy.
param(
  # Position = 0 is load-bearing: ValueFromRemainingArguments alone does NOT
  # make a parameter positional, so an unnamed path would otherwise bind to
  # the first implicitly-positional parameter below (-MayorRoot) and leave
  # this empty — the script then reports its own usage and removes nothing.
  [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
  [string[]]$Worktree = @(),
  [switch]$Force,
  [switch]$ForceDisposable,
  [switch]$DryRun,
  [switch]$SelfTest,
  [string]$MayorRoot = $env:RF2_MAYOR_ROOT
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Normalize-Path([string]$Path) {
  return [System.IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
}

# Run git and hand back its output and exit code, NEVER throwing.
#
# This script's whole job is to handle git commands that fail — a refused
# removal, a status inside a directory that is no longer a repository — so a
# failing git is normal control flow here, not an exception. Windows PowerShell
# disagrees: with $ErrorActionPreference = "Stop" (set above, and wanted for
# every cmdlet) a native command that writes ANYTHING to stderr raises a
# terminating NativeCommandError, and neither `2>$null` nor `2>&1` reliably
# suppresses it. Observed while adding the husk probes: `git status` inside a
# husk aborted the script with a NativeCommandError instead of returning empty,
# and the same latent trap sat under `git worktree remove` — the one call in
# this file guaranteed to write to stderr on the very path that must be
# classified rather than crashed on (rf2-k3j2w).
#
# Lowering the preference INSIDE a function is scoped to that function's
# dynamic extent, so the strict setting is restored on return with no
# bookkeeping, and cmdlet errors elsewhere keep terminating as before.
function Invoke-GitQuiet([string[]]$GitArgs) {
  $ErrorActionPreference = 'Continue'
  $out = & git @GitArgs 2>&1
  return [pscustomobject]@{
    Output   = @($out | ForEach-Object { [string]$_ })
    ExitCode = $LASTEXITCODE
  }
}

# Paths inside a node_modules whose disappearance is damage on its own, whatever
# the counts say. Kept to two: one directory every install has, one package this
# repo cannot build without.
$script:NmSentinels = @('.bin', 'shadow-cljs\package.json')

# A node_modules HEALTH SIGNATURE: <immediate-entries>/<recursive-files>/<sentinels>.
# Returns the string MISSING when the directory is not there at all - MISSING
# against a real "before" is itself a canary failure.
#
# The immediate-entry count alone was the original canary and it is half-blind:
# a partial recursive delete can empty the files *under* every package while
# leaving all the top-level package directories standing, so before -eq after
# reports healthy over material damage. The recursive file count sees exactly
# that shape, and the sentinels see a targeted loss two counts could coincide
# on. All three are cheap - 2933 files in 165ms over this repo's mayor
# node_modules - so there is no reason to settle for the blind one.
#
# -Recurse does not descend through reparse points without -FollowSymlink, so
# the count cannot wander out of the directory it is measuring.
function Get-NodeModulesSignature([string]$Path) {
  if (-not (Test-Path -LiteralPath $Path)) { return 'MISSING' }
  $entries = @(Get-ChildItem -LiteralPath $Path -Force -ErrorAction SilentlyContinue).Count
  $files = @(Get-ChildItem -LiteralPath $Path -Recurse -Force -File -ErrorAction SilentlyContinue).Count
  $sentinels = 0
  foreach ($s in $script:NmSentinels) {
    if (Test-Path -LiteralPath (Join-Path $Path $s)) { $sentinels += 1 }
  }
  return "$entries/$files/$sentinels"
}

# What `git worktree remove` will refuse over: modified or untracked files.
# Empty means clean. This is the discriminator behind a failed removal
# (rf2-p0m6m) - see Write-RemoveFailure.
# CALLERS MUST WRAP THIS IN @(). PowerShell unrolls an array on the way out of
# a function, so a single dirty path arrives at the call site as a bare string
# and `.Count` on it throws under Set-StrictMode - which is exactly the
# one-leftover-log case this function exists to report. An @() inside the
# function cannot prevent that; one at the call site can.
#
# ONLY MEANINGFUL ON AN INTACT WORKTREE. On a husk the git call fails, prints
# nothing, and this returns empty - identical to clean. Every caller therefore
# checks Test-WorktreeIsIntact FIRST.
# The EXIT CODE decides, not the output: on a husk the merged output holds
# git's fatal text, which is not a list of dirty paths. See Invoke-GitQuiet.
function Get-WorktreeDirt([string]$Path) {
  $r = Invoke-GitQuiet @('-C', $Path, 'status', '--porcelain')
  if ($r.ExitCode -ne 0) { return @() }
  return @($r.Output) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
}

# Is there still a git worktree behind this directory?
#
# The one honest discriminator for a HUSK (rf2-k3j2w): a partially failed
# `git worktree remove` deletes the worktree's `.git` before it deletes the
# files, so a husk's files survive with nothing behind them. Seen in the wild
# twice on 2026-08-05, sitting in a worktree parent - `ls` shows a full
# checkout, `rev-parse` exits 128, `git worktree list` has never heard of them
# and `git worktree prune` reports nothing to do.
#
# Note this is a READ of git's own state, not a guess about who is using the
# tree. Whether an AGENT is still alive is not knowable from here and this
# script does not try - see the reaping rule in docs/the-mayor-method.
#
# BOTH halves are load-bearing. `rev-parse` alone walks UP the directory tree,
# so a husk sitting inside another checkout would answer with THAT repo and
# read as intact; the Test-Path catches it, because a linked worktree always
# has a `.git` file at its root and a husk is exactly the tree that lost one.
# And Test-Path alone would accept a dangling or truncated `.git`. -SelfTest
# proves each clause against the husk shape only that clause can see.
function Test-WorktreeIsIntact([string]$Path) {
  if (-not (Test-Path -LiteralPath (Join-Path $Path '.git'))) { return $false }
  # Via Invoke-GitQuiet: the dangling-`.git` case reaches this line with git
  # about to write a fatal to stderr.
  return ((Invoke-GitQuiet @('-C', $Path, 'rev-parse', '--git-dir')).ExitCode -eq 0)
}

# Is one `git status --porcelain` line safe to delete unreviewed?
#
# Only UNTRACKED (`??`) build and gate output qualifies. A modified tracked
# file never does, whatever its path: three worktrees were carrying uncommitted
# source and doc edits when this was written, and a blanket force would have
# taken them silently. Neither does a hand-written note - band-ymi6j held four
# `ladder-*.md` analysis files for an OPEN bead. A worktree that will not reap
# is clutter; deleting somebody's unreviewed work is not.
#
# The patterns are shape-based rather than a roster of names on purpose: four
# different log directories (`logs/`, `bench-logs/`, `.gate-logs/`, `.wtlogs/`)
# turned up across nine worktrees, because every worker invents its own, and a
# roster would have missed two of them. Anything unmatched is NOT disposable -
# the rule fails closed.
#
# StartsWith, not -like: in a PowerShell wildcard `?` matches ANY character, so
# '?? *' would also match the three-character ' M ' prefix of a modified file -
# precisely the line this must never call disposable.
$script:DisposablePatterns = @('*logs/', 'out/', '*/out/', '.shadow-cljs/', '*/.shadow-cljs/', '*.log')

function Test-IsDisposableLine([string]$Line) {
  if (-not $Line.StartsWith('?? ')) { return $false }
  $p = $Line.Substring(3)
  foreach ($pat in $script:DisposablePatterns) {
    if ($p -like $pat) { return $true }
  }
  return $false
}

# The dirt lines that are NOT disposable. Empty means everything present is
# build or gate output and the tree can be swept without a human reading it.
# Callers wrap in @() - see Get-WorktreeDirt.
function Get-UndisposableLines([string[]]$Dirt) {
  return @($Dirt | Where-Object { -not (Test-IsDisposableLine $_) })
}

# Each dirt line tagged with the decision, so a reader sees WHY a tree was
# swept or refused instead of re-applying the rule by eye.
function Write-AnnotatedDirt([string[]]$Dirt, [switch]$AsWarning) {
  foreach ($line in $Dirt) {
    $tag = if (Test-IsDisposableLine $line) { '[build output]' } else { '[KEEP]        ' }
    if ($AsWarning) { Write-Warning "  $tag $line" } else { Write-Output "  $tag $line" }
  }
}

# Is this path a reparse point (junction or symlink) rather than a real
# directory? `-Force` is required or hidden/system reparse points are missed.
function Test-IsLink([string]$Path) {
  $item = Get-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
  if ($null -eq $item) { return $false }
  return (-not [string]::IsNullOrEmpty($item.LinkType))
}

# Every node_modules under $Root, WITHOUT descending into any of them:
# descending into a junction is the very walk this script exists to avoid,
# and descending into a real one is thousands of wasted stats.
#
# The scan is DEPTH-UNBOUNDED on purpose. A depth cap is the kind of guess
# that fails silently — a junction one level deeper than the cap is not
# disarmed, and the canary then only REPORTS the damage instead of
# preventing it. Skipping `.git` (never a home for a node_modules, and the
# entire cost of the walk) buys the whole tree cheaply.
function Find-NodeModules([string]$Root) {
  $found = New-Object System.Collections.Generic.List[string]
  $frontier = New-Object System.Collections.Generic.List[string]
  $frontier.Add($Root)
  while ($frontier.Count -gt 0) {
    $next = New-Object System.Collections.Generic.List[string]
    foreach ($dir in $frontier) {
      $children = @(Get-ChildItem -LiteralPath $dir -Directory -Force -ErrorAction SilentlyContinue)
      foreach ($child in $children) {
        if ($child.Name -eq 'node_modules') {
          $found.Add($child.FullName)          # prune: never descend into it
        }
        elseif ($child.Name -eq '.git') {
          continue                             # skip: cost, never a link home
        }
        elseif ([string]::IsNullOrEmpty($child.LinkType)) {
          $next.Add($child.FullName)           # never descend through a link
        }
      }
    }
    $frontier = $next
  }
  return $found
}

# THE DISARM. Remove a LINK, never its target.
#
# [IO.Directory]::Delete($p, $false) is NON-RECURSIVE - the $false is the
# whole point. It drops the junction and never touches what the junction
# points at. It is deliberately not Remove-Item -Recurse and never `rm -rf`,
# which follows the reparse point and empties the target - the incident this
# script exists to prevent, reproduced in the measurements above.
#
# Returns $false if the link survives, so the caller aborts rather than
# falling through to a removal that would delete through.
function Disarm-Link([string]$Path) {
  try { [System.IO.Directory]::Delete($Path, $false) } catch { }
  if (Test-Path -LiteralPath $Path) {
    # Second, equivalent non-recursive removal - `rmdir` on a junction drops
    # the link only. Belt for the case where the .NET call is refused.
    & cmd /c rmdir "$Path" 2>&1 | Out-Null
  }
  return (-not (Test-Path -LiteralPath $Path))
}

# Disarm every node_modules link under one worktree, before anything recursive
# runs over it.
#
# Extracted from the main loop so the HUSK path gets the identical protection
# (rf2-k3j2w). A husk left behind by a bare `git worktree remove` - one that
# never went through this script - can still hold an ARMED junction, and the
# only remedy left for a husk is a plain recursive delete, which is precisely
# what empties the target. Disarming before we recommend that delete is the
# difference between advice and a loaded gun. Measured against the husk of a
# throwaway worktree carrying a junction: this script reported DISARMED and
# the junction's target still held all 5 of its files afterwards, where the
# previous version refused the path and left the junction armed.
function Disarm-WorktreeLinks([string]$Root) {
  $foundAny = $false
  foreach ($nm in (Find-NodeModules $Root)) {
    if (-not (Test-IsLink $nm)) { continue }
    $foundAny = $true
    $item = Get-Item -LiteralPath $nm -Force -ErrorAction SilentlyContinue
    $linkTarget = if ($null -ne $item -and $null -ne $item.Target) { ($item.Target | Select-Object -First 1) } else { '<unresolved>' }
    if ($DryRun) {
      Write-Output "WOULD_DISARM=$nm -> $linkTarget"
      continue
    }
    if (-not (Disarm-Link $nm)) {
      Write-Error "Failed to remove the link $nm - it is still present. ABORTING before 'git worktree remove', which would delete THROUGH it into $linkTarget."
      exit 1
    }
    Write-Output "DISARMED=$nm -> $linkTarget"
  }
  if (-not $foundAny) { Write-Output "NO_LINKS=$Root" }
}

# Say WHY the removal failed instead of guessing (rf2-p0m6m).
#
# `git worktree remove` has two failure modes with OPPOSITE remedies:
#
#   dirty  fatal: '<wt>' contains modified or untracked files, use --force
#          to delete it - a refusal, non-destructive, and NO amount of waiting
#          clears it, because nothing is going to delete the worker's leftover
#          gate log for you.
#   lock   the tree is clean but a live process still holds a handle under it
#          (Windows shadow-cljs/Node) - genuinely transient; retry later.
#
# This script used to report both as "safe to retry once the lock clears".
# Nine worktrees were then read as locked and waited out across two days and
# a full session of other workers; every one of them was simply dirty, holding
# a single untracked `logs/`, `bench-logs/`, `PRBODY.md` or `*-exit.txt` the
# worker left behind. Telling the two apart is the whole fix.
# A partially removed worktree - a HUSK. Says what was already done TO the
# tree, and why this script is the wrong tool from here on (rf2-k3j2w).
function Write-Husk([string]$Path) {
  Write-Warning "REMOVE_PARTIAL=$Path"
  # One Write-Warning per line: a multi-line string gets the WARNING prefix
  # only on its first line and a blank line between every other.
  foreach ($line in @(
      'The worktree is already GUTTED: git deregistered it and deleted its .git,',
      'then could not delete the directory - a file lock taken mid-removal. The',
      'files you can still see have no worktree behind them.',
      'THIS IS A DESTROYED TREE, NOT AN UNTOUCHED ONE. If an agent was using it,',
      'its build or gate run has already been broken; a partial removal kills a',
      'running gate exactly as a complete one does (rf2-k3j2w).',
      'Retrying this script will not finish the job - there is no registered',
      "worktree left to remove, and 'git worktree prune' has nothing to clear.",
      'Any node_modules link has been disarmed, so what remains is an ordinary',
      'directory delete, once whatever holds the lock has exited:',
      "  Remove-Item -Recurse -Force $Path")) {
    Write-Warning $line
  }
}

function Write-RemoveFailure([string]$Path) {
  # A husk reads as CLEAN below - the git call fails and prints nothing - so
  # it has to be ruled out before the dirty/locked split runs (rf2-k3j2w).
  if (-not (Test-WorktreeIsIntact $Path)) {
    Write-Husk $Path
    $script:partial = $true
    return
  }
  $dirt = @(Get-WorktreeDirt $Path)
  if ($dirt.Count -eq 0) {
    Write-Warning "REMOVE_FAILED=$Path (tree is CLEAN and still INTACT, so this is a file lock: links are already disarmed; safe to retry once it clears)"
    return
  }
  Write-Warning "REMOVE_REFUSED_DIRTY=$Path"
  Write-AnnotatedDirt $dirt -AsWarning
  # One Write-Warning per line: a multi-line string gets the WARNING prefix
  # only on its first line and a blank line between every other.
  # @() around the call, exactly as Get-WorktreeDirt's header demands: the
  # function returns @(), PowerShell UNROLLS it on the way out, an empty result
  # arrives as $null, and `$null.Count` is a terminating error under
  # Set-StrictMode. Without the wrapper this line threw on every dirty tree, so
  # on Windows the REMOVE_REFUSED_DIRTY report died immediately after listing
  # the paths — swallowing the one sentence that tells a hygiene sweep whether
  # -ForceDisposable will clear the tree. Reproduced against main, 2026-08-05.
  $tail = if (@(Get-UndisposableLines $dirt).Count -eq 0) {
    @('All of it is build/gate output: -ForceDisposable will sweep the tree.')
  } else {
    @('The [KEEP] paths are NOT build output - an uncommitted edit, a note, a draft.',
      '-ForceDisposable will refuse them. Save or commit what matters first; only',
      'then is -Force (which DELETES them) the right tool.')
  }
  foreach ($line in @(
      'git refuses a worktree holding modified or untracked files. RETRYING WILL NEVER',
      'CLEAR THIS - nothing is locked and waiting does not add a flag.') + $tail) {
    Write-Warning $line
  }
}

# ---------------------------------------------------------------------------
# -SelfTest - prove the disarm and the detector, rather than asserting them.
#
#   1. Builds a throwaway target with a known signature, junctions a
#      node_modules at it, disarms, and requires BOTH: the link is gone AND
#      the target is untouched.
#   2. Proves the canary SEES the damage the old immediate-entry count was
#      blind to: files vanish from under every package while each package
#      directory stays standing.
#
# Never touches a real node_modules.
# ---------------------------------------------------------------------------
if ($SelfTest) {
  $stRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("rf2-disarm-" + [System.Guid]::NewGuid().ToString('N'))
  try {
    $stTarget = Join-Path $stRoot 'dummy-target'
    $stLink = Join-Path $stRoot 'wt\implementation\node_modules'
    New-Item -ItemType Directory -Force -Path $stTarget | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $stRoot 'wt\implementation') | Out-Null
    1..5 | ForEach-Object { Set-Content -LiteralPath (Join-Path $stTarget "f$_.txt") -Value 'canary' }
    $before = Get-NodeModulesSignature $stTarget
    New-Item -ItemType Junction -Path $stLink -Target $stTarget | Out-Null

    if (-not (Test-IsLink $stLink)) {
      Write-Error "SELF_TEST=FAILED could not create a junction to test against: $stLink"
      exit 1
    }
    Write-Output "SELF_TEST target=$stTarget signature_before=$before"
    if (-not (Disarm-Link $stLink)) {
      Write-Error "SELF_TEST=FAILED the link survived the disarm: $stLink"
      exit 1
    }
    $after = Get-NodeModulesSignature $stTarget
    Write-Output "SELF_TEST link_removed=yes signature_after=$after"
    if ($after -ne $before) {
      Write-Error "SELF_TEST=FAILED the disarm deleted THROUGH the link: $before -> $after."
      exit 1
    }

    # The nested-loss case. This is the shape a partial recursive delete leaves
    # behind, and the shape the old immediate-entry canary called healthy.
    $stNested = Join-Path $stRoot 'nested'
    New-Item -ItemType Directory -Force -Path (Join-Path $stNested 'pkg-a\lib') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $stNested 'pkg-b\lib') | Out-Null
    Set-Content -LiteralPath (Join-Path $stNested 'pkg-a\lib\index.js') -Value 'x'
    Set-Content -LiteralPath (Join-Path $stNested 'pkg-b\lib\index.js') -Value 'x'
    $nBefore = Get-NodeModulesSignature $stNested
    Remove-Item -LiteralPath (Join-Path $stNested 'pkg-a\lib\index.js') -Force
    Remove-Item -LiteralPath (Join-Path $stNested 'pkg-b\lib\index.js') -Force
    $nAfter = Get-NodeModulesSignature $stNested
    $shallowBefore = $nBefore.Split('/')[0]
    $shallowAfter = $nAfter.Split('/')[0]
    if ($shallowBefore -ne $shallowAfter) {
      Write-Error "SELF_TEST=FAILED the fixture's own top-level entry count moved ($shallowBefore -> $shallowAfter); it no longer tests what it claims."
      exit 1
    }
    if ($nBefore -eq $nAfter) {
      Write-Error "SELF_TEST=FAILED the canary is blind to nested file loss: signature stayed $nBefore."
      exit 1
    }
    Write-Output "SELF_TEST nested_loss top_level_entries_unchanged=$shallowBefore signature $nBefore -> $nAfter"

    # The HUSK detector (rf2-k3j2w), in a throwaway repo; never touches this one.
    #
    # THREE fixtures, because Test-WorktreeIsIntact has two clauses and each
    # catches a husk the other misses:
    #   outside  - a husk with no repo above it (the shape seen in the wild).
    #              rev-parse fails; this is the one whose dirt reads EMPTY, i.e.
    #              identical to a clean tree, which is how a gutted worktree
    #              came to be reported as a retryable file lock.
    #   nested   - a husk inside another checkout. rev-parse WALKS UP, finds the
    #              enclosing repo and answers happily; only the missing `.git`
    #              gives it away.
    #   dangling - `.git` present but naming an admin dir that is gone. The
    #              Test-Path check calls that intact; only rev-parse sees it.
    $stRepo = Join-Path $stRoot 'husk-repo'
    $stOutside = Join-Path $stRoot 'husk-outside'
    $stNested = Join-Path $stRepo 'husk-nested'
    New-Item -ItemType Directory -Force -Path $stRepo | Out-Null
    Push-Location $stRepo
    try {
      $null = Invoke-GitQuiet @('init', '-q', '.')
      $null = Invoke-GitQuiet @('-c', 'user.email=selftest@example.invalid', '-c', 'user.name=selftest',
                                'commit', '-q', '--allow-empty', '-m', 'init')
      $null = Invoke-GitQuiet @('worktree', 'add', '-q', $stOutside, '-b', 'husk-probe-outside')
      $null = Invoke-GitQuiet @('worktree', 'add', '-q', $stNested, '-b', 'husk-probe-nested')
    }
    finally { Pop-Location }

    if ((Test-Path -LiteralPath (Join-Path $stOutside '.git')) -and
        (Test-Path -LiteralPath (Join-Path $stNested '.git'))) {
      foreach ($stWt in @($stOutside, $stNested)) {
        if (-not (Test-WorktreeIsIntact $stWt)) {
          Write-Error "SELF_TEST=FAILED a live worktree read as not-intact: $stWt"
          exit 1
        }
        # Precisely what a partial removal leaves behind.
        Remove-Item -LiteralPath (Join-Path $stWt '.git') -Force
        if (Test-WorktreeIsIntact $stWt) {
          Write-Error "SELF_TEST=FAILED a husk read as INTACT: $stWt - it would be classified as a clean tree and a retryable lock."
          exit 1
        }
      }
      if (@(Get-WorktreeDirt $stOutside).Count -ne 0) {
        Write-Error "SELF_TEST=FAILED the husk fixture does not reproduce the ambiguity it exists to test."
        exit 1
      }
      Set-Content -LiteralPath (Join-Path $stOutside '.git') -Value "gitdir: $(Join-Path $stRoot 'no-such-admin-dir')"
      if (Test-WorktreeIsIntact $stOutside) {
        Write-Error "SELF_TEST=FAILED a dangling .git read as INTACT; the Test-Path check alone cannot see it."
        exit 1
      }
      Write-Output "SELF_TEST husk detected=yes for all three shapes (outside a repo, where Get-WorktreeDirt reads EMPTY - the trap; nested inside one, where rev-parse walks up and is fooled; and a dangling .git, where the Test-Path check is fooled)"
    }
    else {
      Write-Output "SELF_TEST husk=SKIPPED (no throwaway worktrees could be built here)"
    }

    Write-Output "SELF_TEST=PASSED link unlinked with its target intact ($after), the canary caught nested-only loss, and a husk is not mistaken for a clean tree."
    exit 0
  }
  finally {
    # Any junction is already gone by here; this only clears real temp dirs.
    Remove-Item -LiteralPath $stRoot -Recurse -Force -ErrorAction SilentlyContinue
  }
}

if ($Worktree.Count -eq 0) {
  Write-Error "usage: remove-worker-worktree.ps1 <worktree-path>... [-Force|-ForceDisposable] [-DryRun]`n       remove-worker-worktree.ps1 -SelfTest"
  exit 2
}

# ---------------------------------------------------------------------------
# Derive the MAYOR ROOT - the repository's PRIMARY worktree, which
# `git worktree list --porcelain` always emits first. Same derivation as
# scripts/assert-worker-worktree.ps1; no maintainer path is ever baked in.
# ---------------------------------------------------------------------------
if ([string]::IsNullOrWhiteSpace($MayorRoot)) {
  $lines = @(& git worktree list --porcelain 2>$null)
  foreach ($line in $lines) {
    if ($line -like 'worktree *') { $MayorRoot = $line.Substring('worktree '.Length); break }
  }
  if ([string]::IsNullOrWhiteSpace($MayorRoot)) {
    Write-Error "Could not determine the mayor (primary) worktree via 'git worktree list'. Set RF2_MAYOR_ROOT or pass -MayorRoot."
    exit 1
  }
}
$mayorRootPath = Normalize-Path $MayorRoot
if (-not (Test-Path -LiteralPath $mayorRootPath)) {
  Write-Error "Mayor root does not exist: $mayorRootPath"
  exit 1
}
Write-Output "MAYOR_ROOT=$mayorRootPath"

# ---------------------------------------------------------------------------
# CANARY, captured at runtime - never a committed number, which would rot.
# Every REAL (non-link) node_modules in the mayor checkout, with its health
# signature. A junction we failed to detect still shows up here as a drop.
# ---------------------------------------------------------------------------
$canary = [ordered]@{}
foreach ($nm in (Find-NodeModules $mayorRootPath)) {
  if (Test-IsLink $nm) { continue }   # a link in the MAYOR tree is not a canary
  $key = Normalize-Path $nm
  $canary[$key] = Get-NodeModulesSignature $nm
  Write-Output "CANARY_BEFORE=$key $($canary[$key])"
}

# The registered-worktree roster, normalised once.
$roster = @()
foreach ($line in @(& git -C $mayorRootPath worktree list --porcelain 2>$null)) {
  if ($line -like 'worktree *') {
    $roster += (Normalize-Path $line.Substring('worktree '.Length)).ToLowerInvariant()
  }
}

# ---------------------------------------------------------------------------
# Per worktree: disarm, verify, THEN remove.
# ---------------------------------------------------------------------------
$failed = $false
$script:partial = $false

foreach ($rawTarget in $Worktree) {
  if ([string]::IsNullOrWhiteSpace($rawTarget)) { continue }
  $wt = Normalize-Path $rawTarget

  # Refuse the mayor checkout outright - removing it is never the intent, and
  # it is the tree the canary is protecting.
  if ($wt.ToLowerInvariant() -eq $mayorRootPath.ToLowerInvariant()) {
    Write-Error "Refusing to remove the MAYOR checkout: $wt"
    exit 1
  }

  # Refuse anything git does not know as a linked worktree. This script never
  # deletes a directory itself; if git will not remove it, neither will we.
  #
  # One unregistered path is NOT a mistyped argument, and saying so is the
  # difference between an actionable message and a wild goose chase: a HUSK
  # left by an earlier partial removal is deregistered BY DEFINITION, so the
  # old advice here ("run 'git worktree prune'") sent the caller after an
  # entry that had already been cleared (rf2-k3j2w). Disarm it and name it.
  if ($roster -notcontains $wt.ToLowerInvariant()) {
    if ((Test-Path -LiteralPath $wt -PathType Container) -and (-not (Test-WorktreeIsIntact $wt))) {
      Disarm-WorktreeLinks $wt
      Write-Husk $wt
      $script:partial = $true
      continue
    }
    Write-Error "Not a registered worktree of ${mayorRootPath}: $wt (run 'git worktree list'; 'git worktree prune' clears stale entries)."
    exit 1
  }

  # 1. Disarm every link, before anything recursive runs over the tree.
  Disarm-WorktreeLinks $wt

  # 2. Only now remove the worktree. Never chained with the disarm.
  $dirt = @(Get-WorktreeDirt $wt)
  $keep = @(Get-UndisposableLines $dirt)

  if ($DryRun) {
    # The survey a hygiene pass actually needs: which trees it can sweep, which
    # need a human, and why - decided BEFORE anything touches them.
    if ($dirt.Count -eq 0) {
      Write-Output "WOULD_REMOVE=$wt"
    }
    elseif ($keep.Count -gt 0) {
      Write-Output "WOULD_REFUSE_KEEP=$wt ($($keep.Count) path(s) that are not build output)"
      Write-AnnotatedDirt $dirt
    }
    else {
      Write-Output "WOULD_NEED_FORCE=$wt ($($dirt.Count) path(s), all build/gate output)"
      Write-AnnotatedDirt $dirt
      Write-Output "WOULD_REMOVE=$wt"
    }
  }
  elseif ($ForceDisposable -and (-not $Force) -and $keep.Count -gt 0) {
    # The whole point of the flag: a bulk sweep stops at unreviewed work
    # instead of taking it. This is a refusal, so nothing has been deleted.
    Write-Warning "REMOVE_REFUSED_KEEP=$wt"
    Write-AnnotatedDirt $dirt -AsWarning
    foreach ($line in @(
        'The [KEEP] paths are not build or gate output. -ForceDisposable will not',
        'delete them. Save or commit what matters, then use -Force if you are certain',
        'the rest can go.')) {
      Write-Warning $line
    }
    $failed = $true
  }
  else {
    $removeArgs = if ($Force -or $ForceDisposable) {
      @('-C', $mayorRootPath, 'worktree', 'remove', '--force', $wt)
    } else {
      @('-C', $mayorRootPath, 'worktree', 'remove', $wt)
    }
    $removeResult = Invoke-GitQuiet $removeArgs
    # git's own diagnosis, verbatim and unprefixed, exactly where the POSIX
    # primary puts it. It is the first thing a reader wants and it is not ours
    # to reword.
    foreach ($l in $removeResult.Output) { [Console]::Error.WriteLine($l) }
    if ($removeResult.ExitCode -eq 0) {
      Write-Output "REMOVED=$wt"
    }
    else {
      Write-RemoveFailure $wt
      $failed = $true
    }
  }
}

# ---------------------------------------------------------------------------
# Re-check the canary. A drop means something deleted through a link.
# ---------------------------------------------------------------------------
$canaryBad = $false
foreach ($key in $canary.Keys) {
  $after = Get-NodeModulesSignature $key
  Write-Output "CANARY_AFTER=$key $after"
  if ($after -ne $canary[$key]) {
    $canaryBad = $true
    Write-Warning "CANARY_FAILED: $key went from $($canary[$key]) to $after (entries/files/sentinels)."
  }
}

if ($canaryBad) {
  Write-Error @"
A node_modules in the MAYOR checkout lost entries during this removal:
something deleted THROUGH a link that was not disarmed. Recover with
  npm ci --prefix implementation
run from $mayorRootPath, then re-check the counts before dispatching anything.
"@
  exit 1
}

# A partial removal outranks a plain failure: both are non-zero, but only one
# of them means "stop calling this script about that path" (rf2-k3j2w).
if ($script:partial) { exit 3 }

if ($failed) { exit 1 }

Write-Output "OK: worktree removal complete."
