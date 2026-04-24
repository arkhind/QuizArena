package org.example.dto.response.attempt;

import org.example.dto.response.quiz.QuestionDTO;

import java.util.List;

/**
 * @param correctAnswerId      Первый из правильных id (совместимость со старым фронтом).
 * @param correctAnswerIds     Все id верных вариантов(для MULTIPLE_CHOICE).
 */
public record AnswerResponse(
  Boolean isCorrect,
  String explanation,
  Long correctAnswerId,
  List<Long> correctAnswerIds,
  Integer scoreEarned,
  QuestionDTO nextQuestion,
  Long quizId
) {}
