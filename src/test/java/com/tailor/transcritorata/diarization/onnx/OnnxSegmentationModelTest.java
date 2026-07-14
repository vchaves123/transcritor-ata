package com.tailor.transcritorata.diarization.onnx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OnnxSegmentationModelTest {

    @Test
    void sampleToFrameMatchesReferenceFormula() {
        // (sample - 721) // 270, floor division, igual ao onnx_pyannote.py
        assertEquals(589, OnnxSegmentationModel.sampleToFrame(160_000));
        assertEquals(293, OnnxSegmentationModel.sampleToFrame(80_000));
    }
}
