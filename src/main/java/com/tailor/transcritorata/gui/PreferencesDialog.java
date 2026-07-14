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
        dialog.setText("Preferências");
        dialog.setLayout(new GridLayout(3, false));

        Text companyNameText = row(dialog, "Nome da empresa (cabeçalho da ata)",
                config.get(AppConfig.KEY_COMPANY_NAME, ""), null);

        Text whisperBinaryText = row(dialog, "Executável whisper-cli.exe",
                config.get(AppConfig.KEY_WHISPER_BINARY, ""), browseFile(dialog, "*.exe"));

        Text whisperModelText = row(dialog, "Modelo Whisper (.bin)",
                config.get(AppConfig.KEY_WHISPER_MODEL, ""), browseFile(dialog, "*.bin"));

        Text voskModelDirText = row(dialog, "Pasta do modelo Vosk",
                config.get(AppConfig.KEY_VOSK_MODEL_DIR, ""), browseDirectory(dialog));

        Text diarizationJarText = row(dialog, "LIUM_SpkDiarization.jar (participantes, opcional)",
                config.get(AppConfig.KEY_DIARIZATION_JAR, ""), browseFile(dialog, "*.jar"));

        Text chunkMinutesText = row(dialog, "Dividir em blocos de N minutos (reuniões longas)",
                Integer.toString(config.getInt(AppConfig.KEY_CHUNK_MINUTES, 20)), null);

        Button chunkEnabledCheckbox = new Button(dialog, SWT.CHECK);
        chunkEnabledCheckbox.setText("Dividir gravações longas em blocos e transcrever em paralelo");
        chunkEnabledCheckbox.setSelection(config.getBoolean(AppConfig.KEY_CHUNK_ENABLED, false));
        GridData chunkCheckboxData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        chunkCheckboxData.horizontalSpan = 3;
        chunkEnabledCheckbox.setLayoutData(chunkCheckboxData);

        Label separator = new Label(dialog, SWT.SEPARATOR | SWT.HORIZONTAL);
        GridData separatorData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        separatorData.horizontalSpan = 3;
        separator.setLayoutData(separatorData);

        Text aiModelText = row(dialog, "Modelo Claude", config.get(AppConfig.KEY_AI_MODEL, "claude-sonnet-4-6"), null);

        Text aiApiKeyText = row(dialog, "Chave da API Anthropic (opcional)",
                config.get(AppConfig.KEY_AI_API_KEY, ""), null);
        aiApiKeyText.setEchoChar('*');

        Label apiKeyHint = new Label(dialog, SWT.WRAP);
        apiKeyHint.setText("A chave será salva no arquivo de configuração local do usuário. Prefira definir a "
                + "variável de ambiente ANTHROPIC_API_KEY quando possível. O uso da API é pago pela Anthropic "
                + "conforme o consumo — veja https://console.anthropic.com.");
        GridData hintData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        hintData.horizontalSpan = 3;
        hintData.widthHint = 480;
        apiKeyHint.setLayoutData(hintData);

        boolean[] saved = { false };

        Button save = new Button(dialog, SWT.PUSH);
        save.setText("Salvar");
        GridData saveData = new GridData(SWT.END, SWT.CENTER, true, false);
        saveData.horizontalSpan = 3;
        save.setLayoutData(saveData);
        save.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                config.set(AppConfig.KEY_COMPANY_NAME, companyNameText.getText().trim());
                config.set(AppConfig.KEY_WHISPER_BINARY, whisperBinaryText.getText().trim());
                config.set(AppConfig.KEY_WHISPER_MODEL, whisperModelText.getText().trim());
                config.set(AppConfig.KEY_VOSK_MODEL_DIR, voskModelDirText.getText().trim());
                config.set(AppConfig.KEY_DIARIZATION_JAR, diarizationJarText.getText().trim());
                config.setBoolean(AppConfig.KEY_CHUNK_ENABLED, chunkEnabledCheckbox.getSelection());
                config.set(AppConfig.KEY_AI_MODEL, aiModelText.getText().trim());

                try {
                    config.setInt(AppConfig.KEY_CHUNK_MINUTES, Integer.parseInt(chunkMinutesText.getText().trim()));
                } catch (NumberFormatException ignored) {
                    // mantém o valor anterior se o usuário digitou algo inválido
                }

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
        box.setText("Privacidade — recurso de IA");
        box.setMessage("""
                Ao habilitar a geração de ata estruturada com IA, o texto da transcrição da reunião \
                será enviado à API da Anthropic pela internet para ser processado pelo modelo Claude.

                Sem esse recurso, o transcritor-ata funciona 100%% offline.

                Deseja habilitar o envio da transcrição para a API da Anthropic?""");
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
        browse.setText("Localizar...");
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

    private static Supplier<String> browseDirectory(Shell dialog) {
        return () -> {
            org.eclipse.swt.widgets.DirectoryDialog directoryDialog = new org.eclipse.swt.widgets.DirectoryDialog(dialog);
            return directoryDialog.open();
        };
    }
}
