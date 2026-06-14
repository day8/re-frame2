# scripts/reap-stale-test-processes.ps1 - guarded stale test/dev process reaper (rf2-c3hffe).
#
# WHAT. Inspects live java/node/clojure processes and identifies STALE
# repo/worktree TEST/DEV processes that are orphaned lock-holders - the
# class the rf2-c3hffe investigation found holding worktree file locks for
# hours/days and deadlocking the cross-spec core JVM suite on Windows
# (e.g. a 27h-old stranded serve-and-run-xray-feature-gate http-server; an
# orphaned `shadow-cljs watch` whose launching process is gone).
#
# DRY-RUN BY DEFAULT. With no flags it ONLY PRINTS what it WOULD kill and
# WHY - it kills NOTHING. Pass -Execute to actually tree-kill the
# identified stale processes. This is the safe-by-default posture the bead
# requires (disposition item 3b).
#
# SAFETY - WHAT IT WILL NEVER KILL.
#   * Codex (any command line containing 'codex' / '@openai/codex').
#   * MCP servers (re-frame2-pair-mcp / story-mcp / any '*-mcp' server.js,
#     or any command line containing 'mcp') - these are long-lived by
#     design and a running session depends on them.
#   * Any process whose PARENT is still ALIVE - a live parent (claude.exe,
#     pwsh, cmd, bash, node) means a session/worker still owns it. Only
#     processes whose parent is GONE, or is a replaced '*.old' process, are
#     candidates. A live worker's clojure/node is therefore protected.
#   * Anything whose command line does NOT point into a re-frame2 checkout
#     or worktree (it scopes strictly to this project's trees).
#   * Its own process tree (the reaper never targets itself).
#
# WHAT IT TARGETS (must match BOTH a stale-parent condition AND a
# test/dev-signature AND a re-frame2 path):
#   * shadow-cljs watch / compile servers (`shadow-cljs ... watch`,
#     `shadow.cljs.devtools.cli ... watch`).
#   * http-server testbed servers (`http-server ... out\...` /
#     `...\out\...` under a worktree).
#   * serve-and-run-* test orchestrators and their children.
# These are the dev/test SERVERS that leak; framework PRODUCTION processes
# and MCP servers are excluded by the signature + MCP guard above.
#
# USAGE.
#   pwsh -File scripts/reap-stale-test-processes.ps1            # DRY-RUN (default)
#   pwsh -File scripts/reap-stale-test-processes.ps1 -Execute   # actually kill
#   pwsh -File scripts/reap-stale-test-processes.ps1 -Verbose   # also show protected/skipped procs + reason
#
# Pairs with the known Windows-worktree-lock cleanup pattern: a worktree's
# node_modules may be junctioned into the mayor tree, so when you also want
# to `git worktree remove` a stale worktree, `cmd /c rmdir` the junction
# BEFORE removing the worktree (reference: junction-safe worktree cleanup).
#
# CROSS-PLATFORM NOTE. Windows-local helper (the lock-deadlock is
# Windows-only). On Mac/Linux orphaned children are reaped by the
# process-group teardown in the orchestrators themselves (the shared
# local-browser-harness POSIX process-group kill) and by the OS reparenting
# + normal `pkill -f shadow-cljs` hygiene; no equivalent reaper is needed.

param(
  [switch]$Execute,
  [switch]$VerboseSkips,
  [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# --- classification predicates -------------------------------------------

# A command line that points into a re-frame2 checkout or worktree. We match
# the project dir name plus a path separator so an unrelated process that
# merely mentions the string in an arg is not caught. Both the mayor
# checkout (`...\re-frame2\`) and worktrees (`...\re-frame2-worktrees\`) are
# in scope.
function Test-InReFrame2([string]$cmd) {
  if ([string]::IsNullOrWhiteSpace($cmd)) { return $false }
  $lc = $cmd.ToLowerInvariant()
  return ($lc -match 're-frame2-worktrees[\\/]') -or ($lc -match 're-frame2[\\/]')
}

# NEVER-KILL: Codex.
function Test-IsCodex([string]$cmd) {
  if ([string]::IsNullOrWhiteSpace($cmd)) { return $false }
  return $cmd.ToLowerInvariant() -match 'codex'
}

# NEVER-KILL: any MCP server (long-lived by design; a live session uses it).
# Matches '-mcp' tool dirs, an 'mcp' token, or a '*server.js' that is an MCP
# server bundle.
function Test-IsMcp([string]$cmd) {
  if ([string]::IsNullOrWhiteSpace($cmd)) { return $false }
  $lc = $cmd.ToLowerInvariant()
  return ($lc -match '-mcp[\\/]') -or ($lc -match '[\\/]mcp') -or ($lc -match '\bmcp\b') -or ($lc -match 'pair-mcp')
}

# TARGET SIGNATURE: a dev/test SERVER that leaks (shadow watch / compile,
# http-server testbed, serve-and-run-* orchestrator).
function Test-IsTestDevServer([string]$cmd) {
  if ([string]::IsNullOrWhiteSpace($cmd)) { return $false }
  $lc = $cmd.ToLowerInvariant()
  if ($lc -match 'shadow-cljs.*watch') { return $true }
  if ($lc -match 'shadow\.cljs\.devtools\.cli.*watch') { return $true }
  if ($lc -match 'shadow-cljs.*compile') { return $true }
  if ($lc -match 'http-server') { return $true }
  if ($lc -match 'serve-and-run-') { return $true }
  return $false
}

# The single classification decision, factored out of the live scan so it is
# deterministically unit-testable (-SelfTest). Returns a hashtable:
#   @{ Verdict = 'reap' | 'protect' | 'ignore'; Reason = <string> }
#   * 'reap'    — a stale re-frame2 test/dev server (a WOULD-KILL candidate).
#   * 'protect' — a re-frame2 process we deliberately spare (MCP, Codex,
#                 live-parent), surfaced in the report.
#   * 'ignore'  — not a re-frame2 process / not a test-dev signature; only
#                 shown under -VerboseSkips.
# `$parentStaleReason` is $null when the parent is alive (and not '*.old'),
# else the human reason the parent is stale. The caller computes it from the
# live process table (or, in the self-test, supplies a synthetic value).
function Get-ReapVerdict([string]$cmd, $parentStaleReason, [bool]$isSelf) {
  if ($isSelf) {
    return @{ Verdict = 'protect'; Reason = "reaper's own process tree" }
  }
  if (-not (Test-InReFrame2 $cmd)) {
    return @{ Verdict = 'ignore'; Reason = "not a re-frame2 process" }
  }
  if (Test-IsCodex $cmd) {
    return @{ Verdict = 'protect'; Reason = "PROTECTED: Codex" }
  }
  if (Test-IsMcp $cmd) {
    return @{ Verdict = 'protect'; Reason = "PROTECTED: MCP server (long-lived)" }
  }
  if (-not (Test-IsTestDevServer $cmd)) {
    return @{ Verdict = 'ignore'; Reason = "re-frame2 process but not a test/dev-server signature" }
  }
  if ($null -eq $parentStaleReason) {
    return @{ Verdict = 'protect'; Reason = "PROTECTED: parent still alive (in use by a session/worker)" }
  }
  return @{ Verdict = 'reap'; Reason = $parentStaleReason }
}

# --- self-test ------------------------------------------------------------
# Deterministic classification proof (no live processes). Mirrors the real
# snapshot classes observed in the rf2-c3hffe investigation: live MCP
# servers, Codex, a live worker's shadow JVM, an orphaned worktree
# http-server, an orphaned shadow watch, and a '*.old'-parented stale JVM.
if ($SelfTest) {
  # Neutral placeholder user component (per scripts/check-no-hardcoded-paths.sh).
  # Only the `re-frame2` / `re-frame2-worktrees` path tokens + signatures
  # drive classification; the user dir is irrelevant to the test.
  $mayor = 'C:\Users\u\code\re-frame2'
  $wt = 'C:\Users\u\code\re-frame2-worktrees\xray-e8330v'
  $cases = @(
    @{ Name='live MCP server (claude parent)';        Cmd="node $mayor\tools\re-frame2-pair-mcp\out\server.js --port-file x"; Stale=$null;               Self=$false; Expect='protect' },
    @{ Name='MCP server even with dead parent';        Cmd="node $mayor\tools\re-frame2-pair-mcp\out\server.js";              Stale='parent PID 1 is gone'; Self=$false; Expect='protect' },
    @{ Name='Codex node';                              Cmd='node C:\Users\x\npm\node_modules\@openai\codex\bin\codex.js';      Stale='parent PID 1 is gone'; Self=$false; Expect='ignore'  },
    @{ Name='Codex even inside a worktree path';       Cmd="node $wt\codex\bin\codex.js";                                     Stale='parent PID 1 is gone'; Self=$false; Expect='protect' },
    @{ Name='live worker shadow JVM (alive parent)';   Cmd="java -cp $wt\implementation\core\src shadow.cljs.devtools.cli watch app"; Stale=$null;          Self=$false; Expect='protect' },
    @{ Name='ORPHANED worktree http-server (gone parent)'; Cmd="node $wt\implementation\node_modules\http-server\bin\http-server $wt\implementation\out\xray-feature-gate -a 127.0.0.1 -p 8037"; Stale='parent PID 99999 is gone'; Self=$false; Expect='reap' },
    @{ Name='ORPHANED shadow watch (gone parent)';     Cmd="java -cp $wt\src shadow.cljs.devtools.cli watch app";             Stale='parent PID 99999 is gone'; Self=$false; Expect='reap' },
    @{ Name='STALE shadow JVM (.old parent)';          Cmd="java -cp $wt\src shadow.cljs.devtools.cli watch app";             Stale="parent is a replaced '*.old' process (claude.exe.old.123, PID 7)"; Self=$false; Expect='reap' },
    @{ Name='serve-and-run orchestrator orphan';       Cmd="node $wt\implementation\scripts\serve-and-run-xray-feature-gate.cjs"; Stale='parent PID 99999 is gone'; Self=$false; Expect='reap' },
    @{ Name='non-re-frame2 random node';               Cmd='node C:\Users\x\some\other\thing.js';                            Stale='parent PID 1 is gone'; Self=$false; Expect='ignore'  },
    @{ Name='re-frame2 NON-server (jvm test, gone parent) is not a server sig'; Cmd="java -cp $wt\src clojure.main -e (+ 1 1)"; Stale='parent PID 1 is gone'; Self=$false; Expect='ignore' },
    @{ Name='reaper own tree is always protected';     Cmd="node $wt\implementation\scripts\serve-and-run-xray-feature-gate.cjs"; Stale='parent PID 1 is gone'; Self=$true;  Expect='protect' }
  )
  $failed = 0
  foreach ($c in $cases) {
    $v = Get-ReapVerdict $c.Cmd $c.Stale $c.Self
    if ($v.Verdict -ne $c.Expect) {
      $failed += 1
      Write-Host ("FAIL  {0}: expected '{1}', got '{2}' ({3})" -f $c.Name, $c.Expect, $v.Verdict, $v.Reason)
    } else {
      Write-Host ("PASS  {0} -> {1}" -f $c.Name, $v.Verdict)
    }
  }
  Write-Host ""
  if ($failed -gt 0) {
    Write-Host "reaper self-test: $failed case(s) FAILED."
    exit 1
  }
  Write-Host "reaper self-test: all $($cases.Count) cases passed."
  exit 0
}

# --- snapshot -------------------------------------------------------------

$all = Get-CimInstance Win32_Process
$byId = @{}
foreach ($p in $all) { $byId[[int]$p.ProcessId] = $p }

# Our own PID + ancestry: never target ourselves or our launching shell.
$selfPid = $PID
$selfAncestry = New-Object System.Collections.Generic.HashSet[int]
[void]$selfAncestry.Add($selfPid)
$cur = $selfPid
$guard = 0
while ($true) {
  $guard += 1
  if ($guard -gt 64) { break }
  $proc = $byId[[int]$cur]
  if ($null -eq $proc) { break }
  $ppid = [int]$proc.ParentProcessId
  if ($ppid -eq 0 -or $selfAncestry.Contains($ppid)) { break }
  [void]$selfAncestry.Add($ppid)
  $cur = $ppid
}

# Is a parent "stale"? Stale == the parent process no longer exists, OR the
# parent's executable name is a replaced '*.old*' image (a Claude/worker
# process that was swapped out, observed as `claude.exe.old.<n>`). A LIVE,
# non-.old parent means the process is still owned by a session/worker and
# is NOT a reap candidate.
function Get-ParentStaleReason([Microsoft.Management.Infrastructure.CimInstance]$proc) {
  $ppid = [int]$proc.ParentProcessId
  $par = $byId[[int]$ppid]
  if ($null -eq $par) { return "parent PID $ppid is gone" }
  $pname = "$($par.Name)"
  if ($pname.ToLowerInvariant() -match '\.old') {
    return "parent is a replaced '*.old' process ($pname, PID $ppid)"
  }
  # A parent that is itself one of OUR candidate test/dev servers is treated
  # as transitively stale only if IT is stale; otherwise a live parent
  # protects the child. We do not chase the whole chain here - the direct
  # parent's liveness is the gate (matches the bead's "parent is gone or is
  # a *.old replaced process").
  return $null
}

# --- classify -------------------------------------------------------------

$candidates = New-Object System.Collections.Generic.List[object]
$protected = New-Object System.Collections.Generic.List[object]

$relevant = $all | Where-Object { $_.Name -match '^(java|javaw|node|clojure|deps)(\.exe)?$' }
foreach ($p in $relevant) {
  $thisPid = [int]$p.ProcessId
  $cmd = "$($p.CommandLine)"
  $isSelf = $selfAncestry.Contains($thisPid)

  # The stale-parent reason is only computed when it could matter (it is the
  # final gate), but Get-ReapVerdict short-circuits earlier classes, so we
  # pass the live reason unconditionally — it is ignored for non-server /
  # MCP / Codex / self cases.
  $staleReason = Get-ParentStaleReason $p
  $v = Get-ReapVerdict $cmd $staleReason $isSelf

  switch ($v.Verdict) {
    'reap' {
      $candidates.Add([PSCustomObject]@{ PID=$thisPid; PPID=[int]$p.ParentProcessId; Name=$p.Name; Reason=$v.Reason; Cmd=$cmd })
    }
    'protect' {
      $protected.Add([PSCustomObject]@{ PID=$thisPid; Name=$p.Name; Reason=$v.Reason; Cmd=$cmd })
    }
    'ignore' {
      if ($VerboseSkips) {
        $protected.Add([PSCustomObject]@{ PID=$thisPid; Name=$p.Name; Reason=$v.Reason; Cmd=$cmd })
      }
    }
  }
}

# --- report ---------------------------------------------------------------

$mode = "DRY-RUN (no processes will be killed; pass -Execute to kill)"
if ($Execute) { $mode = "EXECUTE (stale processes WILL be tree-killed)" }

Write-Host "=============== rf2-c3hffe stale test/dev process reaper ==============="
Write-Host "Mode: $mode"
Write-Host ""

if ($VerboseSkips -and $protected.Count -gt 0) {
  Write-Host "Protected / skipped (NOT reaped):"
  foreach ($p in $protected) {
    Write-Host ("  [skip] PID={0} {1} - {2}" -f $p.PID, $p.Name, $p.Reason)
    Write-Host ("         cmd: {0}" -f $p.Cmd)
  }
  Write-Host ""
}

if ($candidates.Count -eq 0) {
  Write-Host "No stale re-frame2 test/dev lock-holders found. Nothing to reap."
  Write-Host "========================================================================"
  exit 0
}

Write-Host "Stale re-frame2 test/dev lock-holders identified ($($candidates.Count)):"
foreach ($c in $candidates) {
  Write-Host ("  [{0}] PID={1} (PPID={2}) {3}" -f ($(if ($Execute) { "KILL" } else { "WOULD-KILL" })), $c.PID, $c.PPID, $c.Name)
  Write-Host ("         why-stale: {0}" -f $c.Reason)
  Write-Host ("         cmd: {0}" -f $c.Cmd)
}
Write-Host ""

if (-not $Execute) {
  Write-Host "DRY-RUN: nothing was killed. Re-run with -Execute to tree-kill the above."
  Write-Host "========================================================================"
  exit 0
}

$killedOk = 0
$killedFail = 0
foreach ($c in $candidates) {
  Write-Host ("Tree-killing PID {0} ({1}) ..." -f $c.PID, $c.Name)
  try {
    & taskkill.exe /pid $c.PID /T /F 2>&1 | ForEach-Object { Write-Host "  $_" }
    $killedOk += 1
  } catch {
    Write-Host "  FAILED: $($_.Exception.Message)"
    $killedFail += 1
  }
}
Write-Host ""
Write-Host ("Reaped {0} stale process tree(s); {1} failed." -f $killedOk, $killedFail)
Write-Host "========================================================================"
if ($killedFail -gt 0) { exit 1 }
exit 0
