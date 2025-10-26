package org.example.controller;

import java.util.List;

public interface ApiController {

  int register(String username, String password);
  int login(String username, String password);
  int updateUserProfile(Long userId, String newUsername, String newPassword);
  String getUserHistory(Long userId);
  String getUserCreatedQuizzes(Long userId);

  int createQuiz(Long id, String name, String prompt, Long createBy, Boolean hasMaterial,
                 String materialUrl, Long questionNumber, Long time, Boolean isPrivate,
                 Boolean isStatic);
  String searchPublicQuizzes(String searchQuery, String sortBy, Boolean ascending);
  String getQuizDetails(Long quizId);
  int startQuizAttempt(Long userId, Long quizId);
  String getNextQuestion(Long attemptId);
  int submitAnswer(Long attemptId, Long questionId, Long selectedAnswerId);
  String finishQuizAttempt(Long attemptId);
  int deleteQuiz(Long quizId, Long userId);
  int updateQuiz(Long quizId, Long userId, String name, String prompt);
  int removeQuestionFromQuiz(Long quizId, Long questionId, Long userId);
  String getQuizLeaderboard(Long quizId, Long userId);

  String createMultiplayerQuiz(Long userId, Long quizId);
  String getMultiplayerQuiz(String quizId);
  int joinMultiplayerQuiz(Long userId, String quizId);
  String getQuizParticipants(Long quizId);
  int startMultiplayerQuiz(Long quizId, Long hostUserId);
  String getMultiplayerResults(Long quizId);
  int cancelMultiplayerQuiz(Long quizId, Long hostUserId);

  int generateQuizQuestions(Long quizId, String prompt, String materialUrl,
                            Long questionNumber, int questionCount);
  int validateGeneratedQuestions(Long questionSetId);
  int removeDuplicateQuestions(Long questionSetId);
  String getGeneratedQuestions(Long questionSetId);
}