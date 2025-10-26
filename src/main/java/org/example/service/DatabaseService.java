package org.example.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.example.model.User;
import org.example.model.Quiz;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
        String sql = "SELECT id, login, password FROM \"User\"";
        
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
        String sql = "SELECT id, name, prompt, create_by, has_material, material_url, question_number, " +
                    "time, is_private, is_static, private_code, created_at FROM \"Quiz\"";
        
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            
            while (resultSet.next()) {
                Quiz quiz = new Quiz();
                quiz.setId(resultSet.getLong("id"));
                quiz.setName(resultSet.getString("name"));
                quiz.setPrompt(resultSet.getString("prompt"));
                quiz.setCreateBy(resultSet.getLong("create_by"));
                quiz.setHasMaterial(resultSet.getBoolean("has_material"));
                quiz.setMaterialUrl(resultSet.getString("material_url"));
                quiz.setQuestionNumber(resultSet.getLong("question_number"));
                quiz.setTime(resultSet.getLong("time"));
                quiz.setIsPrivate(resultSet.getBoolean("is_private"));
                quiz.setIsStatic(resultSet.getBoolean("is_static"));
                quiz.setPrivateCode(resultSet.getString("private_code"));
                quiz.setCreatedAt(resultSet.getTimestamp("created_at").toLocalDateTime());
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
