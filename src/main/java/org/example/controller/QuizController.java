package org.example.controller;

import org.example.dto.request.quiz.*;
import org.example.dto.response.quiz.*;
import org.example.service.FileStorageService;
import org.example.service.JwtService;
import org.example.service.QuizService;
import org.example.service.UnethicalPromptException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/quizzes")
public class QuizController {

    private final QuizService quizService;
    private final FileStorageService fileStorageService;
    private final JwtService jwtService;

    @Autowired
    public QuizController(QuizService quizService, FileStorageService fileStorageService, JwtService jwtService) {
        this.quizService = quizService;
        this.fileStorageService = fileStorageService;
        this.jwtService = jwtService;
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<?> createQuiz(@RequestBody CreateQuizRequest request) {
        try {
            if (request.name() == null || request.name().trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Название квиза не может быть пустым");
            }
            if (request.createdBy() == null || request.createdBy() <= 0) {
                return ResponseEntity.badRequest().body("Некорректный ID создателя");
            }
            
            QuizResponseDTO response = quizService.createQuiz(request);
            return ResponseEntity.ok(response);
        } catch (UnethicalPromptException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Данные квиза являются неэтичными");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            // Проверяем, не является ли это ошибкой этичности, обернутой в другое исключение
            Throwable cause = e.getCause();
            while (cause != null) {
                if (cause instanceof UnethicalPromptException) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body("Данные квиза являются неэтичными");
                }
                cause = cause.getCause();
            }

            // Проверяем сообщение об ошибке на наличие упоминания об этичности
            String errorMessage = e.getMessage();
            if (errorMessage != null && (errorMessage.contains("неэтичн") || errorMessage.contains("UNETHICAL_PROMPT") || errorMessage.contains("неэтичными"))) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Данные квиза являются неэтичными");
            }

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Внутренняя ошибка сервера: " + e.getMessage());
        }
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<?> createQuizWithMaterials(
            @RequestParam("name") String name,
            @RequestParam("prompt") String prompt,
            @RequestParam("createdBy") Long createdBy,
            @RequestParam("questionNumber") Integer questionNumber,
            @RequestParam("timeLimit") Integer timeLimit,
            @RequestParam("isPrivate") Boolean isPrivate,
            @RequestParam("isStatic") Boolean isStatic,
            @RequestParam(value = "defaultQuestionType", required = false) org.example.model.QuestionType defaultQuestionType,
            @RequestParam(value = "files", required = false) MultipartFile[] files) {
        try {
            boolean hasFiles = files != null && files.length > 0;
            CreateQuizRequest request = new CreateQuizRequest(
                    name,
                    prompt,
                    createdBy,
                    hasFiles,
                    List.of(),
                    questionNumber,
                    timeLimit,
                    isPrivate,
                    isStatic,
                    defaultQuestionType
            );

            ResponseEntity<?> validationError = validateCreateQuizRequest(request);
            if (validationError != null) {
                return validationError;
            }

            QuizResponseDTO response = quizService.createQuiz(request);
            if (hasFiles) {
                List<String> fileUrls = fileStorageService.saveQuizMaterials(files, response.quizId());
                if (!fileUrls.isEmpty()) {
                    quizService.updateQuizMaterialUrl(response.quizId(), fileUrls.get(0));
                    quizService.regenerateWithMaterialFiles(response.quizId(), fileUrls);
                }
            }
            return ResponseEntity.ok(response);
        } catch (UnethicalPromptException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Данные квиза являются неэтичными");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Ошибка при сохранении файлов: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Внутренняя ошибка сервера: " + e.getMessage());
        }
    }

    private ResponseEntity<?> validateCreateQuizRequest(CreateQuizRequest request) {
        if (request.name() == null || request.name().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Название квиза не может быть пустым");
        }
        if (request.createdBy() == null || request.createdBy() <= 0) {
            return ResponseEntity.badRequest().body("Некорректный ID создателя");
        }
        return null;
    }

    @PostMapping("/{quizId}/materials")
    public ResponseEntity<?> uploadQuizMaterials(
            @PathVariable Long quizId,
            @RequestParam("files") MultipartFile[] files) {
        try {
            if (quizId == null || quizId <= 0) {
                return ResponseEntity.badRequest().body("Некорректный ID квиза");
            }

            if (files == null || files.length == 0) {
                return ResponseEntity.badRequest().body("Файлы не загружены");
            }

            List<String> fileUrls = fileStorageService.saveQuizMaterials(files, quizId);
            
            // Обновляем квиз, устанавливая URL первого файла (или можно сохранить список)
            // Для простоты сохраняем URL первого файла
            if (!fileUrls.isEmpty()) {
                quizService.updateQuizMaterialUrl(quizId, fileUrls.get(0));
            }
            quizService.regenerateWithMaterialFiles(quizId, fileUrls);

            return ResponseEntity.ok(fileUrls);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Ошибка при сохранении файлов: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Внутренняя ошибка сервера");
        }
    }

    @PostMapping("/search")
    public ResponseEntity<QuizSearchResponse> searchPublicQuizzes(@RequestBody QuizSearchRequest request) {
        QuizSearchResponse response = quizService.searchPublicQuizzes(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{quizId}")
    public ResponseEntity<?> getQuiz(@PathVariable Long quizId, HttpServletRequest request) {
        try {
            if (quizId == null || quizId <= 0) {
                return ResponseEntity.badRequest().body("Некорректный ID квиза");
            }
            
            // Извлекаем userId из токена для проверки доступа к приватным квизам
            Long userId = jwtService.extractUserIdFromRequest(request);
            QuizDetailsDTO quiz = quizService.getQuiz(quizId, userId);
            return ResponseEntity.ok(quiz);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Доступ запрещен");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Внутренняя ошибка сервера");
        }
    }

    @GetMapping("/{quizId}/generation-status")
    public ResponseEntity<?> getGenerationStatus(@PathVariable Long quizId, HttpServletRequest request) {
        try {
            if (quizId == null || quizId <= 0) {
                return ResponseEntity.badRequest().body("Некорректный ID квиза");
            }
            Long userId = jwtService.extractUserIdFromRequest(request);
            quizService.getQuiz(quizId, userId);
            return ResponseEntity.ok(quizService.getGenerationStatus(quizId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Доступ запрещен");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Внутренняя ошибка сервера");
        }
    }

    @DeleteMapping("/{quizId}")
    public ResponseEntity<?> deleteQuiz(
            @PathVariable Long quizId,
            @RequestParam Long userId) {
        try {
            if (quizId == null || quizId <= 0) {
                return ResponseEntity.badRequest().body("Некорректный ID квиза");
            }
            if (userId == null || userId <= 0) {
                return ResponseEntity.badRequest().body("Некорректный ID пользователя");
            }
            
            DeleteQuizRequest request = new DeleteQuizRequest(quizId, userId);
            boolean deleted = quizService.deleteQuiz(request);
            return ResponseEntity.ok(deleted);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Только создатель может удалить квиз");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Внутренняя ошибка сервера");
        }
    }

    @PutMapping("/{quizId}")
    public ResponseEntity<?> updateQuiz(
            @PathVariable Long quizId,
            @RequestBody UpdateQuizRequest request) {
        try {
            // Обновляем quizId в запросе, если он не совпадает
            UpdateQuizRequest updatedRequest = new UpdateQuizRequest(
                    quizId,
                    request.userId(),
                    request.name(),
                    null,
                    null,
                    request.timeLimit(),
                    request.isPrivate(),
                    request.isStatic(),
                    null
            );
            QuizResponseDTO response = quizService.updateQuiz(updatedRequest);
            return ResponseEntity.ok(response);
        } catch (UnethicalPromptException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Данные квиза являются неэтичными");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Ошибка при обновлении квиза: " + e.getMessage());
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

    @PostMapping("/{quizId}/copy")
    public ResponseEntity<?> copyQuiz(
            @PathVariable Long quizId,
            @RequestParam Long userId) {
        try {
            if (quizId == null || quizId <= 0) {
                return ResponseEntity.badRequest().body("Некорректный ID квиза");
            }
            if (userId == null || userId <= 0) {
                return ResponseEntity.badRequest().body("Некорректный ID пользователя");
            }
            
            QuizResponseDTO response = quizService.copyQuiz(quizId, userId);
            return ResponseEntity.ok(response);
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Внутренняя ошибка сервера");
        }
    }

    @GetMapping("/{quizId}/by-id")
    public ResponseEntity<?> getQuizById(
            @PathVariable Long quizId,
            @RequestParam(required = false) Long userId) {
        try {
            if (quizId == null || quizId <= 0) {
                return ResponseEntity.badRequest().body("Некорректный ID квиза");
            }
            
            QuizDetailsDTO quiz = quizService.getQuizById(quizId, userId);
            return ResponseEntity.ok(quiz);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Внутренняя ошибка сервера");
        }
    }
}
