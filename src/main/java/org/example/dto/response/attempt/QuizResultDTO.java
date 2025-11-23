package org.example.dto.response.attempt;

import java.time.LocalDateTime;

public record QuizResultDTO(
  Long attemptId,
  Integer score,
  Integer correctAnswers,
  Integer totalQuestions,
  Integer position, // место в общем рейтинге
  Long timeSpent,
  LocalDateTime completedAt
) {}
