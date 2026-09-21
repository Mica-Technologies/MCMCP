package com.micatechnologies.minecraft.mcmcp.client;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * Reads a rectangle of the rendered frame, every frame or on an interval, and reduces each read to
 * its mean brightness and colour.
 *
 * <h2>Why not screenshots in a loop</h2>
 *
 * The question it answers is "did that flash", and a flash is short: a strobe lit for 75 ms of every
 * 1,000. Back-to-back {@code client_screenshot} calls land 100 to 250 ms apart, each writing a PNG,
 * so whether a burst catches the flash at all is luck — and every frame then has to be decoded
 * somewhere to learn one number. Reading the frame here, at the end of each render tick, samples at
 * the frame rate and sends back only the numbers.
 *
 * <p>Sampled at {@link TickEvent.Phase#END} of the render tick, when Minecraft's framebuffer is still
 * bound and holds the finished world and GUI, before it is blitted to the window. The read is a
 * pipeline stall, which is why this runs only while a request is active.
 */
@SideOnly(Side.CLIENT)
public final class ScreenSampler {

    /** Upper bound on pixels averaged per frame; larger regions are sampled on a grid. */
    private static final int MAX_SAMPLED_PIXELS = 250_000;

    private static boolean registered;
    private static volatile Request active;

    private ScreenSampler() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(new ScreenSampler.Events());
        registered = true;
    }

    /** One frame's reduction. Channels and luminance are 0-255. */
    public static final class Sample {
        public final long nanos;
        public final double luminance;
        public final double red;
        public final double green;
        public final double blue;

        Sample(long nanos, double luminance, double red, double green, double blue) {
            this.nanos = nanos;
            this.luminance = luminance;
            this.red = red;
            this.green = green;
            this.blue = blue;
        }
    }

    /** The finished request: its samples, and the region actually read after clamping to the window. */
    public static final class Result {
        public final List<Sample> samples;
        public final int x;
        public final int y;
        public final int width;
        public final int height;

        Result(List<Sample> samples, int x, int y, int width, int height) {
            this.samples = samples;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * Starts sampling. {@code width} or {@code height} of 0 means to the window's edge.
     *
     * @return the future completing with the samples, or null if another request is running
     */
    public static synchronized CompletableFuture<Result> start(int x, int y, int width, int height,
                                                               int count, long intervalNanos) {
        register();
        if (active != null) {
            return null;
        }
        Request request = new Request(x, y, width, height, count, intervalNanos);
        active = request;
        return request.future;
    }

    /** Abandons the running request, if any. */
    public static synchronized void cancel() {
        Request request = active;
        active = null;
        if (request != null) {
            request.future.cancel(false);
        }
    }

    private static synchronized void finish(Request request) {
        if (active == request) {
            active = null;
        }
    }

    private static final class Request {
        final int x;
        final int y;
        final int width;
        final int height;
        final int count;
        final long intervalNanos;
        final List<Sample> samples = new ArrayList<>();
        final CompletableFuture<Result> future = new CompletableFuture<>();
        long lastNanos = Long.MIN_VALUE;
        ByteBuffer pixels;
        int readX;
        int readY;
        int readWidth;
        int readHeight;

        Request(int x, int y, int width, int height, int count, long intervalNanos) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.count = count;
            this.intervalNanos = intervalNanos;
        }
    }

    public static class Events {

        @SubscribeEvent
        public void onRenderTick(TickEvent.RenderTickEvent event) {
            Request request = active;
            if (event.phase != TickEvent.Phase.END || request == null) {
                return;
            }
            long now = System.nanoTime();
            if (request.lastNanos != Long.MIN_VALUE && now - request.lastNanos < request.intervalNanos) {
                return;
            }
            try {
                request.samples.add(read(request, now));
                request.lastNanos = now;
            } catch (RuntimeException e) {
                // Never onto the render thread: an escaping exception here ends the session.
                finish(request);
                request.future.completeExceptionally(e);
                return;
            }
            if (request.samples.size() >= request.count) {
                finish(request);
                request.future.complete(new Result(request.samples, request.readX, request.readY,
                    request.readWidth, request.readHeight));
            }
        }
    }

    /** Render thread only, with the frame still in the bound framebuffer. */
    private static Sample read(Request request, long now) {
        Minecraft mc = Minecraft.getMinecraft();
        int screenWidth = mc.displayWidth;
        int screenHeight = mc.displayHeight;
        int x = Math.max(0, Math.min(request.x, screenWidth - 1));
        int y = Math.max(0, Math.min(request.y, screenHeight - 1));
        int width = request.width <= 0 ? screenWidth - x : Math.min(request.width, screenWidth - x);
        int height = request.height <= 0 ? screenHeight - y : Math.min(request.height, screenHeight - y);
        width = Math.max(1, width);
        height = Math.max(1, height);

        int bytes = width * height * 4;
        if (request.pixels == null || request.pixels.capacity() < bytes) {
            request.pixels = BufferUtils.createByteBuffer(bytes);
        }
        request.readX = x;
        request.readY = y;
        request.readWidth = width;
        request.readHeight = height;

        ByteBuffer pixels = request.pixels;
        pixels.clear();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        // GL's origin is the bottom-left; the caller's, like a screenshot's, is the top-left.
        GL11.glReadPixels(x, screenHeight - y - height, width, height, GL11.GL_RGBA,
            GL11.GL_UNSIGNED_BYTE, pixels);

        int step = Math.max(1, (int) Math.ceil(Math.sqrt(width * (double) height / MAX_SAMPLED_PIXELS)));
        double red = 0.0D;
        double green = 0.0D;
        double blue = 0.0D;
        long counted = 0L;
        for (int row = 0; row < height; row += step) {
            for (int column = 0; column < width; column += step) {
                int offset = (row * width + column) * 4;
                red += pixels.get(offset) & 0xFF;
                green += pixels.get(offset + 1) & 0xFF;
                blue += pixels.get(offset + 2) & 0xFF;
                counted++;
            }
        }
        red /= counted;
        green /= counted;
        blue /= counted;
        // Rec. 709 luma weights, applied to the gamma-encoded values as stored — a relative
        // brightness for spotting change, not a photometric measurement.
        double luminance = 0.2126D * red + 0.7152D * green + 0.0722D * blue;
        return new Sample(now, luminance, red, green, blue);
    }
}
