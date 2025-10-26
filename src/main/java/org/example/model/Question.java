package org.example.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Question {
    private Long id;
    private Long quizId;
    private String text;
    private String type;
    private String explanation;
    private String imageUrl;

    public Question() {}

    public Question(Long id, Long quizId, String text, String type, String explanation, String imageUrl) {
        this.id = id;
        this.quizId = quizId;
        this.text = text;
        this.type = type;
        this.explanation = explanation;
        this.imageUrl = imageUrl;
    }
}
