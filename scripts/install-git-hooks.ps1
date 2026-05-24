#requires -Version 5
# scripts/install-git-hooks.ps1 — Windows-friendly mirror of
# scripts/install-git-hooks.sh (rf2-6jj3r).
#
# Same behaviour: idempotent install of the re-frame2-managed segments
# of the repo's git hooks. The hook scripts themselves are POSIX sh
# (Git on Windows ships a `sh.exe` from the Git Bash bundle that runs
# hooks); this installer just stages them into <gitdir>/hooks.
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
$hooksDir  = (& git -C $repoRoot rev-parse --git-common-dir).Trim()
if (-not [System.IO.Path]::IsPathRooted($hooksDir)) {
    $hooksDir = Join-Path $repoRoot $hooksDir
}
$hooksDir = Join-Path $hooksDir 'hooks'
New-Item -ItemType Directory -Force -Path $hooksDir | Out-Null

$beginMark = '# --- BEGIN re-frame2 MCP-staleness check (rf2-6jj3r) ---'
$endMark   = '# --- END re-frame2 MCP-staleness check (rf2-6jj3r) ---'

function Get-MarkerBlock {
    param([string]$Path)
    $lines = Get-Content -LiteralPath $Path
    $out = New-Object System.Collections.Generic.List[string]
    $inBlock = $false
    foreach ($line in $lines) {
        if ($line -eq $beginMark) { $inBlock = $true }
        if ($inBlock) { $out.Add($line) }
        if ($line -eq $endMark) { $inBlock = $false }
    }
    return ($out -join "`n")
}

function Install-Hook {
    param([string]$HookName)
    $src = Join-Path $srcDir $HookName
    $dst = Join-Path $hooksDir $HookName

    if (-not (Test-Path -LiteralPath $src)) {
        Write-Error "install-git-hooks: missing source $src"
    }

    $sourceBlock = Get-MarkerBlock -Path $src
    if ([string]::IsNullOrWhiteSpace($sourceBlock)) {
        Write-Error "install-git-hooks: source $src has no marker block"
    }

    if (-not (Test-Path -LiteralPath $dst)) {
        if ($Check) {
            Write-Error "install-git-hooks: $dst missing"
        }
        $newContent = @(
            '#!/usr/bin/env sh',
            "# $HookName — managed in part by scripts/install-git-hooks.ps1",
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
        $existingBlock = Get-MarkerBlock -Path $dst
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

    # No marker yet — append.
    if ($Check) {
        Write-Error "install-git-hooks: $dst block missing"
    }
    $stripped = $existing.TrimEnd("`r","`n") + "`n`n"
    $newContent = $stripped + $sourceBlock + "`n"
    [System.IO.File]::WriteAllText($dst, $newContent, (New-Object System.Text.UTF8Encoding $false))
    Write-Output "install-git-hooks: appended block to $dst"
}

try {
    Install-Hook -HookName 'post-merge'
}
catch {
    if ($Check) {
        Write-Output "`nRun scripts/install-git-hooks.ps1 (without -Check) to install."
    }
    throw
}
