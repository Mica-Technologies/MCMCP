package com.micatechnologies.minecraft.mcmcp.perf;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates sampled stack traces of one thread into a call tree.
 *
 * <p>A sampling profiler's whole output is "how many of the samples had this frame on the stack".
 * Nothing is measured; a method that appears in 30% of samples was running, or waiting on something
 * it called, for about 30% of the time. That is why every figure here is a sample count and a
 * percentage, never a duration: the honest unit is the sample.
 *
 * <p>Frames are keyed by class and method without the line number, so two samples caught at
 * different lines of one loop land in one node instead of splitting a hot method into a dozen cold
 * ones.
 *
 * <p>Imports no Minecraft class. Not thread-safe: one sampler owns it.
 */
public final class CallTree {

    /**
     * Frames at the top of a stack that mean the thread was parked, not working: the server thread
     * sleeping out the rest of its 50 ms, the client waiting on its frame limiter.
     */
    private static final String[] IDLE_FRAMES = {
        "java.lang.Thread.sleep",
        "java.lang.Thread.yield",
        "java.lang.Object.wait",
        "sun.misc.Unsafe.park",
        "jdk.internal.misc.Unsafe.park",
    };

    /**
     * Packages that are the platform rather than a mod. A sample is attributed to the nearest frame
     * that is none of these, which is the question a mod developer is actually asking: not "which
     * vanilla method is hot" but "whose code called it".
     */
    private static final String[] PLATFORM_PREFIXES = {
        "java.", "javax.", "sun.", "jdk.", "com.sun.",
        "net.minecraft.", "net.minecraftforge.", "org.lwjgl.",
        "com.google.", "io.netty.", "org.apache.", "it.unimi.", "com.mojang.", "paulscode.",
    };

    private final Node root = new Node("(all)");
    private long samples;
    private long idleSamples;

    /**
     * Adds one sample. {@code stack[0]} is the frame that was executing, as
     * {@link java.lang.management.ThreadInfo#getStackTrace} returns it.
     *
     * @return false if the sample was idle and {@code skipIdle} dropped it
     */
    public boolean add(StackTraceElement[] stack, boolean skipIdle) {
        if (stack == null || stack.length == 0) {
            return false;
        }
        if (isIdle(stack[0])) {
            idleSamples++;
            if (skipIdle) {
                return false;
            }
        }
        samples++;
        Node node = root;
        node.total++;
        for (int i = stack.length - 1; i >= 0; i--) {
            node = node.child(key(stack[i]));
            node.total++;
        }
        node.self++;
        return true;
    }

    public long samples() {
        return samples;
    }

    public long idleSamples() {
        return idleSamples;
    }

    static String key(StackTraceElement frame) {
        return frame.getClassName() + "." + frame.getMethodName();
    }

    static boolean isIdle(StackTraceElement top) {
        String key = key(top);
        for (String idle : IDLE_FRAMES) {
            if (idle.equals(key)) {
                return true;
            }
        }
        return false;
    }

    static boolean isPlatform(String frameKey) {
        for (String prefix : PLATFORM_PREFIXES) {
            if (frameKey.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The first three package segments of a frame, which is where a mod's identity usually lives. */
    static String owner(String frameKey) {
        int end = -1;
        for (int i = 0; i < 3; i++) {
            int dot = frameKey.indexOf('.', end + 1);
            if (dot < 0) {
                break;
            }
            // Stop before a class name: a segment that starts upper-case is not a package.
            if (Character.isUpperCase(frameKey.charAt(end + 1))) {
                break;
            }
            end = dot;
        }
        return end <= 0 ? frameKey : frameKey.substring(0, end);
    }

    /**
     * The methods with the most samples in which they were the executing frame — where the time
     * actually went, as opposed to which callers were waiting on it.
     */
    public JsonArray hottestFrames(int limit) {
        Map<String, long[]> bySelf = new HashMap<String, long[]>();
        collectSelf(root, bySelf);
        return topOf(bySelf, limit, "frame");
    }

    /**
     * Samples attributed to the nearest non-platform package on the stack. A vanilla method called
     * from a mod's tile entity counts towards the mod here, and towards vanilla in
     * {@link #hottestFrames}; the two answer different questions and both are needed.
     */
    public JsonArray byOwner(int limit) {
        Map<String, long[]> owners = new HashMap<String, long[]>();
        collectOwners(root, null, owners);
        return topOf(owners, limit, "package");
    }

    private void collectSelf(Node node, Map<String, long[]> into) {
        if (node.self > 0) {
            bump(into, node.frame, node.self);
        }
        for (Node child : node.children.values()) {
            collectSelf(child, into);
        }
    }

    private void collectOwners(Node node, String nearestOwner, Map<String, long[]> into) {
        String owner = node == root || isPlatform(node.frame) ? nearestOwner : owner(node.frame);
        if (node.self > 0) {
            bump(into, owner == null ? "(platform only)" : owner, node.self);
        }
        for (Node child : node.children.values()) {
            collectOwners(child, owner, into);
        }
    }

    private static void bump(Map<String, long[]> map, String key, long by) {
        long[] counter = map.get(key);
        if (counter == null) {
            map.put(key, counter = new long[1]);
        }
        counter[0] += by;
    }

    private JsonArray topOf(Map<String, long[]> counts, int limit, String label) {
        List<Map.Entry<String, long[]>> entries = new ArrayList<Map.Entry<String, long[]>>(counts.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, long[]>>() {
            @Override
            public int compare(Map.Entry<String, long[]> a, Map.Entry<String, long[]> b) {
                int bySamples = Long.compare(b.getValue()[0], a.getValue()[0]);
                return bySamples != 0 ? bySamples : a.getKey().compareTo(b.getKey());
            }
        });
        JsonArray array = new JsonArray();
        for (int i = 0; i < entries.size() && i < limit; i++) {
            JsonObject json = new JsonObject();
            json.addProperty(label, entries.get(i).getKey());
            json.addProperty("percent", percent(entries.get(i).getValue()[0]));
            array.add(json);
        }
        return array;
    }

    /**
     * The tree as indented text, hottest branch first, pruned below {@code minPercent}.
     *
     * <p>Text rather than JSON because a call tree is mostly nesting, and JSON spends more bytes on
     * the nesting than on the content. A chain of frames with nothing branching off is folded onto
     * one line for the same reason — a Minecraft stack is forty frames of dispatch before anything
     * interesting happens.
     */
    public String render(double minPercent, int maxLines) {
        StringBuilder out = new StringBuilder();
        int[] lines = {0};
        renderChildren(root, 0, minPercent, maxLines, lines, out);
        if (lines[0] >= maxLines) {
            out.append("... (line limit reached; raise min_percent or read the file)\n");
        }
        return out.toString();
    }

    private void renderChildren(Node parent, int depth, double minPercent, int maxLines, int[] lines,
                                StringBuilder out) {
        for (Node child : parent.sortedChildren()) {
            if (lines[0] >= maxLines) {
                return;
            }
            if (percent(child.total) < minPercent) {
                // Sorted, so everything after this one is smaller still.
                return;
            }
            // Fold a run of single-child frames: keep walking while nothing else branches off and
            // no samples stopped here.
            Node tip = child;
            int folded = 0;
            while (tip.children.size() == 1 && tip.self == 0) {
                tip = tip.children.values().iterator().next();
                folded++;
            }
            for (int i = 0; i < depth; i++) {
                out.append(' ');
            }
            out.append(percent(tip.total)).append("% ");
            if (folded > 0) {
                out.append(child.frame).append(" > ");
                if (folded > 1) {
                    out.append("(").append(folded - 1).append(") > ");
                }
            }
            out.append(tip.frame);
            if (tip.self > 0 && !tip.children.isEmpty()) {
                out.append(" [self ").append(percent(tip.self)).append("%]");
            }
            out.append('\n');
            lines[0]++;
            renderChildren(tip, depth + 1, minPercent, maxLines, lines, out);
        }
    }

    /** The whole tree, unpruned and unfolded, for the dump file. */
    public JsonObject toJson() {
        return toJson(root);
    }

    private JsonObject toJson(Node node) {
        JsonObject json = new JsonObject();
        json.addProperty("frame", node.frame);
        json.addProperty("samples", node.total);
        if (node.self > 0) {
            json.addProperty("self", node.self);
        }
        if (!node.children.isEmpty()) {
            JsonArray children = new JsonArray();
            for (Node child : node.sortedChildren()) {
                children.add(toJson(child));
            }
            json.add("children", children);
        }
        return json;
    }

    private double percent(long count) {
        return samples == 0 ? 0.0D : Math.round(count * 1000.0D / samples) / 10.0D;
    }

    private static final class Node {

        final String frame;
        final Map<String, Node> children = new LinkedHashMap<String, Node>();
        long total;
        long self;

        Node(String frame) {
            this.frame = frame;
        }

        Node child(String key) {
            Node child = children.get(key);
            if (child == null) {
                children.put(key, child = new Node(key));
            }
            return child;
        }

        List<Node> sortedChildren() {
            List<Node> sorted = new ArrayList<Node>(children.values());
            Collections.sort(sorted, new Comparator<Node>() {
                @Override
                public int compare(Node a, Node b) {
                    int byTotal = Long.compare(b.total, a.total);
                    return byTotal != 0 ? byTotal : a.frame.compareTo(b.frame);
                }
            });
            return sorted;
        }
    }
}
