package org.example.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.example.model.User;
import org.example.model.Quiz;
import org.example.model.Question;
import org.example.model.AnswerOption;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class DatabaseService {
    private HikariDataSource dataSource;

    public DatabaseService() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://localhost:5432/quizarena");
        config.setUsername("quizuser");
        config.setPassword("quizpass");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(5);

        
        this.dataSource = new HikariDataSource(config);
    }

    public boolean testConnection() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(5);
        } catch (SQLException e) {
            System.err.println("Connection test failed: " + e.getMessage());
            return false;
        }
    }

    public List<User> getAllUsers() {
        List<User> users = new ArrayList<>();
        String sql = "SELECT id, login, password FROM users";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            
            while (resultSet.next()) {
                User user = new User();
                user.setId(resultSet.getLong("id"));
                user.setLogin(resultSet.getString("login"));
                user.setPassword(resultSet.getString("password"));
                users.add(user);
            }
        } catch (SQLException e) {
            System.err.println("Error fetching users: " + e.getMessage());
        }
        
        return users;
    }

    public List<Quiz> getAllQuizzes() {
        List<Quiz> quizzes = new ArrayList<>();
        String sql = "SELECT q.id, q.name, q.prompt, q.created_by, q.has_material, q.material_url, " +
                    "q.question_number, q.time_per_question_seconds, q.is_private, q.is_static, q.created_at, " +
                    "u.id as user_id, u.login " +
                    "FROM quizzes q " +
                    "LEFT JOIN users u ON q.created_by = u.id";
        
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            
            while (resultSet.next()) {
                Quiz quiz = new Quiz();
                quiz.setId(resultSet.getLong("id"));
                quiz.setName(resultSet.getString("name"));
                quiz.setPrompt(resultSet.getString("prompt"));
                User creator = new User();
                creator.setId(resultSet.getLong("user_id"));
                creator.setLogin(resultSet.getString("login"));
                quiz.setCreatedBy(creator);
                quiz.setHasMaterial(resultSet.getBoolean("has_material"));
                quiz.setMaterialUrl(resultSet.getString("material_url"));
                quiz.setQuestionNumber(resultSet.getObject("question_number", Integer.class));

                Integer seconds = resultSet.getObject("time_per_question_seconds", Integer.class);
                quiz.setTimePerQuestion(seconds != null ? Duration.ofSeconds(seconds) : null);
                
                quiz.setPrivate(resultSet.getBoolean("is_private"));
                quiz.setStatic(resultSet.getBoolean("is_static"));
                
                OffsetDateTime odt = resultSet.getObject("created_at", OffsetDateTime.class);
                quiz.setCreatedAt(odt != null ? odt.toInstant() : null);
                
                quizzes.add(quiz);
            }
        } catch (SQLException e) {
            System.err.println("Error fetching quizzes: " + e.getMessage());
        }
        
        return quizzes;
    }

    public Long saveQuestion(Question question) throws SQLException {
        Long nextId = getNextQuestionId();
        String sql = "INSERT INTO \"Question\" (id, quiz_id, text, type, explanation, image_url) VALUES (?, ?, ?, ?, ?, ?) RETURNING id";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, nextId);
            statement.setLong(2, question.getQuizId());
            statement.setString(3, question.getText());
            statement.setString(4, question.getType());
            statement.setString(5, question.getExplanation());
            statement.setString(6, question.getImageUrl());
            
            ResultSet rs = statement.executeQuery();
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return null;
    }

    private Long getNextQuestionId() throws SQLException {
        String sql = "SELECT COALESCE(MAX(id), 0) + 1 FROM \"Question\"";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 1L;
    }

    public void saveAnswerOption(AnswerOption option) throws SQLException {
        Long nextId = getNextAnswerOptionId();
        String sql = "INSERT INTO \"AnswerOption\" (id, question_id, text, is_correct, is_na_option) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, nextId);
            statement.setLong(2, option.getQuestionId());
            statement.setString(3, option.getText());
            statement.setBoolean(4, option.getIsCorrect());
            statement.setBoolean(5, option.getIsNaOption());
            statement.executeUpdate();
        }
    }

    private Long getNextAnswerOptionId() throws SQLException {
        String sql = "SELECT COALESCE(MAX(id), 0) + 1 FROM \"AnswerOption\"";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 1L;
    }

    public boolean userExists(Long userId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM \"User\" WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            ResultSet rs = statement.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        }
        return false;
    }

    public Long createUser(Long userId, String login, String password) throws SQLException {
        String sql = "INSERT INTO \"User\" (id, login, password) VALUES (?, ?, ?) RETURNING id";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, login);
            statement.setString(3, password);
            ResultSet rs = statement.executeQuery();
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return null;
    }

    public Long getOrCreateUser(Long userId) throws SQLException {
        if (userExists(userId)) {
            return userId;
        }
        return createUser(userId, "user" + userId, "password");
    }

    public boolean quizExists(Long quizId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM \"Quiz\" WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, quizId);
            ResultSet rs = statement.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        }
        return false;
    }

    public Long createQuiz(Long quizId, String name, String prompt, Long createBy, int questionNumber) throws SQLException {
        String sql = "INSERT INTO \"Quiz\" (id, name, prompt, create_by, has_material, material_url, question_number, " +
                    "time, is_private, is_static, private_code, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, quizId);
            statement.setString(2, name);
            statement.setString(3, prompt);
            statement.setLong(4, createBy);
            statement.setBoolean(5, false);
            statement.setString(6, "");
            statement.setLong(7, questionNumber);
            statement.setLong(8, 0L);
            statement.setBoolean(9, false);
            statement.setBoolean(10, false);
            statement.setString(11, "");
            statement.setTimestamp(12, java.sql.Timestamp.valueOf(LocalDateTime.now()));
            
            ResultSet rs = statement.executeQuery();
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return null;
    }

    public Long getOrCreateQuiz(Long quizId, String name, String prompt, Long createBy, int questionNumber) throws SQLException {
        getOrCreateUser(createBy);
        if (quizExists(quizId)) {
            return quizId;
        }
        return createQuiz(quizId, name, prompt, createBy, questionNumber);
    }

    public void saveQuestionsWithAnswers(List<QuestionParser.ParsedQuestion> parsedQuestions, Long quizId) throws SQLException {
        for (QuestionParser.ParsedQuestion pq : parsedQuestions) {
            pq.question.setQuizId(quizId);
            Long questionId = saveQuestion(pq.question);
            if (questionId != null) {
                for (AnswerOption option : pq.answerOptions) {
                    option.setQuestionId(questionId);
                    saveAnswerOption(option);
                }
            }
        }
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}

