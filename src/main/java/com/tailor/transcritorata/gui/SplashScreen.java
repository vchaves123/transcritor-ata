package com.tailor.transcritorata.gui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;

import com.tailor.transcritorata.deps.AppVersion;

/**
 * Shown immediately at startup, before the main window exists, so the bundled-tools integrity
 * check -- which can take a while to hash upwards of 1 GB of DLLs (see
 * {@code BundledToolIntegrityChecker}) -- doesn't leave the user staring at nothing, wondering if
 * the app is even starting.
 */
final class SplashScreen {

    private final Shell shell;
    private final ProgressBar progressBar;
    private final Label statusLabel;

    private SplashScreen(Shell shell, ProgressBar progressBar, Label statusLabel) {
        this.shell = shell;
        this.progressBar = progressBar;
        this.statusLabel = statusLabel;
    }

    static SplashScreen show(Display display) {
        Shell shell = new Shell(display, SWT.NO_TRIM | SWT.ON_TOP);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 24;
        layout.marginHeight = 20;
        layout.verticalSpacing = 10;
        shell.setLayout(layout);

        Label title = new Label(shell, SWT.NONE);
        title.setText("Transcritor-ata");
        title.setFont(bold(title, 14));

        Label subtitle = new Label(shell, SWT.NONE);
        subtitle.setText("Version " + AppVersion.CURRENT);

        Label statusLabel = new Label(shell, SWT.NONE);
        statusLabel.setText("Starting...");
        GridData statusData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        statusData.verticalIndent = 10;
        statusData.widthHint = 320;
        statusLabel.setLayoutData(statusData);

        ProgressBar progressBar = new ProgressBar(shell, SWT.HORIZONTAL | SWT.SMOOTH);
        progressBar.setMinimum(0);
        progressBar.setMaximum(1000);
        GridData progressData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        progressData.widthHint = 320;
        progressBar.setLayoutData(progressData);

        shell.pack();
        centerOnPrimaryMonitor(shell, display);
        shell.open();
        // Forces the shell to actually paint before any blocking work starts on the caller's
        // thread -- without this, the splash could stay an unpainted blank rectangle (or not
        // appear at all) until the first time the event loop is pumped.
        while (display.readAndDispatch()) {
            // drain pending paint/layout events
        }

        return new SplashScreen(shell, progressBar, statusLabel);
    }

    /** Must be called on the UI thread (e.g. via {@code Display.asyncExec}). */
    void setProgress(String status, long done, long total) {
        if (shell.isDisposed()) {
            return;
        }
        statusLabel.setText(status);
        int selection = total > 0 ? (int) Math.min(1000, (done * 1000) / total) : 1000;
        progressBar.setSelection(selection);
    }

    /** Must be called on the UI thread. */
    void close() {
        if (!shell.isDisposed()) {
            shell.close();
        }
    }

    private static void centerOnPrimaryMonitor(Shell shell, Display display) {
        Rectangle monitorBounds = display.getPrimaryMonitor().getBounds();
        org.eclipse.swt.graphics.Point size = shell.getSize();
        shell.setLocation(monitorBounds.x + (monitorBounds.width - size.x) / 2,
                monitorBounds.y + (monitorBounds.height - size.y) / 2);
    }

    private static Font bold(Label label, int points) {
        FontData[] data = label.getFont().getFontData();
        for (FontData fd : data) {
            fd.setStyle(SWT.BOLD);
            fd.setHeight(points);
        }
        Font font = new Font(label.getDisplay(), data);
        label.addDisposeListener(e -> font.dispose());
        return font;
    }
}
