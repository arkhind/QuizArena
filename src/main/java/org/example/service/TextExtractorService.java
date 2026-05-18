package org.example.service;

import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class TextExtractorService {
    private static final int MAX_EXTRACTED_TEXT_LENGTH = 60_000;

    private final Tika tika = new Tika();

    public String extractText(Path file) {
        if (file == null) {
            return "";
        }
        try {
            String text = tika.parseToString(file);
            if (text == null || text.isBlank()) {
                return "";
            }
            text = text.trim();
            if (text.length() > MAX_EXTRACTED_TEXT_LENGTH) {
                text = text.substring(0, MAX_EXTRACTED_TEXT_LENGTH);
            }
            return text;
        } catch (Exception e) {
            throw new GenerationNonRetryableException("Не удалось извлечь текст из файла: " + file.getFileName(), e);
        }
    }
}
