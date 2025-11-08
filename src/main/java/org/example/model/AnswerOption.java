package org.example.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AnswerOption {
    private Long id;
    private Question question;
    private String text;
    private boolean isCorrect;
    private boolean isNaOption;

    public AnswerOption() {}

    public AnswerOption(Long id, Question question, String text, boolean isCorrect, boolean isNaOption) {
        this.id = id;
        this.question = question;
        this.text = text;
        this.isCorrect = isCorrect;
        this.isNaOption = isNaOption;
    }
}
