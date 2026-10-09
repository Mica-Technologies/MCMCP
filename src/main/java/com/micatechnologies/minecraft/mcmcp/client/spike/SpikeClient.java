package com.micatechnologies.minecraft.mcmcp.client.spike;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpEndpoints;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.spike.Spike;
import com.micatechnologies.minecraft.mcmcp.transport.McpEndpoint;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

/** PHASE 0 SPIKE — throwaway client half. See {@link Spike}. */
public final class SpikeClient {

    private static volatile CompletableFuture<JsonObject> pending;
    private static volatile long s2cBytes;
    private static volatile long s2cFirstNanos;
    private static volatile long s2cLastNanos;
    private static volatile String s2cThread;
    private static volatile int s2cFrames;

    @Nullable
    private static McpEndpoint virtualServer;

    private SpikeClient() {
    }

    public static void register() {
        if (!Spike.enabled()) {
            return;
        }
        Spike.channel().register(new SpikeClient.Handler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new SpikeClient.ConnectionWatcher());
        registerTools();
    }

    public static final class Handler {

        @SubscribeEvent
        public void onClientPacket(FMLNetworkEvent.ClientCustomPacketEvent event) {
            ByteBuf in = event.getPacket().payload();
            byte op = in.readByte();
            long now = System.nanoTime();
            if (op == Spike.S2C_DATA) {
                int seq = in.readInt();
                in.readBoolean();
                if (seq == 0) {
                    s2cFirstNanos = now;
                    s2cBytes = 0;
                    s2cFrames = 0;
                    s2cThread = Thread.currentThread().getName();
                }
                s2cBytes += in.readableBytes();
                s2cFrames++;
                s2cLastNanos = now;
            }
            else if (op == Spike.S2C_DONE || op == Spike.S2C_ACK) {
                byte[] bytes = new byte[in.readableBytes()];
                in.readBytes(bytes);
                JsonObject server = Json.parse(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject out = new JsonObject();
                out.add("server", server);
                if (op == Spike.S2C_DONE) {
                    out.addProperty("clientBytes", s2cBytes);
                    out.addProperty("clientFrames", s2cFrames);
                    out.addProperty("clientFirstToLastMs", (s2cLastNanos - s2cFirstNanos) / 1e6);
                    out.addProperty("clientHandlerThread", s2cThread);
                }
                out.addProperty("doneThread", Thread.currentThread().getName());
                CompletableFuture<JsonObject> p = pending;
                if (p != null) {
                    p.complete(out);
                }
            }
        }
    }

    public static final class ConnectionWatcher {

        @SubscribeEvent
        public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
            com.micatechnologies.minecraft.mcmcp.Mcmcp.LOGGER.warn("SPIKE connected: local={} type={} thread={}",
                event.isLocal(), event.getConnectionType(), Thread.currentThread().getName());
        }

        @SubscribeEvent
        public void onRegistration(FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
            com.micatechnologies.minecraft.mcmcp.Mcmcp.LOGGER.warn("SPIKE channel registration {} side={} {}",
                event.getOperation(), event.getSide(), event.getRegistrations());
        }

        @SubscribeEvent
        public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
            com.micatechnologies.minecraft.mcmcp.Mcmcp.LOGGER.warn("SPIKE disconnected");
        }
    }

    private static void registerTools() {
        McpRegistry.registerTool(McpTool.named("spike_client")
            .description("PHASE 0 SPIKE. op = s2c | c2s | virtual_start | virtual_stop.")
            .schema(JsonSchema.object()
                .string("op", "operation")
                .integer("bytes", "total bytes")
                .integer("frame", "frame size")
                .build())
            .clientOnly()
            .offGameThread()
            .handler(SpikeClient::handle)
            .build());
    }

    private static ToolResult handle(ToolContext ctx) throws Exception {
        String op = ctx.requireString("op");
        if ("virtual_start".equals(op)) {
            if (virtualServer == null) {
                virtualServer = McmcpEndpoints.start(McmcpSide.SERVER, new ServerThreadBridge());
            }
            return ToolResult.text("virtual server endpoint started: " + (virtualServer != null));
        }
        if ("virtual_stop".equals(op)) {
            if (virtualServer != null) {
                virtualServer.stop();
                virtualServer = null;
            }
            return ToolResult.text("virtual server endpoint stopped");
        }
        long total = ctx.getInt("bytes", 1_000_000);
        int frame = ctx.getInt("frame", 30_000);
        if (Minecraft.getMinecraft().getConnection() == null) {
            return ToolResult.error("not connected");
        }
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending = future;
        long start = System.nanoTime();
        if ("s2c".equals(op)) {
            ByteBuf body = Unpooled.buffer(12);
            body.writeLong(total);
            body.writeInt(frame);
            Spike.channel().sendToServer(Spike.packet(Spike.C2S_REQUEST_S2C, body));
        }
        else if ("c2s".equals(op)) {
            byte[] filler = new byte[frame];
            new java.util.Random(2).nextBytes(filler);
            long sent = 0;
            int seq = 0;
            while (sent < total) {
                int n = (int) Math.min(frame, total - sent);
                ByteBuf body = Unpooled.buffer(n + 8);
                body.writeInt(seq++);
                body.writeBoolean(sent + n >= total);
                body.writeBytes(filler, 0, n);
                Spike.channel().sendToServer(Spike.packet(Spike.C2S_DATA, body));
                sent += n;
            }
        }
        else {
            return ToolResult.error("unknown op");
        }
        JsonObject out = future.get(120, TimeUnit.SECONDS);
        double ms = (System.nanoTime() - start) / 1e6;
        out.addProperty("roundTripMs", ms);
        out.addProperty("mbPerSecond", total / 1e6 / (ms / 1000.0));
        out.addProperty("callerThread", Thread.currentThread().getName());
        return ToolResult.structured(out);
    }
}
