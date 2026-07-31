package com.micatechnologies.minecraft.mcmcp.mcp;

import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;

/**
 * The single catalogue of tools, resources and prompts, shared by every MCMCP endpoint.
 *
 * <h2>Why one registry rather than one per endpoint</h2>
 *
 * The client endpoint and the server endpoint expose overlapping but not identical capabilities.
 * Duplicating registration per endpoint would mean two places to add a tool and two places to
 * forget. Instead everything registers once and declares which sides it supports
 * ({@link McpTool#isAvailableOn}), and each endpoint filters at list time. A tool that is not
 * available on an endpoint is not listed by it and cannot be called through it — the filter is
 * enforced on both paths, because a model that has seen a tool name once will try it again
 * elsewhere.
 *
 * <h2>Extension by other mods</h2>
 *
 * Registration is open: any mod may call {@link #registerTool} during or after its own init and the
 * tool becomes available to every connected client, with a {@code notifications/tools/list_changed}
 * emitted so live sessions pick it up without reconnecting. That is the point of MCMCP — the base
 * mod's own tools are just the first consumer of the same API.
 *
 * <p>Names are unique across the whole registry and collisions throw. Silently keeping the first or
 * last registration would make behaviour depend on mod load order, which is the kind of bug that
 * only reproduces on someone else's modpack.
 */
public final class McpRegistry {

    private static final Map<String, McpTool> TOOLS = new LinkedHashMap<>();
    private static final Map<String, McpResource> RESOURCES = new LinkedHashMap<>();
    private static final Map<String, McpPrompt> PROMPTS = new LinkedHashMap<>();

    /** Endpoints subscribe here so catalogue changes reach live sessions. */
    private static final List<ChangeListener> LISTENERS = new CopyOnWriteArrayList<>();

    private McpRegistry() {
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    public static synchronized void registerTool(McpTool tool) {
        if (TOOLS.containsKey(tool.getName())) {
            throw new IllegalArgumentException("A tool named '" + tool.getName() + "' is already registered");
        }
        TOOLS.put(tool.getName(), tool);
        fireToolsChanged();
    }

    public static synchronized void registerTools(Collection<McpTool> tools) {
        for (McpTool tool : tools) {
            if (TOOLS.containsKey(tool.getName())) {
                throw new IllegalArgumentException("A tool named '" + tool.getName() + "' is already registered");
            }
            TOOLS.put(tool.getName(), tool);
        }
        fireToolsChanged();
    }

    public static synchronized void registerResource(McpResource resource) {
        String key = resource.isTemplate() ? resource.getUriTemplate() : resource.getUri();
        if (RESOURCES.containsKey(key)) {
            throw new IllegalArgumentException("A resource at '" + key + "' is already registered");
        }
        RESOURCES.put(key, resource);
        fireResourcesChanged();
    }

    public static synchronized void registerPrompt(McpPrompt prompt) {
        if (PROMPTS.containsKey(prompt.getName())) {
            throw new IllegalArgumentException("A prompt named '" + prompt.getName() + "' is already registered");
        }
        PROMPTS.put(prompt.getName(), prompt);
        firePromptsChanged();
    }

    // ------------------------------------------------------------------
    // Lookup
    // ------------------------------------------------------------------

    /** Tools visible on {@code side}, in registration order. */
    public static synchronized List<McpTool> tools(McmcpSide side) {
        List<McpTool> visible = new ArrayList<>();
        for (McpTool tool : TOOLS.values()) {
            if (tool.isAvailableOn(side)) {
                visible.add(tool);
            }
        }
        return visible;
    }

    /**
     * Looks up a callable tool.
     *
     * <p>Returns null both when the name is unknown and when it exists but is not available on
     * {@code side}. The caller reports the same "unknown tool" either way: telling a client about a
     * tool it cannot reach only invites it to keep trying.
     */
    @Nullable
    public static synchronized McpTool tool(String name, McmcpSide side) {
        McpTool tool = TOOLS.get(name);
        return tool != null && tool.isAvailableOn(side) ? tool : null;
    }

    /** Concrete (non-templated) resources visible on {@code side}. */
    public static synchronized List<McpResource> resources(McmcpSide side) {
        List<McpResource> visible = new ArrayList<>();
        for (McpResource resource : RESOURCES.values()) {
            if (!resource.isTemplate() && resource.isAvailableOn(side)) {
                visible.add(resource);
            }
        }
        return visible;
    }

    /** Templated resource families visible on {@code side}. */
    public static synchronized List<McpResource> resourceTemplates(McmcpSide side) {
        List<McpResource> visible = new ArrayList<>();
        for (McpResource resource : RESOURCES.values()) {
            if (resource.isTemplate() && resource.isAvailableOn(side)) {
                visible.add(resource);
            }
        }
        return visible;
    }

    /**
     * Resolves a requested URI to the resource that serves it.
     *
     * <p>Exact matches win; failing that, every template is tried in registration order. Exact-first
     * matters because a template like {@code minecraft://world/chunk/{x}/{z}} would otherwise
     * shadow a concrete {@code minecraft://world/chunk/spawn} registered alongside it.
     */
    @Nullable
    public static synchronized McpResource resolveResource(String uri, McmcpSide side) {
        McpResource exact = RESOURCES.get(uri);
        if (exact != null && !exact.isTemplate() && exact.isAvailableOn(side)) {
            return exact;
        }
        for (McpResource resource : RESOURCES.values()) {
            if (resource.isTemplate()
                && resource.isAvailableOn(side)
                && UriTemplates.matches(resource.getUriTemplate(), uri)) {
                return resource;
            }
        }
        return null;
    }

    public static synchronized List<McpPrompt> prompts(McmcpSide side) {
        List<McpPrompt> visible = new ArrayList<>();
        for (McpPrompt prompt : PROMPTS.values()) {
            if (prompt.isAvailableOn(side)) {
                visible.add(prompt);
            }
        }
        return visible;
    }

    @Nullable
    public static synchronized McpPrompt prompt(String name, McmcpSide side) {
        McpPrompt prompt = PROMPTS.get(name);
        return prompt != null && prompt.isAvailableOn(side) ? prompt : null;
    }

    /** Every registered tool name, for the {@code /mcmcp tools} command and diagnostics. */
    public static synchronized List<String> allToolNames() {
        return Collections.unmodifiableList(new ArrayList<>(TOOLS.keySet()));
    }

    // ------------------------------------------------------------------
    // Change notification
    // ------------------------------------------------------------------

    public static void addChangeListener(ChangeListener listener) {
        LISTENERS.add(listener);
    }

    public static void removeChangeListener(ChangeListener listener) {
        LISTENERS.remove(listener);
    }

    /**
     * Announces that a subscribable resource's contents changed.
     *
     * <p>Called from gameplay code — a chat message arriving, the player's inventory changing — so
     * it must stay cheap and must never block: it runs on the game thread. Delivery is a queue
     * append per subscribed session and nothing more.
     */
    public static void notifyResourceUpdated(String uri) {
        for (ChangeListener listener : LISTENERS) {
            try {
                listener.onResourceUpdated(uri);
            }
            catch (Exception e) {
                Mcmcp.LOGGER.error("MCMCP resource-update listener failed for " + uri, e);
            }
        }
    }

    private static void fireToolsChanged() {
        for (ChangeListener listener : LISTENERS) {
            try {
                listener.onToolsChanged();
            }
            catch (Exception e) {
                Mcmcp.LOGGER.error("MCMCP tools-changed listener failed", e);
            }
        }
    }

    private static void fireResourcesChanged() {
        for (ChangeListener listener : LISTENERS) {
            try {
                listener.onResourcesChanged();
            }
            catch (Exception e) {
                Mcmcp.LOGGER.error("MCMCP resources-changed listener failed", e);
            }
        }
    }

    private static void firePromptsChanged() {
        for (ChangeListener listener : LISTENERS) {
            try {
                listener.onPromptsChanged();
            }
            catch (Exception e) {
                Mcmcp.LOGGER.error("MCMCP prompts-changed listener failed", e);
            }
        }
    }

    public interface ChangeListener {

        void onToolsChanged();

        void onResourcesChanged();

        void onPromptsChanged();

        void onResourceUpdated(String uri);
    }
}
