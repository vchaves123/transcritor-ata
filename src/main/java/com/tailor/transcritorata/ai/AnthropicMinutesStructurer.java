package com.tailor.transcritorata.ai;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link MinutesStructurer} backed by the official Anthropic Java SDK.
 *
 * <p>Transcripts longer than {@code chunkCharLimit} characters are summarized chunk-by-chunk
 * (map step) before a final request consolidates the summaries into the fixed {@link
 * StructuredMinutes} schema (reduce step). Never renders free-form model text directly into the
 * final document: everything goes through {@link #parseStructuredMinutes(String)}.
 */
public final class AnthropicMinutesStructurer implements MinutesStructurer {

    private static final Logger LOG = LoggerFactory.getLogger(AnthropicMinutesStructurer.class);
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{.*\\}", Pattern.DOTALL);

    private static final String SCHEMA_INSTRUCTIONS = """
            You are an assistant that structures meeting minutes from transcripts. Respond \
            EXCLUSIVELY with a valid JSON object, with no text before or after it, following \
            exactly this schema (keys in English, values in English):

            {
              "executiveSummary": string,
              "participants": [string],
              "agenda": [string],
              "decisions": [string],
              "actionItems": [{ "description": string, "owner": string or null, "dueDate": string or null }]
            }
            """;

    private final AnthropicClient client;
    private final String model;
    private final int chunkCharLimit;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnthropicMinutesStructurer(AnthropicClient client, String model, int chunkCharLimit) {
        this.client = client;
        this.model = model;
        this.chunkCharLimit = chunkCharLimit;
    }

    @Override
    public StructuredMinutes structure(String transcriptText) throws MinutesStructuringException {
        List<String> chunks = TextChunker.split(transcriptText, chunkCharLimit);
        if (chunks.size() <= 1) {
            return structureFromText(transcriptText);
        }

        LOG.info("Long transcript ({} characters): summarizing into {} chunks before structuring",
                transcriptText.length(), chunks.size());
        StringBuilder combinedSummaries = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            String summary = summarizeChunk(chunks.get(i), i + 1, chunks.size());
            combinedSummaries.append("Excerpt ").append(i + 1).append(": ").append(summary).append("\n\n");
        }
        return structureFromText(combinedSummaries.toString());
    }

    private String summarizeChunk(String chunk, int index, int total) throws MinutesStructuringException {
        String prompt = """
                Summarize, objectively and in English, the following excerpt (%d of %d) of a \
                meeting transcript. Highlight decisions made, action items agreed upon (with owner \
                and due date, if mentioned), and participants named. Respond only with the summary \
                as running text.

                Excerpt:
                %s
                """.formatted(index, total, chunk);
        return callModel(prompt);
    }

    private StructuredMinutes structureFromText(String text) throws MinutesStructuringException {
        String prompt = SCHEMA_INSTRUCTIONS + "\n\nMeeting transcript:\n" + text;
        String response = callModel(prompt);
        try {
            return parseStructuredMinutes(response);
        } catch (MinutesStructuringException firstFailure) {
            LOG.warn("Invalid JSON returned by Claude, retrying with a correction request");
            String retryPrompt = """
                    The previous response was not valid JSON according to the requested schema. \
                    Respond again, EXCLUSIVELY with the corrected JSON, with no additional text.

                    Previous response:
                    %s
                    """.formatted(response);
            String retryResponse = callModel(retryPrompt);
            return parseStructuredMinutes(retryResponse);
        }
    }

    private String callModel(String prompt) throws MinutesStructuringException {
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(4096L)
                    .addUserMessage(prompt)
                    .build();
            Message message = client.messages().create(params);
            StringBuilder text = new StringBuilder();
            for (var content : message.content()) {
                content.text().ifPresent(block -> text.append(block.text()));
            }
            return text.toString();
        } catch (AnthropicException e) {
            throw new MinutesStructuringException(
                    "Failed to communicate with the Anthropic API: " + e.getMessage(), e);
        }
    }

    private StructuredMinutes parseStructuredMinutes(String response) throws MinutesStructuringException {
        Matcher matcher = JSON_BLOCK.matcher(response == null ? "" : response);
        if (!matcher.find()) {
            throw new MinutesStructuringException("Claude's response does not contain recognizable JSON.");
        }
        try {
            return objectMapper.readValue(matcher.group(), StructuredMinutes.class);
        } catch (JsonProcessingException e) {
            throw new MinutesStructuringException("JSON returned by Claude is invalid: " + e.getMessage(), e);
        }
    }
}
