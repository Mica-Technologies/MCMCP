package com.micatechnologies.minecraft.mcmcp.spike;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.PacketBuffer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLEventChannel;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;
import net.minecraftforge.server.permission.DefaultPermissionLevel;
import net.minecraftforge.server.permission.PermissionAPI;

/**
 * PHASE 0 SPIKE HARNESS — throwaway. Enabled only when the environment variable
 * {@code MCMCP_SPIKE=1} is set. Removed before the server companion PR.
 *
 * <p>Common half: the {@code mcmcp:spike} channel's server handler, the tick-time sampler, the chunk
 * ticket callback and a permission node. The client half is {@code client/spike/SpikeClient}.
 */
public final class Spike {

    public static final String CHANNEL = "mcmcp:spike";
    public static final String NODE = "mcmcp.companion.spike";

    // Opcodes
    public static final byte C2S_REQUEST_S2C = 1;
    public static final byte S2C_DATA = 2;
    public static final byte S2C_DONE = 3;
    public static final byte C2S_DATA = 4;
    public static final byte S2C_ACK = 5;

    private static FMLEventChannel channel;
    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MCMCP-spike-sender");
        t.setDaemon(true);
        return t;
    });

    // Artificial lag: each server tick sleeps this long until lagUntil.
    public static volatile long lagMillis;
    public static volatile long lagUntil;

    // Tick sampler
    private static volatile long tickStart;
    private static volatile long maxTickNanos;
    private static volatile long tickCount;
    private static volatile long tickTotalNanos;

    // C2S receive state (one transfer at a time is enough for a spike)
    private static volatile long c2sBytes;
    private static volatile long c2sFirstNanos;
    private static volatile String c2sThread;

    private Spike() {
    }

    public static boolean enabled() {
        return "1".equals(System.getenv("MCMCP_SPIKE"));
    }

    /** Called from preInit. */
    public static void preInit() {
        if (!enabled()) {
            return;
        }
        Mcmcp.LOGGER.warn("MCMCP PHASE 0 SPIKE HARNESS ENABLED (MCMCP_SPIKE=1)");
        channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(CHANNEL);
        channel.register(new Spike.ServerHandler());
        MinecraftForge.EVENT_BUS.register(new Spike.ServerHandler());
        SpikeServerTools.register();
    }

    /** Called from init. */
    public static void init() {
        if (!enabled()) {
            return;
        }
        ForgeChunkManager.setForcedChunkLoadingCallback(Mcmcp.instance, (tickets, world) -> {
            Mcmcp.LOGGER.warn("SPIKE ticket callback: {} tickets restored in dim {}; releasing all",
                tickets.size(), world.provider.getDimension());
            for (ForgeChunkManager.Ticket ticket : tickets) {
                ForgeChunkManager.releaseTicket(ticket);
            }
        });
        PermissionAPI.registerNode(NODE, DefaultPermissionLevel.OP, "Phase 0 spike node");
    }

    public static FMLEventChannel channel() {
        return channel;
    }

    public static void resetTickWindow() {
        maxTickNanos = 0;
        tickCount = 0;
        tickTotalNanos = 0;
    }

    public static JsonObject tickWindow() {
        JsonObject json = new JsonObject();
        json.addProperty("ticks", tickCount);
        json.addProperty("meanTickMs", tickCount == 0 ? 0 : tickTotalNanos / 1e6 / tickCount);
        json.addProperty("maxTickMs", maxTickNanos / 1e6);
        return json;
    }

    public static FMLProxyPacket packet(byte op, ByteBuf body) {
        ByteBuf buf = Unpooled.buffer(body.readableBytes() + 1);
        buf.writeByte(op);
        buf.writeBytes(body);
        return new FMLProxyPacket(new PacketBuffer(buf), CHANNEL);
    }

    public static final class ServerHandler {

        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                tickStart = System.nanoTime();
                if (System.currentTimeMillis() < lagUntil && lagMillis > 0) {
                    try {
                        Thread.sleep(lagMillis);
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            else if (tickStart != 0) {
                long d = System.nanoTime() - tickStart;
                tickCount++;
                tickTotalNanos += d;
                if (d > maxTickNanos) {
                    maxTickNanos = d;
                }
            }
        }

        @SubscribeEvent
        public void onServerPacket(FMLNetworkEvent.ServerCustomPacketEvent event) {
            final String thread = Thread.currentThread().getName();
            final EntityPlayerMP player = ((NetHandlerPlayServer) event.getHandler()).player;
            ByteBuf in = event.getPacket().payload();
            byte op = in.readByte();
            if (op == C2S_REQUEST_S2C) {
                final long total = in.readLong();
                final int frame = in.readInt();
                Mcmcp.LOGGER.warn("SPIKE S2C request {} bytes in {} byte frames, handler thread {}",
                    total, frame, thread);
                SENDER.execute(() -> sendS2C(player, total, frame, thread));
            }
            else if (op == C2S_DATA) {
                int seq = in.readInt();
                boolean fin = in.readBoolean();
                if (seq == 0) {
                    c2sBytes = 0;
                    c2sFirstNanos = System.nanoTime();
                    c2sThread = thread;
                    resetTickWindow();
                }
                c2sBytes += in.readableBytes();
                if (fin) {
                    long ms = (System.nanoTime() - c2sFirstNanos) / 1_000_000;
                    JsonObject stats = tickWindow();
                    stats.addProperty("bytes", c2sBytes);
                    stats.addProperty("frames", seq + 1);
                    stats.addProperty("serverReceiveMs", ms);
                    stats.addProperty("handlerThread", c2sThread);
                    byte[] json = stats.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    channel.sendTo(packet(S2C_ACK, Unpooled.wrappedBuffer(json)), player);
                }
            }
        }
    }

    private static void sendS2C(EntityPlayerMP player, long total, int frame, String handlerThread) {
        resetTickWindow();
        long start = System.nanoTime();
        long sent = 0;
        int seq = 0;
        byte[] filler = new byte[frame];
        new java.util.Random(1).nextBytes(filler);
        while (sent < total) {
            int n = (int) Math.min(frame, total - sent);
            ByteBuf body = Unpooled.buffer(n + 8);
            body.writeInt(seq++);
            body.writeBoolean(sent + n >= total);
            body.writeBytes(filler, 0, n);
            channel.sendTo(packet(S2C_DATA, body), player);
            sent += n;
        }
        long enqueueMs = (System.nanoTime() - start) / 1_000_000;
        JsonObject stats = tickWindow();
        stats.addProperty("bytes", sent);
        stats.addProperty("frames", seq);
        stats.addProperty("serverEnqueueMs", enqueueMs);
        stats.addProperty("handlerThread", handlerThread);
        stats.addProperty("senderThread", Thread.currentThread().getName());
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        stats.addProperty("dedicated", server != null && server.isDedicatedServer());
        byte[] json = stats.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        channel.sendTo(packet(S2C_DONE, Unpooled.wrappedBuffer(json)), player);
    }
}
