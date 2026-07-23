# Builds a Windows installer (.msi) for transcritor-ata using jpackage + WiX Toolset 3.x.
#
# A proper installer that non-technical users can just double-click: it adds Start Menu / Desktop
# shortcuts, registers the app in "Add or Remove Programs" (including a real Uninstall entry),
# and supports in-place upgrades across versions via a fixed --win-upgrade-uuid. It installs
# per-user, so no administrator privileges / UAC prompt are required.
#
# Usage:
#   .\package-installer.ps1 [-Version 1.0.0]
#
# Requirements: JDK 21 with jpackage on the PATH, Maven, WiX Toolset 3.x (candle.exe/light.exe on
# PATH -- install with: winget install WiXToolset.WiXToolset), and the tools/ folder already
# populated (see README.md - prerequisites section - for how to download ffmpeg/whisper-cli).

param(
    [string]$Version = "1.0.0"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot
Set-Location $ProjectRoot

$AppName = "transcritor-ata"
$StagingDir = Join-Path $ProjectRoot "jpackage-input"
$ContentStagingDir = Join-Path $ProjectRoot "jpackage-content"
$ReleaseDir = Join-Path $ProjectRoot "release-installer"
$ToolsSrc = Join-Path $ProjectRoot "tools"

# Fixed GUID so Windows Installer treats a new .msi as an in-place upgrade of a previous install
# (replacing it) instead of a separate product. Generated once for this app -- NEVER change this
# for future releases, or upgrades will silently stop working (users would end up with two
# side-by-side installs instead of one being replaced).
$UpgradeUuid = "5f3b6a3e-6c9a-4b0b-9c1a-2b6a6e6f8a1c"

Write-Host "== 1/6: Compiling the fat-jar (mvn clean package) ==" -ForegroundColor Cyan
& mvn -q clean package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }

Write-Host "== 2/6: Preparing staging for jpackage ==" -ForegroundColor Cyan
foreach ($dir in @($StagingDir, $ContentStagingDir, $ReleaseDir)) {
    if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
}
New-Item -ItemType Directory -Path $StagingDir | Out-Null
New-Item -ItemType Directory -Path $ContentStagingDir | Out-Null
Copy-Item (Join-Path $ProjectRoot "target\transcritor-ata.jar") $StagingDir

Write-Host "== 3/6: Preparing bundled tools (ffmpeg, whisper-cli) + checksum manifest ==" -ForegroundColor Cyan
if (-not (Test-Path $ToolsSrc)) {
    throw "tools/ folder not found at $ToolsSrc. Download ffmpeg/whisper-cli before packaging (see README)."
}
$ToolsStagingDir = Join-Path $ContentStagingDir "tools"
Copy-Item $ToolsSrc $ToolsStagingDir -Recurse
# The Whisper model is NOT included in the package: the user chooses and downloads it on first run.
$ModelsDir = Join-Path $ToolsStagingDir "models"
if (Test-Path $ModelsDir) { Remove-Item $ModelsDir -Recurse -Force }

# SHA-256 manifest of every bundled executable: lets the app notice (and warn about, not block on
# -- this manifest ships inside the same installer, so it can't defend against the installer
# itself being tampered with) local corruption or in-place modification of these binaries *after*
# installation.
$ChecksumsFile = Join-Path $ToolsStagingDir "CHECKSUMS.sha256"
$ChecksumLines = Get-ChildItem -Path $ToolsStagingDir -Recurse -Filter "*.exe" | ForEach-Object {
    $RelativePath = $_.FullName.Substring($ToolsStagingDir.Length + 1).Replace('\', '/')
    $Hash = (Get-FileHash -Path $_.FullName -Algorithm SHA256).Hash.ToLower()
    "$Hash  $RelativePath"
}
# Hashes/paths are pure ASCII; written via .NET directly (instead of Set-Content -Encoding utf8)
# to avoid Windows PowerShell 5.1's default UTF-8 BOM, which would otherwise corrupt the first
# line when the app reads this manifest back.
[System.IO.File]::WriteAllLines($ChecksumsFile, $ChecksumLines, (New-Object System.Text.UTF8Encoding $false))

Write-Host "== 4/6: Checking for WiX Toolset (candle.exe/light.exe) ==" -ForegroundColor Cyan
if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue)) {
    throw "WiX Toolset not found on PATH. Install it with: winget install WiXToolset.WiXToolset " +
          "(run from an elevated/Administrator terminal, then restart this terminal so PATH updates)."
}

Write-Host "== 5/6: Generating .msi installer with jpackage ==" -ForegroundColor Cyan
# --java-options -XX:TieredStopAtLevel=1: disables the C2 JIT compiler. This works around a
# native JVM crash (EXCEPTION_ACCESS_VIOLATION inside jvm.dll itself, on the "C2 CompilerThread"
# thread, compiling methods completely unrelated to our code) observed on newer Intel hybrid CPUs
# with Temurin 21.0.11 -- a JIT bug, not an application bug. Some peak performance is lost (only
# the C1 compiler remains), an acceptable trade-off for stability for non-technical end users.
# --app-content: bundles the prepared tools/ folder (ffmpeg, whisper-cli, checksum manifest)
# alongside the launcher exe in the installed app's own folder.
& jpackage `
    --type msi `
    --input $StagingDir `
    --main-jar transcritor-ata.jar `
    --main-class com.tailor.transcritorata.gui.MainApp `
    --name $AppName `
    --app-version $Version `
    --vendor "Tailor" `
    --description "Meeting recording transcription and minutes generation" `
    --icon (Join-Path $ProjectRoot "packaging\app.ico") `
    --java-options "-XX:TieredStopAtLevel=1" `
    --app-content $ToolsStagingDir `
    --win-menu `
    --win-menu-group $AppName `
    --win-shortcut `
    --win-per-user-install `
    --win-upgrade-uuid $UpgradeUuid `
    --dest $ReleaseDir
if ($LASTEXITCODE -ne 0) { throw "jpackage failed." }

Write-Host "== 6/6: Done ==" -ForegroundColor Cyan
$MsiPath = Get-ChildItem -Path $ReleaseDir -Filter "*.msi" | Select-Object -First 1
if (-not $MsiPath) { throw "jpackage reported success but no .msi was found in $ReleaseDir." }

$SizeMb = [math]::Round($MsiPath.Length / 1MB, 1)
Write-Host ""
Write-Host "Installer generated: $($MsiPath.FullName) ($SizeMb MB)" -ForegroundColor Green
Write-Host "Double-click it to install -- no administrator privileges required (per-user install)." -ForegroundColor Green
