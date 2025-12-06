package org.example.service;

import org.example.dto.request.auth.LoginRequest;
import org.example.dto.request.auth.RegisterRequest;
import org.example.dto.response.auth.AuthResponse;
import org.example.model.User;
import org.example.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Сервис для аутентификации и регистрации пользователей.
 */
@Service
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    public AuthResponse register(RegisterRequest request) {
        System.out.println("=== AuthService.register() ===");
        System.out.println("Username: " + request.username());
        
        // Нормализуем логин (убираем пробелы)
        String login = request.username().trim();
        System.out.println("Нормализованный логин: '" + login + "'");
        
        // Выводим всех пользователей для отладки
        try {
            List<User> allUsers = userRepository.findAll();
            System.out.println("Всего пользователей в БД: " + allUsers.size());
            for (User u : allUsers) {
                System.out.println("  Пользователь ID=" + u.getId() + ", login='" + u.getLogin() + "'");
            }
        } catch (Exception e) {
            System.err.println("Не удалось получить список пользователей: " + e.getMessage());
        }
        
        // Проверяем существование пользователя
        boolean exists = userRepository.existsByLogin(login);
        System.out.println("Пользователь с логином '" + login + "' существует: " + exists);
        
        if (exists) {
            System.err.println("ОШИБКА: Пользователь с логином '" + login + "' уже существует");
            throw new IllegalArgumentException("Пользователь с таким логином уже существует");
        }

        User user = new User();
        user.setLogin(login);
        // TODO: Хеширование пароля с помощью BCrypt
        user.setPassword(passwordEncoder.encode(request.password()));
        
        System.out.println("Сохранение пользователя в БД...");
        user = userRepository.save(user);
        System.out.println("Пользователь сохранен, ID: " + user.getId() + ", login: " + user.getLogin());
        
        // Проверяем, что пользователь действительно сохранен в БД
        Optional<User> savedUser = userRepository.findById(user.getId());
        if (savedUser.isEmpty()) {
            System.err.println("КРИТИЧЕСКАЯ ОШИБКА: Пользователь не найден в БД после сохранения! ID: " + user.getId());
            throw new IllegalStateException("Ошибка при сохранении пользователя");
        }
        System.out.println("Проверка: пользователь найден в БД, ID: " + savedUser.get().getId() + ", login: " + savedUser.get().getLogin());

        // TODO: Генерация JWT токена
        String token = "token_" + user.getId() + "_" + System.currentTimeMillis();

        System.out.println("Возвращаем AuthResponse: userId=" + user.getId() + ", username=" + user.getLogin());
        return new AuthResponse(user.getId(), user.getLogin(), token);
    }

    public AuthResponse login(LoginRequest request) {
        System.out.println("=== AuthService.login() ===");
        System.out.println("Username: " + request.username());
        
        // Выводим всех пользователей для отладки
        try {
            List<User> allUsers = userRepository.findAll();
            System.out.println("Всего пользователей в БД: " + allUsers.size());
            for (User u : allUsers) {
                System.out.println("  Пользователь ID=" + u.getId() + ", login='" + u.getLogin() + "'");
            }
        } catch (Exception e) {
            System.err.println("Не удалось получить список пользователей: " + e.getMessage());
        }
        
        // Ищем пользователя по логину (с учетом регистра)
        String login = request.username().trim();
        System.out.println("Поиск пользователя с логином: '" + login + "'");
        
        User user = userRepository.findByLogin(login)
                .orElseThrow(() -> {
                    System.err.println("ОШИБКА: Пользователь с логином '" + login + "' не найден");
                    System.err.println("Попробуйте проверить регистр и пробелы в логине");
                    return new SecurityException("Неверный логин или пароль");
                });
        
        System.out.println("Пользователь найден в БД: ID=" + user.getId() + ", login='" + user.getLogin() + "'");

        // Проверка хеша пароля
        System.out.println("Проверка пароля для пользователя ID=" + user.getId());
        System.out.println("Хеш пароля в БД: " + (user.getPassword() != null ? user.getPassword().substring(0, Math.min(20, user.getPassword().length())) + "..." : "null"));
        
        boolean passwordMatches = passwordEncoder.matches(request.password(), user.getPassword());
        System.out.println("Пароль совпадает: " + passwordMatches);
        
        if (!passwordMatches) {
            System.err.println("ОШИБКА: Неверный пароль для пользователя ID=" + user.getId() + ", login='" + user.getLogin() + "'");
            throw new SecurityException("Неверный логин или пароль");
        }
        
        System.out.println("Пароль верный, продолжаем логин");

        // TODO: Генерация JWT токена
        String token = "token_" + user.getId() + "_" + System.currentTimeMillis();

        System.out.println("Возвращаем AuthResponse: userId=" + user.getId() + ", username=" + user.getLogin());
        return new AuthResponse(user.getId(), user.getLogin(), token);
    }
}

