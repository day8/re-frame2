# scripts/test-jvm-nses-windows.ps1 - Windows-safe per-namespace JVM sharding fallback (rf2-c3hffe).
#
# WHAT. The cross-spec core JVM suite (`cd implementation/core && clojure
# -M:test`) runs every test namespace in ONE JVM. On a Windows host where a
# stranded lock-holder is contending for a worktree file lock (rf2-c3hffe),
# that single run can deadlock with NO indication of WHICH namespace's setup
# touched the locked file. This script SHARDS the suite: it runs each test
# namespace in its OWN bounded `clojure -M:test -n <ns>` invocation, so a
# hang is attributed to a specific namespace ("namespace X held the lock")
# instead of hanging the whole suite forever.
#
# This is the bead's disposition item 4 (sharding fallback) realised as a
# script; it composes with scripts/test-core-jvm-windows.ps1 (item 2, the
# full-suite bounded runner) - use THIS when the full runner times out and
# you need to localise the offender.
#
# NAMESPACE FILTERING. The core `:test` alias delegates to
# cognitect.test-runner, which forwards `-n <namespace>` (run one ns) and
# `-r <regex>` (run matching nses). This script derives the namespace list
# from the test/ source tree (path -> ns munge) and runs `-n` per ns.
#
# WHAT IT KILLS. Per namespace, on timeout it tree-kills ONLY the
# clojure.exe + java.exe subtree it launched for THAT namespace (taskkill
# /T /F rooted at the captured child PID). Never unrelated JVMs/Node.
#
# EXIT. 0 if every namespace passed within its timeout; non-zero if any
# namespace failed or timed out. The summary names every TIMED-OUT and
# FAILED namespace.
#
# USAGE.
#   pwsh -File scripts/test-jvm-nses-windows.ps1
#   pwsh -File scripts/test-jvm-nses-windows.ps1 -PerNsTimeoutSeconds 120
#   pwsh -File scripts/test-jvm-nses-windows.ps1 -Pattern 'cofx'        # substring filter on ns names
#   pwsh -File scripts/test-jvm-nses-windows.ps1 -ListOnly              # print the discovered ns list and exit
#
# CROSS-PLATFORM NOTE. Windows-local helper. On Mac/Linux the suite does not
# deadlock; to shard there, `cd implementation/core && clojure -M:test -n
# <ns>` (or `-r <regex>`) directly under GNU `timeout` (TESTING.md
# section: Windows-local test policy).

param(
  [int]$PerNsTimeoutSeconds = 180,
  [string]$CoreDir = "",
  [string]$Pattern = "",
  [switch]$ListOnly,
  [switch]$Quiet
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Info([string]$msg) {
  if (-not $Quiet) { Write-Host $msg }
}

# Resolve implementation/core relative to this script's repo root unless
# pinned. <repo>/scripts/ -> <repo>/implementation/core. Derived, not baked.
if ([string]::IsNullOrWhiteSpace($CoreDir)) {
  $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
  $repoRoot = Split-Path -Parent $scriptDir
  $CoreDir = Join-Path $repoRoot "implementation\core"
}
if (-not (Test-Path -LiteralPath $CoreDir)) {
  Write-Error "Core artefact dir not found: $CoreDir"
  exit 2
}
$CoreDir = (Resolve-Path -LiteralPath $CoreDir).ProviderPath
$testRoot = Join-Path $CoreDir "test"
if (-not (Test-Path -LiteralPath $testRoot)) {
  Write-Error "Core test dir not found: $testRoot"
  exit 2
}

$clojure = (Get-Command clojure -ErrorAction SilentlyContinue)
if ($null -eq $clojure) {
  Write-Error "clojure launcher not found on PATH. Install the Clojure CLI for Windows."
  exit 2
}
$clojureExe = $clojure.Source

# Munge a test-source file path into a Clojure namespace symbol. The
# cognitect convention: path under test/ with separators -> dots and the
# Clojure underscore<->hyphen convention (file `foo_bar.clj` -> ns
# `foo-bar`). Only `_test.clj` / `_test.cljc` files carry deftests; pure
# .cljc helper nses without tests are harmless to pass to `-n` (the runner
# simply finds no deftests there) but we restrict to `*_test.*` to keep the
# shard list to the actual test namespaces.
function Get-TestNamespaces([string]$root) {
  $files = Get-ChildItem -LiteralPath $root -Recurse -File |
    Where-Object { $_.Name -match '_test\.(clj|cljc)$' }
  $nses = New-Object System.Collections.Generic.List[string]
  foreach ($f in $files) {
    $rel = $f.FullName.Substring($root.Length).TrimStart('\', '/')
    $noExt = $rel -replace '\.(clj|cljc)$', ''
    $dotted = $noExt -replace '[\\/]', '.'
    $ns = $dotted -replace '_', '-'
    $nses.Add($ns)
  }
  return ($nses | Sort-Object -Unique)
}

$allNses = Get-TestNamespaces -root $testRoot
if (-not [string]::IsNullOrWhiteSpace($Pattern)) {
  $allNses = $allNses | Where-Object { $_ -like "*$Pattern*" }
}

if ($null -eq $allNses -or @($allNses).Count -eq 0) {
  Write-Error "No test namespaces discovered under $testRoot (pattern: '$Pattern')."
  exit 2
}
$allNses = @($allNses)

if ($ListOnly) {
  Write-Host "Discovered $($allNses.Count) test namespace(s):"
  foreach ($ns in $allNses) { Write-Host "  $ns" }
  exit 0
}

# Tree-kill ONLY the subtree rooted at our per-ns child PID.
function Stop-OurSubtree([int]$ourChildPid) {
  try {
    & taskkill.exe /pid $ourChildPid /T /F 2>&1 | Out-Null
  } catch {
    Write-Info "  taskkill failed for PID ${ourChildPid}: $($_.Exception.Message)"
  }
}

# Run one namespace under a bounded child. Returns a hashtable
# @{ Ns; Status }; Status in 'pass' | 'fail' | 'timeout'.
function Invoke-NamespaceTest([string]$ns) {
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $clojureExe
  $psi.Arguments = "-M:test -n $ns"
  $psi.WorkingDirectory = $CoreDir
  $psi.UseShellExecute = $false
  $proc = New-Object System.Diagnostics.Process
  $proc.StartInfo = $psi
  [void]$proc.Start()
  $childPid = $proc.Id

  $exited = $proc.WaitForExit($PerNsTimeoutSeconds * 1000)
  if (-not $exited) {
    Write-Host "  TIMEOUT $ns (>${PerNsTimeoutSeconds}s) - tree-killing PID $childPid"
    Stop-OurSubtree -ourChildPid $childPid
    return @{ Ns = $ns; Status = 'timeout' }
  }
  $proc.WaitForExit()
  $code = $proc.ExitCode
  if ($code -eq 0) {
    Write-Info "  PASS $ns"
    return @{ Ns = $ns; Status = 'pass' }
  }
  Write-Host "  FAIL $ns (exit $code)"
  return @{ Ns = $ns; Status = 'fail' }
}

Write-Info "rf2-c3hffe per-namespace JVM sharding fallback"
Write-Info "  dir:         $CoreDir"
Write-Info "  namespaces:  $($allNses.Count)"
Write-Info "  per-ns cap:  ${PerNsTimeoutSeconds}s"
Write-Info ""

$timedOut = New-Object System.Collections.Generic.List[string]
$failed = New-Object System.Collections.Generic.List[string]
$passed = 0
foreach ($ns in $allNses) {
  $r = Invoke-NamespaceTest -ns $ns
  switch ($r.Status) {
    'pass'    { $passed += 1 }
    'fail'    { $failed.Add($ns) }
    'timeout' { $timedOut.Add($ns) }
  }
}

Write-Host ""
Write-Host "================ per-namespace JVM shard summary ================"
Write-Host "  passed:  $passed / $($allNses.Count)"
Write-Host "  failed:  $($failed.Count)"
Write-Host "  timeout: $($timedOut.Count)"
if ($timedOut.Count -gt 0) {
  Write-Host ""
  Write-Host "  TIMED-OUT namespaces (these held a lock / hung - the rf2-c3hffe symptom):"
  foreach ($ns in $timedOut) { Write-Host "    $ns" }
  Write-Host "  -> reap stale holders (scripts/reap-stale-test-processes.ps1) and retry,"
  Write-Host "     or inspect what file these namespaces' setup touches."
}
if ($failed.Count -gt 0) {
  Write-Host ""
  Write-Host "  FAILED namespaces (real test failures, distinct from the deadlock):"
  foreach ($ns in $failed) { Write-Host "    $ns" }
}
Write-Host "================================================================="

if ($timedOut.Count -gt 0 -or $failed.Count -gt 0) { exit 1 }
Write-Host "PASS all $($allNses.Count) namespaces green within their per-ns timeout."
exit 0
