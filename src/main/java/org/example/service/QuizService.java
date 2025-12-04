package org.example.service;

import org.example.dto.request.quiz.*;
import org.example.dto.response.quiz.*;
import org.example.dto.common.LeaderboardEntry;
import org.example.dto.common.QuizMaterial;
import org.example.model.Quiz;
import org.example.model.User;
import org.example.service.FileStorageService;
import org.example.repository.AnswerOptionRepository;
import org.example.repository.QuestionRepository;
import org.example.repository.QuizRepository;
import org.example.repository.UserQuizAttemptRepository;
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
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Сервис для управления квизами.
 */
@Service
@Transactional
public class QuizService {

    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final AnswerOptionRepository answerOptionRepository;
    private final UserQuizAttemptRepository attemptRepository;
    private final org.example.repository.UserRepository userRepository;
    private final FileStorageService fileStorageService;

    @Autowired
    public QuizService(QuizRepository quizRepository,
                      QuestionRepository questionRepository,
                      AnswerOptionRepository answerOptionRepository,
                      UserQuizAttemptRepository attemptRepository,
                      org.example.repository.UserRepository userRepository,
                      FileStorageService fileStorageService) {
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.answerOptionRepository = answerOptionRepository;
        this.attemptRepository = attemptRepository;
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
    }

    public QuizResponseDTO createQuiz(CreateQuizRequest request) {
        User creator = userRepository.findById(request.createdBy())
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));

        Quiz quiz = new Quiz();
        quiz.setName(request.name());
        quiz.setPrompt(request.prompt());
        quiz.setCreatedBy(creator);
        quiz.setHasMaterial(request.hasMaterial() != null && request.hasMaterial());
        quiz.setMaterialUrl(null); // TODO: Сохранение материалов
        quiz.setQuestionNumber(request.questionNumber());
        quiz.setTimePerQuestion(request.timeLimit() != null ? 
                java.time.Duration.ofSeconds(request.timeLimit()) : null);
        quiz.setPrivate(request.isPrivate() != null && request.isPrivate());
        quiz.setStatic(request.isStatic() != null && request.isStatic());
        quiz.setCreatedAt(Instant.now());

        quiz = quizRepository.save(quiz);

        // TODO: Генерация вопросов через ИИ
        // Пока возвращаем успешный ответ без вопросов

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
            // Проверяем, является ли запрос числом (ID квиза)
            try {
                Long quizId = Long.parseLong(query);
                Optional<Quiz> quizById = quizRepository.findById(quizId);
                if (quizById.isPresent() && !quizById.get().isPrivate()) {
                    // Если найден публичный квиз по ID, возвращаем его
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

        // Если квиз приватный, проверяем доступ: разрешаем доступ создателю или если пользователь знает ID
        if (quiz.isPrivate()) {
            // Разрешаем доступ создателю квиза
            if (userId != null && quiz.getCreatedBy().getId().equals(userId)) {
                // Создатель имеет доступ к своему приватному квизу
            } else {
                // Для других пользователей доступ к приватному квизу запрещен
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
                String.valueOf(quiz.getId()), // TODO: Генерация shareableId
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

        quizRepository.deleteById(request.quizId());
        return true;
    }

    public QuizResponseDTO updateQuiz(UpdateQuizRequest request) {
        Quiz quiz = quizRepository.findById(request.quizId())
                .orElseThrow(() -> new IllegalArgumentException("Квиз не найден"));

        if (!quizRepository.isCreator(request.quizId(), request.userId())) {
            throw new SecurityException("Только создатель может редактировать квиз");
        }

        if (request.name() != null) {
            quiz.setName(request.name());
        }
        if (request.prompt() != null) {
            quiz.setPrompt(request.prompt());
        }

        quiz = quizRepository.save(quiz);

        return new QuizResponseDTO(
                quiz.getId(),
                quiz.getName(),
                "updated",
                toLocalDateTime(quiz.getCreatedAt()),
                String.valueOf(quiz.getId())
        );
    }

    /**
     * Обновляет URL материала квиза.
     */
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
        // Используем метод, который возвращает только лучший результат каждого пользователя
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

    /**
     * Копирует квиз для нового пользователя.
     * Создает новый квиз с теми же параметрами, но без вопросов (вопросы будут сгенерированы заново).
     */
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

        // Вопросы не копируются - они будут сгенерированы заново при создании

        return new QuizResponseDTO(
                copiedQuiz.getId(),
                copiedQuiz.getName(),
                "copied",
                toLocalDateTime(copiedQuiz.getCreatedAt()),
                String.valueOf(copiedQuiz.getId())
        );
    }

    /**
     * Получает квиз по ID, включая приватные (для доступа по ID).
     */
    public QuizDetailsDTO getQuizById(Long quizId, Long userId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new IllegalArgumentException("Квиз не найден"));

        // Если квиз приватный, проверяем доступ
        if (quiz.isPrivate() && (userId == null || !quiz.getCreatedBy().getId().equals(userId))) {
            // Для приватных квизов доступ возможен только по прямому ID
            // В данном случае разрешаем доступ, так как пользователь знает ID
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

    // Вспомогательные методы
    private QuizDTO toQuizDTO(Quiz quiz) {
        // Считаем реальное количество вопросов в БД
        int actualQuestionCount = (int) questionRepository.countByQuizId(quiz.getId());
        // Если вопросов еще нет, но есть запланированное количество, показываем его
        // Иначе показываем реальное количество
        int questionCount = actualQuestionCount > 0 ? actualQuestionCount : 
                           (quiz.getQuestionNumber() != null ? quiz.getQuestionNumber() : 0);
        return new QuizDTO(
                quiz.getId(),
                quiz.getName(),
                quiz.getCreatedBy().getLogin(),
                questionCount,
                quiz.getTimePerQuestion() != null ? (int) quiz.getTimePerQuestion().getSeconds() : null,
                !quiz.isPrivate(),
                quiz.isStatic(),
                toLocalDateTime(quiz.getCreatedAt())
        );
    }

    private QuestionDTO toQuestionDTO(org.example.model.Question question) {
        List<org.example.model.AnswerOption> options = answerOptionRepository.findByQuestionId(question.getId());
        List<org.example.dto.common.AnswerOption> dtoOptions = options.stream()
                .map(opt -> new org.example.dto.common.AnswerOption(
                        opt.getId(),
                        opt.getText()
                ))
                .collect(Collectors.toList());

        return new QuestionDTO(
                question.getId(),
                question.getText(),
                dtoOptions,
                question.getQuiz().getTimePerQuestion() != null ? 
                        (int) question.getQuiz().getTimePerQuestion().getSeconds() : null,
                null, // materialReference
                question.getExplanation(),
                null, // difficulty
                null, // category
                0, // position
                toLocalDateTime(question.getQuiz().getCreatedAt())
        );
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant != null ? LocalDateTime.ofInstant(instant, ZoneId.systemDefault()) : null;
    }

    private Pageable createPageable(Integer page, Integer size, String sortBy, Boolean ascending) {
        int pageNum = page != null && page >= 0 ? page : 0;
        int pageSize = size != null && size > 0 ? size : 20;

        Sort.Direction direction = (ascending != null && ascending) ? 
                Sort.Direction.ASC : Sort.Direction.DESC;

        Sort sort;
        if (sortBy != null) {
            switch (sortBy.toLowerCase()) {
                case "name":
                    sort = Sort.by(direction, "name");
                    break;
                case "created":
                case "created_at":
                    sort = Sort.by(direction, "createdAt");
                    break;
                case "popularity":
                    // TODO: Сортировка по популярности (количество прохождений)
                    sort = Sort.by(direction, "createdAt");
                    break;
                default:
                    sort = Sort.by(direction, "createdAt");
            }
        } else {
            sort = Sort.by(direction, "createdAt");
        }

        return PageRequest.of(pageNum, pageSize, sort);
    }
}

