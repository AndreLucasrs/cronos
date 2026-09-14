package dev.cronos.repository;

import dev.cronos.domain.Project;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ProjectRepository {

    private final DataSource dataSource;

    public ProjectRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Project create(String name) {
        String sql = "INSERT INTO projects (name) VALUES (?) RETURNING id, name, created_at";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return map(rs);
            }
        } catch (SQLException e) {
            throw new RepositoryException("Failed to create project", e);
        }
    }

    public List<Project> findAll() {
        String sql = "SELECT id, name, created_at FROM projects ORDER BY created_at DESC";
        List<Project> projects = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                projects.add(map(rs));
            }
            return projects;
        } catch (SQLException e) {
            throw new RepositoryException("Failed to list projects", e);
        }
    }

    public Project findById(UUID id) {
        String sql = "SELECT id, name, created_at FROM projects WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id, Types.OTHER);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return map(rs);
            }
        } catch (SQLException e) {
            throw new RepositoryException("Failed to find project " + id, e);
        }
    }

    private Project map(ResultSet rs) throws SQLException {
        return new Project(
                (UUID) rs.getObject("id"),
                rs.getString("name"),
                rs.getTimestamp("created_at").toInstant());
    }
}
