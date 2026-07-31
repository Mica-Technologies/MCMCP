package com.micatechnologies.minecraft.mcmcp.client;

import com.micatechnologies.minecraft.mcmcp.McmcpConstants;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * A rolling buffer of chat the client has received.
 *
 * <h2>Why this has to exist</h2>
 *
 * Commands sent from the client do not return their output to the caller. {@code sendChatMessage}
 * puts the command on the wire and returns; the server's reply arrives some ticks later as an
 * unsolicited chat packet with nothing tying it back to the request. There is no request/response
 * pairing to hook into and no way to synthesise one without intercepting packets.
 *
 * <p>So the client endpoint's command story is two-step by necessity: {@code client_send_chat} sends,
 * and {@code client_read_chat} reads what came back. This buffer is what makes the second half
 * possible. It also captures everything else worth seeing — death messages, other players talking,
 * mods reporting errors into chat — which vanilla only keeps in a GUI a model cannot read.
 *
 * <p>Capacity is fixed and small. This runs inside the game process for the whole session, and chat
 * on a busy server is unbounded; keeping the last few hundred lines answers every question a model
 * asks of it, and keeping more would only cost memory.
 */
@SideOnly(Side.CLIENT)
public final class ClientChatRecorder {

    private static final int CAPACITY = 300;

    /** The MCP resource URI clients can subscribe to for chat activity. */
    public static final String RESOURCE_URI = McmcpConstants.RESOURCE_SCHEME + "://client/chat/recent";

    private static final Deque<Entry> ENTRIES = new ArrayDeque<>(CAPACITY);

    private static boolean registered;

    private ClientChatRecorder() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(new ClientChatRecorder.Events());
        registered = true;
    }

    /** The most recent {@code limit} lines, oldest first. */
    public static List<Entry> recent(int limit) {
        List<Entry> snapshot;
        synchronized (ENTRIES) {
            snapshot = new ArrayList<>(ENTRIES);
        }
        int from = Math.max(0, snapshot.size() - limit);
        return snapshot.subList(from, snapshot.size());
    }

    public static void clear() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
    }

    public static int size() {
        synchronized (ENTRIES) {
            return ENTRIES.size();
        }
    }

    public static class Events {

        @SubscribeEvent
        public void onChat(ClientChatReceivedEvent event) {
            if (event.getMessage() == null) {
                return;
            }
            // Unformatted: colour codes and click events are chat-window furniture, and stripping
            // them here means every consumer does not have to.
            Entry entry = new Entry(System.currentTimeMillis(),
                event.getMessage().getUnformattedText(),
                String.valueOf(event.getType()));

            synchronized (ENTRIES) {
                if (ENTRIES.size() >= CAPACITY) {
                    ENTRIES.removeFirst();
                }
                ENTRIES.addLast(entry);
            }

            // Wakes up any MCP client subscribed to the chat resource. Fires on the client thread
            // inside the chat handler, so it does nothing but append to per-session queues.
            McpRegistry.notifyResourceUpdated(RESOURCE_URI);
        }
    }

    /** One captured chat line. */
    public static final class Entry {

        private final long timestampMillis;
        private final String text;
        private final String type;

        Entry(long timestampMillis, String text, String type) {
            this.timestampMillis = timestampMillis;
            this.text = text;
            this.type = type;
        }

        public long getTimestampMillis() {
            return timestampMillis;
        }

        public String getText() {
            return text;
        }

        /** {@code CHAT}, {@code SYSTEM} or {@code GAME_INFO} — the action bar uses the last one. */
        public String getType() {
            return type;
        }
    }
}
