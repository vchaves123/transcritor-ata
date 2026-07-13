package com.tailor.transcritorata.gui;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;

import com.tailor.transcritorata.deps.DependencyStatus;

/** Shows the result of {@link com.tailor.transcritorata.deps.DependencyChecker#checkAll()}. */
final class DependencyDialog {

    private DependencyDialog() {
    }

    static void show(Shell parent, List<DependencyStatus> statuses) {
        Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        dialog.setText("Verificação de instalação");
        dialog.setLayout(new GridLayout(1, false));

        for (DependencyStatus status : statuses) {
            addStatusSection(dialog, status);
        }

        Button close = new Button(dialog, SWT.PUSH);
        close.setText("Fechar");
        close.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        close.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                dialog.close();
            }
        });

        dialog.setDefaultButton(close);
        dialog.pack();
        dialog.setMinimumSize(480, dialog.getSize().y);
        centerOn(parent, dialog);
        dialog.open();
    }

    private static void addStatusSection(Shell dialog, DependencyStatus status) {
        Composite section = new Composite(dialog, SWT.NONE);
        section.setLayout(new GridLayout(1, false));
        section.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label title = new Label(section, SWT.NONE);
        String icon = status.ok() ? "✔" : "✖";
        title.setText(icon + " " + status.name() + " — " + status.detail());
        title.setFont(boldFont(title));

        if (!status.ok()) {
            Label instructions = new Label(section, SWT.WRAP);
            instructions.setText(status.instructions());
            GridData instructionsData = new GridData(SWT.FILL, SWT.CENTER, true, false);
            instructionsData.widthHint = 500;
            instructions.setLayoutData(instructionsData);

            if (status.helpUrl() != null) {
                Link link = new Link(section, SWT.NONE);
                link.setText("<a href=\"" + status.helpUrl() + "\">" + status.helpUrl() + "</a>");
                link.addSelectionListener(new SelectionAdapter() {
                    @Override
                    public void widgetSelected(SelectionEvent e) {
                        Program.launch(e.text);
                    }
                });
            }
        }
    }

    private static org.eclipse.swt.graphics.Font boldFont(Label label) {
        org.eclipse.swt.graphics.FontData[] data = label.getFont().getFontData();
        for (org.eclipse.swt.graphics.FontData fd : data) {
            fd.setStyle(SWT.BOLD);
        }
        org.eclipse.swt.graphics.Font font = new org.eclipse.swt.graphics.Font(label.getDisplay(), data);
        label.addDisposeListener(e -> font.dispose());
        return font;
    }

    private static void centerOn(Shell parent, Shell dialog) {
        if (parent == null) {
            return;
        }
        var parentBounds = parent.getBounds();
        var size = dialog.getSize();
        dialog.setLocation(
                parentBounds.x + (parentBounds.width - size.x) / 2,
                parentBounds.y + (parentBounds.height - size.y) / 2);
    }
}
