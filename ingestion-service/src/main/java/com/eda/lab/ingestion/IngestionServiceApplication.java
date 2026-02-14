package com.eda.lab.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main application class for the Ingestion Service.
 * 
 * @EnableScheduling enables Spring's scheduled task execution,
 * which is used by the OutboxPublisher to poll and publish events.
 */
@SpringBootApplication
@EnableScheduling
public class IngestionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionServiceApplication.class, args);
    }
}
