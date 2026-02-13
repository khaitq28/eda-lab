package com.eda.lab.common.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Standard error response structure for all services.
 * Uses Lombok for familiar Spring Boot style.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    private String error;
    private String message;
    private int status;
    private String path;
    
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant timestamp;
    
    private List<FieldError> fieldErrors;

    /**
     * Nested class for field-specific validation errors.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldError {
        private String field;
        private String message;
        private Object rejectedValue;
    }

    /**
     * Factory method for simple errors.
     */
    public static ErrorResponse of(String error, String message, int status, String path) {
        return ErrorResponse.builder()
                .error(error)
                .message(message)
                .status(status)
                .path(path)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Factory method for validation errors.
     */
    public static ErrorResponse withFieldErrors(String error, String message, int status, 
                                                 String path, List<FieldError> fieldErrors) {
        return ErrorResponse.builder()
                .error(error)
                .message(message)
                .status(status)
                .path(path)
                .timestamp(Instant.now())
                .fieldErrors(fieldErrors)
                .build();
    }
}
