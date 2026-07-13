package com.tailor.transcritorata.gui;

import java.nio.file.Path;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/** Completion dialog offering to open the generated minutes file and/or its containing folder. */
final class SuccessDialog {

    private SuccessDialog() {
    }

    static void show(Shell parent, Path simpleMinutes, Path structuredMinutes, String aiWarning) {
        Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dialog.setText("Transcrição concluída");
        dialog.setLayout(new GridLayout(1, false));

        Label message = new Label(dialog, SWT.WRAP);
        String text = "A ata foi gerada com sucesso:\n" + simpleMinutes.getFileName();
        if (structuredMinutes != null) {
            text += "\ne também a ata estruturada:\n" + structuredMinutes.getFileName();
        }
        message.setText(text);
        GridData messageData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        messageData.widthHint = 400;
        message.setLayoutData(messageData);

        if (aiWarning != null) {
            Label warning = new Label(dialog, SWT.WRAP);
            warning.setText("Aviso: " + aiWarning);
            GridData warningData = new GridData(SWT.FILL, SWT.CENTER, true, false);
            warningData.widthHint = 400;
            warning.setLayoutData(warningData);
        }

        var buttonsComposite = new org.eclipse.swt.widgets.Composite(dialog, SWT.NONE);
        buttonsComposite.setLayout(new GridLayout(3, false));
        buttonsComposite.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));

        Button openMinutes = new Button(buttonsComposite, SWT.PUSH);
        openMinutes.setText("Abrir ata");
        openMinutes.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                Program.launch(simpleMinutes.toAbsolutePath().toString());
            }
        });

        Button openFolder = new Button(buttonsComposite, SWT.PUSH);
        openFolder.setText("Abrir pasta");
        openFolder.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                Program.launch(simpleMinutes.toAbsolutePath().getParent().toString());
            }
        });

        Button close = new Button(buttonsComposite, SWT.PUSH);
        close.setText("Fechar");
        close.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                dialog.close();
            }
        });

        dialog.setDefaultButton(close);
        dialog.pack();
        dialog.open();

        var display = parent.getDisplay();
        while (!dialog.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
    }
}
