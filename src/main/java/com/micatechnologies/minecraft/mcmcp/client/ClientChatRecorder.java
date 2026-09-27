package com.micatechnologies.minecraft.mcmcp.client;

import com.micatechnologies.minecraft.mcmcp.McmcpConstants;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
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
 *
 * <p>Code that needs every line from a moment onwards — however many arrive — opens a
 * {@link Capture} instead of reading the buffer, which cannot say what fell off its front.
 */
@SideOnly(Side.CLIENT)
public final class ClientChatRecorder {

    private static final int CAPACITY = 300;

    /** The MCP resource URI clients can subscribe to for chat activity. */
    public static final String RESOURCE_URI = McmcpConstants.RESOURCE_SCHEME + "://client/chat/recent";

    private static final Deque<Entry> ENTRIES = new ArrayDeque<>(CAPACITY);

    private static final List<Capture> CAPTURES = new CopyOnWriteArrayList<>();

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

    /**
     * Starts collecting every line received from now until {@link Capture#close}. Always close it:
     * an open capture keeps every line it is sent.
     */
    public static Capture capture() {
        register();
        Capture capture = new Capture();
        CAPTURES.add(capture);
        return capture;
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
            ITextComponent message = event.getMessage();
            Entry entry = new Entry(System.currentTimeMillis(),
                message.getUnformattedText(),
                String.valueOf(event.getType()),
                isErrorStyled(message),
                message instanceof TextComponentTranslation
                    ? ((TextComponentTranslation) message).getKey() : null,
                message instanceof TextComponentTranslation
                    ? argumentTexts((TextComponentTranslation) message) : new String[0]);
            for (Capture capture : CAPTURES) {
                capture.lines.add(entry);
            }

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

    /**
     * Whether a line is drawn the way a failed command is.
     *
     * <p>Vanilla's {@code CommandHandler} colours every failure red — unknown command, no
     * permission, wrong usage, and each {@code CommandException} a command throws — and WorldEdit,
     * FAWE and ForgeEssentials all print their errors red too. So colour is the one signal that is
     * the same across mods and across the client's language, where matching message text is
     * neither. Judged on the first visible run of text, so a reply that merely highlights a word in
     * red is not an error.
     */
    static boolean isErrorStyled(ITextComponent message) {
        for (ITextComponent part : message) {
            String own = part.getUnformattedComponentText();
            if (own.isEmpty()) {
                continue;
            }
            TextFormatting color = part.getStyle().getColor();
            TextFormatting legacy = leadingLegacyColor(own);
            if (legacy != null) {
                color = legacy;
            }
            return color == TextFormatting.RED || color == TextFormatting.DARK_RED;
        }
        return false;
    }

    /**
     * The last {@code §} colour code before the first visible character, as plugins still send.
     * Only red is told apart: any other colour reads as {@code WHITE}, which is all the caller needs.
     */
    @Nullable
    static TextFormatting leadingLegacyColor(String text) {
        TextFormatting color = null;
        int i = 0;
        while (i + 1 < text.length() && text.charAt(i) == '§') {
            char code = Character.toLowerCase(text.charAt(i + 1));
            if (code == 'c') {
                color = TextFormatting.RED;
            } else if (code == '4') {
                color = TextFormatting.DARK_RED;
            } else if (code == 'r') {
                color = null;
            } else if (Character.digit(code, 16) >= 0) {
                color = TextFormatting.WHITE;
            }
            i += 2;
        }
        return color;
    }

    private static String[] argumentTexts(TextComponentTranslation message) {
        Object[] args = message.getFormatArgs();
        String[] texts = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            texts[i] = args[i] instanceof ITextComponent
                ? ((ITextComponent) args[i]).getUnformattedText()
                : String.valueOf(args[i]);
        }
        return texts;
    }

    /** Every line received while it is open. See {@link #capture}. */
    public static final class Capture implements AutoCloseable {

        private final Queue<Entry> lines = new ConcurrentLinkedQueue<>();

        private Capture() {
        }

        /** The oldest line not yet taken, or null. */
        @Nullable
        public Entry poll() {
            return lines.poll();
        }

        @Override
        public void close() {
            CAPTURES.remove(this);
        }
    }

    /**
     * One captured chat line.
     *
     * <p>Plain strings only, built on the client thread inside the chat event, so no Minecraft object
     * reaches the MCP worker that reads it.
     */
    public static final class Entry {

        private final long timestampMillis;
        private final String text;
        private final String type;
        private final boolean error;
        @Nullable
        private final String translationKey;
        private final String[] arguments;

        Entry(long timestampMillis, String text, String type, boolean error,
            @Nullable String translationKey, String[] arguments) {
            this.timestampMillis = timestampMillis;
            this.text = text;
            this.type = type;
            this.error = error;
            this.translationKey = translationKey;
            this.arguments = arguments;
        }

        /** Coloured the way a failed command is; see {@link #isErrorStyled}. */
        public boolean isError() {
            return error;
        }

        /** The language key, when the server sent a translatable message — vanilla replies are. */
        @Nullable
        public String getTranslationKey() {
            return translationKey;
        }

        /** A translatable message's arguments, as text. Empty for any other message. */
        public String[] getArguments() {
            return arguments.clone();
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
