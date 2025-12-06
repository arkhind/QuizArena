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
import org.example.service.QuizService;
import org.example.service.ApiService;
import org.example.util.TokenUtil;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

@Controller
public class PageController {
    private final ApiController apiController;
    private final QuizService quizService;
    private final UserQuizAttemptRepository attemptRepository;
    private final ApiService apiService;

    @Autowired
    public PageController(ApiController apiController, QuizService quizService, UserQuizAttemptRepository attemptRepository, ApiService apiService) {
        this.apiController = apiController;
        this.quizService = quizService;
        this.attemptRepository = attemptRepository;
        this.apiService = apiService;
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

    @GetMapping("/home")
    public String home(Model model) {
        QuizSearchRequest request = new QuizSearchRequest("", "popularity", true, 0, 20);
        QuizSearchResponse response = apiService.searchPublicQuizzes(request);
        model.addAttribute("quizzes", response.content());
        model.addAttribute("totalPages", response.totalPages());
        model.addAttribute("currentPage", response.currentPage());
        return "home";
    }

    @GetMapping("/profile")
    public String profile(@RequestParam Long userId, Model model) {
        UserProfileDTO userProfile = apiController.getUserProfile(userId);
        UserHistoryDTO userHistory = apiController.getUserHistory(userId);
        List<QuizDTO> createdQuizzes = apiController.getCreatedQuizzes(userId);
        
        model.addAttribute("userProfile", userProfile);
        model.addAttribute("userHistory", userHistory);
        model.addAttribute("createdQuizzes", createdQuizzes);
        return "profile";
    }

    @GetMapping("/history")
    public String historyPage(@RequestParam Long userId, Model model) {
        try {
            UserHistoryDTO history = apiController.getUserHistory(userId);

            model.addAttribute("attempts", history.attempts());
            model.addAttribute("userId", userId);

            return "history";
        } catch (Exception e) {
            model.addAttribute("attempts", List.of());
            model.addAttribute("userId", userId);
            return "history";
        }
    }

    @GetMapping("/quiz")
    public String quizPage(
        @RequestParam(required = false) Long quizId,
        @RequestParam(required = false) Long userId,
        Model model) {
        
        QuizDTO quiz = null;
        LeaderboardDTO leaderboard = null;
        
        if (quizId != null) {
            QuizDetailsDTO quizDetails = quizService.getQuiz(quizId, userId);
            quiz = new QuizDTO(
                quizDetails.id(),
                quizDetails.name(),
                quizDetails.author(),
                quizDetails.questions() != null ? quizDetails.questions().size() : 0,
                quizDetails.timeLimit(),
                quizDetails.isPublic(),
                quizDetails.isStatic(),
                quizDetails.createdAt()
            );
        }
        
        if (quizId != null && userId != null) {
            try {
                leaderboard = quizService.getQuizLeaderboard(quizId, userId);
            } catch (Exception e) {
            }
        }
        List<QuizDTO> quizzes = quiz != null ? List.of(quiz) : List.of();
        
        model.addAttribute("quizzes", quizzes);
        model.addAttribute("leaderboard", leaderboard);
        model.addAttribute("quizId", quizId);
        model.addAttribute("userId", userId);
        
        return "quiz";
    }

    @GetMapping("/quiz/{quizId}")
    public String quizPageByPath(@PathVariable Long quizId, HttpServletRequest request, Model model,
                                  @RequestParam(required = false) String error) {
        // Извлекаем userId из токена для проверки доступа к приватным квизам
        Long userId = TokenUtil.extractUserIdFromRequest(request);
        
        QuizDTO quiz = null;
        LeaderboardDTO leaderboard = null;
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
                quizDetails.isPublic(),
                quizDetails.isStatic(),
                quizDetails.createdAt()
            );
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
        
        model.addAttribute("quizzes", quizzes);
        model.addAttribute("leaderboard", leaderboard);
        model.addAttribute("quizId", quizId);
        model.addAttribute("userId", userId);
        model.addAttribute("hasQuestions", hasQuestions);
        
        // Обработка ошибок
        if ("noQuestions".equals(error)) {
            model.addAttribute("errorMessage", "Этот квиз не содержит вопросов. Невозможно начать прохождение.");
        } else if ("startFailed".equals(error)) {
            model.addAttribute("errorMessage", "Ошибка при начале квиза. Попробуйте позже.");
        }
        
        return "quiz";
    }

    @GetMapping("/quiz/{quizId}/details")
    public String quizDetails(@PathVariable Long quizId, HttpServletRequest request, Model model) {
        // Извлекаем userId из токена для проверки доступа к приватным квизам
        Long userId = TokenUtil.extractUserIdFromRequest(request);
        QuizDetailsDTO quiz = apiService.getQuiz(quizId, userId);
        model.addAttribute("quiz", quiz);
        return "quiz-details";
    }

    @GetMapping("/my-quizzes")
    public String myQuizzes(@RequestParam Long userId, Model model) {
        java.util.List<QuizDTO> createdQuizzes = apiService.getCreatedQuizzes(userId);
        model.addAttribute("createdQuizzes", createdQuizzes);
        model.addAttribute("userId", userId);
        return "my-quizzes";
    }

    @GetMapping("/quiz/create")
    public String createQuizPage(@RequestParam(required = false) Long userId, Model model) {
        return "create-quiz";
    }

    @GetMapping("/quiz/{quizId}/edit")
    public String editQuizPage(@PathVariable Long quizId, @RequestParam Long userId, Model model) {
        QuizDetailsDTO quiz = apiController.getQuiz(quizId, userId);

        model.addAttribute("quiz", quiz);
        model.addAttribute("quizId", quizId);
        model.addAttribute("userId", userId);
        model.addAttribute("quizName", quiz.name());
        model.addAttribute("description", quiz.description());
        model.addAttribute("questions", quiz.questions());
        model.addAttribute("materials", quiz.materials());
        model.addAttribute("timeLimit", quiz.timeLimit());
        model.addAttribute("isPublic", quiz.isPublic());
        model.addAttribute("isStatic", quiz.isStatic());

        return "edit-quiz";
    }

    @GetMapping("/multiplayer/session/{sessionId}")
    public String multiplayerSessionPage(@PathVariable String sessionId, @RequestParam Long userId, Model model) {
        MultiplayerSessionDTO session = apiController.getMultiplayerSession(sessionId);
        model.addAttribute("session", session);
        model.addAttribute("sessionId", sessionId);
        model.addAttribute("hostUserId", userId);
        model.addAttribute("quizName", session.quizName());
        model.addAttribute("participants", session.participants());
        model.addAttribute("status", session.status());
        model.addAttribute("joinLink", session.joinLink());
        
        return "multiplayer-session";
    }

    @GetMapping("/quiz/attempt/{attemptId}/finish")
    public String finishQuizPage(@PathVariable Long attemptId,
                                 @RequestParam Long quizId,
                                 Model model) {
        QuizResultDTO result = apiController.finishQuizAttempt(attemptId);
        
        String quizName = "Квиз";
        try {
            QuizDetailsDTO quiz = quizService.getQuiz(quizId, null);
            quizName = quiz.name();
        } catch (Exception e) {
            quizName = "Квиз";
        }
        
        model.addAttribute("score", result.score());
        model.addAttribute("correctAnswers", result.correctAnswers());
        model.addAttribute("totalQuestions", result.totalQuestions());
        model.addAttribute("position", result.position());
        model.addAttribute("quizId", quizId);
        model.addAttribute("quizName", quizName);
        return "quiz-results";
    }

    @GetMapping("/quiz/{quizId}/attempt")
    public String startQuizPage(@PathVariable Long quizId,
                                @RequestParam Long userId,
                                Model model) {
        System.out.println("=== PageController.startQuizPage() ===");
        System.out.println("Получен userId из URL параметра: " + userId);
        System.out.println("QuizId: " + quizId);
        try {
            // ВАЖНО: StartAttemptRequest принимает (userId, quizId) в таком порядке!
            StartAttemptRequest request = new StartAttemptRequest(userId, quizId);
            System.out.println("Создан StartAttemptRequest: userId=" + request.userId() + ", quizId=" + request.quizId());
            AttemptResponse response = apiController.startQuizAttempt(request);
            model.addAttribute("attemptId", response.attemptId());
            model.addAttribute("currentQuestion", response.currentQuestion());
            model.addAttribute("timeRemaining", response.timeRemaining());
            model.addAttribute("questionsRemaining", response.questionsRemaining());
            model.addAttribute("quizName", response.quizName());
            model.addAttribute("quizId", response.quizId());
            model.addAttribute("defaultTimeLimit", response.timeRemaining());
            return "quiz-attempt";
        } catch (IllegalStateException e) {
            // Если квиз не содержит вопросов или другая ошибка состояния
            if (e.getMessage() != null && e.getMessage().contains("не содержит вопросов")) {
                model.addAttribute("errorMessage", "Этот квиз не содержит вопросов. Невозможно начать прохождение.");
                model.addAttribute("quizId", quizId);
                model.addAttribute("userId", userId);
                return "redirect:/quiz/" + quizId + "?error=noQuestions";
            }
            throw e;
        } catch (Exception e) {
            model.addAttribute("errorMessage", "Ошибка при начале квиза: " + e.getMessage());
            model.addAttribute("quizId", quizId);
            model.addAttribute("userId", userId);
            return "redirect:/quiz/" + quizId + "?error=startFailed";
        }
    }

    @GetMapping("/quiz/attempt/{attemptId}/question")
    public String quizQuestionPage(@PathVariable Long attemptId, Model model) {
        try {
            QuestionDTO nextQuestion = apiController.getNextQuestion(attemptId);

            if (nextQuestion == null) {
                Long quizId = attemptRepository.findQuizIdByAttemptId(attemptId);
                if (quizId != null) {
                    return "redirect:/quiz/attempt/" + attemptId + "/finish?quizId=" + quizId;
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
            Integer timeLimit = quiz.timeLimit() != null ? quiz.timeLimit() : 60;
            Integer questionsRemaining = 10;
            
            model.addAttribute("attemptId", attemptId);
            model.addAttribute("currentQuestion", nextQuestion);
            model.addAttribute("quizName", quizName);
            model.addAttribute("quizId", quizId);
            model.addAttribute("timeRemaining", timeLimit);
            model.addAttribute("defaultTimeLimit", timeLimit);
            model.addAttribute("questionsRemaining", questionsRemaining);
            
            return "quiz-attempt";
            
        } catch (IllegalArgumentException e) {
            return "redirect:/home";
        }
    }

    @GetMapping("/multiplayer/session/{sessionId}/results")
    public String multiplayerResultsPage(@PathVariable String sessionId,
                                         @RequestParam Long userId,
                                         Model model) {
        MultiplayerResultsDTO results = apiController.getMultiplayerResults(sessionId);
        model.addAttribute("results", results);
        model.addAttribute("sessionId", sessionId);
        model.addAttribute("quizName", results.quizName());
        return "multiplayer-results";
    }

    @GetMapping("/quiz/{quizId}/leaderboard")
    public String quizLeaderboard(@PathVariable Long quizId,
                                  @RequestParam Long userId,
                                  Model model) {
        LeaderboardDTO leaderboard = quizService.getQuizLeaderboard(quizId, userId);
        model.addAttribute("leaderboard", leaderboard);
        model.addAttribute("userPosition", leaderboard.userPosition());
        model.addAttribute("quizId", quizId);
        QuizDetailsDTO quiz = quizService.getQuiz(quizId, userId);
        model.addAttribute("quizName", quiz.name());
        return "quiz-leaderboard";
    }

}
