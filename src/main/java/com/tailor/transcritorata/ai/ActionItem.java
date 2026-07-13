package com.tailor.transcritorata.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A single action item extracted from the meeting transcript by {@link MinutesStructurer}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActionItem(
        @JsonProperty("description") String description,
        @JsonProperty("owner") String owner,
        @JsonProperty("dueDate") String dueDate) {
}
