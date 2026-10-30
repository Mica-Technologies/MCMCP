package com.micatechnologies.minecraft.mcmcp.regions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A dimension's wire connections as a graph: each wire once, and the connected pieces it falls into
 * (#160).
 *
 * <p>Immersive Engineering keeps its wire graph in {@code ImmersiveNetHandler}, not in tile-entity
 * NBT, and holds each connection twice, once from each end. Tracing which pole feeds which took
 * insulator NBT, facings and night screenshots, and still gave no confirmed feeder map. This takes
 * the connections as plain coordinates, so the deduplication and the components are tested as plain
 * Java; reading them out of IE is {@code ServerWireTools}'.
 */
public final class WireGraph {

    /** One wire: its two ends, its type and its length in blocks. */
    public static final class Wire {
        public final int[] start;
        public final int[] end;
        public final String type;
        public final int length;

        public Wire(int[] start, int[] end, String type, int length) {
            this.start = start;
            this.end = end;
            this.type = type;
            this.length = length;
        }
    }

    /** A connected piece: its nodes, wires, wire types and bounds. */
    public static final class Component {
        public final List<int[]> nodes = new ArrayList<>();
        public final List<Wire> wires = new ArrayList<>();
        public final Set<String> types = new LinkedHashSet<>();
        public final int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        public final int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
    }

    private final Map<String, Wire> wires = new LinkedHashMap<>();

    /** Adds a connection; the same wire seen from its other end is not added twice. */
    public void add(int[] start, int[] end, String type, int length) {
        String a = key(start);
        String b = key(end);
        String id = a.compareTo(b) <= 0 ? a + "|" + b + "|" + type : b + "|" + a + "|" + type;
        if (!wires.containsKey(id)) {
            wires.put(id, a.compareTo(b) <= 0 ? new Wire(start, end, type, length) : new Wire(end, start, type, length));
        }
    }

    public List<Wire> wires() {
        return new ArrayList<>(wires.values());
    }

    /** Whether a cell is inside the box {minX, minY, minZ, maxX, maxY, maxZ}. */
    public static boolean inside(int[] box, int[] at) {
        return at[0] >= box[0] && at[1] >= box[1] && at[2] >= box[2] && at[0] <= box[3] && at[1] <= box[4]
            && at[2] <= box[5];
    }

    /** The wires with either end in the box. */
    public List<Wire> touching(int[] box) {
        List<Wire> out = new ArrayList<>();
        for (Wire wire : wires.values()) {
            if (inside(box, wire.start) || inside(box, wire.end)) {
                out.add(wire);
            }
        }
        return out;
    }

    /** The connected pieces with a node in the box, largest first. Nodes outside the box are included. */
    public List<Component> components(int[] box) {
        Map<String, String> parent = new HashMap<>();
        Map<String, int[]> cells = new HashMap<>();
        for (Wire wire : wires.values()) {
            String a = key(wire.start);
            String b = key(wire.end);
            cells.put(a, wire.start);
            cells.put(b, wire.end);
            union(parent, a, b);
        }
        Map<String, Component> byRoot = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> cell : cells.entrySet()) {
            Component component = byRoot.computeIfAbsent(find(parent, cell.getKey()), k -> new Component());
            int[] at = cell.getValue();
            component.nodes.add(at);
            for (int i = 0; i < 3; i++) {
                component.min[i] = Math.min(component.min[i], at[i]);
                component.max[i] = Math.max(component.max[i], at[i]);
            }
        }
        for (Wire wire : wires.values()) {
            Component component = byRoot.get(find(parent, key(wire.start)));
            component.wires.add(wire);
            component.types.add(wire.type);
        }
        List<Component> out = new ArrayList<>();
        for (Component component : byRoot.values()) {
            for (int[] node : component.nodes) {
                if (inside(box, node)) {
                    out.add(component);
                    break;
                }
            }
        }
        out.sort((a, b) -> b.nodes.size() - a.nodes.size());
        return out;
    }

    private static String key(int[] at) {
        return at[0] + "," + at[1] + "," + at[2];
    }

    private static String find(Map<String, String> parent, String node) {
        String root = node;
        while (parent.containsKey(root) && !parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        // Path compression, so a long feeder does not make every lookup walk its length.
        String at = node;
        while (parent.containsKey(at) && !parent.get(at).equals(root)) {
            String next = parent.get(at);
            parent.put(at, root);
            at = next;
        }
        return root;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        parent.putIfAbsent(a, a);
        parent.putIfAbsent(b, b);
        String ra = find(parent, a);
        String rb = find(parent, b);
        if (!ra.equals(rb)) {
            parent.put(ra, rb);
        }
    }
}
