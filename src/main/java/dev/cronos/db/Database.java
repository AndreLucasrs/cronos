package dev.cronos.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.cronos.config.Env;

import javax.sql.DataSource;

public final class Database {

    private Database() {
    }

    public static DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(Env.get("CRONOS_DB_URL", "jdbc:postgresql://localhost:5433/cronos"));
        config.setUsername(Env.get("CRONOS_DB_USER", "cronos"));
        config.setPassword(Env.get("CRONOS_DB_PASSWORD", "cronos"));
        config.setMaximumPoolSize(10);
        return new HikariDataSource(config);
    }
}
