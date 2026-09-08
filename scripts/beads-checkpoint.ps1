#requires -Version 5
# scripts/beads-checkpoint.ps1 - Windows-friendly mirror of
# scripts/beads-checkpoint.sh.
#
# ASCII-only by design: Windows PowerShell 5.x reads -File scripts as the
# system ANSI codepage, so a BOM-less UTF-8 multibyte char (e.g. an em-dash)
# in a code position breaks tokenization. Keep this file pure ASCII.
#
# THE FAULT THIS EXISTS TO STOP (rf2-51uz1)
#
#   CLAUDE.md mandates `git checkout HEAD -- .beads` before every pull, and it
#   is right to: an uncommitted .beads/issues.jsonl makes `git pull` abort,
#   silently freezing HEAD at a stale base. But the JSONL is a full-database
#   EXPORT. If a `bd close` or `bd create` happened after the last
#   export-commit, that checkout reverts the export to its pre-close state, and
#   a checkpoint that then commits (or re-imports) the working file writes the
#   revert back over the database. The close simply evaporates.
#
#   OBSERVED, not hypothetical: rf2-5e8zv was reopened exactly this way, and
#   commit e80786e007 on main records three more closes reverted by re-import
#   and re-closed by hand.
#
# THE FIX: EXPORT FIRST. A checkpoint asks the Dolt database what the tracker
# says (`bd export`) instead of trusting whatever sits in the working tree.
#
# THE SECOND FAULT (rf2-rjqtj): EXPORT FIRST IS NOT ENOUGH ON ITS OWN.
#
#   Exporting first is right when the database is strictly ahead of Git. It is
#   wrong when Git is ahead in places, and Git can be: a second writer exists.
#   The merged-PR audit commits issue rows straight to Git, and a `git pull`
#   brings other checkouts' rows in the same way. When both sides move they can
#   diverge at the SAME ROW COUNT, one row for one row. The row-count floor
#   below then sees 1938 == 1938 and waves the export through, and the commit
#   deletes the Git-only rows and reverts the newer Git statuses.
#
#   OBSERVED, not hypothetical: commit 667c744dc875 dropped rf2-3jw04,
#   rf2-jv36i and rf2-lhdp0 and reverted rf2-2rtt6.52/.63 exactly this way.
#   This script is the one that produced that commit.
#
#   So the export is now compared to HEAD by issue id, `updated_at` and
#   `status` before it is allowed to overwrite anything. See Get-GitOnlyFacts.
#   EQUAL COUNTS ARE NOT EQUALITY.
#
# THE THIRD BLIND SPOT (rf2-cve7): BOTH GUARDS ABOVE ONLY SEE ISSUES.
#
#   The floor counts rows, which the issue rows dominate, and the divergence
#   guard reads `"_type":"issue"` and skips everything else. So the memory rows
#   - the `bd remember` store - are unguarded by construction.
#
#   OBSERVED, not hypothetical: on 2026-09-08, 210 memory keys vanished from
#   the live store (1167 -> 957) and nothing said a word. `bd stats` reports
#   ISSUES ONLY and read a healthy 1099 straight through it; the export was
#   90.8% of HEAD's rows, over the floor. Two substantive memories went with the
#   retention cull that took the other 204, including a standing operator
#   preference, and both had to be recovered by hand from an older checkpoint.
#
#   Get-MemoryFacts now reconciles the two populations against HEAD by KEY SET.
#   Unlike the two guards above it WARNS AND DOES NOT REFUSE - see the call site
#   for why that constraint is deliberate.
#
# The commit carries the rows that changed and nothing else: `bd export` does
# not fix the order of the memory rows, so the file is written in minimal-diff
# order first (rf2-51uz1.1, Write-MinimalDiffOrder below).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/beads-checkpoint.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/beads-checkpoint.ps1 -Message "chore(beads): checkpoint (6931 merged)"
#   powershell -ExecutionPolicy Bypass -File scripts/beads-checkpoint.ps1 -PrePull
#   powershell -ExecutionPolicy Bypass -File scripts/beads-checkpoint.ps1 -SelfTest

[CmdletBinding()]
param(
    [switch]$PrePull,
    [switch]$SelfTest,
    [string]$Message = 'chore(beads): checkpoint'
)

$ErrorActionPreference = 'Stop'
# An unset variable or a missing property is a bug in a script whose whole job
# is to not lose data quietly. Every arm below is exercised by -SelfTest.
Set-StrictMode -Version Latest

$tracker = '.beads/issues.jsonl'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot  = (Resolve-Path (Join-Path $scriptDir '..')).Path
Set-Location $repoRoot

function Die {
    param([string]$Text)
    # Plain stderr rather than Write-Error: PowerShell's error record wraps the
    # text to the console width, which breaks the message into pieces mid-phrase
    # and makes it unquotable in a log or a test assertion.
    [Console]::Error.WriteLine("beads-checkpoint: $Text")
    exit 1
}

# ---------------------------------------------------------------------------
# Run a command and capture its stdout as RAW BYTES.
#
# Never `$x = & bd export`: Windows PowerShell 5.x decodes a native command's
# stdout with the console codepage, which mangles the UTF-8 in bead prose. The
# tracker is 10 MB of other people's words - it gets copied byte for byte or
# not at all.
# ---------------------------------------------------------------------------
function Invoke-RawToFile {
    param(
        [string]$Exe,
        [string[]]$Arguments,
        [string]$OutFile
    )
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Exe
    # `Arguments`, not `ArgumentList`: the latter arrived with .NET Core, and the
    # documented invocation here is `powershell -File`, i.e. Windows PowerShell
    # 5.x on .NET Framework, where `$psi.ArgumentList` is simply null.
    $quoted = foreach ($a in $Arguments) { if ($a -match '\s') { '"' + $a + '"' } else { $a } }
    $psi.Arguments = ($quoted -join ' ')
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError  = $true
    $psi.UseShellExecute = $false
    $psi.WorkingDirectory = (Get-Location).Path

    $proc = [System.Diagnostics.Process]::Start($psi)
    $fs = [System.IO.File]::Open($OutFile, [System.IO.FileMode]::Create,
                                 [System.IO.FileAccess]::Write)
    try {
        $proc.StandardOutput.BaseStream.CopyTo($fs)
    } finally {
        $fs.Dispose()
    }
    # stdout is drained first, then stderr. Both commands here (`bd export`,
    # `git show`) write at most a line or two of diagnostics, so stderr cannot
    # fill its pipe while stdout is still copying.
    $proc.StandardError.ReadToEnd() | Out-Null
    $proc.WaitForExit()
    return $proc.ExitCode
}

function Get-RowCount {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return 0 }
    $n = 0
    foreach ($line in [System.IO.File]::ReadLines($Path)) { $n++ }
    return $n
}

# Write HEAD's copy of the tracker to $Path; an empty file if the tracker does
# not exist at HEAD (a first-ever checkpoint).
function Write-HeadCopy {
    param([string]$Path)
    $code = Invoke-RawToFile -Exe 'git' -Arguments @('show', "HEAD:$tracker") -OutFile $Path
    if ($code -ne 0) {
        [System.IO.File]::WriteAllText($Path, '', (New-Object System.Text.UTF8Encoding $false))
    }
}

# Write $Export's rows to $OutFile, but emit every row HEAD already carries in
# HEAD's order first, and only then the rows that are genuinely new, in export
# order.
#
# WHY (rf2-51uz1.1). Test-SameContent below already stops a reorder-ONLY export
# from becoming a commit. It does nothing for the normal case: one real row
# changed, so the checkpoint commits, and the raw export carries every unrelated
# memory reorder along with it. Measured on the first real checkpoint after this
# helper landed: 211 additions / 208 deletions staged, of which 200 added rows
# were byte-identical to 200 removed rows. Pure relocation. Eleven added and
# eight removed lines were the actual tracker change, buried.
#
# The output is the export's row MULTISET exactly - no row is invented, dropped
# or edited, so `bd import` sees the same database either way. Only the line
# ORDER differs, and JSONL row order carries no meaning to the importer. What it
# buys is a diff that is exactly (rows HEAD had and the export does not) plus
# (rows the export has and HEAD does not): no relocation lines at all.
#
# CRLF is stripped so a Windows checkout's `git checkout HEAD -- .beads` copy
# still matches the LF rows `bd export` emits - the same reason
# Test-SameContent strips it. Output is LF, byte-for-byte the export's own rows,
# and UTF-8 WITHOUT a BOM: the tracker is 10 MB of other people's words and a
# BOM would corrupt the first row.
function Write-MinimalDiffOrder {
    param([string]$Export, [string]$HeadCopy, [string]$OutFile)

    $exportLines = foreach ($l in [System.IO.File]::ReadLines($Export)) { $l -replace "`r$", '' }
    $exportLines = [string[]]$exportLines

    # Remaining count per distinct row, consumed as rows are emitted. An
    # ordinal comparer so this matches sh's byte comparison rather than the
    # current culture's collation.
    $remaining = New-Object 'System.Collections.Generic.Dictionary[string,int]' ([System.StringComparer]::Ordinal)
    foreach ($l in $exportLines) {
        if ($remaining.ContainsKey($l)) { $remaining[$l] = $remaining[$l] + 1 }
        else { $remaining[$l] = 1 }
    }

    $out = New-Object 'System.Collections.Generic.List[string]' ($exportLines.Count)

    # Pass 1: HEAD's rows, in HEAD's order, for every row the export still has.
    if (Test-Path -LiteralPath $HeadCopy) {
        foreach ($raw in [System.IO.File]::ReadLines($HeadCopy)) {
            $l = $raw -replace "`r$", ''
            if ($remaining.ContainsKey($l) -and $remaining[$l] -gt 0) {
                $remaining[$l] = $remaining[$l] - 1
                $out.Add($l)
            }
        }
    }

    # Pass 2: whatever the export has left over, in export order.
    foreach ($l in $exportLines) {
        if ($remaining[$l] -gt 0) {
            $remaining[$l] = $remaining[$l] - 1
            $out.Add($l)
        }
    }

    $sw = New-Object System.IO.StreamWriter($OutFile, $false, (New-Object System.Text.UTF8Encoding $false))
    try {
        $sw.NewLine = "`n"
        foreach ($l in $out) { $sw.WriteLine($l) }
    } finally {
        $sw.Dispose()
    }
}

# Same SET of rows, regardless of order.
#
# Order matters here because `bd export` does not fix the order of the trailing
# memory rows: two exports of an unchanged database differ by reordering alone
# (measured: 2396 issue rows byte-identical, 222 memory rows reordered).
# Comparing sorted forms keeps a checkpoint from committing a few-hundred-line
# diff that says nothing, while still noticing a memory added or edited.
function Test-SameContent {
    param([string]$A, [string]$B)
    $la = [System.IO.File]::ReadAllLines($A)
    $lb = [System.IO.File]::ReadAllLines($B)
    if ($la.Count -ne $lb.Count) { return $false }
    $sa = [string[]]$la; [System.Array]::Sort($sa, [System.StringComparer]::Ordinal)
    $sb = [string[]]$lb; [System.Array]::Sort($sb, [System.StringComparer]::Ordinal)
    for ($i = 0; $i -lt $sa.Count; $i++) {
        if (-not [string]::Equals($sa[$i], $sb[$i], [System.StringComparison]::Ordinal)) {
            return $false
        }
    }
    return $true
}

# First value of a top-level JSON string key, or '' when the key is absent.
#
# FIRST match, deliberately: `"id":"` occurs up to eight times in one issue row
# because every comment carries its own id, and only the leading one is the
# bead's. `bd export` writes `_type` and `id` at the front of the row.
function Get-JsonValue {
    param([string]$Line, [string]$Key)
    $m = [regex]::Match($Line, ('"' + $Key + '":"([^"]*)"'))
    if ($m.Success) { return $m.Groups[1].Value }
    return ''
}

# Get-GitOnlyFacts - every tracker fact HEAD carries that the fresh export does
# not. Identical contract to git_only_facts in the .sh sibling, down to the
# wording of the lines it returns.
#
# THE FAULT THIS EXISTS TO STOP (rf2-rjqtj): see the second fault at the top of
# this file. Row counts are a floor, not an equality test.
#
# Three classes, all of them "Git knows something Dolt does not":
#
#   GONE    an issue id at HEAD that the export has no row for at all. The
#           commit would DELETE that bead.
#   REVERT  an id in both, where HEAD's `updated_at` is strictly NEWER than the
#           export's. The commit would revert it to an older status.
#   AMBIG   an id in both carrying the SAME `updated_at` but a DIFFERENT
#           `status`. Neither side can be called newer, so neither may be
#           chosen automatically.
#
# The opposite direction - ids only the export has, rows the export has newer -
# is the normal forward motion of a checkpoint and is deliberately not reported.
#
# WHY `status` AND `updated_at`, NOT JUST THE ID SET: an id-set comparison
# proves presence, nothing more. Confirmed in the field: an interrupted Dolt
# generational GC reverted a bead's close and five note appends while every id
# stayed intact. Presence is not state.
#
# THE ID IS THE STABLE BEAD ID (rf2-...), NEVER A ROW UUID. `bd` regenerates row
# and comment UUIDs on re-import, so a UUID-keyed diff reports phantom losses -
# it flagged three beads that existed and were closed. A row whose id cannot be
# read is REPORTED rather than skipped: a guard that silently stops guarding is
# the bug being fixed here.
#
# Rows are compared only when both sides carry a non-empty `updated_at`. Without
# timestamps there is no basis on which to call either side newer, and inventing
# one would turn every ordinary close into a refusal.
#
# RemedyRows are the GONE and REVERT rows exactly as HEAD holds them, so
# `bd import` of that file is the whole recovery - the bead's own verified,
# bounded mechanism. AMBIG rows are deliberately left out; an import cannot
# adjudicate them.
function Get-GitOnlyFacts {
    param([string]$Export, [string]$HeadCopy)

    $cap = 20
    $report = New-Object 'System.Collections.Generic.List[string]'
    $remedy = New-Object 'System.Collections.Generic.List[string]'

    $xst = New-Object 'System.Collections.Generic.Dictionary[string,string]' ([System.StringComparer]::Ordinal)
    $xup = New-Object 'System.Collections.Generic.Dictionary[string,string]' ([System.StringComparer]::Ordinal)
    $xbad = 0
    $hbad = 0

    foreach ($raw in [System.IO.File]::ReadLines($Export)) {
        $line = $raw -replace "`r$", ''
        if ($line.IndexOf('"_type":"issue"', [System.StringComparison]::Ordinal) -lt 0) { continue }
        $id = Get-JsonValue -Line $line -Key 'id'
        if ($id -eq '') { $xbad++; continue }
        $xst[$id] = Get-JsonValue -Line $line -Key 'status'
        $xup[$id] = Get-JsonValue -Line $line -Key 'updated_at'
    }

    $ngone = 0; $nrev = 0; $namb = 0
    foreach ($raw in [System.IO.File]::ReadLines($HeadCopy)) {
        $line = $raw -replace "`r$", ''
        if ($line.IndexOf('"_type":"issue"', [System.StringComparison]::Ordinal) -lt 0) { continue }
        $id = Get-JsonValue -Line $line -Key 'id'
        if ($id -eq '') { $hbad++; continue }
        $st = Get-JsonValue -Line $line -Key 'status'
        $up = Get-JsonValue -Line $line -Key 'updated_at'

        if (-not $xup.ContainsKey($id)) {
            $ngone++
            if ($ngone -le $cap) {
                $report.Add("  GONE    $id  would be DELETED (HEAD: status=$st updated_at=$up)")
            }
            $remedy.Add($line)
            continue
        }
        # Ordinal, to match the .sh sibling's byte comparison rather than the
        # current culture's collation. ISO-8601 Z timestamps are fixed width, so
        # lexical order is chronological order.
        if ($up -ne '' -and $xup[$id] -ne '' -and
            [string]::CompareOrdinal($up, $xup[$id]) -gt 0) {
            $nrev++
            if ($nrev -le $cap) {
                $report.Add("  REVERT  $id  HEAD status=$st updated_at=$up -> export status=$($xst[$id]) updated_at=$($xup[$id])")
            }
            $remedy.Add($line)
            continue
        }
        if ($up -ne '' -and [string]::CompareOrdinal($up, $xup[$id]) -eq 0 -and $st -ne $xst[$id]) {
            $namb++
            if ($namb -le $cap) {
                $report.Add("  AMBIG   $id  same updated_at=$up but HEAD status=$st, export status=$($xst[$id])")
            }
        }
    }

    if ($ngone -gt $cap) { $report.Add("  ... and $($ngone - $cap) more that would be DELETED") }
    if ($nrev  -gt $cap) { $report.Add("  ... and $($nrev  - $cap) more that would be REVERTED") }
    if ($namb  -gt $cap) { $report.Add("  ... and $($namb  - $cap) more ambiguous rows") }
    if ($xbad -gt 0) {
        $report.Add("  UNREADABLE  $xbad issue rows in the fresh export carry no readable id")
    }
    if ($hbad -gt 0) {
        $report.Add("  UNREADABLE  $hbad issue rows at HEAD carry no readable id")
    }

    return [pscustomobject]@{
        Report     = $report.ToArray()
        RemedyRows = $remedy.ToArray()
    }
}

# Get-MemoryFacts - the memory-reconciliation body when the fresh export
# accounts for FEWER `bd remember` keys than HEAD, or when either file carries
# rows that are neither an issue nor a memory. Empty when both populations
# reconcile, which is every ordinary checkpoint. Identical contract to
# memory_facts in the .sh sibling, down to the wording of the lines it returns.
#
# THE FAULT THIS EXISTS TO STOP (rf2-cve7). On 2026-09-08, 210 memory keys
# vanished from the live store (1167 -> 957) and NO INSTRUMENT SAID A WORD.
# `bd stats` read a healthy `Total Issues: 1099` straight through it, because it
# reports ISSUES ONLY, and every guard above inherited the same blind spot:
#
#   * the row-count floor is dominated by issue rows, so a memory-only deletion
#     slides under it. Measured on the real event: 2073 rows against HEAD's
#     2283 is 90.8% - over the 90% floor, so it waves the export through.
#   * Get-GitOnlyFacts reads `"_type":"issue"` rows and skips every other line,
#     so memories are outside its remit by construction.
#
# WHY THIS IS NOT THE "SUM TO THE ROW COUNT" CHECK THE BEAD ASKED FOR, and the
# distinction is the whole point rather than a quibble. Counting the two
# populations and checking they sum to the file's row count is an INTERNAL
# WELL-FORMEDNESS identity: it holds trivially whenever every row is one of the
# two known types, so it is SILENT ON BOTH SIDES of the event it was proposed to
# catch - 1116 + 1167 == 2283 before, 1116 + 957 == 2073 after. A population
# that shrinks is only visible against a BASELINE, and HEAD's committed export
# is exactly that baseline, already in hand for the guards above. So the sum is
# kept (it catches a row of an unknown `_type`) and the LOSS DETECTOR is the
# key-set comparison.
#
# KEY SETS, NOT COUNTS. A cull that deletes 210 keys and adds 210 leaves the
# count flat while losing 210 memories, so the count is the same kind of floor
# the row count already was. The key set has no such hole.
function Get-MemoryFacts {
    param([string]$Export, [string]$HeadCopy)

    $cap = 10
    $report = New-Object 'System.Collections.Generic.List[string]'

    $xkey = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
    $xrows = 0; $xiss = 0; $xmem = 0; $xoth = 0; $xbad = 0

    foreach ($raw in [System.IO.File]::ReadLines($Export)) {
        $line = $raw -replace "`r$", ''
        $xrows++
        if ($line.IndexOf('"_type":"issue"', [System.StringComparison]::Ordinal) -ge 0) { $xiss++; continue }
        if ($line.IndexOf('"_type":"memory"', [System.StringComparison]::Ordinal) -ge 0) {
            $xmem++
            $k = Get-JsonValue -Line $line -Key 'key'
            if ($k -eq '') { $xbad++; continue }
            [void]$xkey.Add($k)
            continue
        }
        $xoth++
    }

    $hrows = 0; $hiss = 0; $hmem = 0; $hoth = 0; $hbad = 0
    $ngone = 0
    $lost = New-Object 'System.Collections.Generic.List[string]'
    if (Test-Path -LiteralPath $HeadCopy) {
        foreach ($raw in [System.IO.File]::ReadLines($HeadCopy)) {
            $line = $raw -replace "`r$", ''
            $hrows++
            if ($line.IndexOf('"_type":"issue"', [System.StringComparison]::Ordinal) -ge 0) { $hiss++; continue }
            if ($line.IndexOf('"_type":"memory"', [System.StringComparison]::Ordinal) -ge 0) {
                $hmem++
                $k = Get-JsonValue -Line $line -Key 'key'
                if ($k -eq '') { $hbad++; continue }
                if (-not $xkey.Contains($k)) {
                    $ngone++
                    if ($ngone -le $cap) { $lost.Add($k) }
                }
                continue
            }
            $hoth++
        }
    }

    if ($ngone -eq 0 -and $xoth -eq 0 -and $hoth -eq 0 -and $xbad -eq 0 -and $hbad -eq 0) {
        return @()
    }

    $report.Add("  export  $xrows rows = $xiss issues + $xmem memories")
    $report.Add("  HEAD    $hrows rows = $hiss issues + $hmem memories")
    if ($ngone -gt 0) {
        $report.Add('')
        $report.Add("  $ngone memory key(s) at HEAD are ABSENT from the fresh export:")
        foreach ($k in $lost) { $report.Add("      $k") }
        if ($ngone -gt $cap) { $report.Add("      ... and $($ngone - $cap) more") }
    }
    if ($xoth -gt 0) {
        $report.Add('')
        $report.Add("  $xoth row(s) in the fresh export are neither an issue nor a memory,")
        $report.Add('  so the two populations do NOT sum to the export row count.')
    }
    if ($hoth -gt 0) {
        $report.Add('')
        $report.Add("  $hoth row(s) at HEAD are neither an issue nor a memory.")
    }
    if ($xbad -gt 0) {
        $report.Add('')
        $report.Add("  UNREADABLE  $xbad memory rows in the fresh export carry no readable key")
    }
    if ($hbad -gt 0) {
        $report.Add('')
        $report.Add("  UNREADABLE  $hbad memory rows at HEAD carry no readable key")
    }
    return $report.ToArray()
}

# ---------------------------------------------------------------------------
# The tracker database is the MAYOR checkout's to commit (rf2-ia8o7). The
# primary worktree is the first entry of `git worktree list --porcelain`; the
# same derivation as scripts/git-hooks/lib/check-beads-boundary.sh and
# scripts/assert-worker-worktree.ps1, with the same RF2_MAYOR_ROOT override.
#
# Only the COMMITTING arm is gated. -PrePull is a read-only question -
# "would clearing .beads discard tracker state?" - that every worktree
# legitimately asks before its own pull, and it must keep answering from
# worker worktrees (rf2-fifk0).
# ---------------------------------------------------------------------------
function Get-NormalizedPath {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path) {
        $Path = (Resolve-Path -LiteralPath $Path).Path
    }
    return ($Path -replace '\\', '/').TrimEnd('/').ToLowerInvariant()
}

if (-not $PrePull -and -not $SelfTest) {
    $gitRoot = (& git rev-parse --show-toplevel 2>$null)
    if ($LASTEXITCODE -ne 0 -or -not $gitRoot) {
        Die 'not inside a git checkout.'
    }
    $primary = $env:RF2_MAYOR_ROOT
    if (-not $primary) {
        $primary = (& git worktree list --porcelain |
            Where-Object { $_ -like 'worktree *' } |
            Select-Object -First 1) -replace '^worktree ', ''
    }
    if ($primary -and
        (Get-NormalizedPath $gitRoot.Trim()) -ne (Get-NormalizedPath $primary.Trim())) {
        Die 'this is a linked (worker) worktree; the tracker database is the mayor checkout''s to commit.'
    }
}

# ---------------------------------------------------------------------------
# -SelfTest - prove the divergence guard rather than asserting it.
#
# The .sh sibling is covered by layer 8 of scripts/git-hooks/test-pre-commit.sh,
# which is POSIX sh and cannot run this file. Windows is the operator's platform
# and the platform that produced commit 667c744dc875, so the Windows half needs
# its own proof. Everything below runs against a throwaway git repo and a stub
# `bd` in a temp directory: the real tracker database is never opened, let alone
# written.
#
# The cases are the bead's acceptance (rf2-rjqtj), plus the false-positive case
# that matters more than any of them - ordinary forward motion must still commit,
# because a guard that fires on every checkpoint is a guard that gets bypassed.
# ---------------------------------------------------------------------------
if ($SelfTest) {
    $script:failures = 0
    $box = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "rf2-bdchk-selftest-$PID")
    if (Test-Path -LiteralPath $box) { Remove-Item -LiteralPath $box -Recurse -Force }
    $bin  = Join-Path $box 'bin'
    $repo = Join-Path $box 'repo'
    New-Item -ItemType Directory -Force -Path $bin | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $repo 'scripts') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $repo '.beads') | Out-Null
    $dbPath = Join-Path $box 'db.jsonl'

    function Write-Rows {
        param([string]$Path, [string[]]$Rows)
        $sw = New-Object System.IO.StreamWriter($Path, $false,
                                                (New-Object System.Text.UTF8Encoding $false))
        try {
            $sw.NewLine = "`n"
            foreach ($r in $Rows) { $sw.WriteLine($r) }
        } finally { $sw.Dispose() }
    }

    # Stub `bd`. It REFUSES a bare export, modelling bd v1.1.2 (rf2-fifk0): the
    # flagless form drops every `bd remember` row, so a checkpoint that ever
    # loses --include-memories reds every case below instead of quietly
    # committing a memory-less tracker.
    $stub = @(
        '@echo off',
        'echo.%*| findstr /C:"--include-memories" >nul',
        'if errorlevel 1 (',
        '  echo stub bd: a bare export would drop every memory row ^(rf2-fifk0^) 1>&2',
        '  exit /b 1',
        ')',
        'type "%~dp0..\db.jsonl"'
    )
    Write-Rows -Path (Join-Path $bin 'bd.cmd') -Rows $stub

    Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $repo 'scripts/beads-checkpoint.ps1') -Force
    & git -C $repo init -q -b main 2>&1 | Out-Null
    & git -C $repo config user.email 'bdchk-selftest@example.invalid' | Out-Null
    & git -C $repo config user.name  'bdchk-selftest' | Out-Null
    & git -C $repo config commit.gpgsign false | Out-Null

    $env:PATH = "$bin;$env:PATH"
    # Re-invoke THIS host, so the proof is about the interpreter the operator
    # actually runs (Windows PowerShell 5.x via `powershell -File`, or pwsh).
    $hostExe = (Get-Process -Id $PID).Path
    $childOut = Join-Path $box 'out.txt'
    $childErr = Join-Path $box 'err.txt'

    function Invoke-Child {
        param([string[]]$ChildArgs)
        $all = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                 (Join-Path $repo 'scripts/beads-checkpoint.ps1')) + $ChildArgs
        $p = Start-Process -FilePath $hostExe -ArgumentList $all -WorkingDirectory $repo `
                           -NoNewWindow -Wait -PassThru `
                           -RedirectStandardOutput $childOut -RedirectStandardError $childErr
        return $p.ExitCode
    }

    function Get-ChildErr { return (Get-Content -LiteralPath $childErr -Raw -ErrorAction SilentlyContinue) }
    function Get-ChildOut { return (Get-Content -LiteralPath $childOut -Raw -ErrorAction SilentlyContinue) }
    function Get-HeadTracker { return (& git -C $repo show 'HEAD:.beads/issues.jsonl') -join "`n" }
    function Get-HeadSha { return (& git -C $repo rev-parse HEAD).Trim() }

    # The memory `value` a given commit carries for a key, or '' when that commit
    # carries no such row. This is the emitted recovery lookup expressed without
    # jq, which Windows does not ship: the claim under test is that the printed
    # REFERENCE still resolves, not that jq is installed. `${Ref}:` needs the
    # braces - a bare `$Ref:` parses as a scope qualifier. The try/catch is
    # load-bearing under $ErrorActionPreference = 'Stop': pwsh 7.4+ turns a
    # native command's non-zero exit into a terminating error, and asking an
    # unresolvable ref is a case these tests deliberately exercise.
    function Get-MemoryValueAt {
        param([string]$Ref, [string]$Key)
        if (-not $Ref) { return '' }
        try { $rows = @(& git -C $repo show "${Ref}:.beads/issues.jsonl" 2>$null) }
        catch { return '' }
        if ($LASTEXITCODE -ne 0) { return '' }
        foreach ($r in $rows) {
            if ($r -match '"_type":"memory"' -and
                $r -match ('"key":"' + [regex]::Escape($Key) + '"') -and
                $r -match '"value":"([^"]*)"') {
                return $Matches[1]
            }
        }
        return ''
    }

    function Assert-True {
        param([bool]$Cond, [string]$What, [string]$Detail = '')
        if ($Cond) { Write-Output "  PASS  $What" }
        else {
            Write-Output "  FAIL  $What"
            if ($Detail) { Write-Output "        $Detail" }
            $script:failures++
        }
    }

    Write-Output 'beads-checkpoint -SelfTest: divergence guard (rf2-rjqtj)'

    # THE FIXTURE, and it is the bead's own: HEAD and Dolt hold the SAME NUMBER
    # of rows but different facts, one for one.
    #
    #   rf2-a   unchanged on both sides
    #   rf2-b   NEWER ON GIT   - closed at 03:00; Dolt still has it open at 12:00
    #                            the previous day
    #   rf2-c   NEWER ON DOLT  - closed at 02:00; Git still has it open
    #   rf2-g1  GIT ONLY       - Dolt has never heard of it
    #   rf2-d1  DOLT ONLY      - Git has never heard of it
    #
    # Four issue rows plus two memories on each side. The row-count floor sees
    # 6 == 6 and is satisfied; that is precisely the hole.
    $headRowsFixture = @(
        '{"_type":"issue","id":"rf2-a","status":"open","updated_at":"2026-08-01T00:00:00Z"}',
        '{"_type":"issue","id":"rf2-b","status":"closed","updated_at":"2026-08-02T03:00:00Z"}',
        '{"_type":"issue","id":"rf2-c","status":"open","updated_at":"2026-08-01T00:00:00Z"}',
        '{"_type":"issue","id":"rf2-g1","status":"open","updated_at":"2026-08-02T01:00:00Z"}',
        '{"_type":"memory","key":"m1","value":"one"}',
        '{"_type":"memory","key":"m2","value":"two"}'
    )
    $doltRowsFixture = @(
        '{"_type":"issue","id":"rf2-a","status":"open","updated_at":"2026-08-01T00:00:00Z"}',
        '{"_type":"issue","id":"rf2-b","status":"open","updated_at":"2026-08-01T12:00:00Z"}',
        '{"_type":"issue","id":"rf2-c","status":"closed","updated_at":"2026-08-02T02:00:00Z"}',
        '{"_type":"issue","id":"rf2-d1","status":"open","updated_at":"2026-08-02T01:30:00Z"}',
        '{"_type":"memory","key":"m1","value":"one"}',
        '{"_type":"memory","key":"m2","value":"two"}'
    )

    Write-Rows -Path (Join-Path $repo '.beads/issues.jsonl') -Rows $headRowsFixture
    & git -C $repo add -- 'scripts/beads-checkpoint.ps1' '.beads/issues.jsonl' | Out-Null
    & git -C $repo commit -q -m 'seed: tracker at HEAD' | Out-Null
    Write-Rows -Path $dbPath -Rows $doltRowsFixture

    # T1 - THE ACCEPTANCE. Equal counts, disjoint one-for-one substitution, one
    # newer state on each side. Neither side's facts may be lost, so the only
    # safe answer is to refuse and name them.
    $before = Get-HeadSha
    $code = Invoke-Child @()
    $err  = Get-ChildErr
    Assert-True ($code -ne 0) 'T1 an equal-count divergence is REFUSED' "exit $code"
    Assert-True ($err -match 'EQUAL COUNTS ARE NOT EQUALITY') 'T1 and it says why the floor was not enough'
    Assert-True ($err -match 'GONE\s+rf2-g1') 'T1 names the Git-only bead that would be DELETED'
    Assert-True ($err -match 'REVERT\s+rf2-b') 'T1 names the Git-newer bead that would be REVERTED'
    Assert-True ($err -match '2026-08-02T03:00:00Z' -and $err -match 'status=closed') `
                'T1 reports the FIELDS, not just the ids'
    Assert-True (-not ($err -match 'rf2-c')) 'T1 does not cry wolf over the Dolt-newer row'
    Assert-True (-not ($err -match 'rf2-d1')) 'T1 does not cry wolf over the Dolt-only row'
    Assert-True ((Get-HeadSha) -eq $before) 'T1 commits nothing'
    $work = (Get-Content -LiteralPath (Join-Path $repo '.beads/issues.jsonl') -Raw)
    Assert-True ($work -match 'rf2-g1' -and -not ($work -match 'rf2-d1')) `
                'T1 leaves the working tracker UNTOUCHED'

    # T1b - the refusal is actionable: the remedy file holds the Git-only and
    # Git-newer rows and nothing else, so one `bd import` is the whole recovery.
    $remedyPath = ''
    if ($err -match 'bd import (\S+)') { $remedyPath = $Matches[1] }
    Assert-True ($remedyPath -ne '' -and (Test-Path -LiteralPath $remedyPath)) `
                'T1b a remedy file is written and named in the message'
    if ($remedyPath -and (Test-Path -LiteralPath $remedyPath)) {
        $rem = @(Get-Content -LiteralPath $remedyPath)
        Assert-True ($rem.Count -eq 2 -and ($rem -join "`n") -match 'rf2-g1' -and
                     ($rem -join "`n") -match 'rf2-b') `
                    'T1b it holds exactly the two Git-only/Git-newer rows' "got $($rem.Count) rows"
        Remove-Item -LiteralPath $remedyPath -Force -ErrorAction SilentlyContinue
    }

    # T2 - AND THE RECOVERY COMPLETES. The operator runs that import; the
    # database is now the UNION. The next checkpoint must commit, and the
    # committed tracker must carry ALL FOUR facts - neither side lost anything.
    $union = @(
        '{"_type":"issue","id":"rf2-a","status":"open","updated_at":"2026-08-01T00:00:00Z"}',
        '{"_type":"issue","id":"rf2-b","status":"closed","updated_at":"2026-08-02T03:00:00Z"}',
        '{"_type":"issue","id":"rf2-c","status":"closed","updated_at":"2026-08-02T02:00:00Z"}',
        '{"_type":"issue","id":"rf2-d1","status":"open","updated_at":"2026-08-02T01:30:00Z"}',
        '{"_type":"issue","id":"rf2-g1","status":"open","updated_at":"2026-08-02T01:00:00Z"}',
        '{"_type":"memory","key":"m1","value":"one"}',
        '{"_type":"memory","key":"m2","value":"two"}'
    )
    Write-Rows -Path $dbPath -Rows $union
    $before = Get-HeadSha
    $code = Invoke-Child @()
    Assert-True ($code -eq 0) 'T2 the post-import checkpoint succeeds' "exit $code : $(Get-ChildErr)"
    Assert-True ((Get-HeadSha) -ne $before) 'T2 and it commits'
    $committed = Get-HeadTracker
    Assert-True ($committed -match 'rf2-g1') 'T2 the Git-only bead SURVIVED'
    Assert-True ($committed -match '"id":"rf2-b","status":"closed"') 'T2 the Git-side close SURVIVED'
    Assert-True ($committed -match 'rf2-d1') 'T2 the Dolt-only bead SURVIVED'
    Assert-True ($committed -match '"id":"rf2-c","status":"closed"') 'T2 the Dolt-side close SURVIVED'
    Assert-True ($committed -match '"key":"m1"' -and $committed -match '"key":"m2"') `
                'T2 and the memory rows rode along (--include-memories intact)'

    # T3 - THE FALSE-POSITIVE CASE, which matters more than the rest. Ordinary
    # forward motion - Dolt closes a bead and gains a new one, Git is simply
    # behind - is what a checkpoint is FOR. It must commit without a murmur.
    $forward = $union + @('{"_type":"issue","id":"rf2-e","status":"open","updated_at":"2026-08-03T00:00:00Z"}')
    $forward = $forward -replace '"id":"rf2-a","status":"open","updated_at":"2026-08-01T00:00:00Z"',
                                 '"id":"rf2-a","status":"closed","updated_at":"2026-08-03T01:00:00Z"'
    Write-Rows -Path $dbPath -Rows $forward
    $before = Get-HeadSha
    $code = Invoke-Child @()
    Assert-True ($code -eq 0) 'T3 a strictly-ahead database still checkpoints' "exit $code : $(Get-ChildErr)"
    Assert-True ((Get-HeadSha) -ne $before) 'T3 and it commits the forward motion'
    $committed = Get-HeadTracker
    Assert-True ($committed -match '"id":"rf2-a","status":"closed"' -and $committed -match 'rf2-e') `
                'T3 the new close and the new bead both landed'

    # T4 - THE AMBIGUOUS ROW. Same `updated_at`, different `status`: neither
    # side is newer, so neither may be chosen automatically. Presence is not
    # state - an interrupted Dolt GC reverted a close in the field while every
    # id stayed intact, which an id-set comparison would have called clean.
    $ambig = $forward -replace '"id":"rf2-e","status":"open"', '"id":"rf2-e","status":"closed"'
    Write-Rows -Path $dbPath -Rows $ambig
    $before = Get-HeadSha
    $code = Invoke-Child @()
    $err = Get-ChildErr
    Assert-True ($code -ne 0) 'T4 a same-timestamp status conflict is REFUSED' "exit $code"
    Assert-True ($err -match 'AMBIG\s+rf2-e') 'T4 and the row is named as ambiguous'
    Assert-True ((Get-HeadSha) -eq $before) 'T4 commits nothing'
    Assert-True (-not ($err -match 'bd import')) `
                'T4 offers no import: an import cannot adjudicate a tie'

    # T5 - the read-only arm still answers, under the same StrictMode. The
    # working tracker matches HEAD here, so the answer is a silent yes.
    & git -C $repo checkout -q HEAD -- .beads/issues.jsonl
    $code = Invoke-Child @('-PrePull')
    Assert-True ($code -eq 0) 'T5 -PrePull is silent on a checkpointed tracker' "exit $code : $(Get-ChildErr)"

    # T6 - and it warns when the working export IS ahead of HEAD (rf2-51uz1),
    # which is the other half of this file's job.
    Add-Content -LiteralPath (Join-Path $repo '.beads/issues.jsonl') `
                -Value '{"_type":"issue","id":"rf2-z","status":"open","updated_at":"2026-08-04T00:00:00Z"}'
    $code = Invoke-Child @('-PrePull')
    Assert-True ($code -ne 0) 'T6 -PrePull warns when clearing .beads would discard state'
    Assert-True ((Get-ChildErr) -match 'AHEAD of HEAD') 'T6 and says so in those words'

    # -----------------------------------------------------------------------
    # T7-T9: THE MEMORY RECONCILIATION (rf2-cve7).
    #
    # T1-T4 grade the ISSUE rows. Nothing above grades the memories: the row
    # floor is dominated by issue rows and Get-GitOnlyFacts reads issue rows
    # only, so a memory-only deletion is invisible to both - which is how 210
    # keys vanished on 2026-09-08 with `bd stats` reporting a healthy issue
    # count throughout.
    #
    # The fixture models that arithmetic rather than just the symptom: 20 issues
    # and 10 memories at HEAD (30 rows) against an export that has lost 2
    # memories (28 rows). 28*10 = 280 is NOT less than 30*9 = 270, so the floor
    # is silent here exactly as it was on the real event.
    #
    # AND IT MUST NOT REFUSE. This is the one shared tool the mayor runs several
    # times an hour; a false positive that aborted it would halt the dispatch
    # loop. T7 asserts both halves - it warns, AND it commits - and T9 is the
    # no-false-positive case, which matters more than either.
    # -----------------------------------------------------------------------
    # Concatenation rather than the -f operator: these rows are JSON, so every
    # one carries literal braces and -f reads them as format placeholders
    # ("Error formatting a string"). -SelfTest caught that too.
    $memHead = @()
    for ($i = 1; $i -le 20; $i++) {
        $n = $i.ToString('00')
        $memHead += '{"_type":"issue","id":"rf2-m' + $n + '","status":"open","updated_at":"2026-09-01T00:00:00Z"}'
    }
    for ($i = 1; $i -le 10; $i++) {
        $n = $i.ToString('00')
        $memHead += '{"_type":"memory","key":"mem-key-' + $n + '","value":"body ' + $i + '"}'
    }
    Write-Rows -Path (Join-Path $repo '.beads/issues.jsonl') -Rows $memHead
    & git -C $repo add -- '.beads/issues.jsonl' | Out-Null
    & git -C $repo commit -q -m 'seed: 20 issues and 10 memories' | Out-Null

    # The same database one retention cull later: two memories dropped, every
    # issue row untouched.
    $memCulled = $memHead | Where-Object {
        $_ -notmatch '"key":"mem-key-03"' -and $_ -notmatch '"key":"mem-key-07"'
    }
    Assert-True ($memCulled.Count -eq 28 -and $memHead.Count -eq 30) `
                'T7 the fixture slides under the row floor (28/30 rows), as the real event did' `
                "got $($memCulled.Count)/$($memHead.Count)"

    Write-Rows -Path $dbPath -Rows $memCulled
    $before = Get-HeadSha
    $code = Invoke-Child @()
    $err  = Get-ChildErr
    Assert-True ($code -eq 0) 'T7 a memory-only deletion does NOT refuse the checkpoint' "exit $code"
    Assert-True ((Get-HeadSha) -ne $before) 'T7 and it still commits'
    Assert-True ($err -match 'MEMORY RECONCILIATION FAILED') `
                'T7 a memory-only deletion WARNS rather than passing in silence'
    Assert-True ($err -match 'mem-key-03' -and $err -match 'mem-key-07') `
                'T7 and it NAMES the lost keys'
    Assert-True (-not ($err -match 'mem-key-05')) 'T7 and names no key that was not lost'
    Assert-True ($err -match 'export  28 rows = 20 issues \+ 8 memories' -and
                 $err -match 'HEAD    30 rows = 20 issues \+ 10 memories') `
                'T7 and reports both populations on both sides, separately counted'
    Assert-True ($err -match 'WARNING, NOT A REFUSAL') `
                'T7 and says the checkpoint continues, so the operator can tell what happened'

    # T7, second half: THE PRINTED RECOVERY COMMAND MUST ACTUALLY RECOVER
    # (rf2-cve7, merged-PR audit of #9520).
    #
    # The warning above is correct and fires correctly. Its recovery instruction
    # was not: it printed `git show HEAD:.beads/issues.jsonl`, and the checkpoint
    # COMMITS the export a few lines after printing it. By the time an operator
    # reads the message and pastes the command, `HEAD` IS the commit that removed
    # the rows, so the lookup exits 0 and prints NOTHING - the worst available
    # failure shape for a recovery instruction, because success and total failure
    # are the same exit code and the same empty output.
    #
    # These assertions therefore run the emitted lookup AFTER the checkpoint has
    # committed, with the HEAD-form control beside them reproducing the defect in
    # the same repo in the same breath.
    $recRefs = @([regex]::Matches($err, '(?m)^\s*git show (\S+?):') |
                 ForEach-Object { $_.Groups[1].Value })
    $recRef = if ($recRefs.Count -gt 0) { $recRefs[0] } else { '' }
    Assert-True ($recRefs.Count -eq 1) `
                'T7 the warning carries exactly one recovery lookup' "found $($recRefs.Count)"
    Assert-True ($recRef -eq $before) `
                'T7 and it names the immutable pre-checkpoint commit, not the moving HEAD' `
                "printed '$recRef', pre-checkpoint commit '$before'"

    $lookupRef = if ($recRef) { $recRef } else { 'HEAD' }
    Assert-True ((Get-MemoryValueAt -Ref $lookupRef -Key 'mem-key-03') -eq 'body 3') `
                'T7 and running it AFTER the checkpoint committed recovers the deleted value'
    # The control, and it is the whole reason the assertion above means anything:
    # the reference the script USED to print returns nothing at this exact point.
    Assert-True ((Get-MemoryValueAt -Ref 'HEAD' -Key 'mem-key-03') -eq '') `
                'T7 while the same lookup against HEAD recovers nothing - the defect this pins'

    # And later commits must not invalidate it. A commit oid is content-addressed
    # so this holds by construction - but "by construction" is what the HEAD form
    # looked like too, so it is asserted rather than assumed.
    Write-Rows -Path (Join-Path $repo 'later.txt') -Rows @('a later commit, unrelated to the tracker')
    & git -C $repo add -- 'later.txt' | Out-Null
    & git -C $repo commit -q -m 'a later commit lands on top of the checkpoint' | Out-Null
    Assert-True ((Get-MemoryValueAt -Ref $lookupRef -Key 'mem-key-03') -eq 'body 3') `
                'T7 and a further commit on top does not invalidate the printed reference'

    # T8 - a row of an unknown `_type` is reported. This is the "two populations
    # sum to the row count" half of the bead. It cannot detect the deletion
    # above - the identity holds trivially whenever every row is one of the two
    # known types - but it catches a row that is neither.
    Write-Rows -Path $dbPath -Rows ($memHead + @('{"_type":"sprint","id":"s1"}'))
    $before = Get-HeadSha
    $code = Invoke-Child @()
    $err  = Get-ChildErr
    Assert-True ($code -eq 0) 'T8 an unknown _type row does not refuse the checkpoint' "exit $code"
    Assert-True ($err -match 'neither an issue nor a memory') `
                'T8 a row that is neither an issue nor a memory is reported'
    Assert-True ((Get-HeadSha) -ne $before) 'T8 and it still commits'

    # T9 - THE NO-FALSE-POSITIVE CASE. Ordinary forward motion - an issue
    # closes, a memory is ADDED, the rest are shuffled the way `bd export`
    # shuffles them on every invocation - must commit without a murmur.
    #
    # HEAD IS RE-SEEDED FIRST: T8's checkpoint COMMITTED its unknown-`_type`
    # row, so HEAD would carry it into this case and the guard would truthfully
    # report it - a red that looks like a false positive and is not one. A
    # no-false-positive case has to start from a clean baseline.
    Write-Rows -Path (Join-Path $repo '.beads/issues.jsonl') -Rows $memHead
    & git -C $repo add -- '.beads/issues.jsonl' | Out-Null
    & git -C $repo commit -q -m 'seed: back to 20 issues and 10 memories' | Out-Null

    $forwardMem = @()
    for ($i = 1; $i -le 20; $i++) {
        $n  = $i.ToString('00')
        $st = if ($i -eq 4) { 'closed' } else { 'open' }
        $up = if ($i -eq 4) { '2026-09-02T00:00:00Z' } else { '2026-09-01T00:00:00Z' }
        $forwardMem += '{"_type":"issue","id":"rf2-m' + $n + '","status":"' + $st + '","updated_at":"' + $up + '"}'
    }
    for ($i = 10; $i -ge 1; $i--) {
        $n = $i.ToString('00')
        $forwardMem += '{"_type":"memory","key":"mem-key-' + $n + '","value":"body ' + $i + '"}'
    }
    $forwardMem += '{"_type":"memory","key":"mem-key-11","value":"a new lesson"}'
    Write-Rows -Path $dbPath -Rows $forwardMem
    $before = Get-HeadSha
    $code = Invoke-Child @()
    $err  = Get-ChildErr
    Assert-True ($code -eq 0) 'T9 ordinary forward motion still checkpoints' "exit $code : $err"
    Assert-True ((Get-HeadSha) -ne $before) 'T9 and it commits'
    Assert-True (-not ($err -match 'MEMORY RECONCILIATION')) `
                'T9 the reconciliation does NOT cry wolf on a close, a new memory and a reorder' $err
    Assert-True ((Get-HeadTracker) -match '"key":"mem-key-11"') `
                'T9 and the new memory reached the commit'

    Remove-Item -LiteralPath $box -Recurse -Force -ErrorAction SilentlyContinue
    if ($script:failures -gt 0) {
        Write-Output ''
        Write-Output "beads-checkpoint -SelfTest: $($script:failures) FAILED"
        exit 1
    }
    Write-Output ''
    Write-Output 'beads-checkpoint -SelfTest: all cases passed'
    exit 0
}

$tmpExport = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(),
                                       "rf2-bdchk-export-$PID.jsonl")
$tmpHead   = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(),
                                       "rf2-bdchk-head-$PID.jsonl")
$tmpOrdered = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(),
                                        "rf2-bdchk-ordered-$PID.jsonl")

try {
    # -----------------------------------------------------------------------
    # -PrePull: would clearing .beads throw tracker state away?
    # -----------------------------------------------------------------------
    if ($PrePull) {
        if (-not (Test-Path -LiteralPath $tracker)) { exit 0 }
        Write-HeadCopy -Path $tmpHead
        if (Test-SameContent -A $tracker -B $tmpHead) { exit 0 }
        $workRows = Get-RowCount -Path $tracker
        $headRows = Get-RowCount -Path $tmpHead
        # The third step below is deliberately NOT the `&&` form the .sh sibling
        # prints (rf2-9m1n4). It is a pasteable PowerShell line, and `&&` is a
        # PARSE ERROR in Windows PowerShell 5.x, which this script supports
        # (`#requires -Version 5`, and see the `powershell -File` note above);
        # `; if ($?) { ... }` is the equivalent checked sequence and gates
        # identically under 5.x and pwsh 7. Do not "reconcile" it to `&&`.
        # It must stay a fetch-then-rebase pair rather than `git pull --rebase`:
        # a pull rebases onto FETCH_HEAD, a scratch file any concurrent git
        # process in the same checkout rewrites, so naming remote and branch
        # constrains only the fetch half. See CLAUDE.md's Beads durability.
        $lines = @(
            '',
            '[re-frame2] the working tracker export is AHEAD of HEAD.',
            "  working $workRows rows, HEAD $headRows rows, in $tracker",
            '',
            '  `git checkout HEAD -- .beads` here would revert it, and the next',
            '  checkpoint would write that revert back over the database - the',
            '  rf2-51uz1 fault, which has silently reopened closed beads before.',
            '',
            '  Checkpoint first, then clear, then update:',
            '',
            '      powershell -ExecutionPolicy Bypass -File scripts/beads-checkpoint.ps1',
            '      git checkout HEAD -- .beads',
            '      git fetch origin main; if ($?) { git rebase origin/main }',
            ''
        )
        [Console]::Error.WriteLine(($lines -join "`n"))
        exit 1
    }

    # -----------------------------------------------------------------------
    # checkpoint: export from the database, verify, commit.
    # -----------------------------------------------------------------------
    # Resolve bd to a full path rather than trusting CreateProcess's own PATH
    # search, which only appends .exe: a shim (bd.cmd) would otherwise be found
    # by PowerShell and then not found by Process.Start.
    $bdCmd = Get-Command bd -ErrorAction SilentlyContinue
    if (-not $bdCmd) {
        Die 'bd is not on PATH; a checkpoint must re-export from the database, so it cannot proceed.'
    }

    # --include-memories is load-bearing (rf2-fifk0). bd v1.1.2 made the bare
    # export EXCLUDE the `bd remember` memory rows that v1.0.3 always carried,
    # so a flagless checkpoint would silently drop every one of them - caught
    # only because the shrink floor below refused the memory-less export
    # against HEAD. The tracker commits whole: issues AND memories.
    $code = Invoke-RawToFile -Exe $bdCmd.Source -Arguments @('export', '--include-memories') -OutFile $tmpExport
    if ($code -ne 0) {
        Die "bd export failed (exit $code); leaving $tracker untouched."
    }

    Write-HeadCopy -Path $tmpHead
    # The commit $tmpHead was read from - captured HERE, beside the copy it
    # names, because the memory warning below prints it as a RECOVERY REFERENCE
    # and this script COMMITS the fresh export a hundred lines later (rf2-cve7,
    # merged-PR audit of #9520). Printing `HEAD` there is worse than useless: by
    # the time an operator reads the warning and pastes the command, `HEAD` IS
    # the checkpoint that just removed those rows, so the lookup exits 0 and
    # prints nothing - succeeding, and recovering nothing.
    #
    # A full commit oid is content-addressed and immutable, so it keeps naming
    # this exact tree after the checkpoint commits and after any number of later
    # commits land on top; `HEAD` is a symbolic ref re-resolved at read time and
    # does not. Empty on an unborn branch (a first-ever checkpoint), where there
    # is no baseline to recover from and the recovery paragraph is skipped.
    # --quiet keeps git silent on an unborn branch, and the try/catch is
    # load-bearing rather than defensive: $ErrorActionPreference is 'Stop' here,
    # and pwsh 7.4+ turns a native command's non-zero exit into a terminating
    # error, so the unborn-branch case would abort the whole checkpoint.
    $baselineCommit = ''
    try {
        $probe = (& git rev-parse --verify --quiet HEAD 2>$null)
        if ($LASTEXITCODE -eq 0 -and $probe) { $baselineCommit = ([string]$probe).Trim() }
    } catch { $baselineCommit = '' }

    $exportRows = Get-RowCount -Path $tmpExport
    $headRows   = Get-RowCount -Path $tmpHead

    # TRUNCATION GUARD. A `git add` that caught the JSONL mid-rewrite landed an
    # empty export on main once already (incident 2026-06-10, commit
    # 7aea52459), and an export that loses a tenth of the tracker is a bug, not
    # a checkpoint.
    if ($exportRows -le 0) {
        Die "bd export produced 0 rows; refusing to checkpoint. $tracker is untouched."
    }
    if ($headRows -gt 0 -and ($exportRows * 10) -lt ($headRows * 9)) {
        Die ("export has $exportRows rows, HEAD has $headRows - more than a tenth of the " +
             "tracker would disappear. Refusing to checkpoint; $tracker is untouched. " +
             'Inspect with `bd status`, then commit by hand if the shrink is genuine.')
    }

    # DIVERGENCE GUARD (rf2-rjqtj). The floor above answers "is the export big
    # enough?". It cannot answer "does the export still contain what HEAD
    # contains?" - and at equal counts it has already said yes to an export
    # that did not. Nothing has been written yet, so a refusal here leaves the
    # tracker exactly as it was found.
    $facts = Get-GitOnlyFacts -Export $tmpExport -HeadCopy $tmpHead
    if ($facts.Report.Count -gt 0) {
        $lines = New-Object 'System.Collections.Generic.List[string]'
        $lines.Add('beads-checkpoint: HEAD carries tracker facts the fresh export does NOT.')
        if ($exportRows -eq $headRows) {
            $lines.Add("  export $exportRows rows, HEAD $headRows rows. EQUAL COUNTS ARE NOT EQUALITY:")
            $lines.Add('  commit 667c744dc875 passed this floor at 1938 == 1938 and still deleted three')
            $lines.Add('  issues and reverted two closes, because Git and Dolt had diverged one for one.')
        } else {
            $lines.Add("  export $exportRows rows, HEAD $headRows rows.")
        }
        $lines.Add('')
        foreach ($r in $facts.Report) { $lines.Add($r) }
        $lines.Add('')
        $lines.Add('  Committing this export would lose exactly those facts, so it was NOT committed.')
        $lines.Add("  $tracker is UNTOUCHED.")
        $lines.Add('')
        if ($facts.RemedyRows.Count -gt 0) {
            $remedyPath = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(),
                                                    "rf2-beads-git-only-$PID.jsonl")
            $sw = New-Object System.IO.StreamWriter($remedyPath, $false,
                                                    (New-Object System.Text.UTF8Encoding $false))
            try {
                $sw.NewLine = "`n"
                foreach ($r in $facts.RemedyRows) { $sw.WriteLine($r) }
            } finally {
                $sw.Dispose()
            }
            $lines.Add('  To teach the database what Git already knows, then checkpoint again:')
            $lines.Add('')
            $lines.Add("      bd import $remedyPath")
            $lines.Add('      powershell -ExecutionPolicy Bypass -File scripts/beads-checkpoint.ps1')
            $lines.Add('')
            $lines.Add('  That file holds only the rows above, as HEAD holds them; `bd import` is')
            $lines.Add('  timestamp-safe, so it creates what is missing, updates what is genuinely')
            $lines.Add('  newer, and skips the rest. Newer database rows are preserved.')
            $lines.Add('')
        }
        $lines.Add('  If the loss is DELIBERATE (a `bd delete`, a `bd gc`, an AMBIG row you have')
        $lines.Add('  adjudicated), take the export by hand and commit it yourself:')
        $lines.Add('')
        $lines.Add("      bd export --include-memories > $tracker")
        $lines.Add("      git add -- $tracker; git commit -m ""chore(beads): ...""")
        $lines.Add('')
        [Console]::Error.WriteLine(($lines -join "`n"))
        exit 1
    }

    # MEMORY RECONCILIATION (rf2-cve7). Every guard above is blind to the
    # memory rows: the floor is diluted by the issue rows and the divergence
    # guard reads only `"_type":"issue"`. This one counts the two populations
    # separately and names the keys HEAD holds that the export does not.
    #
    # IT WARNS. IT DOES NOT REFUSE, and that is a deliberate design constraint
    # rather than an unfinished one. This is the single shared tool the mayor
    # runs several times an hour, and a false positive that aborted it would
    # halt the whole dispatch loop - which is precisely why the reconciliation
    # sat unbuilt as "a ruling and not a dispatch" while 210 keys went missing
    # in silence. A warning delivers the entire value of the guard (the event
    # becomes one loud block instead of nothing at all) with that risk removed.
    # There is deliberately no strict mode, no override flag and no config key.
    #
    # It also runs AFTER the divergence guard on purpose, so it speaks only
    # when the checkpoint is genuinely about to commit and can say so truly.
    # @(...) is load-bearing under Set-StrictMode: PowerShell unrolls an empty
    # or single-element array return, so a bare assignment yields $null on the
    # ordinary no-warning path and `.Count` then throws. Caught by -SelfTest T2
    # and T3, which is what that arm is for.
    $memFacts = @(Get-MemoryFacts -Export $tmpExport -HeadCopy $tmpHead)
    if ($memFacts.Count -gt 0) {
        $lines = New-Object 'System.Collections.Generic.List[string]'
        $lines.Add('')
        $lines.Add('beads-checkpoint: ***** MEMORY RECONCILIATION FAILED (rf2-cve7) *****')
        $lines.Add('  The tracker''s `bd remember` rows do not reconcile against HEAD.')
        $lines.Add('')
        foreach ($r in $memFacts) { $lines.Add($r) }
        $lines.Add('')
        $lines.Add('  `bd stats` reports ISSUES ONLY, and the row-count floor above is dominated')
        $lines.Add('  by issue rows, so a memory-only deletion passes both in silence. That is')
        $lines.Add('  exactly how 210 keys disappeared on 2026-09-08 with nothing on screen.')
        $lines.Add('')
        $lines.Add('  THIS IS A WARNING, NOT A REFUSAL - the checkpoint continues and commits')
        $lines.Add('  the export. The database is the source of truth, and this may well be a')
        $lines.Add('  deliberate `bd forget` or a retention cull. If it is NOT, the rows are not')
        if ($baselineCommit) {
            $lines.Add('  lost. The commit this export was compared against still carries every')
            $lines.Add('  one of them, and this is the lookup:')
            $lines.Add('')
            $lines.Add("      git show ${baselineCommit}:$tracker |")
            $lines.Add('        jq -r --arg k "<key>" ''select(._type=="memory" and .key==$k)|.value''')
            $lines.Add('')
            $lines.Add('  THAT COMMIT IS SPELLED OUT RATHER THAN `HEAD` ON PURPOSE. This checkpoint')
            $lines.Add('  commits the export a moment from now, so by the time you read this `HEAD`')
            $lines.Add('  is the commit that REMOVED the rows: the same lookup against it would exit')
            $lines.Add('  0 and print nothing - succeeding, and recovering nothing. A full commit oid')
            $lines.Add('  is immutable, so it stays valid after this commit and every later one.')
            $lines.Add('')
            $lines.Add('  Older checkpoints are NOT a general fallback - one made before a key was')
            $lines.Add('  created does not carry it. To find the commit where any single key changed:')
            $lines.Add('')
            $lines.Add("      git log -S'""key"":""<key>""' -- $tracker")
        } else {
            $lines.Add('  lost - but this is a first-ever checkpoint with no commit behind it, so')
            $lines.Add('  there is no baseline to recover from. The database is the only copy.')
        }
        $lines.Add('')
        $lines.Add('  Select on `.key`. A bare grep for the key matches rows that merely MENTION')
        $lines.Add('  it - bead prose naming a deleted key has already been mistaken for the')
        $lines.Add('  memory itself (rf2-cve7, CLAUDE.md instrument item (f)).')
        $lines.Add('')
        [Console]::Error.WriteLine(($lines -join "`n"))
    }

    # The export is trustworthy - it is now the working tracker. From here on
    # the working file cannot be a stale revert, whatever it was a moment ago.
    #
    # It is written in MINIMAL-DIFF order (rf2-51uz1.1) rather than raw export
    # order, so the staged ledger shows the rows that changed and nothing else.
    # The row-count check is the safety net: the rewrite must reproduce the
    # export's rows exactly, and if it ever does not, the raw export wins.
    # Losing a row to a cosmetic reordering would be a far worse bug than the
    # churn it removes.
    Write-MinimalDiffOrder -Export $tmpExport -HeadCopy $tmpHead -OutFile $tmpOrdered
    $orderedRows = Get-RowCount -Path $tmpOrdered
    if ($orderedRows -eq $exportRows) {
        Copy-Item -LiteralPath $tmpOrdered -Destination $tracker -Force
    } else {
        [Console]::Error.WriteLine(
            "beads-checkpoint: minimal-diff rewrite produced $orderedRows rows for a " +
            "$exportRows-row export; committing the raw export instead (order churn, " +
            'but no lost rows).')
        Copy-Item -LiteralPath $tmpExport -Destination $tracker -Force
    }

    if (Test-SameContent -A $tracker -B $tmpHead) {
        Write-Output "beads-checkpoint: nothing to checkpoint ($exportRows rows, unchanged)."
        exit 0
    }

    # Explicit pathspec, both to `git add` and to `git commit`: anything else
    # the operator had staged stays staged, and nothing else is swept in.
    & git add -- $tracker
    if ($LASTEXITCODE -ne 0) { Die 'git add failed.' }
    & git commit -q -m $Message -- $tracker
    if ($LASTEXITCODE -ne 0) { Die 'git commit failed.' }
    Write-Output "beads-checkpoint: committed $tracker ($exportRows rows, HEAD had $headRows)."
}
finally {
    Remove-Item -LiteralPath $tmpExport, $tmpHead, $tmpOrdered -Force -ErrorAction SilentlyContinue
}
