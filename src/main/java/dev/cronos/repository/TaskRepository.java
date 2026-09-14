package dev.cronos.repository;

import dev.cronos.domain.Task;
import dev.cronos.domain.TaskStatus;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class TaskRepository {

    private final DataSource dataSource;

    public TaskRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Task create(UUID projectId, String title, String description, LocalDate dueDate, List<UUID> dependsOn) {
        String insertSql = """
                INSERT INTO tasks (project_id, title, description, due_date)
                VALUES (?, ?, ?, ?)
                RETURNING id, project_id, title, description, status, due_date, created_at
                """;
        try (Connection connection = dataSource.getConnection()) {
            UUID taskId;
            Task created;
            try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
                statement.setObject(1, projectId, Types.OTHER);
                statement.setString(2, title);
                statement.setString(3, description);
                if (dueDate != null) {
                    statement.setDate(4, Date.valueOf(dueDate));
                } else {
                    statement.setNull(4, Types.DATE);
                }
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    taskId = (UUID) rs.getObject("id");
                    created = map(rs, List.of());
                }
            }
            if (dependsOn != null && !dependsOn.isEmpty()) {
                insertDependencies(connection, taskId, dependsOn);
                created = findById(taskId);
            }
            return created;
        } catch (SQLException e) {
            throw new RepositoryException("Failed to create task", e);
        }
    }

    private void insertDependencies(Connection connection, UUID taskId, List<UUID> dependsOn) throws SQLException {
        String sql = "INSERT INTO task_dependencies (task_id, depends_on_task_id) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (UUID dependsOnId : dependsOn) {
                statement.setObject(1, taskId, Types.OTHER);
                statement.setObject(2, dependsOnId, Types.OTHER);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public List<Task> findByProject(UUID projectId) {
        String sql = """
                SELECT t.id, t.project_id, t.title, t.description, t.status, t.due_date, t.created_at,
                       array_remove(array_agg(d.depends_on_task_id), NULL) AS depends_on
                FROM tasks t
                LEFT JOIN task_dependencies d ON d.task_id = t.id
                WHERE t.project_id = ?
                GROUP BY t.id
                ORDER BY t.created_at ASC
                """;
        List<Task> tasks = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, projectId, Types.OTHER);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    tasks.add(map(rs, readDependsOn(rs)));
                }
            }
            return tasks;
        } catch (SQLException e) {
            throw new RepositoryException("Failed to list tasks for project " + projectId, e);
        }
    }

    public Task findById(UUID id) {
        String sql = """
                SELECT t.id, t.project_id, t.title, t.description, t.status, t.due_date, t.created_at,
                       array_remove(array_agg(d.depends_on_task_id), NULL) AS depends_on
                FROM tasks t
                LEFT JOIN task_dependencies d ON d.task_id = t.id
                WHERE t.id = ?
                GROUP BY t.id
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id, Types.OTHER);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return map(rs, readDependsOn(rs));
            }
        } catch (SQLException e) {
            throw new RepositoryException("Failed to find task " + id, e);
        }
    }

    public void updateStatus(UUID id, TaskStatus status) {
        String sql = "UPDATE tasks SET status = ? WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.toWire());
            statement.setObject(2, id, Types.OTHER);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RepositoryException("Failed to update task " + id, e);
        }
    }

    private List<UUID> readDependsOn(ResultSet rs) throws SQLException {
        Array array = rs.getArray("depends_on");
        if (array == null) {
            return List.of();
        }
        Object[] raw = (Object[]) array.getArray();
        return Arrays.stream(raw).map(o -> (UUID) o).toList();
    }

    private Task map(ResultSet rs, List<UUID> dependsOn) throws SQLException {
        Date dueDate = rs.getDate("due_date");
        return new Task(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("project_id"),
                rs.getString("title"),
                rs.getString("description"),
                TaskStatus.fromWire(rs.getString("status")),
                dueDate != null ? dueDate.toLocalDate() : null,
                rs.getTimestamp("created_at").toInstant(),
                dependsOn);
    }
}
