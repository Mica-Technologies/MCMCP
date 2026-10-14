package com.micatechnologies.minecraft.mcmcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.micatechnologies.minecraft.mcmcp.game.GameThreadBridge;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.protocol.EndpointAddress;
import com.micatechnologies.minecraft.mcmcp.protocol.McpDispatcher;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSessionManager;
import java.io.IOException;
import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

class HttpMcpTransportTest {

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
            throw new IllegalStateException("no game");
        }

        @Override
        public void runOnGameThread(Runnable task) {
            throw new IllegalStateException("no game");
        }

        @Override
        public McmcpSide getSide() {
            return McmcpSide.SERVER;
        }
    };

    private static HttpMcpTransport transport(int port, boolean fallback) {
        McpDispatcher dispatcher = new McpDispatcher(McmcpSide.SERVER, NO_GAME, 1000L, "test");
        dispatcher.setAddress(new EndpointAddress("game-abc123", "server", () -> null, () -> false));
        McpEndpointSettings settings = McpEndpointSettings.builder().port(port).portFallback(fallback).build();
        return new HttpMcpTransport(settings, dispatcher, new McpSessionManager(60_000L, 2));
    }

    @Test
    void aTakenPortFallsBackToAFreeOneAndSaysWhichGameAnswers() throws IOException {
        try (ServerSocket taken = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            HttpMcpTransport http = transport(taken.getLocalPort(), true);
            http.start();
            try {
                assertTrue(http.fellBack());
                assertNotEquals("http://127.0.0.1:" + taken.getLocalPort() + "/mcp", http.describeTarget(),
                    "the URL reported is the one actually listening, not the configured one");
                HttpURLConnection health = (HttpURLConnection) new URL(http.describeTarget() + "/health").openConnection();
                assertEquals(200, health.getResponseCode());
                assertEquals("game-abc123.server", health.getHeaderField("Mcmcp-Instance"),
                    "a script can tell which game it reached before it writes anything");
            }
            finally {
                http.stop();
            }
        }
    }

    @Test
    void withFallbackOffATakenPortStillFailsToBind() throws IOException {
        try (ServerSocket taken = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            HttpMcpTransport http = transport(taken.getLocalPort(), false);
            assertThrows(BindException.class, http::start);
            assertFalse(http.isRunning());
        }
    }
}
