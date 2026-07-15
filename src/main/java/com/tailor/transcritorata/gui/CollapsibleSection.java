package com.tailor.transcritorata.gui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

/**
 * A collapsible "phase" panel: a header row (toggle arrow + title + one-line status) that can be
 * clicked to show/hide a log body below it. Used so the four pipeline phases (extraction,
 * transcription, diarization, minutes generation) can each be followed in detail without cluttering
 * the window when the user only cares about overall progress.
 */
final class CollapsibleSection {

    private final Composite container;
    private final Label arrowLabel;
    private final Label statusLabel;
    private final Text logText;
    private final GridData logLayoutData;
    private final Runnable onToggle;
    private boolean expanded;

    /**
     * @param onToggle called after expanding/collapsing (and after this section's own layout is
     *                 updated), so the caller can resize the containing shell to fit the new
     *                 content height; may be {@code null}.
     */
    CollapsibleSection(Composite parent, String title, boolean initiallyExpanded, Runnable onToggle) {
        this.expanded = initiallyExpanded;
        this.onToggle = onToggle;

        container = new Composite(parent, SWT.BORDER);
        container.setLayout(new GridLayout(1, false));
        container.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Composite header = new Composite(container, SWT.NONE);
        GridLayout headerLayout = new GridLayout(3, false);
        headerLayout.marginHeight = 4;
        header.setLayout(headerLayout);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        arrowLabel = new Label(header, SWT.NONE);
        arrowLabel.setText(arrowFor(expanded));

        Label titleLabel = new Label(header, SWT.NONE);
        titleLabel.setText(title);
        titleLabel.setFont(bold(titleLabel));

        statusLabel = new Label(header, SWT.NONE);
        GridData statusData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        statusLabel.setLayoutData(statusData);

        logText = new Text(container, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP | SWT.READ_ONLY);
        logLayoutData = new GridData(SWT.FILL, SWT.FILL, true, true);
        logLayoutData.heightHint = 140;
        logText.setLayoutData(logLayoutData);
        applyExpandedState();

        org.eclipse.swt.events.MouseAdapter toggleOnClick = new org.eclipse.swt.events.MouseAdapter() {
            @Override
            public void mouseUp(org.eclipse.swt.events.MouseEvent e) {
                setExpanded(!expanded);
            }
        };
        header.addMouseListener(toggleOnClick);
        arrowLabel.addMouseListener(toggleOnClick);
        titleLabel.addMouseListener(toggleOnClick);
    }

    void setStatus(String status) {
        if (!statusLabel.isDisposed()) {
            statusLabel.setText(status);
        }
    }

    /** Updates the status only if it still shows the initial blank placeholder. */
    void setStatusIfWaiting(String status) {
        if (!statusLabel.isDisposed() && statusLabel.getText().isEmpty()) {
            statusLabel.setText(status);
        }
    }

    /**
     * Updates the status to {@code status} only if this phase was actually running (i.e. not
     * blank/not-yet-started, and not already finished/disabled) — used so cancelling only marks
     * the phase(s) genuinely interrupted, leaving phases that never got to run untouched.
     */
    void setStatusIfInProgress(String status) {
        if (!statusLabel.isDisposed() && statusLabel.getText().startsWith("In progress")) {
            statusLabel.setText(status);
        }
    }

    void appendLog(String line) {
        if (!logText.isDisposed()) {
            logText.append(line + System.lineSeparator());
        }
    }

    void clear() {
        if (!logText.isDisposed()) {
            logText.setText("");
        }
    }

    void setExpanded(boolean expanded) {
        this.expanded = expanded;
        arrowLabel.setText(arrowFor(expanded));
        applyExpandedState();
        container.getParent().layout(true, true);
        if (onToggle != null) {
            onToggle.run();
        }
    }

    private void applyExpandedState() {
        logText.setVisible(expanded);
        logLayoutData.exclude = !expanded;
    }

    private static String arrowFor(boolean expanded) {
        return expanded ? "▼" : "▶"; // ▼ / ▶
    }

    private static org.eclipse.swt.graphics.Font bold(Label label) {
        org.eclipse.swt.graphics.FontData[] data = label.getFont().getFontData();
        for (org.eclipse.swt.graphics.FontData fd : data) {
            fd.setStyle(SWT.BOLD);
        }
        org.eclipse.swt.graphics.Font font = new org.eclipse.swt.graphics.Font(label.getDisplay(), data);
        label.addDisposeListener(e -> font.dispose());
        return font;
    }
}
