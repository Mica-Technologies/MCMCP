package com.micatechnologies.minecraft.mcmcp.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class LogRedactionTest {

    @Test
    void aPlayersAddressAndPortAreMaskedWhereTheyJoinAndWhereTheyAreDropped() {
        assertEquals("[14:31:06] [Server thread/INFO]: Akselhok[/<address>] logged in with entity id 412 at (1, 2, 3)",
            LogRedaction.maskAddresses(
                "[14:31:06] [Server thread/INFO]: Akselhok[/203.0.113.7:51234] logged in with entity id 412 at (1, 2, 3)"));
        assertEquals("com.mojang.authlib.GameProfile@1f[id=<null>,name=Bob] (/<address>) lost connection: Timed out",
            LogRedaction.maskAddresses(
                "com.mojang.authlib.GameProfile@1f[id=<null>,name=Bob] (/10.0.0.12:60111) lost connection: Timed out"));
    }

    @Test
    void theReasonAConnectionWasLostIsLeftWhole() {
        String line = "[14:31:09] [Server thread/INFO]: Akselhok lost connection: Internal Exception: "
            + "io.netty.handler.codec.EncoderException: String too big (was 135804 bytes encoded, max 32767)";
        assertEquals(line, LogRedaction.maskAddresses(line));
    }

    @Test
    void anIpv6AddressAfterASlashIsMaskedBracketedOrNot() {
        assertEquals("Carol[/<address>] logged in",
            LogRedaction.maskAddresses("Carol[/[2001:db8:0:0:0:0:0:1]:51234] logged in"));
        assertEquals("Carol[/<address>] logged in",
            LogRedaction.maskAddresses("Carol[/0:0:0:0:0:0:0:1:51234] logged in"));
    }

    @Test
    void timestampsVersionsAndCoordinatesAreNotAddresses() {
        for (String line : new String[] {
            "[14:31:09] [Netty Epoll Server IO #13/ERROR]: NetworkDispatcher exception",
            "Forge Mod Loader version 14.23.5.2860 for Minecraft 1.12.2 loading",
            "MinecraftForge v14.23.5.2860 Initialized",
            "Teleported MicaAlex to -600.5, 86.0, -5883.5",
            "Loading mod csm-1.12.2-3.4.10.jar from mods/1.12.2/csm.jar",
            "at net.minecraft.network.PacketBuffer.writeString(PacketBuffer.java:400)"}) {
            assertEquals(line, LogRedaction.maskAddresses(line), line);
        }
    }

    @Test
    void anAddressWithNoPortAndOneInsideOtherTextAreMasked() {
        String masked = LogRedaction.maskAddresses("Disconnecting 198.51.100.23: banned; rcon from 192.168.1.5:25575.");
        assertFalse(masked.contains("198.51"), masked);
        assertFalse(masked.contains("192.168"), masked);
        assertEquals("Disconnecting <address>: banned; rcon from <address>.", masked);
    }
}
