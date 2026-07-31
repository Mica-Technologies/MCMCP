package com.micatechnologies.minecraft.mcmcp;

/**
 * Mod identity, sourced from the {@code Tags} class the build generates.
 *
 * <p>{@code Tags} does not exist in the repository — the GTNH buildscript writes it into
 * {@code build/generated} from {@code buildscript.properties} at compile time (see
 * {@code generateGradleTokenClass} there). Reading identity through it rather than hard-coding
 * strings is what keeps the mod id, display name and version in the jar manifest, {@code mcmod.info}
 * and the code from drifting apart — in particular the version, which is derived from the git tag
 * by CI and is not written down anywhere a human edits.
 */
public class McmcpConstants {

    public static final String MOD_NAMESPACE = Tags.MODID;
    public static final String MOD_NAME = Tags.MODNAME;
    public static final String MOD_VERSION = Tags.VERSION;

    /**
     * The URI scheme MCMCP publishes its MCP resources under, e.g.
     * {@code minecraft://client/player/state}.
     *
     * <p>{@code minecraft} rather than {@code mcmcp}: the resources describe the game, not this mod,
     * and a model reading a URI list benefits from the scheme telling it what domain it is in.
     */
    public static final String RESOURCE_SCHEME = "minecraft";

    private McmcpConstants() {
    }
}
