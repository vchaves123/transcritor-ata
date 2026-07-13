package com.tailor.transcritorata.ai;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.services.blocking.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnthropicMinutesStructurerTest {

    @Test
    void parsesValidJsonResponseIntoStructuredMinutes() throws Exception {
        String json = """
                {
                  "executiveSummary": "Reunião sobre o cronograma do projeto.",
                  "participants": ["Maria", "João"],
                  "agenda": ["Revisão de escopo"],
                  "decisions": ["Adiar entrega para agosto"],
                  "actionItems": [{"description": "Atualizar cronograma", "owner": "Maria", "dueDate": "2026-07-20"}]
                }
                """;

        AnthropicClient client = clientReturning(json);
        AnthropicMinutesStructurer structurer = new AnthropicMinutesStructurer(client, "claude-sonnet-4-6", 12000);

        StructuredMinutes result = structurer.structure("transcrição curta de teste");

        assertEquals("Reunião sobre o cronograma do projeto.", result.executiveSummary());
        assertEquals(List.of("Maria", "João"), result.participants());
        assertEquals(1, result.actionItems().size());
        assertEquals("Maria", result.actionItems().get(0).owner());
    }

    @Test
    void retriesOnceWhenFirstResponseIsNotValidJsonThenSucceeds() throws Exception {
        String invalid = "desculpe, não posso responder em JSON agora.";
        String valid = """
                {"executiveSummary": "ok", "participants": [], "agenda": [], "decisions": [], "actionItems": []}
                """;

        AnthropicClient client = mock(AnthropicClient.class);
        MessageService messageService = mock(MessageService.class);
        when(client.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class)))
                .thenReturn(fakeMessage(invalid))
                .thenReturn(fakeMessage(valid));

        AnthropicMinutesStructurer structurer = new AnthropicMinutesStructurer(client, "claude-sonnet-4-6", 12000);
        StructuredMinutes result = structurer.structure("transcrição");

        assertEquals("ok", result.executiveSummary());
    }

    @Test
    void throwsWhenResponseIsPersistentlyInvalidJson() {
        AnthropicClient client = clientReturning("isso não é JSON de jeito nenhum");
        AnthropicMinutesStructurer structurer = new AnthropicMinutesStructurer(client, "claude-sonnet-4-6", 12000);

        assertThrows(MinutesStructuringException.class, () -> structurer.structure("transcrição"));
    }

    private static AnthropicClient clientReturning(String responseText) {
        AnthropicClient client = mock(AnthropicClient.class);
        MessageService messageService = mock(MessageService.class);
        when(client.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(fakeMessage(responseText));
        return client;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Builds a real {@link Message} by deserializing a JSON payload shaped like an actual
     * Anthropic API response, rather than mocking the SDK's Kotlin-final model classes.
     */
    private static Message fakeMessage(String text) {
        try {
            String escaped = JSON.writeValueAsString(text);
            String responseJson = """
                    {
                      "id": "msg_test",
                      "type": "message",
                      "role": "assistant",
                      "model": "claude-sonnet-4-6",
                      "content": [{"type": "text", "text": %s}],
                      "stop_reason": "end_turn",
                      "stop_sequence": null,
                      "usage": {"input_tokens": 1, "output_tokens": 1}
                    }
                    """.formatted(escaped);
            return JSON.readValue(responseJson, Message.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
