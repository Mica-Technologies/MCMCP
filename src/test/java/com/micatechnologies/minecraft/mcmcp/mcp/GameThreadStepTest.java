package com.micatechnologies.minecraft.mcmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.micatechnologies.minecraft.mcmcp.game.BridgeTask;
import com.micatechnologies.minecraft.mcmcp.game.GameThreadBridge;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.game.ServerLoad;
import com.micatechnologies.minecraft.mcmcp.game.TaskCaller;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** How a tool's step onto the server thread is handed over, and what its caller hears when it does not run (#133). */
class GameThreadStepTest {

    /** A bridge that notes how each step was handed over, and runs it or never gets to it. */
    private static final class Bridge implements GameThreadBridge {

        boolean neverStarts;
        long timeoutMillis;
        TaskCaller caller;
        BooleanSupplier cancelled;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean isGameThread() {
            return false;
        }

        @Override
        public <T> T callOnGameThread(Callable<T> task, long timeoutMillis) throws Exception {
            return callOnGameThread(task, timeoutMillis, null, null, null);
        }

        @Override
        public <T> T callOnGameThread(Callable<T> task, long timeoutMillis, @Nullable Consumer<? super T> ifOrphaned,
                                      @Nullable BooleanSupplier cancelled, @Nullable TaskCaller caller) throws Exception {
            this.timeoutMillis = timeoutMillis;
            this.caller = caller;
            this.cancelled = cancelled;
            if (neverStarts) {
                // A real task the game thread never reaches, so the caller's wait runs out before it starts.
                return new BridgeTask<>(task, null).await(1L, null);
            }
            return task.call();
        }

        @Override
        public void runOnGameThread(Runnable task) {
        }

        @Override
        public McmcpSide getSide() {
            return McmcpSide.SERVER;
        }
    }

    private final Bridge bridge = new Bridge();
    private final McpSession session = new McpSession("s", 0L);
    private final McpSession.CancellationToken cancellation = session.beginRequest(new JsonPrimitive(1));
    private final ToolContext context = new ToolContext(session, new JsonObject(), bridge, cancellation, 5_000L, null);

    @AfterEach
    void healthy() {
        ServerLoad.reset();
    }

    @Test
    void anOrdinaryStepTakesItsCallersTurnWithTheUsualTimeoutAndCanBeCancelled() {
        context.onGameThread(() -> 1);
        assertEquals(5_000L, bridge.timeoutMillis);
        assertFalse(bridge.caller.followUp);
        assertNotNull(bridge.cancelled);
    }

    @Test
    void aFollowUpGoesFirstWaitsLongAndIsNotDroppedForACancel() {
        context.setMutating(true);
        context.onGameThread(() -> 1);
        session.cancelRequest(new JsonPrimitive(1), "test");
        assertTrue(cancellation.isCancelled());
        assertEquals(Integer.valueOf(2), context.onGameThreadFollowUp(() -> 2), "a cancel does not stop it");
        assertTrue(bridge.caller.followUp);
        assertEquals(ToolContext.FOLLOW_UP_TIMEOUT_MILLIS, bridge.timeoutMillis);
        assertNull(bridge.cancelled, "nothing it polls can drop it");
        assertFalse(context.isFollowUp(), "only for that one step");
    }

    @Test
    void aContextMarkedFollowUpMakesEveryStepOne() {
        context.setFollowUp(true);
        context.onGameThread(() -> 1);
        assertTrue(bridge.caller.followUp);
        assertEquals(ToolContext.FOLLOW_UP_TIMEOUT_MILLIS, bridge.timeoutMillis);
    }

    @Test
    void aFirstStepThatNeverStartedSaysNothingChangedAndWhenToRetry() {
        context.setMutating(true);
        bridge.neverStarts = true;
        ToolBusyException busy = assertThrows(ToolBusyException.class, () -> context.onGameThread(() -> 1));
        assertTrue(busy.nothingRan());
        assertTrue(busy.getMessage().contains("nothing changed"), busy.getMessage());
        JsonObject block = busy.toResult().getStructuredContent().getAsJsonObject("busy");
        assertFalse(block.has("earlierStepsRan"));
        assertTrue(block.has("retryAfterMs"));
    }

    @Test
    void aLaterStepThatNeverStartedSaysEarlierStepsOfTheCallHadRun() {
        context.setMutating(true);
        context.onGameThread(() -> 1);
        bridge.neverStarts = true;
        ToolBusyException busy = assertThrows(ToolBusyException.class, () -> context.onGameThread(() -> 2));
        assertTrue(busy.nothingRan(), "that step did not run, so a job may try the step again");
        assertFalse(busy.getMessage().contains("nothing changed"), busy.getMessage());
        assertTrue(busy.getMessage().contains("look before you retry"), busy.getMessage());
        JsonObject block = busy.toResult().getStructuredContent().getAsJsonObject("busy");
        assertTrue(block.get("earlierStepsRan").getAsBoolean());
    }
}
