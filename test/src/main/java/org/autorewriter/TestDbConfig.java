package org.autorewriter;

/**
 * Centralized PostgreSQL test database configuration.
 *
 * All values can be overridden via System Properties (-D flags):
 * <pre>
 *   mvn test -Dpg.host=192.168.1.100 -Dpg.port=5432 -Dpg.username=myuser -Dpg.password=mypass
 * </pre>
 *
 * Default values match the current development setup (localhost:55555, postgres/postgres).
 */
public final class TestDbConfig {

    public static final String HOST = System.getProperty("pg.host", "localhost");
    public static final int PORT = Integer.parseInt(System.getProperty("pg.port", "44444"));
    public static final String USERNAME = System.getProperty("pg.username", "postgres");
    public static final String PASSWORD = System.getProperty("pg.password", "postgres");

    /**
     * Build a JDBC URL for the given database name.
     *
     * @param database the database name
     * @return JDBC URL, e.g. "jdbc:postgresql://localhost:55555/mydb"
     */
    public static String getJdbcUrl(String database) {
        return String.format("jdbc:postgresql://%s:%d/%s", HOST, PORT, database);
    }

    private TestDbConfig() {
        // utility class
    }
}
