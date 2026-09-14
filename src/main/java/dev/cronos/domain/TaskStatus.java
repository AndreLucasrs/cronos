package dev.cronos.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TaskStatus {
    TODO,
    DOING,
    DONE;

    @JsonCreator
    public static TaskStatus fromWire(String value) {
        return TaskStatus.valueOf(value.toUpperCase());
    }

    @JsonValue
    public String toWire() {
        return name().toLowerCase();
    }
}
