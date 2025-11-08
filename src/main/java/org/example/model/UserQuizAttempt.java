package org.example.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Класс представляет попытку пользователя пройти квиз.
 * Хранит информацию о начале и завершении прохождения, набранных баллах и статусе завершения.
 */
@Getter
@Setter
public class UserQuizAttempt {
    private Long id;
    private User user;
    private Quiz quiz;
    private Instant startTime;
    private Instant finishTime;
    private Long score;
    private boolean isCompleted;

    public UserQuizAttempt() {}

    public UserQuizAttempt(Long id, User user, Quiz quiz, Instant startTime, 
                          Instant finishTime, Long score, boolean isCompleted) {
        this.id = id;
        this.user = user;
        this.quiz = quiz;
        this.startTime = startTime;
        this.finishTime = finishTime;
        this.score = score;
        this.isCompleted = isCompleted;
    }
}
