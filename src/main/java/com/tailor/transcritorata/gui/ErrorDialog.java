package com.tailor.transcritorata.gui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/** Friendly error dialog with an optional "view details" expander for raw process output. */
final class ErrorDialog {

    private ErrorDialog() {
    }

    static void show(Shell parent, String friendlyMessage, String details) {
        Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        AppIcon.apply(dialog);
        dialog.setText("A problem occurred");
        dialog.setLayout(new GridLayout(1, false));

        Label icon = new Label(dialog, SWT.WRAP);
        icon.setText(friendlyMessage);
        GridData messageData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        messageData.widthHint = 420;
        icon.setLayoutData(messageData);

        Text detailsText = new Text(dialog, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP);
        detailsText.setText(details == null || details.isBlank() ? "(no additional details)" : details);
        detailsText.setEditable(false);
        GridData detailsData = new GridData(SWT.FILL, SWT.FILL, true, false);
        detailsData.widthHint = 420;
        detailsData.heightHint = 150;
        detailsText.setLayoutData(detailsData);
        detailsText.setVisible(false);
        ((GridData) detailsText.getLayoutData()).exclude = true;

        var buttons = new org.eclipse.swt.widgets.Composite(dialog, SWT.NONE);
        buttons.setLayout(new GridLayout(2, false));
        buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));

        Button toggleDetails = new Button(buttons, SWT.PUSH);
        toggleDetails.setText("View details");
        toggleDetails.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                boolean showing = detailsText.isVisible();
                detailsText.setVisible(!showing);
                ((GridData) detailsText.getLayoutData()).exclude = showing;
                toggleDetails.setText(showing ? "View details" : "Hide details");
                dialog.layout(true, true);
                dialog.pack();
            }
        });

        Button close = new Button(buttons, SWT.PUSH);
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
