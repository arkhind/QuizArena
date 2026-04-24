package org.example.kafka;

import org.example.dto.kafka.QuizGenerationRequestMessage;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
@EnableConfigurationProperties(KafkaQuizGenerationProperties.class)
public class KafkaQuizGenerationConfig {
    @Bean
    public org.apache.kafka.clients.admin.NewTopic quizGenerationRequestTopic(
            KafkaQuizGenerationProperties properties
    ) {
        return TopicBuilder.name(properties.getRequestTopic())
                .partitions(properties.getRequestPartitions())
                .replicas(1)
                .build();
    }

    @Bean
    public org.apache.kafka.clients.admin.NewTopic quizGenerationRequestDltTopic(
            KafkaQuizGenerationProperties properties
    ) {
        return TopicBuilder.name(properties.getRequestDltTopic())
                .partitions(properties.getRequestPartitions())
                .replicas(1)
                .build();
    }

    @Bean
    public ProducerFactory<String, QuizGenerationRequestMessage> quizGenerationRequestProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers
    ) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, QuizGenerationRequestMessage> quizGenerationRequestKafkaTemplate(
            ProducerFactory<String, QuizGenerationRequestMessage> quizGenerationRequestProducerFactory
    ) {
        return new KafkaTemplate<>(quizGenerationRequestProducerFactory);
    }

    @Bean
    public ConsumerFactory<String, QuizGenerationRequestMessage> quizGenerationRequestConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers
    ) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        JsonDeserializer<QuizGenerationRequestMessage> deserializer =
                new JsonDeserializer<>(QuizGenerationRequestMessage.class);
        deserializer.addTrustedPackages("*");
        deserializer.setRemoveTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), deserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, QuizGenerationRequestMessage> quizGenerationRequestKafkaListenerContainerFactory(
            ConsumerFactory<String, QuizGenerationRequestMessage> quizGenerationRequestConsumerFactory,
            DefaultErrorHandler quizGenerationErrorHandler,
            KafkaQuizGenerationProperties properties
    ) {
        ConcurrentKafkaListenerContainerFactory<String, QuizGenerationRequestMessage> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(quizGenerationRequestConsumerFactory);
        factory.setConcurrency(properties.getWorkerConcurrency());
        factory.setCommonErrorHandler(quizGenerationErrorHandler);
        return factory;
    }

    @Bean
    public DeadLetterPublishingRecoverer quizGenerationDeadLetterPublishingRecoverer(
            KafkaTemplate<String, QuizGenerationRequestMessage> quizGenerationRequestKafkaTemplate,
            KafkaQuizGenerationProperties properties
    ) {
        return new DeadLetterPublishingRecoverer(
                quizGenerationRequestKafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception ex) -> new TopicPartition(properties.getRequestDltTopic(), record.partition())
        );
    }

    @Bean
    public DefaultErrorHandler quizGenerationErrorHandler(
            DeadLetterPublishingRecoverer quizGenerationDeadLetterPublishingRecoverer
    ) {
        // После 1 повторной попытки сообщение уходит в DLT, не блокируя основную очередь
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                quizGenerationDeadLetterPublishingRecoverer,
                new FixedBackOff(2000L, 1L)
        );
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        return errorHandler;
    }

}
