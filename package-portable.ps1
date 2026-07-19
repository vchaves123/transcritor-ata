# Builds a portable package (app-image) of transcritor-ata for Windows 11 64-bit.
#
# The result runs without installation and without administrator privileges: just unzip the
# generated .zip into any folder (including Downloads or the Desktop) and run
# transcritor-ata.exe. It includes its own JRE (generated via jpackage) and the external
# transcription tools (ffmpeg, whisper-cli CPU and CUDA) -- except for the Whisper model, which
# the user chooses and downloads on first run (ModelSetupDialog).
#
# Speaker identification (diarization) runs entirely inside the app's own JVM via ONNX Runtime
# (pyannote models embedded in the jar) -- no external process, no additional runtime.
#
# Usage:
#   .\package-portable.ps1 [-Version 1.0.0]
#
# Requirements: JDK 21 with jpackage on the PATH, Maven, and the tools/ folder already populated
# (see README.md - prerequisites section - for how to download ffmpeg/whisper-cli).

param(
    [string]$Version = "1.0.0"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot
Set-Location $ProjectRoot

$AppName = "transcritor-ata"
$StagingDir = Join-Path $ProjectRoot "jpackage-input"
$ReleaseDir = Join-Path $ProjectRoot "release"
$AppImageDir = Join-Path $ReleaseDir $AppName
$ToolsSrc = Join-Path $ProjectRoot "tools"
$ZipName = "$AppName-portable-win64-$Version.zip"

Write-Host "== 1/5: Compiling the fat-jar (mvn clean package) ==" -ForegroundColor Cyan
& mvn -q clean package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }

Write-Host "== 2/5: Preparing staging for jpackage ==" -ForegroundColor Cyan
if (Test-Path $StagingDir) { Remove-Item $StagingDir -Recurse -Force }
if (Test-Path $ReleaseDir) { Remove-Item $ReleaseDir -Recurse -Force }
New-Item -ItemType Directory -Path $StagingDir | Out-Null
Copy-Item (Join-Path $ProjectRoot "target\transcritor-ata.jar") $StagingDir

Write-Host "== 3/5: Generating portable app-image with jpackage ==" -ForegroundColor Cyan
# --java-options -XX:TieredStopAtLevel=1: disables the C2 JIT compiler. This works around a
# native JVM crash (EXCEPTION_ACCESS_VIOLATION inside jvm.dll itself, on the
# "C2 CompilerThread" thread, compiling methods completely unrelated to our code) observed on
# newer Intel hybrid CPUs with Temurin 21.0.11 -- a JIT bug, not an application bug.
# Some peak performance is lost (only the C1 compiler remains), an acceptable trade-off for
# stability for non-technical end users.
& jpackage `
    --type app-image `
    --input $StagingDir `
    --main-jar transcritor-ata.jar `
    --main-class com.tailor.transcritorata.gui.MainApp `
    --name $AppName `
    --app-version $Version `
    --vendor "Tailor" `
    --icon (Join-Path $ProjectRoot "packaging\app.ico") `
    --java-options "-XX:TieredStopAtLevel=1" `
    --dest $ReleaseDir
if ($LASTEXITCODE -ne 0) { throw "jpackage failed." }

Write-Host "== 4/5: Copying external tools (ffmpeg, whisper-cli) ==" -ForegroundColor Cyan
if (-not (Test-Path $ToolsSrc)) {
    throw "tools/ folder not found at $ToolsSrc. Download ffmpeg/whisper-cli before packaging (see README)."
}
$ToolsDest = Join-Path $AppImageDir "tools"
Copy-Item $ToolsSrc $ToolsDest -Recurse
# The Whisper model is NOT included in the package: the user chooses and downloads it on first run.
$ModelsDir = Join-Path $ToolsDest "models"
if (Test-Path $ModelsDir) { Remove-Item $ModelsDir -Recurse -Force }

# SHA-256 manifest of every bundled executable: lets the app notice (and warn about, not block on
# -- this manifest ships inside the same zip, so it can't defend against the zip itself being
# tampered with) local corruption or in-place modification of these binaries *after* extraction.
$ChecksumsFile = Join-Path $ToolsDest "CHECKSUMS.sha256"
$ChecksumLines = Get-ChildItem -Path $ToolsDest -Recurse -Filter "*.exe" | ForEach-Object {
    $RelativePath = $_.FullName.Substring($ToolsDest.Length + 1).Replace('\', '/')
    $Hash = (Get-FileHash -Path $_.FullName -Algorithm SHA256).Hash.ToLower()
    "$Hash  $RelativePath"
}
# Hashes/paths are pure ASCII; written via .NET directly (instead of Set-Content -Encoding utf8)
# to avoid Windows PowerShell 5.1's default UTF-8 BOM, which would otherwise corrupt the first
# line when the app reads this manifest back.
[System.IO.File]::WriteAllLines($ChecksumsFile, $ChecksumLines, (New-Object System.Text.UTF8Encoding $false))

@"
transcritor-ata $Version - portable version for Windows 11 (64-bit)

How to use:
  1. Unzip this folder anywhere (Downloads, Desktop, a USB drive, etc.).
     No installation or administrator privileges required.
  2. Run transcritor-ata.exe.
  3. The first time, choose and download a transcription (Whisper) model when prompted.
  4. Ready to use. See "Help -> Verify installation" inside the program if something
     is missing.

This package already includes: Java (dedicated runtime), ffmpeg, whisper-cli (CPU and GPU/CUDA,
automatically selected based on your video card), and speaker identification (local AI models,
embedded in the program itself). The only download you need to make is the transcription
model, on first run.
"@ | Out-File -FilePath (Join-Path $AppImageDir "README.txt") -Encoding utf8

Write-Host "== 5/5: Compressing final package ==" -ForegroundColor Cyan
$ZipPath = Join-Path $ProjectRoot $ZipName
if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }
Compress-Archive -Path $AppImageDir -DestinationPath $ZipPath -CompressionLevel Optimal

$SizeMb = [math]::Round((Get-Item $ZipPath).Length / 1MB, 1)
Write-Host ""
Write-Host "Package generated: $ZipPath ($SizeMb MB)" -ForegroundColor Green
Write-Host "Unpacked folder (for local testing): $AppImageDir" -ForegroundColor Green
