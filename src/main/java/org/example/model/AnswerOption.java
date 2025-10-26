package org.example.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AnswerOption {
    private Long id;
    private Long questionId;
    private String text;
    private Boolean isCorrect;
    private Boolean isNaOption;

    public AnswerOption() {}

    public AnswerOption(Long id, Long questionId, String text, Boolean isCorrect, Boolean isNaOption) {
        this.id = id;
        this.questionId = questionId;
        this.text = text;
        this.isCorrect = isCorrect;
        this.isNaOption = isNaOption;
    }
}
