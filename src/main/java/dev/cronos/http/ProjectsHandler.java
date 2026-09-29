package dev.cronos.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cronos.assistant.CronosAssistant;
import dev.cronos.domain.Project;
import dev.cronos.repository.ProjectRepository;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpStatus;

import java.util.Map;
import java.util.UUID;

public final class ProjectsHandler {

    private final ProjectRepository projects;
    private final CronosAssistant assistant;
    private final ObjectMapper mapper;

    public ProjectsHandler(ProjectRepository projects, CronosAssistant assistant, ObjectMapper mapper) {
        this.projects = projects;
        this.assistant = assistant;
        this.mapper = mapper;
    }

    public Handler list() {
        return ctx -> ctx.json(projects.findAll());
    }

    public Handler create() {
        return this::handleCreate;
    }

    private void handleCreate(Context ctx) throws Exception {
        CreateProjectRequest request = mapper.readValue(ctx.body(), CreateProjectRequest.class);
        if (request.name() == null || request.name().isBlank()) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "name is required"));
            return;
        }
        Project created = projects.create(request.name().strip());
        ctx.status(HttpStatus.CREATED).json(created);
    }

    public Handler getOne() {
        return ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            Project project = projects.findById(id);
            if (project == null) {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "project not found"));
                return;
            }
            ctx.json(project);
        };
    }

    public Handler setBrief() {
        return this::handleSetBrief;
    }

    private void handleSetBrief(Context ctx) throws Exception {
        UUID id = UUID.fromString(ctx.pathParam("id"));
        Project project = projects.findById(id);
        if (project == null) {
            ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "project not found"));
            return;
        }
        SetBriefRequest request = mapper.readValue(ctx.body(), SetBriefRequest.class);
        if (request.markdown() == null || request.markdown().isBlank()) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "markdown is required"));
            return;
        }
        int chunksIndexed = assistant.ingestProjectBrief(id, request.markdown());
        ctx.json(Map.of("chunksIndexed", chunksIndexed));
    }

    private record CreateProjectRequest(String name) {
    }

    private record SetBriefRequest(String markdown) {
    }
}
