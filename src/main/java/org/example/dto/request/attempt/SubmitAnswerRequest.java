package org.example.dto.request.attempt;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;

public record SubmitAnswerRequest(
  Long attemptId,
  Long questionId,
  @JsonAlias("answerId") Long selectedAnswerId,
  List<Long> selectedAnswerIds,
  Boolean autoSubmitOnTimeout
) {
  /**
   * Returns the list of selected answer IDs.
   * Uses selectedAnswerIds if present, otherwise forms a single-element list from selectedAnswerId (backward compatibility).
   */
  public List<Long> getEffectiveSelectedIds() {
    if (selectedAnswerIds != null && !selectedAnswerIds.isEmpty()) {
      return selectedAnswerIds;
    }
    if (selectedAnswerId != null) {
      return List.of(selectedAnswerId);
    }
    return List.of();
  }
}
