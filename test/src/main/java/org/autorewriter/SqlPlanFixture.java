package org.autorewriter;

import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.rel2sql.RelToSqlConverter;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.SqlString;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.autorewriter.common.enums.ComputeEngine;
import org.autorewriter.common.enums.TableEngine;
import org.autorewriter.meta.schema.CalciteSchemaRegistry;
import org.autorewriter.meta.schema.KwaiCalciteSchema;
import org.autorewriter.meta.schema.ProxySchema;
import org.autorewriter.meta.schema.postgres.PostgresJdbcSchemaService;
import org.autorewriter.sql.analyze.AnalysisContext;
import org.autorewriter.sql.analyze.SqlAnalyzer;

import javax.sql.DataSource;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.autorewriter.common.constant.NotationConstants.EMPTY_STRING;
import static org.autorewriter.sql.analyze.AnalysisConfigs.COMMON_CALCITE_CONNECTION_CONFIG;
import static org.junit.Assert.assertEquals;

/**
 * SQL Plan Fixture， For Testing
 */
@Slf4j
public class SqlPlanFixture {

    private static final String dbStorePath = "./target/postgres-test/";
    private static final String driverClassName = "org.postgresql.Driver";

    private ComputeEngine computeEngine = ComputeEngine.POSTGRESQL;
    protected String db = "test";
    protected List<String> createTableSQLs = new ArrayList<>();
    private String sql;
    protected CalciteCatalogReader catalogReader;
    private RelDataTypeSystem typeSystem = RelDataTypeSystem.DEFAULT;
    protected RelDataTypeFactory typeFactory = new SqlTypeFactoryImpl(typeSystem);
    private RelNode plan;
    private Planner planner;

    public SqlPlanFixture() {
//        catalogReader = new CalciteCatalogReader(CalciteSchema.from(getPostgresDataSourceSchema()),
//                new ArrayList<>(), typeFactory, COMMON_CALCITE_CONNECTION_CONFIG);
    }

    public SqlPlanFixture(SqlPlanFixture other) {
        this.computeEngine = other.computeEngine;
        this.db = other.db;
        this.createTableSQLs = other.createTableSQLs;
        this.sql = other.sql;
        this.typeSystem = other.typeSystem;
        this.typeFactory = other.typeFactory;
        this.catalogReader = other.catalogReader;
        this.planner = other.planner;
        this.plan = other.plan;
    }

    protected SqlPlanFixture createNewInstance() {
        try {
            Constructor<? extends SqlPlanFixture> declaredConstructor =
                    this.getClass().getConstructor(this.getClass());
            return declaredConstructor.newInstance(this);
        } catch (NoSuchMethodException | InvocationTargetException |
                 InstantiationException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public SqlPlanFixture withComputeEngine(ComputeEngine computeEngine) {
        SqlPlanFixture fixture = createNewInstance();
        fixture.computeEngine = computeEngine;
        fixture.catalogReader = new CalciteCatalogReader(
                    CalciteSchema.from(getCalciteSchema(fixture)),
                    new ArrayList<>(),
                    typeFactory,
                    COMMON_CALCITE_CONNECTION_CONFIG);
        return fixture;
    }

    public SqlPlanFixture withDb(String database) {
        SqlPlanFixture fixture = createNewInstance();
        fixture.db = database;
        return fixture;
    }

    public SqlPlanFixture withCreateTable(String createTableSQL) {
        return withMultipleCreateTables(List.of(createTableSQL));
    }

    public SqlPlanFixture withMultipleCreateTables(List<String> createTableSQLs) {
        SqlPlanFixture fixture = createNewInstance();
        fixture.createTableSQLs = createTableSQLs;
        return fixture;
    }

    public SqlPlanFixture withSql(String sql) {
        SqlPlanFixture fixture = createNewInstance();
        fixture.sql = sql;
        return fixture;
    }

    public SqlPlanFixture plan() throws Exception {
        AnalysisContext analysisContext = SqlAnalyzer.analyze(this.sql, this.computeEngine);
        this.planner = analysisContext.getPlanner();
        this.plan = analysisContext.getRelNode();
        return this;
    }

    public SqlPlanFixture plan(String expectedPlan) throws Exception {
        if (this.sql == null) {
            throw new IllegalArgumentException("withSql must be invoked.");
        }
        createNewTable();
        plan();
        assertEquals(expectedPlan, this.plan.explain());
        return this;
    }

    private SchemaPlus getCalciteSchema(SqlPlanFixture fixture) {
        switch (fixture.computeEngine) {
            case POSTGRESQL:
                return getPostgresDataSourceSchema();
            default:
                throw new UnsupportedOperationException("unsupported compute engine " + fixture.computeEngine.name() + " in getCalciteSchema");
        }
    }

    private SchemaPlus getPostgresDataSourceSchema() {
        try {
            Object field = FieldUtils.readDeclaredStaticField(
                    CalciteSchemaRegistry.class, "ENGINE_SCHEMA_MAP", true);
            @SuppressWarnings("unchecked")
            Map<TableEngine, CalciteSchema> engineSchemaMap = (Map<TableEngine, CalciteSchema>) field;

            log.info("Creating new PostgreSQL schema: {}", getUrl());
            String url = getUrl();
            DataSource dataSource = JdbcSchema.dataSource(url, driverClassName, TestDbConfig.USERNAME, TestDbConfig.PASSWORD);
            PostgresJdbcSchemaService postgresSchemaService = new PostgresJdbcSchemaService(dataSource, typeFactory);
            // parents = [catalog=db, schema=public]
            ProxySchema proxySchema = new ProxySchema(List.of(this.db, "public"), postgresSchemaService);
            KwaiCalciteSchema kwaiCalciteSchema = new KwaiCalciteSchema(null, proxySchema, EMPTY_STRING);

            engineSchemaMap.put(TableEngine.POSTGRESQL, kwaiCalciteSchema);
            return kwaiCalciteSchema.plus();
        } catch (IllegalAccessException e) {
            throw new RuntimeException("access postgres schema error", e);
        }
    }

    private String getUrl() {
        return TestDbConfig.getJdbcUrl(this.db);
    }

    private TableEngine mapComputeEngineToTableEngine(ComputeEngine computeEngine) {
        switch (computeEngine) {
            case POSTGRESQL:
                return TableEngine.POSTGRESQL;
            default:
                // 默认使用 POSTGRESQL
                return TableEngine.POSTGRESQL;
        }
    }

    public void planSql(SqlDialect sqlDialect, String expectedSql) {
        if (this.plan == null) {
            throw new IllegalArgumentException("plan() must be invoked.");
        }
        String bestPlanSql = toSql(plan, sqlDialect);
        assertEquals(expectedSql, bestPlanSql);
    }

    public String toSql(RelNode relNode, SqlDialect sqlDialect) {
        RelToSqlConverter relToSqlConverter = new RelToSqlConverter(sqlDialect);
        RelToSqlConverter.Result result = relToSqlConverter.visitRoot(relNode);
        SqlNode statement = result.asStatement();
        SqlString sqlString = statement.toSqlString(sqlDialect);
        return sqlString.getSql();
    }

    public RelNode getPlan() {
        if (this.plan == null) {
            throw new IllegalStateException("plan hasn't been created");
        }
        return this.plan;
    }

    public void createNewTable() throws SQLException {
        if (this.createTableSQLs == null || this.createTableSQLs.isEmpty()) {
            return;
        }

        switch (computeEngine) {
            case POSTGRESQL:
                createNewPostgresTables();
                break;
            default:
                throw new UnsupportedOperationException("unsupported engine " + computeEngine.name());
        }
    }

    protected void createNewPostgresTables() throws SQLException {
        ensureDatabaseExists(this.db);
        String url = getUrl();
        int successCount = 0;
        try (Connection conn = DriverManager.getConnection(url, TestDbConfig.USERNAME, TestDbConfig.PASSWORD)) {
            try (Statement statement = conn.createStatement()) {
                for (String createTableSQL : createTableSQLs) {
                    //log.info("create table with sql:\n {}", createTableSQL);
                    statement.execute(createTableSQL);
                    successCount ++;
                }
            }
            log.info("Successfully created {} tables in PostgreSQL database", successCount);
        } catch (SQLException e) {
            throw new SQLException("Failed to connect to PostgreSQL instance", e);
        }
    }

    private void ensureDatabaseExists(String dbName) throws SQLException {
        String maintenanceUrl = TestDbConfig.getJdbcUrl("postgres");
        try (Connection conn = DriverManager.getConnection(maintenanceUrl, TestDbConfig.USERNAME, TestDbConfig.PASSWORD);
             Statement stmt = conn.createStatement()) {
            java.sql.ResultSet rs = stmt.executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname = '" + dbName + "'");
            if (!rs.next()) {
                stmt.execute("CREATE DATABASE \"" + dbName + "\"");
                log.info("Created PostgreSQL database: {}", dbName);
            }
        }
    }
}

