package org.example.service;

import org.example.dto.request.auth.LoginRequest;
import org.example.dto.request.auth.RegisterRequest;
import org.example.dto.response.auth.AuthResponse;
import org.example.model.User;
import org.example.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сервис для аутентификации и регистрации пользователей.
 */
@Service
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByLogin(request.username())) {
            throw new IllegalArgumentException("Пользователь с таким логином уже существует");
        }

        User user = new User();
        user.setLogin(request.username());
        // TODO: Хеширование пароля с помощью BCrypt
        user.setPassword(passwordEncoder.encode(request.password()));
        user = userRepository.save(user);

        // TODO: Генерация JWT токена
        String token = "token_" + user.getId() + "_" + System.currentTimeMillis();

        return new AuthResponse(user.getId(), user.getLogin(), token);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByLogin(request.username())
                .orElseThrow(() -> new SecurityException("Неверный логин или пароль"));

        // TODO: Проверка хеша пароля
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new SecurityException("Неверный логин или пароль");
        }

        // TODO: Генерация JWT токена
        String token = "token_" + user.getId() + "_" + System.currentTimeMillis();

        return new AuthResponse(user.getId(), user.getLogin(), token);
    }
}

