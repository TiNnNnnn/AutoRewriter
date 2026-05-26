package org.autorewriter.graph.builder;

import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.rel.RelNode;
import org.autorewriter.graph.model.DependencyEdge;
import org.autorewriter.graph.model.RuleDependencyGraph;
import org.autorewriter.graph.model.RuleNode;
import org.autorewriter.rewriter.optimize.trace.OptimizationTrace;
import org.autorewriter.rewriter.optimize.trace.RuleApplicationStep;
import org.autorewriter.rewriter.rule.AutoRewriteRule;

import java.util.*;

/**
 * Incrementally builds a {@link RuleDependencyGraph} from {@link OptimizationTrace} records.
 *
 * <p>Graph nodes are keyed by {@code "ruleId:matchedNodeSignature"}, so the same rule
 * fired at different query sub-plan positions appears as distinct nodes.
 *
 * <p>Edge construction is planner-aware:
 * <ul>
 *   <li><b>RBO (HepPlanner):</b> rules fire sequentially on the evolving plan
 *       tree. Edges connect adjacent distinct steps to form a linear chain.</li>
 *   <li><b>CBO (VolcanoPlanner):</b> rules fire on RelSubsets in equivalence
 *       classes (RelSets), out of plan order. Edges are built from the
 *       <b>structural parent/child relationships of RelSets</b>: when rule A
 *       matches setX whose structural children include setY (via
 *       {@code RelSet.getChildSets}), and rule B matches setY, then A → B.
 *       This produces a DAG-like graph reflecting the query plan tree, not
 *       the temporal trace order.</li>
 * </ul>
 *
 * <p>Planner type is detected from the matched-node signature: CBO traces
 * contain "RelSubset" (Volcano wraps inputs in RelSubset).
 *
 * <p><b>Caveat:</b> CBO graphs are NOT guaranteed acyclic. Volcano's
 * equivalence-set merging can create cycles in the RelSet structural-child
 * relation. Downstream consumers (visualizer, GNN exporter) must tolerate
 * cycles.
 */
@Slf4j
public class RuleGraphBuilder {

    /** nodeKey → accumulated observation count */
    private final Map<String, Integer> observationCounts = new HashMap<>();

    /** (fromNodeKey, toNodeKey) → accumulated fire count */
    private final Map<String, Integer> edgeFireCounts = new HashMap<>();

    /** (fromNodeKey, toNodeKey) → accumulated total benefit */
    private final Map<String, Double> edgeTotalBenefits = new HashMap<>();

    /** nodeKey → RuleNode metadata (populated on first observation) */
    private final Map<String, RuleNode> nodeMetadata = new HashMap<>();

    public void record(OptimizationTrace trace) {
        List<RuleApplicationStep> autoSteps = new ArrayList<>();
        for (RuleApplicationStep step : trace.getSteps()) {
            if (step.getRule() instanceof AutoRewriteRule) {
                autoSteps.add(step);
            }
        }
        if (autoSteps.isEmpty()) return;

        // Update node observation counts and rank for every step
        for (int i = 0; i < autoSteps.size(); i++) {
            RuleApplicationStep step = autoSteps.get(i);
            String nodeKey = nodeKeyOf(step);
            observationCounts.merge(nodeKey, 1, Integer::sum);
            if (!nodeMetadata.containsKey(nodeKey)) {
                nodeMetadata.put(nodeKey, buildRuleNode(step, nodeKey));
            }
            // Update rank: use minimum position across all traces
            int currentRank = nodeMetadata.get(nodeKey).getRank();
            if (currentRank < 0 || i < currentRank) {
                nodeMetadata.get(nodeKey).setRank(i);
            }
        }

        // Detect planner type from the matched node's signature:
        // CBO (VolcanoPlanner) wraps nodes in RelSubset, so the signature contains "RelSubset".
        // RBO (HepPlanner) uses HepRelVertex or concrete RelNodes.
        boolean isCbo = autoSteps.stream()
                .anyMatch(s -> signatureOf(s.getMatchedRelNode()).contains("RelSubset"));

        if (isCbo) {
            buildCboEdges(autoSteps);
        } else {
            // RBO: deduplicate by nodeKey for linear chain
            List<String> distinctKeys = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (RuleApplicationStep step : autoSteps) {
                String key = nodeKeyOf(step);
                if (seen.add(key)) {
                    distinctKeys.add(key);
                }
            }
            buildRboEdges(distinctKeys);
        }
    }

    /**
     * RBO (HepPlanner): rules fire strictly sequentially on the evolving plan tree.
     * Connect only adjacent steps to form a linear chain: A → B → C → D
     */
    private void buildRboEdges(List<String> distinctKeys) {
        for (int i = 0; i < distinctKeys.size() - 1; i++) {
            addEdge(distinctKeys.get(i), distinctKeys.get(i + 1));
        }
    }

    /**
     * CBO (VolcanoPlanner): build edges by structural causality.
     *
     * <p><b>Why not {@code producedIntoSubsetId == matchedSubsetId} linkage?</b>
     * Volcano registers each produced node into the SAME RelSet as the matched
     * node ({@code transformTo} → {@code ensureRegistered(produced, matched)}).
     * So {@code producedIntoSubsetId} always equals {@code matchedSubsetId}
     * and that pair cannot distinguish causally-linked rules from unrelated
     * ones in the same equivalence class.
     *
     * <p><b>Primary strategy: RelSet structural-child linkage.</b>
     * Each step records the structural children of its matched RelSet (via
     * {@code RelSet.getChildSets}). For each step A matching setX with
     * children {Y₁, Y₂, …}, every step B that matched on some Yᵢ is a
     * downstream consumer in the plan tree → edge A → B. This reflects the
     * plan-tree structure rather than temporal trace order, which is
     * appropriate for CBO since Volcano explores rules out of plan order.
     *
     * <p><b>Fallback 1: RelNode-ID linkage</b>
     * ({@code producedRelNode.getId() == matchedRelNode.getId()}). Used when
     * the structural-child signal is unavailable (e.g. older trace data).
     *
     * <p><b>Fallback 2: temporal sequential chain</b> (RBO-style) — preserves
     * ordering when no structural information is available at all.
     *
     * <p><b>Note:</b> Strategy 1 may produce cycles because Volcano's RelSet
     * merging can create reciprocal child relations. The downstream visualizer
     * and exporter handle cycles correctly.
     */
    private void buildCboEdges(List<RuleApplicationStep> allAutoSteps) {
        // Index every step by the RelSet it matched on (only steps with a valid id).
        Map<Integer, List<RuleApplicationStep>> stepsByMatchedSubset = new HashMap<>();
        for (RuleApplicationStep step : allAutoSteps) {
            int mid = step.getMatchedSubsetId();
            if (mid < 0) continue;
            stepsByMatchedSubset.computeIfAbsent(mid, k -> new ArrayList<>()).add(step);
        }

        Set<String> addedEdges = new HashSet<>();

        // Strategy 1: structural child-set linkage.
        boolean hasChildSets = allAutoSteps.stream()
                .anyMatch(s -> s.getMatchedChildSubsetIds() != null
                        && !s.getMatchedChildSubsetIds().isEmpty());

        if (hasChildSets) {
            for (RuleApplicationStep producer : allAutoSteps) {
                List<Integer> childIds = producer.getMatchedChildSubsetIds();
                if (childIds == null || childIds.isEmpty()) continue;
                String fromKey = nodeKeyOf(producer);
                for (int childId : childIds) {
                    List<RuleApplicationStep> consumers = stepsByMatchedSubset.get(childId);
                    if (consumers == null) continue;
                    for (RuleApplicationStep consumer : consumers) {
                        String toKey = nodeKeyOf(consumer);
                        if (fromKey.equals(toKey)) continue;
                        if (addedEdges.add(fromKey + "|" + toKey)) {
                            addEdge(fromKey, toKey);
                        }
                    }
                }
            }
            if (!addedEdges.isEmpty()) return;
        }

        // Fallback 1: RelNode ID linkage (producedId == matchedId)
        Map<Integer, Set<String>> producersByNodeId = new HashMap<>();
        for (RuleApplicationStep step : allAutoSteps) {
            int producedId = step.getProducedRelNode().getId();
            producersByNodeId
                    .computeIfAbsent(producedId, k -> new HashSet<>())
                    .add(nodeKeyOf(step));
        }
        boolean anyEdge = false;
        for (RuleApplicationStep step : allAutoSteps) {
            int matchedId = step.getMatchedRelNode().getId();
            Set<String> producers = producersByNodeId.get(matchedId);
            if (producers == null) continue;
            String toKey = nodeKeyOf(step);
            for (String fromKey : producers) {
                if (!fromKey.equals(toKey) && addedEdges.add(fromKey + "|" + toKey)) {
                    addEdge(fromKey, toKey);
                    anyEdge = true;
                }
            }
        }
        if (anyEdge) return;

        // Fallback 2: sequential chain by temporal order
        List<String> distinctKeys = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RuleApplicationStep step : allAutoSteps) {
            String key = nodeKeyOf(step);
            if (seen.add(key)) {
                distinctKeys.add(key);
            }
        }
        buildRboEdges(distinctKeys);
    }

    private void addEdge(String fromKey, String toKey) {
        String edgeKey = fromKey + "->" + toKey;
        edgeFireCounts.merge(edgeKey, 1, Integer::sum);
        edgeTotalBenefits.merge(edgeKey, 0.0, Double::sum);
    }

    public RuleDependencyGraph build() {
        Map<String, RuleNode> nodes = new HashMap<>(nodeMetadata);
        // Update observation counts from accumulated state
        for (Map.Entry<String, Integer> entry : observationCounts.entrySet()) {
            RuleNode existing = nodes.get(entry.getKey());
            if (existing != null) {
                RuleNode updated = new RuleNode(
                        existing.getNodeKey(),
                        existing.getRuleId(),
                        existing.getSourceTemplateSignature(),
                        existing.getTargetTemplateSignature(),
                        existing.getMatchedNodeSignature(),
                        entry.getValue());
                updated.setRank(existing.getRank());  // preserve rank
                nodes.put(entry.getKey(), updated);
            }
        }

        Map<String, List<DependencyEdge>> outEdges = new HashMap<>();
        for (Map.Entry<String, Integer> entry : edgeFireCounts.entrySet()) {
            String edgeKey = entry.getKey();
            int arrowIdx = edgeKey.indexOf("->");
            String fromKey = edgeKey.substring(0, arrowIdx);
            String toKey   = edgeKey.substring(arrowIdx + 2);
            int count = entry.getValue();
            double totalBenefit = edgeTotalBenefits.getOrDefault(edgeKey, 0.0);
            outEdges.computeIfAbsent(fromKey, k -> new ArrayList<>())
                    .add(new DependencyEdge(fromKey, toKey, count, totalBenefit));
        }

        return new RuleDependencyGraph(nodes, outEdges);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Node key = "ruleId:matchedNodeSignature" */
    private String nodeKeyOf(RuleApplicationStep step) {
        AutoRewriteRule rule = (AutoRewriteRule) step.getRule();
        String matchedSig = signatureOf(step.getMatchedRelNode());
        return RuleNode.keyOf(rule.getRuleId(), matchedSig);
    }

    private RuleNode buildRuleNode(RuleApplicationStep step, String nodeKey) {
        AutoRewriteRule rule = (AutoRewriteRule) step.getRule();
        return new RuleNode(
                nodeKey,
                rule.getRuleId(),
                signatureOf(rule.getSourceTemplate()),
                signatureOf(rule.getTargetTemplate()),
                signatureOf(step.getMatchedRelNode()),
                0);
    }

    private String signatureOf(RelNode node) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        appendSignature(node, sb);
        return sb.toString();
    }

    private void appendSignature(RelNode node, StringBuilder sb) {
        if (sb.length() > 0) sb.append('-');
        sb.append(node.getRelTypeName());
        for (RelNode child : node.getInputs()) {
            appendSignature(child, sb);
        }
    }
}
