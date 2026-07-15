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

    static void show(Shell parent, Path simpleMinutes) {
        Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        AppIcon.apply(dialog);
        dialog.setText("Transcription complete");
        dialog.setLayout(new GridLayout(1, false));

        Label message = new Label(dialog, SWT.WRAP);
        message.setText("The minutes were generated successfully:\n" + simpleMinutes.getFileName());
        GridData messageData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        messageData.widthHint = 400;
        message.setLayoutData(messageData);

        var buttonsComposite = new org.eclipse.swt.widgets.Composite(dialog, SWT.NONE);
        buttonsComposite.setLayout(new GridLayout(3, false));
        buttonsComposite.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));

        Button openMinutes = new Button(buttonsComposite, SWT.PUSH);
        openMinutes.setText("Open minutes");
        openMinutes.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                Program.launch(simpleMinutes.toAbsolutePath().toString());
            }
        });

        Button openFolder = new Button(buttonsComposite, SWT.PUSH);
        openFolder.setText("Open folder");
        openFolder.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                Program.launch(simpleMinutes.toAbsolutePath().getParent().toString());
            }
        });

        Button close = new Button(buttonsComposite, SWT.PUSH);
        close.setText("Close");
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
