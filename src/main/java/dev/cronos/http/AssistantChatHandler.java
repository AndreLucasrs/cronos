package dev.cronos.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aegis4j.core.guard.GuardBlockedException;
import dev.cronos.assistant.CronosAssistant;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpStatus;

import java.util.Map;

public final class AssistantChatHandler {

    private final CronosAssistant assistant;
    private final ObjectMapper mapper;

    public AssistantChatHandler(CronosAssistant assistant, ObjectMapper mapper) {
        this.assistant = assistant;
        this.mapper = mapper;
    }

    public Handler handler() {
        return this::handle;
    }

    private void handle(Context ctx) throws Exception {
        ChatRequestDto request = mapper.readValue(ctx.body(), ChatRequestDto.class);
        if (request.message() == null || request.message().isBlank()) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "message is required"));
            return;
        }
        try {
            CronosAssistant.ChatResult result = assistant.chat(request.message());
            ctx.json(Map.of("reply", result.reply(), "model", result.model()));
        } catch (GuardBlockedException e) {
            ctx.status(HttpStatus.UNPROCESSABLE_CONTENT).json(Map.of(
                    "error", "blocked",
                    "reasonCode", e.reasonCode()));
        }
    }

    private record ChatRequestDto(String message) {
    }
}
