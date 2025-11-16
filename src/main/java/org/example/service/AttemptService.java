package org.example.service;

import org.example.dto.request.attempt.StartAttemptRequest;
import org.example.dto.request.attempt.SubmitAnswerRequest;
import org.example.dto.response.attempt.AnswerResponse;
import org.example.dto.response.attempt.AttemptResponse;
import org.example.dto.response.attempt.QuizResultDTO;
import org.example.dto.response.quiz.QuestionDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AttemptService {

    public AttemptResponse startQuizAttempt(StartAttemptRequest request) {
        throw new UnsupportedOperationException("Метод еще не реализован");
    }

    public QuestionDTO getNextQuestion(Long attemptId) {
        throw new UnsupportedOperationException("Метод еще не реализован");
    }

    public AnswerResponse submitAnswer(SubmitAnswerRequest request) {
        throw new UnsupportedOperationException("Метод еще не реализован");
    }

    public QuizResultDTO finishQuizAttempt(Long attemptId) {
        throw new UnsupportedOperationException("Метод еще не реализован");
    }
}

