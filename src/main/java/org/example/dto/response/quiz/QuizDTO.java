package org.example.dto.response.quiz;

import java.time.LocalDateTime;

public record QuizDTO(
  Long id,
  String name,
  String author,
  Integer questionCount,
  Integer timeLimit,
  Boolean isPublic,
  Boolean isStatic,
  LocalDateTime createdAt
) {}
