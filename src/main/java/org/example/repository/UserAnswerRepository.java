package org.example.repository;

import org.example.model.UserAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий для работы с ответами пользователей.
 */
@Repository
public interface UserAnswerRepository extends JpaRepository<UserAnswer, Long> {
    /**
     * Находит все ответы для указанной попытки.
     */
    List<UserAnswer> findByAttemptId(Long attemptId);

    /**
     * Находит ответ на конкретный вопрос в попытке.
     */
    Optional<UserAnswer> findByAttemptIdAndQuestionId(Long attemptId, Long questionId);

    /**
     * Подсчитывает общее количество ответов в попытке.
     */
    long countByAttemptId(Long attemptId);

    /**
     * Подсчитывает количество правильных ответов в попытке.
     */
    long countByAttemptIdAndIsCorrectTrue(Long attemptId);

    /**
     * Проверяет, был ли дан ответ на вопрос в попытке.
     */
    boolean existsByAttemptIdAndQuestionId(Long attemptId, Long questionId);
}

