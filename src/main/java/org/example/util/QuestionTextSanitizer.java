package org.example.util;

import java.util.regex.Pattern;

public final class QuestionTextSanitizer {
    private static final Pattern TRAILING_PARENTHESES_INSTRUCTION = Pattern.compile(
            "\\s*[\\(（]\\s*(?:выберите|выбери|выбрать|укажите|укажи|отметьте|отметь|select|choose)[^\\)）]*[\\)）]\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern TRAILING_SENTENCE_INSTRUCTION = Pattern.compile(
            "\\s*(?:выберите|выбери|выбрать|укажите|укажи|отметьте|отметь|select|choose)[^.!?]*[.!?]?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private QuestionTextSanitizer() {
    }

    public static String sanitize(String text) {
        if (text == null) {
            return null;
        }

        String sanitized = text.trim();
        String previous;
        do {
            previous = sanitized;
            sanitized = TRAILING_PARENTHESES_INSTRUCTION.matcher(sanitized).replaceFirst("").trim();
            sanitized = TRAILING_SENTENCE_INSTRUCTION.matcher(sanitized).replaceFirst("").trim();
        } while (!sanitized.equals(previous));

        return sanitized.isEmpty() ? text.trim() : sanitized;
    }
}
