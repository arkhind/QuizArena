package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.ml.MlJobStateDTO;
import org.example.dto.ml.MlQuestionDTO;
import org.example.metrics.MetricsService;
import org.example.model.QuestionType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class FastApiClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String questionBaseUrl;
    private final String checkEthicsBaseUrl;
    private final HttpClient httpClient;
    private final MetricsService metricsService;

    public FastApiClient(
            @Value("${quizarena.ml.base-url:http://127.0.0.1:8000}") String mlBaseUrl,
            MetricsService metricsService) {
        String normalizedBase = mlBaseUrl.endsWith("/") ? mlBaseUrl.substring(0, mlBaseUrl.length() - 1) : mlBaseUrl;
        this.questionBaseUrl = normalizedBase + "/question/";
        this.checkEthicsBaseUrl = normalizedBase + "/check-ethics/";
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.metricsService = metricsService;
    }

    public boolean checkPromptEthics(String prompt) throws IOException, InterruptedException {
        String encodedPrompt = URLEncoder.encode(prompt, StandardCharsets.UTF_8);
        String url = checkEthicsBaseUrl + encodedPrompt;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        long ethicsStart = System.nanoTime();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        metricsService.getFastApiEthicsTimer().record(System.nanoTime() - ethicsStart, TimeUnit.NANOSECONDS);

        System.out.println("FastApiClient: Проверка этичности для промпта. Статус: " + response.statusCode());
        System.out.println("FastApiClient: Ответ проверки этичности: " + response.body());

        if (response.statusCode() == 404) {
            System.out.println("FastApiClient: endpoint /check-ethics не найден, пропускаем проверку этичности");
            return false;
        }

        if (response.statusCode() >= 500) {
            System.out.println("FastApiClient: сервис проверки этичности недоступен, пропускаем проверку");
            return false;
        }

        if (response.statusCode() >= 400) {
            throw new RuntimeException("Ошибка при проверке этичности промпта (статус " + response.statusCode() + ")");
        }

        try {
            JsonNode jsonNode = MAPPER.readTree(response.body());

            if (jsonNode.has("unethical")) {
                boolean isUnethical = jsonNode.get("unethical").asBoolean();
                System.out.println("FastApiClient: Промпт неэтичен: " + isUnethical);
                return isUnethical;
            } else if (jsonNode.has("error")) {
                System.out.println("FastApiClient: ошибка проверки этичности на стороне ML: " + jsonNode.get("error").asText());
                return false;
            } else {
                System.out.println("FastApiClient: неожиданный формат ответа проверки этичности, пропускаем проверку");
                return false;
            }
        } catch (Exception e) {
            System.out.println("FastApiClient: ошибка парсинга ответа проверки этичности, пропускаем проверку: " + e.getMessage());
            return false;
        }
    }

    public List<MlQuestionDTO> generateQuestionsStructured(
            String prompt,
            int numberOfQuestions,
            QuestionType preferredQuestionType
    ) throws IOException, InterruptedException, UnethicalPromptException {
        String normalizedPrompt = prompt == null ? "" : prompt;
        String questionTypes = mapQuestionType(preferredQuestionType);

        String formBody = "topic=" + URLEncoder.encode(normalizedPrompt, StandardCharsets.UTF_8)
                + "&number=" + numberOfQuestions
                + "&question_types=" + URLEncoder.encode(questionTypes, StandardCharsets.UTF_8);

        String url = questionBaseUrl.replace("/question/", "/generate");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                .build();

        long generateStart = System.nanoTime();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        metricsService.getFastApiGenerateTimer().record(System.nanoTime() - generateStart, TimeUnit.NANOSECONDS);

        if (response.statusCode() >= 400) {
            try {
                JsonNode jsonNode = MAPPER.readTree(response.body());
                if (jsonNode.has("error")) {
                    String errorType = jsonNode.get("error").asText();
                    String errorMessage = jsonNode.has("message") ? jsonNode.get("message").asText() : "Ошибка при генерации вопросов";
                    if ("UNETHICAL_PROMPT".equals(errorType)) {
                        throw new UnethicalPromptException(errorMessage);
                    }
                    throw new RuntimeException("Ошибка ML сервера: " + errorMessage);
                }
                if (jsonNode.has("detail")) {
                    throw new RuntimeException("Ошибка ML сервера (422): " + jsonNode.get("detail").toString());
                }
                throw new RuntimeException("Ошибка ML сервера (статус " + response.statusCode() + "): " + response.body());
            } catch (UnethicalPromptException e) {
                throw e;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Ошибка при обработке ответа ML сервера (статус " + response.statusCode() + "): " + response.body(), e);
            }
        }

        MlJobStateDTO job = MAPPER.readValue(response.body(), MlJobStateDTO.class);
        if (job == null) {
            return List.of();
        }
        if ("failed".equalsIgnoreCase(job.status()) && job.errors() != null && !job.errors().isEmpty()) {
            throw new RuntimeException("Ошибка ML сервера: " + String.join("; ", job.errors()));
        }
        if (job.result() == null || job.result().parsed_response() == null) {
            return List.of();
        }
        List<MlQuestionDTO> questions = job.result().parsed_response().questions();
        return questions != null ? questions : List.of();
    }

    private String mapQuestionType(QuestionType preferredQuestionType) {
        if (preferredQuestionType == null) {
            return "single_choice";
        }
        return switch (preferredQuestionType) {
            case MULTIPLE_CHOICE -> "multiple_choice";
            case TRUE_FALSE -> "true_false";
            case HUNDRED_TO_ONE -> "100k1";
            case SINGLE_CHOICE, TEXT -> "single_choice";
        };
    }
}
