package org.example.metrics;

import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

@Service
public class MetricsService {

    private final AtomicLong activeAttempts = new AtomicLong(0);
    private final AtomicLong activeMultiplayerSessions = new AtomicLong(0);
    private final AtomicLong activeMultiplayerPlayers = new AtomicLong(0);

    private final Counter answersCorrect;
    private final Counter answersIncorrect;
    private final Counter generationSuccess;
    private final Counter generationFailed;
    private final Counter generationUnethical;
    private final Counter ethicsCheckPassed;
    private final Counter ethicsCheckFailed;
    private final Counter validationFailedNullField;
    private final Counter validationFailedWrongCount;
    private final Counter kafkaDltMessages;

    private final Timer attemptDurationTimer;
    private final Timer generationDurationTimer;
    private final Timer fastApiGenerateTimer;
    private final Timer fastApiEthicsTimer;
    private final Timer kafkaProcessingTimer;

    private final DistributionSummary questionsGeneratedSummary;
    private final DistributionSummary sessionPlayerCountSummary;

    public MetricsService(MeterRegistry registry) {
        Gauge.builder("quizarena_active_attempts_total", activeAttempts, AtomicLong::get)
                .description("Number of currently active quiz attempts")
                .register(registry);

        Gauge.builder("quizarena_multiplayer_sessions_active", activeMultiplayerSessions, AtomicLong::get)
                .description("Number of active multiplayer sessions")
                .register(registry);

        Gauge.builder("quizarena_multiplayer_players_active", activeMultiplayerPlayers, AtomicLong::get)
                .description("Number of players currently in multiplayer sessions")
                .register(registry);

        this.answersCorrect = Counter.builder("quizarena_answers_submitted_total")
                .tag("correct", "true")
                .description("Total answers submitted")
                .register(registry);

        this.answersIncorrect = Counter.builder("quizarena_answers_submitted_total")
                .tag("correct", "false")
                .description("Total answers submitted")
                .register(registry);

        this.generationSuccess = Counter.builder("quizarena_generation_requests_total")
                .tag("status", "success")
                .register(registry);

        this.generationFailed = Counter.builder("quizarena_generation_requests_total")
                .tag("status", "failed")
                .register(registry);

        this.generationUnethical = Counter.builder("quizarena_generation_requests_total")
                .tag("status", "unethical")
                .register(registry);

        this.ethicsCheckPassed = Counter.builder("quizarena_ethics_check_total")
                .tag("result", "passed")
                .register(registry);

        this.ethicsCheckFailed = Counter.builder("quizarena_ethics_check_total")
                .tag("result", "failed")
                .register(registry);

        this.validationFailedNullField = Counter.builder("quizarena_validation_failed_total")
                .tag("reason", "null_field")
                .register(registry);

        this.validationFailedWrongCount = Counter.builder("quizarena_validation_failed_total")
                .tag("reason", "wrong_count")
                .register(registry);

        this.kafkaDltMessages = Counter.builder("quizarena_kafka_dlt_messages_total")
                .description("Messages that ended up in Dead Letter Topic")
                .register(registry);

        this.attemptDurationTimer = Timer.builder("quizarena_attempt_duration_seconds")
                .description("Duration of quiz attempts from start to finish")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.generationDurationTimer = Timer.builder("quizarena_question_generation_seconds")
                .description("Total time for question generation pipeline")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.fastApiGenerateTimer = Timer.builder("quizarena_fastapi_request_seconds")
                .tag("endpoint", "generate")
                .description("FastAPI ML service request duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.fastApiEthicsTimer = Timer.builder("quizarena_fastapi_request_seconds")
                .tag("endpoint", "ethics_check")
                .description("FastAPI ML service request duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.kafkaProcessingTimer = Timer.builder("quizarena_kafka_processing_seconds")
                .description("Time to process one Kafka message end-to-end")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.questionsGeneratedSummary = DistributionSummary.builder("quizarena_questions_generated_count")
                .description("Number of questions generated per batch")
                .publishPercentiles(0.5, 0.95)
                .register(registry);

        this.sessionPlayerCountSummary = DistributionSummary.builder("quizarena_multiplayer_session_players")
                .description("Number of players per multiplayer session at start")
                .register(registry);
    }

    public void incrementActiveAttempts() { activeAttempts.incrementAndGet(); }
    public void decrementActiveAttempts() { activeAttempts.decrementAndGet(); }

    public void incrementActiveMultiplayerSessions() { activeMultiplayerSessions.incrementAndGet(); }
    public void decrementActiveMultiplayerSessions() { activeMultiplayerSessions.decrementAndGet(); }
    public void addMultiplayerPlayers(long count) { activeMultiplayerPlayers.addAndGet(count); }
    public void removeMultiplayerPlayers(long count) { activeMultiplayerPlayers.addAndGet(-count); }

    public void recordCorrectAnswer() { answersCorrect.increment(); }
    public void recordIncorrectAnswer() { answersIncorrect.increment(); }

    public void recordGenerationSuccess() { generationSuccess.increment(); }
    public void recordGenerationFailed() { generationFailed.increment(); }
    public void recordGenerationUnethical() { generationUnethical.increment(); }

    public void recordEthicsCheckPassed() { ethicsCheckPassed.increment(); }
    public void recordEthicsCheckFailed() { ethicsCheckFailed.increment(); }

    public void recordValidationFailedNullField() { validationFailedNullField.increment(); }
    public void recordValidationFailedWrongCount() { validationFailedWrongCount.increment(); }

    public void recordKafkaDltMessage() { kafkaDltMessages.increment(); }

    public void recordQuestionsGenerated(int count) { questionsGeneratedSummary.record(count); }
    public void recordSessionPlayerCount(int count) { sessionPlayerCountSummary.record(count); }

    public Timer getAttemptDurationTimer() { return attemptDurationTimer; }
    public Timer getGenerationDurationTimer() { return generationDurationTimer; }
    public Timer getFastApiGenerateTimer() { return fastApiGenerateTimer; }
    public Timer getFastApiEthicsTimer() { return fastApiEthicsTimer; }
    public Timer getKafkaProcessingTimer() { return kafkaProcessingTimer; }
}
