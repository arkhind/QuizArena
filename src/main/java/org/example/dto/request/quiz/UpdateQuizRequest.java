package org.example.dto.request.quiz;

public record UpdateQuizRequest(Long quizId, Long userId, String name, String prompt) {}
