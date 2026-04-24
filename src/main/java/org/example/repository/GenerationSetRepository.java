package org.example.repository;

import org.example.model.GenerationSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий для работы с наборами сгенерированных вопросов.
 */
@Repository
public interface GenerationSetRepository extends JpaRepository<GenerationSet, Long> {
    /**
     * Находит набор генерации для указанного квиза.
     */
    Optional<GenerationSet> findByQuizId(Long quizId);

    /**
     * Находит все наборы генерации с указанным статусом.
     */
    List<GenerationSet> findByStatus(String status);

    /**
     * Находит все наборы генерации для указанного квиза.
     */
    List<GenerationSet> findAllByQuizId(Long quizId);
}

