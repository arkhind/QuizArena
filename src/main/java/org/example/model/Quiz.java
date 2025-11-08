package org.example.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class Quiz {
    private Long id;
    private String name;
    private String prompt;
    private User createdBy;
    private boolean hasMaterial;
    private String materialUrl;
    private Duration timePerQuestion; // время на вопрос (может быть null, если таймер не используется)
    private boolean isPrivate;
    private boolean isStatic;
    private Instant createdAt;
    private List<Question> questions;

    public Quiz() {
        this.questions = new ArrayList<>();
    }

    public Quiz(Long id, String name, String prompt, User createdBy, boolean hasMaterial, 
                String materialUrl, Duration timePerQuestion, boolean isPrivate, 
                boolean isStatic, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.prompt = prompt;
        this.createdBy = createdBy;
        this.hasMaterial = hasMaterial;
        this.materialUrl = materialUrl;
        this.timePerQuestion = timePerQuestion;
        this.isPrivate = isPrivate;
        this.isStatic = isStatic;
        this.createdAt = createdAt;
        this.questions = new ArrayList<>();
    }
}
