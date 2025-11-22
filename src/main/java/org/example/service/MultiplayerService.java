package org.example.service;

import org.example.dto.request.multiplayer.*;
import org.example.dto.response.multiplayer.*;
import org.example.dto.common.ParticipantDTO;
import org.example.dto.common.PlayerResult;
import org.example.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Сервис для управления многопользовательскими сессиями квизов.
 * Позволяет создавать сессии, подключать участников, запускать и завершать игры.
 */
@Service
@Transactional
public class MultiplayerService {
  private final DatabaseService databaseService;
  private final AttemptService attemptService;

  private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

  public MultiplayerService(DatabaseService databaseService, AttemptService attemptService) {
    this.databaseService = databaseService;
    this.attemptService = attemptService;
  }

  /**
   * Внутренний класс для отслеживания состояния сессии в памяти
   */
  private static class SessionState {
    String sessionId;
    Long quizId;
    String quizName;
    Long hostUserId;
    String status;
    Instant createdAt;
    Map<Long, ParticipantInfo> participants;
    Map<Long, Long> userAttempts;
    Instant startedAt;
    Instant finishedAt;

    SessionState(String sessionId, Long quizId, String quizName, Long hostUserId) {
      this.sessionId = sessionId;
      this.quizId = quizId;
      this.quizName = quizName;
      this.hostUserId = hostUserId;
      this.status = "WAITING";
      this.createdAt = Instant.now();
      this.participants = new HashMap<>();
      this.userAttempts = new HashMap<>();
      this.participants.put(hostUserId, new ParticipantInfo(hostUserId, createdAt));
    }
  }

  /**
   * Информация об участнике сессии
   */
  private static class ParticipantInfo {
    Long userId;
    Instant joinedAt;

    ParticipantInfo(Long userId, Instant joinedAt) {
      this.userId = userId;
      this.joinedAt = joinedAt;
    }
  }

  /**
   * Создает новую сессию для совместного прохождения квиза.
   * Генерирует уникальный идентификатор сессии.
   */
  public MultiplayerSessionDTO createMultiplayerSession(CreateMultiplayerRequest request) {
    Long userId = request.userId();
    Long quizId = request.quizId();

    User user = getUserById(userId);
    if (user == null) {
      throw new RuntimeException("User not found: " + userId);
    }

    Quiz quiz = getQuizById(quizId);
    if (quiz == null) {
      throw new RuntimeException("Quiz not found: " + quizId);
    }

    if (quiz.isPrivate()) {
      throw new RuntimeException("Quiz is private and not accessible");
    }

    String sessionId = generateSessionId();

    SessionState session = new SessionState(sessionId, quizId, quiz.getName(), userId);
    sessions.put(sessionId, session);

    String joinLink = "/multiplayer/join/" + sessionId;

    List<ParticipantDTO> participants = getParticipantsList(session);

    return new MultiplayerSessionDTO(
      sessionId,
      quiz.getName(),
      userId,
      joinLink,
      participants,
      session.status,
      LocalDateTime.ofInstant(session.createdAt, ZoneOffset.UTC)
    );
  }

  /**
   * Получает информацию о сессии по её идентификатору.
   */
  public MultiplayerSessionDTO getMultiplayerSession(String sessionId) {
    SessionState session = sessions.get(sessionId);
    if (session == null) {
      throw new RuntimeException("Session not found: " + sessionId);
    }

    List<ParticipantDTO> participants = getParticipantsList(session);

    return new MultiplayerSessionDTO(
      session.sessionId,
      session.quizName,
      session.hostUserId,
      "/multiplayer/join/" + sessionId,
      participants,
      session.status,
      LocalDateTime.ofInstant(session.createdAt, ZoneOffset.UTC)
    );
  }

  /**
   * Подключает пользователя к сессии.
   */
  public boolean joinMultiplayerSession(JoinMultiplayerRequest request) {
    Long userId = request.userId();
    String sessionId = request.sessionId();

    SessionState session = sessions.get(sessionId);
    if (session == null) {
      throw new RuntimeException("Session not found: " + sessionId);
    }

    if (!"WAITING".equals(session.status)) {
      throw new IllegalStateException("Session is not in WAITING state. Current status: " + session.status);
    }

    User user = getUserById(userId);
    if (user == null) {
      throw new RuntimeException("User not found: " + userId);
    }

    if (session.participants.containsKey(userId)) {
      return true;
    }

    session.participants.put(userId, new ParticipantInfo(userId, Instant.now()));

    return true;
  }

  /**
   * Получает список участников сессии.
   */
  public ParticipantsDTO getSessionParticipants(String sessionId) {
    SessionState session = sessions.get(sessionId);
    if (session == null) {
      throw new RuntimeException("Session not found: " + sessionId);
    }

    List<ParticipantDTO> participants = getParticipantsList(session);

    return new ParticipantsDTO(
      sessionId,
      participants,
      participants.size()
    );
  }

  /**
   * Запускает сессию для всех подключенных участников.
   * Создает попытки прохождения для каждого участника.
   */
  public boolean startMultiplayerSession(StartMultiplayerRequest request) {
    String sessionId = request.sessionId();
    Long hostUserId = request.hostUserId();

    SessionState session = sessions.get(sessionId);
    if (session == null) {
      throw new RuntimeException("Session not found: " + sessionId);
    }

    if (!session.hostUserId.equals(hostUserId)) {
      throw new RuntimeException("Access denied: Only host can start the session");
    }

    if (!"WAITING".equals(session.status)) {
      throw new IllegalStateException("Session cannot be started. Current status: " + session.status);
    }

    if (session.participants.size() < 1) {
      throw new IllegalStateException("Not enough participants to start session. Minimum: 1");
    }

    Instant startTime = Instant.now();
    for (Long userId : session.participants.keySet()) {
      try {
        Long attemptId = createAttempt(userId, session.quizId, startTime);
        session.userAttempts.put(userId, attemptId);
      } catch (Exception e) {
        System.err.println("Error creating attempt for user " + userId + ": " + e.getMessage());
      }
    }

    session.status = "STARTED";
    session.startedAt = startTime;

    return true;
  }

  /**
   * Получает результаты совместного прохождения квиза.
   */
  public MultiplayerResultsDTO getMultiplayerResults(String sessionId) {
    SessionState session = sessions.get(sessionId);
    if (session == null) {
      throw new RuntimeException("Session not found: " + sessionId);
    }

    if (!"FINISHED".equals(session.status)) {
      throw new IllegalStateException("Session is not finished yet. Current status: " + session.status);
    }

    List<PlayerResult> results = new ArrayList<>();
    for (Map.Entry<Long, Long> entry : session.userAttempts.entrySet()) {
      Long userId = entry.getKey();
      Long attemptId = entry.getValue();

      UserQuizAttempt attempt = getAttemptById(attemptId);
      if (attempt != null && attempt.isCompleted()) {
        User user = getUserById(userId);
        String username = user != null ? user.getLogin() : "Unknown";

        long timeSpent = 0;
        if (attempt.getStartTime() != null && attempt.getFinishTime() != null) {
          timeSpent = java.time.Duration.between(attempt.getStartTime(), attempt.getFinishTime()).getSeconds();
        }

        results.add(new PlayerResult(
          null,
          username,
          attempt.getScore() != null ? attempt.getScore().intValue() : 0,
          timeSpent
        ));
      }
    }

    results.sort((a, b) -> {
      int scoreCompare = Integer.compare(b.score(), a.score());
      if (scoreCompare != 0) {
        return scoreCompare;
      }
      return Long.compare(a.timeSpent(), b.timeSpent());
    });

    List<PlayerResult> resultsWithPositions = new ArrayList<>();
    for (int i = 0; i < results.size(); i++) {
      PlayerResult result = results.get(i);
      resultsWithPositions.add(new PlayerResult(
        i + 1,
        result.username(),
        result.score(),
        result.timeSpent()
      ));
    }

    Integer userPosition = resultsWithPositions.isEmpty() ? null : 1;

    return new MultiplayerResultsDTO(
      sessionId,
      resultsWithPositions,
      userPosition,
      session.quizName,
      session.finishedAt != null ? LocalDateTime.ofInstant(session.finishedAt, ZoneOffset.UTC) : null
    );
  }

  /**
   * Отменяет сессию совместного прохождения.
   */
  public boolean cancelMultiplayerSession(CancelMultiplayerRequest request) {
    String sessionId = request.sessionId();
    Long hostUserId = request.hostUserId();

    SessionState session = sessions.get(sessionId);
    if (session == null) {
      throw new RuntimeException("Session not found: " + sessionId);
    }

    if (!session.hostUserId.equals(hostUserId)) {
      throw new RuntimeException("Access denied: Only host can cancel the session");
    }

    if ("CANCELLED".equals(session.status) || "FINISHED".equals(session.status)) {
      return false;
    }

    session.status = "CANCELLED";

    return true;
  }

  /**
   * Помечает сессию как завершенную (вызывается после завершения всех попыток).
   */
  public void markSessionAsFinished(String sessionId) {
    SessionState session = sessions.get(sessionId);
    if (session != null && "STARTED".equals(session.status)) {
      session.status = "FINISHED";
      session.finishedAt = Instant.now();
    }
  }

  // Вспомогательные методы

  /**
   * Генерирует уникальный идентификатор сессии.
   */
  private String generateSessionId() {
    String sessionId;
    do {
      sessionId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    } while (sessions.containsKey(sessionId));
    return sessionId;
  }

  /**
   * Получает список участников в формате DTO.
   */
  private List<ParticipantDTO> getParticipantsList(SessionState session) {
    return session.participants.values().stream()
      .map(participant -> {
        User user = getUserById(participant.userId);
        String username = user != null ? user.getLogin() : "Unknown";
        return new ParticipantDTO(
          participant.userId,
          username,
          LocalDateTime.ofInstant(participant.joinedAt, ZoneOffset.UTC)
        );
      })
      .sorted(Comparator.comparing(ParticipantDTO::joinedAt))
      .collect(Collectors.toList());
  }

  // Операции с базой данных

  private User getUserById(Long userId) {
    String sql = "SELECT id, login, password FROM \"User\" WHERE id = ?";
    try (Connection connection = getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet rs = statement.executeQuery()) {
        if (rs.next()) {
          User user = new User();
          user.setId(rs.getLong("id"));
          user.setLogin(rs.getString("login"));
          user.setPassword(rs.getString("password"));
          return user;
        }
      }
    } catch (SQLException e) {
      System.err.println("Error fetching user: " + e.getMessage());
    }
    return null;
  }

  private Quiz getQuizById(Long quizId) {
    String sql = "SELECT q.id, q.name, q.prompt, q.create_by, q.has_material, q.material_url, " +
      "q.time_per_question_seconds, q.is_private, q.is_static, q.created_at, " +
      "u.id as user_id, u.login " +
      "FROM \"Quiz\" q " +
      "LEFT JOIN \"User\" u ON q.create_by = u.id " +
      "WHERE q.id = ?";
    try (Connection connection = getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, quizId);
      try (ResultSet rs = statement.executeQuery()) {
        if (rs.next()) {
          Quiz quiz = new Quiz();
          quiz.setId(rs.getLong("id"));
          quiz.setName(rs.getString("name"));
          quiz.setPrompt(rs.getString("prompt"));
          User creator = new User();
          creator.setId(rs.getLong("user_id"));
          creator.setLogin(rs.getString("login"));
          quiz.setCreatedBy(creator);
          quiz.setHasMaterial(rs.getBoolean("has_material"));
          quiz.setMaterialUrl(rs.getString("material_url"));

          Integer seconds = rs.getObject("time_per_question_seconds", Integer.class);
          if (seconds != null) {
            quiz.setTimePerQuestion(java.time.Duration.ofSeconds(seconds));
          }

          quiz.setPrivate(rs.getBoolean("is_private"));
          quiz.setStatic(rs.getBoolean("is_static"));

          java.time.OffsetDateTime odt = rs.getObject("created_at", java.time.OffsetDateTime.class);
          quiz.setCreatedAt(odt != null ? odt.toInstant() : null);

          return quiz;
        }
      }
    } catch (SQLException e) {
      System.err.println("Error fetching quiz: " + e.getMessage());
    }
    return null;
  }

  private Long createAttempt(Long userId, Long quizId, Instant startTime) {
    String sql = "INSERT INTO \"UserQuizAttempt\" (user_id, quiz_id, start_time, finish_time, score, is_completed) " +
      "VALUES (?, ?, ?, ?, ?, ?) RETURNING id";
    try (Connection connection = getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      statement.setLong(2, quizId);
      statement.setTimestamp(3, Timestamp.from(startTime));
      statement.setTimestamp(4, Timestamp.from(startTime));
      statement.setLong(5, 0L);
      statement.setBoolean(6, false);

      try (ResultSet rs = statement.executeQuery()) {
        if (rs.next()) {
          return rs.getLong("id");
        }
      }
    } catch (SQLException e) {
      System.err.println("Error creating attempt: " + e.getMessage());
      throw new RuntimeException("Failed to create attempt", e);
    }
    return null;
  }

  private UserQuizAttempt getAttemptById(Long attemptId) {
    String sql = "SELECT id, user_id, quiz_id, start_time, finish_time, score, is_completed " +
      "FROM \"UserQuizAttempt\" WHERE id = ?";
    try (Connection connection = getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, attemptId);
      try (ResultSet rs = statement.executeQuery()) {
        if (rs.next()) {
          UserQuizAttempt attempt = new UserQuizAttempt();
          attempt.setId(rs.getLong("id"));
          User user = new User();
          user.setId(rs.getLong("user_id"));
          attempt.setUser(user);
          Quiz quiz = new Quiz();
          quiz.setId(rs.getLong("quiz_id"));
          attempt.setQuiz(quiz);
          attempt.setStartTime(rs.getTimestamp("start_time").toInstant());
          Timestamp finishTime = rs.getTimestamp("finish_time");
          attempt.setFinishTime(finishTime != null ? finishTime.toInstant() : null);
          attempt.setScore(rs.getLong("score"));
          attempt.setCompleted(rs.getBoolean("is_completed"));
          return attempt;
        }
      }
    } catch (SQLException e) {
      System.err.println("Error fetching attempt: " + e.getMessage());
    }
    return null;
  }

  private Connection getConnection() throws SQLException {
    String url = "jdbc:postgresql://localhost:5432/quizarena";
    String username = "quizuser";
    String password = "quizpass";
    return DriverManager.getConnection(url, username, password);
  }
}
