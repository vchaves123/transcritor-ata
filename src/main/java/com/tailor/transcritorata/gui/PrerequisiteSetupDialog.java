package com.tailor.transcritorata.gui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tailor.transcritorata.config.AppConfig;
import com.tailor.transcritorata.deps.AppHome;
import com.tailor.transcritorata.deps.BundledFfmpegSelector;
import com.tailor.transcritorata.deps.DependencyChecker;
import com.tailor.transcritorata.deps.ExecutableLocator;
import com.tailor.transcritorata.deps.GpuDetector;
import com.tailor.transcritorata.deps.ToolPackageDownloader;
import com.tailor.transcritorata.deps.ToolPackageOption;
import com.tailor.transcritorata.deps.WhisperVariantSelector;

/**
 * Startup dialog offering to download ffmpeg and/or whisper-cli when neither is already present
 * (bundled under {@code tools/}, configured explicitly, or found on PATH -- see
 * {@link DependencyChecker}). Lets the installer stay small instead of always bundling ~1 GB of
 * binaries that a given machine might not even need in that exact form (e.g. the CUDA build on a
 * machine with no NVIDIA GPU).
 */
final class PrerequisiteSetupDialog {

    private static final Logger LOG = LoggerFactory.getLogger(PrerequisiteSetupDialog.class);
    private static final int DIALOG_WIDTH = 360;

    private PrerequisiteSetupDialog() {
    }

    /** Startup gate: only opens when ffmpeg and/or whisper-cli aren't already available. */
    static void showIfNeeded(Display display, AppConfig config) {
        ExecutableLocator locator = new ExecutableLocator.Default();
        DependencyChecker checker = new DependencyChecker(config, locator);

        List<ToolPackageOption> needed = new ArrayList<>();
        if (!checker.checkFfmpeg().ok()) {
            needed.add(ToolPackageOption.FFMPEG);
        }
        if (!checker.checkWhisperBinary().ok()) {
            boolean hasGpu = new GpuDetector(locator).hasNvidiaGpu();
            needed.add(hasGpu ? ToolPackageOption.WHISPER_CLI_CUDA : ToolPackageOption.WHISPER_CLI_CPU);
        }
        if (needed.isEmpty()) {
            return;
        }
        open(display, config, needed);
    }

    private static void open(Display display, AppConfig config, List<ToolPackageOption> needed) {
        Shell dialog = new Shell(display, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        AppIcon.apply(dialog);
        dialog.setText("Initial setup — required tools");
        dialog.setLayout(new GridLayout(1, false));

        Label intro = new Label(dialog, SWT.WRAP);
        intro.setText("transcritor-ata needs the following tools, which weren't found on this machine. "
                + "Choose which to download automatically:");
        GridData introData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        introData.widthHint = DIALOG_WIDTH;
        intro.setLayoutData(introData);

        Button[] checkboxes = new Button[needed.size()];
        for (int i = 0; i < needed.size(); i++) {
            Button checkbox = new Button(dialog, SWT.CHECK);
            checkbox.setText(needed.get(i).label());
            checkbox.setSelection(true);
            GridData checkboxData = new GridData(SWT.FILL, SWT.CENTER, true, false);
            checkboxData.verticalIndent = 6;
            checkbox.setLayoutData(checkboxData);
            checkboxes[i] = checkbox;
        }

        Label statusLabel = new Label(dialog, SWT.WRAP);
        statusLabel.setText(" ");
        GridData statusData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        statusData.widthHint = DIALOG_WIDTH;
        statusData.verticalIndent = 10;
        statusLabel.setLayoutData(statusData);

        ProgressBar progressBar = new ProgressBar(dialog, SWT.SMOOTH);
        progressBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        progressBar.setVisible(false);

        Composite buttons = new Composite(dialog, SWT.NONE);
        buttons.setLayout(new GridLayout(2, false));
        buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));

        Button downloadButton = new Button(buttons, SWT.PUSH);
        downloadButton.setText("Download");

        Button skipButton = new Button(buttons, SWT.PUSH);
        skipButton.setText("Skip for now");

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean downloading = new AtomicBoolean(false);

        skipButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (downloading.get()) {
                    cancelled.set(true);
                } else {
                    dialog.close();
                }
            }
        });

        downloadButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                List<ToolPackageOption> selected = new ArrayList<>();
                for (int i = 0; i < needed.size(); i++) {
                    if (checkboxes[i].getSelection()) {
                        selected.add(needed.get(i));
                    }
                }
                if (selected.isEmpty()) {
                    dialog.close();
                    return;
                }
                downloading.set(true);
                downloadButton.setEnabled(false);
                for (Button checkbox : checkboxes) {
                    checkbox.setEnabled(false);
                }
                skipButton.setText("Cancel");
                progressBar.setVisible(true);

                Thread.ofVirtual().start(() -> runDownloads(display, dialog, config, selected, progressBar,
                        statusLabel, downloadButton, skipButton, checkboxes, cancelled, downloading));
            }
        });

        dialog.pack();
        dialog.open();

        while (!dialog.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
    }

    private static void runDownloads(Display display, Shell dialog, AppConfig config, List<ToolPackageOption> selected,
            ProgressBar progressBar, Label statusLabel, Button downloadButton, Button skipButton, Button[] checkboxes,
            AtomicBoolean cancelled, AtomicBoolean downloading) {
        Path toolsDir = AppHome.resolve("tools");
        ToolPackageDownloader downloader = new ToolPackageDownloader();
        try {
            for (int i = 0; i < selected.size(); i++) {
                ToolPackageOption option = selected.get(i);
                int position = i + 1;
                display.asyncExec(() -> {
                    if (!dialog.isDisposed()) {
                        statusLabel.setText("Downloading " + option.label() + " (" + position + "/" + selected.size() + ")...");
                        progressBar.setSelection(0);
                    }
                });
                downloader.downloadAndInstall(option, toolsDir,
                        (done, total) -> display.asyncExec(() -> {
                            if (dialog.isDisposed()) {
                                return;
                            }
                            if (total > 0) {
                                progressBar.setSelection((int) (done * 100 / total));
                                statusLabel.setText("Downloading " + option.label() + " (" + position + "/"
                                        + selected.size() + ")... " + formatBytes(done) + " / " + formatBytes(total));
                            }
                        }),
                        cancelled);
            }

            display.asyncExec(() -> {
                if (dialog.isDisposed()) {
                    return;
                }
                // Re-applies the same selectors MainApp runs at startup, so the freshly
                // downloaded binaries get wired into config exactly as if they'd been bundled
                // with the installer all along.
                ExecutableLocator locator = new ExecutableLocator.Default();
                BundledFfmpegSelector.applyIfBundlePresent(config, locator);
                WhisperVariantSelector.applyBestVariant(config, new GpuDetector(locator), locator);
                dialog.close();
            });
        } catch (Exception ex) {
            LOG.error("Failed to download prerequisite tool(s)", ex);
            display.asyncExec(() -> {
                if (dialog.isDisposed()) {
                    return;
                }
                statusLabel.setText("Download failed: " + ex.getMessage());
                downloadButton.setEnabled(true);
                for (Button checkbox : checkboxes) {
                    checkbox.setEnabled(true);
                }
                skipButton.setText("Skip for now");
                progressBar.setVisible(false);
            });
        } finally {
            downloading.set(false);
            cancelled.set(false);
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return "%.0f KB".formatted(kb);
        }
        double mb = kb / 1024.0;
        return "%.0f MB".formatted(mb);
    }
}
