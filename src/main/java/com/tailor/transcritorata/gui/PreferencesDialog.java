package com.tailor.transcritorata.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.tailor.transcritorata.config.AppConfig;

/** Preferences dialog: engine paths and company name. */
final class PreferencesDialog {

    private PreferencesDialog() {
    }

    /** @return true if the user saved changes */
    static boolean open(Shell parent, AppConfig config) {
        Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        AppIcon.apply(dialog);
        dialog.setText("Preferences");
        dialog.setLayout(new GridLayout(3, false));

        Text ffmpegBinaryText = row(dialog, "ffmpeg.exe executable",
                config.get(AppConfig.KEY_FFMPEG_BINARY, "ffmpeg"), browseFile(dialog, "*.exe"));

        Text whisperBinaryText = row(dialog, "whisper-cli.exe executable",
                config.get(AppConfig.KEY_WHISPER_BINARY, ""), browseFile(dialog, "*.exe"));

        Text whisperModelText = row(dialog, "Whisper model (.bin)",
                config.get(AppConfig.KEY_WHISPER_MODEL, ""), browseFile(dialog, "*.bin"));

        new Label(dialog, SWT.NONE); // aligns the column with the other rows
        Button downloadModelButton = new Button(dialog, SWT.PUSH);
        downloadModelButton.setText("Download another model...");
        GridData downloadModelData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        downloadModelData.horizontalSpan = 2;
        downloadModelButton.setLayoutData(downloadModelData);
        downloadModelButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                ModelSetupDialog.show(dialog, config);
                whisperModelText.setText(config.get(AppConfig.KEY_WHISPER_MODEL, ""));
            }
        });

        new Label(dialog, SWT.NONE); // aligns the column with the other rows
        Button diarizationCheckbox = new Button(dialog, SWT.CHECK);
        diarizationCheckbox.setText("Identify participants in the transcription");
        diarizationCheckbox.setSelection(config.getBoolean(AppConfig.KEY_DIARIZATION_ENABLED, false));
        diarizationCheckbox.setToolTipText(
                "Identifies participants using local AI models (without sending audio over the internet). "
                        + "Accuracy may vary depending on the recording.");
        GridData diarizationData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        diarizationData.horizontalSpan = 2;
        diarizationCheckbox.setLayoutData(diarizationData);

        new Label(dialog, SWT.NONE); // aligns the column with the other rows
        Button fastModeCheckbox = new Button(dialog, SWT.CHECK);
        fastModeCheckbox.setText("Prioritize speed and GPU memory usage (less accurate)");
        fastModeCheckbox.setToolTipText(
                "Uses greedy decoding (beam-size 1) instead of whisper.cpp's default (beam-size 5) from "
                        + "the very first attempt. Faster and uses noticeably less GPU memory, at the cost of "
                        + "a slightly less accurate transcription. Note: the app already automatically switches "
                        + "to fast mode and/or a smaller model on its own if the GPU runs out of memory — check "
                        + "this only if you'd rather skip straight to fast mode instead of waiting for that.");
        fastModeCheckbox.setSelection(config.getBoolean(AppConfig.KEY_WHISPER_FAST_MODE, false));
        GridData fastModeData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        fastModeData.horizontalSpan = 2;
        fastModeCheckbox.setLayoutData(fastModeData);

        boolean[] saved = { false };

        Button save = new Button(dialog, SWT.PUSH);
        save.setText("Save");
        GridData saveData = new GridData(SWT.END, SWT.CENTER, true, false);
        saveData.horizontalSpan = 3;
        save.setLayoutData(saveData);
        save.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                config.set(AppConfig.KEY_FFMPEG_BINARY, ffmpegBinaryText.getText().trim());
                config.set(AppConfig.KEY_WHISPER_BINARY, whisperBinaryText.getText().trim());
                config.set(AppConfig.KEY_WHISPER_MODEL, whisperModelText.getText().trim());
                config.setBoolean(AppConfig.KEY_DIARIZATION_ENABLED, diarizationCheckbox.getSelection());
                config.setBoolean(AppConfig.KEY_WHISPER_FAST_MODE, fastModeCheckbox.getSelection());

                config.save();
                saved[0] = true;
                dialog.close();
            }
        });

        dialog.pack();
        dialog.setMinimumSize(520, dialog.getSize().y);
        dialog.open();

        var display = parent.getDisplay();
        while (!dialog.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
        return saved[0];
    }

    private static Text row(Shell dialog, String label, String initialValue, Function<String, String> browseAction) {
        Label labelWidget = new Label(dialog, SWT.NONE);
        labelWidget.setText(label);

        Text text = new Text(dialog, SWT.BORDER);
        text.setText(initialValue == null ? "" : initialValue);
        GridData textData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        textData.widthHint = 280;
        text.setLayoutData(textData);

        Button browse = new Button(dialog, SWT.PUSH);
        browse.setText("Browse...");
        browse.setEnabled(browseAction != null);
        if (browseAction != null) {
            browse.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    String chosen = browseAction.apply(text.getText());
                    if (chosen != null) {
                        text.setText(chosen);
                    }
                }
            });
        }
        return text;
    }

    /**
     * Opens the file picker starting from the current field value's folder (if it points to one
     * that exists), so re-browsing doesn't always dump the user back at the OS default folder.
     */
    private static Function<String, String> browseFile(Shell dialog, String pattern) {
        return currentValue -> {
            FileDialog fileDialog = new FileDialog(dialog, SWT.OPEN);
            fileDialog.setFilterExtensions(new String[] { pattern });
            String initialFolder = resolveExistingParentFolder(currentValue);
            if (initialFolder != null) {
                fileDialog.setFilterPath(initialFolder);
            }
            return fileDialog.open();
        };
    }

    private static String resolveExistingParentFolder(String currentValue) {
        if (currentValue == null || currentValue.isBlank()) {
            return null;
        }
        Path parent = Path.of(currentValue).toAbsolutePath().getParent();
        return parent != null && Files.isDirectory(parent) ? parent.toString() : null;
    }
}
