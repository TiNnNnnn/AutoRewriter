package org.autorewriter.meta.schema.postgres.example;

import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.Table;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.autorewriter.common.enums.TableEngine;
import org.autorewriter.meta.schema.CalciteSchemaRegistry;
import org.autorewriter.meta.schema.postgres.PostgresConnectionConfig;
import org.autorewriter.meta.schema.postgres.PostgresConnectionManager;

/**
 * 展示 PostgreSQL schema 集成的示例
 *
 * 此示例展示如何：
 * 1. 配置 PostgreSQL 连接
 * 2. 初始化 schema
 * 3. 查询表的元数据
 *
 * 注意：此示例仅展示 external-meta 模块的功能。
 * SQL 分析功能位于 sql-core 模块，应在更高层模块中使用。
 */
public class PostgresIntegrationExample {

    public static void main(String[] args) {
        try {
            // Example 1: Basic setup
            basicSetup();

            // Example 2: Multiple database configurations
            multipleConfigurations();

            // Cleanup
            cleanup();

        } catch (Exception e) {
            System.err.println("Error occurred: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    /**
     * 示例 1: 基本的 PostgreSQL schema 设置
     */
    public static void basicSetup() {
        System.out.println("=== Example 1: Basic Setup ===\n");

        // Step 1: 创建连接配置
        PostgresConnectionConfig config = PostgresConnectionConfig.builder()
            .host(System.getProperty("pg.host", "localhost"))
            .port(Integer.parseInt(System.getProperty("pg.port", "55555")))
            .database("testdb")
            .schema("public")
            .username(System.getProperty("pg.username", "postgres"))
            .password(System.getProperty("pg.password", "postgres"))
            .build();

        System.out.println("Created configuration for: " + config.getJdbcUrl());

        // Step 2: 在 registry 中初始化 schema
        CalciteSchemaRegistry.initPostgresSchema("example-postgres", config);
        System.out.println("Initialized PostgreSQL schema in registry");

        // Step 3: 获取 schema
        CalciteSchema schema = CalciteSchemaRegistry.getCalciteSchema(TableEngine.POSTGRESQL);
        System.out.println("Retrieved Calcite schema: " + schema.getName());

        // Step 4: 尝试访问一个表（如果存在）
        try {
            Table usersTable = schema.plus().getTable("users");
            if (usersTable != null) {
                System.out.println("\nFound table 'users'");
                RelDataTypeFactory typeFactory = new SqlTypeFactoryImpl(org.apache.calcite.rel.type.RelDataTypeSystem.DEFAULT);
                RelDataType rowType = usersTable.getRowType(typeFactory);
                System.out.println("Row type: " + rowType);
            } else {
                System.out.println("\nTable 'users' not found (this is OK if the table doesn't exist)");
            }
        } catch (Exception e) {
            System.out.println("\nError accessing table: " + e.getMessage());
        }

        System.out.println();
    }

    /**
     * 示例 2: 多数据库配置
     */
    public static void multipleConfigurations() {
        System.out.println("=== Example 2: Multiple Configurations ===\n");

        // 你可以注册多个 PostgreSQL 配置
        PostgresConnectionConfig devConfig = PostgresConnectionConfig.builder()
            .host(System.getProperty("pg.host", "localhost"))
            .port(Integer.parseInt(System.getProperty("pg.port", "55555")))
            .database("dev_db")
            .username(System.getProperty("pg.username", "postgres"))
            .password(System.getProperty("pg.password", "postgres"))
            .build();

        PostgresConnectionConfig prodConfig = PostgresConnectionConfig.builder()
            .host("prod-server")
            .port(Integer.parseInt(System.getProperty("pg.port", "55555")))
            .database("prod_db")
            .username("prod_user")
            .password("prod_pass")
            .build();

        // 注册两个配置（注意：CalciteSchemaRegistry.initPostgresSchema
        // 会覆盖 POSTGRESQL engine 的 schema）
        PostgresConnectionManager.registerConfig("dev", devConfig);
        PostgresConnectionManager.registerConfig("prod", prodConfig);

        System.out.println("Registered multiple configurations");

        // 可以通过重新初始化来切换配置
        CalciteSchemaRegistry.initPostgresSchema("dev", devConfig);
        System.out.println("Using dev configuration");

        // 之后可以切换到 prod
        // CalciteSchemaRegistry.initPostgresSchema("prod", prodConfig);

        System.out.println();
    }

    /**
     * 清理资源
     */
    public static void cleanup() {
        System.out.println("=== Cleanup ===");
        PostgresConnectionManager.clearAll();
        System.out.println("Cleared all configurations");
    }

    /**
     * 用于测试的 SQL 设置脚本示例
     */
    public static String getTestSetupSQL() {
        return "-- Create test database and tables\n" +
            "CREATE DATABASE IF NOT EXISTS testdb;\n" +
            "\n" +
            "\\c testdb\n" +
            "\n" +
            "-- Create users table\n" +
            "CREATE TABLE IF NOT EXISTS users (\n" +
            "    id SERIAL PRIMARY KEY,\n" +
            "    username VARCHAR(50) NOT NULL,\n" +
            "    email VARCHAR(100),\n" +
            "    age INTEGER,\n" +
            "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n" +
            ");\n" +
            "\n" +
            "-- Insert sample data\n" +
            "INSERT INTO users (username, email, age) VALUES\n" +
            "    ('alice', 'alice@example.com', 25),\n" +
            "    ('bob', 'bob@example.com', 30),\n" +
            "    ('charlie', 'charlie@example.com', 22);\n" +
            "\n" +
            "-- Create orders table\n" +
            "CREATE TABLE IF NOT EXISTS orders (\n" +
            "    id SERIAL PRIMARY KEY,\n" +
            "    user_id INTEGER REFERENCES users(id),\n" +
            "    amount DECIMAL(10, 2),\n" +
            "    order_date DATE DEFAULT CURRENT_DATE\n" +
            ");\n" +
            "\n" +
            "-- Insert sample orders\n" +
            "INSERT INTO orders (user_id, amount) VALUES\n" +
            "    (1, 99.99),\n" +
            "    (1, 149.50),\n" +
            "    (2, 79.99);";
    }
}

