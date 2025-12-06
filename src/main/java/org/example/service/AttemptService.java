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

    @Autowired
    public AttemptService(
            UserQuizAttemptRepository attemptRepository,
            QuizRepository quizRepository,
            UserRepository userRepository,
            QuestionRepository questionRepository,
            AnswerOptionRepository answerOptionRepository,
            UserAnswerRepository userAnswerRepository) {
        this.attemptRepository = attemptRepository;
        this.quizRepository = quizRepository;
        this.userRepository = userRepository;
        this.questionRepository = questionRepository;
        this.answerOptionRepository = answerOptionRepository;
        this.userAnswerRepository = userAnswerRepository;
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

        List<Question> questions = questionRepository.findByQuizId(request.quizId());
        
        if (questions.isEmpty()) {
            System.err.println("AttemptService: Квиз ID " + request.quizId() + " не содержит вопросов!");
            throw new IllegalStateException("Квиз не содержит вопросов");
        }

        UserQuizAttempt attempt = new UserQuizAttempt();
        attempt.setUser(user);
        attempt.setQuiz(quiz);
        attempt.setStartTime(Instant.now());
        attempt.setCompleted(false);
        attempt.setScore(null);
        attempt.setSessionId(null);
        attempt = attemptRepository.save(attempt);

        Question firstQuestion = getFirstQuestion(questions);
        
        List<org.example.model.AnswerOption> options = answerOptionRepository.findByQuestionId(firstQuestion.getId());
        if (options.isEmpty()) {
            System.err.println("AttemptService: У вопроса ID " + firstQuestion.getId() + " нет вариантов ответов!");
            throw new IllegalStateException("У вопроса нет вариантов ответов");
        }
        
        QuestionDTO questionDTO = toQuestionDTO(firstQuestion);

        int totalQuestions = questions.size();
        int questionsRemaining = totalQuestions;

        Integer timeRemaining = quiz.getTimePerQuestion() != null
                ? (int) quiz.getTimePerQuestion().getSeconds()
                : 60;

        return new AttemptResponse(
                attempt.getId(),
                quiz.getId(),
                quiz.getName(),
                questionDTO,
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

        // 2. Получаем все вопросы квиза
        List<Question> allQuestions = questionRepository.findByQuizId(attempt.getQuiz().getId());
        
        if (allQuestions.isEmpty()) {
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

        // 4. Находим первый неотвеченный вопрос
        Question nextQuestion = allQuestions.stream()
                .filter(q -> !answeredQuestionIds.contains(q.getId()))
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
                : "";

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

        // 3. Получаем все ответы
        List<UserAnswer> answers = userAnswerRepository.findByAttemptId(attemptId);

        // 4. Подсчитываем статистику
        int totalQuestions = questionRepository.findByQuizId(attempt.getQuiz().getId()).size();
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
}
