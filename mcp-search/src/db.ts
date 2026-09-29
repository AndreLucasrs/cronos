import type { PoolConfig } from "pg";

/**
 * Cronos's Java side (dev.cronos.db.Database) reads CRONOS_DB_URL as a JDBC
 * URL ("jdbc:postgresql://host:port/database") — pg's Pool wants discrete
 * fields instead, so this strips the "jdbc:" prefix and parses the rest as
 * a plain URL to pull host/port/database out of it.
 */
export function parseJdbcUrl(jdbcUrl: string, user: string, password: string): PoolConfig {
  const withoutPrefix = jdbcUrl.replace(/^jdbc:/, "");
  const url = new URL(withoutPrefix);
  return {
    host: url.hostname,
    port: url.port ? Number(url.port) : 5432,
    database: url.pathname.replace(/^\//, ""),
    user,
    password,
  };
}
