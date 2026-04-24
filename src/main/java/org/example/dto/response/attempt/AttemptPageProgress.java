package org.example.dto.response.attempt;

//для видимости прохождения квиза
public record AttemptPageProgress(
        int totalQuestions,
        int questionsRemaining,
        int timePerQuestionSeconds,
        Long currentQuestionDeadlineEpochMs
) {}
