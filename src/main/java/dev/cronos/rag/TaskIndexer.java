package dev.cronos.rag;

import com.pgvector.PGvector;
import dev.aegis4j.api.rag.Embedder;
import dev.cronos.domain.Task;
import dev.cronos.repository.RepositoryException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Indexes a completed task's title+description as a real embedding into
 * {@code task_embeddings}, the table {@code PgVectorRetriever} searches for
 * "have we done something similar before" questions. Triggered from
 * {@code TasksHandler} whenever a task's status becomes {@code DONE} — no
 * separate endpoint, indexing follows the natural lifecycle event.
 */
public final class TaskIndexer {

    private final DataSource dataSource;
    private final Embedder embedder;

    public TaskIndexer(DataSource dataSource, Embedder embedder) {
        this.dataSource = dataSource;
        this.embedder = embedder;
    }

    public void index(Task task) {
        String content = task.title() + (task.description() == null || task.description().isBlank()
                ? ""
                : ". " + task.description());
        float[] embedding = embedder.embed(content);

        String sql = """
                INSERT INTO task_embeddings (id, content, embedding)
                VALUES (?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, embedding = EXCLUDED.embedding
                """;
        try (Connection connection = dataSource.getConnection()) {
            PGvector.addVectorType(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, task.id().toString());
                statement.setString(2, content);
                statement.setObject(3, new PGvector(embedding));
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RepositoryException("Failed to index task " + task.id(), e);
        }
    }
}
