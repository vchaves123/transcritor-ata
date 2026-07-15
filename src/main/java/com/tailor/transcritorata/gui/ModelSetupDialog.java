package com.tailor.transcritorata.gui;

import java.nio.file.Path;
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

import com.tailor.transcritorata.config.AppConfig;
import com.tailor.transcritorata.deps.ExecutableLocator;
import com.tailor.transcritorata.deps.WhisperModelDownloader;
import com.tailor.transcritorata.deps.WhisperModelOption;
import com.tailor.transcritorata.deps.WhisperModelSetupChecker;

/**
 * Dialog offering to download a Whisper model, so the user doesn't have to hunt for a download
 * link and manually edit preferences. Used in two places:
 * <ul>
 *   <li>{@link #showIfNeeded} — on startup, only when {@link WhisperModelSetupChecker} reports
 *       no valid model is configured.</li>
 *   <li>{@link #show} — from Preferences, any time the user wants to switch to a different
 *       model (e.g. a smaller one, after hitting a GPU out-of-memory error with a larger one).</li>
 * </ul>
 */
final class ModelSetupDialog {

    private static final String MODELS_DIR = "tools/models";

    private ModelSetupDialog() {
    }

    /** Startup gate: only opens the dialog when no valid Whisper model is currently configured. */
    static void showIfNeeded(Display display, AppConfig config) {
        ExecutableLocator locator = new ExecutableLocator.Default();
        if (!WhisperModelSetupChecker.isNeeded(config, locator)) {
            return;
        }
        Shell dialog = new Shell(display, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        AppIcon.apply(dialog);
        open(dialog, display, config, "Initial setup — transcription model",
                "To transcribe meetings, transcritor-ata needs a Whisper model. "
                        + "Choose an option below to download it automatically (the file will be saved to "
                        + MODELS_DIR + "/ and your preferences will be adjusted automatically):",
                "Skip for now");
    }

    /** Always opens the dialog, letting the user download and switch to a different model. */
    static void show(Shell parent, AppConfig config) {
        Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        AppIcon.apply(dialog);
        open(dialog, parent.getDisplay(), config,
                "Download another transcription model",
                "Choose a model to download. Smaller models use less memory (RAM/VRAM) and are faster, "
                        + "but transcribe with less accuracy — a good option if you had memory problems "
                        + "with the current model. The file will be saved to " + MODELS_DIR
                        + "/ and your preferences will be adjusted automatically:",
                "Close");
    }

    private static void open(Shell dialog, Display display, AppConfig config, String title, String introText,
            String closeButtonLabel) {
        dialog.setText(title);
        dialog.setLayout(new GridLayout(1, false));

        Label intro = new Label(dialog, SWT.WRAP);
        intro.setText(introText);
        GridData introData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        introData.widthHint = 480;
        intro.setLayoutData(introData);

        WhisperModelOption[] options = WhisperModelOption.values();
        Button[] radios = new Button[options.length];
        for (int i = 0; i < options.length; i++) {
            Button radio = new Button(dialog, SWT.RADIO);
            radio.setText(options[i].label() + " — " + options[i].description());
            radio.setSelection(options[i] == WhisperModelOption.MEDIUM);
            radios[i] = radio;
        }

        Label statusLabel = new Label(dialog, SWT.WRAP);
        statusLabel.setText(" ");
        GridData statusData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        statusData.widthHint = 480;
        statusLabel.setLayoutData(statusData);

        ProgressBar progressBar = new ProgressBar(dialog, SWT.SMOOTH);
        progressBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        progressBar.setVisible(false);

        Composite buttons = new Composite(dialog, SWT.NONE);
        buttons.setLayout(new GridLayout(2, false));
        buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));

        Button downloadButton = new Button(buttons, SWT.PUSH);
        downloadButton.setText("Download");

        Button closeButton = new Button(buttons, SWT.PUSH);
        closeButton.setText(closeButtonLabel);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean downloading = new AtomicBoolean(false);

        closeButton.addSelectionListener(new SelectionAdapter() {
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
                WhisperModelOption chosen = selected(options, radios);
                downloading.set(true);
                downloadButton.setEnabled(false);
                for (Button radio : radios) {
                    radio.setEnabled(false);
                }
                closeButton.setText("Cancel");
                progressBar.setVisible(true);
                statusLabel.setText("Starting download of " + chosen.fileName() + "...");

                Thread.ofVirtual().start(() -> runDownload(display, dialog, config, chosen, progressBar,
                        statusLabel, downloadButton, closeButton, closeButtonLabel, radios, cancelled, downloading));
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

    private static void runDownload(Display display, Shell dialog, AppConfig config, WhisperModelOption chosen,
            ProgressBar progressBar, Label statusLabel, Button downloadButton, Button closeButton,
            String closeButtonLabel, Button[] radios, AtomicBoolean cancelled, AtomicBoolean downloading) {
        try {
            Path targetDir = Path.of(MODELS_DIR);
            new WhisperModelDownloader().download(chosen, targetDir,
                    (done, total) -> display.asyncExec(() -> {
                        if (dialog.isDisposed()) {
                            return;
                        }
                        if (total > 0) {
                            progressBar.setSelection((int) (done * 100 / total));
                            statusLabel.setText(formatBytes(done) + " / " + formatBytes(total));
                        } else {
                            statusLabel.setText(formatBytes(done) + " downloaded...");
                        }
                    }),
                    cancelled);

            display.asyncExec(() -> {
                if (dialog.isDisposed()) {
                    return;
                }
                config.set(AppConfig.KEY_WHISPER_MODEL, MODELS_DIR + "/" + chosen.fileName());
                config.save();
                dialog.close();
            });
        } catch (Exception ex) {
            display.asyncExec(() -> {
                if (dialog.isDisposed()) {
                    return;
                }
                statusLabel.setText("Download failed: " + ex.getMessage());
                downloadButton.setEnabled(true);
                for (Button radio : radios) {
                    radio.setEnabled(true);
                }
                closeButton.setText(closeButtonLabel);
                progressBar.setVisible(false);
            });
        } finally {
            downloading.set(false);
            cancelled.set(false);
        }
    }

    private static WhisperModelOption selected(WhisperModelOption[] options, Button[] radios) {
        for (int i = 0; i < radios.length; i++) {
            if (radios[i].getSelection()) {
                return options[i];
            }
        }
        return WhisperModelOption.MEDIUM;
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
        if (mb < 1024) {
            return "%.0f MB".formatted(mb);
        }
        double gb = mb / 1024.0;
        return "%.2f GB".formatted(gb);
    }
}
