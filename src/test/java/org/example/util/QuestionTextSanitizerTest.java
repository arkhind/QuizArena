package org.example.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuestionTextSanitizerTest {
    @Test
    void removesTrailingParenthesizedSelectionInstruction() {
        assertEquals(
                "Какие фильмы/сериалы корейского происхождения особенно известны международной аудитории?",
                QuestionTextSanitizer.sanitize("Какие фильмы/сериалы корейского происхождения особенно известны международной аудитории? (выберите 5 правильных из 8)")
        );
    }

    @Test
    void keepsMeaningfulParenthesesInsideQuestion() {
        assertEquals(
                "Что означает термин «100 к 1» (в контексте игры)?",
                QuestionTextSanitizer.sanitize("Что означает термин «100 к 1» (в контексте игры)?")
        );
    }
}
