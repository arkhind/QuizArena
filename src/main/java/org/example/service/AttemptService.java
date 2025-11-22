package org.example.service;

import org.example.dto.common.AnswerOption;
import org.example.dto.request.attempt.StartAttemptRequest;
import org.example.dto.request.attempt.SubmitAnswerRequest;
import org.example.dto.response.attempt.AnswerResponse;
import org.example.dto.response.attempt.AttemptResponse;
import org.example.dto.response.attempt.QuizResultDTO;
import org.example.dto.response.quiz.QuestionDTO;
import org.example.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сервис для управления попытками прохождения квизов.
 * Позволяет запускать попытки, отслеживать прогресс, отправлять ответы и завершать попытки.
 */
@Service
@Transactional
public class AttemptService {
  private final DatabaseService databaseService;

  private final Map<Long, AttemptState> attemptStates = new ConcurrentHashMap<>();

  public AttemptService(DatabaseService databaseService) {
    this.databaseService = databaseService;
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

    AttemptState(Long attemptId, Long userId, Long quizId, List<Long> questionIds, Instant startTime) {
      this.attemptId = attemptId;
      this.userId = userId;
      this.quizId = quizId;
      this.questionIds = new ArrayList<>(questionIds);
      this.currentQuestionIndex = 0;
      this.answers = new HashMap<>();
      this.answerResults = new HashMap<>();
      this.startTime = startTime;
      this.score = 0;
    }
  }

  /**
   * Запускает новую попытку прохождения квиза.
   * Для статичных квизов используется существующие вопросы, для нестатичных генерируются новые.
   */
  public AttemptResponse startQuizAttempt(StartAttemptRequest request) {
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

    List<Question> questions = getQuestionsByQuizId(quizId);
    if (questions.isEmpty()) {
      throw new RuntimeException("Quiz has no questions");
    }

    List<Long> questionIds = new ArrayList<>();
    for (Question question : questions) {
      questionIds.add(question.getId());
    }

    if (!quiz.isStatic()) {
      Collections.shuffle(questionIds);
    }

    Instant startTime = Instant.now();
    Long attemptId = createAttempt(userId, quizId, startTime);

    AttemptState state = new AttemptState(attemptId, userId, quizId, questionIds, startTime);
    attemptStates.put(attemptId, state);

    Question firstQuestion = getQuestionById(questionIds.get(0));
    QuestionDTO firstQuestionDTO = convertToQuestionDTO(firstQuestion, quiz);

    Integer timeRemaining = null;
    if (quiz.getTimePerQuestion() != null) {
      timeRemaining = (int) quiz.getTimePerQuestion().getSeconds();
    }

    return new AttemptResponse(
      attemptId,
      quizId,
      quiz.getName(),
      firstQuestionDTO,
      questionIds.size() - 1,
      timeRemaining
    );
  }

  /**
   * Получает следующий вопрос в текущей попытке.
   */
  public QuestionDTO getNextQuestion(Long attemptId) {
    AttemptState state = attemptStates.get(attemptId);
    if (state == null) {
      throw new RuntimeException("Attempt not found: " + attemptId);
    }

    UserQuizAttempt attempt = getAttemptById(attemptId);
    if (attempt != null && attempt.isCompleted()) {
      throw new IllegalStateException("Attempt is already completed");
    }

    state.currentQuestionIndex++;

    if (state.currentQuestionIndex >= state.questionIds.size()) {
      return null;
    }

    Long nextQuestionId = state.questionIds.get(state.currentQuestionIndex);
    Question nextQuestion = getQuestionById(nextQuestionId);
    Quiz quiz = getQuizById(state.quizId);

    return convertToQuestionDTO(nextQuestion, quiz);
  }

  /**
   * Отправляет ответ на текущий вопрос в попытке.
   */
  public AnswerResponse submitAnswer(SubmitAnswerRequest request) {
    Long attemptId = request.attemptId();
    Long questionId = request.questionId();
    Long selectedAnswerId = request.selectedAnswerId();

    AttemptState state = attemptStates.get(attemptId);
    if (state == null) {
      throw new RuntimeException("Attempt not found: " + attemptId);
    }

    if (state.currentQuestionIndex >= state.questionIds.size()) {
      throw new IllegalStateException("No current question in attempt");
    }

    Long currentQuestionId = state.questionIds.get(state.currentQuestionIndex);
    if (!currentQuestionId.equals(questionId)) {
      throw new IllegalStateException("Question ID does not match current question");
    }

    Question question = getQuestionById(questionId);
    AnswerOption correctAnswer = getCorrectAnswer(questionId);

    boolean isCorrect = correctAnswer != null && correctAnswer.id().equals(selectedAnswerId);

    state.answers.put(questionId, selectedAnswerId);
    state.answerResults.put(questionId, isCorrect);

    if (isCorrect) {
      state.score++;
    }

    QuestionDTO nextQuestion = null;
    int questionsRemaining = state.questionIds.size() - state.currentQuestionIndex - 1;

    if (questionsRemaining > 0) {
      state.currentQuestionIndex++;
      Long nextQuestionId = state.questionIds.get(state.currentQuestionIndex);
      Question nextQuestionEntity = getQuestionById(nextQuestionId);
      Quiz quiz = getQuizById(state.quizId);
      nextQuestion = convertToQuestionDTO(nextQuestionEntity, quiz);
    }

    return new AnswerResponse(
      isCorrect,
      question.getExplanation(),
      correctAnswer != null ? correctAnswer.id() : null,
      isCorrect ? 1 : 0,
      nextQuestion
    );
  }

  /**
   * Завершает попытку прохождения квиза и рассчитывает итоговые результаты.
   */
  public QuizResultDTO finishQuizAttempt(Long attemptId) {
    AttemptState state = attemptStates.get(attemptId);
    if (state == null) {
      throw new RuntimeException("Attempt not found: " + attemptId);
    }

    Instant finishTime = Instant.now();
    updateAttempt(attemptId, finishTime, (long) state.score, true);

    int correctAnswers = (int) state.answerResults.values().stream()
      .filter(Boolean::booleanValue)
      .count();
    int totalQuestions = state.questionIds.size();

    long timeSpent = Duration.between(state.startTime, finishTime).getSeconds();

    int position = getLeaderboardPosition(state.quizId, state.userId, state.score);

    attemptStates.remove(attemptId);

    return new QuizResultDTO(
      attemptId,
      state.score,
      correctAnswers,
      totalQuestions,
      position,
      timeSpent,
      LocalDateTime.ofInstant(finishTime, ZoneOffset.UTC)
    );
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
            quiz.setTimePerQuestion(Duration.ofSeconds(seconds));
          }

          quiz.setPrivate(rs.getBoolean("is_private"));
          quiz.setStatic(rs.getBoolean("is_static"));

          OffsetDateTime odt = rs.getObject("created_at", OffsetDateTime.class);
          quiz.setCreatedAt(odt != null ? odt.toInstant() : null);

          return quiz;
        }
      }
    } catch (SQLException e) {
      System.err.println("Error fetching quiz: " + e.getMessage());
    }
    return null;
  }

  private List<Question> getQuestionsByQuizId(Long quizId) {
    List<Question> questions = new ArrayList<>();
    String sql = "SELECT id, quiz_id, text, type, explanation, image FROM \"Question\" WHERE quiz_id = ? ORDER BY id";
    try (Connection connection = getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, quizId);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          Question question = new Question();
          question.setId(rs.getLong("id"));
          Quiz quiz = new Quiz();
          quiz.setId(quizId);
          question.setQuiz(quiz);
          question.setText(rs.getString("text"));
          question.setType(QuestionType.valueOf(rs.getString("type")));
          question.setExplanation(rs.getString("explanation"));
          question.setImage(rs.getBytes("image"));
          questions.add(question);
        }
      }
    } catch (SQLException e) {
      System.err.println("Error fetching questions: " + e.getMessage());
    }
    return questions;
  }

  private Question getQuestionById(Long questionId) {
    String sql = "SELECT id, quiz_id, text, type, explanation, image FROM \"Question\" WHERE id = ?";
    try (Connection connection = getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, questionId);
      try (ResultSet rs = statement.executeQuery()) {
        if (rs.next()) {
          Question question = new Question();
          question.setId(rs.getLong("id"));
          Quiz quiz = new Quiz();
          quiz.setId(rs.getLong("quiz_id"));
          question.setQuiz(quiz);
          question.setText(rs.getString("text"));
          question.setType(QuestionType.valueOf(rs.getString("type")));
          question.setExplanation(rs.getString("explanation"));
          question.setImage(rs.getBytes("image"));
          return question;
        }
      }
    } catch (SQLException e) {
      System.err.println("Error fetching question: " + e.getMessage());
    }
    return null;
  }

  private List<org.example.model.AnswerOption> getAnswerOptionsByQuestionId(Long questionId) {
    List<org.example.model.AnswerOption> options = new ArrayList<>();
    String sql = "SELECT id, question_id, text, is_correct, is_na_option FROM \"AnswerOption\" WHERE question_id = ?";
    try (Connection connection = getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, questionId);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          org.example.model.AnswerOption option = new org.example.model.AnswerOption();
          option.setId(rs.getLong("id"));
          Question question = new Question();
          question.setId(rs.getLong("question_id"));
          option.setQuestion(question);
          option.setText(rs.getString("text"));
          option.setCorrect(rs.getBoolean("is_correct"));
          option.setNaOption(rs.getBoolean("is_na_option"));
          options.add(option);
        }
      }
    } catch (SQLException e) {
      System.err.println("Error fetching answer options: " + e.getMessage());
    }
    return options;
  }

  private org.example.dto.common.AnswerOption getCorrectAnswer(Long questionId) {
    String sql = "SELECT id, text FROM \"AnswerOption\" WHERE question_id = ? AND is_correct = true LIMIT 1";
    try (Connection connection = getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, questionId);
      try (ResultSet rs = statement.executeQuery()) {
        if (rs.next()) {
          return new org.example.dto.common.AnswerOption(
            rs.getLong("id"),
            rs.getString("text")
          );
        }
      }
    } catch (SQLException e) {
      System.err.println("Error fetching correct answer: " + e.getMessage());
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

  private void updateAttempt(Long attemptId, Instant finishTime, Long score, boolean isCompleted) {
    String sql = "UPDATE \"UserQuizAttempt\" SET finish_time = ?, score = ?, is_completed = ? WHERE id = ?";
    try (Connection connection = getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setTimestamp(1, Timestamp.from(finishTime));
      statement.setLong(2, score);
      statement.setBoolean(3, isCompleted);
      statement.setLong(4, attemptId);
      statement.executeUpdate();
    } catch (SQLException e) {
      System.err.println("Error updating attempt: " + e.getMessage());
      throw new RuntimeException("Failed to update attempt", e);
    }
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

  private int getLeaderboardPosition(Long quizId, Long userId, int score) {
    String sql = "SELECT COUNT(*) as position FROM \"UserQuizAttempt\" " +
      "WHERE quiz_id = ? AND is_completed = true AND score > ?";
    try (Connection connection = getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, quizId);
      statement.setLong(2, (long) score);
      try (ResultSet rs = statement.executeQuery()) {
        if (rs.next()) {
          return rs.getInt("position") + 1;
        }
      }
    } catch (SQLException e) {
      System.err.println("Error calculating leaderboard position: " + e.getMessage());
    }
    return 1;
  }

  // Вспомогательные методы

  private QuestionDTO convertToQuestionDTO(Question question, Quiz quiz) {
    List<org.example.model.AnswerOption> options = getAnswerOptionsByQuestionId(question.getId());
    List<org.example.dto.common.AnswerOption> optionDTOs = options.stream()
      .map(opt -> new org.example.dto.common.AnswerOption(opt.getId(), opt.getText()))
      .toList();

    Integer timeLimit = null;
    if (quiz.getTimePerQuestion() != null) {
      timeLimit = (int) quiz.getTimePerQuestion().getSeconds();
    }

    return new QuestionDTO(
      question.getId(),
      question.getText(),
      optionDTOs,
      timeLimit,
      quiz.getMaterialUrl(),
      question.getExplanation(),
      null,
      null,
      null,
      question.getQuiz().getCreatedAt() != null ?
        LocalDateTime.ofInstant(question.getQuiz().getCreatedAt(), ZoneOffset.UTC) : null
    );
  }

  private Connection getConnection() throws SQLException {
    String url = "jdbc:postgresql://localhost:5432/quizarena";
    String username = "quizuser";
    String password = "quizpass";
    return DriverManager.getConnection(url, username, password);
  }
}
