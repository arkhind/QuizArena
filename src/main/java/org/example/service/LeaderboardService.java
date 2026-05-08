package org.example.service;

import org.example.dto.common.LeaderboardEntry;
import org.example.dto.response.quiz.LeaderboardDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Управляет лидербордом в Redis через Sorted Set.
 *
 * Ключи:
 *   leaderboard:{quizId}        — ZSET, member=userId, score=compositeScore
 *   leaderboard:users:{quizId}  — Hash, field=userId, value="login:score:time"
 *
 * compositeScore = userScore * 1_000_000 - timeSpentSeconds
 * (выше = лучше: сначала очки, при равенстве — меньше время)
 */
@Service
public class LeaderboardService {

    private static final String ZSET_KEY = "leaderboard:v3:%d";
    private static final String USERS_KEY = "leaderboard:v3:users:%d";

    public record CachedLeaderboardEntry(Long userId, String login, long score, long timeSpent, Integer accuracyPercent) {}

    private final RedisTemplate<String, String> redisTemplate;

    @Autowired
    public LeaderboardService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Обновляет позицию пользователя, если новый результат лучше текущего.
     */
    public void updateLeaderboard(Long quizId, Long userId, String login, long score, long timeSpent, Integer accuracyPercent) {
        try {
            String zsetKey = String.format(ZSET_KEY, quizId);
            String usersKey = String.format(USERS_KEY, quizId);
            String userIdStr = String.valueOf(userId);

            double compositeScore = calculateCompositeScore(score, timeSpent, accuracyPercent);

            Double current = redisTemplate.opsForZSet().score(zsetKey, userIdStr);
            if (current == null || compositeScore > current) {
                redisTemplate.opsForZSet().add(zsetKey, userIdStr, compositeScore);
                redisTemplate.opsForHash().put(usersKey, userIdStr, serializeEntry(login, score, timeSpent, accuracyPercent));
            }
        } catch (Exception e) {
            System.err.println("LeaderboardService: ошибка обновления лидерборда: " + e.getMessage());
        }
    }

    public void replaceLeaderboard(Long quizId, List<CachedLeaderboardEntry> entries) {
        try {
            String zsetKey = String.format(ZSET_KEY, quizId);
            String usersKey = String.format(USERS_KEY, quizId);

            redisTemplate.delete(zsetKey);
            redisTemplate.delete(usersKey);

            for (CachedLeaderboardEntry entry : entries) {
                if (entry == null || entry.userId() == null) {
                    continue;
                }
                String userIdStr = String.valueOf(entry.userId());
                redisTemplate.opsForZSet().add(
                        zsetKey,
                        userIdStr,
                        calculateCompositeScore(entry.score(), entry.timeSpent(), entry.accuracyPercent())
                );
                redisTemplate.opsForHash().put(
                        usersKey,
                        userIdStr,
                        serializeEntry(entry.login(), entry.score(), entry.timeSpent(), entry.accuracyPercent())
                );
            }
        } catch (Exception e) {
            System.err.println("LeaderboardService: ошибка перестроения лидерборда: " + e.getMessage());
        }
    }

    /**
     * Возвращает топ-100 лидерборда из Redis.
     * Возвращает null, если данных нет (нужен fallback на БД).
     */
    public LeaderboardDTO getLeaderboard(Long quizId, Long requestingUserId) {
        try {
            String zsetKey = String.format(ZSET_KEY, quizId);
            String usersKey = String.format(USERS_KEY, quizId);

            Set<String> userIds = redisTemplate.opsForZSet().reverseRange(zsetKey, 0, 99);
            if (userIds == null || userIds.isEmpty()) {
                return null;
            }

            List<LeaderboardEntry> entries = new ArrayList<>();
            int userPosition = -1;
            Integer userScore = null;
            int position = 1;

            for (String userIdStr : userIds) {
                Object raw = redisTemplate.opsForHash().get(usersKey, userIdStr);
                if (raw == null) continue;

                String[] parts = raw.toString().split(":");
                if (parts.length < 3) continue;

                String login = parts[0];
                int score = Integer.parseInt(parts[1]);
                long time = Long.parseLong(parts[2]);
                Integer accuracy = parts.length >= 4 ? Integer.parseInt(parts[3]) : null;

                entries.add(new LeaderboardEntry(position, login, score, time, accuracy));

                if (requestingUserId != null && requestingUserId.equals(Long.parseLong(userIdStr))) {
                    userPosition = position;
                    userScore = score;
                }
                position++;
            }

            return new LeaderboardDTO(entries, userPosition, userScore);
        } catch (Exception e) {
            System.err.println("LeaderboardService: ошибка чтения лидерборда: " + e.getMessage());
            return null;
        }
    }

    private int normalizeAccuracy(Integer accuracyPercent) {
        if (accuracyPercent == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, accuracyPercent));
    }

    private double calculateCompositeScore(long score, long timeSpent, Integer accuracyPercent) {
        int accuracy = normalizeAccuracy(accuracyPercent);
        return score * 1_000_000_000.0 + accuracy * 1_000_000.0 - timeSpent;
    }

    private String serializeEntry(String login, long score, long timeSpent, Integer accuracyPercent) {
        return login + ":" + score + ":" + timeSpent + ":" + normalizeAccuracy(accuracyPercent);
    }

    /**
     * Удаляет лидерборд квиза из Redis.
     */
    public void evict(Long quizId) {
        try {
            redisTemplate.delete(String.format(ZSET_KEY, quizId));
            redisTemplate.delete(String.format(USERS_KEY, quizId));
        } catch (Exception e) {
            System.err.println("LeaderboardService: ошибка очистки лидерборда: " + e.getMessage());
        }
    }
}
