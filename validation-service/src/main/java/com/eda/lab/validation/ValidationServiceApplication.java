package com.eda.lab.validation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main application class for the Validation Service.
 * 
 * @EnableScheduling enables Spring's scheduled task execution,
 * which is used by the OutboxPublisher to poll and publish events.
 */
@SpringBootApplication
@EnableScheduling
public class ValidationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ValidationServiceApplication.class, args);
    }
}
