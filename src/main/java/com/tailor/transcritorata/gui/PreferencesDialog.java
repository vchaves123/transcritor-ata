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
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.tailor.transcritorata.config.AppConfig;

/** Preferences dialog: engine paths, company name, and the opt-in AI structuring settings. */
final class PreferencesDialog {

    private PreferencesDialog() {
    }

    /** @return true if the user saved changes (so the caller can refresh AI availability, etc.) */
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

        Label separator = new Label(dialog, SWT.SEPARATOR | SWT.HORIZONTAL);
        GridData separatorData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        separatorData.horizontalSpan = 3;
        separator.setLayoutData(separatorData);

        Text aiModelText = row(dialog, "Claude model", config.get(AppConfig.KEY_AI_MODEL, "claude-sonnet-4-6"), null);

        Text aiApiKeyText = row(dialog, "Anthropic API key (optional)",
                config.get(AppConfig.KEY_AI_API_KEY, ""), null);
        aiApiKeyText.setEchoChar('*');

        Label apiKeyHint = new Label(dialog, SWT.WRAP);
        apiKeyHint.setText("The key will be saved in the user's local configuration file. Prefer setting the "
                + "ANTHROPIC_API_KEY environment variable when possible. API usage is billed by Anthropic "
                + "based on consumption — see https://console.anthropic.com.");
        GridData hintData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        hintData.horizontalSpan = 3;
        hintData.widthHint = 480;
        apiKeyHint.setLayoutData(hintData);

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
                config.set(AppConfig.KEY_AI_MODEL, aiModelText.getText().trim());

                String newApiKey = aiApiKeyText.getText().trim();
                boolean hadKeyBefore = config.resolveAnthropicApiKey() != null;
                config.set(AppConfig.KEY_AI_API_KEY, newApiKey);
                boolean hasKeyNow = config.resolveAnthropicApiKey() != null;

                if (hasKeyNow && !hadKeyBefore && !config.getBoolean(AppConfig.KEY_AI_PRIVACY_CONSENT, false)) {
                    boolean consented = confirmPrivacy(dialog);
                    config.setBoolean(AppConfig.KEY_AI_PRIVACY_CONSENT, consented);
                }

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

    private static boolean confirmPrivacy(Shell parent) {
        MessageBox box = new MessageBox(parent, SWT.ICON_INFORMATION | SWT.YES | SWT.NO);
        box.setText("Privacy — AI feature");
        box.setMessage("""
                Enabling AI-structured minutes generation will send the meeting transcript's text \
                to the Anthropic API over the internet to be processed by the Claude model.

                Without this feature, transcritor-ata works 100%% offline.

                Do you want to enable sending the transcript to the Anthropic API?""");
        return box.open() == SWT.YES;
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
