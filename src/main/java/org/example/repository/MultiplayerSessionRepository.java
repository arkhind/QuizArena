package org.example.repository;

import org.example.model.MultiplayerSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий для работы с сессиями мультиплеера.
 * Путь: src/main/java/org/example/repository/MultiplayerSessionRepository.java
 */
@Repository
public interface MultiplayerSessionRepository extends JpaRepository<MultiplayerSession, Long> {
    /**
     * Находит сессию по уникальному sessionId.
     */
    Optional<MultiplayerSession> findBySessionId(String sessionId);

    /**
     * Находит все сессии, созданные указанным пользователем.
     */
    List<MultiplayerSession> findByHostUserId(Long userId);

    /**
     * Находит все сессии с указанным статусом.
     */
    List<MultiplayerSession> findByStatus(String status);

    /**
     * Проверяет существование сессии по sessionId.
     */
    boolean existsBySessionId(String sessionId);
}

