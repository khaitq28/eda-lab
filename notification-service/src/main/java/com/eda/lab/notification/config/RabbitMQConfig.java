package com.eda.lab.notification.config;

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
 * RabbitMQ Configuration for notification-service.
 * 
 * Topology:
 * - Exchange: doc.events (topic, shared across all services)
 * - Main Queue: document.notification.q (consumes DocumentValidated, DocumentRejected, DocumentEnriched)
 * - Bindings:
 *   - document.validated → document.notification.q
 *   - document.rejected → document.notification.q
 *   - document.enriched → document.notification.q
 * - Dead Letter Exchange: doc.dlx
 * - Dead Letter Queue: document.notification.dlq
 * 
 * Events Consumed:
 * - DocumentValidated (document.validated) - Send success notification
 * - DocumentRejected (document.rejected) - Send rejection notification
 * - DocumentEnriched (document.enriched) - Send completion notification
 * 
 * Retry Strategy:
 * - maxAttempts: 5
 * - initialInterval: 1000ms (1 second)
 * - multiplier: 2.0 (exponential backoff)
 * - maxInterval: 10000ms (10 seconds)
 * 
 * After 5 failed attempts, message is sent to DLQ for manual inspection.
 */
@Configuration
@Slf4j
public class RabbitMQConfig {

    // Exchange and Queue Names
    public static final String TOPIC_EXCHANGE = "doc.events";
    public static final String NOTIFICATION_QUEUE = "document.notification.q";
    
    // Dead Letter Configuration
    public static final String DLX_EXCHANGE = "doc.dlx";
    public static final String DLQ_QUEUE = "document.notification.dlq";
    public static final String DLQ_ROUTING_KEY = "document.notification.dlq";

    /**
     * Topic exchange for all document events (shared).
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
     * Main queue for notification events.
     * Configured with DLX for failed messages.
     */
    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE)
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
     * Bind notification queue to DocumentValidated events.
     */
    @Bean
    public Binding validatedBinding(Queue notificationQueue, TopicExchange topicExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(topicExchange)
                .with("document.validated");
    }

    /**
     * Bind notification queue to DocumentRejected events.
     */
    @Bean
    public Binding rejectedBinding(Queue notificationQueue, TopicExchange topicExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(topicExchange)
                .with("document.rejected");
    }

    /**
     * Bind notification queue to DocumentEnriched events.
     */
    @Bean
    public Binding enrichedBinding(Queue notificationQueue, TopicExchange topicExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(topicExchange)
                .with("document.enriched");
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
     * JSON message converter.
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * Retry interceptor with exponential backoff.
     */
    @Bean
    public RetryOperationsInterceptor retryInterceptor() {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(5)
                .backOffOptions(1000, 2.0, 10000)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }

    /**
     * Configure listener container factory with retry.
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
        factory.setDefaultRequeueRejected(false);
        
        log.info("RabbitMQ listener container factory configured for notification-service");
        return factory;
    }
}
