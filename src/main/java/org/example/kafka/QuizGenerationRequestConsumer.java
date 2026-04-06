package org.example.kafka;

import org.example.dto.kafka.QuizGenerationRequestMessage;
import org.example.metrics.MetricsService;
import org.example.service.QuestionGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class QuizGenerationRequestConsumer {
    private static final Logger logger = LoggerFactory.getLogger(QuizGenerationRequestConsumer.class);
    private final QuestionGenerationService questionGenerationService;
    private final MetricsService metricsService;

    public QuizGenerationRequestConsumer(
            QuestionGenerationService questionGenerationService,
            MetricsService metricsService) {
        this.questionGenerationService = questionGenerationService;
        this.metricsService = metricsService;
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

        long start = System.nanoTime();
        try {
            questionGenerationService.processKafkaQuizGenerationRequest(request);
            metricsService.getKafkaProcessingTimer().record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            metricsService.getKafkaProcessingTimer().record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            metricsService.recordKafkaDltMessage();
            logger.error("Ошибка обработки Kafka-сообщения quiz-generation для questionSetId={}", request.questionSetId(), e);
            throw e;
        }
    }
}
