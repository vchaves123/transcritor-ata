# Builds a Windows installer (.msi) for transcritor-ata using jpackage + WiX Toolset 3.x.
#
# A proper installer that non-technical users can just double-click: it adds Start Menu / Desktop
# shortcuts, registers the app in "Add or Remove Programs" (including a real Uninstall entry),
# and supports in-place upgrades across versions via a fixed --win-upgrade-uuid. It installs
# per-user, so no administrator privileges / UAC prompt are required.
#
# ffmpeg and whisper-cli are NOT bundled here: the installer only ships the Java app itself
# (~150-200 MB instead of ~935 MB), and the app downloads whichever of those two tools it needs
# (whisper-cli CPU vs. CUDA build, based on the machine's actual GPU) the first time it runs --
# see PrerequisiteSetupDialog.java / ToolPackageDownloader.java. This also means a machine without
# an NVIDIA GPU never has to download the ~650 MB CUDA build at all, unlike the old bundle-both
# approach.
#
# Usage:
#   .\package-installer.ps1 [-Version 1.0.0]
#
# Requirements: JDK 21 with jpackage on the PATH, Maven, WiX Toolset 3.x (candle.exe/light.exe on
# PATH -- install with: winget install WiXToolset.WiXToolset).

param(
    [string]$Version = "1.0.0"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot
Set-Location $ProjectRoot

$AppName = "transcritor-ata"
$StagingDir = Join-Path $ProjectRoot "jpackage-input"
$ReleaseDir = Join-Path $ProjectRoot "release-installer"

# Fixed GUID so Windows Installer treats a new .msi as an in-place upgrade of a previous install
# (replacing it) instead of a separate product. Generated once for this app -- NEVER change this
# for future releases, or upgrades will silently stop working (users would end up with two
# side-by-side installs instead of one being replaced).
$UpgradeUuid = "5f3b6a3e-6c9a-4b0b-9c1a-2b6a6e6f8a1c"

Write-Host "== 1/4: Compiling the fat-jar (mvn clean package) ==" -ForegroundColor Cyan
& mvn -q clean package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }

Write-Host "== 2/4: Preparing staging for jpackage ==" -ForegroundColor Cyan
foreach ($dir in @($StagingDir, $ReleaseDir)) {
    if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
}
New-Item -ItemType Directory -Path $StagingDir | Out-Null
Copy-Item (Join-Path $ProjectRoot "target\transcritor-ata.jar") $StagingDir

Write-Host "== 3/4: Checking for WiX Toolset (candle.exe/light.exe) ==" -ForegroundColor Cyan
if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue)) {
    throw "WiX Toolset not found on PATH. Install it with: winget install WiXToolset.WiXToolset " +
          "(run from an elevated/Administrator terminal, then restart this terminal so PATH updates)."
}

Write-Host "== 4/4: Generating .msi installer with jpackage ==" -ForegroundColor Cyan
# --java-options -XX:TieredStopAtLevel=1: disables the C2 JIT compiler. This works around a
# native JVM crash (EXCEPTION_ACCESS_VIOLATION inside jvm.dll itself, on the "C2 CompilerThread"
# thread, compiling methods completely unrelated to our code) observed on newer Intel hybrid CPUs
# with Temurin 21.0.11 -- a JIT bug, not an application bug. Some peak performance is lost (only
# the C1 compiler remains), an acceptable trade-off for stability for non-technical end users.
# --license-file: without it, jpackage emits a bare-bones WiX UI (just a progress dialog) that
# closes itself the instant the install finishes -- no confirmation that it succeeded. Passing a
# license file switches jpackage to the full WixUI_Minimal wizard (Welcome -> License -> Progress
# -> Finish), which is what actually shows the user a "installation completed successfully" screen.
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
    --license-file (Join-Path $ProjectRoot "LICENSE") `
    --java-options "-XX:TieredStopAtLevel=1" `
    --win-menu `
    --win-menu-group $AppName `
    --win-shortcut `
    --win-per-user-install `
    --win-upgrade-uuid $UpgradeUuid `
    --dest $ReleaseDir
if ($LASTEXITCODE -ne 0) { throw "jpackage failed." }

Write-Host "== Done ==" -ForegroundColor Cyan
$MsiPath = Get-ChildItem -Path $ReleaseDir -Filter "*.msi" | Select-Object -First 1
if (-not $MsiPath) { throw "jpackage reported success but no .msi was found in $ReleaseDir." }

$SizeMb = [math]::Round($MsiPath.Length / 1MB, 1)
Write-Host ""
Write-Host "Installer generated: $($MsiPath.FullName) ($SizeMb MB)" -ForegroundColor Green
Write-Host "Double-click it to install -- no administrator privileges required (per-user install)." -ForegroundColor Green
