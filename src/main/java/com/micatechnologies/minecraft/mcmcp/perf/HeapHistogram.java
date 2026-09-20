package com.micatechnologies.minecraft.mcmcp.perf;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.management.ObjectName;

/**
 * Which classes the heap is full of, from the JVM's own class histogram.
 *
 * <p>HotSpot exposes the {@code jcmd} diagnostic commands as operations on one MBean, and
 * {@code gcClassHistogram} is the one behind {@code jmap -histo}: every class, its instance count and
 * its bytes, largest first. It is reached by name through the MBean server rather than through a
 * typed interface, so nothing here links against a HotSpot class and its absence on another JVM is an
 * exception to report, not a class that fails to load.
 *
 * <p>By default the command counts only <em>live</em> objects, which it can only know by running a
 * full collection first — a pause of a hundred milliseconds or more on a modded heap. Passing
 * {@code -all} counts everything on the heap, garbage included, and pauses nothing. The two answer
 * different questions: "what is on the heap" is cheap, "what is being kept" costs a collection.
 *
 * <p>Imports no Minecraft class.
 */
public final class HeapHistogram {

    /** {@code    1:        123456       98765432  [B} — and on Java 9+, a trailing {@code (module@version)}. */
    private static final Pattern ROW = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+).*$");

    /** One class's share of the heap. */
    public static final class Row {
        public final String className;
        public final long instances;
        public final long bytes;

        Row(String className, long instances, long bytes) {
            this.className = className;
            this.instances = instances;
            this.bytes = bytes;
        }
    }

    private final List<Row> rows;
    private final long totalInstances;
    private final long totalBytes;

    private HeapHistogram(List<Row> rows) {
        this.rows = rows;
        long instances = 0L;
        long bytes = 0L;
        for (Row row : rows) {
            instances += row.instances;
            bytes += row.bytes;
        }
        this.totalInstances = instances;
        this.totalBytes = bytes;
    }

    /**
     * Asks the running JVM for its histogram.
     *
     * @param liveOnly count only reachable objects, at the price of a full collection first
     * @return the command's raw text, for {@link #parse} and for the dump file
     * @throws Exception if this JVM has no diagnostic command MBean, or refuses the operation
     */
    public static String capture(boolean liveOnly) throws Exception {
        Object result = ManagementFactory.getPlatformMBeanServer().invoke(
            new ObjectName("com.sun.management:type=DiagnosticCommand"),
            "gcClassHistogram",
            new Object[] {liveOnly ? new String[0] : new String[] {"-all"}},
            new String[] {String[].class.getName()});
        return String.valueOf(result);
    }

    /** Parses the command's text. Lines that are not rows — the header, the total, blanks — are skipped. */
    public static HeapHistogram parse(String text) {
        List<Row> rows = new ArrayList<Row>();
        for (String line : text.split("\\r?\\n")) {
            Matcher matcher = ROW.matcher(line);
            if (matcher.matches()) {
                rows.add(new Row(readable(matcher.group(3)), Long.parseLong(matcher.group(1)),
                    Long.parseLong(matcher.group(2))));
            }
        }
        return new HeapHistogram(rows);
    }

    /**
     * Turns a JVM array descriptor into what a person would write: {@code [B} is {@code byte[]},
     * {@code [[Ljava.lang.String;} is {@code java.lang.String[][]}. Anything else is already a name.
     */
    static String readable(String descriptor) {
        int dimensions = 0;
        while (dimensions < descriptor.length() && descriptor.charAt(dimensions) == '[') {
            dimensions++;
        }
        if (dimensions == 0) {
            return descriptor;
        }
        String element = descriptor.substring(dimensions);
        String name;
        if (element.startsWith("L") && element.endsWith(";")) {
            name = element.substring(1, element.length() - 1);
        } else if (element.length() == 1) {
            switch (element.charAt(0)) {
                case 'B': name = "byte"; break;
                case 'C': name = "char"; break;
                case 'D': name = "double"; break;
                case 'F': name = "float"; break;
                case 'I': name = "int"; break;
                case 'J': name = "long"; break;
                case 'S': name = "short"; break;
                case 'Z': name = "boolean"; break;
                default: return descriptor;
            }
        } else {
            return descriptor;
        }
        StringBuilder readable = new StringBuilder(name);
        for (int i = 0; i < dimensions; i++) {
            readable.append("[]");
        }
        return readable.toString();
    }

    public long totalInstances() {
        return totalInstances;
    }

    public long totalBytes() {
        return totalBytes;
    }

    public int classCount() {
        return rows.size();
    }

    /** The largest classes by bytes, optionally only those whose name contains {@code filter}. */
    public List<Row> largest(int limit, String filter) {
        List<Row> matching = new ArrayList<Row>();
        for (Row row : rows) {
            if (filter == null || filter.isEmpty() || row.className.contains(filter)) {
                matching.add(row);
            }
        }
        Collections.sort(matching, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                return Long.compare(b.bytes, a.bytes);
            }
        });
        return matching.size() > limit ? matching.subList(0, limit) : matching;
    }

    /**
     * Bytes by package — the first three segments, as {@link CallTree#owner} cuts them — largest
     * first. A mod's own classes are rarely what fills a heap; its share of the heap is the sum of
     * many small ones, and only a roll-up shows it.
     *
     * <p>Names with no package — which in practice means arrays of primitives — are left out. They
     * are the largest things on any heap, so they are already at the top of {@link #largest}, and
     * here they would only push every real package off the list to say the same thing twice. A
     * {@code byte[]} belongs to whoever holds it, and a histogram does not know who that is.
     */
    public List<Row> largestPackages(int limit) {
        Map<String, long[]> byPackage = new HashMap<String, long[]>();
        for (Row row : rows) {
            if (row.className.indexOf('.') < 0) {
                continue;
            }
            // owner() expects "package.Class.method"; a class name is the same shape one segment short.
            String owner = CallTree.owner(row.className);
            long[] sums = byPackage.get(owner);
            if (sums == null) {
                byPackage.put(owner, sums = new long[2]);
            }
            sums[0] += row.instances;
            sums[1] += row.bytes;
        }
        List<Row> packages = new ArrayList<Row>();
        for (Map.Entry<String, long[]> entry : byPackage.entrySet()) {
            packages.add(new Row(entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
        }
        Collections.sort(packages, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                return Long.compare(b.bytes, a.bytes);
            }
        });
        return packages.size() > limit ? packages.subList(0, limit) : packages;
    }
}
