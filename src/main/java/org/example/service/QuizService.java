package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.request.generation.QuestionGenerationRequest;
import org.example.dto.request.quiz.*;
import org.example.dto.response.quiz.*;
import org.example.dto.response.generation.GenerationStatusResponse;
import org.example.dto.common.LeaderboardEntry;
import org.example.dto.common.QuizMaterial;
import org.example.model.GenerationSet;
import org.example.model.QuestionType;
import org.example.model.Quiz;
import org.example.model.User;
import org.example.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
public class QuizService {
    private static final String QUIZ_CACHE_KEY = "quiz:%d";
    private static final Duration QUIZ_CACHE_TTL = Duration.ofMinutes(30);
    private static final int DEBUG_GENERATION_COUNT = 10;
    private static final String STATUS_READY = "READY";
    private static final String STATUS_FAILED = "FAILED";

    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final AnswerOptionRepository answerOptionRepository;
    private final UserQuizAttemptRepository attemptRepository;
    private final UserAnswerRepository userAnswerRepository;
    private final GenerationSetRepository generationSetRepository;
    private final MultiplayerSessionRepository multiplayerSessionRepository;
    private final org.example.repository.UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final QuestionGenerationService questionGenerationService;
    private final FastApiClient fastApiClient;
    private final LeaderboardService leaderboardService;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNewTx;

    @Autowired
    public QuizService(QuizRepository quizRepository,
                      QuestionRepository questionRepository,
                      AnswerOptionRepository answerOptionRepository,
                      UserQuizAttemptRepository attemptRepository,
                      UserAnswerRepository userAnswerRepository,
                      GenerationSetRepository generationSetRepository,
                      MultiplayerSessionRepository multiplayerSessionRepository,
                      org.example.repository.UserRepository userRepository,
                      FileStorageService fileStorageService,
                      QuestionGenerationService questionGenerationService,
                      FastApiClient fastApiClient,
                      LeaderboardService leaderboardService,
                      RedisTemplate<String, String> redisTemplate,
                      ObjectMapper objectMapper,
                      PlatformTransactionManager transactionManager) {
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.answerOptionRepository = answerOptionRepository;
        this.attemptRepository = attemptRepository;
        this.userAnswerRepository = userAnswerRepository;
        this.generationSetRepository = generationSetRepository;
        this.multiplayerSessionRepository = multiplayerSessionRepository;
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
        this.questionGenerationService = questionGenerationService;
        this.fastApiClient = fastApiClient;
        this.leaderboardService = leaderboardService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;

        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTx = tt;
    }

    /**
     * Важно: генерация идёт через Kafka (другой поток/транзакция),
     * поэтому квиз должен быть закоммичен ДО отправки сообщения.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public QuizResponseDTO createQuiz(CreateQuizRequest request) {
        User creator = userRepository.findById(request.createdBy())
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + request.createdBy()));

        // Этичность проверяется в Kafka worker перед фактической генерацией

        record CreateQuizTxResult(Quiz quiz, boolean hasFile) {}

        CreateQuizTxResult txResult = requiresNewTx.execute(status -> {
            Quiz quiz = new Quiz();
            quiz.setName(request.name());
            quiz.setPrompt(request.prompt());
            quiz.setCreatedBy(creator);
            quiz.setHasMaterial(request.hasMaterial() != null && request.hasMaterial());
            quiz.setMaterialUrl(null);
            quiz.setQuestionNumber(request.questionNumber());
            quiz.setTimePerQuestion(request.timeLimit() != null ?
                    java.time.Duration.ofSeconds(request.timeLimit()) : null);
            quiz.setPrivate(request.isPrivate() != null && request.isPrivate());
            quiz.setStatic(request.isStatic() != null && request.isStatic());
            quiz.setCreatedAt(Instant.now());
            quiz.setDefaultQuestionType(resolveDefaultQuestionType(request.defaultQuestionType()));

            quiz = quizRepository.save(quiz);
            // save() уже в транзакции REQUIRES_NEW, коммит произойдет после выхода из execute()

            boolean hasFile = Boolean.TRUE.equals(request.hasMaterial());
            return new CreateQuizTxResult(quiz, hasFile);
        });

        if (txResult == null || txResult.quiz() == null) {
            throw new RuntimeException("Не удалось создать квиз");
        }

        Quiz quiz = txResult.quiz();

        // Если пользователь выбрал файл, генерация пойдёт в regenerateWithMaterial после POST .../materials
        boolean hasFile = txResult.hasFile();

        if (request.prompt() != null && !request.prompt().trim().isEmpty() && !hasFile) {
            try {
                // Временно генерируем меньше вопросов для более стабильной отладки
                // questionNumber - это количество вопросов для сессии, а не для генерации
                int questionCountForGeneration = resolveGenerationCount(request.questionNumber());
                
                // На новом квизе вопросов ещё нет, но оставим логику "на всякий случай"
                requiresNewTx.execute(status -> {
                    long existingQuestionCount = questionRepository.countByQuizId(quiz.getId());
                    if (existingQuestionCount > 0) {
                        System.out.println("QuizService: Удаляем " + existingQuestionCount + " старых вопросов перед генерацией новых");
                        userAnswerRepository.nullifySelectedAnswerReferences(quiz.getId());
                        userAnswerRepository.deleteByQuestionQuizId(quiz.getId());
                        questionRepository.deleteByQuizId(quiz.getId());
                    }
                    return null;
                });
                
                org.example.dto.request.generation.QuestionGenerationRequest genRequest =
                    new org.example.dto.request.generation.QuestionGenerationRequest(
                        quiz.getId(),
                        request.prompt(),
                        request.materials(),
                        request.questionNumber(), // Количество вопросов для сессии
                        questionCountForGeneration, // Временно генерируем 10 вопросов
                        resolveDefaultQuestionType(quiz.getDefaultQuestionType())
                    );
                
                questionGenerationService.generateQuizQuestionsKafka(genRequest);
            } catch (Exception e) {
                System.err.println("QuizService: Ошибка при постановке генерации в очередь: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Ошибка при постановке генерации в очередь: " + e.getMessage(), e);
            }
        }

        return new QuizResponseDTO(
                quiz.getId(),
                quiz.getName(),
                "created",
                toLocalDateTime(quiz.getCreatedAt()),
                String.valueOf(quiz.getId())
        );
    }

    public QuizSearchResponse searchPublicQuizzes(QuizSearchRequest request) {
        Pageable pageable = createPageable(request.page(), request.size(), request.sortBy(), request.ascending());
        
        Page<Quiz> page;
        if (request.query() == null || request.query().trim().isEmpty()) {
            page = quizRepository.findByIsPrivateFalse(pageable);
        } else {
            String query = request.query().trim();
            try {
                Long quizId = Long.parseLong(query);
                var quizById = quizRepository.findById(quizId);
                if (quizById.isPresent() && !quizById.get().isPrivate()) {
                    List<QuizDTO> content = List.of(toQuizDTO(quizById.get()));
                    return new QuizSearchResponse(content, 0, 1, 1L);
                }
            } catch (NumberFormatException e) {
                // Не число, продолжаем обычный поиск
            }
            page = quizRepository.searchPublicQuizzes(query, pageable);
        }

        List<QuizDTO> content = page.getContent().stream()
                .map(this::toQuizDTO)
                .collect(Collectors.toList());

        return new QuizSearchResponse(
                content,
                page.getNumber(),
                page.getTotalPages(),
                page.getTotalElements()
        );
    }

    public QuizDetailsDTO getQuiz(Long quizId, Long userId) {
        // Пробуем получить из кэша (только публичные квизы)
        String cacheKey = String.format(QUIZ_CACHE_KEY, quizId);
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                QuizDetailsDTO dto = objectMapper.readValue(cached, QuizDetailsDTO.class);
                if (Boolean.TRUE.equals(dto.isPublic())) {
                    return dto;
                }
                // Приватный квиз в кэше — всё равно идём в БД для проверки доступа
            }
        } catch (Exception e) {
            System.err.println("QuizService: ошибка чтения кэша квиза: " + e.getMessage());
        }

        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new IllegalArgumentException("Квиз не найден"));

        if (quiz.isPrivate()) {
            if (userId != null && quiz.getCreatedBy().getId().equals(userId)) {
                // Создатель имеет доступ
            } else {
                throw new SecurityException("Доступ к приватному квизу запрещен");
            }
        }

        List<QuestionDTO> questions = questionRepository.findByQuizId(quizId).stream()
                .map(this::toQuestionDTO)
                .collect(Collectors.toList());

        List<QuizMaterial> materials = new ArrayList<>();
        if (quiz.isHasMaterial() && quiz.getMaterialUrl() != null) {
            String url = quiz.getMaterialUrl();
            String name = url.substring(url.lastIndexOf('/') + 1);
            materials = List.of(new QuizMaterial(name, url, null, null));
        }

        Integer timePerQuestionSeconds = quiz.getTimePerQuestion() != null ? (int) quiz.getTimePerQuestion().getSeconds() : null;
        Integer totalTimeSeconds = null;
        if (timePerQuestionSeconds != null && quiz.getQuestionNumber() != null && quiz.getQuestionNumber() > 0) {
            totalTimeSeconds = timePerQuestionSeconds * quiz.getQuestionNumber();
        }

        QuizDetailsDTO dto = new QuizDetailsDTO(
                quiz.getId(),
                quiz.getName(),
                quiz.getPrompt(),
                quiz.getCreatedBy().getLogin(),
                questions,
                materials,
                quiz.getQuestionNumber(),
                totalTimeSeconds,
                timePerQuestionSeconds,
                !quiz.isPrivate(),
                quiz.isStatic(),
                String.valueOf(quiz.getId()),
                toLocalDateTime(quiz.getCreatedAt()),
                resolveDefaultQuestionType(quiz.getDefaultQuestionType())
        );

        // Кэшируем только публичные квизы
        if (!quiz.isPrivate()) {
            try {
                redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(dto), QUIZ_CACHE_TTL);
            } catch (Exception e) {
                System.err.println("QuizService: ошибка записи кэша квиза: " + e.getMessage());
            }
        }

        return dto;
    }

    @Transactional(readOnly = true)
    public GenerationStatusResponse getGenerationStatus(Long quizId) {
        GenerationSet generationSet = generationSetRepository.findTopByQuizIdOrderByCreatedAtDesc(quizId)
                .orElse(null);
        if (generationSet == null) {
            return new GenerationStatusResponse(
                    quizId,
                    null,
                    "NOT_STARTED",
                    "Генерация для этого квиза ещё не запускалась.",
                    true,
                    false
            );
        }

        String status = generationSet.getStatus() != null ? generationSet.getStatus() : "UNKNOWN";
        boolean failed = STATUS_FAILED.equals(status);
        boolean finished = STATUS_READY.equals(status) || failed;
        String message;
        if (STATUS_READY.equals(status)) {
            message = "Квиз готов.";
        } else if (failed) {
            message = generationSet.getFailureMessage() != null && !generationSet.getFailureMessage().isBlank()
                    ? generationSet.getFailureMessage()
                    : "Не удалось сгенерировать вопросы для этого квиза.";
        } else {
            message = "Квиз генерируется. Это может занять несколько минут, страница обновится, когда вопросы будут готовы.";
        }

        return new GenerationStatusResponse(
                quizId,
                generationSet.getId(),
                status,
                message,
                finished,
                failed
        );
    }

    private QuestionType resolveDefaultQuestionType(QuestionType defaultQuestionType) {
        return defaultQuestionType != null ? defaultQuestionType : QuestionType.SINGLE_CHOICE;
    }

    private int resolveGenerationCount(Integer requestedQuestionCount) {
        return DEBUG_GENERATION_COUNT;
    }

    public boolean deleteQuiz(DeleteQuizRequest request) {
        if (!quizRepository.existsById(request.quizId())) {
            throw new IllegalArgumentException("Квиз не найден");
        }

        if (!quizRepository.isCreator(request.quizId(), request.userId())) {
            throw new SecurityException("Только создатель может удалить квиз");
        }

        Long quizId = request.quizId();
        List<org.example.model.UserQuizAttempt> attempts = attemptRepository.findByQuizId(quizId);
        for (org.example.model.UserQuizAttempt attempt : attempts) {
            userAnswerRepository.findByAttemptId(attempt.getId()).forEach(userAnswerRepository::delete);
        }
        attemptRepository.findByQuizId(quizId).forEach(attemptRepository::delete);
        generationSetRepository.findAllByQuizId(quizId).forEach(generationSetRepository::delete);
        multiplayerSessionRepository.findByQuizId(quizId).forEach(multiplayerSessionRepository::delete);
        questionRepository.deleteByQuizId(quizId);
        try {
            fileStorageService.deleteQuizMaterials(quizId);
        } catch (Exception e) {
            // Игнорируем ошибки при удалении файлов
        }
        quizRepository.deleteById(quizId);

        // Инвалидируем кэш
        evictQuizCache(quizId);
        leaderboardService.evict(quizId);

        return true;
    }

    public QuizResponseDTO updateQuiz(UpdateQuizRequest request) {
        Quiz quiz = quizRepository.findById(request.quizId())
                .orElseThrow(() -> new IllegalArgumentException("Квиз не найден"));

        if (!quizRepository.isCreator(request.quizId(), request.userId())) {
            throw new SecurityException("Только создатель может редактировать квиз");
        }

        // Сохраняем старые значения для проверки изменений
        String oldPrompt = quiz.getPrompt();
        boolean promptChanged = request.prompt() != null && 
                                (oldPrompt == null || !request.prompt().equals(oldPrompt));

        // Проверяем этичность нового промпта ДО обновления квиза, если промпт изменился
        if (promptChanged && request.prompt() != null && !request.prompt().trim().isEmpty()) {
            try {
                boolean isUnethical = fastApiClient.checkPromptEthics(request.prompt());
                if (isUnethical) {
                    throw new UnethicalPromptException("Данные для создания квиза являются неэтичными");
                }
            } catch (UnethicalPromptException e) {
                throw e;
            } catch (Exception e) {
                System.err.println("QuizService: Ошибка при проверке этичности промпта при обновлении: " + e.getMessage());
                throw new RuntimeException("Ошибка при проверке этичности промпта: " + e.getMessage(), e);
            }
        }

        if (request.name() != null) {
            quiz.setName(request.name());
        }
        if (request.prompt() != null) {
            quiz.setPrompt(request.prompt());
        }
        if (request.questionNumber() != null) {
            // Ограничиваем количество вопросов для сессии максимум 200 (так как в БД всего 200 вопросов)
            int questionNumber = Math.min(request.questionNumber(), 200);
            quiz.setQuestionNumber(questionNumber);
        }
        if (request.timeLimit() != null) {
            quiz.setTimePerQuestion(java.time.Duration.ofSeconds(request.timeLimit()));
        }
        if (request.isPrivate() != null) {
            quiz.setPrivate(request.isPrivate());
        }
        if (request.isStatic() != null) {
            quiz.setStatic(request.isStatic());
        }
        if (request.defaultQuestionType() != null) {
            quiz.setDefaultQuestionType(request.defaultQuestionType());
        }

        quiz = quizRepository.save(quiz);

        // Если промпт изменился, перегенерируем вопросы
        if (promptChanged) {
            try {
                // Временно генерируем меньше вопросов в базу данных при изменении промпта
                // questionNumber - это количество вопросов для сессии, а не для генерации
                int questionCountForGeneration = resolveGenerationCount(quiz.getQuestionNumber());

                // Сначала обнуляем ссылки на answer_options в user_answers, чтобы избежать foreign key constraint
                userAnswerRepository.nullifySelectedAnswerReferences(request.quizId());
                
                // Затем удаляем все ответы пользователей, связанные с вопросами этого квиза
                userAnswerRepository.deleteByQuestionQuizId(request.quizId());
                
                // Затем удаляем старые вопросы (каскадно удалятся и варианты ответов)
                questionRepository.deleteByQuizId(request.quizId());

                // Генерируем новые вопросы на основе нового промпта (временно 10 вопросов)
                org.example.dto.request.generation.QuestionGenerationRequest genRequest =
                        new org.example.dto.request.generation.QuestionGenerationRequest(
                                request.quizId(),
                                request.prompt(),
                                null, // materials
                                quiz.getQuestionNumber(), // Количество вопросов для сессии
                                questionCountForGeneration, // Временно генерируем 10 вопросов
                                resolveDefaultQuestionType(quiz.getDefaultQuestionType())
                        );
                questionGenerationService.generateQuizQuestionsKafka(genRequest);
            } catch (UnethicalPromptException e) {
                // Пробрасываем исключение для неэтичных промптов
                System.err.println("QuizService: Промпт не прошел проверку на этичность при обновлении: " + e.getMessage());
                throw e;
            } catch (Exception e) {
                System.err.println("Ошибка при перегенерации вопросов после изменения промпта: " + e.getMessage());
                e.printStackTrace();
                // Пробрасываем исключение, чтобы контроллер мог его обработать
                throw new RuntimeException("Ошибка при генерации вопросов: " + e.getMessage(), e);
            }
        }

        // Инвалидируем кэш квиза после обновления
        evictQuizCache(quiz.getId());

        return new QuizResponseDTO(
                quiz.getId(),
                quiz.getName(),
                "updated",
                toLocalDateTime(quiz.getCreatedAt()),
                String.valueOf(quiz.getId())
        );
    }

    public void regenerateWithMaterial(Long quizId, String materialText) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new IllegalArgumentException("Квиз не найден"));

        String prompt = quiz.getPrompt() == null ? "" : quiz.getPrompt().trim();
        String material = materialText == null ? "" : materialText.trim();
        // Склеиваем промпт + текст из файла
        String enrichedPrompt = prompt.isEmpty() ? material : (material.isEmpty() ? prompt : prompt + " " + material);

        if (enrichedPrompt.isBlank()) {
            throw new IllegalArgumentException("Нет текста для генерации");
        }

        // Удаляем старые вопросы ,сгенерированные только по промпту
        userAnswerRepository.nullifySelectedAnswerReferences(quizId);
        userAnswerRepository.deleteByQuestionQuizId(quizId);
        questionRepository.deleteByQuizId(quizId);

        QuestionGenerationRequest genRequest = new QuestionGenerationRequest(
                quizId, enrichedPrompt, null, null, resolveGenerationCount(quiz.getQuestionNumber()),
                resolveDefaultQuestionType(quiz.getDefaultQuestionType())
        );

        //Пока что синхронная генерация
        questionGenerationService.generateQuizQuestionsKafka(genRequest);

        // Инвалидируем кэш — вопросы изменились
        evictQuizCache(quizId);
    }

    public void regenerateWithMaterialFiles(Long quizId, List<String> fileUrls) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new IllegalArgumentException("Квиз не найден"));

        if (fileUrls == null || fileUrls.isEmpty()) {
            throw new IllegalArgumentException("Файлы материалов не найдены");
        }

        userAnswerRepository.nullifySelectedAnswerReferences(quizId);
        userAnswerRepository.deleteByQuestionQuizId(quizId);
        questionRepository.deleteByQuizId(quizId);

        List<org.example.dto.common.QuizMaterial> materials = fileUrls.stream()
                .map(url -> new org.example.dto.common.QuizMaterial(
                        url.substring(url.lastIndexOf('/') + 1),
                        url,
                        null,
                        null
                ))
                .collect(Collectors.toList());

        QuestionGenerationRequest genRequest = new QuestionGenerationRequest(
                quizId,
                quiz.getPrompt(),
                materials,
                quiz.getQuestionNumber(),
                resolveGenerationCount(quiz.getQuestionNumber()),
                resolveDefaultQuestionType(quiz.getDefaultQuestionType())
        );

        questionGenerationService.generateQuizQuestionsKafka(genRequest);
        evictQuizCache(quizId);
    }

    public void updateQuizMaterialUrl(Long quizId, String materialUrl) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new IllegalArgumentException("Квиз не найден"));
        
        quiz.setMaterialUrl(materialUrl);
        quiz.setHasMaterial(true);
        quizRepository.save(quiz);
    }

    public boolean removeQuestionFromQuiz(RemoveQuestionRequest request) {
        if (!quizRepository.existsById(request.quizId())) {
            throw new IllegalArgumentException("Квиз не найден");
        }

        if (!quizRepository.isCreator(request.quizId(), request.userId())) {
            throw new SecurityException("Только создатель может удалять вопросы");
        }

        org.example.model.Question question = questionRepository.findByIdAndQuizId(request.questionId(), request.quizId())
                .orElseThrow(() -> new IllegalArgumentException("Вопрос не найден"));

        questionRepository.delete(question);
        return true;
    }

    public LeaderboardDTO getQuizLeaderboard(Long quizId, Long userId) {
        if (!quizRepository.existsById(quizId)) {
            throw new IllegalArgumentException("Квиз не найден");
        }

        long completedUsersCount = attemptRepository.countCompletedUsersByQuizId(quizId);
        LeaderboardDTO redisLeaderboard = leaderboardService.getLeaderboard(quizId, userId);
        if (isCompleteCachedLeaderboard(redisLeaderboard, completedUsersCount)) {
            return redisLeaderboard;
        }

        Pageable pageable = PageRequest.of(0, 10000);
        Page<org.example.model.UserQuizAttempt> attempts = attemptRepository.findCompletedByQuizIdOrderByScoreDesc(quizId, pageable);

        Map<Long, org.example.model.UserQuizAttempt> bestAttemptsByUser = new HashMap<>();
        for (org.example.model.UserQuizAttempt attempt : attempts.getContent()) {
            if (attempt.getUser() == null) {
                continue;
            }
            Long attemptUserId = attempt.getUser().getId();
            org.example.model.UserQuizAttempt bestAttempt = bestAttemptsByUser.get(attemptUserId);
            if (bestAttempt == null || compareLeaderboardAttempts(attempt, bestAttempt) < 0) {
                bestAttemptsByUser.put(attemptUserId, attempt);
            }
        }

        List<org.example.model.UserQuizAttempt> sortedAttempts = new ArrayList<>(bestAttemptsByUser.values());
        sortedAttempts.sort(this::compareLeaderboardAttempts);

        List<LeaderboardEntry> entries = new ArrayList<>();
        List<LeaderboardService.CachedLeaderboardEntry> cacheEntries = new ArrayList<>();
        int userPosition = -1;
        Long userScore = null;

        int limit = Math.min(sortedAttempts.size(), 100);
        for (int i = 0; i < limit; i++) {
            org.example.model.UserQuizAttempt attempt = sortedAttempts.get(i);
            long timeSpent = getTimeSpent(attempt);
            Integer accuracyPercent = getAccuracyPercent(attempt);
            Long leaderboardScore = getLeaderboardScore(attempt);
            Long points = getAttemptPoints(attempt);

            entries.add(new LeaderboardEntry(
                    i + 1,
                    attempt.getUser().getLogin(),
                    leaderboardScore.intValue(),
                    points.intValue(),
                    timeSpent,
                    accuracyPercent
            ));
            cacheEntries.add(new LeaderboardService.CachedLeaderboardEntry(
                    attempt.getUser().getId(),
                    attempt.getUser().getLogin(),
                    leaderboardScore,
                    points,
                    timeSpent,
                    accuracyPercent
            ));

            if (attempt.getUser().getId().equals(userId)) {
                userPosition = i + 1;
                userScore = points;
            }
        }

        leaderboardService.replaceLeaderboard(quizId, cacheEntries);

        return new LeaderboardDTO(entries, userPosition, userScore != null ? userScore.intValue() : null);
    }

    private boolean isCompleteCachedLeaderboard(LeaderboardDTO leaderboard, long completedUsersCount) {
        if (leaderboard == null || leaderboard.entries() == null) {
            return false;
        }
        long expectedEntries = Math.min(completedUsersCount, 100);
        return leaderboard.entries().size() >= expectedEntries;
    }

    private Long getLeaderboardScore(org.example.model.UserQuizAttempt attempt) {
        return attempt.getScore() != null ? attempt.getScore() : 0L;
    }

    private Long getAttemptPoints(org.example.model.UserQuizAttempt attempt) {
        if (attempt.getBaseScore() != null) {
            return attempt.getBaseScore();
        }
        return attempt.getScore() != null ? attempt.getScore() : 0L;
    }

    private Integer getAccuracyPercent(org.example.model.UserQuizAttempt attempt) {
        return attempt.getAccuracyPercent() != null ? attempt.getAccuracyPercent() : 0;
    }

    private long getTimeSpent(org.example.model.UserQuizAttempt attempt) {
        if (attempt.getStartTime() != null && attempt.getFinishTime() != null) {
            return java.time.Duration.between(attempt.getStartTime(), attempt.getFinishTime()).getSeconds();
        }
        return 0;
    }

    private int compareLeaderboardAttempts(org.example.model.UserQuizAttempt a, org.example.model.UserQuizAttempt b) {
        int pointsCompare = getAttemptPoints(b).compareTo(getAttemptPoints(a));
        if (pointsCompare != 0) {
            return pointsCompare;
        }

        int scoreCompare = getLeaderboardScore(b).compareTo(getLeaderboardScore(a));
        if (scoreCompare != 0) {
            return scoreCompare;
        }

        int accuracyCompare = getAccuracyPercent(b).compareTo(getAccuracyPercent(a));
        if (accuracyCompare != 0) {
            return accuracyCompare;
        }

        return Long.compare(getTimeSpent(a), getTimeSpent(b));
    }

    public QuizResponseDTO copyQuiz(Long quizId, Long newCreatorId) {
        Quiz originalQuiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new IllegalArgumentException("Квиз не найден"));

        User newCreator = userRepository.findById(newCreatorId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));

        Quiz copiedQuiz = new Quiz();
        copiedQuiz.setName(originalQuiz.getName() + " (копия)");
        copiedQuiz.setPrompt(originalQuiz.getPrompt());
        copiedQuiz.setCreatedBy(newCreator);
        copiedQuiz.setHasMaterial(originalQuiz.isHasMaterial());
        copiedQuiz.setMaterialUrl(originalQuiz.getMaterialUrl());
        copiedQuiz.setQuestionNumber(originalQuiz.getQuestionNumber());
        copiedQuiz.setTimePerQuestion(originalQuiz.getTimePerQuestion());
        copiedQuiz.setPrivate(originalQuiz.isPrivate());
        copiedQuiz.setStatic(originalQuiz.isStatic());
        copiedQuiz.setDefaultQuestionType(originalQuiz.getDefaultQuestionType());
        copiedQuiz.setCreatedAt(Instant.now());

        copiedQuiz = quizRepository.save(copiedQuiz);

        return new QuizResponseDTO(
                copiedQuiz.getId(),
                copiedQuiz.getName(),
                "copied",
                toLocalDateTime(copiedQuiz.getCreatedAt()),
                String.valueOf(copiedQuiz.getId())
        );
    }

    public QuizDetailsDTO getQuizById(Long quizId, Long userId) {
        return getQuiz(quizId, userId);
    }

    private Pageable createPageable(Integer page, Integer size, String sortBy, Boolean ascending) {
        int pageNumber = page != null && page >= 0 ? page : 0;
        int pageSize = size != null && size > 0 ? size : 10;
        
        Sort.Direction direction = (ascending != null && ascending) ? 
                Sort.Direction.ASC : Sort.Direction.DESC;
        
        // Маппинг полей сортировки на реальные поля в Quiz
        String actualSortField = "createdAt"; // по умолчанию
        if (sortBy != null) {
            switch (sortBy.toLowerCase()) {
                case "name":
                    actualSortField = "name";
                    break;
                case "created":
                case "created_at":
                case "createdat":
                    actualSortField = "createdAt";
                    break;
                case "popularity":
                    // Поля popularity нет, используем createdAt
                    actualSortField = "createdAt";
                    break;
                default:
                    actualSortField = "createdAt";
            }
        }
        
        Sort sort = Sort.by(direction, actualSortField);
        return PageRequest.of(pageNumber, pageSize, sort);
    }

    private QuizDTO toQuizDTO(Quiz quiz) {
        int questionCount = quiz.getQuestionNumber() != null ? quiz.getQuestionNumber() : 0;
        
        // Вычисляем общее время на весь квиз в секундах (время на вопрос * количество вопросов)
        Integer totalTimeSeconds = null;
        Integer timePerQuestionSeconds = null;
        if (quiz.getTimePerQuestion() != null && quiz.getTimePerQuestion().getSeconds() > 0) {
            long secondsPerQuestion = quiz.getTimePerQuestion().getSeconds();
            timePerQuestionSeconds = (int) secondsPerQuestion;
            if (questionCount > 0) {
                totalTimeSeconds = (int) (secondsPerQuestion * questionCount);
            }
        }
        
        return new QuizDTO(
                quiz.getId(),
                quiz.getName(),
                quiz.getCreatedBy().getLogin(),
                questionCount,
                totalTimeSeconds,
                timePerQuestionSeconds,
                !quiz.isPrivate(),
                quiz.isStatic(),
                toLocalDateTime(quiz.getCreatedAt())
        );
    }

    private QuestionDTO toQuestionDTO(org.example.model.Question question) {
        List<org.example.model.AnswerOption> options = answerOptionRepository.findByQuestionId(question.getId());
        List<org.example.dto.common.AnswerOption> dtoOptions = options.stream()
                .map(opt -> new org.example.dto.common.AnswerOption(opt.getId(), opt.getText(), opt.getNominal()))
                .collect(Collectors.toList());

        Integer timeLimit = null;
        if (question.getQuiz().getTimePerQuestion() != null) {
            timeLimit = (int) question.getQuiz().getTimePerQuestion().getSeconds();
        }

        return new QuestionDTO(
                question.getId(),
                question.getText(),
                dtoOptions,
                question.getType(),
                timeLimit,
                null,
                question.getExplanation(),
                null,
                null,
                0,
                toLocalDateTime(question.getQuiz().getCreatedAt())
        );
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant != null
                ? LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                : null;
    }

    private void evictQuizCache(Long quizId) {
        try {
            redisTemplate.delete(String.format(QUIZ_CACHE_KEY, quizId));
        } catch (Exception e) {
            System.err.println("QuizService: ошибка инвалидации кэша квиза: " + e.getMessage());
        }
    }
}
