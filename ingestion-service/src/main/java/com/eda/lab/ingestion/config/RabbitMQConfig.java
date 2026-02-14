package com.eda.lab.ingestion.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration for the Ingestion Service.
 * 
 * This configuration declares the RabbitMQ topology (exchanges, queues, bindings)
 * automatically - just like Flyway for databases!
 * 
 * Spring AMQP will create these resources on startup if they don't exist.
 * This is idempotent - running multiple times is safe.
 */
@Configuration
@Slf4j
public class RabbitMQConfig {

    // Exchange name - topic exchange for flexible routing
    public static final String EXCHANGE_NAME = "doc.events";
    
    // Queue name for DocumentUploaded events
    public static final String DOCUMENT_UPLOADED_QUEUE = "document.uploaded.q";
    
    // Routing key for document uploaded events
    public static final String DOCUMENT_UPLOADED_ROUTING_KEY = "document.uploaded";
    
    // Dead Letter Exchange for failed messages
    public static final String DLX_EXCHANGE = "doc.events.dlx";
    public static final String DLQ_QUEUE = "document.uploaded.dlq";

    /**
     * Declare the main topic exchange.
     * Topic exchanges allow flexible routing with wildcards.
     * 
     * Example routing keys:
     * - document.uploaded
     * - document.validated
     * - document.rejected
     * - document.enriched
     */
    @Bean
    public TopicExchange documentEventsExchange() {
        return ExchangeBuilder
                .topicExchange(EXCHANGE_NAME)
                .durable(true)  // Survives broker restart
                .build();
    }

    /**
     * Declare the queue for DocumentUploaded events.
     * 
     * Configuration:
     * - Durable: Survives broker restart
     * - Dead Letter Exchange: Failed messages go to DLQ
     * - TTL: Messages expire after 7 days if not consumed
     * - Max Length: Prevent unbounded growth (10,000 messages)
     */
    @Bean
    public Queue documentUploadedQueue() {
        return QueueBuilder
                .durable(DOCUMENT_UPLOADED_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)  // Route failures to DLX
                .ttl(7 * 24 * 60 * 60 * 1000)      // 7 days TTL
                .maxLength(10000L)                  // Max 10k messages
                .build();
    }

    /**
     * Bind the queue to the exchange with a routing key.
     * Messages published with "document.uploaded" will go to this queue.
     */
    @Bean
    public Binding documentUploadedBinding(Queue documentUploadedQueue, 
                                           TopicExchange documentEventsExchange) {
        return BindingBuilder
                .bind(documentUploadedQueue)
                .to(documentEventsExchange)
                .with(DOCUMENT_UPLOADED_ROUTING_KEY);
    }

    /**
     * Dead Letter Exchange for failed messages.
     * Messages that fail max retries go here for manual inspection.
     */
    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder
                .directExchange(DLX_EXCHANGE)
                .durable(true)
                .build();
    }

    /**
     * Dead Letter Queue - stores failed messages.
     * Ops team can inspect and manually reprocess these.
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
                .with(DOCUMENT_UPLOADED_ROUTING_KEY);
    }

    /**
     * Message converter for JSON serialization.
     * Uses Jackson to convert events to/from JSON.
     */
    @Bean
    public MessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * RabbitTemplate with publisher confirms for reliability.
     * Publisher confirms ensure messages are persisted before ack.
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        
        // Enable publisher confirms for at-least-once delivery
        template.setMandatory(true);
        
        // Callback when message cannot be routed
        template.setReturnsCallback(returned -> {
            log.error("Message returned from broker: {} ({})", 
                    returned.getMessage(), 
                    returned.getReplyText());
        });
        
        return template;
    }
}
