package org.autorewriter.rewriter.optimize.trace;

import lombok.Getter;
import lombok.Setter;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;

/**
 * Records a single successful rule application during optimization.
 */
@Getter
public class RuleApplicationStep {

    /** 1-based index in the overall rule-fire sequence */
    private final int stepIndex;

    /** The rule that successfully fired */
    private final RelOptRule rule;

    /**
     * The RelNode that was matched by the rule (call.rel(0)).
     * In VolcanoPlanner this may be a RelSubset (equivalence class);
     * in HepPlanner it is a concrete RelNode.
     */
    private final RelNode matchedRelNode;

    /**
     * The RelNode produced by the rule (event.getRel() in ruleProductionSucceeded).
     * This is the new subtree root AFTER the transformation.
     */
    private final RelNode producedRelNode;

    /**
     * ID of the RelSet (equivalence class) that contains the matched RelSubset.
     * Set to -1 when the matched node cannot be resolved to a RelSubset (e.g.
     * HepPlanner, or when reflection fails).
     *
     * <p>This is the primary CBO causality signal: combined with
     * {@link #matchedChildSubsetIds}, it lets the graph builder reconstruct
     * the structural parent/child relationships between rule applications.
     */
    private final int matchedSubsetId;

    /**
     * ID of the RelSet into which the produced node was registered.
     * Set to -1 when unknown.
     *
     * <p><b>Note (Volcano semantics):</b> {@code transformTo} calls
     * {@code ensureRegistered(produced, matched)}, which forces {@code produced}
     * into the SAME RelSet as {@code matched}. Therefore in CBO traces this
     * value almost always equals {@link #matchedSubsetId} and is NOT a useful
     * linkage signal between rule applications. It is preserved for diagnostics
     * and for HepPlanner-style traces where the relationship may differ.
     */
    @Setter
    private int producedIntoSubsetId = -1;

    /**
     * RelSet IDs of the produced node's direct input subsets (Volcano only).
     *
     * <p>Diagnostic field: in practice, the {@code produced} RelNode delivered
     * to {@code ruleProductionSucceeded} is the raw rel BEFORE Volcano wraps
     * its inputs in RelSubsets, so this list is usually empty. Kept for
     * symmetry with {@link #matchedChildSubsetIds} and for potential use on
     * planner versions that deliver post-registration produced nodes.
     */
    @Setter
    private java.util.List<Integer> producedInputSubsetIds = java.util.Collections.emptyList();

    /**
     * RelSet IDs of the matched RelSet's structural children (Volcano only).
     *
     * <p>A RelSet's children are the RelSets whose RelSubsets appear as inputs
     * of any registered rel in this set — i.e. the structural children in the
     * query-plan tree. When rule A matches on setX with children {Y₁, Y₂, ...},
     * any rule B that matches on Yᵢ is operating on a sub-tree of A's domain
     * and should appear downstream of A in the dependency graph.
     *
     * <p>This is the primary structural-causality signal in CBO traces because
     * (a) {@link #matchedSubsetId} is always populated (unlike
     * {@link #producedIntoSubsetId}, which Volcano collapses into
     * {@code matchedSubsetId}); and (b) {@code RelSet.getChildSets} reliably
     * captures the plan-tree structure even when individual rel inputs are
     * not RelSubsets at the moment the listener fires.
     *
     * <p><b>Caveat:</b> RelSet child relationships are not guaranteed acyclic
     * after equivalence-set merging in Volcano, so the graph built from this
     * field may contain cycles. Downstream consumers must tolerate that.
     */
    @Setter
    private java.util.List<Integer> matchedChildSubsetIds = java.util.Collections.emptyList();

    /**
     * Snapshot of the full plan (planner root explain()) after this rule fired.
     * Only populated when the listener has access to the planner.
     */
    @Setter
    private String fullPlanAfterStep;

    public RuleApplicationStep(int stepIndex,
                               RelOptRule rule,
                               RelNode matchedRelNode,
                               RelNode producedRelNode) {
        this(stepIndex, rule, matchedRelNode, producedRelNode, -1);
    }

    public RuleApplicationStep(int stepIndex,
                               RelOptRule rule,
                               RelNode matchedRelNode,
                               RelNode producedRelNode,
                               int matchedSubsetId) {
        this.stepIndex      = stepIndex;
        this.rule           = rule;
        this.matchedRelNode = matchedRelNode;
        this.producedRelNode = producedRelNode;
        this.matchedSubsetId = matchedSubsetId;
    }

    @Override
    public String toString() {
        String ruleType = (rule instanceof ConverterRule) ? "Conversion" : "Logical";
        return String.format("[Step %d] [%s] %s | %s => %s",
                stepIndex,
                ruleType,
                rule.toString(),
                matchedRelNode.getRelTypeName(),
                producedRelNode.getRelTypeName());
    }
}

