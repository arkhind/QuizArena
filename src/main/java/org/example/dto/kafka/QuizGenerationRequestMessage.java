package org.example.dto.kafka;

/**
 * Сообщение в Kafka для асинхронной генерации вопросов (request -> worker -> response)
 */
public record QuizGenerationRequestMessage(
        String correlationId,
        Long questionSetId,
        Long quizId,
        String prompt,
        Integer questionCount,
        String preferredQuestionType
) {}

