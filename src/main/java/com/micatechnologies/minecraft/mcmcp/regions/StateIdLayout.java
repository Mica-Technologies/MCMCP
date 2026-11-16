package com.micatechnologies.minecraft.mcmcp.regions;

/**
 * Takes apart the int {@code Block.getStateId} packs a block id and a meta into.
 *
 * <p>Vanilla 1.12.2 packs {@code id | meta << 12}, so block ids stop at 4095. An id extender
 * (RoughlyEnoughIDs, JustEnoughIDs) keeps that for ids below 4096 and packs a larger id as
 * {@code id << 4 | meta}, which always sets a bit above the low 16, and decodes by the same test.
 * {@code getStateById} is patched along with it, but anything that takes the int apart by hand has
 * to follow the same rule: reading an extended id as vanilla named {@code csm:signexceptbus:3} as
 * {@code minecraft:fire:4} (#203, #204).
 *
 * <p>A vanilla id never sets a bit above the low 16, so this rule decodes vanilla ids unchanged.
 */
public final class StateIdLayout {

    private StateIdLayout() {
    }

    /** True when {@code stateId} is packed the extended way, {@code id << 4 | meta}. */
    public static boolean isExtended(int stateId) {
        return (stateId & 0xFFFF0000) != 0;
    }

    /** The block id packed in {@code stateId}. */
    public static int blockId(int stateId) {
        return isExtended(stateId) ? (stateId >>> 4) & 0x0FFFFFFF : stateId & 4095;
    }

    /** The meta packed in {@code stateId}. */
    public static int meta(int stateId) {
        return isExtended(stateId) ? stateId & 15 : (stateId >>> 12) & 15;
    }
}
