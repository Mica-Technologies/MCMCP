package com.micatechnologies.minecraft.mcmcp.client;

import com.micatechnologies.minecraft.mcmcp.McmcpConstants;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
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
 *
 * <h2>Lines printed on this client (#157)</h2>
 *
 * {@link ClientChatReceivedEvent} fires only for chat that came from the server. A client-side
 * command ({@code ClientCommandHandler}: {@code /csmclient ...}) prints straight into the chat
 * window with {@code sendMessage}, and no event sees it, so its output was on screen and nowhere
 * else. {@link #scanLocal} reads the chat window's own line list for lines added since the last look
 * that no chat event delivered, and records them as type {@code LOCAL}. It runs every client tick,
 * and straight after a client command so its lines can be returned as that command's replies.
 */
@SideOnly(Side.CLIENT)
public final class ClientChatRecorder {

    private static final int CAPACITY = 300;

    /** The MCP resource URI clients can subscribe to for chat activity. */
    public static final String RESOURCE_URI = McmcpConstants.RESOURCE_SCHEME + "://client/chat/recent";

    private static final Deque<Entry> ENTRIES = new ArrayDeque<>(CAPACITY);

    private static final List<Capture> CAPTURES = new CopyOnWriteArrayList<>();

    private static boolean registered;

    /** The newest chat-window line already looked at; null before the first look. Client thread. */
    @Nullable
    private static ChatLine lastSeen;

    /**
     * The components chat events delivered lately, by identity: the chat window holds the same
     * object, which is how a line from the server is told from one printed here.
     */
    private static final Deque<ITextComponent> DELIVERED = new ArrayDeque<>();

    private static final java.util.Map<ITextComponent, Boolean> DELIVERED_SET = new IdentityHashMap<>();

    @Nullable
    private static java.lang.reflect.Field chatLinesField;

    private static boolean chatLinesMissing;

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

    /**
     * Records lines the chat window gained since the last look that no chat event delivered: what
     * this client printed itself. Client thread only.
     *
     * @return the lines recorded, oldest first
     */
    public static List<Entry> scanLocal(Minecraft mc) {
        List<ChatLine> lines = chatLines(mc);
        if (lines == null) {
            return java.util.Collections.emptyList();
        }
        List<ChatLine> added = new ArrayList<>();
        if (lastSeen != null) {
            // Newest first. A line no longer there means the window was cleared or scrolled past
            // its 100 lines, and then everything in it is new.
            for (ChatLine line : lines) {
                if (line == lastSeen) {
                    break;
                }
                added.add(line);
            }
        }
        lastSeen = lines.isEmpty() ? null : lines.get(0);
        if (lastSeen == null && added.isEmpty()) {
            // An empty window: the next line printed is new, whatever it is.
            lastSeen = SENTINEL;
        }
        List<Entry> recorded = new ArrayList<>();
        for (int i = added.size() - 1; i >= 0; i--) {
            ITextComponent message = added.get(i).getChatComponent();
            if (message == null || DELIVERED_SET.containsKey(message)) {
                continue;
            }
            // Remembered too: a line deleted by id can make the window look new from the top again.
            remember(message);
            Entry entry = entryOf(message, "LOCAL");
            record(entry);
            recorded.add(entry);
        }
        return recorded;
    }

    /** Stands for "nothing seen yet, but looked": never in the window, so every line is new. */
    private static final ChatLine SENTINEL = new ChatLine(0, null, 0);

    @Nullable
    @SuppressWarnings("unchecked")
    private static List<ChatLine> chatLines(Minecraft mc) {
        if (mc.ingameGUI == null || chatLinesMissing) {
            return null;
        }
        try {
            if (chatLinesField == null) {
                // Private, so by both names: a released jar has the SRG name at runtime.
                java.lang.reflect.Field field;
                try {
                    field = GuiNewChat.class.getDeclaredField("chatLines");
                }
                catch (NoSuchFieldException devName) {
                    field = GuiNewChat.class.getDeclaredField("field_146252_h");
                }
                field.setAccessible(true);
                chatLinesField = field;
            }
            return (List<ChatLine>) chatLinesField.get(mc.ingameGUI.getChatGUI());
        }
        catch (ReflectiveOperationException | RuntimeException e) {
            chatLinesMissing = true;
            com.micatechnologies.minecraft.mcmcp.Mcmcp.LOGGER.warn("MCMCP: cannot read the chat window's "
                + "lines; chat printed by client-side commands will not be recorded.", e);
            return null;
        }
    }

    private static void remember(ITextComponent message) {
        if (DELIVERED_SET.containsKey(message)) {
            return;
        }
        if (DELIVERED.size() >= CAPACITY) {
            DELIVERED_SET.remove(DELIVERED.removeFirst());
        }
        DELIVERED.addLast(message);
        DELIVERED_SET.put(message, Boolean.TRUE);
    }

    private static Entry entryOf(ITextComponent message, String type) {
        // Unformatted: colour codes and click events are chat-window furniture, and stripping
        // them here means every consumer does not have to.
        return new Entry(System.currentTimeMillis(),
            message.getUnformattedText(),
            type,
            isErrorStyled(message),
            message instanceof TextComponentTranslation
                ? ((TextComponentTranslation) message).getKey() : null,
            message instanceof TextComponentTranslation
                ? argumentTexts((TextComponentTranslation) message) : new String[0]);
    }

    private static void record(Entry entry) {
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

    public static class Events {

        @SubscribeEvent
        public void onChat(ClientChatReceivedEvent event) {
            if (event.getMessage() == null) {
                return;
            }
            record(entryOf(event.getMessage(), String.valueOf(event.getType())));
        }

        /**
         * Last, after any mod has replaced the message: the object that reaches the chat window,
         * so {@link #scanLocal} does not record it a second time.
         */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onChatDelivered(ClientChatReceivedEvent event) {
            ITextComponent message = event.getMessage();
            if (message == null || event.isCanceled()) {
                return;
            }
            // Lines before this one are behind it in the window, so they are seen first.
            scanLocal(Minecraft.getMinecraft());
            remember(message);
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                scanLocal(Minecraft.getMinecraft());
            }
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
