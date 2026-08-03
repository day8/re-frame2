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
#     REMOVE_FAILED=         the tree is CLEAN, so this really is a file lock
#                            and really is worth retrying.
#   -DryRun reports the same partition up front as WOULD_NEED_FORCE= (all
#   build output, sweepable) or WOULD_REFUSE_KEEP= (needs a human first).
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
function Get-WorktreeDirt([string]$Path) {
  return @(& git -C $Path status --porcelain 2>$null) |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
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
function Write-RemoveFailure([string]$Path) {
  $dirt = @(Get-WorktreeDirt $Path)
  if ($dirt.Count -eq 0) {
    Write-Warning "REMOVE_FAILED=$Path (tree is CLEAN, so this is a file lock: links are already disarmed; safe to retry once it clears)"
    return
  }
  Write-Warning "REMOVE_REFUSED_DIRTY=$Path"
  Write-AnnotatedDirt $dirt -AsWarning
  # One Write-Warning per line: a multi-line string gets the WARNING prefix
  # only on its first line and a blank line between every other.
  $tail = if ((Get-UndisposableLines $dirt).Count -eq 0) {
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

    Write-Output "SELF_TEST=PASSED link unlinked with its target intact ($after), and the canary caught nested-only loss."
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
  if ($roster -notcontains $wt.ToLowerInvariant()) {
    Write-Error "Not a registered worktree of ${mayorRootPath}: $wt (run 'git worktree list'; 'git worktree prune' clears stale entries)."
    exit 1
  }

  # 1. Disarm every link, before anything recursive runs over the tree.
  $foundAny = $false
  foreach ($nm in (Find-NodeModules $wt)) {
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
  if (-not $foundAny) { Write-Output "NO_LINKS=$wt" }

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
    if ($Force -or $ForceDisposable) { & git -C $mayorRootPath worktree remove --force $wt }
    else { & git -C $mayorRootPath worktree remove $wt }
    if ($LASTEXITCODE -eq 0) {
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

if ($failed) { exit 1 }

Write-Output "OK: worktree removal complete."
