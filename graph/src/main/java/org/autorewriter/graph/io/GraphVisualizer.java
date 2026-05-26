package org.autorewriter.graph.io;

import lombok.extern.slf4j.Slf4j;
import org.autorewriter.graph.model.DependencyEdge;
import org.autorewriter.graph.model.RuleDependencyGraph;
import org.autorewriter.graph.model.RuleNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
/**
 * Exports a {@link RuleDependencyGraph} as a Graphviz DOT graph and renders it to PNG.
 *
 * <p>Nodes represent AutoRewriteRules; directed edges represent observed firing sequences.
 * Node color reflects firing frequency; edge width reflects transition count.
 *
 * <p>Requires the {@code dot} command (Graphviz) to be installed for PNG export.
 */
@Slf4j
public class GraphVisualizer {

    private GraphVisualizer() {}

    /**
     * Generate a DOT-format string from the rule dependency graph.
     *
     * <p>Layout strategy for tree-like clarity:
     * <ul>
     *   <li>{@code rankdir=TB} — top-to-bottom flow (temporal order)</li>
     *   <li>Nodes ranked by in-degree: roots (in-degree=0) at top</li>
     *   <li>Nodes grouped by ruleId type (same matchedNodeSignature prefix → same subgraph cluster)</li>
     *   <li>Edges with high fireCount are thicker and darker</li>
     *   <li>Low-weight edges (fireCount=1) are drawn lighter to reduce visual noise</li>
     * </ul>
     */
    public static String generateDot(RuleDependencyGraph graph) {
        if (graph.nodeCount() == 0) {
            return "digraph RuleDependencyGraph { label=\"empty graph\"; }\n";
        }

        StringBuilder sb = new StringBuilder();

        // --- compute BFS layer for each node (topological rank) ---
        //
        // NOTE: this BFS computes shortest-path layers (each node enqueued at
        // most once). An earlier version used a "longest-path" relaxation that
        // re-enqueued nodes whenever a longer path was found — that loops
        // forever if the graph has cycles. CBO-derived graphs (built from
        // RelSet.getChildSets relationships) are NOT guaranteed acyclic, so
        // shortest-path BFS is the safe choice for layout purposes.
        Map<String, Integer> inDegree = new HashMap<>();
        for (String key : graph.getNodes().keySet()) inDegree.put(key, 0);
        for (List<DependencyEdge> edges : graph.getOutEdges().values()) {
            for (DependencyEdge e : edges) {
                inDegree.merge(e.getToNodeKey(), 1, Integer::sum);
            }
        }
        Map<String, Integer> bfsLayer = new HashMap<>();
        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) { queue.add(e.getKey()); bfsLayer.put(e.getKey(), 0); }
        }
        // If every node has in-degree ≥ 1 (e.g. a pure cycle), seed with any
        // node so BFS still runs.
        if (queue.isEmpty() && !graph.getNodes().isEmpty()) {
            String seed = graph.getNodes().keySet().iterator().next();
            queue.add(seed);
            bfsLayer.put(seed, 0);
        }
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            int layer = bfsLayer.get(cur);
            for (DependencyEdge edge : graph.getOutEdgesOf(cur)) {
                String to = edge.getToNodeKey();
                if (!bfsLayer.containsKey(to)) {
                    bfsLayer.put(to, layer + 1);
                    queue.add(to);
                }
            }
        }
        for (String key : graph.getNodes().keySet()) {
            bfsLayer.putIfAbsent(key, 0);
        }

        // --- group nodes by BFS layer ---
        TreeMap<Integer, List<RuleNode>> byLayer = new TreeMap<>();
        for (RuleNode node : graph.getNodes().values()) {
            int r = bfsLayer.getOrDefault(node.getNodeKey(), 0);
            byLayer.computeIfAbsent(r, k -> new java.util.ArrayList<>()).add(node);
        }

        // --- decide layout mode ---
        // If the graph is essentially a linear chain (max branch ≤ 1 at every node),
        // fold it into rows of FOLD_WIDTH so it doesn't stretch into one long line.
        final int FOLD_WIDTH = 5;   // nodes per "row" before wrapping
        boolean isChain = graph.getOutEdges().values().stream()
                .noneMatch(edges -> edges.size() > 1)
                && byLayer.values().stream().allMatch(g -> g.size() == 1);

        if (isChain) {
            return generateFoldedChainDot(graph, bfsLayer, byLayer, FOLD_WIDTH, sb);
        }

        // --- normal tree/dag layout ---
        sb.append("digraph RuleDependencyGraph {\n");
        sb.append("  rankdir=TB;\n");
        sb.append("  splines=curved;\n");
        sb.append("  nodesep=0.3;\n");
        sb.append("  ranksep=0.5;\n");
        sb.append("  graph [fontname=\"Helvetica\", fontsize=11];\n");
        sb.append("  node  [shape=box, style=\"filled,rounded\", fontname=\"Helvetica-Bold\", fontsize=12, width=2.8, height=0.9, fixedsize=false];\n");
        sb.append("  edge  [fontname=\"Helvetica\", fontsize=8, arrowsize=0.6];\n\n");

        // emit rank=same groups (only groups with 2+ nodes)
        for (Map.Entry<Integer, List<RuleNode>> entry : byLayer.entrySet()) {
            List<RuleNode> group = entry.getValue();
            if (group.size() < 2) continue;
            sb.append("  { rank=same;");
            for (RuleNode node : group) {
                sb.append(" ").append(sanitizeDotId(node.getNodeKey())).append(";");
            }
            sb.append(" }\n");
        }
        sb.append("\n");

        appendNodesAndEdges(graph, bfsLayer, sb);

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Lay out a linear chain in a snake/grid pattern so it doesn't become
     * one extremely long line.  Nodes are arranged in rows of {@code foldWidth}
     * using invisible "anchor" nodes to force row breaks.
     */
    private static String generateFoldedChainDot(
            RuleDependencyGraph graph,
            Map<String, Integer> bfsLayer,
            TreeMap<Integer, List<RuleNode>> byLayer,
            int foldWidth,
            StringBuilder sb) {

        // Build the ordered chain (sorted by BFS layer)
        java.util.List<String> chain = new java.util.ArrayList<>();
        for (List<RuleNode> group : byLayer.values()) {
            for (RuleNode n : group) chain.add(n.getNodeKey());
        }

        sb.append("digraph RuleDependencyGraph {\n");
        sb.append("  rankdir=LR;\n");
        sb.append("  splines=ortho;\n");
        sb.append("  nodesep=0.4;\n");
        sb.append("  ranksep=0.8;\n");
        sb.append("  graph [fontname=\"Helvetica\", fontsize=11];\n");
        sb.append("  node  [shape=box, style=\"filled,rounded\", fontname=\"Helvetica\", fontsize=10, width=2.2, height=0.6, fixedsize=false];\n");
        sb.append("  edge  [fontname=\"Helvetica\", fontsize=8, arrowsize=0.6];\n\n");

        int totalNodes = chain.size();
        int numRows = (totalNodes + foldWidth - 1) / foldWidth;

        // Assign a "column" rank to each node so rank=same groups them into rows
        // Row 0: nodes 0..foldWidth-1  (columns 0..foldWidth-1)
        // Row 1: nodes foldWidth..2*foldWidth-1  (same columns, reversed direction for snake)
        // etc.
        // We achieve the grid by assigning each node a virtual column:
        //   odd rows run right-to-left  (column = foldWidth-1 - pos_in_row)
        //   even rows run left-to-right (column = pos_in_row)
        Map<String, Integer> colOf = new HashMap<>();
        for (int i = 0; i < totalNodes; i++) {
            int row = i / foldWidth;
            int posInRow = i % foldWidth;
            int col = (row % 2 == 0) ? posInRow : (foldWidth - 1 - posInRow);
            colOf.put(chain.get(i), col);
        }

        // emit rank=same for each column (to force vertical alignment)
        TreeMap<Integer, List<String>> byCol = new TreeMap<>();
        for (Map.Entry<String, Integer> e : colOf.entrySet()) {
            byCol.computeIfAbsent(e.getValue(), k -> new java.util.ArrayList<>()).add(e.getKey());
        }
        for (Map.Entry<Integer, List<String>> entry : byCol.entrySet()) {
            List<String> group = entry.getValue();
            if (group.size() < 2) continue;
            sb.append("  { rank=same;");
            for (String key : group) sb.append(" ").append(sanitizeDotId(key)).append(";");
            sb.append(" }\n");
        }
        sb.append("\n");

        // --- max observation count for color scaling ---
        int maxObs = graph.getNodes().values().stream()
                .mapToInt(RuleNode::getObservationCount).max().orElse(1);
        int maxFire = graph.getOutEdges().values().stream()
                .flatMap(List::stream)
                .mapToInt(DependencyEdge::getFireCount).max().orElse(1);

        // emit nodes
        for (RuleNode node : graph.getNodes().values()) {
            String dotId = sanitizeDotId(node.getNodeKey());
            String sig = node.getMatchedNodeSignature();
            if (sig.contains("-")) sig = sig.substring(0, sig.indexOf('-'));
            sig = sig.replace("Logical", "");
            String label = String.format("r%d / %s\\n×%d",
                    node.getRuleId(), escape(sig), node.getObservationCount());
            double ratio = maxObs == 0 ? 0.0 : (double) node.getObservationCount() / maxObs;
            sb.append(String.format("  %s [label=\"%s\", fillcolor=\"%s\"];\n",
                    dotId, label, interpolateColor(ratio)));
        }
        sb.append("\n");

        // emit edges (all chain edges are forward, so no backward handling needed)
        for (int i = 0; i < chain.size(); i++) {
            String fromKey = chain.get(i);
            RuleNode fromNode = graph.getNode(fromKey);
            int fromObs = fromNode != null ? fromNode.getObservationCount() : 0;
            for (DependencyEdge edge : graph.getOutEdgesOf(fromKey)) {
                int fire = edge.getFireCount();
                double prob = edge.getProbability(fromObs);
                double penwidth = 0.8 + (double) fire / Math.max(1, maxFire) * 2.0;
                String color = prob >= 0.7 ? "#CC2222" : prob >= 0.4 ? "#888888" : "#BBBBBB";
                String lbl = fire > 1 ? String.format("×%d", fire) : "";

                // "row break" edges (connecting end of one row to start of next)
                // need constraint=false so they don't force extra horizontal span
                String toKey = edge.getToNodeKey();
                int fromIdx = chain.indexOf(fromKey);
                int toIdx   = chain.indexOf(toKey);
                int fromRow = fromIdx / foldWidth;
                int toRow   = toIdx / foldWidth;
                String extra = (fromRow != toRow) ? ", constraint=false" : "";

                sb.append(String.format(
                        "  %s -> %s [label=\"%s\", penwidth=%.1f, color=\"%s\", fontcolor=\"%s\"%s];\n",
                        sanitizeDotId(fromKey), sanitizeDotId(toKey),
                        lbl, penwidth, color, color, extra));
            }
        }

        sb.append("}\n");
        return sb.toString();
    }

    /** Append node and edge declarations for normal (non-chain) graphs. */
    private static void appendNodesAndEdges(
            RuleDependencyGraph graph,
            Map<String, Integer> bfsLayer,
            StringBuilder sb) {

        int maxObs = graph.getNodes().values().stream()
                .mapToInt(RuleNode::getObservationCount).max().orElse(1);
        int maxFire = graph.getOutEdges().values().stream()
                .flatMap(List::stream)
                .mapToInt(DependencyEdge::getFireCount).max().orElse(1);

        int[] allFires = graph.getOutEdges().values().stream()
                .flatMap(List::stream)
                .mapToInt(DependencyEdge::getFireCount)
                .sorted().toArray();
        int threshold = allFires.length > 0
                ? allFires[Math.max(0, (int) (allFires.length * 0.5))] : 1;

        for (RuleNode node : graph.getNodes().values()) {
            String dotId = sanitizeDotId(node.getNodeKey());
            String sig = node.getMatchedNodeSignature();
            if (sig.contains("-")) sig = sig.substring(0, sig.indexOf('-'));
            sig = sig.replace("Logical", "");
            String label = String.format("r%d / %s\\n×%d",
                    node.getRuleId(), escape(sig), node.getObservationCount());
            double ratio = maxObs == 0 ? 0.0 : (double) node.getObservationCount() / maxObs;
            sb.append(String.format("  %s [label=\"%s\", fillcolor=\"%s\"];\n",
                    dotId, label, interpolateColor(ratio)));
        }
        sb.append("\n");

        for (Map.Entry<String, List<DependencyEdge>> entry : graph.getOutEdges().entrySet()) {
            String fromKey = entry.getKey();
            RuleNode fromNode = graph.getNode(fromKey);
            int fromObs   = fromNode != null ? fromNode.getObservationCount() : 0;
            int fromLayer = bfsLayer.getOrDefault(fromKey, 0);
            for (DependencyEdge edge : entry.getValue()) {
                if (edge.getFireCount() < threshold) continue;
                int toLayer = bfsLayer.getOrDefault(edge.getToNodeKey(), 0);
                double prob     = edge.getProbability(fromObs);
                int    fire     = edge.getFireCount();
                double penwidth = 0.8 + (double)(fire - threshold) / Math.max(1, maxFire - threshold) * 2.5;
                String color    = prob >= 0.7 ? "#CC2222" : prob >= 0.4 ? "#888888" : "#BBBBBB";
                String lbl      = fire > 1 ? String.format("×%d", fire) : "";
                boolean backward = fromLayer >= toLayer;
                String extra = backward ? ", constraint=false, style=dashed" : "";
                sb.append(String.format(
                        "  %s -> %s [label=\"%s\", penwidth=%.1f, color=\"%s\", fontcolor=\"%s\"%s];\n",
                        sanitizeDotId(fromKey), sanitizeDotId(edge.getToNodeKey()),
                        lbl, penwidth, color, color, extra));
            }
        }
    }

    /**
     * Export the graph as a DOT file.
     */
    public static void exportToDot(RuleDependencyGraph graph, Path outputPath) throws IOException {
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        Files.write(outputPath, generateDot(graph).getBytes(StandardCharsets.UTF_8));
        log.info("Graph DOT exported to {}", outputPath);
    }

    /**
     * Export the graph as a PNG image using the Graphviz {@code dot} command.
     *
     * @param graph      the rule dependency graph
     * @param outputPath path of the output PNG file
     * @throws IOException if writing or rendering fails
     */
    public static void exportToPng(RuleDependencyGraph graph, Path outputPath) throws IOException {
        String dot = generateDot(graph);

        Path dotFile = Files.createTempFile("rule-graph-", ".dot");
        try {
            Files.write(dotFile, dot.getBytes(StandardCharsets.UTF_8));

            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }

            ProcessBuilder pb = new ProcessBuilder(
                    "dot", "-Tpng", "-o",
                    outputPath.toAbsolutePath().toString(),
                    dotFile.toAbsolutePath().toString());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for dot process", e);
            }

            if (exitCode != 0) {
                throw new IOException("dot command failed (exit " + exitCode + "): " + processOutput);
            }

            log.info("Graph PNG exported to {}", outputPath.toAbsolutePath());
        } finally {
            Files.deleteIfExists(dotFile);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Escape a string for DOT label. */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** Truncate a string to maxLen chars, appending "..." if truncated. */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }

    /**
     * Convert a nodeKey ("ruleId:matchedSig") to a valid DOT identifier.
     * Replaces non-alphanumeric characters with underscores and prepends "n".
     */
    private static String sanitizeDotId(String nodeKey) {
        if (nodeKey == null) return "n_unknown";
        return "n" + nodeKey.replaceAll("[^a-zA-Z0-9]", "_");
    }

    /**
     * Interpolate between light yellow (#FFF8DC) and deep orange (#FF8C00)
     * based on ratio in [0.0, 1.0].
     */
    private static String interpolateColor(double ratio) {
        // Light: R=255, G=248, B=220  →  Deep: R=255, G=140, B=0
        int r = 255;
        int g = (int) (248 - ratio * (248 - 140));
        int b = (int) (220 - ratio * 220);
        return String.format("#%02X%02X%02X", r, g, b);
    }
}
