package com.tailor.transcritorata.gui;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Fatal, blocking warning shown when {@code BundledToolIntegrityChecker} finds a downloaded tool
 * whose hash no longer matches what was verified right after download -- possible disk corruption
 * or local tampering of a file that runs with full code-execution capability. Deliberately offers
 * no "continue anyway": the caller must not proceed to {@link MainWindow} after this returns.
 */
final class IntegrityFailureDialog {

    private static final int DIALOG_WIDTH = 440;

    private IntegrityFailureDialog() {
    }

    static void show(Display display, List<Path> mismatchedFiles) {
        Shell dialog = new Shell(display, SWT.DIALOG_TRIM);
        AppIcon.apply(dialog);
        dialog.setText("Security warning");
        dialog.setLayout(new GridLayout(1, false));

        Label message = new Label(dialog, SWT.WRAP);
        message.setText("The following installed file(s) no longer match what was downloaded and "
                + "verified -- they may have been corrupted or modified since installation. For your "
                + "safety, transcritor-ata will not start.\n\n"
                + "Delete the affected file(s) below (or the whole \"tools\" folder) and restart the "
                + "app to re-download and re-verify them.");
        GridData messageData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        messageData.widthHint = DIALOG_WIDTH;
        message.setLayoutData(messageData);

        Text fileList = new Text(dialog, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.READ_ONLY);
        fileList.setText(mismatchedFiles.stream().map(Path::toString).collect(Collectors.joining("\n")));
        GridData listData = new GridData(SWT.FILL, SWT.FILL, true, false);
        listData.widthHint = DIALOG_WIDTH;
        listData.heightHint = 80;
        listData.verticalIndent = 10;
        fileList.setLayoutData(listData);

        Button quit = new Button(dialog, SWT.PUSH);
        quit.setText("Quit");
        GridData quitData = new GridData(SWT.END, SWT.CENTER, true, false);
        quitData.verticalIndent = 10;
        quit.setLayoutData(quitData);
        quit.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                dialog.close();
            }
        });
        dialog.setDefaultButton(quit);

        dialog.pack();
        dialog.open();

        while (!dialog.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
    }
}
