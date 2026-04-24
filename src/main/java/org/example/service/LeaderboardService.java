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

    private static final String ZSET_KEY = "leaderboard:%d";
    private static final String USERS_KEY = "leaderboard:users:%d";

    private final RedisTemplate<String, String> redisTemplate;

    @Autowired
    public LeaderboardService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Обновляет позицию пользователя, если новый результат лучше текущего.
     */
    public void updateLeaderboard(Long quizId, Long userId, String login, long score, long timeSpent) {
        try {
            String zsetKey = String.format(ZSET_KEY, quizId);
            String usersKey = String.format(USERS_KEY, quizId);
            String userIdStr = String.valueOf(userId);

            double compositeScore = score * 1_000_000.0 - timeSpent;

            Double current = redisTemplate.opsForZSet().score(zsetKey, userIdStr);
            if (current == null || compositeScore > current) {
                redisTemplate.opsForZSet().add(zsetKey, userIdStr, compositeScore);
                redisTemplate.opsForHash().put(usersKey, userIdStr, login + ":" + score + ":" + timeSpent);
            }
        } catch (Exception e) {
            System.err.println("LeaderboardService: ошибка обновления лидерборда: " + e.getMessage());
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

                entries.add(new LeaderboardEntry(position, login, score, time));

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
