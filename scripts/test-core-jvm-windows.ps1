# scripts/test-core-jvm-windows.ps1 - Windows-safe bounded core JVM runner (rf2-c3hffe).
#
# WHAT. Runs the full cross-spec core JVM suite
#   cd implementation/core && clojure -M:test
# under a hard timeout, on a Windows host where that suite has been observed
# to deadlock past 10 minutes on a file-lock held by a stranded/orphaned
# shadow-cljs/Node/JVM process from a prior test/worker run (rf2-c3hffe,
# rf2-wad2fl).
#
# WHY A WRAPPER (not a matrix change). CI's Linux runners run this exact
# suite green on every PR — it is the authoritative gate and is NOT split,
# trimmed, or weakened (rf2-c3hffe disposition item 1). This wrapper is a
# WINDOWS-LOCAL convenience + diagnostic: it turns "hangs forever" into
# "timed out at N minutes; here are the java/node/clojure processes and (if
# Sysinternals handle.exe is on PATH) the handles into this worktree that
# were holding it — kill them or use the targeted-ns runner." The timeout
# DUMP is the instrument that finally PROVES the lock-holder, closing the
# bead's explicit "verify cause" gap.
#
# WHAT IT KILLS. On timeout it tree-kills ONLY the process subtree THIS
# script launched (the clojure.exe it spawned + that clojure's java.exe
# descendant), via `taskkill /T /F` rooted at the captured child PID. It
# NEVER kills unrelated JVMs/Node (live MCP servers, other workers, Codex) —
# reaping those stale holders is the separate, guarded job of
# scripts/reap-stale-test-processes.ps1.
#
# EXIT CODES.
#   0   suite passed within the timeout.
#   <n> the suite's own non-zero exit (real test failure) — passed through.
#   124 timed out (the deadlock symptom). Matches GNU `timeout`'s convention.
#
# USAGE.
#   pwsh -File scripts/test-core-jvm-windows.ps1
#   pwsh -File scripts/test-core-jvm-windows.ps1 -TimeoutSeconds 300
#   pwsh -File scripts/test-core-jvm-windows.ps1 -CoreDir <abs-path-to-implementation/core>
#
# CROSS-PLATFORM NOTE. This is the Windows-local helper. On Mac/Linux the
# suite does not deadlock and `cd implementation/core && clojure -M:test`
# (or GNU `timeout 600 clojure -M:test`) is the direct command — no wrapper
# is needed (TESTING.md §Windows-local test policy).

param(
  [int]$TimeoutSeconds = 600,
  [string]$CoreDir = "",
  [switch]$Quiet
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Info([string]$msg) {
  if (-not $Quiet) { Write-Host $msg }
}

# Resolve implementation/core relative to this script's repo root unless the
# caller pinned it. The script lives at <repo>/scripts/, so core is at
# <repo>/implementation/core - derived, never hardcoded.
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

# Resolve the clojure launcher (clojure.exe on Windows). Fail loud if absent.
$clojure = (Get-Command clojure -ErrorAction SilentlyContinue)
if ($null -eq $clojure) {
  Write-Error "clojure launcher not found on PATH. Install the Clojure CLI for Windows."
  exit 2
}
$clojureExe = $clojure.Source

# Collect the full descendant subtree of a root PID (root included), using
# Win32_Process ParentProcessId edges. Pure read; used both to scope the
# kill to OUR child tree and to dump the suspects on timeout.
function Get-DescendantPids([int]$rootPid) {
  $all = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue
  $byParent = @{}
  foreach ($p in $all) {
    $ppid = [int]$p.ParentProcessId
    if (-not $byParent.ContainsKey($ppid)) { $byParent[$ppid] = @() }
    $byParent[$ppid] += [int]$p.ProcessId
  }
  $result = New-Object System.Collections.Generic.List[int]
  $stack = New-Object System.Collections.Generic.Stack[int]
  $stack.Push($rootPid)
  $seen = @{}
  while ($stack.Count -gt 0) {
    $cur = $stack.Pop()
    if ($seen.ContainsKey($cur)) { continue }
    $seen[$cur] = $true
    $result.Add($cur)
    if ($byParent.ContainsKey($cur)) {
      foreach ($child in $byParent[$cur]) { $stack.Push($child) }
    }
  }
  return $result
}

# The timeout DUMP - the instrument that proves the holder. Lists every live
# java/node/clojure process with its PID, parent PID, and full command line,
# flags those rooted in THIS worktree, and (if Sysinternals handle.exe is on
# PATH) dumps open handles into the core dir. Best-effort: never throws.
function Write-LockHolderDump([string]$worktreeRoot, [int]$ourChildPid) {
  Write-Host ""
  Write-Host "================ rf2-c3hffe lock-holder dump ================"
  Write-Host "Timed out waiting for: cd $CoreDir && clojure -M:test"
  Write-Host "Worktree root: $worktreeRoot"
  Write-Host ""
  Write-Host "Live java / node / clojure processes (the deadlock suspects):"
  try {
    $procs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
      Where-Object { $_.Name -match '^(java|javaw|node|clojure|deps|powershell|pwsh)(\.exe)?$' }
    foreach ($p in $procs) {
      $cmd = if ($p.CommandLine) { $p.CommandLine } else { "(command line unavailable)" }
      $inWorktree = $false
      if ($p.CommandLine -and $worktreeRoot -and $p.CommandLine.ToLowerInvariant().Contains($worktreeRoot.ToLowerInvariant())) {
        $inWorktree = $true
      }
      $tag = if ($inWorktree) { " [IN-WORKTREE]" } else { "" }
      Write-Host ("  PID={0} PPID={1} {2}{3}" -f $p.ProcessId, $p.ParentProcessId, $p.Name, $tag)
      Write-Host ("      cmd: {0}" -f $cmd)
    }
  } catch {
    Write-Host "  (process enumeration failed: $($_.Exception.Message))"
  }
  Write-Host ""
  Write-Host "Process subtree this runner launched (PID $ourChildPid + descendants):"
  try {
    $ours = Get-DescendantPids -rootPid $ourChildPid
    Write-Host ("  $($ours -join ', ')")
  } catch {
    Write-Host "  (subtree enumeration failed: $($_.Exception.Message))"
  }
  Write-Host ""

  # Opportunistic exact-handle dump (Sysinternals). Only runs if handle.exe
  # is on PATH; this is the one tool that proves the EXACT file-lock holder
  # without inference. Absent it, the command-line + IN-WORKTREE tags above
  # are the documented best-effort instrument (the bead notes neither
  # investigation had handle.exe; this wires it in when available).
  $handle = Get-Command handle.exe -ErrorAction SilentlyContinue
  if ($null -ne $handle) {
    Write-Host "Sysinternals handle.exe found - dumping handles into the worktree:"
    try {
      & $handle.Source -nobanner -accepteula $worktreeRoot 2>&1 | ForEach-Object { Write-Host "  $_" }
    } catch {
      Write-Host "  (handle.exe dump failed: $($_.Exception.Message))"
    }
  } else {
    Write-Host "Sysinternals handle.exe NOT on PATH. For an EXACT lock-holder proof,"
    Write-Host "install it (https://learn.microsoft.com/sysinternals/downloads/handle)"
    Write-Host "and re-run; then: handle.exe `"$worktreeRoot`""
  }
  Write-Host "============================================================="
  Write-Host ""
}

# Tree-kill ONLY the subtree we launched, rooted at our child PID. taskkill
# /T walks the descendants; /F forces. Scoped to OUR pid so unrelated JVMs/
# Node (other workers, MCP servers, Codex) are never touched.
function Stop-OurSubtree([int]$ourChildPid) {
  Write-Info "Tree-killing the runner's own child subtree (PID $ourChildPid) ..."
  try {
    & taskkill.exe /pid $ourChildPid /T /F 2>&1 | ForEach-Object { Write-Info "  $_" }
  } catch {
    Write-Info "  taskkill failed: $($_.Exception.Message)"
  }
}

# Best-effort worktree root for the dump's IN-WORKTREE tagging.
$worktreeRoot = ""
try {
  $worktreeRoot = (& git -C $CoreDir rev-parse --show-toplevel 2>$null)
  if ($worktreeRoot) { $worktreeRoot = (Resolve-Path -LiteralPath $worktreeRoot).ProviderPath }
} catch { $worktreeRoot = "" }
if ([string]::IsNullOrWhiteSpace($worktreeRoot)) { $worktreeRoot = $CoreDir }

Write-Info "rf2-c3hffe bounded core JVM runner"
Write-Info "  dir:     $CoreDir"
Write-Info "  command: clojure -M:test"
Write-Info "  timeout: ${TimeoutSeconds}s"
Write-Info ""

# Launch clojure -M:test as a child process whose PID we own, so the timeout
# path can tree-kill exactly that subtree.
#
# We use System.Diagnostics.Process directly (NOT Start-Process -PassThru):
# Start-Process's returned object does not reliably populate .ExitCode on
# Windows PowerShell, so a real test failure would be misreported. With
# UseShellExecute=$false the child inherits this console's stdio (the suite's
# output streams live, matching a direct invocation) AND .ExitCode is
# reliable after WaitForExit().
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $clojureExe
$psi.Arguments = "-M:test"
$psi.WorkingDirectory = $CoreDir
$psi.UseShellExecute = $false
$proc = New-Object System.Diagnostics.Process
$proc.StartInfo = $psi
[void]$proc.Start()
$ourChildPid = $proc.Id

$exited = $proc.WaitForExit($TimeoutSeconds * 1000)

if (-not $exited) {
  Write-LockHolderDump -worktreeRoot $worktreeRoot -ourChildPid $ourChildPid
  Stop-OurSubtree -ourChildPid $ourChildPid

  Write-Host ""
  Write-Host "FAIL (timeout) core JVM suite did not finish within ${TimeoutSeconds}s."
  Write-Host "This is the rf2-c3hffe Windows file-lock deadlock symptom, NOT a"
  Write-Host "framework-correctness failure - CI's Linux runners run this suite green."
  Write-Host ""
  Write-Host "HANDOFF / next steps:"
  Write-Host "  1. Inspect the lock-holder dump above. Any [IN-WORKTREE] java/node/"
  Write-Host "     clojure whose PPID is a dead/replaced process is a stale holder."
  Write-Host "  2. Reap stale holders (DRY-RUN first):"
  Write-Host "       pwsh -File scripts/reap-stale-test-processes.ps1"
  Write-Host "       pwsh -File scripts/reap-stale-test-processes.ps1 -Execute"
  Write-Host "  3. Or bypass the full gate: run JVM nses one-at-a-time under a"
  Write-Host "     timeout to find which namespace/process holds the lock:"
  Write-Host "       pwsh -File scripts/test-jvm-nses-windows.ps1"
  Write-Host "  4. The authoritative full-suite gate is CI (Linux) - push and let"
  Write-Host "     CI run it (TESTING.md section: Windows-local test policy)."
  exit 124
}

# A no-arg WaitForExit() after the bounded wait succeeds ensures the process
# is fully reaped and .ExitCode is populated before we read it.
$proc.WaitForExit()
$code = $proc.ExitCode
if ($code -eq 0) {
  Write-Info ""
  Write-Info "PASS core JVM suite (clojure -M:test) within ${TimeoutSeconds}s."
} else {
  Write-Host ""
  Write-Host "FAIL core JVM suite exited non-zero (exit $code) - a REAL test failure"
  Write-Host "(distinct from the rf2-c3hffe deadlock, which exits 124)."
  Write-Host "repro: cd implementation/core && clojure -M:test"
}
exit $code
