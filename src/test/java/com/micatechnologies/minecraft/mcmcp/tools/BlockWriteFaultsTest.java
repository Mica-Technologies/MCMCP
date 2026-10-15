package com.micatechnologies.minecraft.mcmcp.tools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which frame a bulk write blames when a block's own code throws (#81). The reason it builds is the
 * only thing a caller learns about the fault, so it has to name the mod, not the vanilla method the
 * mod called into.
 */
class BlockWriteFaultsTest {

    private static StackTraceElement frame(String className, String method) {
        return new StackTraceElement(className, method, null, -1);
    }

    @Test
    @DisplayName("blames the mod's hook, past the vanilla and MCMCP frames around it")
    void blamesTheModsHookPastTheVanillaAndMcmcpFramesAroundIt() {
        IllegalArgumentException e = new IllegalArgumentException("Cannot get property");
        e.setStackTrace(new StackTraceElement[] {
            frame("net.minecraft.block.state.BlockStateContainer$StateImplementation", "getValue"),
            frame("com.example.furniture.BlockTall", "breakBlock"),
            frame("net.minecraft.world.chunk.Chunk", "setBlockState"),
            frame("net.minecraft.world.World", "setBlockState"),
            frame("com.micatechnologies.minecraft.mcmcp.tools.ServerApplyOps", "writeCell"),
        });

        StackTraceElement blamed = BlockWriteFaults.blamed(e);

        assertThat(blamed.getClassName(), is("com.example.furniture.BlockTall"));
        assertThat(blamed.getMethodName(), is("breakBlock"));
    }

    @Test
    @DisplayName("falls back to the top frame when only the platform is on the stack")
    void fallsBackToTheTopFrameWhenOnlyThePlatformIsOnTheStack() {
        IllegalStateException e = new IllegalStateException();
        e.setStackTrace(new StackTraceElement[] {
            frame("net.minecraft.world.chunk.Chunk", "setBlockState"),
            frame("net.minecraft.world.World", "setBlockState"),
        });

        assertThat(BlockWriteFaults.blamed(e).getClassName(), is("net.minecraft.world.chunk.Chunk"));
    }

    @Test
    @DisplayName("does not mistake another Mica Technologies mod for MCMCP")
    void doesNotMistakeAnotherMicaTechnologiesModForMcmcp() {
        assertThat(BlockWriteFaults.isPlatform("com.micatechnologies.minecraft.csm.furniture.BlockTall"), is(false));
        assertThat(BlockWriteFaults.isPlatform("com.micatechnologies.minecraft.mcmcp.tools.TwoPartBlocks"), is(true));
    }
}
