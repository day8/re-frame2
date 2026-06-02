#requires -Version 5
# scripts/install-skills.ps1 - Windows-friendly mirror of
# scripts/install-skills.sh.
#
# ASCII-only by design: Windows PowerShell 5.x reads -File scripts as the
# system ANSI codepage, so a BOM-less UTF-8 multibyte char (e.g. an em-dash)
# in a code position breaks tokenization. Keep this file pure ASCII.
#
# Deploys every skills/<name>/ directory into ~/.claude/skills/<name> BY LINK,
# not by copy, so the active skill Claude Code loads is the SAME directory as
# the repo source - edits in either are reflected in the other. This kills the
# stale-copy drift (rf2-901lr): a one-shot copy froze ~10 days behind the repo
# and Claude Code loaded the stale skill.
#
# Link primitive: a directory JUNCTION via `New-Item -ItemType Junction`. A
# junction needs NO admin / Developer Mode (a Windows *symlink* does); Claude
# Code reads through it like a symlink. This is the right primitive for a dir
# on Windows.
#
# Idempotent: re-running re-links. A target already pointing at this repo's
# skill dir is left alone. A junction pointing elsewhere is re-pointed. A real
# (non-link) directory is a stale COPY: the installer WARNS and refuses to
# clobber it unless -Force is given, so local edits to a copy are not lost.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/install-skills.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/install-skills.ps1 -Force
#   powershell -ExecutionPolicy Bypass -File scripts/install-skills.ps1 -Check
#   powershell -ExecutionPolicy Bypass -File scripts/install-skills.ps1 -Target DIR

[CmdletBinding()]
param(
    [switch]$Check,
    [switch]$Force,
    [string]$Target
)

$ErrorActionPreference = 'Stop'

$scriptDir  = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot   = (Resolve-Path (Join-Path $scriptDir '..')).Path
$skillsSrc  = Join-Path $repoRoot 'skills'

# Derive the install target WITHOUT hardcoding a home or username.
if (-not $Target) {
    $homeDir = $env:USERPROFILE
    if (-not $homeDir) { $homeDir = $HOME }
    if (-not $homeDir) {
        Write-Error 'install-skills: neither $env:USERPROFILE nor $HOME is set; pass -Target DIR'
    }
    $Target = Join-Path (Join-Path $homeDir '.claude') 'skills'
}

if (-not (Test-Path -LiteralPath $skillsSrc)) {
    Write-Error "install-skills: no skills directory at $skillsSrc"
}

# Resolve a directory to its real (target) path so a junction compares equal to
# its source. ReparsePoint dirs expose .Target (the junction destination).
function Resolve-RealDir {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $Path }
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
        $t = $item.Target
        if ($t) {
            # .Target may be an array on some PS versions; take the first.
            if ($t -is [array]) { $t = $t[0] }
            return ([System.IO.Path]::GetFullPath($t)).TrimEnd('\')
        }
    }
    return ($item.FullName).TrimEnd('\')
}

# Is $Path a reparse point (junction/symlink)?
function Test-IsLink {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $false }
    $item = Get-Item -LiteralPath $Path -Force
    return [bool]($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)
}

$mode    = if ($Check) { 'check' } else { 'install' }
$linked  = 0
$skipped = 0
$rc      = 0

if (-not (Test-Path -LiteralPath $Target)) {
    if ($mode -eq 'install') {
        New-Item -ItemType Directory -Force -Path $Target | Out-Null
    }
}

# Link whatever skills/<name> dirs exist (audit ALL of skills/, per the bead -
# including shared/, which carries the retro-protocol doc + tests).
$entries = Get-ChildItem -LiteralPath $skillsSrc -Directory
foreach ($entry in $entries) {
    $name = $entry.Name
    $src  = ([System.IO.Path]::GetFullPath($entry.FullName)).TrimEnd('\')
    $dst  = Join-Path $Target $name

    $existsAsLinkToUs = $false
    if (Test-Path -LiteralPath $dst) {
        if ((Test-IsLink $dst) -and ((Resolve-RealDir $dst) -eq $src)) {
            $existsAsLinkToUs = $true
        }
    }

    if ($existsAsLinkToUs) {
        if ($mode -eq 'install') {
            Write-Output "install-skills: $name already linked -> $src"
        }
        continue
    }

    if ($mode -eq 'check') {
        if (Test-Path -LiteralPath $dst) {
            Write-Error "install-skills: $name present but not linked to this repo ($dst)" -ErrorAction Continue
        } else {
            Write-Error "install-skills: $name not installed ($dst)" -ErrorAction Continue
        }
        $rc = 1
        continue
    }

    # install mode
    if ((Test-Path -LiteralPath $dst) -and -not (Test-IsLink $dst)) {
        # A real directory == a stale COPY. Do not clobber without -Force.
        if (-not $Force) {
            Write-Warning "install-skills: $dst is a real directory (a COPY), not a link."
            Write-Warning "                Refusing to replace it - your local edits would be lost."
            Write-Warning "                Re-run with -Force to replace this copy with a link to the repo:"
            Write-Warning "                  powershell -ExecutionPolicy Bypass -File scripts/install-skills.ps1 -Force"
            $skipped++
            continue
        }
        Remove-Item -LiteralPath $dst -Recurse -Force
    }
    elseif (Test-Path -LiteralPath $dst) {
        # A reparse point pointing elsewhere (or broken) - safe to re-point.
        # Remove the junction itself (Remove-Item on a junction does not touch
        # the target's contents).
        Remove-Item -LiteralPath $dst -Recurse -Force
    }

    New-Item -ItemType Junction -Path $dst -Target $src | Out-Null
    Write-Output "install-skills: linked $name -> $src"
    $linked++
}

if ($mode -eq 'check') {
    if ($rc -ne 0) {
        Write-Output "`nRun scripts/install-skills.ps1 (without -Check) to (re)link."
    } else {
        Write-Output 'install-skills: all skills linked and current.'
    }
    exit $rc
}

Write-Output ""
Write-Output "install-skills: linked $linked, skipped $skipped (copies left intact; use -Force to replace)."
Write-Output "install-skills: target $Target now mirrors $skillsSrc by link."
if ($skipped -gt 0) { exit 1 }
exit 0
