package dev.cronos.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aegis4j.api.provider.CompletionChunk;
import dev.aegis4j.core.engine.StreamedCompletion;
import dev.aegis4j.core.guard.GuardBlockedException;
import dev.cronos.assistant.CronosAssistant;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpStatus;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code POST /api/assistant/chat/stream} — SSE variant of
 * {@link AssistantChatHandler}. Trade-off, not an upgrade: per
 * {@code Aegis4jEngine#chatStream} javadoc, output guards (e.g.
 * {@code RegexPiiGuard}), tool-calling and usage tracking do NOT run on this
 * path, only on the non-streaming {@code chat()}.
 */
public final class AssistantChatStreamHandler {

    private static final byte[] DATA_PREFIX = "data: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TERMINATOR = "\n\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DONE_PAYLOAD = "[DONE]".getBytes(StandardCharsets.UTF_8);

    private final CronosAssistant assistant;
    private final ObjectMapper mapper;

    public AssistantChatStreamHandler(CronosAssistant assistant, ObjectMapper mapper) {
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

        StreamedCompletion streamed;
        try {
            streamed = assistant.chatStream(request.message());
        } catch (GuardBlockedException e) {
            ctx.status(HttpStatus.UNPROCESSABLE_CONTENT).json(Map.of(
                    "error", "blocked", "reasonCode", e.reasonCode()));
            return;
        }

        ctx.status(200);
        ctx.contentType("text/event-stream");
        ctx.header("Cache-Control", "no-cache");
        ctx.header("Connection", "keep-alive");
        ctx.header("X-Accel-Buffering", "no");

        OutputStream out = ctx.outputStream();
        try (Stream<CompletionChunk> chunks = streamed.chunks()) {
            for (CompletionChunk chunk : (Iterable<CompletionChunk>) chunks::iterator) {
                if (chunk.done()) {
                    break;
                }
                writeEvent(out, mapper.writeValueAsBytes(Map.of("delta", chunk.deltaContent())));
            }
        }
        writeDone(out);
    }

    private void writeEvent(OutputStream out, byte[] jsonPayload) throws java.io.IOException {
        out.write(DATA_PREFIX);
        out.write(jsonPayload);
        out.write(TERMINATOR);
        out.flush();
    }

    private void writeDone(OutputStream out) throws java.io.IOException {
        out.write(DATA_PREFIX);
        out.write(DONE_PAYLOAD);
        out.write(TERMINATOR);
        out.flush();
    }

    private record ChatRequestDto(String message) {
    }
}
