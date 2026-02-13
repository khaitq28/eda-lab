# Java 21 Features in EDA Lab - Pragmatic Approach

This project uses Java 21 features **where they add architectural value**, not everywhere.

## 🎯 Philosophy: Best Practices First, Modern Features Second

We use Java 21 features to **enforce architectural constraints**, not just for novelty:
- ✅ Records for **Events** → Immutability (EDA requirement)
- ✅ Sealed interfaces for **Event hierarchy** → Closed type system (EDA pattern)
- ✅ Lombok for **DTOs/Entities** → Familiar Spring Boot style
- ✅ Pattern matching for **Event routing** → Type safety + readability

---

## 📋 What We Use (And Why)

### 1. **Records for Events ONLY** (JEP 395) ✅

#### Why Records for Events?
Events in Event-Driven Architecture **MUST be immutable**. This is not a Java 21 feature, it's an **EDA best practice**.

**Before (Mutable):**
```java
@Data
public class DocumentUploadedEvent {
    private UUID eventId;
    private String documentName;
}
// Problem: event.setDocumentName("hacked"); ← This should never happen!
```

**After (Immutable with Records):**
```java
public record DocumentUploadedEvent(
    UUID eventId,
    String eventType,
    UUID aggregateId,
    Instant timestamp,
    String documentName,
    String contentType,
    Long fileSize
) implements BaseEvent {
    // Can't modify after creation - perfect for events!
}
```

**Benefits:**
- ✅ **Immutability enforced** - Can't accidentally modify events
- ✅ **Thread-safe** - Safe to share between threads
- ✅ **Less boilerplate** - No need for @Data, @Builder, etc.
- ✅ **Explicit contract** - All fields visible at a glance

**Usage:**
```java
var event = DocumentUploadedEvent.create(
    documentId, 
    "invoice.pdf", 
    "application/pdf", 
    1024L
);
// event.documentName = "changed"; ← Compilation error! ✅
```

---

### 2. **Sealed Interfaces for Event Hierarchy** (JEP 409) ✅

#### Why Sealed for Events?
Your EDA system has a **fixed set of events**. New events don't appear randomly at runtime.

**Implementation:**
```java
public sealed interface BaseEvent
    permits DocumentUploadedEvent, DocumentValidatedEvent, 
            DocumentRejectedEvent, DocumentEnrichedEvent {
    UUID eventId();
    String eventType();
    UUID aggregateId();
    Instant timestamp();
}
```

**Benefits:**
- ✅ **Compiler knows all possible events** - Enables exhaustive checking
- ✅ **Self-documenting** - Clear contract of system events
- ✅ **Type safety** - No unexpected event types
- ✅ **Prevents mistakes** - Can't accidentally add events without updating handlers

**Real-world benefit:**
```java
// Compiler ensures ALL events are handled - no missing cases!
public String routeToQueue(BaseEvent event) {
    return switch (event) {
        case DocumentUploadedEvent e -> "validation.queue";
        case DocumentValidatedEvent e -> "enrichment.queue";
        case DocumentRejectedEvent e -> "dlq.queue";
        case DocumentEnrichedEvent e -> "audit.queue";
        // No default needed - compiler checks exhaustiveness
    };
}
```

---

### 3. **Lombok for DTOs and Entities** ✅

#### Why NOT Records for DTOs/Entities?

**DTOs (Data Transfer Objects):**
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    private boolean success;
    private String message;
    private T data;
    private Instant timestamp;
}
```

**Reasons:**
- ✅ **Familiar** - Standard Spring Boot style
- ✅ **Flexible** - Easy to add validation, custom logic
- ✅ **Builder pattern** - Clean construction: `ApiResponse.builder().success(true).build()`
- ✅ **Mutable when needed** - DTOs don't need the same guarantees as events

**JPA Entities:**
```java
@Data
@Entity
public class Document extends BaseEntity {
    private String name;
    private String status;
}
```

**Reasons:**
- ✅ **JPA requires mutability** - Hibernate needs to set fields
- ✅ **Standard pattern** - All Spring Data JPA examples use this
- ✅ **Lombok reduces boilerplate** - No manual getters/setters

---

## 🎯 When to Use What?

| Type | Use | Reason |
|------|-----|--------|
| **Events** | Records | Immutability required by EDA |
| **DTOs** | Lombok @Data/@Builder | Familiar, flexible |
| **JPA Entities** | Lombok @Data | JPA requires mutability |
| **Value Objects** | Records | Immutability desired |
| **Configuration** | Lombok @Data | Spring convention |

---

## 🚀 Pattern Matching for Event Handling (JEP 441)

Enhanced switch with type patterns - perfect for event routing!

```java
public void handleEvent(BaseEvent event) {
    switch (event) {
        case DocumentUploadedEvent e -> {
            log.info("Processing upload: {}", e.documentName());
            validationService.validate(e.aggregateId());
        }
        case DocumentValidatedEvent e -> {
            log.info("Processing validation: {}", e.validationResult());
            enrichmentService.enrich(e.aggregateId());
        }
        case DocumentRejectedEvent e -> {
            log.warn("Document rejected: {}", e.rejectionReason());
            notificationService.notifyRejection(e);
        }
        case DocumentEnrichedEvent e -> {
            log.info("Document enriched: {}", e.classification());
            auditService.logCompletion(e);
        }
    };
}
```

**Benefits:**
- ✅ **No casting** - Type is known in each case
- ✅ **Exhaustive** - Compiler ensures all events handled
- ✅ **Readable** - Clear flow of event processing
- ✅ **Refactor-safe** - Adding new event forces update here

---

## 📊 Comparison: Before vs After

### Event Class Evolution

**Version 1: Plain Java (Verbose)**
```java
public class DocumentUploadedEvent {
    private final UUID eventId;
    private final String documentName;
    
    public DocumentUploadedEvent(UUID eventId, String documentName) {
        this.eventId = eventId;
        this.documentName = documentName;
    }
    
    public UUID getEventId() { return eventId; }
    public String getDocumentName() { return documentName; }
    // ... equals, hashCode, toString
}
// ~50 lines
```

**Version 2: Lombok (Familiar but not immutable)**
```java
@Data
@Builder
@AllArgsConstructor
public class DocumentUploadedEvent {
    private UUID eventId;
    private String documentName;
}
// ~8 lines, but mutable!
```

**Version 3: Record (Final - Immutable + Concise)** ✅
```java
public record DocumentUploadedEvent(
    UUID eventId,
    UUID aggregateId,
    Instant timestamp,
    String documentName,
    Long fileSize
) implements BaseEvent { }
// ~7 lines, immutable, perfect for events!
```

---

## ✅ Our Pragmatic Rules

1. **Events = Records** (Immutability is EDA requirement)
2. **Event hierarchy = Sealed interface** (Fixed set of events)
3. **DTOs = Lombok** (Familiar Spring Boot style)
4. **Entities = Lombok** (JPA needs mutability)
5. **Pattern matching = Event routing** (Type safety)

---

## 🎓 Key Takeaway

**We use Java 21 features to enforce architectural patterns, not to show off.**

- Events are immutable → Records enforce this
- Event set is fixed → Sealed interfaces enforce this
- DTOs are flexible → Lombok keeps them readable

**Focus remains on EDA patterns:**
- ⭐ Transactional Outbox
- ⭐ Idempotent Consumers
- ⭐ Event-driven workflows

Java 21 **supports** these patterns without distracting from them.

---

## 📚 Additional Features (Future Use)

### Virtual Threads (Optional)
Add to `application.yml` when needed:
```yaml
spring:
  threads:
    virtual:
      enabled: true
```
→ Use when you need high concurrency for I/O operations

### Text Blocks (Already Available)
```sql
String sql = """
    SELECT * FROM documents 
    WHERE status = 'PENDING'
    AND created_at > NOW() - INTERVAL '1 day'
    """;
```
→ Use for SQL, JSON, or multi-line strings

---

## 🎯 Summary

**What we changed:**
- ✅ Events: Now immutable Records (architectural requirement)
- ✅ Event hierarchy: Sealed interface (fixed event set)
- ✅ DTOs: Lombok (familiar, flexible)
- ✅ Entities: Lombok (standard Spring Data JPA)

**Result:** Modern where it matters, familiar everywhere else! 🚀
