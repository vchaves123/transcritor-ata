package com.tailor.transcritorata.gui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Shows exactly which Java runtime is actually executing the app right now -- the same
 * information a bug report or support request would need to tell whether it matches the JDK the
 * released installer bundles (see CONTRIBUTING.md), instead of assuming it does.
 */
final class JavaEnvironmentDialog {

    private JavaEnvironmentDialog() {
    }

    static void show(Shell parent) {
        Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        AppIcon.apply(dialog);
        dialog.setText("Java environment");
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 16;
        layout.marginHeight = 16;
        layout.verticalSpacing = 12;
        dialog.setLayout(layout);

        Label description = new Label(dialog, SWT.WRAP);
        description.setText("The Java runtime currently running this application:");
        description.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        String report = buildReport();

        Text reportText = new Text(dialog, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP | SWT.READ_ONLY);
        reportText.setText(report);
        GridData reportData = new GridData(SWT.FILL, SWT.FILL, true, true);
        reportData.widthHint = 480;
        reportData.heightHint = 280;
        reportText.setLayoutData(reportData);

        Composite buttons = new Composite(dialog, SWT.NONE);
        GridLayout buttonsLayout = new GridLayout(2, false);
        buttonsLayout.horizontalSpacing = 10;
        buttons.setLayout(buttonsLayout);
        buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));

        Button copy = new Button(buttons, SWT.PUSH);
        copy.setText("Copy to clipboard");
        copy.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                Clipboard clipboard = new Clipboard(dialog.getDisplay());
                clipboard.setContents(new Object[] { report }, new Transfer[] { TextTransfer.getInstance() });
                clipboard.dispose();
            }
        });

        Button close = new Button(buttons, SWT.PUSH);
        close.setText("Close");
        close.setLayoutData(new GridData(90, SWT.DEFAULT));
        close.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                dialog.close();
            }
        });

        dialog.setDefaultButton(close);
        dialog.pack();
        dialog.setMinimumSize(dialog.getSize());
        dialog.open();

        var display = parent.getDisplay();
        while (!dialog.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
    }

    private static String buildReport() {
        Runtime runtime = Runtime.getRuntime();
        long maxHeapMb = runtime.maxMemory() / (1024 * 1024);
        return """
                Java runtime
                ------------------------------------------------
                Vendor:            %s
                Version:           %s
                VM name:           %s
                VM version:        %s
                Java home:         %s

                Operating system
                ------------------------------------------------
                Name:              %s
                Version:           %s
                Architecture:      %s

                Runtime limits
                ------------------------------------------------
                Available CPUs:    %d
                Max heap:          %d MB
                """.formatted(
                property("java.vendor"),
                property("java.version"),
                property("java.vm.name"),
                property("java.vm.version"),
                property("java.home"),
                property("os.name"),
                property("os.version"),
                property("os.arch"),
                runtime.availableProcessors(),
                maxHeapMb);
    }

    private static String property(String key) {
        return System.getProperty(key, "(unknown)");
    }
}
