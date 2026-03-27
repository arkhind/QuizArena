package org.example.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "quizarena.kafka.quiz-generation")
public class KafkaQuizGenerationProperties {
    private String requestTopic;
    private String requestDltTopic;
    private String workerGroupId;
    private int requestPartitions = 3;
    private int workerConcurrency = 3;

    public String getRequestTopic() {
        return requestTopic;
    }

    public void setRequestTopic(String requestTopic) {
        this.requestTopic = requestTopic;
    }

    public String getRequestDltTopic() {
        return requestDltTopic;
    }

    public void setRequestDltTopic(String requestDltTopic) {
        this.requestDltTopic = requestDltTopic;
    }

    public String getWorkerGroupId() {
        return workerGroupId;
    }

    public void setWorkerGroupId(String workerGroupId) {
        this.workerGroupId = workerGroupId;
    }

    public int getRequestPartitions() {
        return requestPartitions;
    }

    public void setRequestPartitions(int requestPartitions) {
        this.requestPartitions = requestPartitions;
    }

    public int getWorkerConcurrency() {
        return workerConcurrency;
    }

    public void setWorkerConcurrency(int workerConcurrency) {
        this.workerConcurrency = workerConcurrency;
    }
}
