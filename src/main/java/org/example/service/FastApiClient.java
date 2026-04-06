package org.example.service;

import org.example.metrics.MetricsService;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

@Service
public class FastApiClient {
    private static final String BASE_URL = "http://127.0.0.1:8000/question/";
    private static final String CHECK_ETHICS_URL = "http://127.0.0.1:8000/check-ethics/";
    private final HttpClient httpClient;
    private final MetricsService metricsService;

    public FastApiClient(MetricsService metricsService) {
        this.httpClient = HttpClient.newHttpClient();
        this.metricsService = metricsService;
    }

    /**
     * Проверяет этичность промпта ДО генерации вопросов.
     * @param prompt промпт для проверки
     * @return true если промпт неэтичен (не прошел проверку)
     * @throws IOException если ошибка при запросе
     * @throws InterruptedException если запрос прерван
     */
    public boolean checkPromptEthics(String prompt) throws IOException, InterruptedException {
        String encodedPrompt = URLEncoder.encode(prompt, StandardCharsets.UTF_8);
        String url = CHECK_ETHICS_URL + encodedPrompt;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        long start = System.nanoTime();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        metricsService.getFastApiEthicsTimer().record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        
        System.out.println("FastApiClient: Проверка этичности для промпта. Статус: " + response.statusCode());
        System.out.println("FastApiClient: Ответ проверки этичности: " + response.body());
        
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Ошибка при проверке этичности промпта (статус " + response.statusCode() + ")");
        }
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(response.body());
            
            if (jsonNode.has("unethical")) {
                boolean isUnethical = jsonNode.get("unethical").asBoolean();
                System.out.println("FastApiClient: Промпт неэтичен: " + isUnethical);
                return isUnethical;
            } else if (jsonNode.has("error")) {
                throw new RuntimeException("Ошибка ML сервера при проверке этичности: " + jsonNode.get("message").asText());
            } else {
                throw new RuntimeException("Неожиданный формат ответа от ML сервера: " + response.body());
            }
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("Ошибка при парсинге ответа проверки этичности: " + e.getMessage(), e);
        }
    }

    public String getQuestionsByPrompt(String prompt, int numberOfQuestions) throws IOException, InterruptedException, UnethicalPromptException {
        String encodedPrompt = URLEncoder.encode(prompt, StandardCharsets.UTF_8);
        String url = BASE_URL + encodedPrompt + "/" + numberOfQuestions;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        long start = System.nanoTime();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        metricsService.getFastApiGenerateTimer().record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        
        // Проверяем статус код ответа
        if (response.statusCode() >= 400) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode jsonNode = mapper.readTree(response.body());
                
                if (jsonNode.has("error")) {
                    String errorType = jsonNode.get("error").asText();
                    String errorMessage = jsonNode.has("message") ? jsonNode.get("message").asText() : "Ошибка при генерации вопросов";
                    
                    if ("UNETHICAL_PROMPT".equals(errorType)) {
                        throw new UnethicalPromptException(errorMessage);
                    } else {
                        throw new RuntimeException("Ошибка ML сервера: " + errorMessage);
                    }
                }
            } catch (UnethicalPromptException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Ошибка при обработке ответа ML сервера (статус " + response.statusCode() + "): " + response.body(), e);
            }
        }
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(response.body());
            
            // Проверяем наличие поля error даже при успешном статус коде
            if (jsonNode.has("error")) {
                String errorType = jsonNode.get("error").asText();
                String errorMessage = jsonNode.has("message") ? jsonNode.get("message").asText() : "Ошибка при генерации вопросов";
                
                if ("UNETHICAL_PROMPT".equals(errorType)) {
                    throw new UnethicalPromptException(errorMessage);
                } else {
                    throw new RuntimeException("Ошибка ML сервера: " + errorMessage);
                }
            }
            
            // Если есть поле questions, возвращаем его
            if (jsonNode.has("questions")) {
                return jsonNode.get("questions").asText();
            } else {
                throw new RuntimeException("Ответ ML сервера не содержит поля 'questions'");
            }
        } catch (UnethicalPromptException e) {
            throw e;
        } catch (Exception e) {
            System.err.println("FastApiClient: Не удалось распарсить JSON, проверяем тело ответа на ошибку этичности. Ошибка: " + e.getMessage());
            
            // Проверяем тело ответа на наличие ошибки этичности
            String responseBody = response.body();
            if (responseBody != null) {
                String lowerBody = responseBody.toLowerCase();
                if (lowerBody.contains("unethical_prompt") || lowerBody.contains("неэтичн") || lowerBody.contains("неэтичными")) {
                    throw new UnethicalPromptException("Данные для создания квиза являются неэтичными");
                }
            }
            
            // Если это не UnethicalPromptException, пробуем вернуть тело ответа
            if (e instanceof RuntimeException && !(e instanceof UnethicalPromptException)) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("Ошибка при обработке ответа ML сервера: " + responseBody, e);
        }
    }
}

