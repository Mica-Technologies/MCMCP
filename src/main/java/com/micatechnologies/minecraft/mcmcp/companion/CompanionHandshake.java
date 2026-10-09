package com.micatechnologies.minecraft.mcmcp.companion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import javax.annotation.Nullable;

/**
 * The hello a client sends when it joins a server that has the companion channel, and the welcome
 * that answers it.
 *
 * <p>Compatibility is decided here, between the two MCMCP builds, rather than by Forge's mod-list
 * check: MCMCP declares {@code acceptableRemoteVersions = "*"} so that players with no MCMCP, or a
 * different MCMCP, can always join a server that runs it.
 */
public final class CompanionHandshake {

    /** What a client knows about the companion on the server it is connected to. */
    public enum State {
        /** Not connected to a remote server, or the server has no companion channel. */
        ABSENT,
        /** Hello sent, no answer yet. */
        NEGOTIATING,
        /** The server answered and refused this player. */
        UNAUTHORISED,
        /** The server speaks a different version of this protocol. */
        INCOMPATIBLE,
        /** The server granted access; its tools are reachable. */
        AVAILABLE
    }

    private CompanionHandshake() {
    }

    public static byte[] hello(String modVersion) {
        JsonObject json = new JsonObject();
        json.addProperty("protocol", CompanionProtocol.VERSION);
        json.addProperty("modVersion", modVersion);
        return bytes(json);
    }

    /** A welcome that grants access, naming the classes of call the player may make. */
    public static byte[] granted(String modVersion, Collection<String> classes) {
        JsonObject json = new JsonObject();
        json.addProperty("protocol", CompanionProtocol.VERSION);
        json.addProperty("modVersion", modVersion);
        json.addProperty("ok", true);
        json.add("classes", Json.arrayOfStrings(classes));
        return bytes(json);
    }

    /**
     * A welcome that refuses.
     *
     * @param reason shown to the player and their agent as it is, so it must not reveal who else is
     *               allowed or how the server is configured beyond "ask an operator"
     */
    public static byte[] refused(String modVersion, String reason) {
        JsonObject json = new JsonObject();
        json.addProperty("protocol", CompanionProtocol.VERSION);
        json.addProperty("modVersion", modVersion);
        json.addProperty("ok", false);
        json.addProperty("reason", reason);
        return bytes(json);
    }

    /**
     * The server ending a player's companion access while they stay connected: revoked, the companion
     * turned off, a permission removed. The client stops presenting the server's tools.
     */
    public static byte[] bye(String reason) {
        JsonObject json = new JsonObject();
        json.addProperty("reason", reason);
        return bytes(json);
    }

    /** The peer's protocol version from a hello or welcome, or -1 when it is not one. */
    public static int protocolOf(@Nullable JsonObject message) {
        return message == null ? -1 : Json.getInt(message, "protocol", -1);
    }

    /** What a client should make of a welcome. */
    public static State stateOf(@Nullable JsonObject welcome) {
        if (welcome == null) {
            return State.ABSENT;
        }
        if (protocolOf(welcome) != CompanionProtocol.VERSION) {
            return State.INCOMPATIBLE;
        }
        return Json.getBoolean(welcome, "ok", false) ? State.AVAILABLE : State.UNAUTHORISED;
    }

    @Nullable
    public static JsonObject parse(byte[] bytes) {
        try {
            JsonElement element = Json.parse(new String(bytes, StandardCharsets.UTF_8));
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    public static byte[] bytes(JsonObject json) {
        return Json.write(json).getBytes(StandardCharsets.UTF_8);
    }
}
