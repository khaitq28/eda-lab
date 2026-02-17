package com.eda.lab.audit.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * DTO for document event timeline.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentTimelineResponse {
    private UUID documentId;
    private List<String> eventTimeline;
    private long eventCount;
}
