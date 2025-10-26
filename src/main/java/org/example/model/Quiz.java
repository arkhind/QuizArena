package org.example.model;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
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
}
