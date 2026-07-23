package com.tailor.transcritorata.deps;

/**
 * Whisper ggml models officially published by ggerganov, offered to the user on first run so
 * they don't have to hunt for a download link themselves.
 */
public enum WhisperModelOption {

    SMALL("ggml-small.bin", "Small", "~466 MB — faster, lower accuracy. Good for modest machines.",
            "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b", false, false),
    SMALL_Q5_1("ggml-small-q5_1.bin", "Small (compact)",
            "~181 MB — quantized version of Small: about 2.6x smaller and ~20% faster on CPU, with "
                    + "the same transcription output as Small on every recording tested so far. The "
                    + "lightest option that still gives full-size-Small-level accuracy.",
            "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb", false, false),
    MEDIUM("ggml-medium.bin", "Medium", "~1.5 GB — good balance for CPU use, but the heaviest of the "
            + "well-rounded options.",
            "6c14d5adee5f86394037b4e4e8b59f1673b6cee10e3cf0b11bbdbee79c156208", false, false),
    MEDIUM_Q5_0("ggml-medium-q5_0.bin", "Medium (recommended, compact)",
            "~514 MB — quantized version of Medium: about 2.9x smaller and ~18% faster on CPU, with "
                    + "the same transcription output as Medium on every recording tested so far. The "
                    + "best balance of accuracy and resource usage for most machines. Automatically "
                    + "preferred over Large Turbo (compact) whenever transcription falls back to CPU.",
            "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f", true, false),
    LARGE_V3("ggml-large-v3.bin", "Large", "~2.9 GB — best accuracy, but the heaviest option.",
            "64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2", false, false),
    LARGE_V3_TURBO_Q5_0("ggml-large-v3-turbo-q5_0.bin", "Large Turbo (compact)",
            "~547 MB — a pruned, much faster variant of Large (fewer decoder layers), quantized on "
                    + "top: about 5x smaller than Large. Note: in local CPU testing this was NOT "
                    + "faster than Medium (its encoder is still Large-sized, which dominates runtime "
                    + "on short/medium recordings) -- its speed advantage (and the best transcription "
                    + "quality seen so far) shows up on a GPU, where it's automatically preferred over "
                    + "other models. Falls back to Medium (compact) automatically when running on CPU.",
            "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", false, true);

    private static final String BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    private final String fileName;
    private final String label;
    private final String description;
    private final String sha256;
    private final boolean recommendedForCpu;
    private final boolean recommendedForGpu;

    WhisperModelOption(String fileName, String label, String description, String sha256,
            boolean recommendedForCpu, boolean recommendedForGpu) {
        this.fileName = fileName;
        this.label = label;
        this.description = description;
        this.sha256 = sha256;
        this.recommendedForCpu = recommendedForCpu;
        this.recommendedForGpu = recommendedForGpu;
    }

    /**
     * @return true if this model should be automatically preferred as the CPU fallback whenever
     * it's locally available — in benchmarking on real Portuguese meeting audio, this was faster
     * on CPU than the (larger, but architecturally GPU-oriented) {@link #LARGE_V3_TURBO_Q5_0}.
     */
    public boolean isRecommendedForCpu() {
        return recommendedForCpu;
    }

    /**
     * @return true if this model should be automatically preferred over other locally available
     * models when running on GPU, even if a larger model would also fit in VRAM — its "Turbo"
     * pruned decoder is optimized for parallel (GPU) inference, where in benchmarking it was both
     * faster and produced better transcriptions than the plain, larger {@link #LARGE_V3} model.
     */
    public boolean isRecommendedForGpu() {
        return recommendedForGpu;
    }

    /** @return the officially published SHA-256 digest, verified after download before trusting the file. */
    public String sha256() {
        return sha256;
    }

    public String fileName() {
        return fileName;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public String downloadUrl() {
        return BASE_URL + fileName;
    }
}
