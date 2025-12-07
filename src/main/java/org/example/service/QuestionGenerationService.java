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
            String apiResponse = fastApiClient.getQuestionsByPrompt(prompt, questionCount);
            List<QuestionParser.ParsedQuestion> parsedQuestions = QuestionParser.parse(apiResponse);
            
            if (parsedQuestions.isEmpty()) {
                System.err.println("QuestionGenerationService: Парсер не нашел вопросов в ответе FastAPI");
                throw new RuntimeException("Парсер не нашел вопросов в ответе FastAPI");
            }
            
            for (int i = 0; i < parsedQuestions.size(); i++) {
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
            
            if (generatedQuestions.isEmpty()) {
                throw new RuntimeException("Не удалось сохранить ни одного вопроса из распарсенных");
            }
            
        } catch (Exception e) {
            System.err.println("QuestionGenerationService: Ошибка при генерации вопросов: " + e.getMessage());
            e.printStackTrace();
            
            if (generatedQuestions.isEmpty()) {
        for (int i = 0; i < questionCount; i++) {
                    Question question = new Question();
                    question.setQuiz(quiz);
                    question.setText("Вопрос " + (i + 1) + " по теме \"" + prompt + "\"");
                    question.setType(QuestionType.SINGLE_CHOICE);
                    question.setExplanation("Это правильный ответ");
                    question.setIsGenerated(true);
                    question.setGenerationSetId(questionSet.getId());
                    question.setIsValid(true);
                    question.setIsDuplicate(false);
                    question = questionRepository.save(question);

            for (int j = 0; j < 4; j++) {
                        AnswerOption option = new AnswerOption();
                        option.setQuestion(question);
                        option.setText("Вариант " + (j + 1));
                        option.setCorrect(j == 0);
                option.setNaOption(false);
                answerOptionRepository.save(option);
            }

                    generatedQuestions.add(question);
                }
            }
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
