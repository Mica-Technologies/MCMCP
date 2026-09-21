package com.micatechnologies.minecraft.mcmcp.perf;

import com.google.gson.JsonObject;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Off-heap buffer memory: how much of it is in use, and how much the JVM will allow.
 *
 * <h2>Why heap figures are not enough</h2>
 *
 * A client drawing a very dense chunk section died with {@code OutOfMemoryError: Direct buffer
 * memory} in the chunk rebuild worker ({@code BufferBuilder.growBuffer}) while its heap had room to
 * spare. Minecraft's vertex buffers, LWJGL's scratch buffers and Netty's pooled buffers all live
 * outside the heap, in a pool with its own ceiling, and every memory figure MCMCP reported was about
 * the heap. A test could watch the heap sit at half full right up to the crash.
 *
 * <p>The used figure comes from the {@code direct} {@link BufferPoolMXBean}. The ceiling has no
 * public API: it is {@code -XX:MaxDirectMemorySize} when that was given, and otherwise HotSpot's
 * default, which is the maximum heap size. {@link #maxDirectBytes} says which it used.
 *
 * <p>No Minecraft class is named here, so the parsing is unit-tested without a game.
 */
public final class DirectMemory {

    private static final String FLAG = "-XX:MaxDirectMemorySize=";

    private DirectMemory() {
    }

    /**
     * The {@code -XX:MaxDirectMemorySize} value among {@code jvmArguments}, in bytes, or null if it
     * was not given or does not parse. The last occurrence wins, as it does for the JVM.
     */
    @Nullable
    static Long parseMaxDirectMemoryFlag(List<String> jvmArguments) {
        Long found = null;
        for (String argument : jvmArguments) {
            if (argument == null || !argument.startsWith(FLAG)) {
                continue;
            }
            Long parsed = parseSize(argument.substring(FLAG.length()));
            if (parsed != null) {
                found = parsed;
            }
        }
        return found;
    }

    /** A JVM size such as {@code 512m}, {@code 2G} or {@code 1048576}, in bytes. */
    @Nullable
    static Long parseSize(String text) {
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        long multiplier = 1L;
        char unit = value.charAt(value.length() - 1);
        if (unit == 'k' || unit == 'm' || unit == 'g' || unit == 't') {
            multiplier = unit == 'k' ? 1L << 10 : unit == 'm' ? 1L << 20 : unit == 'g' ? 1L << 30 : 1L << 40;
            value = value.substring(0, value.length() - 1);
        }
        try {
            return Long.parseLong(value) * multiplier;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Megabytes, to one decimal place. */
    static double megabytes(long bytes) {
        return Math.round(bytes / 104857.6D) / 10.0D;
    }

    /**
     * The direct and mapped buffer pools, and the direct ceiling, as reported by
     * {@code client_runtime_info} and {@code game_health}.
     */
    public static JsonObject toJson() {
        JsonObject json = new JsonObject();
        long directUsed = -1L;
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if ("direct".equals(pool.getName())) {
                directUsed = pool.getMemoryUsed();
                json.addProperty("directUsedMb", megabytes(pool.getMemoryUsed()));
                json.addProperty("directCapacityMb", megabytes(pool.getTotalCapacity()));
                json.addProperty("directBuffers", pool.getCount());
            } else if ("mapped".equals(pool.getName()) && pool.getCount() > 0L) {
                json.addProperty("mappedUsedMb", megabytes(pool.getMemoryUsed()));
            }
        }

        Long flag = parseMaxDirectMemoryFlag(ManagementFactory.getRuntimeMXBean().getInputArguments());
        long max = flag != null ? flag : Runtime.getRuntime().maxMemory();
        json.addProperty("directMaxMb", megabytes(max));
        json.addProperty("directMaxFrom", flag != null ? "-XX:MaxDirectMemorySize" : "heap maximum (JVM default)");
        if (directUsed >= 0L && max > 0L) {
            json.addProperty("directUsedPercentOfMax", Math.round(directUsed * 1000.0D / max) / 10.0D);
        }
        return json;
    }
}
