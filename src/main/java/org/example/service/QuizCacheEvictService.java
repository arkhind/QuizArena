package org.example.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Инвалидация кэша квизов (Redis).
 * Нужна, потому что генерация вопросов теперь асинхронная через Kafka.
 */
@Service
public class QuizCacheEvictService {
    private static final String QUIZ_CACHE_KEY = "quiz:%d";

    private final RedisTemplate<String, String> redisTemplate;

    public QuizCacheEvictService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void evictQuizCache(Long quizId) {
        if (quizId == null) return;
        try {
            redisTemplate.delete(String.format(QUIZ_CACHE_KEY, quizId));
        } catch (Exception e) {
            System.err.println("QuizCacheEvictService: ошибка инвалидации кэша квиза: " + e.getMessage());
        }
    }
}

