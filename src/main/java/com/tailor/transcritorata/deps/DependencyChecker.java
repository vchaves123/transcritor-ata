package com.tailor.transcritorata.deps;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.tailor.transcritorata.config.AppConfig;

/**
 * Checks that the external tools and models required by the transcription pipeline are present,
 * producing a list of {@link DependencyStatus} with concrete Portuguese installation instructions
 * for whatever is missing. Never throws to the caller.
 */
public final class DependencyChecker {

    private static final long PROBE_TIMEOUT_SECONDS = 10;

    private final ExecutableLocator locator;
    private final AppConfig config;

    public DependencyChecker(AppConfig config) {
        this(config, new ExecutableLocator.Default());
    }

    public DependencyChecker(AppConfig config, ExecutableLocator locator) {
        this.config = config;
        this.locator = locator;
    }

    /** Checks ffmpeg + the dependencies of the currently configured transcription engine. */
    public List<DependencyStatus> checkAll() {
        List<DependencyStatus> results = new ArrayList<>();
        results.add(checkFfmpeg());

        String engine = config.get(AppConfig.KEY_ENGINE, "whisper");
        if ("vosk".equalsIgnoreCase(engine)) {
            results.add(checkVoskModel());
        } else {
            results.add(checkWhisperBinary());
            results.add(checkWhisperModel());
        }
        results.add(checkDiarization());
        return results;
    }

    public DependencyStatus checkFfmpeg() {
        List<Path> candidates = List.of(Path.of("C:\\ffmpeg\\bin"));
        Optional<Path> found = locator.findOnPathOrCandidates("ffmpeg.exe", candidates);
        List<String> command = found.isPresent()
                ? List.of(found.get().toString(), "-version")
                : List.of("ffmpeg", "-version");
        boolean ok = locator.canRun(command, PROBE_TIMEOUT_SECONDS);

        if (ok) {
            return new DependencyStatus("ffmpeg", true,
                    found.map(Path::toString).orElse("encontrado no PATH"), null, null);
        }
        String instructions = """
                O ffmpeg não foi encontrado. No Windows 11, a forma mais simples de instalar é abrir o \
                Terminal/PowerShell e executar:

                    winget install Gyan.FFmpeg

                Alternativamente, baixe o build em https://www.gyan.dev/ffmpeg/builds/ e extraia em uma pasta \
                (por exemplo C:\\ffmpeg), adicionando a subpasta "bin" ao PATH do sistema.

                Importante: após instalar pelo winget, feche e reabra o transcritor-ata para que o PATH \
                atualizado seja reconhecido.
                """;
        return new DependencyStatus("ffmpeg", false, "não encontrado", instructions,
                "https://www.gyan.dev/ffmpeg/builds/");
    }

    public DependencyStatus checkWhisperBinary() {
        String configured = config.get(AppConfig.KEY_WHISPER_BINARY, "");
        List<Path> candidates = new ArrayList<>();
        if (!configured.isBlank()) {
            Path configuredPath = Path.of(configured);
            if (locator.exists(configuredPath)) {
                return new DependencyStatus("whisper-cli (whisper.cpp)", true, configured, null, null);
            }
            candidates.add(configuredPath.getParent());
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            candidates.add(Path.of(localAppData, "transcritor-ata", "whisper"));
        }
        Optional<Path> found = locator.findOnPathOrCandidates("whisper-cli.exe", candidates);
        if (found.isPresent()) {
            return new DependencyStatus("whisper-cli (whisper.cpp)", true, found.get().toString(), null, null);
        }

        String instructions = """
                O executável whisper-cli.exe não foi encontrado. Baixe o binário pré-compilado para Windows \
                nas releases oficiais do whisper.cpp:

                    https://github.com/ggml-org/whisper.cpp/releases

                Procure pelo arquivo zip com sufixo "-bin-x64", extraia o conteúdo em uma pasta de sua \
                preferência e, nas configurações do transcritor-ata, aponte o campo "whisper-cli" para o \
                arquivo whisper-cli.exe extraído.

                Não é necessário compilar nada.
                """;
        return new DependencyStatus("whisper-cli (whisper.cpp)", false, "não encontrado", instructions,
                "https://github.com/ggml-org/whisper.cpp/releases");
    }

    public DependencyStatus checkWhisperModel() {
        String configured = config.get(AppConfig.KEY_WHISPER_MODEL, "");
        if (!configured.isBlank()) {
            Path modelPath = Path.of(configured);
            long size = locator.sizeOf(modelPath);
            long minimumPlausibleBytes = 10L * 1024 * 1024; // any real ggml model is well above 10 MB
            if (locator.exists(modelPath) && size >= minimumPlausibleBytes) {
                return new DependencyStatus("Modelo Whisper (.bin)", true, configured, null, null);
            }
        }
        String instructions = """
                Nenhum modelo Whisper válido está configurado. Baixe um modelo em formato ggml em:

                    https://huggingface.co/ggerganov/whisper.cpp/tree/main

                Recomendações:
                  - ggml-medium.bin: bom equilíbrio para uso em CPU (recomendado)
                  - ggml-small.bin: para máquinas mais modestas
                  - ggml-large-v3.bin: para quem possui GPU disponível

                Salve o arquivo baixado e selecione-o no campo "Modelo Whisper" das configurações.
                """;
        return new DependencyStatus("Modelo Whisper (.bin)", false, "não configurado ou inválido", instructions,
                "https://huggingface.co/ggerganov/whisper.cpp/tree/main");
    }

    public DependencyStatus checkVoskModel() {
        String configured = config.get(AppConfig.KEY_VOSK_MODEL_DIR, "");
        if (!configured.isBlank()) {
            Path dir = Path.of(configured);
            if (java.nio.file.Files.isDirectory(dir)) {
                return new DependencyStatus("Modelo Vosk", true, configured, null, null);
            }
        }
        String instructions = """
                Nenhum modelo Vosk válido está configurado. Baixe um modelo em português em:

                    https://alphacephei.com/vosk/models

                Recomendação: vosk-model-small-pt-0.3

                Descompacte o arquivo baixado e selecione a pasta resultante no campo "Modelo Vosk" das \
                configurações.
                """;
        return new DependencyStatus("Modelo Vosk", false, "não configurado", instructions,
                "https://alphacephei.com/vosk/models");
    }

    public DependencyStatus checkDiarization() {
        String configured = config.get(AppConfig.KEY_DIARIZATION_JAR, "");
        if (!configured.isBlank()) {
            Path jarPath = Path.of(configured);
            long size = locator.sizeOf(jarPath);
            long minimumPlausibleBytes = 100L * 1024; // o jar do LIUM tem alguns MB
            if (locator.exists(jarPath) && size >= minimumPlausibleBytes) {
                return new DependencyStatus("Identificação de participantes (LIUM, opcional)", true,
                        configured, null, null, true);
            }
        }
        String instructions = """
                Recurso opcional e experimental. Para identificar os participantes na transcrição, baixe:

                    https://git-lium.univ-lemans.fr/Meignier/lium-spkdiarization/-/raw/master/jar/lium_spkdiarization-8.4.1.jar.gz

                O arquivo vem compactado em .gz (não é um zip comum) — descompacte-o (ex.: com 7-Zip) para \
                obter o lium_spkdiarization-8.4.1.jar, salve-o em uma pasta e selecione-o no campo \
                "LIUM_SpkDiarization" das configurações. Requer Java instalado (o mesmo usado para executar \
                este programa).

                Observação: a qualidade da identificação é limitada (tecnologia clássica, não neural) e \
                funciona melhor em áudios com poucos participantes e pouca sobreposição de falas.
                """;
        return new DependencyStatus("Identificação de participantes (LIUM, opcional)", false,
                "não configurado", instructions,
                "https://git-lium.univ-lemans.fr/Meignier/lium-spkdiarization/-/raw/master/jar/lium_spkdiarization-8.4.1.jar.gz",
                true);
    }
}
