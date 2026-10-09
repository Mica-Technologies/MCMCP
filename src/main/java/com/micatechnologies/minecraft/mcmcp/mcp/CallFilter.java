package com.micatechnologies.minecraft.mcmcp.mcp;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import javax.annotation.Nullable;

/**
 * Decides, per session, which tools a dispatcher lists and runs.
 *
 * <p>An endpoint's dispatcher has none: everything registered for its side is listed and callable,
 * and the config's permission switches answer inside each tool. The companion's dispatcher has one,
 * because its callers are players with differing grants, and a tool listed to a caller who can never
 * run it is a plan that cannot work.
 *
 * <p>Applied at list time and at call time alike. The call-time check is the one that matters for
 * safety; the list is what keeps a model from planning around tools it cannot use.
 */
public interface CallFilter {

    /** Whether {@code tool} appears in this session's {@code tools/list}. */
    boolean lists(McpSession session, McpTool tool);

    /**
     * Admits a call or says why not. May rewrite {@code arguments} first — to fill in or check an
     * argument that names a player, for instance.
     *
     * @return null to run the call, else the refusal, returned to the caller as a tool error
     */
    @Nullable
    String admit(McpSession session, McpTool tool, JsonObject arguments);

    /** Whether resources and prompts are served to this session at all. */
    boolean servesResourcesAndPrompts(McpSession session);
}
