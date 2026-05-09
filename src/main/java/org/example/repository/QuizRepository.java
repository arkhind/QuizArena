package org.example.repository;

import org.example.model.Quiz;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface QuizRepository extends JpaRepository<Quiz, Long> {
    Page<Quiz> findByIsPrivateFalse(Pageable pageable);
    List<Quiz> findByCreatedById(Long userId);

    @Query("SELECT q FROM Quiz q WHERE q.id = :id AND q.isPrivate = false")
    Optional<Quiz> findPublicById(@Param("id") Long id);

    /**
     * Ищет публичные квизы по названию.
     *
     * @param searchTerm поисковый запрос
     * @param pageable параметры пагинации
     * @return страница с найденными квизами
     */
    @Query("SELECT q FROM Quiz q WHERE q.isPrivate = false AND " +
           "LOWER(q.name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    Page<Quiz> searchPublicQuizzes(@Param("searchTerm") String searchTerm, Pageable pageable);

    @Query(
            value = "SELECT q FROM Quiz q LEFT JOIN UserQuizAttempt u ON u.quiz = q " +
                    "AND u.isCompleted = true AND u.startTime IS NOT NULL AND u.finishTime IS NOT NULL " +
                    "WHERE q.isPrivate = false GROUP BY q ORDER BY COUNT(u) ASC, q.createdAt DESC",
            countQuery = "SELECT COUNT(q) FROM Quiz q WHERE q.isPrivate = false"
    )
    Page<Quiz> findPublicOrderByCompletedAttemptsAsc(Pageable pageable);

    @Query(
            value = "SELECT q FROM Quiz q LEFT JOIN UserQuizAttempt u ON u.quiz = q " +
                    "AND u.isCompleted = true AND u.startTime IS NOT NULL AND u.finishTime IS NOT NULL " +
                    "WHERE q.isPrivate = false GROUP BY q ORDER BY COUNT(u) DESC, q.createdAt DESC",
            countQuery = "SELECT COUNT(q) FROM Quiz q WHERE q.isPrivate = false"
    )
    Page<Quiz> findPublicOrderByCompletedAttemptsDesc(Pageable pageable);

    @Query(
            value = "SELECT q FROM Quiz q LEFT JOIN UserQuizAttempt u ON u.quiz = q " +
                    "AND u.isCompleted = true AND u.startTime IS NOT NULL AND u.finishTime IS NOT NULL " +
                    "WHERE q.isPrivate = false AND LOWER(q.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
                    "GROUP BY q ORDER BY COUNT(u) ASC, q.createdAt DESC",
            countQuery = "SELECT COUNT(q) FROM Quiz q WHERE q.isPrivate = false AND " +
                    "LOWER(q.name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))"
    )
    Page<Quiz> searchPublicOrderByCompletedAttemptsAsc(@Param("searchTerm") String searchTerm, Pageable pageable);

    @Query(
            value = "SELECT q FROM Quiz q LEFT JOIN UserQuizAttempt u ON u.quiz = q " +
                    "AND u.isCompleted = true AND u.startTime IS NOT NULL AND u.finishTime IS NOT NULL " +
                    "WHERE q.isPrivate = false AND LOWER(q.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
                    "GROUP BY q ORDER BY COUNT(u) DESC, q.createdAt DESC",
            countQuery = "SELECT COUNT(q) FROM Quiz q WHERE q.isPrivate = false AND " +
                    "LOWER(q.name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))"
    )
    Page<Quiz> searchPublicOrderByCompletedAttemptsDesc(@Param("searchTerm") String searchTerm, Pageable pageable);

    /**
     * Проверяет, является ли пользователь создателем квиза.
     *
     * @param quizId ID квиза
     * @param userId ID пользователя
     * @return true если пользователь является создателем
     */
    @Query("SELECT COUNT(q) > 0 FROM Quiz q WHERE q.id = :quizId AND q.createdBy.id = :userId")
    boolean isCreator(@Param("quizId") Long quizId, @Param("userId") Long userId);
    
    long countByCreatedById(Long userId);
}
