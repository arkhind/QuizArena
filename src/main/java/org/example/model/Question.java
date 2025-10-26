package org.example.model;

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getQuizId() { return quizId; }
    public void setQuizId(Long quizId) { this.quizId = quizId; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
}
