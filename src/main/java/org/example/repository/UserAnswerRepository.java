package org.example.repository;

import org.example.model.UserAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Удаляет все ответы пользователей, связанные с вопросами указанного квиза.
     * Сначала обнуляет ссылки на answer_options, затем удаляет записи.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM user_answers WHERE question_id IN (SELECT id FROM questions WHERE quiz_id = :quizId)", nativeQuery = true)
    void deleteByQuestionQuizId(@Param("quizId") Long quizId);
    
    /**
     * Обнуляет ссылки на answer_options в ответах пользователей перед удалением.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE user_answers SET selected_answer_id = NULL WHERE selected_answer_id IN (SELECT ao.id FROM answer_options ao JOIN questions q ON ao.question_id = q.id WHERE q.quiz_id = :quizId)", nativeQuery = true)
    void nullifySelectedAnswerReferences(@Param("quizId") Long quizId);
    
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM user_answers WHERE attempt_id IN (SELECT id FROM user_quiz_attempts WHERE user_id = :userId AND quiz_id = :quizId AND session_id = :sessionId)", nativeQuery = true)
    int deleteByUserIdAndQuizIdAndSessionId(@Param("userId") Long userId, @Param("quizId") Long quizId, @Param("sessionId") String sessionId);
}

