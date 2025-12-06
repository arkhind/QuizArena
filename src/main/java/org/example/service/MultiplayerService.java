package org.example.service;

import org.example.dto.request.multiplayer.*;
import org.example.dto.response.multiplayer.MultiplayerResultsDTO;
import org.example.dto.response.multiplayer.MultiplayerSessionDTO;
import org.example.dto.response.multiplayer.ParticipantsDTO;
import org.example.dto.common.ParticipantDTO;
import org.example.dto.common.PlayerResult;
import org.example.model.*;
import org.example.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Сервис для совместного прохождения квизов.
 * Путь: src/main/java/org/example/service/MultiplayerService.java
 */
@Service
@Transactional
public class MultiplayerService {
    private final AttemptService attemptService;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final MultiplayerSessionRepository sessionRepository;
    private final QuizRepository quizRepository;
    private final UserRepository userRepository;
    private final UserQuizAttemptRepository attemptRepository;
    private final UserAnswerRepository userAnswerRepository;

    @Autowired
    public MultiplayerService(
            AttemptService attemptService,
            MultiplayerSessionRepository sessionRepository,
            QuizRepository quizRepository,
            UserRepository userRepository,
            UserQuizAttemptRepository attemptRepository,
            UserAnswerRepository userAnswerRepository) {
        this.attemptService = attemptService;
        this.sessionRepository = sessionRepository;
        this.quizRepository = quizRepository;
        this.userRepository = userRepository;
        this.attemptRepository = attemptRepository;
        this.userAnswerRepository = userAnswerRepository;
    }

    /**
     * Внутренний класс для хранения состояния сессии
     */
    private static class SessionState {
        String sessionId;
        Long quizId;
        List<Long> participantIds;
        Instant startTime;
    }

    /**
     * Создает сессию для совместного прохождения квиза.
     */
    public MultiplayerSessionDTO createMultiplayerSession(CreateMultiplayerRequest request) {
        System.err.println("MultiplayerService.createMultiplayerSession: userId=" + request.userId() + ", quizId=" + request.quizId());
        User host = userRepository.findById(request.userId())
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));

        Quiz quiz = quizRepository.findById(request.quizId())
                .orElseThrow(() -> new IllegalArgumentException("Квиз не найден"));

        // Генерируем уникальный sessionId
        String sessionId = UUID.randomUUID().toString();
        System.err.println("MultiplayerService.createMultiplayerSession: создана сессия с ID: " + sessionId);

        // Создаем сессию
        MultiplayerSession session = new MultiplayerSession();
        session.setSessionId(sessionId);
        session.setQuiz(quiz);
        session.setHostUser(host);
        session.setStatus("WAITING");
        session.setCreatedAt(Instant.now());
        session = sessionRepository.save(session);
        System.err.println("MultiplayerService.createMultiplayerSession: сессия сохранена в БД с ID: " + session.getId());

        // Добавляем хоста как участника (создаем попытку с sessionId)
        UserQuizAttempt hostAttempt = new UserQuizAttempt();
        hostAttempt.setUser(host);
        hostAttempt.setQuiz(quiz);
        hostAttempt.setStartTime(null); // Начнется при старте сессии
        hostAttempt.setCompleted(false);
        hostAttempt.setScore(null);
        hostAttempt.setSessionId(sessionId);
        attemptRepository.save(hostAttempt);

        // Формируем joinLink
        String joinLink = "/multiplayer/join?sessionId=" + sessionId;

        // Получаем участников (пока только хост)
        List<ParticipantDTO> participants = new ArrayList<>();
        participants.add(new ParticipantDTO(
                host.getId(),
                host.getLogin(),
                toLocalDateTime(session.getCreatedAt())
        ));

        return new MultiplayerSessionDTO(
                sessionId,
                quiz.getName(),
                host.getId(),
                joinLink,
                participants,
                session.getStatus(),
                toLocalDateTime(session.getCreatedAt())
        );
    }

    /**
     * Получает информацию о сессии.
     */
    public MultiplayerSessionDTO getMultiplayerSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("SessionId не может быть пустым");
        }
        System.err.println("MultiplayerService.getMultiplayerSession: ищем сессию с ID: " + sessionId);
        MultiplayerSession session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> {
                    System.err.println("MultiplayerService.getMultiplayerSession: сессия не найдена: " + sessionId);
                    return new IllegalArgumentException("Сессия не найдена: " + sessionId);
                });
        System.err.println("MultiplayerService.getMultiplayerSession: сессия найдена, статус: " + session.getStatus());

        // Получаем участников через попытки с этим sessionId
        List<UserQuizAttempt> sessionAttempts = attemptRepository.findBySessionIdWithUser(sessionId);

        List<ParticipantDTO> participantDTOs = sessionAttempts.stream()
                .filter(a -> a.getUser() != null)
                .map(attempt -> new ParticipantDTO(
                        attempt.getUser().getId(),
                        attempt.getUser().getLogin(),
                        toLocalDateTime(attempt.getStartTime() != null 
                                ? attempt.getStartTime() 
                                : session.getCreatedAt())
                ))
                .collect(Collectors.toList());

        String joinLink = "/multiplayer/join?sessionId=" + sessionId;

        return new MultiplayerSessionDTO(
                session.getSessionId(),
                session.getQuiz().getName(),
                session.getHostUser().getId(),
                joinLink,
                participantDTOs,
                session.getStatus(),
                toLocalDateTime(session.getCreatedAt())
        );
    }

    /**
     * Подключает пользователя к сессии.
     */
    public boolean joinMultiplayerSession(JoinMultiplayerRequest request) {
        MultiplayerSession session = sessionRepository.findBySessionId(request.sessionId())
                .orElseThrow(() -> new IllegalArgumentException("Сессия не найдена"));

        if (!"WAITING".equals(session.getStatus())) {
            throw new IllegalStateException("Сессия уже начата или отменена");
        }

        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));

        // Проверяем, не присоединился ли уже (ищем попытку с этим sessionId и userId)
        List<UserQuizAttempt> attempts = attemptRepository.findBySessionIdWithUser(session.getSessionId());
        boolean alreadyJoined = attempts.stream()
                .anyMatch(a -> a.getUser() != null && user.getId().equals(a.getUser().getId()));

        if (alreadyJoined) {
            return false; // Уже участник
        }

        // Добавляем участника (создаем попытку с sessionId)
        UserQuizAttempt participantAttempt = new UserQuizAttempt();
        participantAttempt.setUser(user);
        participantAttempt.setQuiz(session.getQuiz());
        participantAttempt.setStartTime(null); // Начнется при старте сессии
        participantAttempt.setCompleted(false);
        participantAttempt.setScore(null);
        participantAttempt.setSessionId(session.getSessionId());
        attemptRepository.save(participantAttempt);

        return true;
    }

    /**
     * Получает список участников сессии.
     */
    public ParticipantsDTO getSessionParticipants(String sessionId) {
        MultiplayerSession session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Сессия не найдена"));

        // Получаем участников через попытки с этим sessionId
        List<UserQuizAttempt> sessionAttempts = attemptRepository.findBySessionIdWithUser(sessionId);

        List<ParticipantDTO> participantDTOs = sessionAttempts.stream()
                .filter(a -> a.getUser() != null)
                .map(attempt -> new ParticipantDTO(
                        attempt.getUser().getId(),
                        attempt.getUser().getLogin(),
                        toLocalDateTime(attempt.getStartTime() != null 
                                ? attempt.getStartTime() 
                                : session.getCreatedAt())
                ))
                .collect(Collectors.toList());

        return new ParticipantsDTO(
                sessionId,
                participantDTOs,
                participantDTOs.size()
        );
    }

    /**
     * Запускает сессию для всех участников.
     */
    public boolean startMultiplayerSession(StartMultiplayerRequest request) {
        MultiplayerSession session = sessionRepository.findBySessionId(request.sessionId())
                .orElseThrow(() -> new IllegalArgumentException("Сессия не найдена"));

        // Проверяем, что пользователь - хост
        if (!session.getHostUser().getId().equals(request.hostUserId())) {
            throw new SecurityException("Только хост может запустить сессию");
        }

        if (!"WAITING".equals(session.getStatus())) {
            throw new IllegalStateException("Сессия уже начата или отменена");
        }

        // Получаем участников через попытки с этим sessionId
        String sessionIdStr = request.sessionId();
        List<UserQuizAttempt> attempts = attemptRepository.findByQuizId(session.getQuiz().getId());
        List<UserQuizAttempt> sessionAttempts = attempts.stream()
                .filter(a -> sessionIdStr != null && sessionIdStr.equals(a.getSessionId()))
                .collect(Collectors.toList());

        // Проверяем минимальное количество участников
        if (sessionAttempts.size() < 2) {
            throw new IllegalStateException("Недостаточно участников для начала");
        }

        // Запускаем сессию
        session.setStatus("STARTED");
        session.setStartedAt(Instant.now());
        sessionRepository.save(session);

        // Обновляем попытки участников (устанавливаем startTime)
        Instant now = Instant.now();
        for (UserQuizAttempt attempt : sessionAttempts) {
            attempt.setStartTime(now);
            attemptRepository.save(attempt);
        }

        return true;
    }

    /**
     * Получает результаты совместного прохождения.
     */
    public MultiplayerResultsDTO getMultiplayerResults(String sessionId) {
        MultiplayerSession session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Сессия не найдена"));

        if (session.getQuiz() == null) {
            throw new IllegalStateException("Сессия не содержит квиз");
        }

        List<UserQuizAttempt> attempts = attemptRepository.findBySessionIdWithUser(sessionId);
        if (attempts == null || attempts.isEmpty()) {
            return new MultiplayerResultsDTO(
                    sessionId,
                    new ArrayList<>(),
                    null,
                    session.getQuiz().getName(),
                    null
            );
        }
        
        List<UserQuizAttempt> completedAttempts = attempts.stream()
                .filter(a -> a != null && a.isCompleted())
                .collect(Collectors.toList());

        if (completedAttempts.size() >= 2 && !"FINISHED".equals(session.getStatus())) {
            session.setStatus("FINISHED");
            session.setFinishedAt(Instant.now());
            sessionRepository.save(session);
        }

        // Получаем всех участников с их результатами (используем уже отфильтрованные completedAttempts)
        // Если не все завершили, показываем результаты тех, кто завершил
        List<UserQuizAttempt> sessionAttempts = completedAttempts;

        // Формируем результаты
        List<PlayerResult> results = new ArrayList<>();
        for (UserQuizAttempt attempt : sessionAttempts) {
            if (attempt.getUser() == null || attempt.getUser().getLogin() == null) {
                System.err.println("MultiplayerService: Пропущен attempt с null user или login, attemptId=" + attempt.getId());
                continue;
            }
            
            int score = attempt.getScore() != null ? attempt.getScore().intValue() : 0;
            long timeSpent = 0;
            if (attempt.getStartTime() != null && attempt.getFinishTime() != null) {
                timeSpent = java.time.Duration.between(
                        attempt.getStartTime(),
                        attempt.getFinishTime()
                ).getSeconds();
            }

            results.add(new PlayerResult(
                    0, // position будет установлен после сортировки
                    attempt.getUser().getLogin(),
                    score,
                    timeSpent
            ));
        }

        // Сортируем по счету (убывание), затем по времени (возрастание)
        results.sort((a, b) -> {
            int scoreCompare = Integer.compare(b.score(), a.score());
            if (scoreCompare != 0) return scoreCompare;
            return Long.compare(a.timeSpent(), b.timeSpent());
        });

        // Обновляем позиции
        List<PlayerResult> finalResults = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            finalResults.add(new PlayerResult(
                    i + 1,
                    results.get(i).username(),
                    results.get(i).score(),
                    results.get(i).timeSpent()
            ));
        }

        if (session.getQuiz() == null) {
            throw new IllegalStateException("Сессия не содержит квиз");
        }
        
        return new MultiplayerResultsDTO(
                sessionId,
                finalResults,
                null, // userPosition - можно вычислить если передать userId
                session.getQuiz().getName(),
                toLocalDateTime(session.getFinishedAt())
        );
    }

    /**
     * Отменяет сессию.
     */
    public boolean cancelMultiplayerSession(CancelMultiplayerRequest request) {
        MultiplayerSession session = sessionRepository.findBySessionId(request.sessionId())
                .orElseThrow(() -> new IllegalArgumentException("Сессия не найдена"));

        // Проверяем, что пользователь - хост
        if (!session.getHostUser().getId().equals(request.hostUserId())) {
            throw new SecurityException("Только хост может отменить сессию");
        }

        if ("FINISHED".equals(session.getStatus())) {
            throw new IllegalStateException("Сессия уже завершена");
        }

        session.setStatus("CANCELLED");
        sessionRepository.save(session);

        return true;
    }

    /**
     * Получает прогресс игроков в сессии для текущего вопроса.
     */
    public Map<String, Object> getSessionProgress(String sessionId, Long questionId) {
        MultiplayerSession session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Сессия не найдена"));

        List<UserQuizAttempt> attempts = attemptRepository.findBySessionId(sessionId);
        if (attempts.size() < 2) {
            return Map.of(
                    "bothAnswered", false,
                    "currentQuestionAnswered", false,
                    "currentQuestionId", questionId != null ? questionId : 0L
            );
        }

        boolean bothAnswered = true;
        boolean currentQuestionAnswered = false;

        for (UserQuizAttempt attempt : attempts) {
            if (questionId != null) {
                boolean answered = userAnswerRepository.existsByAttemptIdAndQuestionId(attempt.getId(), questionId);
                if (!answered) {
                    bothAnswered = false;
                } else {
                    currentQuestionAnswered = true;
                }
            } else {
                bothAnswered = false;
            }
        }

        return Map.of(
                "bothAnswered", bothAnswered,
                "currentQuestionAnswered", currentQuestionAnswered,
                "currentQuestionId", questionId != null ? questionId : 0L
        );
    }

    // ========== Вспомогательные методы ==========

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant != null
                ? LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                : null;
    }
}
