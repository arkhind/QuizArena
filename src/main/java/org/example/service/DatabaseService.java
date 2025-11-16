package org.example.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.example.model.User;
import org.example.model.Quiz;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
                    "q.time_per_question_seconds, q.is_private, q.is_static, q.created_at, " +
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

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
