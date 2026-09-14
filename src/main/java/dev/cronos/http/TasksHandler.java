package dev.cronos.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cronos.domain.Task;
import dev.cronos.domain.TaskStatus;
import dev.cronos.repository.TaskRepository;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TasksHandler {

    private final TaskRepository tasks;
    private final ObjectMapper mapper;

    public TasksHandler(TaskRepository tasks, ObjectMapper mapper) {
        this.tasks = tasks;
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
            tasks.updateStatus(id, TaskStatus.fromWire(request.status()));
            Task updated = tasks.findById(id);
            if (updated == null) {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "task not found"));
                return;
            }
            ctx.json(updated);
        };
    }

    private record CreateTaskRequest(String projectId, String title, String description, String dueDate,
                                      List<UUID> dependsOn) {
    }

    private record UpdateStatusRequest(String status) {
    }
}
