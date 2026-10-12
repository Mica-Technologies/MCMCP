package com.micatechnologies.minecraft.mcmcp.tools;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketChat;
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;

/**
 * Collects the chat a command sends a player, so a command can run as the real player and still
 * return its output (#64).
 *
 * <h2>Why the real player</h2>
 *
 * A {@link CapturingCommandSender} wrapping the player collects output, but it is not an
 * {@code EntityPlayerMP}, and vanilla's {@code getCommandSenderAsPlayer} refuses anything else:
 * {@code /gamemode 1} with no target failed with "You must specify which player". Running as the
 * player fixes every such command at once; this is what keeps the output.
 *
 * <h2>How</h2>
 *
 * A handler at the tail of the player's netty pipeline, where packets are still objects, takes chat
 * and system messages instead of sending them. The game thread hands every packet to the channel's
 * event loop in order, so a task queued before the command and one queued after it bracket exactly
 * that command's packets: {@link #into} switches where messages go, {@link #detach} removes the
 * handler, and nothing sent before or after is touched. Action-bar messages pass through.
 *
 * <p>The output is complete when {@link #detach}'s future completes. Wait for it off the game thread.
 */
final class PlayerChatCapture {

    private static final AtomicInteger NEXT = new AtomicInteger();

    /** {@code SPacketChat.chatComponent}; its getter is client-only, so a dedicated server has none. */
    @Nullable
    private static final Field COMPONENT = findComponentField();

    private final Channel channel;
    private final String name;
    private final Handler handler = new Handler();

    private PlayerChatCapture(Channel channel) {
        this.channel = channel;
        this.name = "mcmcp_chat_capture_" + NEXT.incrementAndGet();
    }

    /**
     * Starts capturing on {@code player}'s connection, or returns null when it has none to capture on
     * (no open channel), in which case the caller runs the command through a capturing sender instead.
     */
    @Nullable
    static PlayerChatCapture attach(EntityPlayerMP player) {
        if (COMPONENT == null || player.connection == null) {
            return null;
        }
        NetworkManager manager = player.connection.getNetworkManager();
        Channel channel = manager == null ? null : manager.channel();
        if (channel == null || !channel.isOpen()) {
            return null;
        }
        final PlayerChatCapture capture = new PlayerChatCapture(channel);
        channel.eventLoop().execute(() -> {
            if (capture.channel.isOpen()) {
                capture.channel.pipeline().addLast(capture.name, capture.handler);
            }
        });
        return capture;
    }

    /** From here on, in packet order, messages go to {@code collector}. */
    void into(@Nullable CapturingCommandSender collector) {
        channel.eventLoop().execute(() -> handler.collector = collector);
    }

    /** Stops capturing once every packet sent so far has passed; completes when it has. */
    CompletableFuture<Void> detach() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            channel.eventLoop().execute(() -> {
                try {
                    if (channel.pipeline().get(name) != null) {
                        channel.pipeline().remove(name);
                    }
                }
                finally {
                    done.complete(null);
                }
            });
        }
        catch (RuntimeException e) {
            // The event loop is shutting down with the connection: there is nothing left to wait for.
            done.complete(null);
        }
        return done;
    }

    /**
     * Waits, off the game thread, for a capture to finish; at most a few seconds, after which whatever
     * arrived is what the call reports. Null means there was no capture.
     */
    static void await(@Nullable CompletableFuture<?> detached) {
        if (detached == null) {
            return;
        }
        try {
            detached.get(5, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        catch (ExecutionException | TimeoutException e) {
            // The connection went away or is backed up; the output so far stands.
        }
    }

    private static final class Handler extends ChannelOutboundHandlerAdapter {

        /** Read and written on the event loop only. */
        @Nullable
        CapturingCommandSender collector;

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            CapturingCommandSender into = collector;
            if (into != null && message instanceof SPacketChat && ((SPacketChat) message).getType() != ChatType.GAME_INFO) {
                ITextComponent component = componentOf((SPacketChat) message);
                if (component != null) {
                    into.record(component);
                    promise.trySuccess();
                    return;
                }
            }
            super.write(context, message, promise);
        }
    }

    @Nullable
    private static ITextComponent componentOf(SPacketChat packet) {
        try {
            return (ITextComponent) COMPONENT.get(packet);
        }
        catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static Field findComponentField() {
        try {
            // The SRG name; ObfuscationReflectionHelper maps it to the dev name in a dev workspace.
            return ObfuscationReflectionHelper.findField(SPacketChat.class, "field_148919_a");
        }
        catch (RuntimeException e) {
            return null;
        }
    }
}
