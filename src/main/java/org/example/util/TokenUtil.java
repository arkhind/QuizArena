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
        System.out.println("TokenUtil.extractUserId: token=" + (token != null ? token.substring(0, Math.min(50, token.length())) + "..." : "null"));
        
        if (token == null || token.isEmpty()) {
            System.out.println("TokenUtil.extractUserId: токен пустой, возвращаем null");
            return null;
        }
        
        // Простая проверка формата токена
        if (!token.startsWith("token_")) {
            System.out.println("TokenUtil.extractUserId: токен не начинается с 'token_', возвращаем null");
            return null;
        }
        
        // Извлекаем userId из токена
        String[] parts = token.split("_");
        System.out.println("TokenUtil.extractUserId: разбит на части, количество: " + parts.length);
        if (parts.length < 3) {
            System.out.println("TokenUtil.extractUserId: недостаточно частей в токене, возвращаем null");
            return null;
        }
        
        try {
            Long userId = Long.parseLong(parts[1]);
            System.out.println("TokenUtil.extractUserId: извлечен userId=" + userId);
            // Проверяем, что userId валидный
            Long result = userId > 0 ? userId : null;
            System.out.println("TokenUtil.extractUserId: возвращаем userId=" + result);
            return result;
        } catch (NumberFormatException e) {
            System.err.println("TokenUtil.extractUserId: ошибка парсинга userId: " + e.getMessage());
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
        System.out.println("TokenUtil.extractUserIdFromRequest: извлечение токена из запроса");
        String token = extractToken(request);
        System.out.println("TokenUtil.extractUserIdFromRequest: токен извлечен: " + (token != null ? "да" : "нет"));
        Long userId = token != null ? extractUserId(token) : null;
        System.out.println("TokenUtil.extractUserIdFromRequest: итоговый userId=" + userId);
        return userId;
    }
}

