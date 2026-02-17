package com.eda.lab.enrichment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EnrichmentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnrichmentServiceApplication.class, args);
    }
}
