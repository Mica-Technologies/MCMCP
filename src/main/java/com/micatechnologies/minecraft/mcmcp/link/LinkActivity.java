package com.micatechnologies.minecraft.mcmcp.link;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.util.EnumMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The task an orchestrator says an agent is working on in this game.
 *
 * <p>Agents keep task lists in the orchestrator, and a person watching the game rather than the
 * orchestrator's window would otherwise never see them. The orchestrator sends an {@code activity}
 * frame when the task changes; this holds the latest, for the in-game line and {@code /mcmcp link}.
 *
 * <p>Kept per link, and shown from whichever link spoke last. A singleplayer world is two links from
 * one game, told the same task; when the server link goes with the world, the client's copy must
 * stay, because the orchestrator only sends a task again when it changes.
 */
public final class LinkActivity {

    /** One task, as the orchestrator described it. Immutable, so it can cross threads freely. */
    public static final class Task {

        private final String list;
        private final String progress;
        private final String title;
        private final String status;

        private final long receivedMillis = System.currentTimeMillis();

        Task(String list, String progress, String title, String status) {
            this.list = list;
            this.progress = progress;
            this.title = title;
            this.status = status;
        }

        public String getList() {
            return list;
        }

        public String getProgress() {
            return progress;
        }

        public String getTitle() {
            return title;
        }

        public String getStatus() {
            return status;
        }

        /** Whether the agent says it is working on this right now. */
        public boolean isInProgress() {
            return "doing".equals(status);
        }

        @Override
        public String toString() {
            return list + " (" + progress + "): " + title + " [" + status + "]";
        }
    }

    private static final Map<McmcpSide, Task> BY_LINK = new EnumMap<>(McmcpSide.class);

    private LinkActivity() {
    }

    /** The task the orchestrator last named on any live link, or null. */
    @Nullable
    public static synchronized Task current() {
        Task newest = null;
        for (Task task : BY_LINK.values()) {
            if (newest == null || task.receivedMillis >= newest.receivedMillis) {
                newest = task;
            }
        }
        return newest;
    }

    /**
     * Takes in an {@code activity} frame from one link. A null or absent task clears that link's; a
     * task missing its title is treated the same, since there would be nothing to show.
     */
    public static synchronized void accept(McmcpSide link, JsonObject frame) {
        Task task = parse(frame);
        if (task == null) {
            BY_LINK.remove(link);
        }
        else {
            BY_LINK.put(link, task);
        }
    }

    /** Forgets one link's task, as when that link goes away. */
    public static synchronized void clear(McmcpSide link) {
        BY_LINK.remove(link);
    }

    @Nullable
    static Task parse(JsonObject frame) {
        JsonElement task = frame.get(LinkProtocol.FIELD_TASK);
        if (task == null || !task.isJsonObject()) {
            return null;
        }
        JsonObject object = task.getAsJsonObject();
        String title = Json.getString(object, LinkProtocol.FIELD_TITLE);
        if (title == null || title.isEmpty()) {
            return null;
        }
        return new Task(
            Json.getString(object, LinkProtocol.FIELD_LIST, ""),
            Json.getString(object, LinkProtocol.FIELD_PROGRESS, ""),
            title,
            Json.getString(object, LinkProtocol.FIELD_STATUS, "todo"));
    }
}
