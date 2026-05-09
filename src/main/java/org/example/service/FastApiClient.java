package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.ml.MlJobStateDTO;
import org.example.dto.ml.MlQuestionDTO;
import org.example.metrics.MetricsService;
import org.example.model.QuestionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class FastApiClient {
    private static final Logger logger = LoggerFactory.getLogger(FastApiClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_TOPIC = "\u0443\u0447\u0435\u0431\u043d\u044b\u0439 \u043c\u0430\u0442\u0435\u0440\u0438\u0430\u043b";

    private final String checkEthicsBaseUrl;
    private final String generateUrl;
    private final String jobsGenerateUrl;
    private final String jobsBaseUrl;
    private final HttpClient httpClient;
    private final MetricsService metricsService;

    public FastApiClient(
            @Value("${quizarena.ml.base-url:http://127.0.0.1:8000}") String mlBaseUrl,
            MetricsService metricsService) {
        String normalizedBase = mlBaseUrl.endsWith("/") ? mlBaseUrl.substring(0, mlBaseUrl.length() - 1) : mlBaseUrl;
        this.checkEthicsBaseUrl = normalizedBase + "/check-ethics/";
        this.generateUrl = normalizedBase + "/generate";
        this.jobsGenerateUrl = normalizedBase + "/jobs/generate";
        this.jobsBaseUrl = normalizedBase + "/jobs/";
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.metricsService = metricsService;
    }

    public boolean checkPromptEthics(String prompt) throws IOException, InterruptedException {
        String encodedPrompt = URLEncoder.encode(prompt, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(checkEthicsBaseUrl + encodedPrompt))
                .GET()
                .build();

        long ethicsStart = System.nanoTime();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        metricsService.getFastApiEthicsTimer().record(System.nanoTime() - ethicsStart, TimeUnit.NANOSECONDS);

        if (response.statusCode() == 404) {
            return false;
        }
        if (response.statusCode() >= 500) {
            throw new GenerationRetryableException("ML service ethics check is temporarily unavailable");
        }
        if (response.statusCode() >= 400) {
            throw new GenerationNonRetryableException("Ethics check failed with status " + response.statusCode());
        }

        try {
            JsonNode jsonNode = MAPPER.readTree(response.body());
            if (jsonNode.has("unethical")) {
                return jsonNode.get("unethical").asBoolean();
            }
            return false;
        } catch (Exception e) {
            throw new GenerationNonRetryableException("Invalid ethics check response", e);
        }
    }

    public List<MlQuestionDTO> generateQuestionsStructured(
            String prompt,
            int numberOfQuestions,
            QuestionType preferredQuestionType
    ) throws IOException, InterruptedException, UnethicalPromptException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(generateUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        buildGenerationFormBody(prompt, numberOfQuestions, preferredQuestionType),
                        StandardCharsets.UTF_8))
                .build();

        long generateStart = System.nanoTime();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        metricsService.getFastApiGenerateTimer().record(System.nanoTime() - generateStart, TimeUnit.NANOSECONDS);

        handleGenerationError(response);
        return extractFinishedQuestions(MAPPER.readValue(response.body(), MlJobStateDTO.class));
    }

    public MlJobStateDTO startGenerationJob(
            String prompt,
            int numberOfQuestions,
            QuestionType preferredQuestionType,
            List<Path> materialFiles
    ) throws IOException, InterruptedException, UnethicalPromptException {
        List<Path> files = materialFiles != null ? materialFiles : List.of();
        logger.info("Sending quiz generation request to ML service with {} material file(s): {}", files.size(), files);
        String boundary = "QuizArenaBoundary" + UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(jobsGenerateUrl))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(buildMultipartBody(boundary, prompt, numberOfQuestions, preferredQuestionType, files))
                .build();

        long generateStart = System.nanoTime();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        metricsService.getFastApiGenerateTimer().record(System.nanoTime() - generateStart, TimeUnit.NANOSECONDS);

        handleGenerationError(response);
        MlJobStateDTO job = MAPPER.readValue(response.body(), MlJobStateDTO.class);
        if (job == null || job.id() == null || job.id().isBlank()) {
            throw new GenerationRetryableException("ML service did not return a job id");
        }
        return job;
    }

    public MlJobStateDTO startGenerationJob(
            String prompt,
            int numberOfQuestions,
            QuestionType preferredQuestionType
    ) throws IOException, InterruptedException, UnethicalPromptException {
        return startGenerationJob(prompt, numberOfQuestions, preferredQuestionType, List.of());
    }

    public MlJobStateDTO getGenerationJob(String mlJobId) throws IOException, InterruptedException {
        String encodedJobId = URLEncoder.encode(mlJobId, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(jobsBaseUrl + encodedJobId))
                .GET()
                .build();

        long generateStart = System.nanoTime();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        metricsService.getFastApiGenerateTimer().record(System.nanoTime() - generateStart, TimeUnit.NANOSECONDS);

        if (response.statusCode() == 404) {
            throw new GenerationNonRetryableException("ML job was not found: " + mlJobId);
        }
        handleGenerationError(response);
        return MAPPER.readValue(response.body(), MlJobStateDTO.class);
    }

    public boolean isJobFinished(MlJobStateDTO job) {
        return job != null && "finished".equalsIgnoreCase(job.status());
    }

    public boolean isJobFailed(MlJobStateDTO job) {
        return job != null && "failed".equalsIgnoreCase(job.status());
    }

    public List<MlQuestionDTO> extractFinishedQuestions(MlJobStateDTO job) {
        if (job == null) {
            return List.of();
        }
        if (isJobFailed(job)) {
            String message = job.errors() != null && !job.errors().isEmpty()
                    ? String.join("; ", job.errors())
                    : "ML job failed";
            throw new GenerationNonRetryableException("ML service error: " + message);
        }
        if (job.result() == null || job.result().parsed_response() == null) {
            return List.of();
        }
        List<MlQuestionDTO> questions = job.result().parsed_response().questions();
        return questions != null ? questions : List.of();
    }

    private String buildGenerationFormBody(String prompt, int numberOfQuestions, QuestionType preferredQuestionType) {
        String normalizedPrompt = normalizeTopic(prompt);
        String questionTypes = mapQuestionType(preferredQuestionType);

        return "topic=" + URLEncoder.encode(normalizedPrompt, StandardCharsets.UTF_8)
                + "&number=" + numberOfQuestions
                + "&question_types=" + URLEncoder.encode(questionTypes, StandardCharsets.UTF_8);
    }

    private HttpRequest.BodyPublisher buildMultipartBody(
            String boundary,
            String prompt,
            int numberOfQuestions,
            QuestionType preferredQuestionType,
            List<Path> materialFiles
    ) throws IOException {
        List<byte[]> byteArrays = new ArrayList<>();
        addFormField(byteArrays, boundary, "topic", normalizeTopic(prompt));
        addFormField(byteArrays, boundary, "number", String.valueOf(numberOfQuestions));
        addFormField(byteArrays, boundary, "question_types", mapQuestionType(preferredQuestionType));

        for (Path file : materialFiles) {
            if (file == null || !Files.isRegularFile(file)) {
                continue;
            }
            String filename = file.getFileName().toString();
            String contentType = Files.probeContentType(file);
            if (contentType == null || contentType.isBlank()) {
                contentType = "application/octet-stream";
            }
            byteArrays.add(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"files\"; filename=\"" + escapeMultipart(filename) + "\"\r\n"
                    + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            byteArrays.add(Files.readAllBytes(file));
            byteArrays.add("\r\n".getBytes(StandardCharsets.UTF_8));
        }

        byteArrays.add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.BodyPublishers.ofByteArrays(byteArrays);
    }

    private void addFormField(List<byte[]> byteArrays, String boundary, String name, String value) {
        byteArrays.add(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private String escapeMultipart(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String normalizeTopic(String prompt) {
        String normalized = prompt != null ? prompt.trim() : "";
        return hasMeaningfulText(normalized) ? normalized : DEFAULT_TOPIC;
    }

    private boolean hasMeaningfulText(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private void handleGenerationError(HttpResponse<String> response) throws UnethicalPromptException {
        if (response.statusCode() < 400) {
            return;
        }

        try {
            if (response.statusCode() == 502 || response.statusCode() == 503 || response.statusCode() == 504) {
                throw new GenerationRetryableException(
                        "ML service is temporarily unavailable, status " + response.statusCode()
                );
            }

            JsonNode jsonNode = MAPPER.readTree(response.body());
            if (jsonNode.has("error")) {
                String errorType = jsonNode.get("error").asText();
                String errorMessage = jsonNode.has("message")
                        ? jsonNode.get("message").asText()
                        : "Question generation failed";
                if ("UNETHICAL_PROMPT".equals(errorType)) {
                    throw new UnethicalPromptException(errorMessage);
                }
                throw new GenerationNonRetryableException("ML service error: " + errorMessage);
            }
            if (jsonNode.has("detail")) {
                throw new GenerationNonRetryableException("ML service error: " + jsonNode.get("detail"));
            }
            throw new GenerationNonRetryableException(
                    "ML service returned status " + response.statusCode() + ": " + response.body()
            );
        } catch (UnethicalPromptException | GenerationRetryableException | GenerationNonRetryableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new GenerationNonRetryableException(
                    "Failed to parse ML error response, status " + response.statusCode() + ": " + response.body(),
                    e
            );
        }
    }

    private String mapQuestionType(QuestionType preferredQuestionType) {
        if (preferredQuestionType == null) {
            return "single_choice";
        }
        return switch (preferredQuestionType) {
            case MULTIPLE_CHOICE -> "multiple_choice";
            case HUNDRED_TO_ONE -> "100k1";
            case SINGLE_CHOICE -> "single_choice";
        };
    }
}

