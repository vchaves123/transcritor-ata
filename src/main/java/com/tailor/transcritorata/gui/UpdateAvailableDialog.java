package com.tailor.transcritorata.gui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import com.tailor.transcritorata.deps.AppVersion;
import com.tailor.transcritorata.deps.UpdateChecker.UpdateInfo;

/** Tells the user a newer version is available, offering to open its download page or dismiss it. */
final class UpdateAvailableDialog {

    private UpdateAvailableDialog() {
    }

    static void show(Shell parent, UpdateInfo update) {
        Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        AppIcon.apply(dialog);
        dialog.setText("Update available");
        GridLayout dialogLayout = new GridLayout(1, false);
        dialogLayout.marginWidth = 16;
        dialogLayout.marginHeight = 16;
        dialogLayout.verticalSpacing = 12;
        dialog.setLayout(dialogLayout);

        Label message = new Label(dialog, SWT.WRAP);
        message.setText("A new version of transcritor-ata is available: " + update.version()
                + " (you have " + AppVersion.CURRENT + ").");
        GridData messageData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        messageData.widthHint = 380;
        message.setLayoutData(messageData);

        Composite buttons = new Composite(dialog, SWT.NONE);
        GridLayout buttonsLayout = new GridLayout(2, false);
        buttonsLayout.horizontalSpacing = 10;
        buttons.setLayout(buttonsLayout);
        buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));

        Button ignore = new Button(buttons, SWT.PUSH);
        ignore.setText("Ignore");
        ignore.setLayoutData(new GridData(90, SWT.DEFAULT));
        ignore.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                dialog.close();
            }
        });

        Button download = new Button(buttons, SWT.PUSH);
        download.setText("Download");
        download.setLayoutData(new GridData(90, SWT.DEFAULT));
        download.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                Program.launch(update.downloadUrl());
                dialog.close();
            }
        });

        dialog.setDefaultButton(download);
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
