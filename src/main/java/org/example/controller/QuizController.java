package org.example.controller;

import org.example.dto.request.quiz.*;
import org.example.dto.response.quiz.*;
import org.example.service.QuizService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/quizzes")
public class QuizController {

    private final QuizService quizService;

    @Autowired
    public QuizController(QuizService quizService) {
        this.quizService = quizService;
    }

    @PostMapping
    public ResponseEntity<QuizResponseDTO> createQuiz(@RequestBody CreateQuizRequest request) {
        try {
            QuizResponseDTO response = quizService.createQuiz(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/search")
    public ResponseEntity<QuizSearchResponse> searchPublicQuizzes(@RequestBody QuizSearchRequest request) {
        QuizSearchResponse response = quizService.searchPublicQuizzes(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{quizId}")
    public ResponseEntity<QuizDetailsDTO> getQuiz(@PathVariable Long quizId) {
        try {
            QuizDetailsDTO quiz = quizService.getQuiz(quizId);
            return ResponseEntity.ok(quiz);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @DeleteMapping("/{quizId}")
    public ResponseEntity<Boolean> deleteQuiz(
            @PathVariable Long quizId,
            @RequestParam Long userId) {
        try {
            DeleteQuizRequest request = new DeleteQuizRequest(quizId, userId);
            boolean deleted = quizService.deleteQuiz(request);
            return ResponseEntity.ok(deleted);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @PutMapping("/{quizId}")
    public ResponseEntity<QuizResponseDTO> updateQuiz(
            @PathVariable Long quizId,
            @RequestBody UpdateQuizRequest request) {
        try {
            // Обновляем quizId в запросе, если он не совпадает
            UpdateQuizRequest updatedRequest = new UpdateQuizRequest(
                    quizId,
                    request.userId(),
                    request.name(),
                    request.prompt()
            );
            QuizResponseDTO response = quizService.updateQuiz(updatedRequest);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @DeleteMapping("/{quizId}/questions/{questionId}")
    public ResponseEntity<Boolean> removeQuestion(
            @PathVariable Long quizId,
            @PathVariable Long questionId,
            @RequestParam Long userId) {
        try {
            RemoveQuestionRequest request = new RemoveQuestionRequest(quizId, questionId, userId);
            boolean removed = quizService.removeQuestionFromQuiz(request);
            return ResponseEntity.ok(removed);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @GetMapping("/{quizId}/leaderboard")
    public ResponseEntity<LeaderboardDTO> getQuizLeaderboard(
            @PathVariable Long quizId,
            @RequestParam Long userId) {
        try {
            LeaderboardDTO leaderboard = quizService.getQuizLeaderboard(quizId, userId);
            return ResponseEntity.ok(leaderboard);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}

