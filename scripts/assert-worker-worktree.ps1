param(
  [string]$ExpectedRoot = "",
  [string]$WorktreeParent = $env:RF2_WORKTREE_PARENT,
  [string]$MayorRoot = $env:RF2_MAYOR_ROOT
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($WorktreeParent)) {
  $WorktreeParent = "C:\Users\miket\code\re-frame2-worktrees"
}

if ([string]::IsNullOrWhiteSpace($MayorRoot)) {
  $MayorRoot = "C:\Users\miket\code\re-frame2"
}

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

$gitRootRaw = (& git rev-parse --show-toplevel 2>$null)
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($gitRootRaw)) {
  throw "Not inside a git checkout. cd to the worker worktree before editing."
}

$gitRoot = Normalize-Path (Resolve-ExistingPath $gitRootRaw "Git root")
$worktreeParentRoot = Normalize-Path (Resolve-ExistingPath $WorktreeParent "Worker worktree parent")
$mayorCheckoutRoot = Normalize-Path (Resolve-ExistingPath $MayorRoot "Mayor checkout")

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
