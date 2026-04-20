package org.example.service;

import org.example.dto.common.AnswerOption;
import org.example.dto.request.attempt.StartAttemptRequest;
import org.example.dto.request.attempt.SubmitAnswerRequest;
import org.example.dto.response.attempt.AnswerResponse;
import org.example.dto.response.attempt.AttemptPageProgress;
import org.example.dto.response.attempt.AttemptResponse;
import org.example.dto.response.attempt.QuizResultDTO;
import org.example.dto.response.quiz.QuestionDTO;
import org.example.model.*;
import org.example.repository.*;
import org.example.metrics.MetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Сервис для работы с попытками прохождения квизов.
 * Путь: src/main/java/org/example/service/AttemptService.java
 */
@Service
@Transactional
public class AttemptService {
  private final Map<Long, AttemptState> attemptStates = new ConcurrentHashMap<>();
    private final UserQuizAttemptRepository attemptRepository;
    private final QuizRepository quizRepository;
    private final UserRepository userRepository;
    private final QuestionRepository questionRepository;
    private final AnswerOptionRepository answerOptionRepository;
    private final UserAnswerRepository userAnswerRepository;
    private final org.example.repository.MultiplayerSessionRepository multiplayerSessionRepository;
    private final org.example.repository.AttemptQuestionRepository attemptQuestionRepository;
    private final LeaderboardService leaderboardService;
    private final MetricsService metricsService;

    @Autowired
    public AttemptService(
            UserQuizAttemptRepository attemptRepository,
            QuizRepository quizRepository,
            UserRepository userRepository,
            QuestionRepository questionRepository,
            AnswerOptionRepository answerOptionRepository,
            UserAnswerRepository userAnswerRepository,
            org.example.repository.MultiplayerSessionRepository multiplayerSessionRepository,
            org.example.repository.AttemptQuestionRepository attemptQuestionRepository,
            LeaderboardService leaderboardService,
            MetricsService metricsService) {
        this.attemptRepository = attemptRepository;
        this.quizRepository = quizRepository;
        this.userRepository = userRepository;
        this.questionRepository = questionRepository;
        this.answerOptionRepository = answerOptionRepository;
        this.userAnswerRepository = userAnswerRepository;
        this.multiplayerSessionRepository = multiplayerSessionRepository;
        this.attemptQuestionRepository = attemptQuestionRepository;
        this.leaderboardService = leaderboardService;
        this.metricsService = metricsService;
    }

    /**
     * Внутренний класс для отслеживания состояния попыток в памяти
     */
    private static class AttemptState {
        Long attemptId;
        Long userId;
        Long quizId;
        List<Long> questionIds;
        int currentQuestionIndex;
        Map<Long, Long> answers;
        Map<Long, Boolean> answerResults;
        Instant startTime;
        double score;

        // Для вопроса типа HUNDRED_TO_ONE: questionId -> (answerOptionId -> nominal)
        Map<Long, Map<Long, BigDecimal>> hundredToOneNominalsByQuestionId;

        // «Кот в мешке»: индекс вопроса в попытке (0-based), null если не используется
        Integer catQuestionIndex;
        // Ставка на текущий вопрос «Кот в мешке», null если ставка ещё не сделана или вопрос не «Кот»
        Integer stakeForCurrentQuestion;
    }

    /**
     * Начинает попытку прохождения квиза.
     * Создает UserQuizAttempt и возвращает первый вопрос.
     * Если передан sessionId, использует существующую попытку для мультиплеера.
     */
    public AttemptResponse startQuizAttempt(StartAttemptRequest request) {
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> {
                    System.err.println("AttemptService: Пользователь не найден: " + request.userId());
                    return new IllegalArgumentException("Пользователь не найден");
                });

        Quiz quiz = quizRepository.findById(request.quizId())
                .orElseThrow(() -> {
                    System.err.println("AttemptService: Квиз не найден: " + request.quizId());
                    return new IllegalArgumentException("Квиз не найден");
                });

        // Проверяем доступ к приватному квизу: разрешаем доступ создателю
        if (quiz.isPrivate()) {
            // Проверяем, является ли пользователь создателем квиза
            if (!quizRepository.isCreator(request.quizId(), request.userId())) {
                System.err.println("AttemptService: Попытка доступа к приватному квизу. UserId: " + request.userId() + ", QuizId: " + request.quizId());
                throw new SecurityException("Доступ к приватному квизу запрещен");
            }
            System.out.println("AttemptService: Разрешен доступ к приватному квизу для создателя. UserId: " + request.userId() + ", QuizId: " + request.quizId());
        }

        List<Question> allQuestions = questionRepository.findByQuizId(request.quizId());
        
        if (allQuestions.isEmpty()) {
            System.err.println("AttemptService: Квиз ID " + request.quizId() + " не содержит вопросов!");
            throw new IllegalStateException("Квиз не содержит вопросов");
        }

        UserQuizAttempt attempt;
        Integer resolvedCatQuestionIndex = request.catQuestionIndex();
        
        // Если передан sessionId, ищем существующую попытку для мультиплеера
        if (request.sessionId() != null && !request.sessionId().isEmpty()) {
            attempt = attemptRepository.findTopByUserIdAndQuizIdAndSessionIdOrderByIdDesc(
                    request.userId(), request.quizId(), request.sessionId());
            
            if (attempt == null) {
                System.err.println("AttemptService: Попытка с sessionId не найдена, создаем новую");
                attempt = new UserQuizAttempt();
                attempt.setUser(user);
                attempt.setQuiz(quiz);
                attempt.setStartTime(Instant.now());
                attempt.setCompleted(false);
                attempt.setScore(null);
                attempt.setSessionId(request.sessionId());
                attempt = attemptRepository.save(attempt);
                
                // Выбираем вопросы для новой попытки
                selectQuestionsForAttempt(attempt, quiz, allQuestions);
            } else {
                System.err.println("AttemptService: Используем существующую попытку ID " + attempt.getId() + " для мультиплеера");
                // Проверяем, есть ли уже выбранные вопросы для этой попытки
                if (attemptQuestionRepository.findByAttemptIdOrderByQuestionOrder(attempt.getId()).isEmpty()) {
                    selectQuestionsForAttempt(attempt, quiz, allQuestions);
                }

                // Для уже завершенной попытки не запускаем повторно:
                // контроллер обработает маркер и отправит на экран завершения.
                if (attempt.isCompleted()) {
                    throw new IllegalStateException("ATTEMPT_COMPLETED:" + attempt.getId());
                }
            }
        } else {
            attempt = new UserQuizAttempt();
            attempt.setUser(user);
            attempt.setQuiz(quiz);
            attempt.setStartTime(Instant.now());
            attempt.setCompleted(false);
            attempt.setScore(null);
            attempt.setSessionId(null);
            attempt = attemptRepository.save(attempt);
            
            // Выбираем вопросы для новой попытки
            selectQuestionsForAttempt(attempt, quiz, allQuestions);
        }

        // Для мультиплеера выбираем и фиксируем "кота в мешке" на уровне сессии,
        // когда вопросы уже выбраны для попытки.
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            var sessionOpt = multiplayerSessionRepository.findBySessionId(request.sessionId());
            if (sessionOpt.isPresent()) {
                var session = sessionOpt.get();
                if (session.getCatQuestionIndex() != null) {
                    resolvedCatQuestionIndex = session.getCatQuestionIndex();
                } else {
                    int totalQuestions = attemptQuestionRepository
                            .findByAttemptIdOrderByQuestionOrder(attempt.getId())
                            .size();
                    if (totalQuestions > 0) {
                        int lastSegmentStart = Math.max(0, totalQuestions - Math.max(1, totalQuestions / 3));
                        int catIndex = lastSegmentStart + new Random().nextInt(totalQuestions - lastSegmentStart);
                        session.setCatQuestionIndex(catIndex);
                        multiplayerSessionRepository.save(session);
                        resolvedCatQuestionIndex = catIndex;
                    }
                }
            }
        }

        // Инициализируем состояние попытки в памяти (используется для HUNDRED_TO_ONE и «Кот в мешке»)
        AttemptState existingState = attemptStates.get(attempt.getId());
        if (existingState == null) {
            AttemptState st = new AttemptState();
            st.attemptId = attempt.getId();
            st.userId = request.userId();
            st.quizId = quiz.getId();
            st.hundredToOneNominalsByQuestionId = new java.util.concurrent.ConcurrentHashMap<>();
            st.score = 0.0;
            st.catQuestionIndex = resolvedCatQuestionIndex;
            st.stakeForCurrentQuestion = null;
            attemptStates.put(attempt.getId(), st);
        } else {
            if (resolvedCatQuestionIndex != null) {
                existingState.catQuestionIndex = resolvedCatQuestionIndex;
            }
        }

        // Получаем текущий вопрос (первый неотвеченный)
        QuestionDTO currentQuestion = getNextQuestion(attempt.getId());
        if (currentQuestion == null) {
            // Явный маркер для контроллера: попытка исчерпана, нужно вести на finish/results.
            throw new IllegalStateException("ATTEMPT_COMPLETED:" + attempt.getId());
        }

        // Получаем количество выбранных вопросов для этой попытки
        List<AttemptQuestion> attemptQuestions = attemptQuestionRepository.findByAttemptIdOrderByQuestionOrder(attempt.getId());
        int totalQuestions = attemptQuestions.size();
        List<UserAnswer> answeredQuestions = userAnswerRepository.findByAttemptId(attempt.getId());
        int questionsRemaining = totalQuestions - answeredQuestions.size();

        Integer timeRemaining = quiz.getTimePerQuestion() != null
                ? (int) quiz.getTimePerQuestion().getSeconds()
                : 60;

        return new AttemptResponse(
                attempt.getId(),
                quiz.getId(),
                quiz.getName(),
                currentQuestion,
                questionsRemaining,
                totalQuestions,
                timeRemaining
        );
  }

    /**
     * Получает следующий вопрос для попытки.
     * Возвращает null, если все вопросы отвечены.
     */
    public QuestionDTO getNextQuestion(Long attemptId) {
        // 1. Получаем попытку
        UserQuizAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Попытка не найдена"));

        if (attempt.isCompleted()) {
            throw new IllegalStateException("Попытка уже завершена");
        }

        // 2. Получаем выбранные вопросы для этой попытки (в порядке questionOrder)
        List<AttemptQuestion> attemptQuestions = attemptQuestionRepository.findByAttemptIdOrderByQuestionOrder(attemptId);
        
        if (attemptQuestions.isEmpty()) {
            // Если вопросов нет, возвращаем null (не должно происходить, но на всякий случай)
            return null;
        }

        // 3. Получаем уже отвеченные вопросы
        List<UserAnswer> answeredQuestions = userAnswerRepository.findByAttemptId(attemptId);
        List<Long> answeredQuestionIds = answeredQuestions.stream()
                .map(answer -> {
                    Question q = answer.getQuestion();
                    return q != null ? q.getId() : null;
                })
                .filter(id -> id != null)
                .collect(Collectors.toList());

        // 4. Находим первый неотвеченный вопрос и его индекс в попытке
        Question nextQuestion = null;
        int nextQuestionIndex = -1;
        for (int i = 0; i < attemptQuestions.size(); i++) {
            Question q = attemptQuestions.get(i).getQuestion();
            if (q != null && !answeredQuestionIds.contains(q.getId())) {
                nextQuestion = q;
                nextQuestionIndex = i;
                break;
            }
        }

        if (nextQuestion == null) {
            return null; // Все вопросы отвечены
        }

        // 5. Проверяем, является ли вопрос «Котом в мешке» и нужен ли экран ставки
        AttemptState st = attemptStates.get(attemptId);
        if (st != null && st.catQuestionIndex != null
                && nextQuestionIndex == st.catQuestionIndex
                && st.stakeForCurrentQuestion == null) {
            Integer timeLimit = null;
            Quiz quiz = nextQuestion.getQuiz();
            if (quiz != null && quiz.getTimePerQuestion() != null) {
                timeLimit = (int) quiz.getTimePerQuestion().getSeconds();
            }
            return new QuestionDTO(
                    nextQuestion.getId(),
                    null,
                    List.of(),
                    nextQuestion.getType(),
                    timeLimit,
                    null, null, null, null, 0,
                    quiz != null ? toLocalDateTime(quiz.getCreatedAt()) : null,
                    true
            );
        }

        return toQuestionDTO(attemptId, nextQuestion);
    }

    /**
     * Прогресс попытки для страницы /quiz/attempt/{id}/question
     */
    public AttemptPageProgress getAttemptPageProgress(Long attemptId) {
        UserQuizAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Попытка не найдена"));
        if (attempt.isCompleted()) {
            throw new IllegalStateException("Попытка уже завершена");
        }
        List<AttemptQuestion> attemptQuestions =
                attemptQuestionRepository.findByAttemptIdOrderByQuestionOrder(attemptId);
        int total = attemptQuestions.size();
        int answered = userAnswerRepository.findByAttemptId(attemptId).size();
        int remaining = Math.max(0, total - answered);
        Quiz quiz = attempt.getQuiz();
        int seconds = quiz.getTimePerQuestion() != null
                ? (int) quiz.getTimePerQuestion().getSeconds()
                : 60;
        return new AttemptPageProgress(total, remaining, seconds);
    }

    /**
     * Отправляет ответ на вопрос.
     * Обрабатывает как обычные ответы, так и таймауты (selectedAnswerId = null).
     */
    public AnswerResponse submitAnswer(SubmitAnswerRequest request) {
        // 1. Получаем попытку
        UserQuizAttempt attempt = attemptRepository.findById(request.attemptId())
                .orElseThrow(() -> new IllegalArgumentException("Попытка не найдена"));

        if (attempt.isCompleted()) {
            throw new IllegalStateException("Попытка уже завершена");
        }

        // 2. Получаем вопрос (из запроса или из текущего состояния попытки)
        Long questionId = request.questionId();
        if (questionId == null) {
            // Если questionId не передан, получаем текущий неотвеченный вопрос
            QuestionDTO currentQuestion = getNextQuestion(request.attemptId());
            if (currentQuestion == null) {
                throw new IllegalStateException("Нет активных вопросов");
            }
            questionId = currentQuestion.id();
        }

        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new IllegalArgumentException("Вопрос не найден"));

        // 3. Проверяем, что вопрос принадлежит квизу попытки
        if (!question.getQuiz().getId().equals(attempt.getQuiz().getId())) {
            throw new IllegalArgumentException("Вопрос не принадлежит этому квизу");
        }

        // 4. Проверяем, что вопрос еще не отвечен
        if (userAnswerRepository.existsByAttemptIdAndQuestionId(request.attemptId(), questionId)) {
            throw new IllegalStateException("Вопрос уже отвечен");
        }

        // 4.1. Блокируем ответ на вопрос «Кот в мешке» без ставки
        AttemptState catCheck = attemptStates.get(request.attemptId());
        if (catCheck != null && catCheck.catQuestionIndex != null && catCheck.stakeForCurrentQuestion == null) {
            int qIdx = getQuestionIndexInAttempt(request.attemptId(), questionId);
            if (qIdx == catCheck.catQuestionIndex) {
                throw new IllegalStateException("Сначала необходимо сделать ставку (submitStake)");
            }
        }

        // 5. Обрабатываем ответ (может быть null при таймауте)
        List<Long> selectedIds = request.getEffectiveSelectedIds();
        Boolean isCorrect = null;
        Long correctAnswerId = null;
        int scoreEarned = 0;
        double scoreEarnedDouble = 0.0;
        org.example.model.AnswerOption selectedOption = null;

        // Получаем все правильные варианты для вопроса
        List<org.example.model.AnswerOption> allOptions = answerOptionRepository.findByQuestionId(questionId);
        java.util.Set<Long> correctIds = allOptions.stream()
                .filter(org.example.model.AnswerOption::isCorrect)
                .map(org.example.model.AnswerOption::getId)
                .collect(java.util.stream.Collectors.toSet());
        correctAnswerId = correctIds.isEmpty() ? null : correctIds.iterator().next();

        if (!selectedIds.isEmpty()) {
            // Берём первый выбранный для сохранения в UserAnswer (обратная совместимость)
            selectedOption = answerOptionRepository.findById(selectedIds.get(0)).orElse(null);

            if (question.getType() == QuestionType.MULTIPLE_CHOICE) {
                int A = (int) selectedIds.stream().filter(correctIds::contains).count();
                int B = (int) selectedIds.stream().filter(id -> !correctIds.contains(id)).count();
                int C = correctIds.size();
                double points = C > 0 ? 2.0 * Math.max(A - B, 0) / C : 0;
                scoreEarned = (int) Math.round(points);
                scoreEarnedDouble = points;
                isCorrect = points > 0;
            } else if (question.getType() == QuestionType.HUNDRED_TO_ONE) {
                java.util.Map<Long, BigDecimal> nominals = ensureHundredToOneNominals(request.attemptId(), question);
                BigDecimal sum = BigDecimal.ZERO;
                for (Long selectedId : selectedIds) {
                    if (selectedId == null) continue;
                    BigDecimal nominal = nominals.get(selectedId);
                    if (nominal != null) {
                        sum = sum.add(nominal);
                    }
                }
                scoreEarned = sum.setScale(0, RoundingMode.HALF_UP).intValue();
                scoreEarnedDouble = sum.doubleValue();
                // В текущей логике isCorrect используется только для UI-подсветки.
                // Для 100к1 считаем "корректно" если итоговый балл положительный.
                isCorrect = scoreEarned > 0;
            } else {
                // SINGLE_CHOICE и прочие
                isCorrect = selectedIds.size() == 1 && correctIds.contains(selectedIds.get(0));
                scoreEarned = isCorrect ? calculateScore(question, attempt) : 0;
                scoreEarnedDouble = (double) scoreEarned;
            }
        } else {
            // ТАЙМАУТ: ничего не выбрано
            isCorrect = false;
            scoreEarned = 0;
            scoreEarnedDouble = 0.0;
        }

        if (Boolean.TRUE.equals(isCorrect)) {
            metricsService.recordCorrectAnswer();
        } else {
            metricsService.recordIncorrectAnswer();
        }

        UserAnswer userAnswer = new UserAnswer();
        userAnswer.setAttempt(attempt);
        userAnswer.setQuestion(question);
        userAnswer.setSelectedAnswer(selectedOption);
        userAnswer.setIsCorrect(isCorrect);
        userAnswer.setAnsweredAt(Instant.now());
        userAnswer.setTimeSpentSeconds(null);
        userAnswerRepository.save(userAnswer);

        // 7. Накопление счета в памяти (для 100к1 нужны дробные номиналы).
        AttemptState st = attemptStates.get(request.attemptId());
        if (st != null) {
            st.score += scoreEarnedDouble;

            // 7.1. «Кот в мешке»: при ответе на вопрос кота применяем ±ставку
            if (st.catQuestionIndex != null && st.stakeForCurrentQuestion != null) {
                int qIndex = getQuestionIndexInAttempt(request.attemptId(), questionId);
                if (qIndex == st.catQuestionIndex) {
                    int stake = st.stakeForCurrentQuestion;
                    if (Boolean.TRUE.equals(isCorrect)) {
                        st.score += stake;
                        scoreEarned += stake;
                    } else {
                        st.score -= stake;
                        scoreEarned -= stake;
                    }
                    st.stakeForCurrentQuestion = null;
                }
            }
        } else {
            // fallback на старую логику, если state не был инициализирован
            Long currentScore = attempt.getScore() != null ? attempt.getScore() : 0L;
            attempt.setScore(currentScore + scoreEarned);
            attemptRepository.save(attempt);
        }

        // 8. Получаем следующий вопрос
        QuestionDTO nextQuestion = getNextQuestion(request.attemptId());

        // 9. Формируем ответ
        String explanation = question.getExplanation() != null
                ? question.getExplanation()
                : "Объяснение отсутствует";
        
        System.out.println("AttemptService: Возвращаем объяснение для вопроса ID " + questionId + 
                ": " + (explanation.length() > 50 ? explanation.substring(0, 50) + "..." : explanation));

        java.util.List<Long> correctAnswerIds = correctIds.isEmpty()
                ? java.util.List.of()
                : correctIds.stream().sorted().toList();

        return new AnswerResponse(
                isCorrect,
                explanation,
                correctAnswerId,
                correctAnswerIds,
                scoreEarned,
                nextQuestion,
                attempt.getQuiz().getId()
        );
    }

    /**
     * Завершает попытку прохождения квиза.
     * Рассчитывает итоговый счет и обновляет рейтинг.
     */
    public QuizResultDTO finishQuizAttempt(Long attemptId) {
        // 1. Получаем попытку
        UserQuizAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Попытка не найдена"));

        if (attempt.isCompleted()) {
            // Идемпотентность: если finish вызван повторно, возвращаем уже сохраненный результат.
            List<AttemptQuestion> attemptQuestions = attemptQuestionRepository.findByAttemptIdOrderByQuestionOrder(attemptId);
            int totalQuestions = attemptQuestions.size();
            int correctAnswers = (int) userAnswerRepository.countByAttemptIdAndIsCorrectTrue(attemptId);
            int finalScore = attempt.getScore() != null ? attempt.getScore().intValue() : 0;

            long timeSpent = 0;
            if (attempt.getStartTime() != null && attempt.getFinishTime() != null) {
                timeSpent = java.time.Duration.between(attempt.getStartTime(), attempt.getFinishTime()).getSeconds();
            }

            int position = calculatePosition(attempt.getQuiz().getId(), attempt.getUser().getId(), finalScore, timeSpent);
            return new QuizResultDTO(
                    attemptId,
                    finalScore,
                    correctAnswers,
                    totalQuestions,
                    position,
                    timeSpent,
                    toLocalDateTime(attempt.getFinishTime())
            );
        }

        attempt.setCompleted(true);
        attempt.setFinishTime(Instant.now());
        attempt = attemptRepository.save(attempt);

        // 2.5. Если это мультиплеер, проверяем завершение сессии
        if (attempt.getSessionId() != null && !attempt.getSessionId().isEmpty()) {
            checkAndFinishMultiplayerSession(attempt.getSessionId());
        }

        // 3. Получаем все ответы
        List<UserAnswer> answers = userAnswerRepository.findByAttemptId(attemptId);

        // 4. Подсчитываем статистику
        // Используем количество выбранных вопросов для попытки, а не всех вопросов квиза
        List<AttemptQuestion> attemptQuestions = attemptQuestionRepository.findByAttemptIdOrderByQuestionOrder(attemptId);
        int totalQuestions = attemptQuestions.size();
        int correctAnswers = (int) userAnswerRepository.countByAttemptIdAndIsCorrectTrue(attemptId);
        // Для HUNDRED_TO_ONE финальный счёт должен считаться из накопленного double в AttemptState.
        // Для остальных типов допускаем fallback на attempt.getScore().
        AttemptState st = attemptStates.get(attemptId);
        double rawScore = st != null
                ? st.score
                : (attempt.getScore() != null ? attempt.getScore().doubleValue() : 0.0);
        long finalScoreLong = Math.round(rawScore);

        attempt.setScore(finalScoreLong);
        attempt = attemptRepository.save(attempt);
        int finalScore = (int) finalScoreLong;

        // 5. Вычисляем время прохождения
        long timeSpent = 0;
        if (attempt.getStartTime() != null && attempt.getFinishTime() != null) {
            timeSpent = java.time.Duration.between(attempt.getStartTime(), attempt.getFinishTime()).getSeconds();
        }

        // 6. Обновляем лидерборд в Redis
        leaderboardService.updateLeaderboard(
                attempt.getQuiz().getId(),
                attempt.getUser().getId(),
                attempt.getUser().getLogin(),
                finalScore,
                timeSpent
        );

        // 7. Вычисляем позицию в рейтинге (по лучшим попыткам каждого пользователя)
        int position = calculatePosition(attempt.getQuiz().getId(), attempt.getUser().getId(), finalScore, timeSpent);

        if (attempt.getStartTime() != null && attempt.getFinishTime() != null) {
            long durationNanos = java.time.Duration.between(attempt.getStartTime(), attempt.getFinishTime()).toNanos();
            metricsService.getAttemptDurationTimer().record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        }

        return new QuizResultDTO(
                attemptId,
                finalScore,
                correctAnswers,
                totalQuestions,
                position,
                timeSpent,
                toLocalDateTime(attempt.getFinishTime())
        );
    }

    private void checkAndFinishMultiplayerSession(String sessionId) {
        try {
            List<UserQuizAttempt> attempts = attemptRepository.findBySessionId(sessionId);
            boolean allCompleted = attempts.stream().allMatch(UserQuizAttempt::isCompleted);
            
            if (allCompleted && attempts.size() >= 2) {
                org.example.model.MultiplayerSession session = multiplayerSessionRepository.findBySessionId(sessionId)
                    .orElse(null);
                
                if (session != null && !"FINISHED".equals(session.getStatus())) {
                    session.setStatus("FINISHED");
                    session.setFinishedAt(Instant.now());
                    multiplayerSessionRepository.save(session);
                }
            }
        } catch (Exception e) {
            System.err.println("AttemptService: Ошибка при проверке завершения мультиплеер сессии: " + e.getMessage());
        }
    }

    /**
     * Устанавливает catQuestionIndex в AttemptState.
     * Вызывается из MultiplayerService при старте сессии, чтобы у всех участников
     * был одинаковый индекс «Кота в мешке».
     */
    public void setCatQuestionIndex(Long attemptId, Integer catQuestionIndex) {
        AttemptState st = getOrCreateAttemptState(attemptId);
        st.catQuestionIndex = catQuestionIndex;
        st.stakeForCurrentQuestion = null;
    }

    /**
     * Возвращает текущий накопленный счёт попытки (из in-memory state или из БД).
     */
    public double getCurrentScore(Long attemptId) {
        AttemptState st = attemptStates.get(attemptId);
        if (st != null) {
            return st.score;
        }
        UserQuizAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Попытка не найдена"));
        return attempt.getScore() != null ? attempt.getScore().doubleValue() : 0.0;
    }

    /**
     * Принимает ставку для вопроса «Кот в мешке».
     * Валидация: stake >= 0, stake <= текущий счёт; при счёте <= 0 допускается только 0.
     * После принятия ставки повторный вызов getNextQuestion вернёт сам вопрос (без экрана ставки).
     *
     * @return QuestionDTO — раскрытый вопрос «Кот в мешке» для немедленной отдачи клиенту
     */
    public QuestionDTO submitStake(org.example.dto.request.attempt.SubmitStakeRequest request) {
        UserQuizAttempt attempt = attemptRepository.findById(request.attemptId())
                .orElseThrow(() -> new IllegalArgumentException("Попытка не найдена"));

        if (attempt.isCompleted()) {
            throw new IllegalStateException("Попытка уже завершена");
        }

        AttemptState st = getOrCreateAttemptState(request.attemptId());
        if (st.catQuestionIndex == null) {
            throw new IllegalStateException("В данной попытке нет вопроса «Кот в мешке»");
        }
        if (st.stakeForCurrentQuestion != null) {
            throw new IllegalStateException("Ставка уже сделана");
        }

        // Проверяем, что игрок действительно находится на вопросе «Кот в мешке»
        List<AttemptQuestion> aq = attemptQuestionRepository.findByAttemptIdOrderByQuestionOrder(request.attemptId());
        List<Long> answeredIds = userAnswerRepository.findByAttemptId(request.attemptId()).stream()
                .map(a -> a.getQuestion() != null ? a.getQuestion().getId() : null)
                .filter(id -> id != null)
                .collect(java.util.stream.Collectors.toList());
        int currentIndex = -1;
        for (int i = 0; i < aq.size(); i++) {
            Question q = aq.get(i).getQuestion();
            if (q != null && !answeredIds.contains(q.getId())) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex != st.catQuestionIndex) {
            throw new IllegalStateException("Ставку можно делать только на вопросе «Кот в мешке»");
        }

        int stake = request.stake() != null ? request.stake() : 0;
        double currentScore = getCurrentScore(request.attemptId());
        int maxStake = (int) Math.floor(currentScore);

        if (stake < 0) {
            throw new IllegalArgumentException("Ставка не может быть отрицательной");
        }
        if (currentScore <= 0 && stake != 0) {
            throw new IllegalArgumentException("При текущем счёте <= 0 допускается только ставка 0");
        }
        if (stake > maxStake) {
            throw new IllegalArgumentException("Ставка не может превышать текущий счёт (" + maxStake + ")");
        }

        st.stakeForCurrentQuestion = stake;

        return getNextQuestion(request.attemptId());
    }

    // ========== Вспомогательные методы ==========

    private Question getFirstQuestion(List<Question> questions) {
        List<Question> validQuestions = questions.stream()
                .filter(q -> q != null 
                        && q.getText() != null 
                        && !q.getText().trim().isEmpty()
                        && (q.getIsValid() == null || q.getIsValid())
                        && (q.getIsDuplicate() == null || !q.getIsDuplicate()))
                .collect(Collectors.toList());
        
        if (validQuestions.isEmpty()) {
            validQuestions = questions.stream()
                    .filter(q -> q != null && q.getText() != null && !q.getText().trim().isEmpty())
                    .collect(Collectors.toList());
        }
        
        if (validQuestions.isEmpty()) {
            throw new IllegalStateException("Нет вопросов с текстом для отображения");
        }
        
        return validQuestions.get(0);
    }

    private QuestionDTO toQuestionDTO(Long attemptId, Question question) {
        if (question == null) {
            throw new IllegalArgumentException("Question is null");
        }
        if (question.getText() == null || question.getText().trim().isEmpty()) {
            System.err.println("AttemptService: текст вопроса пустой для ID " + question.getId());
        }

        java.util.Map<Long, BigDecimal> hundredToOneNominalsByOptionId = null;
        if (question.getType() == QuestionType.HUNDRED_TO_ONE) {
            hundredToOneNominalsByOptionId = ensureHundredToOneNominals(attemptId, question);
        }
            
        List<org.example.model.AnswerOption> options = answerOptionRepository.findByQuestionId(question.getId());
            
        if (options.isEmpty()) {
            throw new IllegalStateException("У вопроса ID " + question.getId() + " нет вариантов ответов");
        }
            
        List<AnswerOption> dtoOptions = new ArrayList<>();
        for (org.example.model.AnswerOption opt : options) {
            if (opt == null) {
                System.err.println("AttemptService: найден null вариант ответа, пропускаем");
                continue;
            }
            String optionText = opt.getText();
            if (optionText == null || optionText.trim().isEmpty()) {
                System.err.println("AttemptService: текст варианта ответа пустой для ID " + opt.getId() + ", пропускаем");
                continue;
            }
            BigDecimal nominal = null;
            if (question.getType() == QuestionType.HUNDRED_TO_ONE) {
                nominal = hundredToOneNominalsByOptionId != null ? hundredToOneNominalsByOptionId.get(opt.getId()) : null;
            } else {
                nominal = opt.getNominal();
            }
            dtoOptions.add(new AnswerOption(opt.getId(), optionText, nominal));
        }
            
        if (dtoOptions.isEmpty()) {
            throw new IllegalStateException("У вопроса ID " + question.getId() + " нет валидных вариантов ответов");
        }

        Integer timeLimit = null;
        Quiz quiz = question.getQuiz();
        if (quiz != null && quiz.getTimePerQuestion() != null) {
            timeLimit = (int) quiz.getTimePerQuestion().getSeconds();
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
                quiz != null ? toLocalDateTime(quiz.getCreatedAt()) : null
        );
    }

    private AttemptState getOrCreateAttemptState(Long attemptId) {
        AttemptState st = attemptStates.get(attemptId);
        if (st == null) {
            UserQuizAttempt attempt = attemptRepository.findById(attemptId)
                    .orElseThrow(() -> new IllegalArgumentException("Попытка не найдена"));

            st = new AttemptState();
            st.attemptId = attemptId;
            st.userId = attempt.getUser() != null ? attempt.getUser().getId() : null;
            st.quizId = attempt.getQuiz() != null ? attempt.getQuiz().getId() : null;
            st.hundredToOneNominalsByQuestionId = new ConcurrentHashMap<>();
            attemptStates.put(attemptId, st);
        }

        if (st.hundredToOneNominalsByQuestionId == null) {
            st.hundredToOneNominalsByQuestionId = new ConcurrentHashMap<>();
        }
        return st;
    }

    /**
     * Назначает (рандомно, но стабильно для одной попытки) номиналы вариантам ответа для вопроса HUNDRED_TO_ONE.
     */
    private java.util.Map<Long, BigDecimal> ensureHundredToOneNominals(Long attemptId, Question question) {
        AttemptState st = getOrCreateAttemptState(attemptId);

        java.util.Map<Long, BigDecimal> existing = st.hundredToOneNominalsByQuestionId.get(question.getId());
        if (existing != null) {
            return existing;
        }

        List<org.example.model.AnswerOption> options = answerOptionRepository.findByQuestionId(question.getId());
        if (options == null || options.isEmpty()) {
            throw new IllegalStateException("У вопроса ID " + question.getId() + " нет вариантов ответов");
        }

        java.util.List<Long> correctIds = options.stream()
                .filter(org.example.model.AnswerOption::isCorrect)
                .map(org.example.model.AnswerOption::getId)
                .toList();
        java.util.List<Long> incorrectIds = options.stream()
                .filter(o -> !o.isCorrect())
                .map(org.example.model.AnswerOption::getId)
                .toList();

        java.util.List<BigDecimal> correctPool = new java.util.ArrayList<>(
                java.util.List.of(
                        new BigDecimal("1"),
                        new BigDecimal("1.5"),
                        new BigDecimal("2"),
                        new BigDecimal("2.5"),
                        new BigDecimal("3")
                )
        );
        java.util.List<BigDecimal> incorrectPool = new java.util.ArrayList<>(
                java.util.List.of(
                        new BigDecimal("0"),
                        new BigDecimal("-1"),
                        new BigDecimal("-2")
                )
        );

        long seed = attemptId * 1000003L + (question.getId() != null ? question.getId() : 0L);
        Random rnd = new Random(seed);
        Collections.shuffle(correctPool, rnd);
        Collections.shuffle(incorrectPool, rnd);

        java.util.Map<Long, BigDecimal> mapping = new java.util.HashMap<>();

        for (int i = 0; i < correctIds.size(); i++) {
            BigDecimal nominal = correctPool.get(i % correctPool.size());
            mapping.put(correctIds.get(i), nominal);
        }
        for (int i = 0; i < incorrectIds.size(); i++) {
            BigDecimal nominal = incorrectPool.get(i % incorrectPool.size());
            mapping.put(incorrectIds.get(i), nominal);
        }

        st.hundredToOneNominalsByQuestionId.put(question.getId(), mapping);
        return mapping;
    }

    /**
     * Определяет 0-based индекс вопроса в порядке попытки.
     * Возвращает -1, если вопрос не найден.
     */
    private int getQuestionIndexInAttempt(Long attemptId, Long questionId) {
        List<AttemptQuestion> attemptQuestions =
                attemptQuestionRepository.findByAttemptIdOrderByQuestionOrder(attemptId);
        for (int i = 0; i < attemptQuestions.size(); i++) {
            Question q = attemptQuestions.get(i).getQuestion();
            if (q != null && q.getId().equals(questionId)) {
                return i;
            }
        }
        return -1;
    }

    private Integer calculateScore(Question question, UserQuizAttempt attempt) {
        // Базовая логика: 1 очко за правильный ответ
        // Можно усложнить: учитывать время ответа, сложность вопроса и т.д.
        return 1;
    }

    private int calculatePosition(Long quizId, Long currentUserId, int score, long timeSpent) {
        // Получаем все завершенные попытки для этого квиза
        Pageable pageable = PageRequest.of(0, 10000); // Увеличиваем лимит для учета всех попыток
        Page<UserQuizAttempt> allAttempts = attemptRepository
                .findCompletedByQuizIdOrderByScoreDesc(quizId, pageable);

        // Группируем попытки по пользователям и выбираем лучшую для каждого
        Map<Long, UserQuizAttempt> bestAttemptsByUser = new java.util.HashMap<>();
        
        for (UserQuizAttempt attempt : allAttempts.getContent()) {
            if (attempt.getUser() == null || attempt.getScore() == null) {
                continue;
            }
            
            Long userId = attempt.getUser().getId();
            UserQuizAttempt bestAttempt = bestAttemptsByUser.get(userId);
            
            if (bestAttempt == null) {
                // Первая попытка пользователя
                bestAttemptsByUser.put(userId, attempt);
            } else {
                // Сравниваем с текущей лучшей попыткой
                Long bestScore = bestAttempt.getScore();
                Long currentScore = attempt.getScore();
                
                if (currentScore > bestScore) {
                    // Текущая попытка лучше по счету
                    bestAttemptsByUser.put(userId, attempt);
                } else if (currentScore.equals(bestScore)) {
                    // Одинаковый счет, сравниваем по времени
                    long bestTime = 0;
                    long currentTime = 0;
                    
                    if (bestAttempt.getStartTime() != null && bestAttempt.getFinishTime() != null) {
                        bestTime = java.time.Duration.between(
                                bestAttempt.getStartTime(),
                                bestAttempt.getFinishTime()
                        ).getSeconds();
                    }
                    
                    if (attempt.getStartTime() != null && attempt.getFinishTime() != null) {
                        currentTime = java.time.Duration.between(
                                attempt.getStartTime(),
                                attempt.getFinishTime()
                        ).getSeconds();
                    }
                    
                    // Выбираем попытку с меньшим временем (быстрее = лучше)
                    if (currentTime > 0 && (bestTime == 0 || currentTime < bestTime)) {
                        bestAttemptsByUser.put(userId, attempt);
                    }
                }
            }
        }

        // Сортируем лучшие попытки: сначала по счету (убывание), затем по времени (возрастание)
        List<UserQuizAttempt> sortedBestAttempts = new ArrayList<>(bestAttemptsByUser.values());
        sortedBestAttempts.sort((a, b) -> {
            Long scoreA = a.getScore() != null ? a.getScore() : 0L;
            Long scoreB = b.getScore() != null ? b.getScore() : 0L;
            
            int scoreCompare = scoreB.compareTo(scoreA);
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            
            // При одинаковом счете сравниваем по времени
            long timeA = 0;
            long timeB = 0;
            
            if (a.getStartTime() != null && a.getFinishTime() != null) {
                timeA = java.time.Duration.between(a.getStartTime(), a.getFinishTime()).getSeconds();
            }
            if (b.getStartTime() != null && b.getFinishTime() != null) {
                timeB = java.time.Duration.between(b.getStartTime(), b.getFinishTime()).getSeconds();
            }
            
            return Long.compare(timeA, timeB); // Меньшее время = лучше
        });

        // Находим позицию лучшей попытки текущего пользователя
        int position = 1;
        boolean found = false;
        for (UserQuizAttempt bestAttempt : sortedBestAttempts) {
            if (bestAttempt.getUser() != null && bestAttempt.getUser().getId().equals(currentUserId)) {
                // Нашли лучшую попытку текущего пользователя
                found = true;
                break;
            }
            position++;
        }
        
        // Если не нашли (не должно произойти, но на всякий случай)
        if (!found) {
            position = sortedBestAttempts.size() + 1;
        }

        return position;
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant != null
                ? LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                : null;
    }

    /**
     * Выбирает вопросы для попытки в зависимости от типа квиза.
     * 
     * Для статичного квиза: берутся первые N вопросов в фиксированном порядке (по ID).
     * Эти вопросы сохраняются один раз и остаются неизменными для всех попыток этого квиза.
     * 
     * Для обновляемого квиза: берутся случайные N вопросов из всех доступных (из 200 вопросов в БД).
     * При каждой новой попытке выбираются новые случайные вопросы для замены.
     * 
     * @param attempt попытка прохождения
     * @param quiz квиз
     * @param allQuestions все вопросы квиза (должно быть 200 вопросов в БД)
     */
    private void selectQuestionsForAttempt(UserQuizAttempt attempt, Quiz quiz, List<Question> allQuestions) {
        // Фильтруем валидные вопросы (исключаем дубликаты и невалидные)
        List<Question> validQuestions = allQuestions.stream()
                .filter(q -> q != null 
                        && q.getText() != null 
                        && !q.getText().trim().isEmpty()
                        && (q.getIsValid() == null || q.getIsValid())
                        && (q.getIsDuplicate() == null || !q.getIsDuplicate()))
                .collect(Collectors.toList());
        
        // Если нет валидных вопросов, используем все вопросы
        if (validQuestions.isEmpty()) {
            validQuestions = allQuestions.stream()
                    .filter(q -> q != null && q.getText() != null && !q.getText().trim().isEmpty())
                    .collect(Collectors.toList());
        }
        
        if (validQuestions.isEmpty()) {
            throw new IllegalStateException("Нет доступных вопросов для выбора");
        }

        // Определяем количество вопросов для попытки
        int questionNumber = quiz.getQuestionNumber() != null && quiz.getQuestionNumber() > 0 
                ? quiz.getQuestionNumber() 
                : validQuestions.size(); // Если не указано, берем все
        
        // Ограничиваем количеством доступных вопросов
        questionNumber = Math.min(questionNumber, validQuestions.size());

        List<Question> selectedQuestions;
        
        if (quiz.isStatic()) {
            // Статичный квиз: берем первые N вопросов в фиксированном порядке (по ID)
            // Эти вопросы остаются одинаковыми для всех попыток этого квиза
            selectedQuestions = validQuestions.stream()
                    .sorted((q1, q2) -> Long.compare(q1.getId(), q2.getId()))
                    .limit(questionNumber)
                    .collect(Collectors.toList());
        } else {
            // Обновляемый квиз: берем случайные N вопросов из всех доступных (из 200 в БД)
            // При каждой новой попытке выбираются новые случайные вопросы для замены
            Collections.shuffle(validQuestions);
            selectedQuestions = validQuestions.stream()
                    .limit(questionNumber)
                    .collect(Collectors.toList());
        }

        // Сохраняем выбранные вопросы для попытки
        for (int i = 0; i < selectedQuestions.size(); i++) {
            AttemptQuestion attemptQuestion = new AttemptQuestion();
            attemptQuestion.setAttempt(attempt);
            attemptQuestion.setQuestion(selectedQuestions.get(i));
            attemptQuestion.setQuestionOrder(i);
            attemptQuestionRepository.save(attemptQuestion);
        }
        
        System.out.println("AttemptService: Выбрано " + selectedQuestions.size() + " вопросов для попытки ID " + attempt.getId() + 
                " (тип квиза: " + (quiz.isStatic() ? "статичный" : "обновляемый") + ")");
    }
}
