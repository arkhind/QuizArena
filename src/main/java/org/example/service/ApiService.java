package org.example.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class ApiService {
    private static final String BASE_URL = "http://127.0.0.1:8000/question/";
    private final HttpClient httpClient;

    public ApiService() {
        this.httpClient = HttpClient.newHttpClient();
    }

    public String getQuestionsByPrompt(String prompt, int numberOfQuestions) throws IOException, InterruptedException {
        String url = BASE_URL + prompt + "/" + numberOfQuestions;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }
}

