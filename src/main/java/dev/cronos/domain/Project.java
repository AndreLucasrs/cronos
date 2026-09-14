package dev.cronos.domain;

import java.time.Instant;
import java.util.UUID;

public record Project(UUID id, String name, Instant createdAt) {
}
