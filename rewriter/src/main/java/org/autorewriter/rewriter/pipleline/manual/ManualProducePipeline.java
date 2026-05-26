package org.autorewriter.rewriter.pipleline.manual;

import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.rel2sql.RelToSqlConverter;
import org.autorewriter.rewriter.optimize.costBaseOpt.DistinctAggregateStripper;
import org.autorewriter.rewriter.optimize.costBaseOpt.insub.InSubFilterExpander;
import org.autorewriter.rewriter.optimize.costBaseOpt.insub.InSubFilterSqlConverter;
import org.apache.calcite.rel.rules.JoinCommuteRule;
import org.apache.calcite.rel.rules.ProjectMergeRule;
import org.apache.calcite.rel.rules.SubQueryRemoveRule;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.dialect.AnsiSqlDialect;
import org.autorewriter.rewriter.analyze.RuleAnalysisContext;
import org.autorewriter.rewriter.historical.HistoricalSqlRecord;

import java.util.List;
import org.autorewriter.rewriter.optimize.OptimizeResult;
import org.autorewriter.rewriter.optimize.costBaseOpt.postgres.FilterSplitter;
import org.autorewriter.rewriter.optimize.ruleBaseOpt.RuleBaseOptimizer;
import org.autorewriter.rewriter.optimize.ruleBaseOpt.RuleTemplateSimplifier;
import org.autorewriter.rewriter.optimize.trace.OptimizationTrace;
import org.autorewriter.rewriter.pipleline.ProduceContext;
import org.autorewriter.rewriter.pipleline.ProducePipeline;
import org.autorewriter.rewriter.pipleline.ProduceStage;
import org.autorewriter.rewriter.pipleline.costbase.CostBaseProducePipeline;
import org.autorewriter.rewriter.pipleline.result.ProduceResult;
import org.autorewriter.rewriter.rule.AutoRewriteRule;
import org.autorewriter.sql.analyze.SqlAnalyzer;

@Slf4j
public class ManualProducePipeline extends ProducePipeline {

    /**
     * Optional: when non-null, each completed {@link OptimizationTrace} is forwarded
     * to this consumer (e.g. a GraphModule instance in the {@code graph} module).
     */
    private org.autorewriter.rewriter.optimize.trace.TraceConsumer traceConsumer;

    @Override
    protected ProduceStage lastStage() {
        return ProduceStage.ONLINE;
    }

    /**
     * Inject a {@link org.autorewriter.rewriter.optimize.trace.TraceConsumer} for recording
     * optimization traces. Supports method chaining.
     */
    public ManualProducePipeline withTraceConsumer(
            org.autorewriter.rewriter.optimize.trace.TraceConsumer consumer) {
        this.traceConsumer = consumer;
        return this;
    }

    @Override
    protected ProduceResult runTheLogic(ProduceStage lastStage, ProduceContext context) {
        ProduceResult produceResult = new ProduceResult();

        // create rbo optimizer and register rules
        RuleBaseOptimizer optimizer = new RuleBaseOptimizer();

        long ruleRegStart = System.currentTimeMillis();
        List<RuleAnalysisContext> ruleContexts = context.getRuleAnalysisContexts();

        int validRuleCount = 0;
        for (int i = 0; i < ruleContexts.size(); i++) {
            RuleAnalysisContext ruleContext = ruleContexts.get(i);

            if (ruleContext.isNoOp()) {
                continue;
            }

            RelNode sourceTemplate = InSubFilterExpander.expand(ruleContext.getSourceRelNode());
            sourceTemplate = FilterSplitter.split(sourceTemplate);

            RelNode targetTemplate = InSubFilterExpander.expand(ruleContext.getTargetRelNode());
            targetTemplate = FilterSplitter.split(targetTemplate);

            RuleAnalysisContext expandedContext = new RuleAnalysisContext(
                    sourceTemplate, targetTemplate,
                    ruleContext.getMatchConstraints(), ruleContext.getRewriteConstraints());

            // Register original rule
            Class<? extends RelNode> rootClass = expandedContext.getSourceRelNode().getClass();
            AutoRewriteRule rule = new AutoRewriteRule(
                    RelOptRule.operand(rootClass, RelOptRule.any()),
                    expandedContext,
                    i
            );
            optimizer.addRule(rule);
            validRuleCount ++;

            // Register stripped DISTINCT variant (aligned with CostBaseProducePipeline)
            if (DistinctAggregateStripper.isDistinctAggregate(sourceTemplate)) {
                RuleAnalysisContext strippedContext =
                        DistinctAggregateStripper.stripBoth(expandedContext);
                if (strippedContext.isNoOp()) {
                    continue;
                }
                Class<? extends RelNode> strippedRootClass = strippedContext.getSourceRelNode().getClass();
                AutoRewriteRule strippedRule = new AutoRewriteRule(
                        RelOptRule.operand(strippedRootClass, RelOptRule.any()),
                        strippedContext,
                        i,
                        "_stripped"
                );
                optimizer.addRule(strippedRule);
            }
        }
        produceResult.setRuleRegistrationTimeMs(System.currentTimeMillis() - ruleRegStart);
        log.info("rule registration finished, {} rules, {} valid rules registered in {} ms",
                context.getRuleAnalysisContexts().size(), validRuleCount, produceResult.getRuleRegistrationTimeMs());

        for(HistoricalSqlRecord historicalSqlRecord : context.getQueryId2HistoricalSqlRecord().values()) {
            OptimizeResult optimizeResult = new OptimizeResult(historicalSqlRecord.getSql(), historicalSqlRecord.getQueryId());

            // parse sql and convert to orginal logical plan
            log.info("start to optimize query {}", historicalSqlRecord.getQueryId());
            RelNode relNode = SqlAnalyzer.analyze(historicalSqlRecord.getSql(), context.getComputeEngine()).getRelNode();
            log.info("original query logical plan:\n {}", relNode.explain());

            OptimizationTrace trace = new OptimizationTrace();

            long startTime = System.currentTimeMillis();
            RelNode optimizedRelNode = optimizer.optimize(relNode, trace, true);
            long endTime = System.currentTimeMillis();
            long optimizationTimeInMs = endTime - startTime;

            optimizeResult.setTrace(trace);
            if (traceConsumer != null) {
                traceConsumer.consume(trace);
            }
            if (optimizedRelNode.deepEquals(relNode)) {
                log.info("query {} cannot be optimized by any rule, optimize time: {} ms", historicalSqlRecord.getQueryId(), optimizationTimeInMs);
                optimizeResult.setRewritten(false);
                optimizeResult.setOptimizationTimeInMs(optimizationTimeInMs);
                optimizeResult.setOriginalRelNode(relNode);
            } else {
                log.info("query {} is optimized. {}", historicalSqlRecord.getQueryId(), trace.detailedSummary());
                if (trace.getRawOptimizedPlan() != null) {
                    log.info("raw optimized plan (before filter merge):\n{}", trace.getRawOptimizedPlan().explain());
                }
                log.info("optimized query:\n {}", relNodeToSql(optimizedRelNode));
                optimizeResult.setRewritten(true);
                optimizeResult.setOptimizationTimeInMs(optimizationTimeInMs);
                optimizeResult.setOriginalRelNode(relNode);
                optimizeResult.setOptimizedRelNode(optimizedRelNode);
            }
            produceResult.getOptimizeResults().add(optimizeResult);
            log.info("finish optimize query {}", historicalSqlRecord.getQueryId());
        }
        produceResult.setSuccess(true);
        return produceResult;
    }

    private String relNodeToSql(RelNode relNode) {
        try {
            SqlDialect dialect = AnsiSqlDialect.DEFAULT;
            InSubFilterSqlConverter converter =
                    new InSubFilterSqlConverter(dialect);
            RelToSqlConverter.Result result =
                    converter.visitRoot(relNode);
            SqlNode sqlNode = result.asStatement();
            return sqlNode.toSqlString(dialect).getSql();
        } catch (Exception e) {
            return "Failed to convert to SQL: " + e.getMessage();
        }
    }
}
