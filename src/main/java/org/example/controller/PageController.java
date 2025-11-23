package org.example.controller;

import org.example.dto.request.quiz.QuizSearchRequest;
import org.example.dto.response.quiz.QuizDTO;
import org.example.dto.response.quiz.QuizDetailsDTO;
import org.example.dto.response.quiz.QuizSearchResponse;
import org.example.dto.response.auth.UserProfileDTO;
import org.example.dto.response.history.UserHistoryDTO;
import org.example.service.ApiService;
import org.example.util.TokenUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.servlet.http.HttpServletRequest;

@Controller
public class PageController {

    private final ApiService apiService;
    
    @Autowired
    public PageController(ApiService apiService) {
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
        UserProfileDTO userProfile = apiService.getUserProfile(userId);
        UserHistoryDTO userHistory = apiService.getUserHistory(userId);
        java.util.List<QuizDTO> createdQuizzes = apiService.getCreatedQuizzes(userId);
        
        model.addAttribute("userProfile", userProfile);
        model.addAttribute("userHistory", userHistory);
        model.addAttribute("createdQuizzes", createdQuizzes);
        return "profile";
    }

    @GetMapping("/quiz")
    public String quizList(@RequestParam(required = false, defaultValue = "") String search, Model model) {
        QuizSearchRequest request = new QuizSearchRequest(search, "name", true, 0, 20);
        QuizSearchResponse response = apiService.searchPublicQuizzes(request);
        model.addAttribute("quizzes", response.content());
        model.addAttribute("search", search);
        return "quiz";
    }

    @GetMapping("/quiz/{quizId}")
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
        return "my-quizzes";
    }

    @GetMapping("/quiz/create")
    public String createQuizPage(@RequestParam(required = false) Long userId, Model model) {
        return "create-quiz";
    }
}
