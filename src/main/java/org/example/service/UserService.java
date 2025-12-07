package org.example.service;

import org.example.dto.request.auth.UpdateProfileRequest;
import org.example.dto.response.auth.UserProfileDTO;
import org.example.dto.response.history.UserHistoryDTO;
import org.example.dto.response.quiz.QuizDTO;
import org.example.dto.common.AttemptSummary;
import org.example.dto.common.Statistics;
import org.example.model.User;
import org.example.model.UserQuizAttempt;
import org.example.repository.QuizRepository;
import org.example.repository.UserQuizAttemptRepository;
import org.example.repository.UserRepository;
import org.example.repository.QuestionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Сервис для работы с профилем пользователя и историей.
 */
@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final QuizRepository quizRepository;
    private final UserQuizAttemptRepository attemptRepository;
    private final QuestionRepository questionRepository;

    @Autowired
    public UserService(UserRepository userRepository,
                      QuizRepository quizRepository,
                      UserQuizAttemptRepository attemptRepository,
                      QuestionRepository questionRepository) {
        this.userRepository = userRepository;
        this.quizRepository = quizRepository;
        this.attemptRepository = attemptRepository;
        this.questionRepository = questionRepository;
    }

    public UserProfileDTO updateUserProfile(UpdateProfileRequest request) {
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));

        if (request.newUsername() != null && !request.newUsername().isEmpty()) {
            if (userRepository.existsByLogin(request.newUsername()) && 
                !request.newUsername().equals(user.getLogin())) {
                throw new IllegalArgumentException("Пользователь с таким логином уже существует");
            }
            user.setLogin(request.newUsername());
        }

        if (request.newPassword() != null && !request.newPassword().isEmpty()) {
            // TODO: Хеширование пароля
            user.setPassword(request.newPassword());
        }

        user = userRepository.save(user);

        long totalQuizzes = quizRepository.countByCreatedById(user.getId());
        long totalAttempts = attemptRepository.countByUserId(user.getId());

        return new UserProfileDTO(
                user.getId(),
                user.getLogin(),
                null, // TODO: добавить поле registrationDate в User
                (int) totalQuizzes,
                (int) totalAttempts
        );
    }

    public UserProfileDTO getUserProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));

        long totalQuizzes = quizRepository.countByCreatedById(userId);
        long totalAttempts = attemptRepository.countByUserId(userId);

        return new UserProfileDTO(
                user.getId(),
                user.getLogin(),
                null, // TODO: добавить поле registrationDate в User
                (int) totalQuizzes,
                (int) totalAttempts
        );
    }

    public UserHistoryDTO getUserHistory(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("Пользователь не найден");
        }

        List<UserQuizAttempt> attempts = attemptRepository.findByUserId(userId, 
                PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "startTime"))).getContent();

        List<AttemptSummary> summaries = attempts.stream()
                .filter(UserQuizAttempt::isCompleted)
                .map(attempt -> new AttemptSummary(
                        attempt.getId(),
                        attempt.getQuiz().getName(),
                        attempt.getScore() != null ? attempt.getScore().intValue() : 0,
                        toLocalDateTime(attempt.getFinishTime())
                ))
                .collect(Collectors.toList());

        long totalAttempts = attemptRepository.countByUserId(userId);
        double avgScore = attempts.stream()
                .filter(a -> a.getScore() != null && a.isCompleted())
                .mapToLong(a -> a.getScore())
                .average()
                .orElse(0.0);
        long bestScore = attempts.stream()
                .filter(a -> a.getScore() != null && a.isCompleted())
                .mapToLong(a -> a.getScore())
                .max()
                .orElse(0L);

        Statistics stats = new Statistics(
                (int) totalAttempts,
                avgScore,
                (int) bestScore
        );

        return new UserHistoryDTO(summaries, stats);
    }

    public List<QuizDTO> getCreatedQuizzes(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("Пользователь не найден");
        }

        return quizRepository.findByCreatedById(userId).stream()
                .map(this::toQuizDTO)
                .collect(Collectors.toList());
    }

    private QuizDTO toQuizDTO(org.example.model.Quiz quiz) {
        // Считаем реальное количество вопросов в БД
        int actualQuestionCount = (int) questionRepository.countByQuizId(quiz.getId());
        // Если вопросов еще нет, но есть запланированное количество, показываем его
        // Иначе показываем реальное количество
        int questionCount = actualQuestionCount > 0 ? actualQuestionCount : 
                           (quiz.getQuestionNumber() != null ? quiz.getQuestionNumber() : 0);
        
        // Вычисляем общее время на весь квиз в секундах (время на вопрос * количество вопросов)
        Integer totalTimeSeconds = null;
        if (quiz.getTimePerQuestion() != null && quiz.getTimePerQuestion().getSeconds() > 0) {
            long secondsPerQuestion = quiz.getTimePerQuestion().getSeconds();
            int questions = questionCount > 0 ? questionCount : (quiz.getQuestionNumber() != null ? quiz.getQuestionNumber() : 0);
            if (questions > 0) {
                totalTimeSeconds = (int) (secondsPerQuestion * questions);
            }
        }
        
        return new QuizDTO(
                quiz.getId(),
                quiz.getName(),
                quiz.getCreatedBy().getLogin(),
                questionCount,
                totalTimeSeconds,
                !quiz.isPrivate(),
                quiz.isStatic(),
                toLocalDateTime(quiz.getCreatedAt())
        );
    }

    private LocalDateTime toLocalDateTime(java.time.Instant instant) {
        return instant != null ? LocalDateTime.ofInstant(instant, ZoneId.systemDefault()) : null;
    }
}

