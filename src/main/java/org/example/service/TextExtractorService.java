package org.example.service;

import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TextExtractorService {

    private final Tika tika = new Tika();

    public String extractText(MultipartFile file) {
        try {
            String text = tika.parseToString(file.getInputStream());

            // Ограничили длину запроса qwen3:8b ≈ 8K токенов
            if (text.length() > 15000) {
                text = text.substring(0, 15000);
            }
            return text.trim();
        } catch (Exception e) {
            throw new RuntimeException("Не удалось извлечь текст из файла: " + e.getMessage(), e);
        }
    }
}
