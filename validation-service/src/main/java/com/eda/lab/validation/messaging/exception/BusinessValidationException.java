package com.eda.lab.validation.messaging.exception;

import lombok.Getter;

/**
 * Exception thrown when document fails business validation rules.
 * 
 * This exception indicates a BUSINESS RULE violation, NOT a technical failure.
 * 
 * Key Difference:
 * - BusinessValidationException => DON'T RETRY (emit DocumentRejected event)
 * - RuntimeException/IOException => RETRY (transient technical failure)
 * 
 * Examples of Business Validation Failures:
 * - File name too long
 * - Invalid file format (not PDF)
 * - File size exceeds limit
 * - Invalid metadata
 * - Content violates business rules
 * 
 * These should NOT be retried because:
 * - Retrying won't fix the issue
 * - The document itself is invalid
 * - We should emit DocumentRejected event instead
 */
@Getter
public class BusinessValidationException extends RuntimeException {
    
    private final String reason;
    
    public BusinessValidationException(String reason) {
        super("Business validation failed: " + reason);
        this.reason = reason;
    }
    
    public BusinessValidationException(String reason, Object... args) {
        super(String.format("Business validation failed: " + reason, args));
        this.reason = String.format(reason, args);
    }

}
