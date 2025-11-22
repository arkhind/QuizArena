package org.example.service;

import org.example.dto.request.generation.QuestionGenerationRequest;
import org.example.dto.response.generation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сервис для генерации вопросов с помощью ИИ.
 */
@Service
@Transactional
public class QuestionGenerationService {

    public QuestionGenerationResponse generateQuizQuestions(QuestionGenerationRequest request) {
        throw new UnsupportedOperationException("Метод еще не реализован");
    }

    public ValidationResponse validateGeneratedQuestions(Long questionSetId) {
        throw new UnsupportedOperationException("Метод еще не реализован");
    }

    public DeduplicationResponse removeDuplicateQuestions(Long questionSetId) {
        throw new UnsupportedOperationException("Метод еще не реализован");
    }

    public GeneratedQuestionsDTO getGeneratedQuestions(Long questionSetId) {
        throw new UnsupportedOperationException("Метод еще не реализован");
    }
}

