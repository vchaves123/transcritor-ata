# Gera um pacote portavel (app-image) do transcritor-ata para Windows 11 64-bit.
#
# O resultado roda sem instalacao e sem privilegios de administrador: e so descompactar o
# .zip gerado em qualquer pasta (inclusive em Downloads ou no Desktop) e executar
# transcritor-ata.exe. Inclui um JRE proprio (gerado via jpackage) e todas as ferramentas
# externas (ffmpeg, whisper-cli CPU e CUDA, LIUM_SpkDiarization) -- exceto o modelo Whisper,
# que o proprio usuario escolhe e baixa na primeira execucao (ModelSetupDialog).
#
# O runtime do jpackage NAO inclui um java.exe utilizavel (soh o launcher nativo do app), entao
# este script tambem gera via jlink um runtime Java minimo dedicado (tools/jre) so para rodar o
# LIUM_SpkDiarization como processo externo -- sem isso, a identificacao de participantes falha
# silenciosamente em qualquer maquina sem JDK instalado separadamente.
#
# Uso:
#   .\package-portable.ps1 [-Version 1.0.0]
#
# Requisitos: JDK 21 com jpackage e jlink no PATH, Maven, e a pasta tools/ ja populada
# (veja o README.md - secao de pre-requisitos - para como baixar ffmpeg/whisper-cli/LIUM).

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

Write-Host "== 1/5: Compilando o fat-jar (mvn clean package) ==" -ForegroundColor Cyan
& mvn -q clean package
if ($LASTEXITCODE -ne 0) { throw "Build Maven falhou." }

Write-Host "== 2/5: Preparando staging para o jpackage ==" -ForegroundColor Cyan
if (Test-Path $StagingDir) { Remove-Item $StagingDir -Recurse -Force }
if (Test-Path $ReleaseDir) { Remove-Item $ReleaseDir -Recurse -Force }
New-Item -ItemType Directory -Path $StagingDir | Out-Null
Copy-Item (Join-Path $ProjectRoot "target\transcritor-ata.jar") $StagingDir

Write-Host "== 3/5: Gerando app-image portavel com jpackage ==" -ForegroundColor Cyan
& jpackage `
    --type app-image `
    --input $StagingDir `
    --main-jar transcritor-ata.jar `
    --main-class com.tailor.transcritorata.gui.MainApp `
    --name $AppName `
    --app-version $Version `
    --vendor "Tailor" `
    --dest $ReleaseDir
if ($LASTEXITCODE -ne 0) { throw "jpackage falhou." }

Write-Host "== 4/6: Copiando ferramentas externas (ffmpeg, whisper-cli, LIUM) ==" -ForegroundColor Cyan
if (-not (Test-Path $ToolsSrc)) {
    throw "Pasta tools/ nao encontrada em $ToolsSrc. Baixe ffmpeg/whisper-cli/LIUM antes de empacotar (veja o README)."
}
$ToolsDest = Join-Path $AppImageDir "tools"
Copy-Item $ToolsSrc $ToolsDest -Recurse
# O modelo Whisper NAO entra no pacote: o usuario escolhe e baixa na primeira execucao.
$ModelsDir = Join-Path $ToolsDest "models"
if (Test-Path $ModelsDir) { Remove-Item $ModelsDir -Recurse -Force }

Write-Host "== 5/6: Gerando runtime Java dedicado para o LIUM (jlink) ==" -ForegroundColor Cyan
$JreDest = Join-Path $ToolsDest "jre"
if (Test-Path $JreDest) { Remove-Item $JreDest -Recurse -Force }
& jlink --add-modules ALL-MODULE-PATH --output $JreDest --strip-debug --no-header-files --no-man-pages
if ($LASTEXITCODE -ne 0) { throw "jlink falhou ao gerar o runtime para o LIUM." }

@"
transcritor-ata $Version - versao portavel para Windows 11 (64-bit)

Como usar:
  1. Descompacte esta pasta em qualquer lugar (Downloads, Desktop, um pendrive, etc.).
     Nao precisa de instalacao nem de privilegios de administrador.
  2. Execute transcritor-ata.exe.
  3. Na primeira vez, escolha e baixe um modelo de transcricao (Whisper) quando solicitado.
  4. Pronto para usar. Veja "Ajuda -> Verificar instalacao" dentro do programa se algo
     estiver faltando.

Este pacote ja inclui: Java (runtime proprio), ffmpeg, whisper-cli (CPU e GPU/CUDA,
selecionado automaticamente conforme sua placa de video) e a ferramenta opcional de
identificacao de participantes (LIUM_SpkDiarization). O unico download feito por voce e o
modelo de transcricao, na primeira execucao.
"@ | Out-File -FilePath (Join-Path $AppImageDir "LEIA-ME.txt") -Encoding utf8

Write-Host "== 6/6: Compactando pacote final ==" -ForegroundColor Cyan
$ZipPath = Join-Path $ProjectRoot $ZipName
if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }
Compress-Archive -Path $AppImageDir -DestinationPath $ZipPath -CompressionLevel Optimal

$SizeMb = [math]::Round((Get-Item $ZipPath).Length / 1MB, 1)
Write-Host ""
Write-Host "Pacote gerado: $ZipPath ($SizeMb MB)" -ForegroundColor Green
Write-Host "Pasta descompactada (para testes locais): $AppImageDir" -ForegroundColor Green
