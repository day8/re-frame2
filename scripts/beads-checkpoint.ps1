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
# The commit carries the rows that changed and nothing else: `bd export` does
# not fix the order of the memory rows, so the file is written in minimal-diff
# order first (rf2-51uz1.1, Write-MinimalDiffOrder below).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/beads-checkpoint.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/beads-checkpoint.ps1 -Message "chore(beads): checkpoint (6931 merged)"
#   powershell -ExecutionPolicy Bypass -File scripts/beads-checkpoint.ps1 -PrePull

[CmdletBinding()]
param(
    [switch]$PrePull,
    [string]$Message = 'chore(beads): checkpoint'
)

$ErrorActionPreference = 'Stop'

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

# ---------------------------------------------------------------------------
# The tracker database is the MAYOR checkout's to commit (rf2-ia8o7). The
# primary worktree is the first entry of `git worktree list --porcelain`; the
# same derivation as scripts/git-hooks/lib/check-beads-boundary.sh and
# scripts/assert-worker-worktree.ps1, with the same RF2_MAYOR_ROOT override.
# ---------------------------------------------------------------------------
function Get-NormalizedPath {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path) {
        $Path = (Resolve-Path -LiteralPath $Path).Path
    }
    return ($Path -replace '\\', '/').TrimEnd('/').ToLowerInvariant()
}

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
        $lines = @(
            '',
            '[re-frame2] the working tracker export is AHEAD of HEAD.',
            "  working $workRows rows, HEAD $headRows rows, in $tracker",
            '',
            '  `git checkout HEAD -- .beads` here would revert it, and the next',
            '  checkpoint would write that revert back over the database - the',
            '  rf2-51uz1 fault, which has silently reopened closed beads before.',
            '',
            '  Checkpoint first, then clear, then pull:',
            '',
            '      powershell -ExecutionPolicy Bypass -File scripts/beads-checkpoint.ps1',
            '      git checkout HEAD -- .beads',
            '      git pull --rebase',
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

    $code = Invoke-RawToFile -Exe $bdCmd.Source -Arguments @('export') -OutFile $tmpExport
    if ($code -ne 0) {
        Die "bd export failed (exit $code); leaving $tracker untouched."
    }

    Write-HeadCopy -Path $tmpHead
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
