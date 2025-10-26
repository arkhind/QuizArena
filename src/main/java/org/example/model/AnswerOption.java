package org.example.model;

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public Boolean getIsCorrect() { return isCorrect; }
    public void setIsCorrect(Boolean isCorrect) { this.isCorrect = isCorrect; }

    public Boolean getIsNaOption() { return isNaOption; }
    public void setIsNaOption(Boolean isNaOption) { this.isNaOption = isNaOption; }
}
