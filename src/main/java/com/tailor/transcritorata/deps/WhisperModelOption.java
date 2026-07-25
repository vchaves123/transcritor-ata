package com.tailor.transcritorata.deps;

/**
 * Whisper ggml models officially published by ggerganov, offered to the user on first run so
 * they don't have to hunt for a download link themselves. Only the two models that came out
 * ahead in benchmarking are offered: one for GPU machines, one for CPU-only machines -- see
 * {@link #isRecommendedForCpu()}/{@link #isRecommendedForGpu()}.
 */
public enum WhisperModelOption {

    MEDIUM_Q5_0("ggml-medium-q5_0.bin", 539_212_467L, "Medium (recommended, compact)",
            "~514 MB — same accuracy as Medium, ~2.9x smaller and faster. Best all-around pick.",
            "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f", true, false),
    LARGE_V3_TURBO_Q5_0("ggml-large-v3-turbo-q5_0.bin", 574_041_195L, "Large Turbo (compact)",
            "~547 MB — 5x smaller than Large. Fastest and most accurate option on a GPU; slower "
                    + "than Medium (compact) on CPU-only machines.",
            "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", false, true);

    private static final String BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    private final String fileName;
    private final long downloadSizeBytes;
    private final String label;
    private final String description;
    private final String sha256;
    private final boolean recommendedForCpu;
    private final boolean recommendedForGpu;

    WhisperModelOption(String fileName, long downloadSizeBytes, String label, String description, String sha256,
            boolean recommendedForCpu, boolean recommendedForGpu) {
        this.fileName = fileName;
        this.downloadSizeBytes = downloadSizeBytes;
        this.label = label;
        this.description = description;
        this.sha256 = sha256;
        this.recommendedForCpu = recommendedForCpu;
        this.recommendedForGpu = recommendedForGpu;
    }

    /** @return the approximate size of the model file itself (pinned at the time this URL was added). */
    public long downloadSizeBytes() {
        return downloadSizeBytes;
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

    /** @return the single model to offer for download, based on whether an NVIDIA GPU is present. */
    public static WhisperModelOption recommendedFor(boolean hasNvidiaGpu) {
        return hasNvidiaGpu ? LARGE_V3_TURBO_Q5_0 : MEDIUM_Q5_0;
    }
}
