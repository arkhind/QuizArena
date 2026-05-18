package org.example.controller;
import org.example.dto.request.auth.*;
import org.example.dto.request.quiz.*;
import org.example.dto.request.attempt.*;
import org.example.dto.request.multiplayer.*;
import org.example.dto.request.generation.*;

import org.example.dto.response.auth.*;
import org.example.dto.response.quiz.*;
import org.example.dto.response.attempt.*;
import org.example.dto.response.multiplayer.*;
import org.example.dto.response.history.*;
import org.example.dto.response.generation.*;

import org.example.dto.common.*;
import org.example.repository.*;
import org.example.model.UserQuizAttempt;
import org.example.service.AttemptService;
import org.example.service.JwtService;
import org.example.service.QuizService;
import org.example.service.ApiService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

@Controller
public class PageController {
    private static final int PAGE_SIZE = 5;

    public record LeaderboardRow(
            Integer position,
            String username,
            Integer points,
            Integer accuracyPercent,
            String formattedTime,
            boolean currentUser
    ) {}

    public record SessionResultRow(
            Integer position,
            String username,
            Integer points,
            Integer correctAnswers,
            String formattedTime,
            boolean currentUser
    ) {}

    private final ApiController apiController;
    private final QuizService quizService;
    private final UserQuizAttemptRepository attemptRepository;
    private final ApiService apiService;
    private final org.example.repository.MultiplayerSessionRepository multiplayerSessionRepository;
    private final org.example.repository.UserRepository userRepository;
    private final org.example.repository.QuizRepository quizRepository;
    private final AttemptService attemptService;
    private final JwtService jwtService;

    @Autowired
    public PageController(ApiController apiController, QuizService quizService, UserQuizAttemptRepository attemptRepository, ApiService apiService, org.example.repository.MultiplayerSessionRepository multiplayerSessionRepository, org.example.repository.UserRepository userRepository, org.example.repository.QuizRepository quizRepository, AttemptService attemptService, JwtService jwtService) {
        this.apiController = apiController;
        this.quizService = quizService;
        this.attemptRepository = attemptRepository;
        this.apiService = apiService;
        this.multiplayerSessionRepository = multiplayerSessionRepository;
        this.userRepository = userRepository;
        this.quizRepository = quizRepository;
        this.attemptService = attemptService;
        this.jwtService = jwtService;
    }
    
    @GetMapping("/")
    public String index() {
        return "redirect:/login";
    }
    
    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/register")
    public String register() {
        return "register";
    }

    @GetMapping("/logout")
    public String logout(HttpServletResponse response) {
        clearAuthAndRedirectToLogin(response);
        return "redirect:/login?logout=1";
    }

    @GetMapping("/home")
    public String home(@RequestParam(required = false) String search,
                       @RequestParam(required = false, defaultValue = "0") Integer page,
                       @RequestParam(required = false, defaultValue = "created") String sortBy,
                       @RequestParam(required = false, defaultValue = "false") Boolean ascending,
                       Model model) {
        String searchQuery = search != null ? search : "";
        int pageNumber = sanitizePage(page);
        String normalizedSortBy = normalizeHomeSort(sortBy);
        boolean sortAscending = Boolean.TRUE.equals(ascending);
        
        QuizSearchRequest request = new QuizSearchRequest(searchQuery, normalizedSortBy, sortAscending, pageNumber, PAGE_SIZE);
        QuizSearchResponse response = apiService.searchPublicQuizzes(request);
        
        model.addAttribute("quizzes", response.content());
        addPaginationAttributes(
                model,
                response.currentPage(),
                response.totalPages(),
                response.totalElements() != null ? response.totalElements().intValue() : 0,
                "/home",
                "квизов"
        );
        model.addAttribute("search", searchQuery);
        model.addAttribute("sortBy", normalizedSortBy);
        model.addAttribute("ascending", sortAscending);
        return "home";
    }

    @GetMapping("/profile")
    public String profile(HttpServletRequest request, HttpServletResponse response, Model model) {
        Long userId = resolveCurrentUserId(request);
        if (userId == null) {
            clearAuthAndRedirectToLogin(response);
            return "redirect:/login?logout=1";
        }
        if (userRepository.findById(userId).isEmpty()) {
            clearAuthAndRedirectToLogin(response);
            return "redirect:/login?logout=1";
        }

        UserProfileDTO userProfile = apiController.getUserProfile(userId);
        UserHistoryDTO userHistory = apiController.getUserHistory(userId);
        List<QuizDTO> createdQuizzes = apiController.getCreatedQuizzes(userId);
        
        model.addAttribute("userProfile", userProfile);
        model.addAttribute("userHistory", userHistory);
        model.addAttribute("createdQuizzes", createdQuizzes);
        return "profile";
    }

    @GetMapping("/edit-profile")
    public String editProfile(HttpServletRequest request, HttpServletResponse response, Model model) {
        Long userId = resolveCurrentUserId(request);
        if (userId == null) {
            clearAuthAndRedirectToLogin(response);
            return "redirect:/login?logout=1";
        }
        if (userRepository.findById(userId).isEmpty()) {
            clearAuthAndRedirectToLogin(response);
            return "redirect:/login?logout=1";
        }

        try {
            UserProfileDTO userProfile = apiController.getUserProfile(userId);
            model.addAttribute("userProfile", userProfile);
            model.addAttribute("userId", userId);
            return "edit-profile";
        } catch (Exception e) {
            return "redirect:/profile";
        }
    }

    private Long resolveCurrentUserId(HttpServletRequest request) {
        return jwtService.extractUserIdFromRequest(request);
    }

    private void clearAuthAndRedirectToLogin(HttpServletResponse response) {
        Cookie tokenCookie = new Cookie("authToken", "");
        tokenCookie.setHttpOnly(true);
        tokenCookie.setPath("/");
        tokenCookie.setMaxAge(0);
        response.addCookie(tokenCookie);
    }

    private String getRequestTarget(HttpServletRequest request) {
        String target = request.getRequestURI();
        String queryString = request.getQueryString();
        if (queryString != null && !queryString.isBlank()) {
            target += "?" + queryString;
        }
        return target;
    }

    private Optional<org.example.model.Quiz> findAccessibleQuiz(Long quizId, Long userId) {
        return quizRepository.findById(quizId)
                .filter(quiz -> !quiz.isPrivate()
                        || (userId != null
                        && quiz.getCreatedBy() != null
                        && quiz.getCreatedBy().getId() != null
                        && quiz.getCreatedBy().getId().equals(userId)));
    }

    private boolean canEditQuiz(Long quizId, Long userId) {
        return userId != null && quizRepository.isCreator(quizId, userId);
    }

    private String renderNotFound(HttpServletResponse response, Model model) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        model.addAttribute("errorCode", "404");
        model.addAttribute("errorMessage", "Страница не найдена");
        return "error";
    }

    @GetMapping("/history")
    public String historyPage(HttpServletRequest request,
                              HttpServletResponse response,
                              @RequestParam(required = false, defaultValue = "0") Integer page,
                              Model model) {
        Long userId = resolveCurrentUserId(request);
        if (userId == null || userRepository.findById(userId).isEmpty()) {
            clearAuthAndRedirectToLogin(response);
            return "redirect:/login?logout=1";
        }
        try {
            UserHistoryDTO history = apiController.getUserHistory(userId);
            List<AttemptSummary> attempts = history.attempts() != null ? history.attempts() : List.of();
            int currentPage = clampPage(sanitizePage(page), attempts.size());

            model.addAttribute("attempts", pageItems(attempts, currentPage));
            model.addAttribute("userId", userId);
            addPaginationAttributes(model, currentPage, totalPages(attempts.size()), attempts.size(), "/history", "попыток");

            return "history";
        } catch (Exception e) {
            model.addAttribute("attempts", List.of());
            model.addAttribute("userId", userId);
            addPaginationAttributes(model, 0, 0, 0, "/history", "попыток");
            return "history";
        }
    }

    @GetMapping("/quiz")
    public String quizPage(
        @RequestParam(required = false) Long quizId,
        @RequestParam(required = false) Long userId,
        @RequestParam(required = false) String sessionId,
        HttpServletResponse response,
        Model model) {
        if (quizId != null && findAccessibleQuiz(quizId, userId).isEmpty()) {
            return renderNotFound(response, model);
        }
        
        QuizDTO quiz = null;
        LeaderboardDTO leaderboard = null;
        GenerationStatusResponse generationStatus = null;
        boolean hasQuestions = false;
        
        if (quizId != null) {
            try {
                QuizDetailsDTO quizDetails = quizService.getQuiz(quizId, userId);
                hasQuestions = quizDetails.questions() != null && !quizDetails.questions().isEmpty();
                quiz = new QuizDTO(
                    quizDetails.id(),
                    quizDetails.name(),
                    quizDetails.author(),
                    quizDetails.questions() != null ? quizDetails.questions().size() : 0,
                    quizDetails.timeLimit(),
                    quizDetails.timePerQuestion(),
                    quizDetails.isPublic(),
                    quizDetails.isStatic(),
	                quizDetails.createdAt(),
                    attemptRepository.countCompletedAttemptsByQuizId(quizId)
	            );
                generationStatus = quizService.getGenerationStatus(quizId);
            } catch (Exception e) {
                // Если квиз не найден, оставляем quiz = null
            }
        }
        
        if (quizId != null && userId != null) {
            try {
                leaderboard = quizService.getQuizLeaderboard(quizId, userId);
            } catch (Exception e) {
            }
        }
        List<QuizDTO> quizzes = quiz != null ? List.of(quiz) : List.of();
        
        // Проверяем, является ли пользователь админом (по логину "admin" или ID=1)
        boolean isAdmin = false;
        if (userId != null) {
            try {
                org.example.model.User user = userRepository.findById(userId).orElse(null);
                if (user != null && ("admin".equalsIgnoreCase(user.getLogin()) || userId == 1L)) {
                    isAdmin = true;
                }
            } catch (Exception e) {
                // Игнорируем ошибки при проверке
            }
        }
        
        // Проверяем, является ли пользователь создателем квиза
        boolean isCreator = false;
        if (quizId != null && userId != null) {
            try {
                isCreator = quizRepository.isCreator(quizId, userId);
                System.out.println("PageController.quizPage: quizId=" + quizId + ", userId=" + userId + ", isCreator=" + isCreator);
            } catch (Exception e) {
                System.err.println("PageController.quizPage: Ошибка при проверке создателя: " + e.getMessage());
            }
        }
        
        System.out.println("PageController.quizPage: isAdmin=" + isAdmin + ", isCreator=" + isCreator);
        
        // Проверяем, является ли пользователь создателем квиза в мультиплеере
        boolean isMultiplayerHost = false;
        if (sessionId != null && userId != null) {
            try {
                MultiplayerSessionDTO session = apiController.getMultiplayerSession(sessionId);
                isMultiplayerHost = session.hostUserId().equals(userId);
            } catch (Exception e) {
                // Игнорируем ошибки
            }
        }
        
        model.addAttribute("quizzes", quizzes);
        model.addAttribute("leaderboard", leaderboard);
        model.addAttribute("quizId", quizId);
        model.addAttribute("userId", userId);
        model.addAttribute("hasQuestions", hasQuestions);
        model.addAttribute("generationStatus", generationStatus);
        model.addAttribute("isAdmin", isAdmin);
        model.addAttribute("isCreator", isCreator);
        model.addAttribute("sessionId", sessionId);
        model.addAttribute("isMultiplayerHost", isMultiplayerHost);
        if (userId != null && sessionId == null) {
            UserQuizAttempt activeAttempt = attemptRepository
                    .findTopByUserIdAndQuizIdAndSessionIdIsNullAndIsCompletedFalseOrderByIdDesc(userId, quizId);
            model.addAttribute("activeAttemptId", activeAttempt != null ? activeAttempt.getId() : null);
        }
        
        return "quiz";
    }

    @GetMapping("/quiz/{quizId}")
    public String quizPageByPath(@PathVariable Long quizId, HttpServletRequest request, HttpServletResponse response, Model model,
                                  @RequestParam(required = false) String error,
                                  @RequestParam(required = false) String sessionId) {
        // Извлекаем userId из токена для проверки доступа к приватным квизам
        Long userId = jwtService.extractUserIdFromRequest(request);
        if (findAccessibleQuiz(quizId, userId).isEmpty()) {
            return renderNotFound(response, model);
        }
        
        QuizDTO quiz = null;
        LeaderboardDTO leaderboard = null;
        GenerationStatusResponse generationStatus = null;
        boolean hasQuestions = false;
        
        try {
            QuizDetailsDTO quizDetails = quizService.getQuiz(quizId, userId);
            hasQuestions = quizDetails.questions() != null && !quizDetails.questions().isEmpty();
            quiz = new QuizDTO(
                quizDetails.id(),
                quizDetails.name(),
                quizDetails.author(),
                quizDetails.questions() != null ? quizDetails.questions().size() : 0,
                quizDetails.timeLimit(),
                quizDetails.timePerQuestion(),
                quizDetails.isPublic(),
                quizDetails.isStatic(),
                quizDetails.createdAt(),
                attemptRepository.countCompletedAttemptsByQuizId(quizId)
            );
            generationStatus = quizService.getGenerationStatus(quizId);
        } catch (Exception e) {
            // Если квиз не найден, возвращаем пустой список
        }
        
        // Пытаемся загрузить лидерборд (может быть null если userId не передан)
        try {
            leaderboard = quizService.getQuizLeaderboard(quizId, userId);
        } catch (Exception e) {
            // Лидерборд может быть пустым или недоступным
        }
        
        List<QuizDTO> quizzes = quiz != null ? List.of(quiz) : List.of();
        
        // Проверяем, является ли пользователь админом (по логину "admin" или ID=1)
        boolean isAdmin = false;
        if (userId != null) {
            try {
                org.example.model.User user = userRepository.findById(userId).orElse(null);
                if (user != null && ("admin".equalsIgnoreCase(user.getLogin()) || userId == 1L)) {
                    isAdmin = true;
                }
            } catch (Exception e) {
                // Игнорируем ошибки при проверке
            }
        }
        
        // Проверяем, является ли пользователь создателем квиза
        boolean isCreator = false;
        if (quizId != null && userId != null) {
            try {
                isCreator = quizRepository.isCreator(quizId, userId);
                System.out.println("PageController.quizPageByPath: quizId=" + quizId + ", userId=" + userId + ", isCreator=" + isCreator);
            } catch (Exception e) {
                System.err.println("PageController.quizPageByPath: Ошибка при проверке создателя: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("PageController.quizPageByPath: quizId=" + quizId + ", userId=" + userId + " - пропуск проверки создателя");
        }
        
        System.out.println("PageController.quizPageByPath: isAdmin=" + isAdmin + ", isCreator=" + isCreator + ", передаем в модель");
        
        // Проверяем, является ли пользователь создателем квиза в мультиплеере
        boolean isMultiplayerHost = false;
        if (sessionId != null && userId != null) {
            try {
                MultiplayerSessionDTO session = apiController.getMultiplayerSession(sessionId);
                isMultiplayerHost = session.hostUserId().equals(userId);
            } catch (Exception e) {
                // Игнорируем ошибки
            }
        }
        
        model.addAttribute("quizzes", quizzes);
        model.addAttribute("leaderboard", leaderboard);
        model.addAttribute("quizId", quizId);
        model.addAttribute("userId", userId);
        model.addAttribute("hasQuestions", hasQuestions);
        model.addAttribute("generationStatus", generationStatus);
        model.addAttribute("isAdmin", isAdmin);
        model.addAttribute("isCreator", isCreator);
        model.addAttribute("sessionId", sessionId);
        model.addAttribute("isMultiplayerHost", isMultiplayerHost);
        if (userId != null && sessionId == null) {
            UserQuizAttempt activeAttempt = attemptRepository
                    .findTopByUserIdAndQuizIdAndSessionIdIsNullAndIsCompletedFalseOrderByIdDesc(userId, quizId);
            model.addAttribute("activeAttemptId", activeAttempt != null ? activeAttempt.getId() : null);
        }
        
        
        // Обработка ошибок
        if ("noQuestions".equals(error)) {
            model.addAttribute("errorMessage", "Этот квиз не содержит вопросов. Невозможно начать прохождение.");
        } else if ("startFailed".equals(error)) {
            model.addAttribute("errorMessage", "Ошибка при начале квиза. Попробуйте позже.");
        } else if ("accessDenied".equals(error)) {
            model.addAttribute("errorMessage", "Доступ к этому квизу запрещен. Это приватный квиз.");
        } else if ("notFound".equals(error)) {
            model.addAttribute("errorMessage", "Квиз или пользователь не найдены.");
        }
        
        return "quiz";
    }

    @GetMapping("/my-quizzes")
    public String myQuizzes(HttpServletRequest request,
                            HttpServletResponse response,
                            @RequestParam(required = false, defaultValue = "0") Integer page,
                            Model model) {
        if (request.getParameter("userId") != null) {
            return "redirect:/my-quizzes";
        }
        Long userId = resolveCurrentUserId(request);
        if (userId == null || userRepository.findById(userId).isEmpty()) {
            clearAuthAndRedirectToLogin(response);
            return "redirect:/login?logout=1";
        }
        java.util.List<QuizDTO> createdQuizzes = apiService.getCreatedQuizzes(userId);
        int currentPage = clampPage(sanitizePage(page), createdQuizzes.size());
        model.addAttribute("createdQuizzes", pageItems(createdQuizzes, currentPage));
        model.addAttribute("userId", userId);
        addPaginationAttributes(model, currentPage, totalPages(createdQuizzes.size()), createdQuizzes.size(), "/my-quizzes", "квизов");
        return "my-quizzes";
    }

    private int sanitizePage(Integer page) {
        return page != null && page > 0 ? page : 0;
    }

    private String normalizeHomeSort(String sortBy) {
        if (sortBy == null) {
            return "created";
        }
        return switch (sortBy) {
            case "completions", "questions", "created" -> sortBy;
            default -> "created";
        };
    }

    private int totalPages(int totalElements) {
        return totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / PAGE_SIZE);
    }

    private int clampPage(int page, int totalElements) {
        int totalPages = totalPages(totalElements);
        if (totalPages == 0) {
            return 0;
        }
        return Math.min(page, totalPages - 1);
    }

    private <T> List<T> pageItems(List<T> items, int page) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        int fromIndex = Math.min(page * PAGE_SIZE, items.size());
        int toIndex = Math.min(fromIndex + PAGE_SIZE, items.size());
        return items.subList(fromIndex, toIndex);
    }

    private void addPaginationAttributes(
            Model model,
            int currentPage,
            int totalPages,
            int totalElements,
            String pageUrl,
            String itemLabel
    ) {
        int shownFrom = totalElements == 0 ? 0 : currentPage * PAGE_SIZE + 1;
        int shownTo = totalElements == 0 ? 0 : Math.min(shownFrom + PAGE_SIZE - 1, totalElements);
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalElements", totalElements);
        model.addAttribute("shownFrom", shownFrom);
        model.addAttribute("shownTo", shownTo);
        model.addAttribute("pageUrl", pageUrl);
        model.addAttribute("pageItemLabel", itemLabel);
    }

    @GetMapping("/quiz/create")
    public String createQuizPage(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Model model) {
        Long userId = resolveCurrentUserId(request);
        if (userId == null || userRepository.findById(userId).isEmpty()) {
            clearAuthAndRedirectToLogin(response);
            return "redirect:/login?logout=1";
        }
        model.addAttribute("userId", userId);
        return "create-quiz";
    }

    @GetMapping("/quiz/{quizId}/edit")
    public String editQuizPage(@PathVariable Long quizId,
                               HttpServletRequest request,
                               HttpServletResponse response,
                               Model model) {
        Long userId = resolveCurrentUserId(request);
        if (userId == null || userRepository.findById(userId).isEmpty()) {
            clearAuthAndRedirectToLogin(response);
            return "redirect:/login?logout=1";
        }
        if (!canEditQuiz(quizId, userId)) {
            return renderNotFound(response, model);
        }
        QuizDetailsDTO quiz = apiController.getQuiz(quizId, userId);

        model.addAttribute("quiz", quiz);
        model.addAttribute("quizId", quizId);
        model.addAttribute("userId", userId);
        model.addAttribute("quizName", quiz.name());
        model.addAttribute("description", quiz.description());
        model.addAttribute("questions", quiz.questions());
        model.addAttribute("materials", quiz.materials());
        model.addAttribute("timeLimit", quiz.timePerQuestion());
        model.addAttribute("isPublic", quiz.isPublic());
        model.addAttribute("isStatic", quiz.isStatic());

        return "edit-quiz";
    }

    @GetMapping("/multiplayer/create")
    public String createMultiplayerSession(@RequestParam Long quizId,
                                           HttpServletRequest request,
                                           HttpServletResponse response,
                                           Model model) {
        Long userId = resolveCurrentUserId(request);
        if (userId == null || userRepository.findById(userId).isEmpty()) {
            clearAuthAndRedirectToLogin(response);
            return "redirect:/login?logout=1";
        }
        if (findAccessibleQuiz(quizId, userId).isEmpty()) {
            return renderNotFound(response, model);
        }
        try {
            CreateMultiplayerRequest createRequest = new CreateMultiplayerRequest(userId, quizId);
            MultiplayerSessionDTO session = apiController.createMultiplayerSession(createRequest);
            return "redirect:/multiplayer/session/" + session.sessionId();
        } catch (Exception e) {
            model.addAttribute("errorMessage", "Ошибка при создании сессии: " + e.getMessage());
            return "redirect:/quiz/" + quizId;
        }
    }

    @GetMapping("/multiplayer/join")
    public String joinMultiplayerPage(@RequestParam(required = false) String sessionId, 
                                      HttpServletRequest request,
                                      HttpServletResponse response,
                                      Model model) {
        Long userId = resolveCurrentUserId(request);
        if (userId == null || userRepository.findById(userId).isEmpty()) {
            clearAuthAndRedirectToLogin(response);
            return "redirect:/login?next=" + URLEncoder.encode(getRequestTarget(request), StandardCharsets.UTF_8);
        }

        model.addAttribute("sessionId", sessionId);
        model.addAttribute("userId", userId);
        return "multiplayer-join";
    }

    @GetMapping("/multiplayer/session/{sessionId}")
    public String multiplayerSessionPage(@PathVariable String sessionId,
                                         HttpServletRequest request,
                                         HttpServletResponse response,
                                         Model model) {
        Long userId = resolveCurrentUserId(request);
        if (userId == null || userRepository.findById(userId).isEmpty()) {
            clearAuthAndRedirectToLogin(response);
            return "redirect:/login?logout=1";
        }
        try {
            MultiplayerSessionDTO session = apiController.getMultiplayerSession(sessionId);
            org.example.model.MultiplayerSession sessionEntity = multiplayerSessionRepository.findBySessionId(sessionId).orElse(null);
            Long quizId = sessionEntity != null ? sessionEntity.getQuiz().getId() : null;
            Long actualHostUserId = session.hostUserId();
            boolean isParticipant = session.participants() != null && session.participants().stream()
                    .anyMatch(participant -> participant.userId() != null && participant.userId().equals(userId));
            if (!isParticipant && !actualHostUserId.equals(userId)) {
                return "redirect:/multiplayer/join?sessionId=" + sessionId;
            }
            
            model.addAttribute("session", session);
            model.addAttribute("sessionId", sessionId);
            model.addAttribute("hostUserId", actualHostUserId);
            model.addAttribute("currentUserId", userId);
            model.addAttribute("quizName", session.quizName());
            model.addAttribute("participants", session.participants());
            model.addAttribute("status", session.status());
            model.addAttribute("joinLink", session.joinLink());
            model.addAttribute("quizId", quizId);
        } catch (IllegalArgumentException e) {
            System.err.println("PageController.multiplayerSessionPage: сессия не найдена: " + sessionId);
            model.addAttribute("sessionId", sessionId);
            model.addAttribute("hostUserId", userId);
            model.addAttribute("errorMessage", "Сессия не найдена: " + sessionId);
            model.addAttribute("quizName", "Сессия не найдена");
            model.addAttribute("participants", List.of());
            model.addAttribute("status", "NOT_FOUND");
            model.addAttribute("joinLink", "/multiplayer/join?sessionId=" + sessionId);
            model.addAttribute("quizId", null);
        }
        
        return "multiplayer-session";
    }

    @GetMapping("/quiz/attempt/{attemptId}/finish")
    public String finishQuizPage(@PathVariable Long attemptId,
                                 HttpServletRequest request,
                                 HttpServletResponse response,
                                 Model model) {
        Long userId = resolveCurrentUserId(request);
        Optional<org.example.model.User> currentUserOpt = userId != null
                ? userRepository.findById(userId)
                : Optional.empty();
        if (userId == null || currentUserOpt.isEmpty()) {
            clearAuthAndRedirectToLogin(response);
            return "redirect:/login?logout=1";
        }
        String currentUsername = currentUserOpt.get().getLogin();

        UserQuizAttempt attempt = attemptRepository.findById(attemptId).orElse(null);
        if (attempt == null || attempt.getUser() == null || !userId.equals(attempt.getUser().getId())) {
            return renderNotFound(response, model);
        }
        if (attempt.getSessionId() != null && !attempt.getSessionId().isBlank()
                && attempt.getStartTime() == null && !attempt.isCompleted()) {
            return renderNotFound(response, model);
        }

        Long quizId = attempt.getQuiz() != null ? attempt.getQuiz().getId() : null;
        if (quizId == null) {
            return renderNotFound(response, model);
        }

        QuizResultDTO result = apiController.finishQuizAttempt(attemptId);

        String quizName = "Квиз";
        Integer quizQuestionCount = result.totalQuestions();
        try {
            QuizDetailsDTO quiz = quizService.getQuiz(quizId, userId);
            quizName = quiz.name();
            if (quiz.questionNumber() != null && quiz.questionNumber() > 0) {
                quizQuestionCount = quiz.questionNumber();
            }
        } catch (Exception e) {
            quizName = "Квиз";
        }

        LeaderboardDTO leaderboard = null;
        if (quizId != null && userId != null) {
            try {
                leaderboard = quizService.getQuizLeaderboard(quizId, userId);
            } catch (Exception ignored) {
            }
        }

        UserQuizAttempt activeAttempt = null;
        if (quizId != null && userId != null) {
            activeAttempt = attemptRepository
                    .findTopByUserIdAndQuizIdAndSessionIdIsNullAndIsCompletedFalseOrderByIdDesc(userId, quizId);
        }

        long timeSpentSeconds = result.timeSpent() != null ? Math.max(0L, result.timeSpent()) : 0L;
        String formattedTimeSpent = String.format("%02d:%02d", timeSpentSeconds / 60, timeSpentSeconds % 60);
        int leaderboardSize = leaderboard != null && leaderboard.entries() != null ? leaderboard.entries().size() : 0;
        int outperformedPercent = 0;
        if (leaderboardSize > 0 && result.position() != null && result.position() > 0) {
            if (leaderboardSize == 1) {
                outperformedPercent = 0;
            } else {
                outperformedPercent = Math.min(100,
                        Math.max(0, ((leaderboardSize - result.position()) * 100) / (leaderboardSize - 1)));
            }
        }

        List<LeaderboardRow> leaderboardRows = new java.util.ArrayList<>();
        if (leaderboard != null && leaderboard.entries() != null) {
            for (LeaderboardEntry entry : leaderboard.entries()) {
                boolean currentUser = userPositionMatches(leaderboard.userPosition(), entry.position());
                long entryTimeSpent = entry.timeSpent() != null ? Math.max(0L, entry.timeSpent()) : 0L;
                leaderboardRows.add(new LeaderboardRow(
                        entry.position(),
                        entry.username(),
                        entry.points() != null ? entry.points() : 0,
                        entry.accuracyPercent() != null ? entry.accuracyPercent() : 0,
                        formatDurationHms(entryTimeSpent),
                        currentUser
                ));
            }
        }

        boolean multiplayerAttempt = attempt.getSessionId() != null && !attempt.getSessionId().isBlank();
        String sessionId = multiplayerAttempt ? attempt.getSessionId() : null;
        List<SessionResultRow> sessionResultRows = new java.util.ArrayList<>();
        Integer sessionPosition = null;
        if (multiplayerAttempt) {
            try {
                MultiplayerResultsDTO sessionResults = apiController.getMultiplayerResults(sessionId);
                if (sessionResults != null && sessionResults.results() != null) {
                    for (PlayerResult resultRow : sessionResults.results()) {
                        boolean currentUser = currentUsername != null && currentUsername.equals(resultRow.username());
                        if (currentUser) {
                            sessionPosition = resultRow.position();
                        }
                        long rowTimeSpent = resultRow.timeSpent() != null ? Math.max(0L, resultRow.timeSpent()) : 0L;
                        sessionResultRows.add(new SessionResultRow(
                                resultRow.position(),
                                resultRow.username(),
                                resultRow.points() != null ? resultRow.points() : 0,
                                resultRow.score() != null ? resultRow.score() : 0,
                                formatDurationHms(rowTimeSpent),
                                currentUser
                        ));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        UserQuizAttempt finishedAttempt = attemptRepository.findById(attemptId).orElse(attempt);
        Integer accuracyPercent = finishedAttempt.getAccuracyPercent() != null ? finishedAttempt.getAccuracyPercent() : 0;
        if (leaderboard != null && leaderboard.entries() != null && leaderboard.userPosition() != null) {
            for (LeaderboardEntry entry : leaderboard.entries()) {
                if (userPositionMatches(leaderboard.userPosition(), entry.position()) && entry.accuracyPercent() != null) {
                    accuracyPercent = entry.accuracyPercent();
                    break;
                }
            }
        }

        model.addAttribute("score", result.score());
        model.addAttribute("points", result.points());
        model.addAttribute("correctAnswers", result.correctAnswers());
        model.addAttribute("totalQuestions", quizQuestionCount);
        model.addAttribute("position", result.position());
        model.addAttribute("accuracyPercent", accuracyPercent);
        model.addAttribute("timeSpentSeconds", timeSpentSeconds);
        model.addAttribute("formattedTimeSpent", formattedTimeSpent);
        model.addAttribute("quizId", quizId);
        model.addAttribute("quizName", quizName);
        model.addAttribute("leaderboard", leaderboard);
        model.addAttribute("leaderboardRows", leaderboardRows);
        model.addAttribute("multiplayerAttempt", multiplayerAttempt);
        model.addAttribute("sessionId", sessionId);
        model.addAttribute("sessionResultRows", sessionResultRows);
        model.addAttribute("sessionPosition", sessionPosition);
        model.addAttribute("sessionSize", sessionResultRows.size());
        model.addAttribute("currentUsername", currentUsername);
        model.addAttribute("userPosition", leaderboard != null ? leaderboard.userPosition() : null);
        model.addAttribute("leaderboardSize", leaderboardSize);
        model.addAttribute("outperformedPercent", outperformedPercent);
        model.addAttribute("userId", userId);
        model.addAttribute("activeAttemptId", activeAttempt != null ? activeAttempt.getId() : null);
        return "quiz-results";
    }

    private boolean userPositionMatches(Integer userPosition, Integer entryPosition) {
        return userPosition != null && entryPosition != null && entryPosition.equals(userPosition);
    }

    private String formatDurationHms(long seconds) {
        long safeSeconds = Math.max(0L, seconds);
        return String.format("%02d:%02d:%02d", safeSeconds / 3600, (safeSeconds % 3600) / 60, safeSeconds % 60);
    }

    @GetMapping("/quiz/{quizId}/attempt")
    public String startQuizPage(@PathVariable Long quizId,
                                @RequestParam(required = false) String sessionId,
                                HttpServletRequest request,
                                HttpServletResponse response,
                                Model model) {
        Long userId = resolveCurrentUserId(request);
        if (userId == null || userRepository.findById(userId).isEmpty()) {
            clearAuthAndRedirectToLogin(response);
            return "redirect:/login?logout=1";
        }
        if (findAccessibleQuiz(quizId, userId).isEmpty()) {
            return renderNotFound(response, model);
        }
        System.out.println("=== PageController.startQuizPage() ===");
        System.out.println("Получен userId из JWT токена: " + userId);
        System.out.println("QuizId: " + quizId);
        System.out.println("SessionId: " + sessionId);
        try {
            // ВАЖНО: StartAttemptRequest принимает (userId, quizId) в таком порядке!
            StartAttemptRequest startAttemptRequest = new StartAttemptRequest(userId, quizId, sessionId);
            System.out.println("Создан StartAttemptRequest: userId=" + startAttemptRequest.userId() + ", quizId=" + startAttemptRequest.quizId() + ", sessionId=" + startAttemptRequest.sessionId());
            AttemptResponse attemptResponse = apiController.startQuizAttempt(startAttemptRequest);
            model.addAttribute("attemptId", attemptResponse.attemptId());
            model.addAttribute("currentQuestion", attemptResponse.currentQuestion());
            model.addAttribute("timeRemaining", attemptResponse.timeRemaining());
            model.addAttribute("questionsRemaining", attemptResponse.questionsRemaining());
            model.addAttribute("totalQuestions", attemptResponse.totalQuestions());
            model.addAttribute("quizName", attemptResponse.quizName());
            model.addAttribute("quizId", attemptResponse.quizId());
            model.addAttribute("defaultTimeLimit", attemptResponse.currentQuestion() != null
                    ? attemptResponse.currentQuestion().timeLimit()
                    : attemptResponse.timeRemaining());
            model.addAttribute("currentQuestionDeadlineEpochMs", attemptResponse.currentQuestionDeadlineEpochMs());
            if (sessionId != null) {
                model.addAttribute("sessionId", sessionId);
            }
            return "quiz-attempt";
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("ATTEMPT_COMPLETED:")) {
                String attemptIdStr = e.getMessage().substring("ATTEMPT_COMPLETED:".length());
                try {
                    Long completedAttemptId = Long.parseLong(attemptIdStr);
                    if (sessionId != null && !sessionId.isEmpty()) {
                        return "redirect:/quiz/attempt/" + completedAttemptId + "/finish";
                    }
                    return "redirect:/quiz/attempt/" + completedAttemptId + "/finish";
                } catch (Exception ignored) {
                    // fallback ниже
                }
            }
            // Если квиз не содержит вопросов или другая ошибка состояния
            if (e.getMessage() != null && e.getMessage().contains("не содержит вопросов")) {
                return "redirect:/quiz/" + quizId + "?error=noQuestions";
            }
            return "redirect:/quiz/" + quizId + "?error=startFailed";
        } catch (Exception e) {
            // Общая ошибка
            return "redirect:/quiz/" + quizId + "?error=startFailed";
        }
    }

    @PostMapping("/quiz/{quizId}/attempt/restart")
    public String restartQuizPage(@PathVariable Long quizId,
                                  HttpServletRequest request,
                                  HttpServletResponse response,
                                  Model model) {
        Long userId = resolveCurrentUserId(request);
        if (userId == null || userRepository.findById(userId).isEmpty()) {
            clearAuthAndRedirectToLogin(response);
            return "redirect:/login?logout=1";
        }
        if (findAccessibleQuiz(quizId, userId).isEmpty()) {
            return renderNotFound(response, model);
        }

        UserQuizAttempt existingAttempt = attemptRepository
                .findTopByUserIdAndQuizIdAndSessionIdIsNullAndIsCompletedFalseOrderByIdDesc(userId, quizId);
        if (existingAttempt != null) {
            apiController.finishQuizAttempt(existingAttempt.getId());
        }

        return "redirect:/quiz/" + quizId + "/attempt";
    }

    @GetMapping("/quiz/attempt/{attemptId}/question")
    public String quizQuestionPage(@PathVariable Long attemptId,
                                   @RequestParam(required = false) String sessionId,
                                   Model model) {
        try {
            QuestionDTO nextQuestion = apiController.getNextQuestion(attemptId);

            if (nextQuestion == null) {
                Long quizId = attemptRepository.findQuizIdByAttemptId(attemptId);
                if (quizId != null) {
                    return "redirect:/quiz/attempt/" + attemptId + "/finish";
                } else {
                    return "redirect:/home";
                }
            }

            Long quizId = attemptRepository.findQuizIdByAttemptId(attemptId);
            if (quizId == null) {
                return "redirect:/home";
            }

            QuizDetailsDTO quiz = quizService.getQuiz(quizId, null);
            String quizName = quiz.name();
            AttemptPageProgress progress = attemptService.getAttemptPageProgress(attemptId);
            
            model.addAttribute("questionsRemaining", progress.questionsRemaining());
            model.addAttribute("totalQuestions", progress.totalQuestions());
            model.addAttribute("timeRemaining", progress.timePerQuestionSeconds());
            model.addAttribute("defaultTimeLimit", progress.timePerQuestionSeconds());
            model.addAttribute("currentQuestionDeadlineEpochMs", progress.currentQuestionDeadlineEpochMs());
            model.addAttribute("attemptId", attemptId);
            model.addAttribute("currentQuestion", nextQuestion);
            model.addAttribute("quizName", quizName);
            model.addAttribute("quizId", quizId);
            if (sessionId != null) {
                model.addAttribute("sessionId", sessionId);
            }
            
            return "quiz-attempt";
            
        } catch (IllegalStateException e) {
            return "redirect:/login";
        } catch (IllegalArgumentException e) {
            return "redirect:/login";
        } catch (Exception e) {
            return "redirect:/login";
        }
    }

    @GetMapping("/multiplayer/session/{sessionId}/results")
    public String multiplayerResultsPage(@PathVariable String sessionId,
                                         HttpServletRequest request,
                                         HttpServletResponse response,
                                         Model model) {
        Long userId = resolveCurrentUserId(request);
        if (userId == null || userRepository.findById(userId).isEmpty()) {
            clearAuthAndRedirectToLogin(response);
            return "redirect:/login?logout=1";
        }

        return attemptRepository.findBySessionIdWithUser(sessionId).stream()
                .filter(attempt -> attempt.getUser() != null && userId.equals(attempt.getUser().getId()))
                .findFirst()
                .map(attempt -> "redirect:/quiz/attempt/" + attempt.getId() + "/finish")
                .orElse("redirect:/home");
    }

    @GetMapping("/quiz/{quizId}/leaderboard")
    public String quizLeaderboard(@PathVariable Long quizId,
                                  HttpServletRequest request,
                                  HttpServletResponse response,
                                  Model model) {
        Long userId = resolveCurrentUserId(request);
        if (userId == null || userRepository.findById(userId).isEmpty()) {
            clearAuthAndRedirectToLogin(response);
            return "redirect:/login?logout=1";
        }
        if (findAccessibleQuiz(quizId, userId).isEmpty()) {
            return renderNotFound(response, model);
        }
        LeaderboardDTO leaderboard = quizService.getQuizLeaderboard(quizId, userId);
        model.addAttribute("leaderboard", leaderboard);
        model.addAttribute("userPosition", leaderboard.userPosition());
        model.addAttribute("quizId", quizId);
        QuizDetailsDTO quiz = quizService.getQuiz(quizId, userId);
        model.addAttribute("quizName", quiz.name());
        UserQuizAttempt activeAttempt = attemptRepository
                .findTopByUserIdAndQuizIdAndSessionIdIsNullAndIsCompletedFalseOrderByIdDesc(userId, quizId);
        model.addAttribute("activeAttemptId", activeAttempt != null ? activeAttempt.getId() : null);
        return "quiz-leaderboard";
    }

}
