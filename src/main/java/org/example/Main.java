package org.example;

import org.example.service.DatabaseService;
import org.example.model.User;
import org.example.model.Quiz;

public class Main {
    public static void main(String[] args) {
        System.out.println("QuizArena - Starting database connection test...");
        
        DatabaseService dbService = new DatabaseService();
        
        // Test connection
        if (dbService.testConnection()) {
            System.out.println("✅ Database connection successful!");
            
            // Test queries
            System.out.println("\n--- Users ---");
            var users = dbService.getAllUsers();
            System.out.println("Found " + users.size() + " users");
            users.forEach(user -> System.out.println("User: " + user.getLogin()));
            
            System.out.println("\n--- Quizzes ---");
            var quizzes = dbService.getAllQuizzes();
            System.out.println("Found " + quizzes.size() + " quizzes");
            quizzes.forEach(quiz -> System.out.println("Quiz: " + quiz.getName()));
            
        } else {
            System.err.println("❌ Database connection failed!");
        }
        
        dbService.close();
        System.out.println("\nDatabase connection closed.");
    }
}