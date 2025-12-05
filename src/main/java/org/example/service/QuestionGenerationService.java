package org.example.service;

import org.example.dto.common.*;
import org.example.dto.request.generation.QuestionGenerationRequest;
import org.example.dto.response.generation.*;
import org.example.dto.common.GenerationMetadata;
import org.example.dto.common.ValidationError;
import org.example.dto.common.DuplicatePair;
import org.example.dto.response.quiz.QuestionDTO;
import org.example.dto.common.AnswerOption;
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
import java.util.stream.Collectors;

/**
 * Сервис для генерации вопросов с помощью ИИ.
 * Путь: src/main/java/org/example/service/QuestionGenerationService.java
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

    private final GenerationSetRepository generationSetRepository;
    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final AnswerOptionRepository answerOptionRepository;
    // TODO: Добавить AIService для интеграции с ИИ

    @Autowired
    public QuestionGenerationService(
            GenerationSetRepository generationSetRepository,
            QuizRepository quizRepository,
            QuestionRepository questionRepository,
            AnswerOptionRepository answerOptionRepository) {
        this.generationSetRepository = generationSetRepository;
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.answerOptionRepository = answerOptionRepository;
    }

    /**
     * Генерирует вопросы для квиза с использованием ИИ.
     */
    public QuestionGenerationResponse generateQuizQuestions(QuestionGenerationRequest request) {
        Quiz quiz = quizRepository.findById(request.quizId())
                .orElseThrow(() -> new IllegalArgumentException("Квиз не найден"));

        // Создаем набор вопросов
        GenerationSet questionSet = new GenerationSet();
        questionSet.setQuiz(quiz);
        questionSet.setPrompt(request.prompt());
        questionSet.setStatus("GENERATING");
        questionSet.setCreatedAt(Instant.now());
        questionSet.setGeneratedCount(0);
        questionSet.setValidCount(0);
        questionSet.setDuplicateCount(0);
        questionSet.setFinalCount(0);
        questionSet = generationSetRepository.save(questionSet);

        // TODO: Интеграция с ИИ сервисом
        // Пример псевдокода:
        // List<AIGeneratedQuestion> aiQuestions = aiService.generateQuestions(
        //     request.prompt(),
        //     request.materials(),
        //     request.questionCount()
        // );

        // Пока генерируем заглушки
        int questionCount = request.questionCount() != null ? request.questionCount() : 10;
        List<Question> generatedQuestions = new ArrayList<>();

        for (int i = 0; i < questionCount; i++) {
            Question genQuestion = new Question();
            genQuestion.setQuiz(quiz);
            genQuestion.setText("Сгенерированный вопрос " + (i + 1));
            genQuestion.setType(QuestionType.SINGLE_CHOICE);
            genQuestion.setExplanation("Объяснение к вопросу " + (i + 1));
            genQuestion.setIsGenerated(true);
            genQuestion.setGenerationSetId(questionSet.getId());
            genQuestion.setIsValid(null); // Будет проверено при валидации
            genQuestion.setIsDuplicate(false);
            genQuestion = questionRepository.save(genQuestion);

            // Генерируем варианты ответов (4 варианта)
            for (int j = 0; j < 4; j++) {
                org.example.model.AnswerOption option = new org.example.model.AnswerOption();
                option.setQuestion(genQuestion);
                option.setText("Вариант ответа " + (j + 1));
                option.setCorrect(j == 0); // Первый вариант правильный (пример)
                option.setNaOption(false);
                answerOptionRepository.save(option);
            }

            generatedQuestions.add(genQuestion);
        }

        questionSet.setGeneratedCount(generatedQuestions.size());
        questionSet.setStatus("VALIDATING");
        generationSetRepository.save(questionSet);

        return new QuestionGenerationResponse(
                questionSet.getId(),
                questionSet.getStatus(),
                generatedQuestions.size(),
                0, // validCount - будет после валидации
                0, // duplicateCount - будет после дедупликации
                generatedQuestions.size() // finalCount
        );
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

    /**
     * Валидирует сгенерированные вопросы.
     */
    public ValidationResponse validateGeneratedQuestions(Long questionSetId) {
        GenerationSet questionSet = generationSetRepository.findById(questionSetId)
                .orElseThrow(() -> new IllegalArgumentException("Набор вопросов не найден"));

        // Получаем все вопросы из набора
        Long quizId = questionSet.getQuiz().getId();
        List<Question> questions = questionRepository.findByQuizId(quizId);
        List<Question> generatedQuestions = questions.stream()
                .filter(q -> q.getGenerationSetId() != null && questionSetId.equals(q.getGenerationSetId()))
                .collect(Collectors.toList());

        List<ValidationError> errors = new ArrayList<>();
        int validCount = 0;

        for (Question question : generatedQuestions) {
            boolean isValid = true;
            List<String> questionErrors = new ArrayList<>();

            // Проверка 1: Текст вопроса не пустой и не слишком короткий
            if (question.getText() == null || question.getText().trim().length() < 10) {
                isValid = false;
                questionErrors.add("Текст вопроса слишком короткий");
            }

            // Проверка 2: Есть варианты ответов
            List<org.example.model.AnswerOption> options = answerOptionRepository.findByQuestionId(question.getId());
            if (options.size() < 4) {
                isValid = false;
                questionErrors.add("Недостаточно вариантов ответа (нужно 4)");
            }

            // Проверка 3: Есть хотя бы один правильный ответ
            boolean hasCorrectAnswer = options.stream()
                    .anyMatch(org.example.model.AnswerOption::isCorrect);
            if (!hasCorrectAnswer) {
                isValid = false;
                questionErrors.add("Нет правильного варианта ответа");
            }

            // Проверка 4: Есть объяснение
            if (question.getExplanation() == null || question.getExplanation().trim().isEmpty()) {
                isValid = false;
                questionErrors.add("Отсутствует объяснение");
            }

            question.setIsValid(isValid);
            questionRepository.save(question);

            if (isValid) {
                validCount++;
            } else {
                for (String error : questionErrors) {
                    errors.add(new ValidationError(
                            question.getText(),
                            error,
                            "question"
                    ));
                }
            }
        }

        questionSet.setValidCount(validCount);
        questionSet.setStatus("DEDUPLICATING");
        generationSetRepository.save(questionSet);

        return new ValidationResponse(
                questionSetId,
                generatedQuestions.size(),
                validCount,
                errors
        );
    }
  }

    /**
     * Удаляет дубликаты вопросов.
     */
    public DeduplicationResponse removeDuplicateQuestions(Long questionSetId) {
        GenerationSet questionSet = generationSetRepository.findById(questionSetId)
                .orElseThrow(() -> new IllegalArgumentException("Набор вопросов не найден"));

        // Получаем все валидные вопросы из набора
        List<Question> questions = questionRepository.findByQuizId(questionSet.getQuiz().getId());
        List<Question> validQuestions = questions.stream()
                .filter(q -> questionSetId.equals(q.getGenerationSetId())
                        && Boolean.TRUE.equals(q.getIsValid()))
                .collect(Collectors.toList());

        List<Question> uniqueQuestions = new ArrayList<>();
        List<DuplicatePair> duplicatePairs = new ArrayList<>();

        for (int i = 0; i < validQuestions.size(); i++) {
            Question question1 = validQuestions.get(i);
            boolean isDuplicate = false;

            for (int j = i + 1; j < validQuestions.size(); j++) {
                Question question2 = validQuestions.get(j);
                if (isSimilarQuestion(question1.getText(), question2.getText())) {
                    isDuplicate = true;
                    question2.setIsDuplicate(true);
                    questionRepository.save(question2);
                    duplicatePairs.add(new DuplicatePair(
                            question1.getId(),
                            question2.getId(),
                            0.85 // Примерное значение схожести
                    ));
                }
            }

            if (!isDuplicate) {
                question1.setIsDuplicate(false);
                uniqueQuestions.add(question1);
            } else {
                question1.setIsDuplicate(true);
            }
            questionRepository.save(question1);
        }

        questionSet.setDuplicateCount(duplicatePairs.size());
        questionSet.setFinalCount(uniqueQuestions.size());
        questionSet.setStatus("READY");
        generationSetRepository.save(questionSet);

        return new DeduplicationResponse(
                questionSetId,
                validQuestions.size(),
                uniqueQuestions.size(),
                duplicatePairs
        );
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

    /**
     * Получает сгенерированные вопросы.
     */
    public GeneratedQuestionsDTO getGeneratedQuestions(Long questionSetId) {
        GenerationSet questionSet = generationSetRepository.findById(questionSetId)
                .orElseThrow(() -> new IllegalArgumentException("Набор вопросов не найден"));

        // Получаем все уникальные вопросы из набора
        Long quizId = questionSet.getQuiz().getId();
        List<Question> questions = questionRepository.findByQuizId(quizId);
        List<Question> uniqueQuestions = questions.stream()
                .filter(q -> q.getGenerationSetId() != null 
                        && questionSetId.equals(q.getGenerationSetId())
                        && Boolean.FALSE.equals(q.getIsDuplicate()))
                .collect(Collectors.toList());

        List<QuestionDTO> questionDTOs = uniqueQuestions.stream()
                .map(this::toQuestionDTO)
                .collect(Collectors.toList());

        GenerationMetadata metadata = new GenerationMetadata(
                toLocalDateTime(questionSet.getCreatedAt()),
                "1.0", // modelVersion
                String.valueOf(questionSet.getPrompt().hashCode()) // promptHash
        );

        return new GeneratedQuestionsDTO(
                questionSetId,
                questionDTOs,
                metadata
        );
    }

    // ========== Вспомогательные методы ==========

    private boolean isSimilarQuestion(String text1, String text2) {
        // Простая проверка на схожесть (можно улучшить с помощью NLP)
        if (text1 == null || text2 == null) return false;

        // Нормализуем тексты
        String normalized1 = text1.toLowerCase().trim();
        String normalized2 = text2.toLowerCase().trim();

        // Проверяем точное совпадение
        if (normalized1.equals(normalized2)) return true;

        // Проверяем схожесть по словам (упрощенная версия)
        String[] words1 = normalized1.split("\\s+");
        String[] words2 = normalized2.split("\\s+");

        if (words1.length == 0 || words2.length == 0) return false;

        int commonWords = 0;
        for (String word1 : words1) {
            for (String word2 : words2) {
                if (word1.equals(word2)) {
                    commonWords++;
                    break;
                }
            }
        }

        double similarity = (double) commonWords / Math.max(words1.length, words2.length);
        return similarity > 0.8; // 80% схожести
    }

    private QuestionDTO toQuestionDTO(Question question) {
        List<org.example.model.AnswerOption> options = answerOptionRepository.findByQuestionId(question.getId());
        List<AnswerOption> dtoOptions = options.stream()
                .map(opt -> new AnswerOption(opt.getId(), opt.getText()))
                .collect(Collectors.toList());

        Integer timeLimit = null;
        if (question.getQuiz().getTimePerQuestion() != null) {
            timeLimit = (int) question.getQuiz().getTimePerQuestion().getSeconds();
        }

        return new QuestionDTO(
                question.getId(),
                question.getText(),
                dtoOptions,
                timeLimit,
                null, // materialReference
                question.getExplanation(),
                null, // difficulty
                null, // category
                0, // position
                toLocalDateTime(question.getQuiz().getCreatedAt())
        );
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant != null
                ? LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                : null;
    }
}
