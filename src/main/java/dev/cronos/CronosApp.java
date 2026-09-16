package dev.cronos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.cronos.assistant.CronosAssistant;
import dev.cronos.config.Env;
import dev.cronos.db.Database;
import dev.cronos.http.AssistantChatHandler;
import dev.cronos.http.ProjectsHandler;
import dev.cronos.http.TasksHandler;
import dev.cronos.repository.ProjectRepository;
import dev.cronos.repository.TaskRepository;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CronosApp {

    public static void main(String[] args) throws IOException {
        String remindersDir = Env.get("CRONOS_REMINDERS_DIR", "reminders");
        Files.createDirectories(Path.of(remindersDir));

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        DataSource dataSource = Database.dataSource();
        ProjectRepository projectRepository = new ProjectRepository(dataSource);
        TaskRepository taskRepository = new TaskRepository(dataSource);
        CronosAssistant assistant = new CronosAssistant(dataSource);
        Runtime.getRuntime().addShutdownHook(new Thread(assistant::close));

        ProjectsHandler projectsHandler = new ProjectsHandler(projectRepository, mapper);
        TasksHandler tasksHandler = new TasksHandler(taskRepository, assistant, mapper);
        AssistantChatHandler chatHandler = new AssistantChatHandler(assistant, mapper);

        Javalin app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson().updateMapper(om -> {
                om.registerModule(new JavaTimeModule());
                om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            }));
            config.staticFiles.add(staticFiles -> {
                staticFiles.directory = "web";
                staticFiles.location = Location.EXTERNAL;
            });
            config.staticFiles.add(staticFiles -> {
                staticFiles.directory = remindersDir;
                staticFiles.location = Location.EXTERNAL;
                staticFiles.hostedPath = "/reminders";
            });
        });

        app.get("/health", ctx -> ctx.result("ok"));
        app.get("/api/projects", projectsHandler.list());
        app.post("/api/projects", projectsHandler.create());
        app.get("/api/projects/{id}", projectsHandler.getOne());
        app.get("/api/projects/{id}/tasks", tasksHandler.listByProject());
        app.post("/api/tasks", tasksHandler.create());
        app.patch("/api/tasks/{id}/status", tasksHandler.updateStatus());
        app.post("/api/tasks/{id}/reminder", tasksHandler.createReminder());
        app.post("/api/assistant/chat", chatHandler.handler());

        app.start(Env.getInt("CRONOS_PORT", 7070));
    }
}
