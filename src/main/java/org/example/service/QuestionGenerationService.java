package org.example.service;

import org.example.metrics.MetricsService;
import org.example.dto.request.generation.QuestionGenerationRequest;
import org.example.dto.kafka.QuizGenerationRequestMessage;
import org.example.kafka.KafkaQuizGenerationProperties;
import org.example.dto.response.generation.QuestionGenerationResponse;
import org.example.model.*;
import org.example.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.example.dto.ml.MlQuestionDTO;
import org.example.dto.ml.MlQuestionOptionDTO;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class QuestionGenerationService {
    private static final int MAX_GENERATION_ATTEMPTS = 3;
    private final GenerationSetRepository generationSetRepository;
    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final AnswerOptionRepository answerOptionRepository;
    private final FastApiClient fastApiClient;
    private final KafkaTemplate<String, QuizGenerationRequestMessage> requestKafkaTemplate;
    private final String requestTopic;
    private final QuizCacheEvictService quizCacheEvictService;
    private final MetricsService metricsService;

    @Autowired
    public QuestionGenerationService(
            GenerationSetRepository generationSetRepository,
            QuizRepository quizRepository,
            QuestionRepository questionRepository,
            AnswerOptionRepository answerOptionRepository,
            FastApiClient fastApiClient,
            KafkaTemplate<String, QuizGenerationRequestMessage> quizGenerationRequestKafkaTemplate,
            KafkaQuizGenerationProperties kafkaQuizGenerationProperties,
            QuizCacheEvictService quizCacheEvictService,
            MetricsService metricsService) {
        this.generationSetRepository = generationSetRepository;
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.answerOptionRepository = answerOptionRepository;
        this.fastApiClient = fastApiClient;
        this.requestKafkaTemplate = quizGenerationRequestKafkaTemplate;
        this.requestTopic = kafkaQuizGenerationProperties.getRequestTopic();
        this.quizCacheEvictService = quizCacheEvictService;
        this.metricsService = metricsService;
    }

    /**
     * Producer-side: создаёт GenerationSet, отправляет request в Kafka и возвращает questionSetId
     */
    public QuestionGenerationResponse generateQuizQuestionsKafka(QuestionGenerationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is null");
        }

        Quiz quiz = quizRepository.findById(request.quizId())
                .orElseThrow(() -> new IllegalArgumentException("Квиз не найден: " + request.quizId()));

        GenerationSet questionSet = new GenerationSet();
        questionSet.setQuiz(quiz);
        questionSet.setPrompt(request.prompt());
        questionSet.setStatus("GENERATING");
        questionSet.setCreatedAt(Instant.now());
        questionSet.setGeneratedCount(0);
        questionSet.setValidCount(0);
        questionSet.setDuplicateCount(0);
        questionSet.setFinalCount(0);
        questionSet = generationSetRepository.save(questionSet);

        String correlationId = UUID.randomUUID().toString();
        String preferredQuestionType = request.preferredQuestionType() != null
                ? request.preferredQuestionType().name()
                : null;

        QuizGenerationRequestMessage kafkaRequest = new QuizGenerationRequestMessage(
                correlationId,
                questionSet.getId(),
                request.quizId(),
                request.prompt(),
                request.questionCount(),
                preferredQuestionType
        );

        try {
            requestKafkaTemplate.send(requestTopic, kafkaRequest.correlationId(), kafkaRequest);
        } catch (Exception e) {
            questionSet.setStatus("FAILED");
            generationSetRepository.save(questionSet);
            throw new RuntimeException("Kafka send failed", e);
        }

        return new QuestionGenerationResponse(
                questionSet.getId(),
                questionSet.getStatus(),
                0,
                0,
                0,
                0
        );
    }

    /**
     * Worker-side: вызывается consumer'ом по Kafka request и выполняет генерацию через ML
     */
    public void processKafkaQuizGenerationRequest(QuizGenerationRequestMessage kafkaRequest) {
        if (kafkaRequest == null) {
            throw new IllegalArgumentException("kafkaRequest is null");
        }
        GenerationSet existingSet = null;
        long generationStart = System.nanoTime();
        try {
            if (kafkaRequest.questionSetId() == null) {
                throw new IllegalArgumentException("questionSetId is required");
            }

            existingSet = generationSetRepository.findById(kafkaRequest.questionSetId())
                    .orElseThrow(() -> new IllegalArgumentException("Набор вопросов не найден"));

            if ("READY".equals(existingSet.getStatus())) {
                return;
            }

            if (kafkaRequest.prompt() != null && !kafkaRequest.prompt().trim().isEmpty()) {
                boolean isUnethical = fastApiClient.checkPromptEthics(kafkaRequest.prompt());
                if (isUnethical) {
                    metricsService.recordGenerationUnethical();
                    existingSet.setStatus("FAILED");
                    generationSetRepository.save(existingSet);
                    if (existingSet.getQuiz() != null && existingSet.getQuiz().getId() != null) {
                        quizCacheEvictService.evictQuizCache(existingSet.getQuiz().getId());
                    }
                    return;
                }
            }

            QuestionType preferred = null;
            if (kafkaRequest.preferredQuestionType() != null && !kafkaRequest.preferredQuestionType().isBlank()) {
                try {
                    preferred = QuestionType.valueOf(kafkaRequest.preferredQuestionType().trim().toUpperCase());
                } catch (Exception ignored) {
                    preferred = null;
                }
            }

            QuestionGenerationRequest internalRequest = new QuestionGenerationRequest(
                    kafkaRequest.quizId(),
                    kafkaRequest.prompt(),
                    null,
                    null,
                    kafkaRequest.questionCount(),
                    preferred
            );

            generateExistingQuestionSet(kafkaRequest.questionSetId(), internalRequest);
            metricsService.recordGenerationSuccess();
            metricsService.getGenerationDurationTimer().record(System.nanoTime() - generationStart, TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            if (existingSet != null && !"READY".equals(existingSet.getStatus())) {
                existingSet.setStatus("FAILED");
                generationSetRepository.save(existingSet);
                if (existingSet.getQuiz() != null && existingSet.getQuiz().getId() != null) {
                    quizCacheEvictService.evictQuizCache(existingSet.getQuiz().getId());
                }
            }
            metricsService.recordGenerationFailed();
            metricsService.getGenerationDurationTimer().record(System.nanoTime() - generationStart, TimeUnit.NANOSECONDS);
            throw new RuntimeException("Ошибка обработки Kafka-запроса генерации: " + e.getMessage(), e);
        }
    }

    @Transactional
    public QuestionGenerationResponse generateExistingQuestionSet(Long questionSetId, QuestionGenerationRequest request) {
        GenerationSet existingSet = generationSetRepository.findById(questionSetId)
                .orElseThrow(() -> new IllegalArgumentException("Набор вопросов не найден"));

        Quiz quiz = quizRepository.findById(request.quizId())
                .orElseThrow(() -> new IllegalArgumentException("Квиз не найден: " + request.quizId()));

        // синхронизируем set (на случай если prompt пустой/обновился)
        existingSet.setQuiz(quiz);
        existingSet.setPrompt(request.prompt());
        existingSet.setStatus("GENERATING");
        existingSet.setGeneratedCount(0);
        existingSet.setValidCount(0);
        existingSet.setDuplicateCount(0);
        existingSet.setFinalCount(0);
        generationSetRepository.save(existingSet);

        return generateQuizQuestionsUsingSet(existingSet, request);
    }

    private QuestionGenerationResponse generateQuizQuestionsUsingSet(GenerationSet questionSet, QuestionGenerationRequest request) {
        Quiz quiz = questionSet.getQuiz();
        int questionCount = request.questionCount() != null ? request.questionCount() : 10;
        String prompt = request.prompt() != null ? request.prompt() : "Общая тема";
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_GENERATION_ATTEMPTS; attempt++) {
            List<Question> generatedQuestions = new ArrayList<>();
            try {
                if (attempt > 1) {
                    // Перед новой попыткой очищаем частично сохранённые результаты предыдущей
                    questionRepository.deleteByGenerationSetId(questionSet.getId());
                }

                System.out.println("QuestionGenerationService: Начинаем генерацию вопросов, попытка " + attempt +
                        " для промпта: " + prompt.substring(0, Math.min(50, prompt.length())));
                List<MlQuestionDTO> mlQuestions = fastApiClient.generateQuestionsStructured(
                        prompt,
                        questionCount,
                        request.preferredQuestionType()
                );
                System.out.println("QuestionGenerationService: Получено вопросов от ML: " + (mlQuestions != null ? mlQuestions.size() : 0));

                if (mlQuestions == null || mlQuestions.isEmpty()) {
                    throw new RuntimeException("ML не вернул ни одного вопроса");
                }

                int maxQuestionsToSave = questionCount;
                for (int i = 0; i < mlQuestions.size() && generatedQuestions.size() < maxQuestionsToSave; i++) {
                    MlQuestionDTO mq = mlQuestions.get(i);
                    if (mq == null || mq.question() == null || mq.question().trim().isEmpty()) {
                        metricsService.recordValidationFailed();
                        continue;
                    }

                    QuestionType type = mapMlType(mq.type(), request.preferredQuestionType());

                    List<MlQuestionOptionDTO> options = mq.options() != null ? mq.options() : List.of();
                    List<String> correctIds = mq.correct_answers() != null ? mq.correct_answers() : List.of();
                    long correctCount = options.stream()
                            .filter(o -> o != null && o.id() != null && correctIds.stream().anyMatch(id -> id != null && id.equalsIgnoreCase(o.id())))
                            .count();
                    long optionCount = options.stream().filter(o -> o != null && o.text() != null && !o.text().trim().isEmpty()).count();
                    long wrongCount = optionCount - correctCount;

                    if (type == QuestionType.HUNDRED_TO_ONE) {
                        if (optionCount != 8 || correctCount != 5 || wrongCount != 3) {
                            System.err.println("QuestionGenerationService: Пропуск вопроса 100к1 - некорректная структура ("
                                    + optionCount + " опций, " + correctCount + " correct, " + wrongCount + " wrong)");
                            metricsService.recordValidationFailed();
                            continue;
                        }
                    }

                    if (type == QuestionType.SINGLE_CHOICE) {
                        // Мы требуем 4 варианта, но не блокируем полностью генерацию — просто пропускаем некорректные
                        if (optionCount != 4) {
                            System.err.println("QuestionGenerationService: Пропуск single_choice - ожидалось 4 варианта, получено " + optionCount);
                            metricsService.recordValidationFailed();
                            continue;
                        }
                    }

                    Question question = new Question();
                    question.setQuiz(quiz);
                    question.setText(mq.question().trim());
                    question.setType(type);
                    question.setIsGenerated(true);
                    question.setGenerationSetId(questionSet.getId());
                    question.setIsValid(true);
                    question.setIsDuplicate(false);

                    String explanation = mq.explanation() != null ? mq.explanation().trim() : "";
                    question.setExplanation(explanation.isEmpty() ? "Объяснение отсутствует" : explanation);

                    question = questionRepository.save(question);

                    for (MlQuestionOptionDTO opt : options) {
                        if (opt == null || opt.text() == null || opt.text().trim().isEmpty()) {
                            continue;
                        }
                        AnswerOption ao = new AnswerOption();
                        ao.setQuestion(question);
                        ao.setText(opt.text().trim());
                        boolean isCorrect = false;
                        if (opt.id() != null) {
                            for (String cid : correctIds) {
                                if (cid != null && cid.equalsIgnoreCase(opt.id().trim())) {
                                    isCorrect = true;
                                    break;
                                }
                            }
                        }
                        ao.setCorrect(isCorrect);
                        ao.setNaOption(false);
                        answerOptionRepository.save(ao);
                    }

                    generatedQuestions.add(question);
                }

                if (generatedQuestions.isEmpty()) {
                    throw new RuntimeException("Не удалось сохранить ни одного вопроса из ответа ML");
                }

                questionSet.setGeneratedCount(generatedQuestions.size());
                questionSet.setValidCount(generatedQuestions.size());
                questionSet.setFinalCount(generatedQuestions.size());
                questionSet.setStatus("READY");
                metricsService.recordQuestionsGenerated(generatedQuestions.size());
                generationSetRepository.save(questionSet);
                quizCacheEvictService.evictQuizCache(quiz.getId());

                return new QuestionGenerationResponse(
                        questionSet.getId(),
                        questionSet.getStatus(),
                        generatedQuestions.size(),
                        generatedQuestions.size(),
                        0,
                        generatedQuestions.size()
                );
            } catch (Exception e) {
                lastException = e;
                System.err.println("QuestionGenerationService: Попытка " + attempt + " завершилась ошибкой: " + e.getMessage());
                if (attempt == MAX_GENERATION_ATTEMPTS) {
                    break;
                }
            }
        }

        questionSet.setStatus("FAILED");
        generationSetRepository.save(questionSet);
        quizCacheEvictService.evictQuizCache(quiz.getId());
        throw new RuntimeException(
                "Ошибка при генерации вопросов после " + MAX_GENERATION_ATTEMPTS + " попыток: "
                        + (lastException != null ? lastException.getMessage() : "unknown"),
                lastException
        );
    }

    private QuestionType mapMlType(String mlType, QuestionType preferredFallback) {
        String t = mlType != null ? mlType.trim().toLowerCase() : "";
        return switch (t) {
            case "multiple_choice" -> QuestionType.MULTIPLE_CHOICE;
            case "true_false" -> QuestionType.TRUE_FALSE;
            case "100k1", "q100k1", "hundred_to_one" -> QuestionType.HUNDRED_TO_ONE;
            case "single_choice" -> QuestionType.SINGLE_CHOICE;
            default -> preferredFallback != null ? preferredFallback : QuestionType.SINGLE_CHOICE;
        };
    }

    @Transactional
    public QuestionGenerationResponse generateQuizQuestions(QuestionGenerationRequest request) {
        Quiz quiz = quizRepository.findById(request.quizId())
                .orElseThrow(() -> new IllegalArgumentException("Квиз не найден: " + request.quizId()));

        GenerationSet questionSet = new GenerationSet();
        questionSet.setQuiz(quiz);
        questionSet.setPrompt(request.prompt());
        questionSet.setStatus("GENERATING");
        questionSet.setCreatedAt(Instant.now());
        questionSet.setGeneratedCount(0);
        questionSet.setValidCount(0);
        questionSet.setDuplicateCount(0);
        questionSet.setFinalCount(0);
        questionSet = generationSetRepository.save(questionSet);
        return generateQuizQuestionsUsingSet(questionSet, request);
    }

    @Transactional(readOnly = true)
    public org.example.dto.response.generation.ValidationResponse validateGeneratedQuestions(Long questionSetId) {
        GenerationSet questionSet = generationSetRepository.findById(questionSetId)
                .orElseThrow(() -> new IllegalArgumentException("Набор вопросов не найден"));

        Long quizId = questionSet.getQuiz().getId();
        List<Question> questions = questionRepository.findByQuizId(quizId);
        List<Question> generatedQuestions = questions.stream()
                .filter(q -> q.getGenerationSetId() != null && questionSetId.equals(q.getGenerationSetId()))
                .collect(java.util.stream.Collectors.toList());
        
        return new org.example.dto.response.generation.ValidationResponse(
                questionSetId,
                generatedQuestions.size(),
                generatedQuestions.size(),
                new java.util.ArrayList<>()
        );
  }

    @Transactional(readOnly = true)
    public org.example.dto.response.generation.DeduplicationResponse removeDuplicateQuestions(Long questionSetId) {
        GenerationSet questionSet = generationSetRepository.findById(questionSetId)
                .orElseThrow(() -> new IllegalArgumentException("Набор вопросов не найден"));

        Long quizId = questionSet.getQuiz().getId();
        List<Question> questions = questionRepository.findByQuizId(quizId);
        List<Question> validQuestions = questions.stream()
                .filter(q -> q.getGenerationSetId() != null && questionSetId.equals(q.getGenerationSetId()))
                .collect(java.util.stream.Collectors.toList());
        
        return new org.example.dto.response.generation.DeduplicationResponse(
                questionSetId,
                validQuestions.size(),
                validQuestions.size(),
                new java.util.ArrayList<>()
        );
    }

    @Transactional(readOnly = true)
    public org.example.dto.response.generation.GeneratedQuestionsDTO getGeneratedQuestions(Long questionSetId) {
        GenerationSet questionSet = generationSetRepository.findById(questionSetId)
                .orElseThrow(() -> new IllegalArgumentException("Набор вопросов не найден"));

        Long quizId = questionSet.getQuiz().getId();
        List<Question> questions = questionRepository.findByQuizId(quizId);
        List<Question> generatedQuestions = questions.stream()
                .filter(q -> q.getGenerationSetId() != null && questionSetId.equals(q.getGenerationSetId()))
                .collect(java.util.stream.Collectors.toList());
        
        List<org.example.dto.response.quiz.QuestionDTO> questionDTOs = generatedQuestions.stream()
                .map(q -> {
                    List<org.example.model.AnswerOption> options = answerOptionRepository.findByQuestionId(q.getId());
                    List<org.example.dto.common.AnswerOption> dtoOptions = options.stream()
                            .map(opt -> new org.example.dto.common.AnswerOption(opt.getId(), opt.getText(), opt.getNominal()))
                            .collect(java.util.stream.Collectors.toList());

                    return new org.example.dto.response.quiz.QuestionDTO(
                            q.getId(),
                            q.getText(),
                            dtoOptions,
                            q.getType(),
                            q.getQuiz().getTimePerQuestion() != null ? 
                                    (int) q.getQuiz().getTimePerQuestion().getSeconds() : null,
                            null,
                            q.getExplanation(),
                            null,
                            null,
                            0,
                            toLocalDateTime(q.getQuiz().getCreatedAt())
                    );
                })
                .collect(java.util.stream.Collectors.toList());

        org.example.dto.common.GenerationMetadata metadata = new org.example.dto.common.GenerationMetadata(
                toLocalDateTime(questionSet.getCreatedAt()),
                "1.0",
                String.valueOf(questionSet.getPrompt() != null ? questionSet.getPrompt().hashCode() : 0)
        );

        return new org.example.dto.response.generation.GeneratedQuestionsDTO(
                questionSetId,
                questionDTOs,
                metadata
        );
    }

    private java.time.LocalDateTime toLocalDateTime(Instant instant) {
        return instant != null
                ? java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
                : null;
    }
}
