package org.example.service;

import org.example.dto.request.quiz.*;
import org.example.dto.response.quiz.*;
import org.example.dto.common.LeaderboardEntry;
import org.example.dto.common.QuizMaterial;
import org.example.model.Quiz;
import org.example.model.User;
import org.example.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class QuizService {
    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final AnswerOptionRepository answerOptionRepository;
    private final UserQuizAttemptRepository attemptRepository;
    private final UserAnswerRepository userAnswerRepository;
    private final GenerationSetRepository generationSetRepository;
    private final MultiplayerSessionRepository multiplayerSessionRepository;
    private final org.example.repository.UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final QuestionGenerationService questionGenerationService;

    @Autowired
    public QuizService(QuizRepository quizRepository,
                      QuestionRepository questionRepository,
                      AnswerOptionRepository answerOptionRepository,
                      UserQuizAttemptRepository attemptRepository,
                      UserAnswerRepository userAnswerRepository,
                      GenerationSetRepository generationSetRepository,
                      MultiplayerSessionRepository multiplayerSessionRepository,
                      org.example.repository.UserRepository userRepository,
                      FileStorageService fileStorageService,
                      QuestionGenerationService questionGenerationService) {
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.answerOptionRepository = answerOptionRepository;
        this.attemptRepository = attemptRepository;
        this.userAnswerRepository = userAnswerRepository;
        this.generationSetRepository = generationSetRepository;
        this.multiplayerSessionRepository = multiplayerSessionRepository;
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
        this.questionGenerationService = questionGenerationService;
    }

    public QuizResponseDTO createQuiz(CreateQuizRequest request) {
        User creator = userRepository.findById(request.createdBy())
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + request.createdBy()));

        Quiz quiz = new Quiz();
        quiz.setName(request.name());
        quiz.setPrompt(request.prompt());
        quiz.setCreatedBy(creator);
        quiz.setHasMaterial(request.hasMaterial() != null && request.hasMaterial());
        quiz.setMaterialUrl(null);
        quiz.setQuestionNumber(request.questionNumber());
        quiz.setTimePerQuestion(request.timeLimit() != null ? 
                java.time.Duration.ofSeconds(request.timeLimit()) : null);
        quiz.setPrivate(request.isPrivate() != null && request.isPrivate());
        quiz.setStatic(request.isStatic() != null && request.isStatic());
        quiz.setCreatedAt(Instant.now());

        quiz = quizRepository.save(quiz);

        if (request.prompt() != null && !request.prompt().trim().isEmpty()) {
            try {
                int questionCount = request.questionNumber() != null && request.questionNumber() > 0 
                    ? request.questionNumber() 
                    : 10;
                
                org.example.dto.request.generation.QuestionGenerationRequest genRequest = 
                    new org.example.dto.request.generation.QuestionGenerationRequest(
                        quiz.getId(),
                        request.prompt(),
                        request.materials(),
                        request.questionNumber(),
                        questionCount
                    );
                
                questionGenerationService.generateQuizQuestions(genRequest);
            } catch (Exception e) {
                System.err.println("QuizService: Ошибка при генерации вопросов: " + e.getMessage());
                e.printStackTrace();
            }
        }

        return new QuizResponseDTO(
                quiz.getId(),
                quiz.getName(),
                "created",
                toLocalDateTime(quiz.getCreatedAt()),
                String.valueOf(quiz.getId())
        );
    }

    public QuizSearchResponse searchPublicQuizzes(QuizSearchRequest request) {
        Pageable pageable = createPageable(request.page(), request.size(), request.sortBy(), request.ascending());
        
        Page<Quiz> page;
        if (request.query() == null || request.query().trim().isEmpty()) {
            page = quizRepository.findByIsPrivateFalse(pageable);
        } else {
            String query = request.query().trim();
            try {
                Long quizId = Long.parseLong(query);
                var quizById = quizRepository.findById(quizId);
                if (quizById.isPresent() && !quizById.get().isPrivate()) {
                    List<QuizDTO> content = List.of(toQuizDTO(quizById.get()));
                    return new QuizSearchResponse(content, 0, 1, 1L);
                }
            } catch (NumberFormatException e) {
                // Не число, продолжаем обычный поиск
            }
            page = quizRepository.searchPublicQuizzes(query, pageable);
        }

        List<QuizDTO> content = page.getContent().stream()
                .map(this::toQuizDTO)
                .collect(Collectors.toList());

        return new QuizSearchResponse(
                content,
                page.getNumber(),
                page.getTotalPages(),
                page.getTotalElements()
        );
    }

    public QuizDetailsDTO getQuiz(Long quizId, Long userId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new IllegalArgumentException("Квиз не найден"));

        if (quiz.isPrivate()) {
            if (userId != null && quiz.getCreatedBy().getId().equals(userId)) {
                // Создатель имеет доступ
            } else {
                throw new SecurityException("Доступ к приватному квизу запрещен");
            }
        }

        List<QuestionDTO> questions = questionRepository.findByQuizId(quizId).stream()
                .map(this::toQuestionDTO)
                .collect(Collectors.toList());

        List<QuizMaterial> materials = new ArrayList<>();
        if (quiz.isHasMaterial() && quiz.getMaterialUrl() != null) {
            // TODO: Загрузка материалов
        }

        return new QuizDetailsDTO(
                quiz.getId(),
                quiz.getName(),
                quiz.getPrompt(),
                quiz.getCreatedBy().getLogin(),
                questions,
                materials,
                quiz.getTimePerQuestion() != null ? (int) quiz.getTimePerQuestion().getSeconds() : null,
                !quiz.isPrivate(),
                quiz.isStatic(),
                String.valueOf(quiz.getId()),
                toLocalDateTime(quiz.getCreatedAt())
        );
    }

    public boolean deleteQuiz(DeleteQuizRequest request) {
        if (!quizRepository.existsById(request.quizId())) {
            throw new IllegalArgumentException("Квиз не найден");
        }

        if (!quizRepository.isCreator(request.quizId(), request.userId())) {
            throw new SecurityException("Только создатель может удалить квиз");
        }

        Long quizId = request.quizId();
        List<org.example.model.UserQuizAttempt> attempts = attemptRepository.findByQuizId(quizId);
        for (org.example.model.UserQuizAttempt attempt : attempts) {
            userAnswerRepository.findByAttemptId(attempt.getId()).forEach(userAnswerRepository::delete);
        }
        attemptRepository.findByQuizId(quizId).forEach(attemptRepository::delete);
        generationSetRepository.findAllByQuizId(quizId).forEach(generationSetRepository::delete);
        multiplayerSessionRepository.findByQuizId(quizId).forEach(multiplayerSessionRepository::delete);
        questionRepository.deleteByQuizId(quizId);
        try {
            fileStorageService.deleteQuizMaterials(quizId);
        } catch (Exception e) {
            // Игнорируем ошибки при удалении файлов
        }
        quizRepository.deleteById(quizId);
        return true;
    }

    public QuizResponseDTO updateQuiz(UpdateQuizRequest request) {
        Quiz quiz = quizRepository.findById(request.quizId())
                .orElseThrow(() -> new IllegalArgumentException("Квиз не найден"));

        if (!quizRepository.isCreator(request.quizId(), request.userId())) {
            throw new SecurityException("Только создатель может редактировать квиз");
        }

        // Сохраняем старый промпт для проверки изменения
        String oldPrompt = quiz.getPrompt();
        boolean promptChanged = request.prompt() != null && 
                                (oldPrompt == null || !request.prompt().equals(oldPrompt));

        if (request.name() != null) {
            quiz.setName(request.name());
        }
        if (request.prompt() != null) {
            quiz.setPrompt(request.prompt());
        }
        if (request.timeLimit() != null) {
            quiz.setTimePerQuestion(java.time.Duration.ofSeconds(request.timeLimit()));
        }
        if (request.isPrivate() != null) {
            quiz.setPrivate(request.isPrivate());
        }
        if (request.isStatic() != null) {
            quiz.setStatic(request.isStatic());
        }

        quiz = quizRepository.save(quiz);

        // Если промпт изменился, перегенерируем вопросы
        if (promptChanged) {
            try {
                // Определяем количество вопросов для генерации (берем текущее количество или дефолт 10)
                int questionCount = (int) questionRepository.countByQuizId(request.quizId());
                if (questionCount == 0) {
                    questionCount = quiz.getQuestionNumber() != null ? quiz.getQuestionNumber() : 10;
                }

                // Сначала обнуляем ссылки на answer_options в user_answers, чтобы избежать foreign key constraint
                userAnswerRepository.nullifySelectedAnswerReferences(request.quizId());
                
                // Затем удаляем все ответы пользователей, связанные с вопросами этого квиза
                userAnswerRepository.deleteByQuestionQuizId(request.quizId());
                
                // Затем удаляем старые вопросы (каскадно удалятся и варианты ответов)
                questionRepository.deleteByQuizId(request.quizId());

                // Генерируем новые вопросы на основе нового промпта
                org.example.dto.request.generation.QuestionGenerationRequest genRequest =
                        new org.example.dto.request.generation.QuestionGenerationRequest(
                                request.quizId(),
                                request.prompt(),
                                null, // materials
                                quiz.getQuestionNumber(),
                                questionCount
                        );
                questionGenerationService.generateQuizQuestions(genRequest);
            } catch (Exception e) {
                System.err.println("Ошибка при перегенерации вопросов после изменения промпта: " + e.getMessage());
                e.printStackTrace();
                // Не прерываем обновление квиза, если генерация вопросов не удалась
                // Пользователь сможет сгенерировать вопросы вручную позже
            }
        }

        return new QuizResponseDTO(
                quiz.getId(),
                quiz.getName(),
                "updated",
                toLocalDateTime(quiz.getCreatedAt()),
                String.valueOf(quiz.getId())
        );
    }

    public void updateQuizMaterialUrl(Long quizId, String materialUrl) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new IllegalArgumentException("Квиз не найден"));
        
        quiz.setMaterialUrl(materialUrl);
        quiz.setHasMaterial(true);
        quizRepository.save(quiz);
    }

    public boolean removeQuestionFromQuiz(RemoveQuestionRequest request) {
        if (!quizRepository.existsById(request.quizId())) {
            throw new IllegalArgumentException("Квиз не найден");
        }

        if (!quizRepository.isCreator(request.quizId(), request.userId())) {
            throw new SecurityException("Только создатель может удалять вопросы");
        }

        org.example.model.Question question = questionRepository.findByIdAndQuizId(request.questionId(), request.quizId())
                .orElseThrow(() -> new IllegalArgumentException("Вопрос не найден"));

        questionRepository.delete(question);
        return true;
    }

    public LeaderboardDTO getQuizLeaderboard(Long quizId, Long userId) {
        if (!quizRepository.existsById(quizId)) {
            throw new IllegalArgumentException("Квиз не найден");
        }

        Pageable pageable = PageRequest.of(0, 100);
        Page<org.example.model.UserQuizAttempt> attempts = attemptRepository.findBestAttemptsByQuizId(quizId, pageable);

        List<LeaderboardEntry> entries = new ArrayList<>();
        int userPosition = -1;
        Long userScore = null;

        for (int i = 0; i < attempts.getContent().size(); i++) {
            org.example.model.UserQuizAttempt attempt = attempts.getContent().get(i);
            long timeSpent = 0;
            if (attempt.getStartTime() != null && attempt.getFinishTime() != null) {
                timeSpent = java.time.Duration.between(attempt.getStartTime(), attempt.getFinishTime()).getSeconds();
            }

            entries.add(new LeaderboardEntry(
                    i + 1,
                    attempt.getUser().getLogin(),
                    attempt.getScore() != null ? attempt.getScore().intValue() : 0,
                    timeSpent
            ));

            if (attempt.getUser().getId().equals(userId)) {
                userPosition = i + 1;
                userScore = attempt.getScore() != null ? attempt.getScore().intValue() : 0L;
            }
        }

        return new LeaderboardDTO(entries, userPosition, userScore != null ? userScore.intValue() : null);
    }

    public QuizResponseDTO copyQuiz(Long quizId, Long newCreatorId) {
        Quiz originalQuiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new IllegalArgumentException("Квиз не найден"));

        User newCreator = userRepository.findById(newCreatorId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));

        Quiz copiedQuiz = new Quiz();
        copiedQuiz.setName(originalQuiz.getName() + " (копия)");
        copiedQuiz.setPrompt(originalQuiz.getPrompt());
        copiedQuiz.setCreatedBy(newCreator);
        copiedQuiz.setHasMaterial(originalQuiz.isHasMaterial());
        copiedQuiz.setMaterialUrl(originalQuiz.getMaterialUrl());
        copiedQuiz.setQuestionNumber(originalQuiz.getQuestionNumber());
        copiedQuiz.setTimePerQuestion(originalQuiz.getTimePerQuestion());
        copiedQuiz.setPrivate(originalQuiz.isPrivate());
        copiedQuiz.setStatic(originalQuiz.isStatic());
        copiedQuiz.setCreatedAt(Instant.now());

        copiedQuiz = quizRepository.save(copiedQuiz);

        return new QuizResponseDTO(
                copiedQuiz.getId(),
                copiedQuiz.getName(),
                "copied",
                toLocalDateTime(copiedQuiz.getCreatedAt()),
                String.valueOf(copiedQuiz.getId())
        );
    }

    public QuizDetailsDTO getQuizById(Long quizId, Long userId) {
        return getQuiz(quizId, userId);
    }

    private Pageable createPageable(Integer page, Integer size, String sortBy, Boolean ascending) {
        int pageNumber = page != null && page >= 0 ? page : 0;
        int pageSize = size != null && size > 0 ? size : 10;
        
        Sort.Direction direction = (ascending != null && ascending) ? 
                Sort.Direction.ASC : Sort.Direction.DESC;
        
        // Маппинг полей сортировки на реальные поля в Quiz
        String actualSortField = "createdAt"; // по умолчанию
        if (sortBy != null) {
            switch (sortBy.toLowerCase()) {
                case "name":
                    actualSortField = "name";
                    break;
                case "created":
                case "created_at":
                case "createdat":
                    actualSortField = "createdAt";
                    break;
                case "popularity":
                    // Поля popularity нет, используем createdAt
                    actualSortField = "createdAt";
                    break;
                default:
                    actualSortField = "createdAt";
            }
        }
        
        Sort sort = Sort.by(direction, actualSortField);
        return PageRequest.of(pageNumber, pageSize, sort);
    }

    private QuizDTO toQuizDTO(Quiz quiz) {
        int actualQuestionCount = (int) questionRepository.countByQuizId(quiz.getId());
        int questionCount = actualQuestionCount > 0 ? actualQuestionCount : 
                           (quiz.getQuestionNumber() != null ? quiz.getQuestionNumber() : 0);
        return new QuizDTO(
                quiz.getId(),
                quiz.getName(),
                quiz.getCreatedBy().getLogin(),
                questionCount,
                quiz.getTimePerQuestion() != null ? (int) quiz.getTimePerQuestion().getSeconds() / 60 : null,
                !quiz.isPrivate(),
                quiz.isStatic(),
                toLocalDateTime(quiz.getCreatedAt())
        );
    }

    private QuestionDTO toQuestionDTO(org.example.model.Question question) {
        List<org.example.model.AnswerOption> options = answerOptionRepository.findByQuestionId(question.getId());
        List<org.example.dto.common.AnswerOption> dtoOptions = options.stream()
                .map(opt -> new org.example.dto.common.AnswerOption(opt.getId(), opt.getText()))
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
                null,
                question.getExplanation(),
                null,
                null,
                0,
                toLocalDateTime(question.getQuiz().getCreatedAt())
        );
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant != null
                ? LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                : null;
            }
}
