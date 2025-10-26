package org.example.model;

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getQuizId() { return quizId; }
    public void setQuizId(Long quizId) { this.quizId = quizId; }

    public Long getStartTime() { return startTime; }
    public void setStartTime(Long startTime) { this.startTime = startTime; }

    public Long getFinishTime() { return finishTime; }
    public void setFinishTime(Long finishTime) { this.finishTime = finishTime; }

    public Long getScore() { return score; }
    public void setScore(Long score) { this.score = score; }

    public Long getIsCompleted() { return isCompleted; }
    public void setIsCompleted(Long isCompleted) { this.isCompleted = isCompleted; }
}
