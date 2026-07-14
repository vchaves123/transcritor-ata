package com.tailor.transcritorata.diarization.onnx;

/** A plain time interval (in seconds) with no speaker identity attached yet. */
record TimeSpan(double startSeconds, double endSeconds) {
}
