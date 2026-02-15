package com.eda.lab.ingestion.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuration properties for the Outbox Publisher.
 * Values can be overridden in application.yml.
 */
@Configuration
@ConfigurationProperties(prefix = "outbox.publisher")
@Data
public class OutboxProperties {

    /**
     * How often to poll for pending outbox events.
     * Default: 2 seconds
     */
    private Duration pollingInterval = Duration.ofSeconds(2);

    /**
     * Maximum number of events to process in one batch.
     * Default: 50
     */
    private int batchSize = 50;

    /**
     * Maximum number of retry attempts before marking event as FAILED.
     * Default: 10
     */
    private int maxRetries = 5;

    /**
     * Initial retry delay (exponential backoff starts here).
     * Default: 10 seconds
     */
    private Duration initialRetryDelay = Duration.ofSeconds(10);

    /**
     * Maximum retry delay (exponential backoff caps at this).
     * Default: 1 hour
     */
    private Duration maxRetryDelay = Duration.ofHours(1);

    /**
     * Enable/disable the outbox publisher.
     * Useful for testing or maintenance.
     * Default: true
     */
    private boolean enabled = true;
}
