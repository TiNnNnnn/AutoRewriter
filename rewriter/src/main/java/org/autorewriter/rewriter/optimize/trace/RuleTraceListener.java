package org.autorewriter.rewriter.optimize.trace;

import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.plan.RelOptListener;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.volcano.RelSubset;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.rel.RelNode;

/**
 * {@link RelOptListener} implementation that records every successful rule
 * application during optimization into an {@link OptimizationTrace}.
 *
 * <p>Works with both {@link HepPlanner} and {@link VolcanoPlanner}. For
 * VolcanoPlanner, it captures structural metadata used by the graph module
 * to reconstruct rule-application causality:
 * <ul>
 *   <li>{@code matchedSubsetId}: RelSet.id of the equivalence class the rule
 *       matched on. Resolved via {@link VolcanoPlanner#getSubset(RelNode)}
 *       since Volcano unwraps RelSubsets before invoking rules
 *       ({@code call.rel(0)} is the concrete inner rel, not the RelSubset).</li>
 *   <li>{@code matchedChildSubsetIds}: structural children of the matched
 *       RelSet (via {@code RelSet.getChildSets}). When rule A matches setX
 *       with children {Y₁, Y₂, …}, any rule B matching one of Yᵢ is operating
 *       on a sub-tree of A's domain ⇒ structural edge A → B. This is the
 *       primary signal the graph builder uses to construct DAG-like
 *       dependencies.</li>
 *   <li>{@code producedIntoSubsetId}: kept for diagnostics. Volcano's
 *       {@code transformTo} calls {@code ensureRegistered(produced, matched)},
 *       so this almost always equals {@code matchedSubsetId} and is not a
 *       useful linkage signal on its own.</li>
 * </ul>
 *
 * <p>Reflection is used to access the package-private {@code RelSubset.set}
 * field, the package-private {@code RelSet} class (and its {@code id} field
 * and {@code getChildSets} method), and the private {@code mapDigestToRel}
 * field on VolcanoPlanner. All reflection failures degrade to {@code -1} or
 * an empty list — they never throw.
 */
@Slf4j
public class RuleTraceListener implements RelOptListener {

    private final OptimizationTrace trace;
    private final HepPlanner        hepPlanner;
    private final VolcanoPlanner    volcanoPlanner;
    private int stepCounter = 0;

    /** Per-trace cache of {@code matchedSubsetId → child RelSet ids}. Avoids
     * repeated reflection on hot RelSets when the same set is matched many
     * times. Cleared implicitly when this listener is discarded. */
    private final java.util.Map<Integer, java.util.List<Integer>> childSetIdsCache =
            new java.util.HashMap<>();

    /** HepPlanner constructor (legacy). */
    public RuleTraceListener(OptimizationTrace trace) {
        this(trace, (HepPlanner) null);
    }

    /** HepPlanner constructor with plan snapshot support. */
    public RuleTraceListener(OptimizationTrace trace, HepPlanner planner) {
        this.trace          = trace;
        this.hepPlanner     = planner;
        this.volcanoPlanner = null;
    }

    /** VolcanoPlanner constructor — enables subset ID tracking. */
    public RuleTraceListener(OptimizationTrace trace, VolcanoPlanner planner) {
        this.trace          = trace;
        this.hepPlanner     = null;
        this.volcanoPlanner = planner;
    }

    @Override
    public void ruleAttempted(RuleAttemptedEvent event) {
        if (event.isBefore()) {
            RelOptRuleCall call = event.getRuleCall();
            log.debug("[ATTEMPT] Rule: {}  on: {}",
                    call.getRule(), call.rel(0).getRelTypeName());
        }
    }

    @Override
    public void ruleProductionSucceeded(RuleProductionEvent event) {
        if (!event.isBefore()) {
            RelOptRuleCall call     = event.getRuleCall();
            RelNode        matched  = call.rel(0);
            RelNode        produced = event.getRel();
            stepCounter++;

            // --- matchedSubsetId: RelSet.id of the matched equivalence class ---
            //
            // NOTE: In VolcanoPlanner, call.rel(0) is the unwrapped concrete RelNode,
            // NOT a RelSubset (the planner unwraps the subset before invoking rules).
            // To recover the subset, we look it up via VolcanoPlanner.getSubset(rel),
            // which is a public API on the fork. The legacy `instanceof RelSubset`
            // branch is kept as a defensive fallback for non-Volcano contexts.
            int matchedSubsetId = -1;
            if (matched instanceof RelSubset) {
                matchedSubsetId = getRelSetId((RelSubset) matched);
            } else if (volcanoPlanner != null) {
                try {
                    RelSubset matchedSubset = volcanoPlanner.getSubset(matched);
                    if (matchedSubset != null) {
                        matchedSubsetId = getRelSetId(matchedSubset);
                    }
                } catch (Exception e) {
                    log.debug("Could not resolve subset for matched node at step {}", stepCounter, e);
                }
            }

            // --- producedIntoSubsetId: RelSet.id where produced node was registered ---
            //
            // VolcanoPlanner.getSubset(produced) uses an IdentityHashMap; when the
            // planner deduplicates `produced` against an existing equivalent node
            // (via RelDigest), the original `produced` identity is NOT in the map,
            // so getSubset returns null. Fallback path: look up by RelDigest in the
            // planner's `mapDigestToRel` to recover the canonical RelNode, then
            // resolve its subset.
            int producedIntoSubsetId = -1;
            if (volcanoPlanner != null) {
                try {
                    RelSubset producedSubset = volcanoPlanner.getSubset(produced);
                    if (producedSubset == null) {
                        producedSubset = lookupSubsetByDigest(volcanoPlanner, produced);
                    }
                    if (producedSubset != null) {
                        producedIntoSubsetId = getRelSetId(producedSubset);
                    }
                } catch (Exception e) {
                    log.debug("Could not resolve subset for produced node at step {}", stepCounter, e);
                }
            } else if (matchedSubsetId >= 0) {
                producedIntoSubsetId = matchedSubsetId;
            }

            RuleApplicationStep step = new RuleApplicationStep(
                    stepCounter, call.getRule(), matched, produced, matchedSubsetId);
            step.setProducedIntoSubsetId(producedIntoSubsetId);

            // --- matchedChildSubsetIds: structural children of matched RelSet ---
            //
            // RelSet.getChildSets(planner) returns the RelSets reachable via
            // any RelSubset input of any rel in this set — i.e. the structural
            // children in the query-plan tree. This is the primary DAG signal:
            // matched-subset's children = candidate downstream domains for
            // subsequent rule matches.
            //
            // Cached per-listener by matchedSubsetId to avoid repeated reflection
            // on the same hot RelSet.
            if (volcanoPlanner != null && matchedSubsetId >= 0) {
                java.util.List<Integer> childIds = childSetIdsCache.get(matchedSubsetId);
                if (childIds == null) {
                    childIds = getChildSetIds(volcanoPlanner, matched);
                    childSetIdsCache.put(matchedSubsetId, childIds);
                }
                step.setMatchedChildSubsetIds(childIds);
            }

            // Capture full plan snapshot for HepPlanner if available
            if (hepPlanner != null) {
                try {
                    step.setFullPlanAfterStep(hepPlanner.getRoot().explain());
                } catch (Exception e) {
                    log.debug("Failed to capture full plan snapshot at step {}", stepCounter, e);
                }
            }

            trace.addStep(step);
            log.debug("[FIRED  ] step={} rule={} matchedSet={} childSets={}",
                    stepCounter, call.getRule(), matchedSubsetId,
                    step.getMatchedChildSubsetIds());
        }
    }

    @Override public void relEquivalenceFound(RelEquivalenceEvent event) {}
    @Override public void relDiscarded(RelDiscardedEvent event) {}
    @Override public void relChosen(RelChosenEvent event) {}

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Compute the structural-children RelSet IDs of the matched node's RelSet.
     *
     * <p>Calls {@code RelSet.getChildSets(planner)} via reflection (RelSet is
     * package-private) on the RelSet that contains the matched node, then reads
     * each child's {@code id} field.
     *
     * <p>Returns an empty list on any reflection or lookup failure.
     */
    private static java.util.List<Integer> getChildSetIds(VolcanoPlanner planner, RelNode matched) {
        try {
            RelSubset matchedSubset = (matched instanceof RelSubset)
                    ? (RelSubset) matched : planner.getSubset(matched);
            if (matchedSubset == null) {
                matchedSubset = lookupSubsetByDigest(planner, matched);
            }
            if (matchedSubset == null) return java.util.Collections.emptyList();

            java.lang.reflect.Field setField = RelSubset.class.getDeclaredField("set");
            setField.setAccessible(true);
            Object relSet = setField.get(matchedSubset);
            if (relSet == null) return java.util.Collections.emptyList();

            java.lang.reflect.Method getChildSets =
                    relSet.getClass().getDeclaredMethod("getChildSets", VolcanoPlanner.class);
            getChildSets.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Set<Object> childSets = (java.util.Set<Object>) getChildSets.invoke(relSet, planner);
            if (childSets == null || childSets.isEmpty()) return java.util.Collections.emptyList();

            java.util.List<Integer> ids = new java.util.ArrayList<>(childSets.size());
            for (Object child : childSets) {
                if (child == null) continue;
                java.lang.reflect.Field idField = child.getClass().getDeclaredField("id");
                idField.setAccessible(true);
                ids.add((int) idField.get(child));
            }
            return ids;
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Fallback lookup when {@link VolcanoPlanner#getSubset(RelNode)} returns null:
     * VolcanoPlanner's {@code mapRel2Subset} is an IdentityHashMap, so when the
     * produced node is structurally equal to an already-registered node, the
     * planner registers the canonical node (different Java identity) and the
     * raw {@code produced} reference is not a key in the map.
     *
     * <p>This method reflectively reads {@code mapDigestToRel} (a digest →
     * RelNode map), uses {@code produced.getRelDigest()} to find the canonical
     * registered RelNode, then calls {@code getSubset} on that.
     */
    private static RelSubset lookupSubsetByDigest(VolcanoPlanner planner, RelNode produced) {
        try {
            java.lang.reflect.Field mapField =
                    VolcanoPlanner.class.getDeclaredField("mapDigestToRel");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<Object, RelNode> map =
                    (java.util.Map<Object, RelNode>) mapField.get(planner);
            if (map == null) return null;
            RelNode canonical = map.get(produced.getRelDigest());
            if (canonical == null) return null;
            if (canonical instanceof RelSubset) return (RelSubset) canonical;
            return planner.getSubset(canonical);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reflectively reads the {@code RelSet.id} of the equivalence class that
     * contains the given subset.
     *
     * <p>Note: {@code RelSubset.set} is a package-private <b>field</b> (not a method),
     * and {@code RelSet.id} is also a package-private field. Both must be accessed
     * via field reflection — there is no {@code getSet()} method on RelSubset.
     *
     * @return the RelSet id, or -1 if reflection fails
     */
    private static int getRelSetId(RelSubset subset) {
        try {
            java.lang.reflect.Field setField =
                    RelSubset.class.getDeclaredField("set");
            setField.setAccessible(true);
            Object relSet = setField.get(subset);
            if (relSet == null) return -1;
            java.lang.reflect.Field idField =
                    relSet.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            return (int) idField.get(relSet);
        } catch (Exception e) {
            return -1;
        }
    }
}

