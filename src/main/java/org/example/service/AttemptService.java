package org.example.service;

import org.example.dto.common.AnswerOption;
import org.example.dto.request.attempt.StartAttemptRequest;
import org.example.dto.request.attempt.SubmitAnswerRequest;
import org.example.dto.response.attempt.AnswerResponse;
import org.example.dto.response.attempt.AttemptResponse;
import org.example.dto.response.attempt.QuizResultDTO;
import org.example.dto.response.quiz.QuestionDTO;
import org.example.model.*;
import org.example.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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

    @Autowired
    public AttemptService(
            UserQuizAttemptRepository attemptRepository,
            QuizRepository quizRepository,
            UserRepository userRepository,
            QuestionRepository questionRepository,
            AnswerOptionRepository answerOptionRepository,
            UserAnswerRepository userAnswerRepository,
            org.example.repository.MultiplayerSessionRepository multiplayerSessionRepository,
            org.example.repository.AttemptQuestionRepository attemptQuestionRepository) {
        this.attemptRepository = attemptRepository;
        this.quizRepository = quizRepository;
        this.userRepository = userRepository;
        this.questionRepository = questionRepository;
        this.answerOptionRepository = answerOptionRepository;
        this.userAnswerRepository = userAnswerRepository;
        this.multiplayerSessionRepository = multiplayerSessionRepository;
        this.attemptQuestionRepository = attemptQuestionRepository;
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
        int score;
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

        if (quiz.isPrivate()) {
            throw new SecurityException("Доступ к приватному квизу запрещен");
        }

        List<Question> allQuestions = questionRepository.findByQuizId(request.quizId());
        
        if (allQuestions.isEmpty()) {
            System.err.println("AttemptService: Квиз ID " + request.quizId() + " не содержит вопросов!");
            throw new IllegalStateException("Квиз не содержит вопросов");
        }

        UserQuizAttempt attempt;
        
        // Если передан sessionId, ищем существующую попытку для мультиплеера
        if (request.sessionId() != null && !request.sessionId().isEmpty()) {
            attempt = attemptRepository.findByUserIdAndQuizIdAndSessionId(
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

        // Получаем текущий вопрос (первый неотвеченный)
        QuestionDTO currentQuestion = getNextQuestion(attempt.getId());
        if (currentQuestion == null) {
            throw new IllegalStateException("Все вопросы уже отвечены");
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

        // 4. Находим первый неотвеченный вопрос из выбранных для попытки
        Question nextQuestion = attemptQuestions.stream()
                .map(AttemptQuestion::getQuestion)
                .filter(q -> q != null && !answeredQuestionIds.contains(q.getId()))
                .findFirst()
                .orElse(null);

        if (nextQuestion == null) {
            return null; // Все вопросы отвечены
        }

        return toQuestionDTO(nextQuestion);
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

        // 5. Обрабатываем ответ (может быть null при таймауте)
        Long selectedAnswerId = request.selectedAnswerId();
        Boolean isCorrect = null;
        Long correctAnswerId = null;
        Integer scoreEarned = 0;
        org.example.model.AnswerOption selectedOption = null;

        if (selectedAnswerId != null) {
            // Пользователь выбрал ответ
            selectedOption = answerOptionRepository.findById(selectedAnswerId)
                    .orElseThrow(() -> new IllegalArgumentException("Вариант ответа не найден"));

            // Проверяем, что вариант принадлежит вопросу
            if (!selectedOption.getQuestion().getId().equals(questionId)) {
                throw new IllegalArgumentException("Вариант ответа не принадлежит этому вопросу");
            }

            Optional<org.example.model.AnswerOption> correctOptionOpt = answerOptionRepository
                    .findByQuestionIdAndIsCorrectTrue(questionId);

            if (correctOptionOpt.isPresent()) {
                org.example.model.AnswerOption correctOption = correctOptionOpt.get();
                correctAnswerId = correctOption.getId();
                isCorrect = selectedAnswerId.equals(correctAnswerId);

                if (isCorrect) {
                    scoreEarned = calculateScore(question, attempt);
                }
            } else {
                System.err.println("AttemptService: Не найден правильный ответ для вопроса ID=" + questionId);
            }
        } else {
            // ТАЙМАУТ: selectedAnswerId == null
            // Находим правильный ответ для информации
            Optional<org.example.model.AnswerOption> correctOptionOpt = answerOptionRepository
                    .findByQuestionIdAndIsCorrectTrue(questionId);

            if (correctOptionOpt.isPresent()) {
                correctAnswerId = correctOptionOpt.get().getId();
            }

            isCorrect = false;  // При таймауте ответ считается неверным
            scoreEarned = 0;    // Очки не начисляются
        }

        // 6. Сохраняем ответ пользователя
        UserAnswer userAnswer = new UserAnswer();
        userAnswer.setAttempt(attempt);
        userAnswer.setQuestion(question);
        userAnswer.setSelectedAnswer(selectedOption); // null при таймауте
        userAnswer.setIsCorrect(isCorrect);
        userAnswer.setAnsweredAt(Instant.now());
        userAnswer.setTimeSpentSeconds(null); // TODO: Вычислить на основе времени начала вопроса
        userAnswerRepository.save(userAnswer);

        // 7. Обновляем счет попытки
        Long currentScore = attempt.getScore() != null ? attempt.getScore() : 0L;
        attempt.setScore(currentScore + scoreEarned);
        attemptRepository.save(attempt);

        // 8. Получаем следующий вопрос
        QuestionDTO nextQuestion = getNextQuestion(request.attemptId());

        // 9. Формируем ответ
        String explanation = question.getExplanation() != null
                ? question.getExplanation()
                : "Объяснение отсутствует";
        
        System.out.println("AttemptService: Возвращаем объяснение для вопроса ID " + questionId + 
                ": " + (explanation.length() > 50 ? explanation.substring(0, 50) + "..." : explanation));

        return new AnswerResponse(
                isCorrect,
                explanation,
                correctAnswerId,
                scoreEarned,
                nextQuestion
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
            throw new IllegalStateException("Попытка уже завершена");
        }

        // 2. Завершаем попытку
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
        int finalScore = attempt.getScore() != null ? attempt.getScore().intValue() : 0;

        // 5. Вычисляем время прохождения
        long timeSpent = 0;
        if (attempt.getStartTime() != null && attempt.getFinishTime() != null) {
            timeSpent = java.time.Duration.between(attempt.getStartTime(), attempt.getFinishTime()).getSeconds();
        }

        // 6. Вычисляем позицию в рейтинге
        int position = calculatePosition(attempt.getQuiz().getId(), finalScore, timeSpent);

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

    private QuestionDTO toQuestionDTO(Question question) {
        if (question == null) {
            throw new IllegalArgumentException("Question is null");
        }
        if (question.getText() == null || question.getText().trim().isEmpty()) {
            System.err.println("AttemptService: текст вопроса пустой для ID " + question.getId());
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
            dtoOptions.add(new AnswerOption(opt.getId(), optionText));
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
                timeLimit,
                null,
                question.getExplanation(),
                null,
                null,
                0,
                quiz != null ? toLocalDateTime(quiz.getCreatedAt()) : null
        );
    }

    private Integer calculateScore(Question question, UserQuizAttempt attempt) {
        // Базовая логика: 1 очко за правильный ответ
        // Можно усложнить: учитывать время ответа, сложность вопроса и т.д.
        return 1;
    }

    private int calculatePosition(Long quizId, int score, long timeSpent) {
        // Получаем все завершенные попытки для этого квиза, отсортированные по счету
        Pageable pageable = PageRequest.of(0, 1000);
        Page<UserQuizAttempt> attempts = attemptRepository
                .findCompletedByQuizIdOrderByScoreDesc(quizId, pageable);

        int position = 1;
        for (UserQuizAttempt attempt : attempts.getContent()) {
            if (attempt.getScore() != null && attempt.getScore() > score) {
                position++;
            } else if (attempt.getScore() != null && attempt.getScore().equals((long) score)) {
                // При одинаковом счете учитываем время
                if (attempt.getStartTime() != null && attempt.getFinishTime() != null) {
                    long attemptTime = java.time.Duration.between(
                            attempt.getStartTime(),
                            attempt.getFinishTime()
                    ).getSeconds();
                    if (attemptTime < timeSpent) {
                        position++;
                    }
                }
            }
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
     * Для обновляемого квиза: берутся случайные N вопросов из всех доступных (из 100 вопросов в БД).
     * При каждой новой попытке выбираются новые случайные вопросы для замены.
     * 
     * @param attempt попытка прохождения
     * @param quiz квиз
     * @param allQuestions все вопросы квиза (должно быть 100 вопросов в БД)
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
            // Обновляемый квиз: берем случайные N вопросов из всех доступных (из 100 в БД)
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
