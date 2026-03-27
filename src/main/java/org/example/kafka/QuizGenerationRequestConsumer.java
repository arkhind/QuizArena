package org.example.kafka;

import org.example.dto.kafka.QuizGenerationRequestMessage;
import org.example.service.QuestionGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class QuizGenerationRequestConsumer {
    private static final Logger logger = LoggerFactory.getLogger(QuizGenerationRequestConsumer.class);
    private final QuestionGenerationService questionGenerationService;

    public QuizGenerationRequestConsumer(
            QuestionGenerationService questionGenerationService
    ) {
        this.questionGenerationService = questionGenerationService;
    }

    @KafkaListener(
            topics = "${quizarena.kafka.quiz-generation.request-topic}",
            groupId = "${quizarena.kafka.quiz-generation.worker-group-id}",
            containerFactory = "quizGenerationRequestKafkaListenerContainerFactory"
    )
    public void onRequest(QuizGenerationRequestMessage request) {
        if (request == null) {
            return;
        }

        try {
            questionGenerationService.processKafkaQuizGenerationRequest(request);
        } catch (Exception e) {
            logger.error("Ошибка обработки Kafka-сообщения quiz-generation для questionSetId={}", request.questionSetId(), e);
            // Пробрасываем исключение дальше, чтобы offset не коммитился и Kafka могла повторно доставить запись
            throw e;
        }
    }
}
