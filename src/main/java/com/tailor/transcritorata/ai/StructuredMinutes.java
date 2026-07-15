package com.tailor.transcritorata.ai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Fixed schema returned by Claude when asked to structure a meeting transcript.
 * Keys and values are both in English, per the prompt contract.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StructuredMinutes(
        @JsonProperty("executiveSummary") String executiveSummary,
        @JsonProperty("participants") List<String> participants,
        @JsonProperty("agenda") List<String> agenda,
        @JsonProperty("decisions") List<String> decisions,
        @JsonProperty("actionItems") List<ActionItem> actionItems) {

    public StructuredMinutes {
        participants = participants == null ? List.of() : participants;
        agenda = agenda == null ? List.of() : agenda;
        decisions = decisions == null ? List.of() : decisions;
        actionItems = actionItems == null ? List.of() : actionItems;
        executiveSummary = executiveSummary == null ? "" : executiveSummary;
    }
}
