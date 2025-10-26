package org.example.model;

import java.time.LocalDateTime;

public class Quiz {
    private Long id;
    private String name;
    private String prompt;
    private Long createBy;
    private Boolean hasMaterial;
    private String materialUrl;
    private Long questionNumber;
    private Long time;
    private Boolean isPrivate;
    private Boolean isStatic;
    private LocalDateTime createdAt;

    public Quiz() {}

    public Quiz(Long id, String name, String prompt, Long createBy, Boolean hasMaterial, 
                String materialUrl, Long questionNumber, Long time, Boolean isPrivate, 
                Boolean isStatic, LocalDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.prompt = prompt;
        this.createBy = createBy;
        this.hasMaterial = hasMaterial;
        this.materialUrl = materialUrl;
        this.questionNumber = questionNumber;
        this.time = time;
        this.isPrivate = isPrivate;
        this.isStatic = isStatic;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }

    public Boolean getHasMaterial() { return hasMaterial; }
    public void setHasMaterial(Boolean hasMaterial) { this.hasMaterial = hasMaterial; }

    public String getMaterialUrl() { return materialUrl; }
    public void setMaterialUrl(String materialUrl) { this.materialUrl = materialUrl; }

    public Long getQuestionNumber() { return questionNumber; }
    public void setQuestionNumber(Long questionNumber) { this.questionNumber = questionNumber; }

    public Long getTime() { return time; }
    public void setTime(Long time) { this.time = time; }

    public Boolean getIsPrivate() { return isPrivate; }
    public void setIsPrivate(Boolean isPrivate) { this.isPrivate = isPrivate; }

    public Boolean getIsStatic() { return isStatic; }
    public void setIsStatic(Boolean isStatic) { this.isStatic = isStatic; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
