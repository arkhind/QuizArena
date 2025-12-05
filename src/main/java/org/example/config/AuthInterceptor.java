package org.example.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.List;

/**
 * Interceptor для проверки авторизации пользователя.
 * Защищает страницы, требующие авторизации.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    // Список путей, которые требуют авторизации
    private static final List<String> PROTECTED_PATHS = Arrays.asList(
            "/home",
            "/profile",
            "/quiz",
            "/my-quizzes",
            "/quiz/create",
            "/quiz/",
            "/attempt/",
            "/multiplayer/"
    );

    // Пути, которые не требуют авторизации
    private static final List<String> PUBLIC_PATHS = Arrays.asList(
            "/login",
            "/register",
            "/api/auth/login",
            "/api/auth/register",
            "/error"
    );

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) throws Exception {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // Пропускаем публичные пути
        if (isPublicPath(path)) {
            return true;
        }

        // Пропускаем OPTIONS запросы (для CORS)
        if ("OPTIONS".equals(method)) {
            return true;
        }

        // Пропускаем статические ресурсы
        if (path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/") || 
            path.startsWith("/static/") || path.endsWith(".css") || path.endsWith(".js") || 
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".ico")) {
            return true;
        }

        // Проверяем, требуется ли авторизация для этого пути
        if (requiresAuth(path)) {
            String token = extractToken(request);
            
            if (token == null || !isValidToken(token)) {
                // Если это API запрос, возвращаем 401
                if (path.startsWith("/api/")) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Требуется авторизация\"}");
                    return false;
                }
                // Если это страница, перенаправляем на логин
                response.sendRedirect("/login");
                return false;
            }
        }

        return true;
    }

    /**
     * Проверяет, является ли путь публичным.
     */
    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    /**
     * Проверяет, требуется ли авторизация для пути.
     */
    private boolean requiresAuth(String path) {
        // API endpoints требуют авторизации (кроме /api/auth)
        if (path.startsWith("/api/") && !path.startsWith("/api/auth")) {
            return true;
        }
        
        // Проверяем защищенные пути
        return PROTECTED_PATHS.stream().anyMatch(path::startsWith);
    }

    /**
     * Извлекает токен из запроса.
     */
    private String extractToken(HttpServletRequest request) {
        // Пытаемся получить токен из заголовка Authorization (для API запросов)
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        
        // Пытаемся получить токен из cookie (для страниц)
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (jakarta.servlet.http.Cookie cookie : cookies) {
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
     * Проверяет валидность токена.
     * В текущей реализации токен имеет формат: "token_" + userId + "_" + timestamp
     */
    private boolean isValidToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        
        // Простая проверка формата токена
        if (!token.startsWith("token_")) {
            return false;
        }
        
        // Извлекаем userId из токена
        String[] parts = token.split("_");
        if (parts.length < 3) {
            return false;
        }
        
        try {
            Long userId = Long.parseLong(parts[1]);
            // Проверяем, что userId валидный
            return userId > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}

