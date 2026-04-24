package org.example.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Утилита для извлечения JWT-токена из HTTP-запроса.
 * Валидация токена и получение userId выполняются через {@link org.example.service.JwtService}.
 */
public final class TokenUtil {

    private TokenUtil() {}

    /**
     * Извлекает токен из запроса: сначала из заголовка Authorization, затем из cookie authToken,
     * затем из query-параметра token.
     */
    public static String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("authToken".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        return request.getParameter("token");
    }
}
