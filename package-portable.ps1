# Gera um pacote portavel (app-image) do transcritor-ata para Windows 11 64-bit.
#
# O resultado roda sem instalacao e sem privilegios de administrador: e so descompactar o
# .zip gerado em qualquer pasta (inclusive em Downloads ou no Desktop) e executar
# transcritor-ata.exe. Inclui um JRE proprio (gerado via jpackage) e as ferramentas externas
# de transcricao (ffmpeg, whisper-cli CPU e CUDA) -- exceto o modelo Whisper, que o proprio
# usuario escolhe e baixa na primeira execucao (ModelSetupDialog).
#
# A identificacao de participantes (diarizacao) roda inteiramente dentro da JVM do proprio app
# via ONNX Runtime (modelos pyannote embutidos no jar) -- sem processo externo, sem runtime
# adicional.
#
# Uso:
#   .\package-portable.ps1 [-Version 1.0.0]
#
# Requisitos: JDK 21 com jpackage no PATH, Maven, e a pasta tools/ ja populada
# (veja o README.md - secao de pre-requisitos - para como baixar ffmpeg/whisper-cli).

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
# --java-options -XX:TieredStopAtLevel=1: desativa o compilador JIT C2. Contornamos com isso um
# crash nativo da JVM (EXCEPTION_ACCESS_VIOLATION dentro do proprio jvm.dll, na thread
# "C2 CompilerThread", compilando metodos completamente alheios ao nosso codigo) observado em
# CPUs hibridas mais novas da Intel com o Temurin 21.0.11 -- um bug do JIT, nao da aplicacao.
# Perde-se um pouco de performance de pico (fica so com o compilador C1), troca aceitavel por
# estabilidade para usuarios finais nao tecnicos.
& jpackage `
    --type app-image `
    --input $StagingDir `
    --main-jar transcritor-ata.jar `
    --main-class com.tailor.transcritorata.gui.MainApp `
    --name $AppName `
    --app-version $Version `
    --vendor "Tailor" `
    --java-options "-XX:TieredStopAtLevel=1" `
    --dest $ReleaseDir
if ($LASTEXITCODE -ne 0) { throw "jpackage falhou." }

Write-Host "== 4/5: Copiando ferramentas externas (ffmpeg, whisper-cli) ==" -ForegroundColor Cyan
if (-not (Test-Path $ToolsSrc)) {
    throw "Pasta tools/ nao encontrada em $ToolsSrc. Baixe ffmpeg/whisper-cli antes de empacotar (veja o README)."
}
$ToolsDest = Join-Path $AppImageDir "tools"
Copy-Item $ToolsSrc $ToolsDest -Recurse
# O modelo Whisper NAO entra no pacote: o usuario escolhe e baixa na primeira execucao.
$ModelsDir = Join-Path $ToolsDest "models"
if (Test-Path $ModelsDir) { Remove-Item $ModelsDir -Recurse -Force }

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
selecionado automaticamente conforme sua placa de video) e identificacao de participantes
(modelos de IA locais, embutidos no proprio programa). O unico download feito por voce e o
modelo de transcricao, na primeira execucao.
"@ | Out-File -FilePath (Join-Path $AppImageDir "LEIA-ME.txt") -Encoding utf8

Write-Host "== 5/5: Compactando pacote final ==" -ForegroundColor Cyan
$ZipPath = Join-Path $ProjectRoot $ZipName
if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }
Compress-Archive -Path $AppImageDir -DestinationPath $ZipPath -CompressionLevel Optimal

$SizeMb = [math]::Round((Get-Item $ZipPath).Length / 1MB, 1)
Write-Host ""
Write-Host "Pacote gerado: $ZipPath ($SizeMb MB)" -ForegroundColor Green
Write-Host "Pasta descompactada (para testes locais): $AppImageDir" -ForegroundColor Green
