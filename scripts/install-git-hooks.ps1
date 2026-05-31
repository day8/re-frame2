#requires -Version 5
# scripts/install-git-hooks.ps1 - Windows-friendly mirror of
# scripts/install-git-hooks.sh.
#
# ASCII-only by design: Windows PowerShell 5.x reads -File scripts as the
# system ANSI codepage, so a BOM-less UTF-8 multibyte char (e.g. an em-dash)
# in a code position breaks tokenization. Keep this file pure ASCII.
#
# Same behaviour: idempotent install of the re-frame2-managed segments
# of the repo's git hooks, plus the mayor-marker file that gates the
# pre-commit hook (rf2-ydl2p). The hook scripts themselves are POSIX sh
# (Git on Windows ships a `sh.exe` from the Git Bash bundle that runs
# hooks); this installer just stages them into <gitdir>/hooks.
#
# Installs:
#   - post-merge       (rf2-6jj3r) MCP-staleness advisory warning.
#   - pre-commit       (rf2-ydl2p) refuses commits in the MAYOR checkout
#                       that touch worker-tracked surfaces.
#   - mayor-marker     (rf2-ydl2p) sentinel at <common-dir>/mayor-marker;
#                       hook activation gate. Worker worktrees have a
#                       distinct per-worktree git dir, so they never see
#                       this marker and the pre-commit hook no-ops there.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/install-git-hooks.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/install-git-hooks.ps1 -Check

[CmdletBinding()]
param(
    [switch]$Check
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot  = (Resolve-Path (Join-Path $scriptDir '..')).Path
$srcDir    = Join-Path $scriptDir 'git-hooks'
$commonDir = (& git -C $repoRoot rev-parse --git-common-dir).Trim()
if (-not [System.IO.Path]::IsPathRooted($commonDir)) {
    $commonDir = Join-Path $repoRoot $commonDir
}
$hooksDir = Join-Path $commonDir 'hooks'
New-Item -ItemType Directory -Force -Path $hooksDir | Out-Null

# Per-hook marker pairs. Keys are hook names; values are @(BEGIN, END).
$hookMarkers = @{
    'post-merge' = @(
        '# --- BEGIN re-frame2 MCP-staleness check (rf2-6jj3r) ---',
        '# --- END re-frame2 MCP-staleness check (rf2-6jj3r) ---'
    )
    'pre-commit' = @(
        '# --- BEGIN re-frame2 mayor commit boundary (rf2-ydl2p) ---',
        '# --- END re-frame2 mayor commit boundary (rf2-ydl2p) ---'
    )
}

function Get-MarkerBlock {
    param(
        [string]$Path,
        [string]$BeginMark,
        [string]$EndMark
    )
    $lines = Get-Content -LiteralPath $Path
    $out = New-Object System.Collections.Generic.List[string]
    $inBlock = $false
    foreach ($line in $lines) {
        if ($line -eq $BeginMark) { $inBlock = $true }
        if ($inBlock) { $out.Add($line) }
        if ($line -eq $EndMark) { $inBlock = $false }
    }
    return ($out -join "`n")
}

function Install-Hook {
    param([string]$HookName)

    $marks     = $hookMarkers[$HookName]
    if (-not $marks) {
        Write-Error "install-git-hooks: unknown hook name: $HookName"
    }
    $beginMark = $marks[0]
    $endMark   = $marks[1]

    $src = Join-Path $srcDir $HookName
    $dst = Join-Path $hooksDir $HookName

    if (-not (Test-Path -LiteralPath $src)) {
        Write-Error "install-git-hooks: missing source $src"
    }

    $sourceBlock = Get-MarkerBlock -Path $src -BeginMark $beginMark -EndMark $endMark
    if ([string]::IsNullOrWhiteSpace($sourceBlock)) {
        Write-Error "install-git-hooks: source $src has no marker block"
    }

    if (-not (Test-Path -LiteralPath $dst)) {
        if ($Check) {
            Write-Error "install-git-hooks: $dst missing"
        }
        $newContent = @(
            '#!/usr/bin/env sh',
            "# $HookName - managed in part by scripts/install-git-hooks.ps1",
            '',
            $sourceBlock
        ) -join "`n"
        # LF line endings: hooks must be sh-friendly even on Windows.
        [System.IO.File]::WriteAllText($dst, $newContent + "`n", (New-Object System.Text.UTF8Encoding $false))
        Write-Output "install-git-hooks: installed $dst"
        return
    }

    $existing = Get-Content -LiteralPath $dst -Raw
    if ($existing -match [regex]::Escape($beginMark)) {
        $existingBlock = Get-MarkerBlock -Path $dst -BeginMark $beginMark -EndMark $endMark
        if ($existingBlock -eq $sourceBlock) {
            if (-not $Check) {
                Write-Output "install-git-hooks: $dst up to date"
            }
            return
        }
        if ($Check) {
            Write-Error "install-git-hooks: $dst block out of date"
        }
        # Strip the existing block, then append the fresh one.
        $stripped = [regex]::Replace(
            $existing,
            "(?ms)" + [regex]::Escape($beginMark) + ".*?" + [regex]::Escape($endMark) + "`r?`n?",
            ''
        )
        $stripped = $stripped.TrimEnd("`r","`n") + "`n"
        $newContent = $stripped + $sourceBlock + "`n"
        [System.IO.File]::WriteAllText($dst, $newContent, (New-Object System.Text.UTF8Encoding $false))
        Write-Output "install-git-hooks: refreshed $dst"
        return
    }

    # No marker yet - append.
    if ($Check) {
        Write-Error "install-git-hooks: $dst block missing"
    }
    $stripped = $existing.TrimEnd("`r","`n") + "`n`n"
    $newContent = $stripped + $sourceBlock + "`n"
    [System.IO.File]::WriteAllText($dst, $newContent, (New-Object System.Text.UTF8Encoding $false))
    Write-Output "install-git-hooks: appended block to $dst"
}

function Install-MayorMarker {
    # Drops <common-dir>/mayor-marker. The marker is the activation gate
    # for the pre-commit hook (rf2-ydl2p). It lives in the mayor's
    # per-worktree git dir (== common dir for the primary worktree);
    # worker worktrees have a distinct per-worktree git dir at
    # <common>/worktrees/<name>/ and therefore see no marker -> hook no-ops.
    $markerPath = Join-Path $commonDir 'mayor-marker'
    $markerContent = @"
re-frame2 mayor checkout marker (rf2-ydl2p).
Presence of this file in <git-dir> activates the pre-commit hook that
refuses commits to worker-tracked surfaces. Managed by
scripts/install-git-hooks.ps1; safe to delete (the hook then no-ops and
this checkout behaves like a worker worktree from the hook's POV).
"@
    # Normalise to LF and trailing-newline so sh and ps1 installers agree.
    $expected = ($markerContent -replace "`r`n","`n").TrimEnd("`n") + "`n"

    if (-not (Test-Path -LiteralPath $markerPath)) {
        if ($Check) {
            Write-Error "install-git-hooks: mayor-marker missing at $markerPath"
        }
        [System.IO.File]::WriteAllText($markerPath, $expected, (New-Object System.Text.UTF8Encoding $false))
        Write-Output "install-git-hooks: installed mayor-marker at $markerPath"
        return
    }

    $current = Get-Content -LiteralPath $markerPath -Raw
    $currentNorm = ($current -replace "`r`n","`n")
    if ($currentNorm -eq $expected) {
        if (-not $Check) {
            Write-Output "install-git-hooks: mayor-marker up to date"
        }
        return
    }
    if ($Check) {
        Write-Error "install-git-hooks: mayor-marker content drifted at $markerPath"
    }
    [System.IO.File]::WriteAllText($markerPath, $expected, (New-Object System.Text.UTF8Encoding $false))
    Write-Output "install-git-hooks: refreshed mayor-marker at $markerPath"
}

try {
    Install-Hook -HookName 'post-merge'
    Install-Hook -HookName 'pre-commit'
    Install-MayorMarker
}
catch {
    if ($Check) {
        Write-Output "`nRun scripts/install-git-hooks.ps1 (without -Check) to install."
    }
    throw
}
