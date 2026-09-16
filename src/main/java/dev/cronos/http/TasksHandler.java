package dev.cronos.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cronos.assistant.CronosAssistant;
import dev.cronos.config.Env;
import dev.cronos.domain.Task;
import dev.cronos.domain.TaskStatus;
import dev.cronos.repository.TaskRepository;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class TasksHandler {

    private final TaskRepository tasks;
    private final CronosAssistant assistant;
    private final ObjectMapper mapper;

    public TasksHandler(TaskRepository tasks, CronosAssistant assistant, ObjectMapper mapper) {
        this.tasks = tasks;
        this.assistant = assistant;
        this.mapper = mapper;
    }

    public Handler listByProject() {
        return ctx -> {
            UUID projectId = UUID.fromString(ctx.pathParam("id"));
            ctx.json(tasks.findByProject(projectId));
        };
    }

    public Handler create() {
        return this::handleCreate;
    }

    private void handleCreate(Context ctx) throws Exception {
        CreateTaskRequest request = mapper.readValue(ctx.body(), CreateTaskRequest.class);
        if (request.projectId() == null || request.title() == null || request.title().isBlank()) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "projectId and title are required"));
            return;
        }
        LocalDate dueDate = request.dueDate() == null ? null : LocalDate.parse(request.dueDate());
        List<UUID> dependsOn = request.dependsOn() == null ? List.of() : request.dependsOn();
        Task created = tasks.create(
                UUID.fromString(request.projectId()),
                request.title().strip(),
                request.description(),
                dueDate,
                dependsOn);
        ctx.status(HttpStatus.CREATED).json(created);
    }

    public Handler updateStatus() {
        return ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            UpdateStatusRequest request = mapper.readValue(ctx.body(), UpdateStatusRequest.class);
            TaskStatus status = TaskStatus.fromWire(request.status());
            tasks.updateStatus(id, status);
            Task updated = tasks.findById(id);
            if (updated == null) {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "task not found"));
                return;
            }
            if (status == TaskStatus.DONE) {
                assistant.taskIndexer().index(updated);
            }
            ctx.json(updated);
        };
    }

    /** Deterministic MCP action (a UI button), never LLM-decided — see CronosAssistant. */
    public Handler createReminder() {
        return this::handleCreateReminder;
    }

    private void handleCreateReminder(Context ctx) throws IOException {
        UUID id = UUID.fromString(ctx.pathParam("id"));
        Task task = tasks.findById(id);
        if (task == null) {
            ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "task not found"));
            return;
        }
        if (task.dueDate() == null) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "task has no due date"));
            return;
        }

        Optional<String> ics = assistant.createReminderIcs(task.title(), task.dueDate(), task.description());
        if (ics.isEmpty()) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", "failed to generate reminder"));
            return;
        }

        Path remindersDir = Path.of(Env.get("CRONOS_REMINDERS_DIR", "reminders"));
        Files.createDirectories(remindersDir);
        Path icsFile = remindersDir.resolve(id + ".ics");
        Files.writeString(icsFile, ics.get());

        ctx.json(Map.of("downloadUrl", "/reminders/" + id + ".ics"));
    }

    private record CreateTaskRequest(String projectId, String title, String description, String dueDate,
                                      List<UUID> dependsOn) {
    }

    private record UpdateStatusRequest(String status) {
    }
}
