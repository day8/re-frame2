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
#   5. Re-snapshot the canary and FAIL LOUDLY if any count dropped.
#   Steps 2 and 4 are never chained into one command: the point is that the
#   removal runs against a tree with no live reparse point left in it.
#
# USAGE
#   powershell -ExecutionPolicy Bypass -File scripts/remove-worker-worktree.ps1 <path> [<path>...]
#   ... -DryRun     report what would be disarmed/removed; change nothing
#   ... -Force      pass --force to `git worktree remove` (dirty worktree)
#   ... -SelfTest   prove the disarm against a throwaway junction in a temp dir
#   -MayorRoot <path> / RF2_MAYOR_ROOT   override mayor-root derivation
param(
  # Position = 0 is load-bearing: ValueFromRemainingArguments alone does NOT
  # make a parameter positional, so an unnamed path would otherwise bind to
  # the first implicitly-positional parameter below (-MayorRoot) and leave
  # this empty — the script then reports its own usage and removes nothing.
  [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
  [string[]]$Worktree = @(),
  [switch]$Force,
  [switch]$DryRun,
  [switch]$SelfTest,
  [string]$MayorRoot = $env:RF2_MAYOR_ROOT
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Normalize-Path([string]$Path) {
  return [System.IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
}

# Entry count of a directory (hidden entries included). Returns the string
# MISSING when the directory is not there at all - MISSING against a numeric
# "before" is itself a canary failure.
function Get-EntryCount([string]$Path) {
  if (-not (Test-Path -LiteralPath $Path)) { return 'MISSING' }
  return ([string](@(Get-ChildItem -LiteralPath $Path -Force -ErrorAction SilentlyContinue)).Count)
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

# ---------------------------------------------------------------------------
# -SelfTest - prove the disarm rather than asserting it.
#
# Builds a throwaway target directory with a known entry count, junctions a
# node_modules at it, disarms, and requires BOTH: the link is gone AND the
# target still has every entry. Never touches a real node_modules.
# ---------------------------------------------------------------------------
if ($SelfTest) {
  $stRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("rf2-disarm-" + [System.Guid]::NewGuid().ToString('N'))
  try {
    $stTarget = Join-Path $stRoot 'dummy-target'
    $stLink = Join-Path $stRoot 'wt\implementation\node_modules'
    New-Item -ItemType Directory -Force -Path $stTarget | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $stRoot 'wt\implementation') | Out-Null
    1..5 | ForEach-Object { Set-Content -LiteralPath (Join-Path $stTarget "f$_.txt") -Value 'canary' }
    $before = Get-EntryCount $stTarget
    New-Item -ItemType Junction -Path $stLink -Target $stTarget | Out-Null

    if (-not (Test-IsLink $stLink)) {
      Write-Error "SELF_TEST=FAILED could not create a junction to test against: $stLink"
      exit 1
    }
    Write-Output "SELF_TEST target=$stTarget entries_before=$before"
    if (-not (Disarm-Link $stLink)) {
      Write-Error "SELF_TEST=FAILED the link survived the disarm: $stLink"
      exit 1
    }
    $after = Get-EntryCount $stTarget
    Write-Output "SELF_TEST link_removed=yes entries_after=$after"
    if ($after -ne $before) {
      Write-Error "SELF_TEST=FAILED the disarm deleted THROUGH the link: $before -> $after entries."
      exit 1
    }
    Write-Output "SELF_TEST=PASSED link unlinked, target intact ($after entries)."
    exit 0
  }
  finally {
    # Any junction is already gone by here; this only clears real temp dirs.
    Remove-Item -LiteralPath $stRoot -Recurse -Force -ErrorAction SilentlyContinue
  }
}

if ($Worktree.Count -eq 0) {
  Write-Error "usage: remove-worker-worktree.ps1 <worktree-path>... [-Force] [-DryRun]`n       remove-worker-worktree.ps1 -SelfTest"
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
# Every REAL (non-link) node_modules in the mayor checkout, with its entry
# count. A junction we failed to detect still shows up here as a drop.
# ---------------------------------------------------------------------------
$canary = [ordered]@{}
foreach ($nm in (Find-NodeModules $mayorRootPath)) {
  if (Test-IsLink $nm) { continue }   # a link in the MAYOR tree is not a canary
  $key = Normalize-Path $nm
  $canary[$key] = Get-EntryCount $nm
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
  if ($DryRun) {
    Write-Output "WOULD_REMOVE=$wt"
  }
  else {
    if ($Force) { & git -C $mayorRootPath worktree remove --force $wt }
    else { & git -C $mayorRootPath worktree remove $wt }
    if ($LASTEXITCODE -eq 0) {
      Write-Output "REMOVED=$wt"
    }
    else {
      # A Windows file lock (a shadow-cljs / Node process still holding the
      # tree) is the common cause and is harmless: the links are already
      # removed, so retrying later cannot delete through anything.
      Write-Warning "REMOVE_FAILED=$wt (links are already disarmed; safe to retry once the lock clears)"
      $failed = $true
    }
  }
}

# ---------------------------------------------------------------------------
# Re-check the canary. A drop means something deleted through a link.
# ---------------------------------------------------------------------------
$canaryBad = $false
foreach ($key in $canary.Keys) {
  $after = Get-EntryCount $key
  Write-Output "CANARY_AFTER=$key $after"
  if ($after -ne $canary[$key]) {
    $canaryBad = $true
    Write-Warning "CANARY_FAILED: $key went from $($canary[$key]) to $after entries."
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
