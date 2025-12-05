package org.example.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Сервис для работы с загрузкой и хранением файлов материалов квизов.
 */
@Service
public class FileStorageService {

    @Value("${file.upload-dir:uploads}")
    private String uploadDir;

    private static final long MAX_TOTAL_SIZE = 5 * 1024 * 1024; // 5 МБ
    private static final int MAX_FILES = 10;
    private static final List<String> ALLOWED_EXTENSIONS = List.of(".pdf", ".txt", ".doc", ".docx");

    /**
     * Сохраняет загруженные файлы материалов для квиза.
     *
     * @param files массив загруженных файлов
     * @param quizId ID квиза
     * @return список URL сохраненных файлов
     * @throws IllegalArgumentException если файлы не соответствуют требованиям
     */
    public List<String> saveQuizMaterials(MultipartFile[] files, Long quizId) throws IOException {
        if (files == null || files.length == 0) {
            return new ArrayList<>();
        }

        // Проверка количества файлов
        if (files.length > MAX_FILES) {
            throw new IllegalArgumentException("Максимальное количество файлов: " + MAX_FILES);
        }

        // Проверка общего размера
        long totalSize = 0;
        for (MultipartFile file : files) {
            if (!file.isEmpty()) {
                totalSize += file.getSize();
            }
        }

        if (totalSize > MAX_TOTAL_SIZE) {
            throw new IllegalArgumentException("Общий размер файлов не должен превышать 5 МБ");
        }

        // Создаем директорию для квиза
        Path quizDir = Paths.get(uploadDir, "quizzes", String.valueOf(quizId));
        Files.createDirectories(quizDir);

        List<String> savedFileUrls = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }

            // Проверка расширения файла
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null) {
                continue;
            }

            String extension = getFileExtension(originalFilename);
            if (!ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
                throw new IllegalArgumentException("Неподдерживаемый формат файла: " + extension + 
                        ". Разрешенные форматы: pdf, txt, doc, docx");
            }

            // Генерируем уникальное имя файла
            String uniqueFilename = UUID.randomUUID().toString() + extension;
            Path targetPath = quizDir.resolve(uniqueFilename);

            // Сохраняем файл
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            // Сохраняем относительный путь для URL
            String fileUrl = "/uploads/quizzes/" + quizId + "/" + uniqueFilename;
            savedFileUrls.add(fileUrl);
        }

        return savedFileUrls;
    }

    /**
     * Удаляет файлы материалов квиза.
     */
    public void deleteQuizMaterials(Long quizId) throws IOException {
        Path quizDir = Paths.get(uploadDir, "quizzes", String.valueOf(quizId));
        if (Files.exists(quizDir)) {
            Files.walk(quizDir)
                    .sorted((a, b) -> b.compareTo(a)) // Сначала удаляем файлы, потом директории
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            // Логируем ошибку, но продолжаем удаление
                            System.err.println("Ошибка при удалении файла: " + path + " - " + e.getMessage());
                        }
                    });
        }
    }

    /**
     * Получает расширение файла.
     */
    private String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(lastDotIndex);
    }

    /**
     * Инициализирует директорию для загрузки файлов.
     */
    public void init() {
        try {
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }
        } catch (IOException e) {
            throw new RuntimeException("Не удалось создать директорию для загрузки файлов", e);
        }
    }
}

