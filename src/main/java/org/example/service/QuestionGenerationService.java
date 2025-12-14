package org.example.service;

import org.example.dto.request.generation.QuestionGenerationRequest;
import org.example.dto.response.generation.QuestionGenerationResponse;
import org.example.model.*;
import org.example.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@Transactional
public class QuestionGenerationService {
    private final GenerationSetRepository generationSetRepository;
    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final AnswerOptionRepository answerOptionRepository;
    private final FastApiClient fastApiClient;

    @Autowired
    public QuestionGenerationService(
            GenerationSetRepository generationSetRepository,
            QuizRepository quizRepository,
            QuestionRepository questionRepository,
            AnswerOptionRepository answerOptionRepository,
            FastApiClient fastApiClient) {
        this.generationSetRepository = generationSetRepository;
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.answerOptionRepository = answerOptionRepository;
        this.fastApiClient = fastApiClient;
    }

    public QuestionGenerationResponse generateQuizQuestions(QuestionGenerationRequest request) {
        Quiz quiz = quizRepository.findById(request.quizId())
                .orElseThrow(() -> new IllegalArgumentException("Квиз не найден: " + request.quizId()));

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
            System.out.println("QuestionGenerationService: Начинаем генерацию вопросов для промпта: " + prompt.substring(0, Math.min(50, prompt.length())));
            String apiResponse = fastApiClient.getQuestionsByPrompt(prompt, questionCount);
            System.out.println("QuestionGenerationService: Получен ответ от ML сервера, длина: " + (apiResponse != null ? apiResponse.length() : 0));
            
            // Проверяем ответ на наличие признаков ошибки этичности ДО парсинга
            if (apiResponse == null || apiResponse.trim().isEmpty()) {
                System.err.println("QuestionGenerationService: Пустой ответ от ML сервера");
                // Если ответ пустой, это может быть признаком ошибки этичности
                // Проверяем этичность промпта еще раз
                try {
                    System.out.println("QuestionGenerationService: Повторная проверка этичности из-за пустого ответа");
                    boolean isUnethical = fastApiClient.checkPromptEthics(prompt);
                    System.out.println("QuestionGenerationService: Результат повторной проверки этичности: " + isUnethical);
                    if (isUnethical) {
                        throw new UnethicalPromptException("Данные для создания квиза являются неэтичными");
                    }
                } catch (UnethicalPromptException e) {
                    throw e;
                } catch (Exception e) {
                    System.err.println("QuestionGenerationService: Ошибка при повторной проверке этичности: " + e.getMessage());
                    // Игнорируем ошибку проверки этичности, пробрасываем исходную ошибку
                }
                throw new RuntimeException("Пустой ответ от ML сервера");
            }
            
            // Проверяем ответ на наличие признаков ошибки этичности в тексте
            String lowerResponse = apiResponse.toLowerCase();
            if (lowerResponse.contains("unethical_prompt") || lowerResponse.contains("неэтичн") || 
                lowerResponse.contains("неэтичными") || lowerResponse.contains("cringe") ||
                lowerResponse.contains("не могу") || lowerResponse.contains("не могу сгенерировать")) {
                System.err.println("QuestionGenerationService: Обнаружены признаки ошибки этичности в ответе ML сервера");
                throw new UnethicalPromptException("Данные для создания квиза являются неэтичными");
            }
            
            List<QuestionParser.ParsedQuestion> parsedQuestions = QuestionParser.parse(apiResponse);
            
            if (parsedQuestions.isEmpty()) {
                System.err.println("QuestionGenerationService: Парсер не нашел вопросов в ответе FastAPI");
                System.err.println("QuestionGenerationService: Ответ ML сервера (первые 500 символов): " + apiResponse.substring(0, Math.min(500, apiResponse.length())));
                
                // Если парсер не нашел вопросов, это может быть признаком ошибки этичности
                // Проверяем этичность промпта еще раз
                try {
                    System.out.println("QuestionGenerationService: Повторная проверка этичности из-за отсутствия вопросов в парсере");
                    boolean isUnethical = fastApiClient.checkPromptEthics(prompt);
                    System.out.println("QuestionGenerationService: Результат повторной проверки этичности: " + isUnethical);
                    if (isUnethical) {
                        throw new UnethicalPromptException("Данные для создания квиза являются неэтичными");
                    }
                } catch (UnethicalPromptException e) {
                    throw e;
                } catch (Exception e) {
                    System.err.println("QuestionGenerationService: Ошибка при повторной проверке этичности: " + e.getMessage());
                    // Игнорируем ошибку проверки этичности, пробрасываем исходную ошибку
                }
                
                throw new RuntimeException("Парсер не нашел вопросов в ответе FastAPI");
            }
            
            // Ограничиваем количество сохраняемых вопросов до запрошенного количества
            int maxQuestionsToSave = questionCount;
            
            for (int i = 0; i < parsedQuestions.size() && generatedQuestions.size() < maxQuestionsToSave; i++) {
                QuestionParser.ParsedQuestion pq = parsedQuestions.get(i);
                
                if (pq.question == null || pq.answerOptions == null || pq.answerOptions.isEmpty()) {
                    System.err.println("QuestionGenerationService: Пропуск вопроса " + (i + 1) + " - некорректные данные");
                    continue;
                }
                
                Question question = pq.question;
                question.setQuiz(quiz);
                question.setIsGenerated(true);
                question.setGenerationSetId(questionSet.getId());
                question.setIsValid(true);
                question.setIsDuplicate(false);
                
                if (question.getType() == null) {
                    question.setType(QuestionType.MULTIPLE_CHOICE);
                }
                
                if (question.getExplanation() == null || question.getExplanation().trim().isEmpty()) {
                    question.setExplanation("Объяснение отсутствует");
                }
                
                if (question.getText() == null || question.getText().trim().isEmpty()) {
                    System.err.println("QuestionGenerationService: Пропуск вопроса " + (i + 1) + " - текст вопроса пустой");
                    continue;
                }
                
                question = questionRepository.save(question);
                
                // Логируем сохранение объяснения
                System.out.println("QuestionGenerationService: Сохранён вопрос ID " + question.getId() + 
                        " с объяснением: " + (question.getExplanation() != null && !question.getExplanation().isEmpty() 
                        ? question.getExplanation().substring(0, Math.min(50, question.getExplanation().length())) + "..." 
                        : "отсутствует"));
                
                for (AnswerOption option : pq.answerOptions) {
                    option.setQuestion(question);
                    answerOptionRepository.save(option);
                }
                
                generatedQuestions.add(question);
            }
            
            // Проверяем, что сохранилось ровно столько вопросов, сколько запрошено
            if (generatedQuestions.size() != questionCount) {
                System.out.println("QuestionGenerationService: ВНИМАНИЕ! Запрошено " + questionCount + 
                        " вопросов, но сохранено " + generatedQuestions.size() + " вопросов.");
            }
            
            if (generatedQuestions.isEmpty()) {
                throw new RuntimeException("Не удалось сохранить ни одного вопроса из распарсенных");
            }
            
        } catch (UnethicalPromptException e) {
            // Если промпт не прошел VibeCheck, не создаем квиз и пробрасываем исключение дальше
            System.err.println("QuestionGenerationService: Промпт не прошел проверку на этичность: " + e.getMessage());
            questionSet.setStatus("FAILED");
            generationSetRepository.save(questionSet);
            throw e;
        } catch (Exception e) {
            // Для всех остальных ошибок также не создаем заглушки
            System.err.println("QuestionGenerationService: Ошибка при генерации вопросов: " + e.getMessage());
            e.printStackTrace();
            
            // Проверяем, не является ли это ошибкой этичности, обернутой в другое исключение
            Throwable cause = e.getCause();
            while (cause != null) {
                if (cause instanceof UnethicalPromptException) {
                    questionSet.setStatus("FAILED");
                    generationSetRepository.save(questionSet);
                    throw (UnethicalPromptException) cause;
                }
                cause = cause.getCause();
            }
            
            // Проверяем сообщение об ошибке на наличие упоминания об этичности
            String errorMessage = e.getMessage();
            if (errorMessage != null && (errorMessage.contains("неэтичн") || errorMessage.contains("UNETHICAL_PROMPT") || errorMessage.contains("неэтичными"))) {
                questionSet.setStatus("FAILED");
                generationSetRepository.save(questionSet);
                throw new UnethicalPromptException("Данные для создания квиза являются неэтичными");
            }
            
            questionSet.setStatus("FAILED");
            generationSetRepository.save(questionSet);
            throw new RuntimeException("Ошибка при генерации вопросов: " + e.getMessage(), e);
        }

        questionSet.setGeneratedCount(generatedQuestions.size());
        questionSet.setValidCount(generatedQuestions.size());
        questionSet.setFinalCount(generatedQuestions.size());
        questionSet.setStatus("READY");
        generationSetRepository.save(questionSet);

        return new QuestionGenerationResponse(
                questionSet.getId(),
                questionSet.getStatus(),
                generatedQuestions.size(),
                generatedQuestions.size(),
                0,
                generatedQuestions.size()
        );
    }

    public org.example.dto.response.generation.ValidationResponse validateGeneratedQuestions(Long questionSetId) {
        GenerationSet questionSet = generationSetRepository.findById(questionSetId)
                .orElseThrow(() -> new IllegalArgumentException("Набор вопросов не найден"));

        Long quizId = questionSet.getQuiz().getId();
        List<Question> questions = questionRepository.findByQuizId(quizId);
        List<Question> generatedQuestions = questions.stream()
                .filter(q -> q.getGenerationSetId() != null && questionSetId.equals(q.getGenerationSetId()))
                .collect(java.util.stream.Collectors.toList());
        
        return new org.example.dto.response.generation.ValidationResponse(
                questionSetId,
                generatedQuestions.size(),
                generatedQuestions.size(),
                new java.util.ArrayList<>()
        );
  }

    public org.example.dto.response.generation.DeduplicationResponse removeDuplicateQuestions(Long questionSetId) {
        GenerationSet questionSet = generationSetRepository.findById(questionSetId)
                .orElseThrow(() -> new IllegalArgumentException("Набор вопросов не найден"));

        Long quizId = questionSet.getQuiz().getId();
        List<Question> questions = questionRepository.findByQuizId(quizId);
        List<Question> validQuestions = questions.stream()
                .filter(q -> q.getGenerationSetId() != null && questionSetId.equals(q.getGenerationSetId()))
                .collect(java.util.stream.Collectors.toList());
        
        return new org.example.dto.response.generation.DeduplicationResponse(
                questionSetId,
                validQuestions.size(),
                validQuestions.size(),
                new java.util.ArrayList<>()
        );
    }

    public org.example.dto.response.generation.GeneratedQuestionsDTO getGeneratedQuestions(Long questionSetId) {
        GenerationSet questionSet = generationSetRepository.findById(questionSetId)
                .orElseThrow(() -> new IllegalArgumentException("Набор вопросов не найден"));

        Long quizId = questionSet.getQuiz().getId();
        List<Question> questions = questionRepository.findByQuizId(quizId);
        List<Question> generatedQuestions = questions.stream()
                .filter(q -> q.getGenerationSetId() != null && questionSetId.equals(q.getGenerationSetId()))
                .collect(java.util.stream.Collectors.toList());
        
        List<org.example.dto.response.quiz.QuestionDTO> questionDTOs = generatedQuestions.stream()
                .map(q -> {
                    List<org.example.model.AnswerOption> options = answerOptionRepository.findByQuestionId(q.getId());
                    List<org.example.dto.common.AnswerOption> dtoOptions = options.stream()
                            .map(opt -> new org.example.dto.common.AnswerOption(opt.getId(), opt.getText()))
                            .collect(java.util.stream.Collectors.toList());

                    return new org.example.dto.response.quiz.QuestionDTO(
                            q.getId(),
                            q.getText(),
                            dtoOptions,
                            q.getQuiz().getTimePerQuestion() != null ? 
                                    (int) q.getQuiz().getTimePerQuestion().getSeconds() : null,
                            null,
                            q.getExplanation(),
                            null,
                            null,
                            0,
                            toLocalDateTime(q.getQuiz().getCreatedAt())
                    );
                })
                .collect(java.util.stream.Collectors.toList());

        org.example.dto.common.GenerationMetadata metadata = new org.example.dto.common.GenerationMetadata(
                toLocalDateTime(questionSet.getCreatedAt()),
                "1.0",
                String.valueOf(questionSet.getPrompt() != null ? questionSet.getPrompt().hashCode() : 0)
        );

        return new org.example.dto.response.generation.GeneratedQuestionsDTO(
                questionSetId,
                questionDTOs,
                metadata
        );
    }

    private java.time.LocalDateTime toLocalDateTime(Instant instant) {
        return instant != null
                ? java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
                : null;
    }
}
