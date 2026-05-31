# scripts/assert-worker-worktree.ps1 - worker worktree edit guard (Windows).
#
# Windows PowerShell sibling of the POSIX-sh PRIMARY at
# scripts/assert-worker-worktree.sh. IDENTICAL CONTRACT:
#   - On success prints WORKTREE_ROOT=, BRANCH= (unless detached), and
#     "OK: worker worktree guard passed.", then exits 0.
#   - Refuses the MAYOR checkout (the repository's primary worktree).
#   - Refuses any checkout that is not a worker worktree under the
#     worktree parent directory.
#   - Refuses when -ExpectedRoot is given and the git root does not match.
#
# CROSS-PLATFORM ROOT DETECTION (no hardcoded maintainer paths):
#   - MAYOR ROOT is derived as the repository's PRIMARY worktree - the
#     first entry of `git worktree list --porcelain`. Marker-free and
#     portable; mirrors the mayor-marker pattern the git hooks use.
#   - WORKTREE PARENT defaults to `<dir-containing-mayor>/re-frame2-worktrees`,
#     derived from the mayor root's own location - never a baked-in path.
#   - Both overridable via RF2_MAYOR_ROOT / RF2_WORKTREE_PARENT (env) or
#     the -MayorRoot / -WorktreeParent flags.
param(
  [string]$ExpectedRoot = "",
  [string]$WorktreeParent = $env:RF2_WORKTREE_PARENT,
  [string]$MayorRoot = $env:RF2_MAYOR_ROOT
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-ExistingPath([string]$Path, [string]$Label) {
  try {
    return (Resolve-Path -LiteralPath $Path).ProviderPath
  }
  catch {
    throw "$Label does not exist: $Path"
  }
}

function Normalize-Path([string]$Path) {
  return [System.IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
}

# The repository's PRIMARY worktree is always the first `worktree <path>`
# line of `git worktree list --porcelain`. That is the mayor checkout.
function Get-PrimaryWorktree() {
  $lines = (& git worktree list --porcelain 2>$null)
  if ($LASTEXITCODE -ne 0) { return "" }
  foreach ($line in $lines) {
    if ($line -like 'worktree *') {
      return $line.Substring('worktree '.Length)
    }
  }
  return ""
}

$gitRootRaw = (& git rev-parse --show-toplevel 2>$null)
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($gitRootRaw)) {
  throw "Not inside a git checkout. cd to the worker worktree before editing."
}

$gitRoot = Normalize-Path (Resolve-ExistingPath $gitRootRaw "Git root")

# Derive the mayor root (primary worktree) unless overridden via env/flag.
if ([string]::IsNullOrWhiteSpace($MayorRoot)) {
  $MayorRoot = Get-PrimaryWorktree
  if ([string]::IsNullOrWhiteSpace($MayorRoot)) {
    throw "Could not determine the mayor (primary) worktree via 'git worktree list'. Set RF2_MAYOR_ROOT or pass -MayorRoot."
  }
}
$mayorCheckoutRoot = Normalize-Path (Resolve-ExistingPath $MayorRoot "Mayor checkout")

# Derive the worktree parent from the mayor's own location unless overridden.
# Sibling of the mayor checkout named re-frame2-worktrees - no hardcoded path.
if ([string]::IsNullOrWhiteSpace($WorktreeParent)) {
  $WorktreeParent = Join-Path (Split-Path -Parent $mayorCheckoutRoot) "re-frame2-worktrees"
}
$worktreeParentRoot = Normalize-Path (Resolve-ExistingPath $WorktreeParent "Worker worktree parent")

$gitRootComparison = $gitRoot.ToLowerInvariant()
$worktreeParentComparison = $worktreeParentRoot.ToLowerInvariant()
$mayorRootComparison = $mayorCheckoutRoot.ToLowerInvariant()
$worktreePrefix = $worktreeParentComparison + [System.IO.Path]::DirectorySeparatorChar

if ($gitRootComparison -eq $mayorRootComparison) {
  throw "Refusing to edit from mayor checkout: $gitRoot. Switch to a worker worktree under $worktreeParentRoot."
}

if (-not $gitRootComparison.StartsWith($worktreePrefix)) {
  throw "Refusing to edit outside worker worktree parent. git root: $gitRoot; expected under: $worktreeParentRoot."
}

if (-not [string]::IsNullOrWhiteSpace($ExpectedRoot)) {
  $expectedRootPath = Normalize-Path (Resolve-ExistingPath $ExpectedRoot "Expected worker root")
  if ($gitRootComparison -ne $expectedRootPath.ToLowerInvariant()) {
    throw "Wrong worker worktree. git root: $gitRoot; expected: $expectedRootPath."
  }
}

$branch = (& git branch --show-current 2>$null)
if ($LASTEXITCODE -ne 0) {
  $branch = ""
}

Write-Output "WORKTREE_ROOT=$gitRoot"
if (-not [string]::IsNullOrWhiteSpace($branch)) {
  Write-Output "BRANCH=$branch"
}
Write-Output "OK: worker worktree guard passed."
