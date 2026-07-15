package com.tailor.transcritorata.gui;

import java.util.function.Supplier;

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

        Text companyNameText = row(dialog, "Company name (minutes header)",
                config.get(AppConfig.KEY_COMPANY_NAME, ""), null);

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

        boolean[] saved = { false };

        Button save = new Button(dialog, SWT.PUSH);
        save.setText("Save");
        GridData saveData = new GridData(SWT.END, SWT.CENTER, true, false);
        saveData.horizontalSpan = 3;
        save.setLayoutData(saveData);
        save.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                config.set(AppConfig.KEY_COMPANY_NAME, companyNameText.getText().trim());
                config.set(AppConfig.KEY_FFMPEG_BINARY, ffmpegBinaryText.getText().trim());
                config.set(AppConfig.KEY_WHISPER_BINARY, whisperBinaryText.getText().trim());
                config.set(AppConfig.KEY_WHISPER_MODEL, whisperModelText.getText().trim());

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

    private static Text row(Shell dialog, String label, String initialValue, Supplier<String> browseAction) {
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
                    String chosen = browseAction.get();
                    if (chosen != null) {
                        text.setText(chosen);
                    }
                }
            });
        }
        return text;
    }

    private static Supplier<String> browseFile(Shell dialog, String pattern) {
        return () -> {
            FileDialog fileDialog = new FileDialog(dialog, SWT.OPEN);
            fileDialog.setFilterExtensions(new String[] { pattern });
            return fileDialog.open();
        };
    }
}
