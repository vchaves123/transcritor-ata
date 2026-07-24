package com.tailor.transcritorata.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tailor.transcritorata.audio.AudioExtractor;
import com.tailor.transcritorata.audio.ExternalProcessException;
import com.tailor.transcritorata.audio.ProcessCancelledException;
import com.tailor.transcritorata.audio.ProcessRunner;
import com.tailor.transcritorata.audio.VideoProbe;
import com.tailor.transcritorata.config.AppConfig;
import com.tailor.transcritorata.diarization.SpeakerDiarizer;
import com.tailor.transcritorata.diarization.onnx.OnnxSpeakerDiarizer;
import com.tailor.transcritorata.deps.DependencyChecker;
import com.tailor.transcritorata.deps.DependencyStatus;
import com.tailor.transcritorata.deps.ExecutableLocator;
import com.tailor.transcritorata.deps.GpuDetector;
import com.tailor.transcritorata.deps.SleepDetector;
import com.tailor.transcritorata.deps.UpdateChecker;
import com.tailor.transcritorata.deps.WhisperModelOption;
import com.tailor.transcritorata.minutes.DocxMinutesGenerator;
import com.tailor.transcritorata.transcription.AdaptiveWhisperEngine;
import com.tailor.transcritorata.transcription.PipelineResult;
import com.tailor.transcritorata.transcription.TranscriptionEngine;
import com.tailor.transcritorata.transcription.TranscriptionPipeline;
import com.tailor.transcritorata.transcription.WhisperModelSelector;

/** The application's single main window. */
public final class MainWindow {

    private static final Logger LOG = LoggerFactory.getLogger(MainWindow.class);
    private static final long DEFAULT_TIMEOUT_SECONDS = 3600;
    private static final DateTimeFormatter LOG_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter CLOCK_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Display display;
    private final AppConfig config;
    private final Shell shell;

    private org.eclipse.swt.widgets.List fileListWidget;
    private Button addButton;
    private Button removeFileButton;
    private Button moveUpButton;
    private Button moveDownButton;
    private Label totalsLabel;
    private Label outputDirLabel;
    private Button outputDirButton;
    private MenuItem preferencesMenuItem;
    private Button transcribeButton;
    private Button cancelButton;
    private Label elapsedTimeLabel;
    private long transcriptionStartMillis;
    private volatile boolean elapsedTimerActive;

    private CollapsibleSection audioSection;
    private CollapsibleSection transcriptionSection;
    private CollapsibleSection diarizationSection;
    private CollapsibleSection minutesSection;

    private final List<VideoFileInfo> selectedVideos = new ArrayList<>();
    private volatile ProcessRunner.Handle currentHandle;
    // Which whisper-cli binary/model/decoding-mode AdaptiveWhisperEngine is currently attempting,
    // e.g. "Trying ggml-medium.bin on GPU (beam search)" — kept so it stays visible in the
    // transcription phase's status even once percentage-based progress updates start arriving.
    private String currentTranscriptionAttempt = "";

    public MainWindow(Display display, AppConfig config) {
        this.display = display;
        this.config = config;
        this.shell = new Shell(display);
        AppIcon.apply(shell);
        build();
        // pack() instead of a fixed size: with the 4 phases starting collapsed, a fixed size large
        // enough to fit them expanded left an empty leftover space at the bottom of the window.
        shell.pack();
        org.eclipse.swt.graphics.Point packedSize = shell.getSize();
        shell.setSize(Math.max(640, packedSize.x), packedSize.y);
        shell.setMinimumSize(640, packedSize.y);
    }

    public void open() {
        shell.open();
        refreshDependencyState();
        checkForUpdatesInBackground();
    }

    /**
     * Best-effort, non-blocking check against the GitHub Releases API. Runs after the window is
     * already open (not before, unlike {@link ModelSetupDialog#showIfNeeded}) so a slow/unavailable
     * network never delays startup -- if a newer version turns up a little later, the dialog just
     * pops up on top of the already-usable window.
     */
    private void checkForUpdatesInBackground() {
        Thread.ofVirtual().start(() -> UpdateChecker.checkForUpdate().ifPresent(update -> display.asyncExec(() -> {
            if (!shell.isDisposed()) {
                UpdateAvailableDialog.show(shell, update);
            }
        })));
    }

    public boolean isDisposed() {
        return shell.isDisposed();
    }

    private void build() {
        shell.setText("Transcritor-ata — Meeting Minutes Transcriber");
        shell.setLayout(new GridLayout(1, false));

        buildMenu();
        buildFileListSection();

        Composite actionsRow = new Composite(shell, SWT.NONE);
        GridLayout actionsLayout = new GridLayout(3, false);
        actionsLayout.marginWidth = 0;
        actionsLayout.marginHeight = 0;
        actionsRow.setLayout(actionsLayout);
        actionsRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        transcribeButton = new Button(actionsRow, SWT.PUSH);
        transcribeButton.setText("Transcribe");
        transcribeButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                startTranscription();
            }
        });

        cancelButton = new Button(actionsRow, SWT.PUSH);
        cancelButton.setText("Cancel");
        cancelButton.setEnabled(false);
        cancelButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                cancelTranscription();
            }
        });

        elapsedTimeLabel = new Label(actionsRow, SWT.NONE);
        elapsedTimeLabel.setText("Elapsed time: 00:00");
        elapsedTimeLabel.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));

        buildPhaseSections();
        refreshDiarizationSectionStatus();
    }

    private void buildFileListSection() {
        Label filesLabel = new Label(shell, SWT.NONE);
        filesLabel.setText("Video files (in the order they will be concatenated):");

        Composite filesRow = new Composite(shell, SWT.NONE);
        GridLayout filesRowLayout = new GridLayout(2, false);
        filesRowLayout.marginWidth = 0;
        filesRowLayout.marginHeight = 0;
        filesRow.setLayout(filesRowLayout);
        filesRow.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));

        fileListWidget = new org.eclipse.swt.widgets.List(filesRow, SWT.BORDER | SWT.V_SCROLL | SWT.SINGLE);
        GridData listData = new GridData(SWT.FILL, SWT.FILL, true, false);
        listData.heightHint = 120;
        fileListWidget.setLayoutData(listData);
        fileListWidget.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                refreshFileListButtons();
            }
        });

        Composite fileButtonsColumn = new Composite(filesRow, SWT.NONE);
        GridLayout fileButtonsLayout = new GridLayout(1, false);
        fileButtonsColumn.setLayout(fileButtonsLayout);
        fileButtonsColumn.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));

        addButton = new Button(fileButtonsColumn, SWT.PUSH);
        addButton.setText("Add...");
        addButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        addButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                addVideoFiles();
            }
        });

        removeFileButton = new Button(fileButtonsColumn, SWT.PUSH);
        removeFileButton.setText("Remove");
        removeFileButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        removeFileButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                removeSelectedVideoFile();
            }
        });

        moveUpButton = new Button(fileButtonsColumn, SWT.PUSH);
        moveUpButton.setText("▲");
        moveUpButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        moveUpButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                moveSelectedVideoFile(-1);
            }
        });

        moveDownButton = new Button(fileButtonsColumn, SWT.PUSH);
        moveDownButton.setText("▼");
        moveDownButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        moveDownButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                moveSelectedVideoFile(1);
            }
        });

        totalsLabel = new Label(shell, SWT.NONE);
        totalsLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite outputDirRow = new Composite(shell, SWT.NONE);
        GridLayout outputDirLayout = new GridLayout(2, false);
        outputDirLayout.marginWidth = 0;
        outputDirLayout.marginHeight = 0;
        outputDirRow.setLayout(outputDirLayout);
        outputDirRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        outputDirLabel = new Label(outputDirRow, SWT.NONE);
        outputDirLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        outputDirButton = new Button(outputDirRow, SWT.PUSH);
        outputDirButton.setText("Choose destination folder...");
        outputDirButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                chooseOutputDir();
            }
        });

        refreshFileListButtons();
        refreshTotalsLabel();
        refreshOutputDirLabel();
    }

    private void chooseOutputDir() {
        DirectoryDialog dialog = new DirectoryDialog(shell);
        dialog.setText("Choose minutes destination folder");
        String currentOutputDir = config.get(AppConfig.KEY_OUTPUT_DIR, "");
        dialog.setFilterPath(!currentOutputDir.isBlank() ? currentOutputDir : config.get(AppConfig.KEY_LAST_VIDEO_DIR, ""));
        String chosen = dialog.open();
        if (chosen != null) {
            config.set(AppConfig.KEY_OUTPUT_DIR, chosen);
            config.save();
            refreshOutputDirLabel();
        }
    }

    private void refreshOutputDirLabel() {
        String configured = config.get(AppConfig.KEY_OUTPUT_DIR, "");
        outputDirLabel.setText(configured.isBlank()
                ? "Minutes destination folder: same folder as the first video"
                : "Minutes destination folder: " + configured);
    }

    private void buildPhaseSections() {
        audioSection = new CollapsibleSection(shell, "Audio extraction", false, this::adjustShellHeightToContent);
        transcriptionSection = new CollapsibleSection(shell, "Transcription", false, this::adjustShellHeightToContent);
        diarizationSection = new CollapsibleSection(shell, "Speaker identification", false,
                this::adjustShellHeightToContent);
        minutesSection = new CollapsibleSection(shell, "Minutes generation", false, this::adjustShellHeightToContent);
    }

    /**
     * Grows/shrinks the window's height to fit its content whenever a phase section is
     * expanded or collapsed, keeping the current width and top-left position untouched.
     */
    private void adjustShellHeightToContent() {
        if (shell.isDisposed()) {
            return;
        }
        org.eclipse.swt.graphics.Point currentSize = shell.getSize();
        int preferredHeight = shell.computeSize(currentSize.x, SWT.DEFAULT).y;
        shell.setSize(currentSize.x, preferredHeight);
    }

    private void buildMenu() {
        Menu menuBar = new Menu(shell, SWT.BAR);
        shell.setMenuBar(menuBar);

        MenuItem settingsMenuHeader = new MenuItem(menuBar, SWT.CASCADE);
        settingsMenuHeader.setText("Settings");
        Menu settingsMenu = new Menu(shell, SWT.DROP_DOWN);
        settingsMenuHeader.setMenu(settingsMenu);

        preferencesMenuItem = new MenuItem(settingsMenu, SWT.PUSH);
        preferencesMenuItem.setText("Preferences...");
        preferencesMenuItem.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (PreferencesDialog.open(shell, config)) {
                    refreshDependencyState();
                    refreshDiarizationSectionStatus();
                }
            }
        });

        MenuItem helpMenuHeader = new MenuItem(menuBar, SWT.CASCADE);
        helpMenuHeader.setText("Help");
        Menu helpMenu = new Menu(shell, SWT.DROP_DOWN);
        helpMenuHeader.setMenu(helpMenu);

        MenuItem checkInstall = new MenuItem(helpMenu, SWT.PUSH);
        checkInstall.setText("Check installation");
        checkInstall.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                showDependencyDialog();
            }
        });

        MenuItem javaEnvironment = new MenuItem(helpMenu, SWT.PUSH);
        javaEnvironment.setText("Java environment...");
        javaEnvironment.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                JavaEnvironmentDialog.show(shell);
            }
        });

        new MenuItem(helpMenu, SWT.SEPARATOR);

        MenuItem about = new MenuItem(helpMenu, SWT.PUSH);
        about.setText("About transcritor-ata...");
        about.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                AboutDialog.show(shell);
            }
        });
    }

    private void addVideoFiles() {
        FileDialog fileDialog = new FileDialog(shell, SWT.OPEN | SWT.MULTI);
        fileDialog.setFilterExtensions(new String[] { "*.wmv;*.mp4;*.mkv;*.avi" });
        fileDialog.setFilterPath(config.get(AppConfig.KEY_LAST_VIDEO_DIR, ""));
        String chosen = fileDialog.open();
        if (chosen == null) {
            return;
        }
        Path directory = Path.of(fileDialog.getFilterPath());
        List<Path> newVideos = new ArrayList<>();
        for (String fileName : fileDialog.getFileNames()) {
            Path video = directory.resolve(fileName);
            if (selectedVideos.stream().noneMatch(v -> v.path().equals(video))) {
                long sizeBytes;
                try {
                    sizeBytes = Files.size(video);
                } catch (java.io.IOException e) {
                    sizeBytes = 0;
                }
                selectedVideos.add(new VideoFileInfo(video, sizeBytes, null));
                newVideos.add(video);
            }
        }
        config.set(AppConfig.KEY_LAST_VIDEO_DIR, directory.toString());
        config.save();
        refreshFileListWidget(selectedVideos.size() - 1);
        refreshDependencyState();
        probeDurationsAsync(newVideos);
    }

    /**
     * Reads each new file's duration via ffprobe on a background thread (metadata-only, but still
     * I/O, so it must not run on the UI thread) and updates the list once each result arrives.
     */
    private void probeDurationsAsync(List<Path> videos) {
        String ffprobeExecutable = VideoProbe.resolveFfprobeExecutable(resolveFfmpegExecutable());
        for (Path video : videos) {
            Thread.ofVirtual().start(() -> {
                Optional<Duration> duration = VideoProbe.probeDuration(ffprobeExecutable, video);
                display.asyncExec(() -> {
                    if (shell.isDisposed()) {
                        return;
                    }
                    int index = indexOfVideo(video);
                    if (index < 0) {
                        return;
                    }
                    selectedVideos.set(index, selectedVideos.get(index).withDuration(duration.orElse(Duration.ZERO)));
                    int selection = fileListWidget.getSelectionIndex();
                    refreshFileListWidget(selection);
                });
            });
        }
    }

    private int indexOfVideo(Path video) {
        for (int i = 0; i < selectedVideos.size(); i++) {
            if (selectedVideos.get(i).path().equals(video)) {
                return i;
            }
        }
        return -1;
    }

    private void removeSelectedVideoFile() {
        int index = fileListWidget.getSelectionIndex();
        if (index < 0) {
            return;
        }
        selectedVideos.remove(index);
        refreshFileListWidget(Math.min(index, selectedVideos.size() - 1));
        refreshDependencyState();
    }

    private void moveSelectedVideoFile(int offset) {
        int index = fileListWidget.getSelectionIndex();
        int newIndex = index + offset;
        if (index < 0 || newIndex < 0 || newIndex >= selectedVideos.size()) {
            return;
        }
        VideoFileInfo moved = selectedVideos.remove(index);
        selectedVideos.add(newIndex, moved);
        refreshFileListWidget(newIndex);
    }

    private void refreshFileListWidget(int selectIndex) {
        String[] items = new String[selectedVideos.size()];
        for (int i = 0; i < items.length; i++) {
            VideoFileInfo info = selectedVideos.get(i);
            String durationText = info.duration() == null ? "calculating..." : formatDuration(info.duration());
            items[i] = "%d - %s (%s, %s)".formatted(i + 1, info.path().getFileName(),
                    formatSize(info.sizeBytes()), durationText);
        }
        fileListWidget.setItems(items);
        if (selectIndex >= 0 && selectIndex < items.length) {
            fileListWidget.select(selectIndex);
        }
        refreshFileListButtons();
        refreshTotalsLabel();
    }

    private void refreshTotalsLabel() {
        if (totalsLabel == null) {
            return;
        }
        long totalBytes = selectedVideos.stream().mapToLong(VideoFileInfo::sizeBytes).sum();
        Duration totalDuration = selectedVideos.stream()
                .map(v -> v.duration() == null ? Duration.ZERO : v.duration())
                .reduce(Duration.ZERO, Duration::plus);
        boolean pending = selectedVideos.stream().anyMatch(v -> v.duration() == null);
        String durationText = formatDuration(totalDuration) + (pending ? " (calculating...)" : "");
        totalsLabel.setText("Total: %s, %s".formatted(formatSize(totalBytes), durationText));
    }

    private static String formatSize(long bytes) {
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.0f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private static String formatDuration(Duration duration) {
        long totalSeconds = duration.toSeconds();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0 ? "%d:%02d:%02d".formatted(hours, minutes, seconds) : "%02d:%02d".formatted(minutes, seconds);
    }

    private void refreshFileListButtons() {
        int index = fileListWidget.getSelectionIndex();
        removeFileButton.setEnabled(index >= 0);
        moveUpButton.setEnabled(index > 0);
        moveDownButton.setEnabled(index >= 0 && index < selectedVideos.size() - 1);
    }

    private void refreshDependencyState() {
        transcribeButton.setEnabled(false);
        Thread.ofVirtual().start(() -> {
            DependencyChecker checker = new DependencyChecker(config);
            List<DependencyStatus> statuses = checker.checkAll();
            boolean allOk = statuses.stream().allMatch(DependencyStatus::ok);
            display.asyncExec(() -> {
                if (!shell.isDisposed()) {
                    transcribeButton.setEnabled(allOk && !selectedVideos.isEmpty());
                    transcribeButton.setToolTipText(allOk ? null
                            : "Missing dependencies. See Help → Check installation.");
                }
            });
        });
    }

    private void refreshDiarizationSectionStatus() {
        if (diarizationSection != null) {
            diarizationSection.setStatus(config.getBoolean(AppConfig.KEY_DIARIZATION_ENABLED, false) ? "" : "Disabled");
        }
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
        if (selectedVideos.isEmpty()) {
            return;
        }

        boolean diarizationEnabled = config.getBoolean(AppConfig.KEY_DIARIZATION_ENABLED, false);
        List<Path> videos = selectedVideos.stream().map(VideoFileInfo::path).toList();

        setControlsEnabledWhileBusy(true);
        audioSection.clear();
        transcriptionSection.clear();
        diarizationSection.clear();
        minutesSection.clear();
        audioSection.setStatus("");
        transcriptionSection.setStatus("");
        minutesSection.setStatus("");
        diarizationSection.setStatus(diarizationEnabled ? "" : "Disabled");
        currentTranscriptionAttempt = "";
        startElapsedTimer();

        ProcessRunner.Handle handle = new ProcessRunner.Handle();
        currentHandle = handle;

        Thread.ofVirtual().start(() -> runPipeline(handle, videos, diarizationEnabled));
    }

    private void cancelTranscription() {
        ProcessRunner.Handle handle = currentHandle;
        if (handle != null) {
            handle.cancel();
        }
        display.asyncExec(() -> {
            if (shell.isDisposed()) {
                return;
            }
            audioSection.appendLog("Cancelling...");
            audioSection.setStatusIfInProgress("Cancelled");
            transcriptionSection.setStatusIfInProgress("Cancelled");
            diarizationSection.setStatusIfInProgress("Cancelled");
            minutesSection.setStatusIfInProgress("Cancelled");
        });
    }

    /**
     * While a transcription runs, every control except the collapsible sections' expand/collapse
     * toggle is locked down (only "Cancel" remains usable) so the user can't change files or
     * settings mid-run. When it ends (success, cancellation, or failure), controls go back to
     * their normal state — re-derived via {@link #refreshDependencyState()} rather than blindly
     * re-enabled, since their availability depends on external conditions (dependencies present).
     */
    private void setControlsEnabledWhileBusy(boolean busy) {
        fileListWidget.setEnabled(!busy);
        addButton.setEnabled(!busy);
        outputDirButton.setEnabled(!busy);
        preferencesMenuItem.setEnabled(!busy);
        cancelButton.setEnabled(busy);
        if (busy) {
            fileListWidget.deselectAll();
            transcribeButton.setEnabled(false);
            removeFileButton.setEnabled(false);
            moveUpButton.setEnabled(false);
            moveDownButton.setEnabled(false);
        } else {
            stopElapsedTimer();
            refreshFileListButtons();
            refreshDependencyState();
        }
    }

    /** Starts showing "Elapsed time: mm:ss" next to the action buttons, ticking every second. */
    private void startElapsedTimer() {
        transcriptionStartMillis = System.currentTimeMillis();
        elapsedTimerActive = true;
        setElapsedTimeLabelText("Elapsed time: 00:00");
        display.timerExec(1000, this::tickElapsedTimer);
    }

    private void tickElapsedTimer() {
        if (!elapsedTimerActive || shell.isDisposed()) {
            return;
        }
        Duration elapsed = Duration.ofMillis(System.currentTimeMillis() - transcriptionStartMillis);
        setElapsedTimeLabelText("Elapsed time: " + formatDuration(elapsed));
        display.timerExec(1000, this::tickElapsedTimer);
    }

    /**
     * Setting a Label's text alone doesn't always make GridLayout recompute the widget's bounds
     * within its (already grab-expanded) cell, since the cell's width was fixed at the last full
     * layout pass — forcing a targeted re-layout of just this widget guarantees it's visible as
     * soon as the text changes, without re-laying out the whole window.
     */
    private void setElapsedTimeLabelText(String text) {
        elapsedTimeLabel.setText(text);
        elapsedTimeLabel.getParent().layout(new org.eclipse.swt.widgets.Control[] { elapsedTimeLabel });
    }

    private void stopElapsedTimer() {
        elapsedTimerActive = false;
    }

    private void runPipeline(ProcessRunner.Handle handle, List<Path> videos, boolean diarizationEnabled) {
        Instant pipelineStart = Instant.now();
        try {
            TranscriptionPipeline pipeline = buildPipeline(diarizationEnabled);
            Path outputDir = resolveOutputDir(videos);
            // percent == -1 is the sentinel used by the engines/ffmpeg for "just a log line"
            // (raw process output, transcribed sentences as they are recognized, etc.), which
            // should not move the phase's progress indicator.
            PipelineResult result = pipeline.run(videos, outputDir,
                    (message, percent) -> display.asyncExec(() -> reportPhaseProgress(audioSection, message, percent)),
                    (message, percent) -> display
                            .asyncExec(() -> reportPhaseProgress(transcriptionSection, message, percent)),
                    (message, percent) -> display
                            .asyncExec(() -> reportPhaseProgress(diarizationSection, message, percent)),
                    (message, percent) -> display
                            .asyncExec(() -> reportPhaseProgress(minutesSection, message, percent)),
                    handle);

            display.asyncExec(() -> {
                if (shell.isDisposed()) {
                    return;
                }
                setControlsEnabledWhileBusy(false);
                SuccessDialog.show(shell, result.simpleMinutesPath());
            });
        } catch (ProcessCancelledException e) {
            LOG.info("Transcription cancelled by the user");
            display.asyncExec(() -> {
                if (shell.isDisposed()) {
                    return;
                }
                audioSection.appendLog("Transcription cancelled.");
                setControlsEnabledWhileBusy(false);
            });
        } catch (ExternalProcessException e) {
            LOG.error("External process failure during transcription", e);
            display.asyncExec(() -> onPipelineFailed(
                    "A problem occurred while running an external tool (ffmpeg or whisper-cli). "
                            + e.getMessage(),
                    e.getProcessOutput()));
        } catch (Exception e) {
            LOG.error("Unexpected failure during transcription", e);
            display.asyncExec(() -> onPipelineFailed(
                    "An unexpected problem occurred during transcription: " + e.getMessage(), ""));
        } finally {
            reportSleepIntervals(pipelineStart);
        }
    }

    /**
     * Checks Windows' own event log for any sleep/resume cycle that happened during this run
     * (see {@link SleepDetector}) and, if found, appends a note to every phase's log -- run
     * regardless of how the pipeline ended, since an unexplained timeout or failure is often
     * exactly when this matters most. Best-effort: any failure to query the event log is already
     * swallowed inside SleepDetector, so this never affects the pipeline's own outcome.
     */
    private void reportSleepIntervals(Instant pipelineStart) {
        List<SleepDetector.SleepInterval> intervals = SleepDetector.findSleepIntervalsSince(pipelineStart);
        if (intervals.isEmpty()) {
            return;
        }
        display.asyncExec(() -> {
            if (shell.isDisposed()) {
                return;
            }
            java.time.ZoneId zone = java.time.ZoneId.systemDefault();
            for (SleepDetector.SleepInterval interval : intervals) {
                String note = "[" + LocalTime.now().format(LOG_TIMESTAMP_FORMAT) + "] System was suspended from "
                        + interval.sleepTime().atZone(zone).format(CLOCK_TIME_FORMAT) + " to "
                        + interval.wakeTime().atZone(zone).format(CLOCK_TIME_FORMAT)
                        + " (" + formatDuration(interval.duration()) + ")";
                audioSection.appendLog(note);
                transcriptionSection.appendLog(note);
                diarizationSection.appendLog(note);
                minutesSection.appendLog(note);
            }
        });
    }

    private void reportPhaseProgress(CollapsibleSection section, String message, int percent) {
        if (shell.isDisposed()) {
            return;
        }
        // AdaptiveWhisperEngine announces every (binary, model, decoding mode) it's about to try
        // with a "Trying ..." message; keep the latest one so the status keeps showing which
        // whisper/model/mode is currently running even after percentage updates start arriving.
        if (section == transcriptionSection && message.startsWith("Trying ")) {
            currentTranscriptionAttempt = message.endsWith("...")
                    ? message.substring(0, message.length() - 3)
                    : message;
        }
        if (percent < 0) {
            section.appendLog(withTimestampIfTimed(section, message));
            if (section == transcriptionSection && !currentTranscriptionAttempt.isEmpty()) {
                section.setStatus(currentTranscriptionAttempt);
            } else {
                section.setStatusIfWaiting("In progress...");
            }
        } else {
            section.appendLog(withTimestampIfTimed(section, message + " (" + percent + "%)"));
            if (percent >= 100) {
                section.setStatus(message.startsWith("Could not") ? "Failed ⚠" : "Completed ✓");
                currentTranscriptionAttempt = "";
            } else if (section == transcriptionSection && !currentTranscriptionAttempt.isEmpty()) {
                section.setStatus(currentTranscriptionAttempt + " — " + percent + "%");
            } else {
                section.setStatus("In progress (" + percent + "%)");
            }
        }
    }

    /**
     * Prefixes {@code message} with a wall-clock timestamp for the transcription and speaker
     * identification sections only -- these are the two phases whose per-line log output can
     * each take anywhere from milliseconds to several minutes (a whisper.cpp segment, an ONNX
     * diarization step), so seeing exactly when each line arrived is what makes it possible to
     * tell how long the process actually spent on each one.
     */
    private String withTimestampIfTimed(CollapsibleSection section, String message) {
        if (section != transcriptionSection && section != diarizationSection) {
            return message;
        }
        return "[" + LocalTime.now().format(LOG_TIMESTAMP_FORMAT) + "] " + message;
    }

    private void onPipelineFailed(String friendlyMessage, String details) {
        if (shell.isDisposed()) {
            return;
        }
        setControlsEnabledWhileBusy(false);
        ErrorDialog.show(shell, friendlyMessage, details);
    }

    private TranscriptionPipeline buildPipeline(boolean diarizationEnabled) throws Exception {
        long timeout = config.getInt(AppConfig.KEY_PROCESS_TIMEOUT_SECONDS, (int) DEFAULT_TIMEOUT_SECONDS);
        AudioExtractor audioExtractor = new AudioExtractor(resolveFfmpegExecutable(), timeout);

        TranscriptionEngine engine = buildWhisperEngine(timeout);

        DocxMinutesGenerator generator = new DocxMinutesGenerator();

        SpeakerDiarizer diarizer = diarizationEnabled ? new OnnxSpeakerDiarizer() : null;

        return new TranscriptionPipeline(audioExtractor, engine, generator, diarizer, diarizationEnabled);
    }

    private String resolveFfmpegExecutable() {
        return config.get(AppConfig.KEY_FFMPEG_BINARY, defaultExecutable("ffmpeg.exe"));
    }

    /**
     * Resolves a bare executable name (used only when nothing is configured/bundled) to an
     * absolute path found via an explicit PATH scan, instead of handing the bare name to
     * {@link ProcessBuilder} — on Windows, a bare name is looked up in the current working
     * directory before PATH, so a same-named file planted there would otherwise be preferred
     * over the real, PATH-installed executable. Falls back to the bare name only if it can't be
     * found anywhere, which fails the same way a bare invocation would have anyway.
     */
    private static String defaultExecutable(String executableName) {
        return new ExecutableLocator.Default().findOnPathOrCandidates(executableName, List.of())
                .map(Path::toString)
                .orElse(executableName.replace(".exe", ""));
    }

    /**
     * The configured destination folder, if the user chose one and it still exists; otherwise
     * falls back to the first video's folder (the original, implicit behavior).
     */
    private Path resolveOutputDir(List<Path> videos) {
        String configured = config.get(AppConfig.KEY_OUTPUT_DIR, "");
        if (!configured.isBlank()) {
            Path dir = Path.of(configured);
            if (Files.isDirectory(dir)) {
                return dir;
            }
        }
        return videos.get(0).getParent();
    }

    /**
     * Builds the Whisper engine, transparently wrapped with a CPU fallback when the configured
     * binary is the bundled CUDA build: some GPUs (2 GB VRAM cards are common on budget/older
     * laptops) run out of memory partway through long recordings even though the model loaded
     * fine, and restarting on CPU is far friendlier than surfacing a raw CUDA error.
     */
    private TranscriptionEngine buildWhisperEngine(long timeout) {
        Path cudaBinaryPath = com.tailor.transcritorata.deps.AppHome.resolve("tools/whisper-cuda/Release/whisper-cli.exe");
        Path cpuBinaryPath = com.tailor.transcritorata.deps.AppHome.resolve("tools/whisper-cpu/Release/whisper-cli.exe");
        String cudaBinary = Files.isRegularFile(cudaBinaryPath) ? cudaBinaryPath.toString() : null;
        String cpuBinary = Files.isRegularFile(cpuBinaryPath) ? cpuBinaryPath.toString()
                : config.get(AppConfig.KEY_WHISPER_BINARY, defaultExecutable("whisper-cli.exe"));

        Path configuredModelPath = Path.of(config.get(AppConfig.KEY_WHISPER_MODEL, ""));
        List<AdaptiveWhisperEngine.ModelCandidate> candidates = discoverModelCandidates(configuredModelPath);
        Path cpuFallbackModel = WhisperModelSelector.selectCpuFallback(candidates, configuredModelPath);

        boolean preferFastModeFirst = config.getBoolean(AppConfig.KEY_WHISPER_FAST_MODE, false);
        GpuDetector gpuDetector = new GpuDetector(new ExecutableLocator.Default());

        return new AdaptiveWhisperEngine(cudaBinary, cpuBinary, candidates, cpuFallbackModel, "pt", timeout,
                preferFastModeFirst, gpuDetector);
    }

    /**
     * Locally available Whisper models, used by {@link AdaptiveWhisperEngine} for the GPU
     * cascade: every known model (Small/Medium/Large and their quantized variants) found next to
     * the configured model, plus the configured model itself (in case it's a custom file that
     * doesn't match one of those names) — deduplicated by path, ordered via
     * {@link WhisperModelSelector#orderForGpuCascade(List)}.
     */
    private static List<AdaptiveWhisperEngine.ModelCandidate> discoverModelCandidates(Path configuredModelPath) {
        Path modelsDir = configuredModelPath.getParent();
        if (modelsDir == null) {
            modelsDir = com.tailor.transcritorata.deps.AppHome.resolve("tools/models");
        }

        List<AdaptiveWhisperEngine.ModelCandidate> candidates = new ArrayList<>();
        for (WhisperModelOption option : WhisperModelOption.values()) {
            addCandidateIfFile(candidates, modelsDir.resolve(option.fileName()));
        }
        addCandidateIfFile(candidates, configuredModelPath);

        return WhisperModelSelector.orderForGpuCascade(candidates);
    }

    private static void addCandidateIfFile(List<AdaptiveWhisperEngine.ModelCandidate> candidates, Path path) {
        if (path == null || !Files.isRegularFile(path) || candidates.stream().anyMatch(c -> c.path().equals(path))) {
            return;
        }
        try {
            candidates.add(new AdaptiveWhisperEngine.ModelCandidate(path, Files.size(path)));
        } catch (java.io.IOException e) {
            // Skip unreadable file; it just won't be offered as a candidate.
        }
    }
}
