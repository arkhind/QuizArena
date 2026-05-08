package org.example.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.service.JwtService;
import org.example.util.TokenUtil;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Interceptor для проверки авторизации пользователя.
 * Защищает страницы, требующие авторизации.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtService jwtService;

    public AuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

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

        if (isPublicPath(path)) {
            return true;
        }

        if ("OPTIONS".equals(method)) {
            return true;
        }

        if (path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/") ||
            path.startsWith("/static/") || path.endsWith(".css") || path.endsWith(".js") ||
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".ico")) {
            return true;
        }

        if (requiresAuth(path)) {
            String token = TokenUtil.extractToken(request);

            if (token == null || !jwtService.isValid(token)) {
                if (path.startsWith("/api/")) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Требуется авторизация\"}");
                    return false;
                }
                response.sendRedirect("/login?next=" + URLEncoder.encode(getRequestTarget(request), StandardCharsets.UTF_8));
                return false;
            }
        }

        return true;
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    private boolean requiresAuth(String path) {
        if (path.startsWith("/api/") && !path.startsWith("/api/auth")) {
            return true;
        }
        return PROTECTED_PATHS.stream().anyMatch(path::startsWith);
    }

    private String getRequestTarget(HttpServletRequest request) {
        String target = request.getRequestURI();
        String queryString = request.getQueryString();
        if (queryString != null && !queryString.isBlank()) {
            target += "?" + queryString;
        }
        return target;
    }
}
