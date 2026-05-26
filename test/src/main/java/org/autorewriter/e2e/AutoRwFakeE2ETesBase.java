package org.autorewriter.e2e;

import lombok.extern.slf4j.Slf4j;
import org.autorewriter.SqlTestBase;
import org.autorewriter.common.enums.ComputeEngine;
import org.autorewriter.graph.GraphModule;
import org.autorewriter.graph.model.GraphSummary;
import org.autorewriter.graph.model.RuleDependencyGraph;
import org.autorewriter.rewriter.analyze.RuleAnalysisContext;
import org.autorewriter.rewriter.analyze.RuleAnalyzer;
import org.autorewriter.rewriter.historical.HistoricalSqlRecord;
import org.autorewriter.rewriter.pipleline.ProduceContext;
import org.autorewriter.rewriter.pipleline.ProducePipeline;
import org.autorewriter.rewriter.pipleline.costbase.CostBaseProducePipeline;
import org.autorewriter.rewriter.pipleline.manual.ManualProducePipeline;
import org.autorewriter.rewriter.pipleline.result.ProduceResult;
import org.autorewriter.sql.analyze.PostgresqlSchemaTestBase;
import org.junit.BeforeClass;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.autorewriter.SqlTestBase.createAllTable;

@Slf4j
public class AutoRwFakeE2ETesBase extends PostgresqlSchemaTestBase {

    /** Absolute path to test/src/main/resources/, resolved via ClassLoader at runtime */
    protected static final String RESOURCES_ROOT = resolveResourcesRoot();

    public  final static String RULE_DIR          = RESOURCES_ROOT + "example";
    public  final static String RULE_DIR2          = RESOURCES_ROOT + "example2";
    public  final static String RULE_DIR3          = RESOURCES_ROOT + "example3";
    public  final static String COMMON_TABLE_DDL  = RESOURCES_ROOT + "schema/common_table_ddl.sql";
    public  final static String E2E_TEST_TABLE_DDL = RESOURCES_ROOT + "schema/";
    protected final static String CUSTOM_TABLE_DDL  = "table_ddl.sql";
    protected final static String E2E_TEST_DIR      = RESOURCES_ROOT + "schema/";

    private static String resolveResourcesRoot() {
        try {
            java.net.URL classUrl = AutoRwFakeE2ETesBase.class
                    .getResource(AutoRwFakeE2ETesBase.class.getSimpleName() + ".class");
            if (classUrl == null) {
                throw new IllegalStateException("Cannot locate class file for " +
                        AutoRwFakeE2ETesBase.class.getName());
            }
            Path classPath = Paths.get(classUrl.toURI()).toAbsolutePath();

            // Walk up until we find a directory that contains a "src" child
            Path dir = classPath.getParent();
            while (dir != null && !Files.isDirectory(dir.resolve("src"))) {
                dir = dir.getParent();
            }
            if (dir == null) {
                throw new IllegalStateException("Could not find module root (directory containing 'src') " +
                        "above: " + classPath);
            }

            Path resources = dir.resolve("src/main/resources");
            if (!Files.isDirectory(resources)) {
                throw new IllegalStateException("src/main/resources does not exist under module root: " + dir);
            }
            return resources.toString() + "/";
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("Failed to resolve resources root", e);
        }
    }

    @BeforeClass
    public static void init() {
        SqlTestBase.sqlPlanFixture.withDb("tpcds").withComputeEngine(ComputeEngine.POSTGRESQL);
        createAllTable(COMMON_TABLE_DDL);
    }

    protected void executePipeline(PipelineType pipelineType, String ddlDirname, String ruleDirname) {
        String customDdlFilePath = E2E_TEST_TABLE_DDL + ddlDirname + "/" + CUSTOM_TABLE_DDL;
        Path path = Paths.get(customDdlFilePath);
        boolean exists = Files.exists(path);
        if(exists) {
            createAllTable(customDdlFilePath);
        }

        ProduceContext context = createContext(ddlDirname, ruleDirname);
        ProducePipeline pipeline = createPipeline(pipelineType);

        executePipelineWithContext(pipeline, context);
    }

    /**
     * Execute pipeline with explicit rule strings (instead of reading from files).
     *
     * @param pipelineType CBO or MANUAL
     * @param ddlDirname   schema directory name under E2E_TEST_DIR (e.g. "diaspora")
     * @param rules        list of rule template strings
     * @return ProduceResult containing OptimizeResult for each query
     */
    protected ProduceResult executePipeline(PipelineType pipelineType, String ddlDirname, List<String> rules) {
        String customDdlFilePath = E2E_TEST_TABLE_DDL + ddlDirname + "/" + CUSTOM_TABLE_DDL;
        Path path = Paths.get(customDdlFilePath);
        if (Files.exists(path)) {
            createAllTable(customDdlFilePath);
        }

        // Parse rule strings into RuleAnalysisContexts
        List<RuleAnalysisContext> ruleContexts = new ArrayList<>();
        for (String rule : rules) {
            try {
                RuleAnalysisContext ctx = RuleAnalyzer.analyze(rule);
                if (ctx != null && ctx.getSourceRelNode() != null && ctx.getTargetRelNode() != null) {
                    ruleContexts.add(ctx);
                } else {
                    log.warn("Failed to parse rule (null result): {}", rule.substring(0, Math.min(80, rule.length())));
                }
            } catch (Exception e) {
                log.warn("Failed to parse rule: {}", rule.substring(0, Math.min(80, rule.length())), e);
            }
        }

        // Read queries from ddlDirname/query
        String queryDir = ddlDirname + "/query";
        Path scenarioDir = resolveScenarioDir(queryDir);
        List<String> sqlStatements = readSqlStatements(scenarioDir);

        Map<String, HistoricalSqlRecord> queryId2HistoricalSqlRecord = new LinkedHashMap<>();
        for (int i = 0; i < sqlStatements.size(); i++) {
            String queryId = String.valueOf(i);
            HistoricalSqlRecord record = new HistoricalSqlRecord();
            record.setQueryId(queryId);
            record.setSql(sqlStatements.get(i));
            queryId2HistoricalSqlRecord.put(queryId, record);
        }

        ProduceContext context = new ProduceContext(
                queryId2HistoricalSqlRecord, ruleContexts, ComputeEngine.POSTGRESQL);

        log.info("Context created with {} queries and {} rules (from strings)",
                queryId2HistoricalSqlRecord.size(), ruleContexts.size());

        ProducePipeline pipeline = createPipeline(pipelineType);
        return pipeline.run(context);
    }

    /**
     * Execute MANUAL pipeline with explicit rule strings and a single SQL string.
     * Used by WeTune E2E tests where each query is tested individually.
     */
    protected ProduceResult executePipeline(PipelineType pipelineType, String ddlDirname,
                                             List<String> rules, String sql) {
        String customDdlFilePath = E2E_TEST_TABLE_DDL + ddlDirname + "/" + CUSTOM_TABLE_DDL;
        Path path = Paths.get(customDdlFilePath);
        if (Files.exists(path)) {
            createAllTable(customDdlFilePath);
        }

        List<RuleAnalysisContext> ruleContexts = new ArrayList<>();
        for (String rule : rules) {
            try {
                RuleAnalysisContext ctx = RuleAnalyzer.analyze(rule);
                if (ctx != null && ctx.getSourceRelNode() != null && ctx.getTargetRelNode() != null) {
                    ruleContexts.add(ctx);
                }
            } catch (Exception e) {
                log.warn("Failed to parse rule: {}", rule.substring(0, Math.min(80, rule.length())), e);
            }
        }

        Map<String, HistoricalSqlRecord> queries = new LinkedHashMap<>();
        HistoricalSqlRecord record = new HistoricalSqlRecord();
        record.setQueryId("q0");
        record.setSql(sql);
        queries.put("q0", record);

        ProduceContext context = new ProduceContext(queries, ruleContexts, ComputeEngine.POSTGRESQL);
        ProducePipeline pipeline = createPipeline(pipelineType);
        return pipeline.run(context);
    }

    public ProduceContext createContextPublic(String ddlDirname, String ruleDirname) {
        return createContext(ddlDirname, ruleDirname);
    }

    private ProduceContext createContext(String ddlDirname, String ruleDirname) {
        // read sql statements from query dir
        String queryDir = ddlDirname + "/query";
        Path scenarioDir = resolveScenarioDir(queryDir);
        List<String> sqlStatements = readSqlStatements(scenarioDir);

        Map<String, HistoricalSqlRecord> queryId2HistoricalSqlRecord = new LinkedHashMap<>();
        for (int i = 0; i < sqlStatements.size(); i++) {
            String queryId = String.valueOf(i);
            HistoricalSqlRecord record = new HistoricalSqlRecord();
            record.setQueryId(queryId);
            record.setSql(sqlStatements.get(i));
            queryId2HistoricalSqlRecord.put(queryId, record);
        }

        // read rules — ruleDirname is already an absolute path (e.g. RULE_DIR = RESOURCES_ROOT + "example")
        Path rulePath = Paths.get(ruleDirname);
        List<RuleAnalysisContext> ruleAnalysisContexts = readRuleAnalysisContexts(rulePath);

        log.info("Context created with {} sql statements and {} rules", queryId2HistoricalSqlRecord.size(), ruleAnalysisContexts.size());

        return new ProduceContext(
                queryId2HistoricalSqlRecord,
                ruleAnalysisContexts,
                ComputeEngine.POSTGRESQL);
    }

    private List<RuleAnalysisContext> readRuleAnalysisContexts(Path ruleDir) {
        List<RuleAnalysisContext> contexts = new ArrayList<>();
        try (Stream<Path> stream = Files.list(ruleDir)) {
            List<Path> ruleFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());

            for (Path ruleFile : ruleFiles) {
                try (BufferedReader reader = new BufferedReader(
                        new java.io.InputStreamReader(Files.newInputStream(ruleFile), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    int lineNumber = 0;
                    while ((line = reader.readLine()) != null) {
                        lineNumber++;
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }
                        try {
                            RuleAnalysisContext ctx = RuleAnalyzer.analyze(line);
                            if (ctx != null && ctx.getSourceRelNode() != null && ctx.getTargetRelNode() != null) {
                                RuleAnalysisContext ctxWithLine = new RuleAnalysisContext(
                                        ctx.getSourceRelNode(),
                                        ctx.getTargetRelNode(),
                                        ctx.getMatchConstraints(),
                                        ctx.getRewriteConstraints(),
                                        lineNumber
                                );
                                contexts.add(ctxWithLine);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to parse rule at {}:{} — {}", ruleFile.getFileName(), lineNumber, e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read rule files under " + ruleDir, e);
        }
        return contexts;
    }

    private ProducePipeline createPipeline(PipelineType pipelineType) {
        switch (pipelineType) {
            case MANUAL:
                return new ManualProducePipeline();
            case CBO:
                return new CostBaseProducePipeline();
            default:
                throw new IllegalArgumentException("unsupported pipeline type: " + pipelineType);
        }
    }

    private void executePipelineWithContext(ProducePipeline pipeline, ProduceContext context) {
        try {
            System.out.println("Starting executing pipeline");
            pipeline.run(context);
            System.out.println("Finish executing pipeline");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to execute pipeline", e);
        }
    }

    private List<String> readSqlStatements(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(this::readSqlFromFile)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read sql files under " + directory, e);
        }
    }

    private String readSqlFromFile(Path file) {
        String content = SqlTestBase.readFile(file.toAbsolutePath().toString()).trim();
        if (content.endsWith(";")) {
            content = content.substring(0, content.length() - 1).trim();
        }
        return content;
    }

    public enum PipelineType {
        MANUAL,
        CBO,
        HYBRID
    }

    public RuleDependencyGraph runWithGraph(Path outputDir, String dbName, String ruleDir, PipelineType pipelineType) {
        String prefix = pipelineType.name().toLowerCase() + "-";

        GraphModule graphModule = GraphModule.load(
                outputDir.resolve(prefix + "rule-dependency-graph.json"), 0);

        ProduceContext context = createContextPublic(dbName, ruleDir);

        if (pipelineType == PipelineType.CBO) {
            new CostBaseProducePipeline()
                    .withTraceConsumer(graphModule)
                    .run(context);
        } else {
            new ManualProducePipeline()
                    .withTraceConsumer(graphModule)
                    .run(context);
        }

        graphModule.export(outputDir.resolve(prefix + "gnn-input"));
        graphModule.visualizeDot(outputDir.resolve(prefix + "rule-dependency-graph.dot"));
        graphModule.visualize(outputDir.resolve(prefix + "rule-dependency-graph.png"));

        RuleDependencyGraph graph = graphModule.buildGraph();
        System.out.printf("[Graph/%s] %d nodes, %d edges → %s%n",
                pipelineType.name(), graph.nodeCount(), graph.edgeCount(),
                outputDir.toAbsolutePath());

        System.out.println(new GraphSummary(graph).report(30));
        return graph;
    }

    private Path resolveScenarioDir(String dirName) {
        // dirName is relative, e.g. "tpcds/query" → RESOURCES_ROOT + "schema/tpcds/query"
        Path directory = Paths.get(E2E_TEST_DIR, dirName).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Scenario directory does not exist: " + directory);
        }
        return directory;
    }
}
