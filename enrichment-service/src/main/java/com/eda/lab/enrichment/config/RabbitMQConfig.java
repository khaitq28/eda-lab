package com.eda.lab.enrichment.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

/**
 * RabbitMQ Configuration for enrichment-service.
 * 
 * Topology:
 * - Exchange: doc.events (topic, shared across all services)
 * - Main Queue: document.validated.q (consumes DocumentValidated events)
 * - Dead Letter Exchange: doc.dlx
 * - Dead Letter Queue: document.validated.dlq (for failed messages after retries)
 * 
 * Retry Strategy:
 * - maxAttempts: 5
 * - initialInterval: 1000ms (1 second)
 * - multiplier: 2.0 (exponential backoff)
 * - maxInterval: 10000ms (10 seconds)
 * - Sequence: 1s, 2s, 4s, 8s, 10s
 * 
 * After 5 failed attempts, message is sent to DLQ for manual inspection.
 */
@Configuration
@Slf4j
public class RabbitMQConfig {

    // Exchange and Queue Names
    public static final String TOPIC_EXCHANGE = "doc.events";
    public static final String MAIN_QUEUE = "document.validated.q";
    public static final String ROUTING_KEY = "document.validated";
    
    // Dead Letter Configuration
    public static final String DLX_EXCHANGE = "doc.dlx";
    public static final String DLQ_QUEUE = "document.validated.dlq";
    public static final String DLQ_ROUTING_KEY = "document.validated.dlq";

    /**
     * Topic exchange for all document events.
     * Shared across all services (ingestion, validation, enrichment, audit).
     */
    @Bean
    public TopicExchange topicExchange() {
        return new TopicExchange(TOPIC_EXCHANGE, true, false);
    }

    /**
     * Dead Letter Exchange for failed messages.
     */
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    /**
     * Main queue for consuming DocumentValidated events.
     * Configured with DLX for failed messages.
     */
    @Bean
    public Queue mainQueue() {
        return QueueBuilder.durable(MAIN_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build();
    }

    /**
     * Dead Letter Queue for messages that failed after all retries.
     */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    /**
     * Bind main queue to topic exchange with routing key "document.validated".
     */
    @Bean
    public Binding mainQueueBinding(Queue mainQueue, TopicExchange topicExchange) {
        return BindingBuilder.bind(mainQueue)
                .to(topicExchange)
                .with(ROUTING_KEY);
    }

    /**
     * Bind DLQ to dead letter exchange.
     */
    @Bean
    public Binding deadLetterQueueBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue)
                .to(deadLetterExchange)
                .with(DLQ_ROUTING_KEY);
    }

    /**
     * JSON message converter for serializing/deserializing events.
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * Retry interceptor for consumer with exponential backoff.
     * 
     * Retry Strategy:
     * - Attempt 1: Immediate
     * - Attempt 2: After 1 second
     * - Attempt 3: After 2 seconds
     * - Attempt 4: After 4 seconds
     * - Attempt 5: After 8 seconds
     * - After 5 attempts: Send to DLQ
     * 
     * Technical failures (DB down, network issues) trigger retry.
     * Business failures should NOT throw exceptions to avoid retry.
     */
    @Bean
    public RetryOperationsInterceptor retryInterceptor() {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(5)
                .backOffOptions(
                        1000,  // initialInterval: 1 second
                        2.0,   // multiplier: exponential backoff
                        10000  // maxInterval: 10 seconds
                )
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }

    /**
     * Configure listener container factory with retry interceptor.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            RetryOperationsInterceptor retryInterceptor,
            MessageConverter jsonMessageConverter) {
        
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setAdviceChain(retryInterceptor);
        factory.setDefaultRequeueRejected(false); // Don't requeue, let retry interceptor handle it
        
        log.info("RabbitMQ listener container factory configured with retry interceptor");
        return factory;
    }
}
