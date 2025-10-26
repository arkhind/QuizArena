package org.example.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserQuizAttempt {
    private Long id;
    private Long userId;
    private Long quizId;
    private Long startTime;
    private Long finishTime;
    private Long score;
    private Long isCompleted;

    public UserQuizAttempt() {}

    public UserQuizAttempt(Long id, Long userId, Long quizId, Long startTime, 
                          Long finishTime, Long score, Long isCompleted) {
        this.id = id;
        this.userId = userId;
        this.quizId = quizId;
        this.startTime = startTime;
        this.finishTime = finishTime;
        this.score = score;
        this.isCompleted = isCompleted;
    }
}
