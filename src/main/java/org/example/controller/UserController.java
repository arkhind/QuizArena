package org.example.controller;

import org.example.dto.request.auth.UpdateProfileRequest;
import org.example.dto.response.auth.UserProfileDTO;
import org.example.dto.response.history.UserHistoryDTO;
import org.example.dto.response.quiz.QuizDTO;
import org.example.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    @Autowired
    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{userId}/profile")
    public ResponseEntity<UserProfileDTO> getUserProfile(@PathVariable Long userId) {
        try {
            UserProfileDTO profile = userService.getUserProfile(userId);
            return ResponseEntity.ok(profile);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/profile")
    public ResponseEntity<UserProfileDTO> updateUserProfile(@RequestBody UpdateProfileRequest request) {
        try {
            UserProfileDTO profile = userService.updateUserProfile(request);
            return ResponseEntity.ok(profile);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/{userId}/history")
    public ResponseEntity<UserHistoryDTO> getUserHistory(@PathVariable Long userId) {
        try {
            UserHistoryDTO history = userService.getUserHistory(userId);
            return ResponseEntity.ok(history);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{userId}/quizzes")
    public ResponseEntity<List<QuizDTO>> getCreatedQuizzes(@PathVariable Long userId) {
        try {
            List<QuizDTO> quizzes = userService.getCreatedQuizzes(userId);
            return ResponseEntity.ok(quizzes);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
