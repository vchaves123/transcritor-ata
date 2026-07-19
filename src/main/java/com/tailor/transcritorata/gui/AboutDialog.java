package com.tailor.transcritorata.gui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * "About" dialog: application/version info and the licenses of every third-party component
 * shipped with the app (Java libraries in the jar, external tools bundled alongside it, and the
 * AI models embedded as resources).
 */
final class AboutDialog {

    private static final String APP_VERSION = "1.0.5";
    private static final String REPO_URL = "https://github.com/vchaves123/transcritor-ata";

    private AboutDialog() {
    }

    static void show(Shell parent) {
        Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        AppIcon.apply(dialog);
        dialog.setText("About transcritor-ata");
        dialog.setLayout(new GridLayout(1, false));

        Label title = new Label(dialog, SWT.NONE);
        title.setText("Transcritor-ata — Meeting Minutes Transcriber");
        title.setFont(bold(title));

        Label version = new Label(dialog, SWT.NONE);
        version.setText("Version " + APP_VERSION + " · © Tailor");

        Link repoLink = new Link(dialog, SWT.NONE);
        repoLink.setText("<a href=\"" + REPO_URL + "\">" + REPO_URL + "</a>");
        repoLink.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                Program.launch(e.text);
            }
        });

        Label licensesLabel = new Label(dialog, SWT.NONE);
        licensesLabel.setText("Third-party components and licenses:");
        GridData licensesLabelData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        licensesLabelData.verticalIndent = 12;
        licensesLabel.setLayoutData(licensesLabelData);

        Text licensesText = new Text(dialog, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP | SWT.READ_ONLY);
        licensesText.setText(THIRD_PARTY_NOTICES);
        GridData licensesData = new GridData(SWT.FILL, SWT.FILL, true, true);
        licensesData.widthHint = 560;
        licensesData.heightHint = 320;
        licensesText.setLayoutData(licensesData);

        Button close = new Button(dialog, SWT.PUSH);
        close.setText("Close");
        close.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        close.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                dialog.close();
            }
        });

        dialog.setDefaultButton(close);
        dialog.pack();
        dialog.setMinimumSize(600, 480);
        dialog.open();

        var display = parent.getDisplay();
        while (!dialog.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
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

    private static final String THIRD_PARTY_NOTICES = """
            Java libraries (bundled in the application jar)
            -------------------------------------------------
            Eclipse SWT (org.eclipse.platform) 3.126.0
              Eclipse Public License 2.0 (EPL-2.0)
              https://www.eclipse.org/legal/epl-2.0/

            Apache POI (org.apache.poi:poi-ooxml) 5.3.0
              Apache License 2.0
              https://www.apache.org/licenses/LICENSE-2.0

            Jackson Databind (com.fasterxml.jackson.core:jackson-databind) 2.17.2
              Apache License 2.0
              https://www.apache.org/licenses/LICENSE-2.0

            ONNX Runtime (com.microsoft.onnxruntime:onnxruntime) 1.27.0
              MIT License
              https://github.com/microsoft/onnxruntime/blob/main/LICENSE

            JTransforms (com.github.wendykierp:JTransforms) 3.2
              BSD 2-Clause License
              https://github.com/wendykierp/JTransforms

            SLF4J (org.slf4j:slf4j-api) 2.0.16
              MIT License
              https://www.slf4j.org/license.html

            Logback Classic (ch.qos.logback:logback-classic) 1.5.6
              Eclipse Public License 1.0 / GNU LGPL 2.1 (dual-licensed)
              https://logback.qos.ch/license.html

            External tools (bundled alongside the app, not inside the jar)
            -------------------------------------------------
            ffmpeg (static build, tools/ffmpeg)
              GNU Lesser General Public License 2.1 or later (LGPL build)
              https://ffmpeg.org/legal.html

            whisper.cpp / whisper-cli (tools/whisper-cpu, tools/whisper-cuda)
              MIT License
              https://github.com/ggml-org/whisper.cpp/blob/master/LICENSE

            AI models
            -------------------------------------------------
            pyannote/speaker-diarization-3.1 (segmentation + embedding, ONNX export,
            embedded as a resource for speaker identification)
              See the model card for license/usage terms:
              https://huggingface.co/pyannote/speaker-diarization-3.1

            Whisper transcription model (downloaded separately by the user, not bundled)
              MIT License (ggerganov/whisper.cpp model conversions)
              https://huggingface.co/ggerganov/whisper.cpp

            Silero VAD (voice activity detection, ggml export, embedded as a resource so
            whisper.cpp can skip long silences and avoid hallucinated text)
              MIT License
              https://huggingface.co/ggml-org/whisper-vad
            """;
}
