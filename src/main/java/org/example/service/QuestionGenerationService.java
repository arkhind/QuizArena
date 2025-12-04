package org.example.service;

import org.example.dto.common.*;
import org.example.dto.request.generation.QuestionGenerationRequest;
import org.example.dto.response.generation.*;
import org.example.dto.response.quiz.QuestionDTO;
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
 * Сервис для генерации вопросов для квизов с использованием ИИ.
 * Позволяет генерировать, валидировать, дедуплицировать и получать вопросы.
 */
@Service
@Transactional
public class QuestionGenerationService {
  private final DatabaseService databaseService;

  private final Map<Long, QuestionSetState> questionSets = new ConcurrentHashMap<>();
  private long nextQuestionSetId = 1;

  public QuestionGenerationService(DatabaseService databaseService) {
    this.databaseService = databaseService;
  }

  /**
   * Внутренний класс для хранения состояния набора вопросов в памяти
   */
  private static class QuestionSetState {
    Long questionSetId;
    Long quizId;
    String prompt;
    List<GeneratedQuestion> questions;
    Instant createdAt;
    String status;
    Integer generatedCount;
    Integer validCount;
    Integer duplicateCount;

    QuestionSetState(Long questionSetId, Long quizId, String prompt) {
      this.questionSetId = questionSetId;
      this.quizId = quizId;
      this.prompt = prompt;
      this.questions = new ArrayList<>();
      this.createdAt = Instant.now();
      this.status = "GENERATED";
      this.generatedCount = 0;
      this.validCount = 0;
      this.duplicateCount = 0;
    }
  }

  /**
   * Внутренний класс для хранения сгенерированного вопроса
   */
  private static class GeneratedQuestion {
    Long id;
    String text;
    QuestionType type;
    String explanation;
    List<GeneratedAnswerOption> answerOptions;
    boolean isValid;
    boolean isDuplicate;

    GeneratedQuestion(Long id, String text, QuestionType type, String explanation) {
      this.id = id;
      this.text = text;
      this.type = type;
      this.explanation = explanation;
      this.answerOptions = new ArrayList<>();
      this.isValid = false;
      this.isDuplicate = false;
    }
  }

  /**
   * Внутренний класс для хранения варианта ответа
   */
  private static class GeneratedAnswerOption {
    String text;
    boolean isCorrect;
    boolean isNaOption;

    GeneratedAnswerOption(String text, boolean isCorrect, boolean isNaOption) {
      this.text = text;
      this.isCorrect = isCorrect;
      this.isNaOption = isNaOption;
    }
  }

  /**
   * Генерирует вопросы для квиза с использованием ИИ.
   * Включает валидацию и удаление дубликатов.
   */
  public QuestionGenerationResponse generateQuizQuestions(QuestionGenerationRequest request) {
    Long quizId = request.quizId();
    String prompt = request.prompt();
    Integer questionCount = request.questionCount() != null ? request.questionCount() : 10;

    Quiz quiz = getQuizById(quizId);
    if (quiz == null) {
      throw new RuntimeException("Quiz not found: " + quizId);
    }

    Long questionSetId = generateQuestionSetId();
    QuestionSetState questionSet = new QuestionSetState(questionSetId, quizId, prompt);
    questionSets.put(questionSetId, questionSet);

    List<GeneratedQuestion> generatedQuestions = generateQuestionsWithAI(prompt, questionCount, request.materials());
    questionSet.questions = generatedQuestions;
    questionSet.generatedCount = generatedQuestions.size();

    List<ValidationError> validationErrors = validateQuestions(generatedQuestions);
    int validCount = (int) generatedQuestions.stream().filter(q -> q.isValid).count();
    questionSet.validCount = validCount;

    List<DuplicatePair> duplicates = findDuplicates(generatedQuestions);
    removeDuplicates(generatedQuestions, duplicates);
    int finalCount = (int) generatedQuestions.stream().filter(q -> !q.isDuplicate).count();
    questionSet.duplicateCount = duplicates.size();

    questionSet.status = "COMPLETED";

    return new QuestionGenerationResponse(
      questionSetId,
      questionSet.status,
      questionSet.generatedCount,
      questionSet.validCount,
      questionSet.duplicateCount,
      finalCount
    );
  }

  /**
   * Валидирует сгенерированные вопросы на соответствие требованиям.
   */
  public ValidationResponse validateGeneratedQuestions(Long questionSetId) {
    QuestionSetState questionSet = questionSets.get(questionSetId);
    if (questionSet == null) {
      throw new RuntimeException("Question set not found: " + questionSetId);
    }

    List<ValidationError> errors = validateQuestions(questionSet.questions);
    int validCount = (int) questionSet.questions.stream().filter(q -> q.isValid).count();
    questionSet.validCount = validCount;

    return new ValidationResponse(
      questionSetId,
      questionSet.questions.size(),
      validCount,
      errors
    );
  }

  /**
   * Удаляет дубликаты вопросов из набора.
   */
  public DeduplicationResponse removeDuplicateQuestions(Long questionSetId) {
    QuestionSetState questionSet = questionSets.get(questionSetId);
    if (questionSet == null) {
      throw new RuntimeException("Question set not found: " + questionSetId);
    }

    int initialCount = questionSet.questions.size();
    List<DuplicatePair> duplicates = findDuplicates(questionSet.questions);
    removeDuplicates(questionSet.questions, duplicates);
    int finalCount = (int) questionSet.questions.stream().filter(q -> !q.isDuplicate).count();
    questionSet.duplicateCount = duplicates.size();

    return new DeduplicationResponse(
      questionSetId,
      initialCount,
      finalCount,
      duplicates
    );
  }

  /**
   * Получает сгенерированные вопросы из набора.
   */
  public GeneratedQuestionsDTO getGeneratedQuestions(Long questionSetId) {
    QuestionSetState questionSet = questionSets.get(questionSetId);
    if (questionSet == null) {
      throw new RuntimeException("Question set not found: " + questionSetId);
    }

    List<QuestionDTO> questionDTOs = questionSet.questions.stream()
      .filter(q -> !q.isDuplicate)
      .map(this::convertToQuestionDTO)
      .collect(Collectors.toList());

    GenerationMetadata metadata = new GenerationMetadata(
      LocalDateTime.ofInstant(questionSet.createdAt, ZoneOffset.UTC),
      "1.0",
      String.valueOf(questionSet.prompt.hashCode())
    );

    return new GeneratedQuestionsDTO(
      questionSetId,
      questionDTOs,
      metadata
    );
  }

  // Вспомогательные методы

  /**
   * Генерирует уникальный идентификатор набора вопросов.
   */
  private synchronized Long generateQuestionSetId() {
    return nextQuestionSetId++;
  }

  /**
   * Генерирует вопросы с использованием ИИ (заглушка).
   * В реальной реализации здесь будет вызов AI сервиса.
   */
  private List<GeneratedQuestion> generateQuestionsWithAI(String prompt, int count, List<QuizMaterial> materials) {
    List<GeneratedQuestion> questions = new ArrayList<>();

    for (int i = 0; i < count; i++) {
      long questionId = System.currentTimeMillis() + i;
      String questionText = "Generated question " + (i + 1) + " based on: " + prompt;
      QuestionType type = QuestionType.SINGLE_CHOICE;
      String explanation = "Explanation for question " + (i + 1);

      GeneratedQuestion question = new GeneratedQuestion(questionId, questionText, type, explanation);

      question.answerOptions.add(new GeneratedAnswerOption("Option A", true, false));
      question.answerOptions.add(new GeneratedAnswerOption("Option B", false, false));
      question.answerOptions.add(new GeneratedAnswerOption("Option C", false, false));
      question.answerOptions.add(new GeneratedAnswerOption("Option D", false, false));

      questions.add(question);
    }

    return questions;
  }

  /**
   * Валидирует вопросы на соответствие требованиям.
   */
  private List<ValidationError> validateQuestions(List<GeneratedQuestion> questions) {
    List<ValidationError> errors = new ArrayList<>();

    for (GeneratedQuestion question : questions) {
      boolean isValid = true;

      if (question.text == null || question.text.trim().isEmpty()) {
        errors.add(new ValidationError(question.text, "Question text is empty", "text"));
        isValid = false;
      } else if (question.text.length() > 300) {
        errors.add(new ValidationError(question.text, "Question text exceeds 300 characters", "text"));
        isValid = false;
      }

      if (question.explanation == null || question.explanation.trim().isEmpty()) {
        errors.add(new ValidationError(question.text, "Explanation is missing", "explanation"));
        isValid = false;
      } else if (question.explanation.length() > 255) {
        errors.add(new ValidationError(question.text, "Explanation exceeds 255 characters", "explanation"));
        isValid = false;
      }

      if (question.answerOptions == null || question.answerOptions.isEmpty()) {
        errors.add(new ValidationError(question.text, "No answer options provided", "answerOptions"));
        isValid = false;
      } else {
        boolean hasCorrectAnswer = question.answerOptions.stream().anyMatch(opt -> opt.isCorrect);
        if (!hasCorrectAnswer) {
          errors.add(new ValidationError(question.text, "No correct answer provided", "answerOptions"));
          isValid = false;
        }

        for (GeneratedAnswerOption option : question.answerOptions) {
          if (option.text == null || option.text.trim().isEmpty()) {
            errors.add(new ValidationError(question.text, "Answer option text is empty", "answerOptions"));
            isValid = false;
          } else if (option.text.length() > 100) {
            errors.add(new ValidationError(question.text, "Answer option text exceeds 100 characters", "answerOptions"));
            isValid = false;
          }
        }
      }

      question.isValid = isValid;
    }

    return errors;
  }

  /**
   * Находит дубликаты вопросов в наборе.
   */
  private List<DuplicatePair> findDuplicates(List<GeneratedQuestion> questions) {
    List<DuplicatePair> duplicates = new ArrayList<>();

    for (int i = 0; i < questions.size(); i++) {
      GeneratedQuestion q1 = questions.get(i);
      if (q1.isDuplicate) continue;

      for (int j = i + 1; j < questions.size(); j++) {
        GeneratedQuestion q2 = questions.get(j);
        if (q2.isDuplicate) continue;

        double similarity = calculateSimilarity(q1.text, q2.text);
        if (similarity > 0.8) {
          duplicates.add(new DuplicatePair(q1.id, q2.id, similarity));
          q2.isDuplicate = true;
        }
      }
    }

    return duplicates;
  }

  /**
   * Удаляет дубликаты из списка вопросов.
   */
  private void removeDuplicates(List<GeneratedQuestion> questions, List<DuplicatePair> duplicates) {}

  /**
   * Вычисляет схожесть между двумя текстами (простая реализация).
   */
  private double calculateSimilarity(String text1, String text2) {
    if (text1 == null || text2 == null) {
      return 0.0;
    }

    String lower1 = text1.toLowerCase().trim();
    String lower2 = text2.toLowerCase().trim();

    if (lower1.equals(lower2)) {
      return 1.0;
    }

    if (lower1.contains(lower2) || lower2.contains(lower1)) {
      return 0.9;
    }

    String[] words1 = lower1.split("\\s+");
    String[] words2 = lower2.split("\\s+");

    Set<String> set1 = new HashSet<>(Arrays.asList(words1));
    Set<String> set2 = new HashSet<>(Arrays.asList(words2));

    Set<String> intersection = new HashSet<>(set1);
    intersection.retainAll(set2);

    Set<String> union = new HashSet<>(set1);
    union.addAll(set2);

    if (union.isEmpty()) {
      return 0.0;
    }

    return (double) intersection.size() / union.size();
  }

  /**
   * Преобразует GeneratedQuestion в QuestionDTO.
   */
  private QuestionDTO convertToQuestionDTO(GeneratedQuestion question) {
    List<org.example.dto.common.AnswerOption> answerOptions = question.answerOptions.stream()
      .map(opt -> new org.example.dto.common.AnswerOption(null, opt.text))
      .collect(Collectors.toList());

    return new QuestionDTO(
      question.id,
      question.text,
      answerOptions,
      null,
      null,
      question.explanation,
      null,
      null,
      null,
      LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)
    );
  }

  // Операции с базой данных

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

  private Connection getConnection() throws SQLException {
    String url = "jdbc:postgresql://localhost:5432/quizarena";
    String username = "quizuser";
    String password = "quizpass";
    return DriverManager.getConnection(url, username, password);
  }
}
