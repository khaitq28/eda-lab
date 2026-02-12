package com.eda.lab.common.event;

/**
 * Constants for event type names used across the system.
 */
public final class EventTypes {

    private EventTypes() {
        // Prevent instantiation
    }

    public static final String DOCUMENT_UPLOADED = "DocumentUploaded";
    public static final String DOCUMENT_VALIDATED = "DocumentValidated";
    public static final String DOCUMENT_REJECTED = "DocumentRejected";
    public static final String DOCUMENT_ENRICHED = "DocumentEnriched";
}
