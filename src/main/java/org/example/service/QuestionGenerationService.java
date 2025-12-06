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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Сервис для генерации вопросов с помощью ИИ.
 * Путь: src/main/java/org/example/service/QuestionGenerationService.java
 */
@Service
@Transactional
public class QuestionGenerationService {
    private final Map<Long, QuestionSetState> questionSets = new ConcurrentHashMap<>();
    private long nextQuestionSetId = 1;
    private final GenerationSetRepository generationSetRepository;
    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final AnswerOptionRepository answerOptionRepository;
    private final WebClient webClient;

    @Value("${llm.service.url:http://localhost:8000}")
    private String llmServiceUrl;

    @Value("${llm.service.timeout:120}")
    private int llmServiceTimeout;

    @Autowired
    public QuestionGenerationService(
            GenerationSetRepository generationSetRepository,
            QuizRepository quizRepository,
            QuestionRepository questionRepository,
            AnswerOptionRepository answerOptionRepository,
            WebClient.Builder webClientBuilder) {
        this.generationSetRepository = generationSetRepository;
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.answerOptionRepository = answerOptionRepository;
        this.webClient = webClientBuilder.build();
    }

    /**
     * Внутренний класс для хранения состояния набора вопросов
     */
    private static class QuestionSetState {
        Long setId;
        Long quizId;
        String status;
        List<Long> questionIds;
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
    }

    /**
     * Внутренний класс для хранения варианта ответа
     */
    private static class GeneratedAnswerOption {
        Long id;
        String text;
        boolean isCorrect;
    }

    /**
     * Генерирует вопросы для квиза с использованием ИИ через Python сервис.
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

        int questionCount = request.questionCount() != null ? request.questionCount() : 10;
        String prompt = request.prompt() != null ? request.prompt() : "Общая тема";
        List<Question> generatedQuestions = new ArrayList<>();

        try {
            // Вызываем Python LLM сервис
            String llmResponse = callLLMService(prompt, questionCount);
            
            // Парсим ответ от LLM
            List<GeneratedQuestionData> parsedQuestions = parseLLMResponse(llmResponse);
            
            if (parsedQuestions.isEmpty()) {
                throw new IllegalStateException("LLM сервис вернул пустой ответ. Проверьте, что Python сервис работает корректно и Ollama запущен.");
            }
            
            // Используем вопросы от LLM
            for (GeneratedQuestionData qData : parsedQuestions) {
                Question genQuestion = createQuestionFromLLMData(quiz, questionSet, qData);
                generatedQuestions.add(genQuestion);
            }
        } catch (RuntimeException e) {
            // Если LLM сервис недоступен, выбрасываем понятную ошибку
            String errorMessage = String.format(
                "Не удалось сгенерировать вопросы через LLM сервис. " +
                "Убедитесь, что:\n" +
                "1. Python FastAPI сервис запущен на %s\n" +
                "2. Ollama установлен и модель 'qwen3:8b' загружена\n" +
                "3. Сеть доступна\n\n" +
                "Ошибка: %s",
                llmServiceUrl,
                e.getMessage()
            );
            throw new IllegalStateException(errorMessage, e);
        }

        questionSet.setGeneratedCount(generatedQuestions.size());
        questionSet.setStatus("VALIDATING");
        generationSetRepository.save(questionSet);

        return new QuestionGenerationResponse(
                questionSet.getId(),
                questionSet.getStatus(),
                generatedQuestions.size(),
                0,
                0,
                generatedQuestions.size()
        );
    }

    /**
     * Вызывает Python LLM сервис для генерации вопросов.
     */
    private String callLLMService(String prompt, int questionCount) {
        try {
            String encodedPrompt = URLEncoder.encode(prompt, StandardCharsets.UTF_8);
            String url = llmServiceUrl + "/question/" + encodedPrompt + "/" + questionCount;
            
            return webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(llmServiceTimeout))
                    .block();
        } catch (WebClientException e) {
            throw new RuntimeException("Failed to call LLM service: " + e.getMessage(), e);
        }
    }

    /**
     * Парсит ответ от LLM в структурированный формат.
     * Формат ответа:
     * *Вопрос*
     * 1)*вариант ответа*
     * 2)*вариант ответа*
     * 3)*вариант ответа*
     * 4)*вариант ответа*
     * *правильный ответ*
     * *объяснение*
     * *Следующий вопрос*
     */
    private List<GeneratedQuestionData> parseLLMResponse(String response) {
        List<GeneratedQuestionData> questions = new ArrayList<>();
        
        if (response == null || response.trim().isEmpty()) {
            return questions;
        }

        // Разбиваем на строки
        String[] lines = response.split("\n");
        GeneratedQuestionData currentQuestion = null;
        int expectedAnswerIndex = 1;
        boolean lookingForCorrectAnswer = false;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // Проверяем, начинается ли строка с *
            if (line.startsWith("*") && line.endsWith("*")) {
                String content = line.substring(1, line.length() - 1).trim();
                
                // Если это не номер варианта ответа (1)*, 2)*, и т.д.)
                if (!line.matches("^\\d+\\)\\*.*\\*$")) {
                    // Проверяем, не содержит ли строка "Следующий вопрос" или "правильный ответ"
                    if (content.toLowerCase().contains("следующий вопрос")) {
                        // Сохраняем текущий вопрос и начинаем новый
                        if (currentQuestion != null && currentQuestion.text != null) {
                            questions.add(currentQuestion);
                        }
                        currentQuestion = new GeneratedQuestionData();
                        expectedAnswerIndex = 1;
                        lookingForCorrectAnswer = false;
                    } else if (content.toLowerCase().contains("правильный ответ") || 
                              content.matches(".*[1-4].*")) {
                        // Это строка с правильным ответом
                        lookingForCorrectAnswer = true;
                        if (currentQuestion != null) {
                            currentQuestion.correctAnswerIndex = extractAnswerIndex(content);
                        }
                    } else if (lookingForCorrectAnswer && currentQuestion != null) {
                        // Это объяснение (идет после правильного ответа)
                        currentQuestion.explanation = content;
                        lookingForCorrectAnswer = false;
                    } else if (currentQuestion == null || currentQuestion.text == null) {
                        // Это начало нового вопроса
                        if (currentQuestion != null && currentQuestion.text != null) {
                            questions.add(currentQuestion);
                        }
                        currentQuestion = new GeneratedQuestionData();
                        currentQuestion.text = content;
                        expectedAnswerIndex = 1;
                    } else if (currentQuestion.explanation == null) {
                        // Это объяснение
                        currentQuestion.explanation = content;
                    }
                }
            } else if (line.matches("^\\d+\\)\\*.*")) {
                // Вариант ответа в формате 1)*ответ*
                Pattern pattern = Pattern.compile("^(\\d+)\\)\\*(.*)\\*$");
                Matcher matcher = pattern.matcher(line);
                if (matcher.matches()) {
                    int answerNum = Integer.parseInt(matcher.group(1));
                    String answerText = matcher.group(2).trim();
                    
                    if (currentQuestion == null) {
                        currentQuestion = new GeneratedQuestionData();
                    }
                    
                    // Добавляем ответы в правильном порядке
                    while (currentQuestion.answers.size() < answerNum) {
                        currentQuestion.answers.add("");
                    }
                    if (currentQuestion.answers.size() >= answerNum) {
                        if (answerNum <= currentQuestion.answers.size()) {
                            currentQuestion.answers.set(answerNum - 1, answerText);
                        } else {
                            currentQuestion.answers.add(answerText);
                        }
                    }
                    expectedAnswerIndex = Math.max(expectedAnswerIndex, answerNum + 1);
                }
            }
        }

        // Добавляем последний вопрос, если есть
        if (currentQuestion != null && currentQuestion.text != null) {
            questions.add(currentQuestion);
        }

        return questions;
    }

    /**
     * Извлекает номер правильного ответа из текста.
     */
    private int extractAnswerIndex(String text) {
        Pattern pattern = Pattern.compile("([1-4])");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1)) - 1; // Конвертируем в 0-based индекс
        }
        return 0; // По умолчанию первый ответ
    }

    /**
     * Создает вопрос из распарсенных данных LLM.
     */
    private Question createQuestionFromLLMData(Quiz quiz, GenerationSet questionSet, GeneratedQuestionData qData) {
        Question genQuestion = new Question();
        genQuestion.setQuiz(quiz);
        genQuestion.setText(qData.text);
        genQuestion.setType(QuestionType.SINGLE_CHOICE);
        genQuestion.setExplanation(qData.explanation != null && !qData.explanation.isEmpty() 
                ? qData.explanation 
                : "Правильный ответ соответствует теме.");
        genQuestion.setIsGenerated(true);
        genQuestion.setGenerationSetId(questionSet.getId());
        genQuestion.setIsValid(null);
        genQuestion.setIsDuplicate(false);
        genQuestion = questionRepository.save(genQuestion);

        // Определяем правильный ответ
        int correctIndex = 0;
        if (qData.correctAnswerIndex >= 0 && qData.correctAnswerIndex < qData.answers.size()) {
            correctIndex = qData.correctAnswerIndex;
        }

        // Сохраняем варианты ответов (минимум 4)
        int answerCount = Math.max(4, qData.answers.size());
        for (int i = 0; i < answerCount; i++) {
            org.example.model.AnswerOption option = new org.example.model.AnswerOption();
            option.setQuestion(genQuestion);
            
            if (i < qData.answers.size() && !qData.answers.get(i).isEmpty()) {
                option.setText(qData.answers.get(i));
            } else {
                option.setText("Вариант ответа " + (i + 1));
            }
            
            option.setCorrect(i == correctIndex);
            option.setNaOption(false);
            answerOptionRepository.save(option);
        }

        return genQuestion;
    }


    /**
     * Внутренний класс для хранения распарсенных данных вопроса от LLM.
     */
    private static class GeneratedQuestionData {
        String text;
        List<String> answers = new ArrayList<>();
        int correctAnswerIndex = 0;
        String explanation;
    }

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
