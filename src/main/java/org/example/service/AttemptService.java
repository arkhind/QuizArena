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
import java.time.Duration;
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
     * Внутренний класс для отслеживания состояния попыток в памяти.
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
        double baseScore;

        // Для вопроса типа HUNDRED_TO_ONE: questionId -> (answerOptionId -> nominal)
        Map<Long, Map<Long, BigDecimal>> hundredToOneNominalsByQuestionId;

        // «Кот в мешке»: индекс вопроса в попытке (0-based), null если не используется
        Integer catQuestionIndex;
        // Ставка на текущий вопрос «Кот в мешке», null если ставка ещё не сделана или вопрос не «Кот»
        Integer stakeForCurrentQuestion;
    }

    /**
     * Начинает попытку прохождения квиза.
     * Создаёт UserQuizAttempt и возвращает первый вопрос.
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

        if (quiz.isPrivate()) {
            if (!quizRepository.isCreator(request.quizId(), request.userId())) {
                System.err.println("AttemptService: Попытка доступа к приватному квизу. UserId: " + request.userId() + ", QuizId: " + request.quizId());
                throw new SecurityException("Доступ к приватному квизу запрещён");
            }
            System.out.println("AttemptService: Разрешён доступ к приватному квизу для создателя. UserId: " + request.userId() + ", QuizId: " + request.quizId());
        }

        List<Question> allQuestions = questionRepository.findByQuizId(request.quizId());
        if (allQuestions.isEmpty()) {
            System.err.println("AttemptService: Квиз ID " + request.quizId() + " не содержит вопросов!");
            throw new IllegalStateException("Квиз не содержит вопросов");
        }

        UserQuizAttempt attempt;
        Integer resolvedCatQuestionIndex = request.catQuestionIndex();

        if (request.sessionId() != null && !request.sessionId().isEmpty()) {
            attempt = attemptRepository.findTopByUserIdAndQuizIdAndSessionIdOrderByIdDesc(
                    request.userId(), request.quizId(), request.sessionId());

            if (attempt == null) {
                System.err.println("AttemptService: Попытка с sessionId не найдена, создаём новую");
                attempt = new UserQuizAttempt();
                attempt.setUser(user);
                attempt.setQuiz(quiz);
                attempt.setStartTime(Instant.now());
                attempt.setCompleted(false);
                attempt.setScore(null);
                attempt.setBaseScore(null);
                attempt.setSessionId(request.sessionId());
                attempt = attemptRepository.save(attempt);
                selectQuestionsForAttempt(attempt, quiz, allQuestions);
            } else {
                System.err.println("AttemptService: Используем существующую попытку ID " + attempt.getId() + " для мультиплеера");
                if (attemptQuestionRepository.findByAttemptIdOrderByQuestionOrder(attempt.getId()).isEmpty()) {
                    selectQuestionsForAttempt(attempt, quiz, allQuestions);
                }
                if (attempt.isCompleted()) {
                    throw new IllegalStateException("ATTEMPT_COMPLETED:" + attempt.getId());
                }
            }
        } else {
            attempt = attemptRepository.findTopByUserIdAndQuizIdAndSessionIdIsNullAndIsCompletedFalseOrderByIdDesc(
                    request.userId(), request.quizId());
            if (attempt == null) {
                attempt = new UserQuizAttempt();
                attempt.setUser(user);
                attempt.setQuiz(quiz);
                attempt.setStartTime(Instant.now());
                attempt.setCompleted(false);
                attempt.setScore(null);
                attempt.setBaseScore(null);
                attempt.setSessionId(null);
                attempt = attemptRepository.save(attempt);
                selectQuestionsForAttempt(attempt, quiz, allQuestions);
            } else if (attemptQuestionRepository.findByAttemptIdOrderByQuestionOrder(attempt.getId()).isEmpty()) {
                selectQuestionsForAttempt(attempt, quiz, allQuestions);
            }
        }

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

        AttemptState existingState = attemptStates.get(attempt.getId());
        if (existingState == null) {
            AttemptState st = new AttemptState();
            st.attemptId = attempt.getId();
            st.userId = request.userId();
            st.quizId = quiz.getId();
            st.hundredToOneNominalsByQuestionId = new ConcurrentHashMap<>();
            st.score = attempt.getScore() != null ? attempt.getScore() : 0.0;
            st.baseScore = attempt.getBaseScore() != null ? attempt.getBaseScore() : st.score;
            st.catQuestionIndex = resolvedCatQuestionIndex;
            st.stakeForCurrentQuestion = null;
            attemptStates.put(attempt.getId(), st);
        } else if (resolvedCatQuestionIndex != null) {
            existingState.catQuestionIndex = resolvedCatQuestionIndex;
        }

        QuestionDTO currentQuestion = getNextQuestion(attempt.getId());
        if (currentQuestion == null) {
            throw new IllegalStateException("ATTEMPT_COMPLETED:" + attempt.getId());
        }

        List<AttemptQuestion> attemptQuestions = attemptQuestionRepository.findByAttemptIdOrderByQuestionOrder(attempt.getId());
        int totalQuestions = attemptQuestions.size();
        int questionsRemaining = totalQuestions - userAnswerRepository.findByAttemptId(attempt.getId()).size();
        Integer timeRemaining = getRemainingSeconds(attempt, currentQuestion);

        return new AttemptResponse(
                attempt.getId(),
                quiz.getId(),
                quiz.getName(),
                currentQuestion,
                questionsRemaining,
                totalQuestions,
                timeRemaining,
                toEpochMillis(attempt.getCurrentQuestionDeadlineAt())
        );
    }
    public QuestionDTO getNextQuestion(Long attemptId) {
        return getNextQuestionInternal(attemptId, true);
    }
    public AttemptPageProgress getAttemptPageProgress(Long attemptId) {
        UserQuizAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Попытка не найдена"));
        if (attempt.isCompleted()) {
            throw new IllegalStateException("Попытка уже завершена");
        }

        List<AttemptQuestion> attemptQuestions = attemptQuestionRepository.findByAttemptIdOrderByQuestionOrder(attemptId);
        int total = attemptQuestions.size();
        int answered = userAnswerRepository.findByAttemptId(attemptId).size();
        int remaining = Math.max(0, total - answered);
        int seconds = getDefaultTimePerQuestionSeconds(attempt.getQuiz());

        return new AttemptPageProgress(
                total,
                remaining,
                attempt.getCurrentQuestionDeadlineAt() != null ? getRemainingSeconds(attempt, null) : seconds,
                toEpochMillis(attempt.getCurrentQuestionDeadlineAt())
        );
    }
    public AnswerResponse submitAnswer(SubmitAnswerRequest request) {
        UserQuizAttempt attempt = attemptRepository.findById(request.attemptId())
                .orElseThrow(() -> new IllegalArgumentException("Попытка не найдена"));

        if (attempt.isCompleted()) {
            throw new IllegalStateException("Попытка уже завершена");
        }

        Long questionId = request.questionId();
        if (questionId == null) {
            questionId = attempt.getCurrentQuestionId();
        }
        if (questionId == null) {
            AttemptQuestion pendingQuestion = findNextPendingAttemptQuestion(request.attemptId());
            if (pendingQuestion == null || pendingQuestion.getQuestion() == null) {
                throw new IllegalStateException("Нет активных вопросов");
            }
            questionId = pendingQuestion.getQuestion().getId();
        }

        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new IllegalArgumentException("Вопрос не найден"));

        if (!question.getQuiz().getId().equals(attempt.getQuiz().getId())) {
            throw new IllegalArgumentException("Вопрос не принадлежит этому квизу");
        }
        if (userAnswerRepository.existsByAttemptIdAndQuestionId(request.attemptId(), questionId)) {
            throw new IllegalStateException("Вопрос уже отвечен");
        }
        if (attempt.getCurrentQuestionId() != null && !attempt.getCurrentQuestionId().equals(questionId)) {
            throw new IllegalStateException("Сейчас активен другой вопрос");
        }

        AttemptState catCheck = attemptStates.get(request.attemptId());
        if (catCheck != null && catCheck.catQuestionIndex != null && catCheck.stakeForCurrentQuestion == null) {
            int qIdx = getQuestionIndexInAttempt(request.attemptId(), questionId);
            if (qIdx == catCheck.catQuestionIndex) {
                throw new IllegalStateException("Сначала необходимо сделать ставку (submitStake)");
            }
        }

        Instant answeredAt = Instant.now();
        boolean timedOut = isTimedOut(attempt, questionId, answeredAt);
        boolean keepTimeoutSelection = timedOut && Boolean.TRUE.equals(request.autoSubmitOnTimeout());
        List<Long> selectedIds = timedOut && !keepTimeoutSelection
                ? List.of()
                : request.getEffectiveSelectedIds();
        Boolean isCorrect;
        Long correctAnswerId;
        int scoreEarned = 0;
        double pointsEarned = 0.0;
        double accuracyRatio = 0.0;
        org.example.model.AnswerOption selectedOption = null;

        List<org.example.model.AnswerOption> allOptions = answerOptionRepository.findByQuestionId(questionId);
        java.util.Set<Long> correctIds = allOptions.stream()
                .filter(org.example.model.AnswerOption::isCorrect)
                .map(org.example.model.AnswerOption::getId)
                .collect(java.util.stream.Collectors.toSet());
        correctAnswerId = correctIds.isEmpty() ? null : correctIds.iterator().next();

        if (!selectedIds.isEmpty()) {
            selectedOption = answerOptionRepository.findById(selectedIds.get(0)).orElse(null);
            java.util.Set<Long> selectedIdSet = selectedIds.stream()
                    .filter(id -> id != null)
                    .collect(java.util.stream.Collectors.toSet());

            if (question.getType() == QuestionType.MULTIPLE_CHOICE) {
                int a = (int) selectedIdSet.stream().filter(correctIds::contains).count();
                int b = (int) selectedIdSet.stream().filter(id -> !correctIds.contains(id)).count();
                int c = correctIds.size();
                accuracyRatio = c > 0 ? Math.max(a - b, 0) / (double) c : 0.0;
                isCorrect = c > 0 && selectedIdSet.equals(correctIds);
                scoreEarned = isCorrect ? calculateScore(question, attempt) : 0;
                pointsEarned = c > 0 ? 2.0 * Math.max(a - b, 0) / c : 0.0;
            } else if (question.getType() == QuestionType.HUNDRED_TO_ONE) {
                java.util.Map<Long, BigDecimal> nominals = ensureHundredToOneNominals(request.attemptId(), question);
                BigDecimal sum = BigDecimal.ZERO;
                for (Long selectedId : selectedIds) {
                    if (selectedId == null) {
                        continue;
                    }
                    BigDecimal nominal = nominals.get(selectedId);
                    if (nominal != null) {
                        sum = sum.add(nominal);
                    }
                }
                pointsEarned = sum.doubleValue();
                isCorrect = pointsEarned > 0;
                scoreEarned = Boolean.TRUE.equals(isCorrect) ? calculateScore(question, attempt) : 0;
                accuracyRatio = Boolean.TRUE.equals(isCorrect) ? 1.0 : 0.0;
            } else {
                isCorrect = selectedIds.size() == 1 && correctIds.contains(selectedIds.get(0));
                scoreEarned = isCorrect ? calculateScore(question, attempt) : 0;
                pointsEarned = scoreEarned;
                accuracyRatio = Boolean.TRUE.equals(isCorrect) ? 1.0 : 0.0;
            }
        } else {
            isCorrect = false;
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
        userAnswer.setAccuracyRatio(accuracyRatio);
        userAnswerRepository.save(userAnswer);

        clearCurrentQuestionState(attempt);

        AttemptState st = attemptStates.get(request.attemptId());
        if (st != null) {
            st.baseScore += pointsEarned;
            st.score += pointsEarned;
            if (st.catQuestionIndex != null && st.stakeForCurrentQuestion != null) {
                int qIndex = getQuestionIndexInAttempt(request.attemptId(), questionId);
                if (qIndex == st.catQuestionIndex) {
                    int stake = st.stakeForCurrentQuestion;
                    int catStakeBonus;
                    if (Boolean.TRUE.equals(isCorrect)) {
                        st.score += stake;
                        scoreEarned += stake;
                        catStakeBonus = stake;
                    } else {
                        st.score -= stake;
                        scoreEarned -= stake;
                        catStakeBonus = -stake;
                    }
                    attempt.setCatStake(stake);
                    attempt.setCatStakeBonus(catStakeBonus);
                    st.stakeForCurrentQuestion = null;
                }
            }
            attempt.setBaseScore(Math.round(st.baseScore));
            attempt.setScore(Math.round(st.score));
            attemptRepository.save(attempt);
        } else {
            Long currentPoints = attempt.getBaseScore() != null ? attempt.getBaseScore() : 0L;
            attempt.setBaseScore(currentPoints + Math.round(pointsEarned));
            attempt.setScore(attempt.getBaseScore());
            attemptRepository.save(attempt);
        }

        QuestionDTO nextQuestion = peekNextQuestion(request.attemptId());
        String explanation = question.getExplanation() != null
                ? question.getExplanation()
                : "Объяснение отсутствует";
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
    private QuestionDTO peekNextQuestion(Long attemptId) {
        return getNextQuestionInternal(attemptId, false);
    }

    private QuestionDTO getNextQuestionInternal(Long attemptId, boolean activateQuestion) {
        UserQuizAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Попытка не найдена"));

        if (attempt.isCompleted()) {
            throw new IllegalStateException("Попытка уже завершена");
        }

        AttemptQuestion pendingQuestion = findNextPendingAttemptQuestion(attemptId);
        if (pendingQuestion == null || pendingQuestion.getQuestion() == null) {
            clearCurrentQuestionState(attempt);
            return null;
        }

        Question question = pendingQuestion.getQuestion();
        int questionIndex = pendingQuestion.getQuestionOrder() != null ? pendingQuestion.getQuestionOrder() : -1;

        AttemptState st = attemptStates.get(attemptId);
        if (st != null && st.catQuestionIndex != null
                && questionIndex == st.catQuestionIndex
                && st.stakeForCurrentQuestion == null) {
            clearCurrentQuestionState(attempt);
            Integer timeLimit = null;
            Quiz quiz = question.getQuiz();
            if (quiz != null && quiz.getTimePerQuestion() != null) {
                timeLimit = (int) quiz.getTimePerQuestion().getSeconds();
            }
            return new QuestionDTO(
                    question.getId(),
                    null,
                    List.of(),
                    question.getType(),
                    timeLimit,
                    null, null, null, null, 0,
                    quiz != null ? toLocalDateTime(quiz.getCreatedAt()) : null,
                    true
            );
        }

        if (activateQuestion) {
            activateCurrentQuestion(attempt, question);
        }
        return toQuestionDTO(attemptId, question);
    }

    private AttemptQuestion findNextPendingAttemptQuestion(Long attemptId) {
        List<AttemptQuestion> attemptQuestions = attemptQuestionRepository.findByAttemptIdOrderByQuestionOrder(attemptId);
        if (attemptQuestions.isEmpty()) {
            return null;
        }

        List<Long> answeredQuestionIds = userAnswerRepository.findByAttemptId(attemptId).stream()
                .map(answer -> answer.getQuestion() != null ? answer.getQuestion().getId() : null)
                .filter(id -> id != null)
                .collect(Collectors.toList());

        for (AttemptQuestion attemptQuestion : attemptQuestions) {
            Question question = attemptQuestion.getQuestion();
            if (question != null && !answeredQuestionIds.contains(question.getId())) {
                return attemptQuestion;
            }
        }
        return null;
    }

    private void activateCurrentQuestion(UserQuizAttempt attempt, Question question) {
        if (question == null) {
            return;
        }

        attempt.setCurrentQuestionId(question.getId());
        attempt.setCurrentQuestionStartedAt(Instant.now());
        int seconds = getDefaultTimePerQuestionSeconds(attempt.getQuiz());
        attempt.setCurrentQuestionDeadlineAt(attempt.getCurrentQuestionStartedAt().plusSeconds(seconds));
        attemptRepository.save(attempt);
    }

    private void clearCurrentQuestionState(UserQuizAttempt attempt) {
        if (attempt.getCurrentQuestionId() == null
                && attempt.getCurrentQuestionStartedAt() == null
                && attempt.getCurrentQuestionDeadlineAt() == null) {
            return;
        }
        attempt.setCurrentQuestionId(null);
        attempt.setCurrentQuestionStartedAt(null);
        attempt.setCurrentQuestionDeadlineAt(null);
        attemptRepository.save(attempt);
    }

    private boolean isTimedOut(UserQuizAttempt attempt, Long questionId, Instant now) {
        return questionId != null
                && questionId.equals(attempt.getCurrentQuestionId())
                && attempt.getCurrentQuestionDeadlineAt() != null
                && now.isAfter(attempt.getCurrentQuestionDeadlineAt());
    }

    private Integer getRemainingSeconds(UserQuizAttempt attempt, QuestionDTO currentQuestion) {
        if (currentQuestion != null && Boolean.TRUE.equals(currentQuestion.isCatInBagStakeScreen())) {
            return getDefaultTimePerQuestionSeconds(attempt.getQuiz());
        }
        if (attempt.getCurrentQuestionDeadlineAt() == null) {
            return getDefaultTimePerQuestionSeconds(attempt.getQuiz());
        }
        long remainingMillis = Duration.between(Instant.now(), attempt.getCurrentQuestionDeadlineAt()).toMillis();
        if (remainingMillis <= 0) {
            return 0;
        }
        return (int) Math.ceil(remainingMillis / 1000.0);
    }

    private int getDefaultTimePerQuestionSeconds(Quiz quiz) {
        return quiz != null && quiz.getTimePerQuestion() != null
                ? (int) quiz.getTimePerQuestion().getSeconds()
                : 60;
    }

    private Long toEpochMillis(Instant instant) {
        return instant != null ? instant.toEpochMilli() : null;
    }
    public QuizResultDTO finishQuizAttempt(Long attemptId) {
        // 1. Получаем попытку
        UserQuizAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Попытка не найдена"));

        if (attempt.getSessionId() != null && !attempt.getSessionId().isBlank()
                && attempt.getStartTime() == null && !attempt.isCompleted()) {
            throw new IllegalStateException("Мультиплеерная попытка ещё не началась");
        }

        if (attempt.isCompleted()) {
            // Идемпотентность: если finish вызван повторно, возвращаем уже сохранённый результат.
            List<AttemptQuestion> attemptQuestions = attemptQuestionRepository.findByAttemptIdOrderByQuestionOrder(attemptId);
            int totalQuestions = attemptQuestions.size();
            int correctAnswers = (int) userAnswerRepository.countByAttemptIdAndIsCorrectTrue(attemptId);
            int finalScore = attempt.getScore() != null ? attempt.getScore().intValue() : 0;
            int finalPoints = attempt.getBaseScore() != null ? attempt.getBaseScore().intValue() : finalScore;

            long timeSpent = 0;
            if (attempt.getStartTime() != null && attempt.getFinishTime() != null) {
                timeSpent = java.time.Duration.between(attempt.getStartTime(), attempt.getFinishTime()).getSeconds();
            }

            int position = calculatePosition(attempt.getQuiz().getId(), attempt.getUser().getId());
            return new QuizResultDTO(
                    attemptId,
                    finalScore,
                    finalPoints,
                    correctAnswers,
                    totalQuestions,
                    position,
                    timeSpent,
                    toLocalDateTime(attempt.getFinishTime())
            );
        }

        clearCurrentQuestionState(attempt);
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
        AttemptState st = attemptStates.get(attemptId);
        double rawPoints = st != null
                ? st.score
                : (attempt.getScore() != null ? attempt.getScore().doubleValue() : 0.0);
        double rawBasePoints = st != null
                ? st.baseScore
                : (attempt.getBaseScore() != null ? attempt.getBaseScore().doubleValue() : rawPoints);
        long finalPointsLong = Math.round(rawPoints);
        long leaderboardPointsLong = Math.round(rawBasePoints);
        long finalScoreLong = correctAnswers;

        attempt.setBaseScore(leaderboardPointsLong);
        attempt.setScore(finalScoreLong);
        attempt.setAccuracyPercent(calculateAccuracyPercent(answers, attemptQuestions));
        attempt = attemptRepository.save(attempt);
        int finalScore = (int) finalScoreLong;
        int finalPoints = (int) finalPointsLong;
        int accuracyPercent = attempt.getAccuracyPercent() != null ? attempt.getAccuracyPercent() : 0;

        // 5. Вычисляем время прохождения
        long timeSpent = 0;
        if (attempt.getStartTime() != null && attempt.getFinishTime() != null) {
            timeSpent = java.time.Duration.between(attempt.getStartTime(), attempt.getFinishTime()).getSeconds();
        }

        // 6. Обновляем таблицу лидеров в Redis
        leaderboardService.updateLeaderboard(
                attempt.getQuiz().getId(),
                attempt.getUser().getId(),
                attempt.getUser().getLogin(),
                finalScore,
                (int) leaderboardPointsLong,
                timeSpent,
                accuracyPercent
        );

        // 7. Вычисляем позицию в рейтинге по лучшим попыткам каждого пользователя
        int position = calculatePosition(attempt.getQuiz().getId(), attempt.getUser().getId());

        if (attempt.getStartTime() != null && attempt.getFinishTime() != null) {
            long durationNanos = java.time.Duration.between(attempt.getStartTime(), attempt.getFinishTime()).toNanos();
            metricsService.getAttemptDurationTimer().record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        }

        return new QuizResultDTO(
                attemptId,
                finalScore,
                finalPoints,
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
            System.err.println("AttemptService: Ошибка при проверке завершения мультиплеерной сессии: " + e.getMessage());
        }
    }

    /**
     * Устанавливает catQuestionIndex в AttemptState.
     * Вызывается из MultiplayerService при старте сессии, чтобы у всех участников
     * был одинаковый индекс вопроса «Кот в мешке».
     */
    public void setCatQuestionIndex(Long attemptId, Integer catQuestionIndex) {
        AttemptState st = getOrCreateAttemptState(attemptId);
        st.catQuestionIndex = catQuestionIndex;
        st.stakeForCurrentQuestion = null;
    }

    /**
     * Возвращает текущий накопленный счёт попытки из памяти или из БД.
     */
    public double getCurrentScore(Long attemptId) {
        AttemptState st = attemptStates.get(attemptId);
        if (st != null) {
            return st.score;
        }
        UserQuizAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Попытка не найдена"));
        if (attempt.getScore() != null) {
            return attempt.getScore().doubleValue();
        }
        return attempt.getBaseScore() != null ? attempt.getBaseScore().doubleValue() : 0.0;
    }

    private Long getLeaderboardScore(UserQuizAttempt attempt) {
        return attempt.getScore() != null ? attempt.getScore() : 0L;
    }

    private Long getAttemptPoints(UserQuizAttempt attempt) {
        if (attempt.getBaseScore() != null) {
            return attempt.getBaseScore();
        }
        return attempt.getScore() != null ? attempt.getScore() : 0L;
    }

    private int calculateAccuracyPercent(List<UserAnswer> answers, List<AttemptQuestion> attemptQuestions) {
        int totalQuestions = attemptQuestions != null ? attemptQuestions.size() : 0;
        if (totalQuestions <= 0) {
            return 0;
        }

        Map<Long, Double> accuracyByQuestionId = answers.stream()
                .filter(answer -> answer.getQuestion() != null && answer.getQuestion().getId() != null)
                .collect(Collectors.toMap(
                        answer -> answer.getQuestion().getId(),
                        answer -> answer.getAccuracyRatio() != null
                                ? Math.max(0.0, Math.min(1.0, answer.getAccuracyRatio()))
                                : (Boolean.TRUE.equals(answer.getIsCorrect()) ? 1.0 : 0.0),
                        (first, ignored) -> first
                ));

        double totalAccuracy = 0.0;
        for (AttemptQuestion attemptQuestion : attemptQuestions) {
            Question question = attemptQuestion.getQuestion();
            if (question != null && question.getId() != null) {
                totalAccuracy += accuracyByQuestionId.getOrDefault(question.getId(), 0.0);
            }
        }
        return (int) Math.round((totalAccuracy * 100.0) / totalQuestions);
    }

    /**
     * Принимает ставку для вопроса «Кот в мешке».
     * Валидация: stake >= 0, stake <= текущий счёт; при счёте <= 0 допускается только 0.
     * После принятия ставки повторный вызов getNextQuestion вернёт сам вопрос без экрана ставки.
     *
     * @return раскрытый QuestionDTO для немедленной отдачи клиенту
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
                        && !q.getText().trim().isEmpty())
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

        // Детерминированная перетасовка: порядок стабилен в рамках попытки,
        // но не повторяет порядок, который вернула модель или БД.
        long shuffleSeed = attemptId * 1000003L + (question.getId() != null ? question.getId() : 0L);
        options = new ArrayList<>(options);
        Collections.shuffle(options, new Random(shuffleSeed));

        List<AnswerOption> dtoOptions = new ArrayList<>();
        for (org.example.model.AnswerOption opt : options) {
            if (opt == null) {
                System.err.println("AttemptService: найден null-вариант ответа, пропускаем");
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
            st.score = attempt.getScore() != null ? attempt.getScore() : 0.0;
            st.baseScore = attempt.getBaseScore() != null ? attempt.getBaseScore() : st.score;
            attemptStates.put(attemptId, st);
        }

        if (st.hundredToOneNominalsByQuestionId == null) {
            st.hundredToOneNominalsByQuestionId = new ConcurrentHashMap<>();
        }
        return st;
    }

    /**
     * Назначает номиналы вариантам ответа для вопроса HUNDRED_TO_ONE.
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
        // Базовая логика: 1 очко за правильный ответ.
        // При необходимости сюда можно добавить учёт времени и сложности вопроса.
        return 1;
    }

    private int calculatePosition(Long quizId, Long currentUserId) {
        // Получаем все завершённые попытки для этого квиза.
        Pageable pageable = PageRequest.of(0, 10000); // Увеличиваем лимит, чтобы учесть все попытки.
        Page<UserQuizAttempt> allAttempts = attemptRepository
                .findCompletedByQuizIdOrderByScoreDesc(quizId, pageable);

        // Группируем попытки по пользователям и выбираем лучшую для каждого.
        Map<Long, UserQuizAttempt> bestAttemptsByUser = new java.util.HashMap<>();
        
        for (UserQuizAttempt attempt : allAttempts.getContent()) {
            if (attempt.getUser() == null || getLeaderboardScore(attempt) == null) {
                continue;
            }
            
            Long userId = attempt.getUser().getId();
            UserQuizAttempt bestAttempt = bestAttemptsByUser.get(userId);
            
            if (bestAttempt == null) {
                // Первая попытка пользователя.
                bestAttemptsByUser.put(userId, attempt);
            } else {
                // Сравниваем с текущей лучшей попыткой.
                Long bestPoints = getAttemptPoints(bestAttempt);
                Long currentPoints = getAttemptPoints(attempt);
                
                if (currentPoints > bestPoints) {
                    // Текущая попытка лучше по очкам.
                    bestAttemptsByUser.put(userId, attempt);
                } else if (currentPoints.equals(bestPoints)) {
                    Long bestScore = getLeaderboardScore(bestAttempt);
                    Long currentScore = getLeaderboardScore(attempt);
                    if (currentScore > bestScore) {
                        bestAttemptsByUser.put(userId, attempt);
                        continue;
                    }
                    if (currentScore < bestScore) {
                        continue;
                    }
                    int bestAccuracy = bestAttempt.getAccuracyPercent() != null ? bestAttempt.getAccuracyPercent() : 0;
                    int currentAccuracy = attempt.getAccuracyPercent() != null ? attempt.getAccuracyPercent() : 0;
                    if (currentAccuracy > bestAccuracy) {
                        bestAttemptsByUser.put(userId, attempt);
                        continue;
                    }
                    if (currentAccuracy < bestAccuracy) {
                        continue;
                    }
                    // При одинаковом счёте сравниваем по времени.
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
                    
                    // Выбираем попытку с меньшим временем.
                    if (currentTime > 0 && (bestTime == 0 || currentTime < bestTime)) {
                        bestAttemptsByUser.put(userId, attempt);
                    }
                }
            }
        }

        // Сортируем лучшие попытки: сначала по очкам, затем по баллам, точности и времени.
        List<UserQuizAttempt> sortedBestAttempts = new ArrayList<>(bestAttemptsByUser.values());
        sortedBestAttempts.sort((a, b) -> {
            Long pointsA = getAttemptPoints(a);
            Long pointsB = getAttemptPoints(b);
            int pointsCompare = pointsB.compareTo(pointsA);
            if (pointsCompare != 0) {
                return pointsCompare;
            }

            Long scoreA = getLeaderboardScore(a);
            Long scoreB = getLeaderboardScore(b);
            
            int scoreCompare = scoreB.compareTo(scoreA);
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            int accuracyA = a.getAccuracyPercent() != null ? a.getAccuracyPercent() : 0;
            int accuracyB = b.getAccuracyPercent() != null ? b.getAccuracyPercent() : 0;
            int accuracyCompare = Integer.compare(accuracyB, accuracyA);
            if (accuracyCompare != 0) {
                return accuracyCompare;
            }
            
            // При одинаковом счёте сравниваем по времени.
            long timeA = 0;
            long timeB = 0;
            
            if (a.getStartTime() != null && a.getFinishTime() != null) {
                timeA = java.time.Duration.between(a.getStartTime(), a.getFinishTime()).getSeconds();
            }
            if (b.getStartTime() != null && b.getFinishTime() != null) {
                timeB = java.time.Duration.between(b.getStartTime(), b.getFinishTime()).getSeconds();
            }
            
            return Long.compare(timeA, timeB); // Меньшее время лучше.
        });

        // Находим позицию лучшей попытки текущего пользователя.
        int position = 1;
        boolean found = false;
        for (UserQuizAttempt bestAttempt : sortedBestAttempts) {
            if (bestAttempt.getUser() != null && bestAttempt.getUser().getId().equals(currentUserId)) {
                // Нашли лучшую попытку текущего пользователя.
                found = true;
                break;
            }
            position++;
        }
        
        // Если не нашли, ставим позицию после всех найденных.
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
     * Для статичного квиза берутся первые N вопросов в фиксированном порядке.
     * Эти вопросы остаются одинаковыми для всех попыток этого квиза.
     * 
     * Для обновляемого квиза берутся случайные N вопросов из всех доступных.
     * При каждой новой попытке выбираются новые случайные вопросы для замены.
     * 
     * @param attempt попытка прохождения
     * @param quiz квиз
     * @param allQuestions все вопросы квиза
     */
    private void selectQuestionsForAttempt(UserQuizAttempt attempt, Quiz quiz, List<Question> allQuestions) {
        // Фильтруем валидные вопросы.
        List<Question> validQuestions = allQuestions.stream()
                .filter(q -> q != null 
                        && q.getText() != null 
                        && !q.getText().trim().isEmpty())
                .collect(Collectors.toList());
        
        // Если валидных вопросов нет, используем просто вопросы с непустым текстом.
        if (validQuestions.isEmpty()) {
            validQuestions = allQuestions.stream()
                    .filter(q -> q != null && q.getText() != null && !q.getText().trim().isEmpty())
                    .collect(Collectors.toList());
        }
        
        if (validQuestions.isEmpty()) {
            throw new IllegalStateException("Нет доступных вопросов для выбора");
        }

        // Определяем количество вопросов для попытки.
        int questionNumber = quiz.getQuestionNumber() != null && quiz.getQuestionNumber() > 0 
                ? quiz.getQuestionNumber() 
                : validQuestions.size(); // Если не указано, берём все.
        
        // Ограничиваем количество доступными вопросами.
        questionNumber = Math.min(questionNumber, validQuestions.size());

        List<Question> selectedQuestions;
        
        if (quiz.isStatic()) {
            // Статичный квиз: берём первые N вопросов в фиксированном порядке.
            // Эти вопросы остаются одинаковыми для всех попыток этого квиза.
            selectedQuestions = validQuestions.stream()
                    .sorted((q1, q2) -> Long.compare(q1.getId(), q2.getId()))
                    .limit(questionNumber)
                    .collect(Collectors.toList());
        } else {
            // Обновляемый квиз: берём случайные N вопросов из всех доступных.
            // При каждой новой попытке выбираются новые случайные вопросы для замены.
            Collections.shuffle(validQuestions);
            selectedQuestions = validQuestions.stream()
                    .limit(questionNumber)
                    .collect(Collectors.toList());
        }

        // Сохраняем выбранные вопросы для попытки.
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
