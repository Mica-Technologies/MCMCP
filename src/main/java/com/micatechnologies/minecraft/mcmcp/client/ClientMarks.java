package com.micatechnologies.minecraft.mcmcp.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.game.McmcpPaths;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.protocol.JsonRpc;
import com.micatechnologies.minecraft.mcmcp.protocol.McpProtocol;
import com.micatechnologies.minecraft.mcmcp.transport.McpEndpoint;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;

/**
 * Marks: a person pointing at something in the world for an agent, or an agent for a person.
 *
 * <h2>Why</h2>
 *
 * "The broken signal by the station" costs an agent a survey to find, and the person knew exactly
 * where it was. {@code /mark [note]} records the block the player is looking at (or where they
 * stand), with the note; the agent reads it with {@code client_marks} and is told the moment one is
 * made. It works the other way too: an agent can mark what it found for the person.
 *
 * <h2>Kept per world</h2>
 *
 * Coordinates mean nothing outside their own world. In singleplayer the marks live in the save's own
 * folder; on a server, where the client has no save, in the game folder under the server's address.
 *
 * <h2>Who hears about a new mark</h2>
 *
 * Every session on the client endpoint, as an MCP log message carrying the mark: the agent, the
 * orchestrator (which shows it in the app and records it in the flight recorder), and any direct
 * HTTP client.
 */
@SideOnly(Side.CLIENT)
public final class ClientMarks {

    /** The most marks one world keeps. The oldest go first. */
    public static final int MAX_MARKS = 200;

    /** The longest note kept, in characters. */
    public static final int MAX_NOTE = 200;

    private static final AtomicLong LAST_ID = new AtomicLong();
    private static boolean registered;
    private static KeyBinding markKey;

    private ClientMarks() {
    }

    /** Registers {@code /mark} and the "Mark this spot" key (unbound until a person binds it). */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        ClientCommandHandler.instance.registerCommand(new MarkCommand());
        markKey = new KeyBinding("key.mcmcp.mark", Keyboard.KEY_NONE, "key.categories.mcmcp");
        ClientRegistry.registerKeyBinding(markKey);
        MinecraftForge.EVENT_BUS.register(new Events());
        registered = true;
    }

    // ------------------------------------------------------------------
    // Where
    // ------------------------------------------------------------------

    /**
     * The file holding this world's marks, or null outside a world. Client thread: it reads which
     * world the client is in.
     */
    @Nullable
    public static File file() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) {
            return null;
        }
        if (mc.isIntegratedServerRunning() && mc.getIntegratedServer() != null) {
            File save = new File(new File(McmcpPaths.gameDirectory(), "saves"),
                mc.getIntegratedServer().getFolderName());
            return new File(save, "mcmcp-marks.json");
        }
        String address = mc.getCurrentServerData() == null ? "unknown-server"
            : mc.getCurrentServerData().serverIP;
        String safe = address.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return new File(new File(McmcpPaths.gameDirectory(), "mcmcp-marks"), safe + ".json");
    }

    // ------------------------------------------------------------------
    // Reading and writing
    // ------------------------------------------------------------------

    public static synchronized List<JsonObject> list(File file) {
        List<JsonObject> marks = new ArrayList<>();
        if (!file.isFile()) {
            return marks;
        }
        try {
            JsonElement parsed = Json.parse(new String(Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8));
            if (parsed != null && parsed.isJsonArray()) {
                for (JsonElement element : parsed.getAsJsonArray()) {
                    if (element.isJsonObject()) {
                        marks.add(element.getAsJsonObject());
                    }
                }
            }
        }
        catch (IOException | RuntimeException e) {
            Mcmcp.LOGGER.warn("MCMCP could not read marks from " + file, e);
        }
        return marks;
    }

    private static synchronized void write(File file, List<JsonObject> marks) throws IOException {
        File folder = file.getParentFile();
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("could not create " + folder);
        }
        JsonArray array = new JsonArray();
        for (JsonObject mark : marks) {
            array.add(mark);
        }
        File temporary = new File(folder, file.getName() + ".tmp");
        Files.write(temporary.toPath(), Json.writePretty(array).getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Adds a mark, keeps the newest {@link #MAX_MARKS}, and tells every session. Returns the mark.
     *
     * @param by "player" or "agent"
     */
    public static synchronized JsonObject add(File file, BlockPos pos, int dimension, String note, String by,
                                              @Nullable String block) throws IOException {
        long id = LAST_ID.updateAndGet(last -> Math.max(last + 1, System.currentTimeMillis()));
        JsonObject mark = new JsonObject();
        mark.addProperty("id", "m" + id);
        mark.addProperty("x", pos.getX());
        mark.addProperty("y", pos.getY());
        mark.addProperty("z", pos.getZ());
        mark.addProperty("dimension", dimension);
        String trimmed = note == null ? "" : note.trim();
        if (!trimmed.isEmpty()) {
            mark.addProperty("note", trimmed.length() <= MAX_NOTE ? trimmed : trimmed.substring(0, MAX_NOTE));
        }
        if (block != null) {
            mark.addProperty("block", block);
        }
        mark.addProperty("by", by);
        mark.addProperty("at", System.currentTimeMillis());
        List<JsonObject> marks = list(file);
        marks.add(mark);
        while (marks.size() > MAX_MARKS) {
            marks.remove(0);
        }
        write(file, marks);
        announce(mark);
        return mark;
    }

    /** Removes one mark by id, or all of them with a null id. Returns how many went. */
    public static synchronized int clear(File file, @Nullable String id) throws IOException {
        List<JsonObject> marks = list(file);
        int before = marks.size();
        if (id == null) {
            marks.clear();
        }
        else {
            marks.removeIf(mark -> id.equals(Json.getString(mark, "id", "")));
        }
        if (marks.size() != before) {
            write(file, marks);
        }
        return before - marks.size();
    }

    /** Sends the mark to every session on the client endpoint, as an MCP log message. */
    private static void announce(JsonObject mark) {
        JsonObject data = new JsonObject();
        data.addProperty("message", describe(mark));
        data.add("mark", mark);
        JsonObject params = new JsonObject();
        params.addProperty("level", "notice");
        params.addProperty("logger", "mcmcp.marks");
        params.add("data", data);
        JsonObject notification = JsonRpc.notification(McpProtocol.NOTIFICATION_MESSAGE, params);
        for (McpEndpoint endpoint : Mcmcp.allEndpoints()) {
            if (endpoint.getSide().isClient()) {
                endpoint.getSessions().broadcast(notification);
            }
        }
    }

    /** "Marked 12 64 -30 (minecraft:stone): the broken signal", as people and agents read it. */
    public static String describe(JsonObject mark) {
        String block = Json.getString(mark, "block", null);
        String note = Json.getString(mark, "note", null);
        return ("agent".equals(Json.getString(mark, "by", "")) ? "Agent marked " : "Marked ")
            + Json.getInt(mark, "x", 0) + " " + Json.getInt(mark, "y", 0) + " " + Json.getInt(mark, "z", 0)
            + (block == null ? "" : " (" + block + ")")
            + (note == null ? "" : ": " + note);
    }

    // ------------------------------------------------------------------
    // Marking from the game
    // ------------------------------------------------------------------

    /** Marks what the player is looking at, or where they stand. Client thread. */
    static JsonObject markHere(String note) throws IOException {
        Minecraft mc = Minecraft.getMinecraft();
        File file = file();
        if (file == null || mc.player == null) {
            throw new IOException("not in a world");
        }
        RayTraceResult target = mc.objectMouseOver;
        BlockPos pos;
        String block = null;
        if (target != null && target.typeOfHit == RayTraceResult.Type.BLOCK && target.getBlockPos() != null) {
            pos = target.getBlockPos();
            net.minecraft.util.ResourceLocation name = mc.world.getBlockState(pos).getBlock().getRegistryName();
            block = name == null ? null : name.toString();
        }
        else {
            pos = com.micatechnologies.minecraft.mcmcp.tools.GameJson.blockPosOf(mc.player);
        }
        return add(file, pos, mc.player.dimension, note, "player", block);
    }

    private static void tell(String text, TextFormatting colour) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player != null) {
            TextComponentString message = new TextComponentString(text);
            message.getStyle().setColor(colour);
            mc.player.sendMessage(message);
        }
    }

    /** {@code /mark [note]}: a client command, so it works on a server without MCMCP. */
    public static final class MarkCommand extends CommandBase {

        @Override
        public String getName() {
            return "mark";
        }

        @Override
        public String getUsage(ICommandSender sender) {
            return "/mark [note] — mark the block you are looking at for the agent";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
            return true;
        }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
            try {
                JsonObject mark = markHere(String.join(" ", args));
                tell(describe(mark) + " — the agent can see it.", TextFormatting.AQUA);
            }
            catch (IOException e) {
                tell("Could not mark: " + e.getMessage(), TextFormatting.RED);
            }
        }
    }

    public static final class Events {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END || markKey == null) {
                return;
            }
            while (markKey.isPressed()) {
                try {
                    JsonObject mark = markHere("");
                    tell(describe(mark) + " — add a note with /mark <note> next time.", TextFormatting.AQUA);
                }
                catch (IOException e) {
                    tell("Could not mark: " + e.getMessage(), TextFormatting.RED);
                }
            }
        }
    }

    /** For tools: the marks of the world the client is in, newest first. Client thread. */
    public static List<JsonObject> current() {
        File file = file();
        if (file == null) {
            return Collections.emptyList();
        }
        List<JsonObject> marks = list(file);
        Collections.reverse(marks);
        return marks;
    }
}
