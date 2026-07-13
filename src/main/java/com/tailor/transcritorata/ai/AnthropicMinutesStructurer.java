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
            Você é um assistente que estrutura atas de reunião a partir de transcrições em \
            português. Responda EXCLUSIVAMENTE com um objeto JSON válido, sem nenhum texto antes \
            ou depois, seguindo exatamente este schema (chaves em inglês, valores em português):

            {
              "executiveSummary": string,
              "participants": [string],
              "agenda": [string],
              "decisions": [string],
              "actionItems": [{ "description": string, "owner": string ou null, "dueDate": string ou null }]
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

        LOG.info("Transcrição longa ({} caracteres): resumindo em {} blocos antes de estruturar",
                transcriptText.length(), chunks.size());
        StringBuilder combinedSummaries = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            String summary = summarizeChunk(chunks.get(i), i + 1, chunks.size());
            combinedSummaries.append("Trecho ").append(i + 1).append(": ").append(summary).append("\n\n");
        }
        return structureFromText(combinedSummaries.toString());
    }

    private String summarizeChunk(String chunk, int index, int total) throws MinutesStructuringException {
        String prompt = """
                Resuma em português, de forma objetiva, o seguinte trecho (%d de %d) da transcrição \
                de uma reunião. Destaque decisões tomadas, ações combinadas (com responsável e prazo, \
                se mencionados) e participantes citados. Responda apenas com o resumo em texto corrido.

                Trecho:
                %s
                """.formatted(index, total, chunk);
        return callModel(prompt);
    }

    private StructuredMinutes structureFromText(String text) throws MinutesStructuringException {
        String prompt = SCHEMA_INSTRUCTIONS + "\n\nTranscrição da reunião:\n" + text;
        String response = callModel(prompt);
        try {
            return parseStructuredMinutes(response);
        } catch (MinutesStructuringException firstFailure) {
            LOG.warn("JSON inválido retornado pelo Claude, tentando novamente com correção solicitada");
            String retryPrompt = """
                    A resposta anterior não era um JSON válido segundo o schema pedido. \
                    Responda novamente, EXCLUSIVAMENTE com o JSON corrigido, sem nenhum texto adicional.

                    Resposta anterior:
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
                    "Falha ao comunicar com a API da Anthropic: " + e.getMessage(), e);
        }
    }

    private StructuredMinutes parseStructuredMinutes(String response) throws MinutesStructuringException {
        Matcher matcher = JSON_BLOCK.matcher(response == null ? "" : response);
        if (!matcher.find()) {
            throw new MinutesStructuringException("A resposta do Claude não contém um JSON reconhecível.");
        }
        try {
            return objectMapper.readValue(matcher.group(), StructuredMinutes.class);
        } catch (JsonProcessingException e) {
            throw new MinutesStructuringException("JSON retornado pelo Claude é inválido: " + e.getMessage(), e);
        }
    }
}
