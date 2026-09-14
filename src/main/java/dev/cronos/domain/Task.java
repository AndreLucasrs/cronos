package dev.cronos.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record Task(
        UUID id,
        UUID projectId,
        String title,
        String description,
        TaskStatus status,
        LocalDate dueDate,
        Instant createdAt,
        List<UUID> dependsOn) {
}
