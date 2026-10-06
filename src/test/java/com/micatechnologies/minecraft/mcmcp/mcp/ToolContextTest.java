package com.micatechnologies.minecraft.mcmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.micatechnologies.minecraft.mcmcp.game.GameThreadBridge;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

class ToolContextTest {

    private static final GameThreadBridge NO_GAME = new GameThreadBridge() {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public boolean isGameThread() {
            return false;
        }

        @Override
        public <T> T callOnGameThread(Callable<T> task, long timeoutMillis) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void runOnGameThread(Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public McmcpSide getSide() {
            return McmcpSide.CLIENT;
        }
    };

    private static ToolContext context(McpSession session, JsonObject arguments) {
        return new ToolContext(session, arguments, NO_GAME,
            session.beginRequest(new JsonPrimitive(1)), 1000L, new JsonPrimitive("token"));
    }

    private static List<Double> progressSent(McpSession session) throws InterruptedException {
        List<Double> values = new ArrayList<>();
        for (JsonObject message; (message = session.pollOutbound(0L)) != null; ) {
            values.add(message.getAsJsonObject("params").get("progress").getAsDouble());
        }
        return values;
    }

    @Test
    void progress_that_does_not_increase_is_not_sent() throws InterruptedException {
        // The spec has progress increase with every notification; a repeat at a step boundary is
        // what a strict client could treat as a fault.
        McpSession session = new McpSession("test", 0L);
        ToolContext context = context(session, new JsonObject());

        context.reportProgress(1, 10, null);
        context.reportProgress(1, 10, null);
        context.reportProgress(0.5, 10, null);
        context.reportProgress(2, 10, null);

        assertEquals(2, progressSent(session).size());
    }

    @Test
    void a_part_sees_its_own_arguments_and_shares_the_call_it_belongs_to() {
        McpSession session = new McpSession("test", 0L);
        JsonObject whole = new JsonObject();
        whole.addProperty("steps", "all");
        ToolContext context = context(session, whole);
        JsonObject partArguments = new JsonObject();
        partArguments.addProperty("slot", 3);

        ToolContext part = context.forPart(partArguments, 2, 5);

        assertEquals(3, part.getInt("slot", -1));
        assertNull(part.getString("steps", null));
        assertSame(context.getCancellation(), part.getCancellation());
        assertSame(session, part.getSession());
    }

    @Test
    void a_parts_progress_lands_inside_its_slice_of_the_whole() throws InterruptedException {
        McpSession session = new McpSession("test", 0L);
        ToolContext context = context(session, new JsonObject());
        context.reportProgress(2, 5, null);

        ToolContext part = context.forPart(new JsonObject(), 2, 5);
        part.reportProgress(500, 1000, null);
        // A part that runs past its own total still stays short of the next step's start.
        part.reportProgress(5000, 1000, null);

        List<Double> sent = progressSent(session);
        assertEquals(3, sent.size());
        assertEquals(2.5, sent.get(1), 1e-9);
        assertEquals(2.99, sent.get(2), 1e-9);
    }
}
