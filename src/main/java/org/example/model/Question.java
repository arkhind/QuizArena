package org.example.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class Question {
    private Long id;
    private Quiz quiz;
    private String text;
    private QuestionType type;
    private String explanation;
    private byte[] image;
    private List<AnswerOption> answerOptions;

    public Question() {
        this.answerOptions = new ArrayList<>();
    }

    public Question(Long id, Quiz quiz, String text, QuestionType type, String explanation, byte[] image) {
        this.id = id;
        this.quiz = quiz;
        this.text = text;
        this.type = type;
        this.explanation = explanation;
        this.image = image;
        this.answerOptions = new ArrayList<>();
    }
}
