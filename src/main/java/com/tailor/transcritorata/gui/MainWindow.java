package com.tailor.transcritorata.gui;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.tailor.transcritorata.ai.AnthropicMinutesStructurer;
import com.tailor.transcritorata.ai.MinutesStructurer;
import com.tailor.transcritorata.audio.AudioExtractor;
import com.tailor.transcritorata.audio.ExternalProcessException;
import com.tailor.transcritorata.audio.ProcessRunner;
import com.tailor.transcritorata.config.AppConfig;
import com.tailor.transcritorata.deps.DependencyChecker;
import com.tailor.transcritorata.deps.DependencyStatus;
import com.tailor.transcritorata.minutes.DocxMinutesGenerator;
import com.tailor.transcritorata.transcription.PipelineResult;
import com.tailor.transcritorata.transcription.TranscriptionEngine;
import com.tailor.transcritorata.transcription.TranscriptionPipeline;
import com.tailor.transcritorata.transcription.VoskEngine;
import com.tailor.transcritorata.transcription.WhisperCppEngine;

/** The application's single main window. */
public final class MainWindow {

    private static final Logger LOG = LoggerFactory.getLogger(MainWindow.class);
    private static final String ENGINE_WHISPER = "Whisper (recomendado)";
    private static final String ENGINE_VOSK = "Vosk";
    private static final long DEFAULT_TIMEOUT_SECONDS = 3600;

    private final Display display;
    private final AppConfig config;
    private final Shell shell;

    private Label videoFileLabel;
    private Combo engineCombo;
    private Button aiCheckbox;
    private Button transcribeButton;
    private Button cancelButton;
    private ProgressBar progressBar;
    private Text logText;

    private Path selectedVideo;
    private volatile ProcessRunner.Handle currentHandle;

    public MainWindow(Display display, AppConfig config) {
        this.display = display;
        this.config = config;
        this.shell = new Shell(display);
        build();
    }

    public void open() {
        shell.open();
        refreshDependencyState();
        refreshAiAvailability();
    }

    public boolean isDisposed() {
        return shell.isDisposed();
    }

    private void build() {
        shell.setText("Transcritor de Ata de Reunião");
        shell.setLayout(new GridLayout(3, false));
        shell.setSize(640, 520);

        buildMenu();

        Button chooseVideoButton = new Button(shell, SWT.PUSH);
        chooseVideoButton.setText("Escolher vídeo...");
        chooseVideoButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                chooseVideo();
            }
        });

        videoFileLabel = new Label(shell, SWT.NONE);
        videoFileLabel.setText("Nenhum arquivo selecionado");
        GridData videoLabelData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        videoFileLabel.setLayoutData(videoLabelData);

        Label engineLabel = new Label(shell, SWT.NONE);
        engineLabel.setText("Motor de transcrição:");

        engineCombo = new Combo(shell, SWT.READ_ONLY);
        engineCombo.setItems(ENGINE_WHISPER, ENGINE_VOSK);
        engineCombo.select("vosk".equalsIgnoreCase(config.get(AppConfig.KEY_ENGINE, "whisper")) ? 1 : 0);
        engineCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                config.set(AppConfig.KEY_ENGINE, engineCombo.getSelectionIndex() == 1 ? "vosk" : "whisper");
                config.save();
                refreshDependencyState();
            }
        });
        engineCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button preferencesButton = new Button(shell, SWT.PUSH);
        preferencesButton.setText("Preferências...");
        preferencesButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (PreferencesDialog.open(shell, config)) {
                    refreshDependencyState();
                    refreshAiAvailability();
                }
            }
        });

        aiCheckbox = new Button(shell, SWT.CHECK);
        aiCheckbox.setText("Gerar ata estruturada com IA (Claude)");
        aiCheckbox.setSelection(config.getBoolean(AppConfig.KEY_AI_ENABLED, false));
        GridData aiCheckboxData = new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1);
        aiCheckbox.setLayoutData(aiCheckboxData);
        aiCheckbox.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                config.setBoolean(AppConfig.KEY_AI_ENABLED, aiCheckbox.getSelection());
                config.save();
            }
        });

        transcribeButton = new Button(shell, SWT.PUSH);
        transcribeButton.setText("Transcrever");
        transcribeButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                startTranscription();
            }
        });

        cancelButton = new Button(shell, SWT.PUSH);
        cancelButton.setText("Cancelar");
        cancelButton.setEnabled(false);
        cancelButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                cancelTranscription();
            }
        });

        progressBar = new ProgressBar(shell, SWT.SMOOTH);
        progressBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        logText = new Text(shell, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP | SWT.READ_ONLY);
        GridData logData = new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1);
        logData.heightHint = 220;
        logText.setLayoutData(logData);
    }

    private void buildMenu() {
        Menu menuBar = new Menu(shell, SWT.BAR);
        shell.setMenuBar(menuBar);

        MenuItem helpMenuHeader = new MenuItem(menuBar, SWT.CASCADE);
        helpMenuHeader.setText("Ajuda");
        Menu helpMenu = new Menu(shell, SWT.DROP_DOWN);
        helpMenuHeader.setMenu(helpMenu);

        MenuItem checkInstall = new MenuItem(helpMenu, SWT.PUSH);
        checkInstall.setText("Verificar instalação");
        checkInstall.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                showDependencyDialog();
            }
        });
    }

    private void chooseVideo() {
        FileDialog fileDialog = new FileDialog(shell, SWT.OPEN);
        fileDialog.setFilterExtensions(new String[] { "*.wmv;*.mp4;*.mkv;*.avi" });
        fileDialog.setFilterPath(config.get(AppConfig.KEY_LAST_VIDEO_DIR, ""));
        String chosen = fileDialog.open();
        if (chosen != null) {
            selectedVideo = Path.of(chosen);
            videoFileLabel.setText(selectedVideo.getFileName().toString());
            config.set(AppConfig.KEY_LAST_VIDEO_DIR, selectedVideo.getParent().toString());
            config.save();
        }
    }

    private void refreshDependencyState() {
        transcribeButton.setEnabled(false);
        Thread.ofVirtual().start(() -> {
            DependencyChecker checker = new DependencyChecker(config);
            List<DependencyStatus> statuses = checker.checkAll();
            boolean allOk = statuses.stream().allMatch(DependencyStatus::ok);
            display.asyncExec(() -> {
                if (!shell.isDisposed()) {
                    transcribeButton.setEnabled(allOk && selectedVideo != null);
                    transcribeButton.setToolTipText(allOk ? null
                            : "Dependências ausentes. Veja Ajuda → Verificar instalação.");
                }
            });
        });
    }

    private void refreshAiAvailability() {
        Thread.ofVirtual().start(() -> {
            boolean apiKeyPresent = config.resolveAnthropicApiKey() != null;
            boolean consented = config.getBoolean(AppConfig.KEY_AI_PRIVACY_CONSENT, false);
            display.asyncExec(() -> {
                if (shell.isDisposed()) {
                    return;
                }
                boolean available = apiKeyPresent && consented;
                aiCheckbox.setEnabled(available);
                if (!available) {
                    aiCheckbox.setSelection(false);
                    aiCheckbox.setToolTipText(!apiKeyPresent
                            ? "Configure a chave da API da Anthropic nas Preferências para habilitar este recurso."
                            : "É necessário confirmar o consentimento de privacidade nas Preferências.");
                } else {
                    aiCheckbox.setToolTipText(null);
                }
            });
        });
    }

    private void showDependencyDialog() {
        Thread.ofVirtual().start(() -> {
            DependencyChecker checker = new DependencyChecker(config);
            List<DependencyStatus> statuses = checker.checkAll();
            display.asyncExec(() -> {
                if (!shell.isDisposed()) {
                    DependencyDialog.show(shell, statuses);
                }
            });
        });
    }

    private void startTranscription() {
        if (selectedVideo == null) {
            return;
        }
        transcribeButton.setEnabled(false);
        cancelButton.setEnabled(true);
        logText.setText("");
        progressBar.setSelection(0);

        ProcessRunner.Handle handle = new ProcessRunner.Handle();
        currentHandle = handle;

        // Lê o estado dos widgets aqui, na UI thread — a thread de fundo não pode tocar em
        // widgets SWT (dispararia SWTException: Invalid thread access).
        boolean useVosk = engineCombo.getSelectionIndex() == 1;
        boolean aiEnabled = aiCheckbox.getEnabled() && aiCheckbox.getSelection();

        Thread.ofVirtual().start(() -> runPipeline(handle, useVosk, aiEnabled));
    }

    private void cancelTranscription() {
        ProcessRunner.Handle handle = currentHandle;
        if (handle != null) {
            handle.cancel();
        }
        appendLog("Cancelando...");
    }

    private void runPipeline(ProcessRunner.Handle handle, boolean useVosk, boolean aiEnabled) {
        try {
            TranscriptionPipeline pipeline = buildPipeline(useVosk, aiEnabled);
            Path outputDir = selectedVideo.getParent();
            PipelineResult result = pipeline.run(selectedVideo, outputDir,
                    (message, percent) -> display.asyncExec(() -> {
                        if (!shell.isDisposed()) {
                            appendLog(message + " (" + percent + "%)");
                            progressBar.setSelection(percent);
                        }
                    }),
                    handle);

            display.asyncExec(() -> {
                if (shell.isDisposed()) {
                    return;
                }
                transcribeButton.setEnabled(true);
                cancelButton.setEnabled(false);
                SuccessDialog.show(shell, result.simpleMinutesPath(), result.structuredMinutesPath(),
                        result.aiWarning());
            });
        } catch (ExternalProcessException e) {
            LOG.error("Falha em processo externo durante a transcrição", e);
            display.asyncExec(() -> onPipelineFailed(
                    "Ocorreu um problema ao executar uma ferramenta externa (ffmpeg ou whisper-cli). "
                            + e.getMessage(),
                    e.getProcessOutput()));
        } catch (Exception e) {
            LOG.error("Falha inesperada durante a transcrição", e);
            display.asyncExec(() -> onPipelineFailed(
                    "Ocorreu um problema inesperado durante a transcrição: " + e.getMessage(), ""));
        }
    }

    private void onPipelineFailed(String friendlyMessage, String details) {
        if (shell.isDisposed()) {
            return;
        }
        transcribeButton.setEnabled(true);
        cancelButton.setEnabled(false);
        ErrorDialog.show(shell, friendlyMessage, details);
    }

    private TranscriptionPipeline buildPipeline(boolean useVosk, boolean aiEnabled) throws Exception {
        long timeout = config.getInt(AppConfig.KEY_PROCESS_TIMEOUT_SECONDS, (int) DEFAULT_TIMEOUT_SECONDS);
        AudioExtractor audioExtractor = new AudioExtractor(resolveFfmpegExecutable(), timeout);

        TranscriptionEngine engine = useVosk
                ? new VoskEngine(Path.of(config.get(AppConfig.KEY_VOSK_MODEL_DIR, "")))
                : new WhisperCppEngine(config.get(AppConfig.KEY_WHISPER_BINARY, "whisper-cli"),
                        Path.of(config.get(AppConfig.KEY_WHISPER_MODEL, "")), "pt", timeout);

        DocxMinutesGenerator generator = new DocxMinutesGenerator(config.get(AppConfig.KEY_COMPANY_NAME, ""));

        MinutesStructurer structurer = null;
        if (aiEnabled) {
            AnthropicClient client = AnthropicOkHttpClient.builder()
                    .apiKey(config.resolveAnthropicApiKey())
                    .build();
            structurer = new AnthropicMinutesStructurer(client,
                    config.get(AppConfig.KEY_AI_MODEL, "claude-sonnet-4-6"),
                    config.getInt(AppConfig.KEY_AI_CHUNK_CHAR_LIMIT, 12000));
        }

        boolean chunkingEnabled = config.getBoolean(AppConfig.KEY_CHUNK_ENABLED, false);
        int chunkMinutes = config.getInt(AppConfig.KEY_CHUNK_MINUTES, 20);

        return new TranscriptionPipeline(audioExtractor, engine, generator, structurer,
                chunkingEnabled, chunkMinutes, aiEnabled);
    }

    private String resolveFfmpegExecutable() {
        return "ffmpeg";
    }

    private void appendLog(String message) {
        logText.append(message + System.lineSeparator());
    }
}
