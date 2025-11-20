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

import java.util.List;

/**
 * Основной интерфейс API для системы управления квизами.
 * Предоставляет методы для аутентификации, управления квизами,
 * прохождения тестов и совместных сессий.
 */
public interface ApiController {

  /**
   * Регистрирует нового пользователя в системе.
   *
   * @param request данные для регистрации, содержащие логин и пароль
   * @return объект аутентификации с ID пользователя, именем и токеном доступа
   * @throws IllegalArgumentException если имя пользователя уже существует или пароль не соответствует требованиям
   * @see RegisterRequest
   * @see AuthResponse
   */
  AuthResponse register(RegisterRequest request);

  /**
   * Выполняет аутентификацию пользователя в системе.
   *
   * @param request учетные данные пользователя (логин и пароль)
   * @return объект аутентификации с токеном доступа и информацией о пользователе
   * @throws SecurityException если учетные данные неверны
   * @see LoginRequest
   * @see AuthResponse
   */
  AuthResponse login(LoginRequest request);

  /**
   * Обновляет профиль пользователя (никнейм и/или пароль).
   *
   * @param request запрос на обновление профиля
   * @return обновленный профиль пользователя
   * @throws IllegalArgumentException если новый никнейм уже занят
   * @throws EntityNotFoundException если пользователь не найден
   * @see UpdateProfileRequest
   * @see UserProfileDTO
   */
  UserProfileDTO updateUserProfile(UpdateProfileRequest request);

  /**
   * Получает профиль пользователя по его идентификатору.
   * Включает основную информацию и статистику активности.
   *
   * @param userId уникальный идентификатор пользователя
   * @return DTO с данными профиля пользователя
   * @throws EntityNotFoundException если пользователь не найден
   * @see UserProfileDTO
   */
  UserProfileDTO getUserProfile(Long userId);

  /**
   * Получает историю прохождений квизов пользователем.
   *
   * @param userId уникальный идентификатор пользователя
   * @return DTO с историей попыток и статистикой
   * @throws EntityNotFoundException если пользователь не найден
   * @see UserHistoryDTO
   */
  UserHistoryDTO getUserHistory(Long userId);

  /**
   * Получает список всех квизов, созданных пользователем.
   * Используется для отображения в разделе профиля пользователя.
   *
   * @param userId уникальный идентификатор пользователя
   * @return список DTO созданных квизов
   * @throws EntityNotFoundException если пользователь не найден
   * @see QuizDTO
   */
  List<QuizDTO> getCreatedQuizzes(Long userId);

  /**
   * Создает новый квиз на основе предоставленных параметров.
   *
   * @param request параметры создаваемого квиза
   * @return DTO созданного квиза с идентификатором и статусом
   * @throws IllegalArgumentException если параметры невалидны
   * @throws FileProcessingException если ошибка обработки материалов
   * @see CreateQuizRequest
   * @see QuizResponseDTO
   */
  QuizResponseDTO createQuiz(CreateQuizRequest request);

  /**
   * Выполняет поиск публичных квизов по заданным критериям.
   * Поддерживает пагинацию и сортировку по различным параметрам.
   *
   * @param request параметры поиска и пагинации
   * @return ответ с результатами поиска и метаданными пагинации
   * @see QuizSearchRequest
   * @see QuizSearchResponse
   */
  QuizSearchResponse searchPublicQuizzes(QuizSearchRequest request);

  /**
   * Получает полную информацию о квизе по его идентификатору.
   * Включает пример вопроса, материалы и настройки квиза.
   *
   * @param quizId уникальный идентификатор квиза
   * @return DTO с детальной информацией о квизе
   * @throws EntityNotFoundException если квиз не найден
   * @throws AccessDeniedException если квиз приватный и у пользователя нет доступа
   * @see QuizDetailsDTO
   */
  QuizDetailsDTO getQuiz(Long quizId);

  /**
   * Удаляет квиз, созданный пользователем.
   * При удалении сбрасывается рейтинг квиза.
   *
   * @param request запрос на удаление с проверкой прав доступа
   * @return true если удаление успешно, false в противном случае
   * @throws EntityNotFoundException если квиз не найден
   * @throws AccessDeniedException если пользователь не является создателем квиза
   * @see DeleteQuizRequest
   */
  boolean deleteQuiz(DeleteQuizRequest request);

  /**
   * Обновляет параметры существующего квиза.
   * При редактировании рейтинг квиза сбрасывается.
   *
   * @param request параметры обновления квиза
   * @return DTO обновленного квиза
   * @throws EntityNotFoundException если квиз не найден
   * @throws AccessDeniedException если пользователь не является создателем
   * @see UpdateQuizRequest
   * @see QuizResponseDTO
   */
  QuizResponseDTO updateQuiz(UpdateQuizRequest request);

  /**
   * Удаляет вопрос из базы вопросов квиза.
   * Используется при редактировании квиза для удаления отдельных вопросов.
   *
   * @param request запрос на удаление вопроса с проверкой прав доступа
   * @return true если удаление успешно, false в противном случае
   * @throws EntityNotFoundException если квиз или вопрос не найдены
   * @throws AccessDeniedException если пользователь не является создателем
   * @see RemoveQuestionRequest
   */
  boolean removeQuestionFromQuiz(RemoveQuestionRequest request);

  /**
   * Получает таблицу лидеров для указанного квиза.
   * Отображает рейтинг участников и позицию текущего пользователя.
   *
   * @param quizId уникальный идентификатор квиза
   * @param userId идентификатор пользователя для определения его позиции
   * @return DTO с таблицей лидеров и позицией пользователя
   * @throws EntityNotFoundException если квиз не найден
   * @see LeaderboardDTO
   */
  LeaderboardDTO getQuizLeaderboard(Long quizId, Long userId);

  /**
   * Начинает новую попытку прохождения квиза.
   * Для статичных квизов используется фиксированный набор вопросов,
   * для обновляемых - генерируется новый набор.
   *
   * @param request запрос на начало попытки
   * @return DTO с информацией о начатой попытке и первым вопросом
   * @throws EntityNotFoundException если квиз или пользователь не найдены
   * @throws QuizNotAvailableException если квиз приватный или недоступен
   * @see StartAttemptRequest
   * @see AttemptResponse
   */
  AttemptResponse startQuizAttempt(StartAttemptRequest request);

  /**
   * Получает следующий вопрос в текущей попытке прохождения.
   * Используется для последовательного прохождения вопросов квиза.
   *
   * @param attemptId уникальный идентификатор попытки
   * @return DTO следующего вопроса или null если вопросы закончились
   * @throws EntityNotFoundException если попытка не найдена
   * @throws IllegalStateException если попытка уже завершена
   * @see QuestionDTO
   */
  QuestionDTO getNextQuestion(Long attemptId);

  /**
   * Отправляет ответ на текущий вопрос в попытке прохождения.
   * Возвращает результат проверки и пояснение.
   *
   * @param request запрос с выбранным ответом
   * @return DTO с результатом проверки и следующим вопросом
   * @throws EntityNotFoundException если попытка или вопрос не найдены
   * @throws IllegalStateException если время на ответ истекло
   * @see SubmitAnswerRequest
   * @see AnswerResponse
   */
  AnswerResponse submitAnswer(SubmitAnswerRequest request);

  /**
   * Завершает текущую попытку прохождения квиза.
   * Рассчитывает итоговый счет и обновляет рейтинг пользователя.
   *
   * @param attemptId уникальный идентификатор попытки
   * @return DTO с результатами прохождения квиза
   * @throws EntityNotFoundException если попытка не найдена
   * @see QuizResultDTO
   */
  QuizResultDTO finishQuizAttempt(Long attemptId);

  /**
   * Создает сессию для совместного прохождения квиза.
   * Генерирует уникальную ссылку для подключения участников.
   *
   * @param request запрос на создание сессии
   * @return DTO созданной сессии с ссылкой для подключения
   * @throws EntityNotFoundException если квиз или пользователь не найдены
   * @see CreateMultiplayerRequest
   * @see MultiplayerSessionDTO
   */
  MultiplayerSessionDTO createMultiplayerSession(CreateMultiplayerRequest request);

  /**
   * Получает информацию о сессии совместного прохождения.
   * Используется для отображения страницы ожидания начала игры.
   *
   * @param sessionId уникальный идентификатор сессии
   * @return DTO с информацией о сессии и списком участников
   * @throws EntityNotFoundException если сессия не найдена
   * @see MultiplayerSessionDTO
   */
  MultiplayerSessionDTO getMultiplayerSession(String sessionId);

  /**
   * Подключает пользователя к сессии совместного прохождения.
   *
   * @param request запрос на подключение к сессии
   * @return true если подключение успешно, false в противном случае
   * @throws EntityNotFoundException если сессия не найдена
   * @throws IllegalStateException если сессия уже начата или отменена
   * @see JoinMultiplayerRequest
   */
  boolean joinMultiplayerSession(JoinMultiplayerRequest request);

  /**
   * Получает список участников сессии совместного прохождения.
   * Используется для обновления списка ожидающих на странице хоста.
   *
   * @param sessionId уникальный идентификатор сессии
   * @return DTO со списком участников сессии
   * @throws EntityNotFoundException если сессия не найдена
   * @see ParticipantsDTO
   */
  ParticipantsDTO getSessionParticipants(String sessionId);

  /**
   * Запускает совместный квиз для всех подключенных участников.
   * Может быть вызван только создателем сессии.
   *
   * @param request запрос на запуск сессии
   * @return true если запуск успешен, false в противном случае
   * @throws EntityNotFoundException если сессия не найдена
   * @throws AccessDeniedException если пользователь не является хостом
   * @throws IllegalStateException если в сессии недостаточно участников
   * @see StartMultiplayerRequest
   */
  boolean startMultiplayerSession(StartMultiplayerRequest request);

  /**
   * Получает результаты совместного прохождения квиза.
   *
   * @param sessionId уникальный идентификатор сессии
   * @return DTO с результатами всех участников
   * @throws EntityNotFoundException если сессия не найдена
   * @throws IllegalStateException если сессия еще не завершена
   * @see MultiplayerResultsDTO
   */
  MultiplayerResultsDTO getMultiplayerResults(String sessionId);

  /**
   * Отменяет сессию совместного прохождения.
   * Выбрасывает всех участников на страницу выбора квиза.
   * Может быть вызван только создателем сессии.
   *
   * @param request запрос на отмену сессии
   * @return true если отмена успешна, false в противном случае
   * @throws EntityNotFoundException если сессия не найдена
   * @throws AccessDeniedException если пользователь не является хостом
   * @see CancelMultiplayerRequest
   */
  boolean cancelMultiplayerSession(CancelMultiplayerRequest request);

  /**
   * Генерирует вопросы для квиза с использованием ИИ.
   * Включает валидацию и удаление дубликатов.
   *
   * @param request параметры генерации вопросов
   * @return DTO с результатами генерации и идентификатором набора вопросов
   * @throws EntityNotFoundException если квиз не найден
   * @throws AIServiceException если ошибка при обращении к сервису ИИ
   * @see QuestionGenerationRequest
   * @see QuestionGenerationResponse
   */
  QuestionGenerationResponse generateQuizQuestions(QuestionGenerationRequest request);

  /**
   * Валидирует сгенерированные вопросы на соответствие требованиям.
   * Проверяет длину текста, количество вариантов ответов и наличие пояснений.
   *
   * @param questionSetId идентификатор набора вопросов
   * @return DTO с результатами валидации и списком ошибок
   * @throws EntityNotFoundException если набор вопросов не найден
   * @see ValidationResponse
   */
  ValidationResponse validateGeneratedQuestions(Long questionSetId);

  /**
   * Удаляет дубликаты вопросов из набора.
   *
   * @param questionSetId идентификатор набора вопросов
   * @return DTO с результатами дедубликации
   * @throws EntityNotFoundException если набор вопросов не найден
   * @see DeduplicationResponse
   */
  DeduplicationResponse removeDuplicateQuestions(Long questionSetId);

  /**
   * Получает сгенерированные вопросы из набора.
   * Используется для предварительного просмотра перед сохранением в квиз.
   *
   * @param questionSetId идентификатор набора вопросов
   * @return DTO с вопросами и метаданными генерации
   * @throws EntityNotFoundException если набор вопросов не найден
   * @see GeneratedQuestionsDTO
   */
  GeneratedQuestionsDTO getGeneratedQuestions(Long questionSetId);
}
