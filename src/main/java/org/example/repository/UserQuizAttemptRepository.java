package org.example.repository;

import org.example.model.UserQuizAttempt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserQuizAttemptRepository extends JpaRepository<UserQuizAttempt, Long> {
    Page<UserQuizAttempt> findByUserId(Long userId, Pageable pageable);
    List<UserQuizAttempt> findByQuizId(Long quizId);
    
    /**
     * Находит все завершенные попытки для указанного квиза, отсортированные по счету (убывание).
     *
     * @param quizId ID квиза
     * @param pageable параметры пагинации
     * @return страница с попытками
     */
    @Query("SELECT u FROM UserQuizAttempt u WHERE u.quiz.id = :quizId AND u.isCompleted = true " +
           "ORDER BY u.score DESC NULLS LAST, u.finishTime ASC")
    Page<UserQuizAttempt> findCompletedByQuizIdOrderByScoreDesc(@Param("quizId") Long quizId, Pageable pageable);

    /**
     * Находит ID квиза по ID попытки прохождения.
     *
     * @param attemptId ID попытки прохождения
     * @return ID квиза или null если попытка не найдена
     */
    @Query("SELECT u.quiz.id FROM UserQuizAttempt u WHERE u.id = :attemptId")
    Long findQuizIdByAttemptId(@Param("attemptId") Long attemptId);
  
     * Находит лучший результат каждого пользователя по квизу (только лучший результат для каждого пользователя).
     */
    @Query("SELECT u FROM UserQuizAttempt u WHERE u.quiz.id = :quizId AND u.isCompleted = true " +
           "AND u.score = (SELECT MAX(u2.score) FROM UserQuizAttempt u2 WHERE u2.quiz.id = :quizId " +
           "AND u2.user.id = u.user.id AND u2.isCompleted = true) " +
           "ORDER BY u.score DESC NULLS LAST, u.finishTime ASC")
    Page<UserQuizAttempt> findBestAttemptsByQuizId(@Param("quizId") Long quizId, Pageable pageable);

    long countByUserId(Long userId);
}

