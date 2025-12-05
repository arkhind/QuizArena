package org.example.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Утилита для работы с токенами аутентификации.
 */
public class TokenUtil {

    /**
     * Извлекает токен из запроса.
     */
    public static String extractToken(HttpServletRequest request) {
        // Пытаемся получить токен из заголовка Authorization (для API запросов)
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        
        // Пытаемся получить токен из cookie (для страниц)
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("authToken".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        
        // Пытаемся получить токен из параметра запроса (для некоторых случаев)
        String tokenParam = request.getParameter("token");
        if (tokenParam != null) {
            return tokenParam;
        }
        
        return null;
    }

    /**
     * Извлекает userId из токена.
     * Токен имеет формат: "token_" + userId + "_" + timestamp
     * 
     * @param token токен аутентификации
     * @return userId или null, если токен невалидный
     */
    public static Long extractUserId(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        
        // Простая проверка формата токена
        if (!token.startsWith("token_")) {
            return null;
        }
        
        // Извлекаем userId из токена
        String[] parts = token.split("_");
        if (parts.length < 3) {
            return null;
        }
        
        try {
            Long userId = Long.parseLong(parts[1]);
            // Проверяем, что userId валидный
            return userId > 0 ? userId : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Извлекает userId из запроса.
     * 
     * @param request HTTP запрос
     * @return userId или null, если токен не найден или невалидный
     */
    public static Long extractUserIdFromRequest(HttpServletRequest request) {
        String token = extractToken(request);
        return token != null ? extractUserId(token) : null;
    }
}

