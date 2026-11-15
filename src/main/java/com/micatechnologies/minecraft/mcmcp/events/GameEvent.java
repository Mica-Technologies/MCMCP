package com.micatechnologies.minecraft.mcmcp.events;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * One thing that happened in the game that an agent may want to react to: a chat line, a player
 * joining or leaving, a death, an advancement, a command someone ran.
 *
 * <p>Immutable, and built without any Minecraft type, so the ring that holds these and the filter
 * that picks from it are unit-tested as plain Java. The Forge hooks that create them are thin.
 */
public final class GameEvent {

    public static final String CHAT = "chat";
    public static final String JOIN = "join";
    public static final String LEAVE = "leave";
    public static final String DEATH = "death";
    public static final String ADVANCEMENT = "advancement";
    public static final String COMMAND = "command";

    /** Every type, in the order the schema lists them. */
    public static final List<String> TYPES = Collections.unmodifiableList(java.util.Arrays.asList(
        CHAT, JOIN, LEAVE, DEATH, ADVANCEMENT, COMMAND));

    private final long seq;
    private final long timeMillis;
    private final String type;
    @Nullable
    private final String player;
    @Nullable
    private final String uuid;
    @Nullable
    private final String text;
    @Nullable
    private final List<String> online;
    @Nullable
    private final String detail;

    GameEvent(long seq, long timeMillis, Draft draft) {
        this.seq = seq;
        this.timeMillis = timeMillis;
        this.type = draft.type;
        this.player = draft.player;
        this.uuid = draft.uuid;
        this.text = draft.text;
        this.online = draft.online == null ? null : Collections.unmodifiableList(draft.online);
        this.detail = draft.detail;
    }

    /** Starts describing an event of {@code type}; {@link EventLog#append} gives it a seq and a time. */
    public static Draft of(String type) {
        return new Draft(type);
    }

    public long getSeq() {
        return seq;
    }

    public long getTimeMillis() {
        return timeMillis;
    }

    public String getType() {
        return type;
    }

    @Nullable
    public String getPlayer() {
        return player;
    }

    @Nullable
    public String getText() {
        return text;
    }

    /**
     * The compact wire form. Absent fields are left out rather than sent as null: every byte here
     * goes into a model's context, and a quiet hour of joins is mostly these objects.
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("seq", seq);
        json.addProperty("t", Instant.ofEpochMilli(timeMillis).truncatedTo(ChronoUnit.SECONDS).toString());
        json.addProperty("type", type);
        if (player != null) {
            json.addProperty("player", player);
        }
        if (uuid != null) {
            json.addProperty("uuid", uuid);
        }
        if (text != null) {
            json.addProperty("text", text);
        }
        if (detail != null) {
            // Named for what it holds: today only an advancement's id carries one.
            json.addProperty(ADVANCEMENT.equals(type) ? "advancement" : "detail", detail);
        }
        if (online != null) {
            JsonArray names = new JsonArray();
            for (String name : online) {
                names.add(name);
            }
            json.add("online", names);
        }
        return json;
    }

    /** The mutable half: what a hook knows before the log stamps it. */
    public static final class Draft {

        private final String type;
        @Nullable
        private String player;
        @Nullable
        private String uuid;
        @Nullable
        private String text;
        @Nullable
        private List<String> online;
        @Nullable
        private String detail;

        private Draft(String type) {
            this.type = type;
        }

        public Draft player(@Nullable String name, @Nullable String id) {
            this.player = name;
            this.uuid = id;
            return this;
        }

        public Draft text(@Nullable String value) {
            this.text = value;
            return this;
        }

        public Draft online(@Nullable List<String> names) {
            this.online = names == null ? null : new java.util.ArrayList<>(names);
            return this;
        }

        public Draft detail(@Nullable String value) {
            this.detail = value;
            return this;
        }

        String type() {
            return type;
        }
    }
}
