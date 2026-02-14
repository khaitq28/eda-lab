package com.eda.lab.validation.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

/**
 * RabbitMQ configuration for Validation Service.
 * 
 * Key Features:
 * - Topology declaration (exchanges, queues, bindings, DLQ)
 * - Retry interceptor with exponential backoff
 * - Dead Letter Queue for permanently failed messages
 * - Manual acknowledgment for fine-grained control
 * - Jackson JSON message converter
 * 
 * Topology:
 * - doc.events (topic exchange) - shared with ingestion-service
 * - document.uploaded.q (main queue) - consumes DocumentUploaded events
 * - doc.dlx (dead letter exchange) - for failed messages
 * - document.uploaded.dlq (dead letter queue) - stores permanently failed messages
 * 
 * Retry Strategy:
 * - Max 5 attempts with exponential backoff (1s, 2s, 4s, 8s, 10s)
 * - After retries exhausted, message goes to DLQ
 * - Operators can manually inspect and reprocess DLQ messages
 */
@Configuration
@Slf4j
public class RabbitMQConfig {

    // ============================================================
    // Constants - Must match ingestion-service topology
    // ============================================================
    
    public static final String EXCHANGE_NAME = "doc.events";
    public static final String DOCUMENT_UPLOADED_QUEUE = "document.uploaded.q";
    public static final String DOCUMENT_UPLOADED_ROUTING_KEY = "document.uploaded";
    
    // Dead Letter Exchange/Queue
    public static final String DLX_EXCHANGE = "doc.dlx";
    public static final String DLQ_QUEUE = "document.uploaded.dlq";
    public static final String DLQ_ROUTING_KEY = "document.uploaded.dlq";

    // ============================================================
    // Main Exchange & Queue
    // ============================================================

    /**
     * Declare the main topic exchange (shared with ingestion-service).
     * This is idempotent - safe to declare in multiple services.
     */
    @Bean
    public TopicExchange documentEventsExchange() {
        return ExchangeBuilder
                .topicExchange(EXCHANGE_NAME)
                .durable(true)
                .build();
    }

    /**
     * Declare the main consumer queue with DLX configuration.
     * 
     * Key Configuration:
     * - x-dead-letter-exchange: Failed messages route to DLX
     * - x-dead-letter-routing-key: Routing key for DLQ
     * - TTL: 7 days (messages expire if not consumed)
     * - Max length: 10,000 (prevent unbounded growth)
     */
    @Bean
    public Queue documentUploadedQueue() {
        return QueueBuilder
                .durable(DOCUMENT_UPLOADED_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .ttl(7 * 24 * 60 * 60 * 1000)  // 7 days
                .maxLength(10000L)
                .build();
    }

    /**
     * Bind the main queue to the topic exchange.
     */
    @Bean
    public Binding documentUploadedBinding(Queue documentUploadedQueue,
                                           TopicExchange documentEventsExchange) {
        return BindingBuilder
                .bind(documentUploadedQueue)
                .to(documentEventsExchange)
                .with(DOCUMENT_UPLOADED_ROUTING_KEY);
    }

    // ============================================================
    // Dead Letter Exchange & Queue
    // ============================================================

    /**
     * Dead Letter Exchange for permanently failed messages.
     * Direct exchange for simple routing.
     */
    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder
                .directExchange(DLX_EXCHANGE)
                .durable(true)
                .build();
    }

    /**
     * Dead Letter Queue for failed messages.
     * Operators can inspect and manually reprocess these.
     */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder
                .durable(DLQ_QUEUE)
                .build();
    }

    /**
     * Bind DLQ to DLX.
     */
    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue,
                                     DirectExchange deadLetterExchange) {
        return BindingBuilder
                .bind(deadLetterQueue)
                .to(deadLetterExchange)
                .with(DLQ_ROUTING_KEY);
    }

    // ============================================================
    // Message Converter
    // ============================================================

    /**
     * JSON message converter using Jackson.
     */
    @Bean
    public MessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ============================================================
    // Retry Interceptor with Exponential Backoff
    // ============================================================

    /**
     * Retry interceptor with exponential backoff.
     * 
     * Retry Schedule:
     * - Attempt 1: Immediate (original delivery)
     * - Attempt 2: +1s delay (1s total)
     * - Attempt 3: +2s delay (3s total)
     * - Attempt 4: +4s delay (7s total)
     * - Attempt 5: +8s delay (15s total)
     * 
     * After 5 attempts, message is rejected and routed to DLQ.
     * 
     * RejectAndDontRequeueRecoverer ensures message goes to DLX (not back to main queue).
     */
    @Bean
    public RetryOperationsInterceptor retryInterceptor() {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(5)
                .backOffOptions(
                        1000,  // initialInterval: 1 second
                        2.0,   // multiplier: exponential backoff
                        10000  // maxInterval: cap at 10 seconds
                )
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }

    // ============================================================
    // Listener Container Factory with Retry
    // ============================================================

    /**
     * Custom listener container factory with:
     * - Manual acknowledgment (fine-grained control)
     * - Retry interceptor
     * - Prefetch count for throughput control
     * - JSON message converter
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            MessageConverter messageConverter,
            RetryOperationsInterceptor retryInterceptor) {
        
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        
        // Manual acknowledgment for idempotency control
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        
        // Prefetch: Number of unacked messages per consumer
        factory.setPrefetchCount(10);
        
        // Message converter
        factory.setMessageConverter(messageConverter);
        
        // Retry advice
        factory.setAdviceChain(retryInterceptor);
        
        // Error handler (logs errors before retry)
        factory.setErrorHandler(t -> {
            log.error("Error in listener (will retry if attempts remain)", t);
        });
        
        return factory;
    }
}
