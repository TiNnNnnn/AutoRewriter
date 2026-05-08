package org.autorewriter.meta.schema.postgres;

import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.schema.Table;
import org.autorewriter.common.enums.TableEngine;
import org.autorewriter.meta.schema.CalciteSchemaRegistry;
import org.junit.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for PostgreSQL schema integration via JDBC
 *
 * To run this test, you need a running PostgreSQL instance.
 * Update the connection details below to match your setup.
 */
public class PostgresSchemaIntegrationTest {

    private static final String CONFIG_NAME = "test-postgres";

    @BeforeAll
    public static void setup() {
        // Configure PostgreSQL connection
        // Update these values to match your PostgreSQL setup, or pass -Dpg.host / -Dpg.port / etc.
        PostgresConnectionConfig config = PostgresConnectionConfig.builder()
            .host(System.getProperty("pg.host", "localhost"))
            .port(Integer.parseInt(System.getProperty("pg.port", "55555")))
            .database("testdb")
            .schema("public")
            .username(System.getProperty("pg.username", "postgres"))
            .password(System.getProperty("pg.password", "postgres"))
            .build();

        // Initialize PostgreSQL schema
        CalciteSchemaRegistry.initPostgresSchema(CONFIG_NAME, config);
    }

    @AfterAll
    public static void cleanup() {
        PostgresConnectionManager.removeConfig(CONFIG_NAME);
    }

    @Test
    public void testGetSchema() {
        CalciteSchema schema = CalciteSchemaRegistry.getCalciteSchema(TableEngine.POSTGRESQL);
        assertNotNull(schema);
    }

    @Test
    public void testGetTable() throws Exception {
        // This test assumes a table named 'users' exists in your PostgreSQL database
        // Create a test table first:
        // CREATE TABLE users (
        //     id SERIAL PRIMARY KEY,
        //     username VARCHAR(50) NOT NULL,
        //     email VARCHAR(100),
        //     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        // );

        CalciteSchema schema = CalciteSchemaRegistry.getCalciteSchema(TableEngine.POSTGRESQL);
        Table table = schema.plus().getTable("users");

        // If the table exists, it should not be null
        // If it doesn't exist, this will be null
        // Uncomment the assertion below if you have the table
        // assertNotNull(table, "Table 'users' should exist");
    }

    @Test
    public void testMetadataReader() throws Exception {
        PostgresMetadataReader reader = new PostgresMetadataReader(CONFIG_NAME);

        // List all tables (should work even if empty)
        var tables = reader.listTables(java.util.Collections.emptyList());
        assertNotNull(tables);
        System.out.println("Found " + tables.size() + " tables");
        tables.forEach(System.out::println);
    }
}

