package org.example.service;

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

@Service
public class FastApiClient {
    private static final String BASE_URL = "http://127.0.0.1:8000/question/";
    private final HttpClient httpClient;

    public FastApiClient() {
        this.httpClient = HttpClient.newHttpClient();
    }

    public String getQuestionsByPrompt(String prompt, int numberOfQuestions) throws IOException, InterruptedException {
        String encodedPrompt = URLEncoder.encode(prompt, StandardCharsets.UTF_8);
        String url = BASE_URL + encodedPrompt + "/" + numberOfQuestions;
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(response.body());
            return jsonNode.get("questions").asText();
        } catch (Exception e) {
            System.err.println("FastApiClient: Не удалось распарсить JSON, возвращаем тело ответа как есть. Ошибка: " + e.getMessage());
            return response.body();
        }
    }
}

