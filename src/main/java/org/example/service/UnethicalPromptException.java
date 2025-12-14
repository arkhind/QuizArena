package org.example.service;

/**
 * Исключение, выбрасываемое когда промпт не прошел проверку на этичность (VibeCheck).
 */
public class UnethicalPromptException extends RuntimeException {
    public UnethicalPromptException(String message) {
        super(message);
    }
    
    public UnethicalPromptException() {
        super("Данные квиза являются неэтичными");
    }
}

