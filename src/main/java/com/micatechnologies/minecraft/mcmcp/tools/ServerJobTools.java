package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkLoadGovernor;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionPolicy;
import com.micatechnologies.minecraft.mcmcp.game.Estop;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.jobs.Job;
import com.micatechnologies.minecraft.mcmcp.jobs.JobManager;
import com.micatechnologies.minecraft.mcmcp.jobs.JobParts;
import com.micatechnologies.minecraft.mcmcp.json.ArgumentNames;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.Principal;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import javax.annotation.Nullable;

/**
 * Background jobs (#73) and the emergency stop's agent-facing half.
 *
 * <p>A companion caller sees and controls only their own jobs; the endpoint sees all. Submitting checks
 * the caller's access to the tool the job will run, since the parts call it directly rather than
 * through the companion's filter.
 */
public final class ServerJobTools {

    /** Longest one status call waits; a client gives up on a call that says nothing for much longer. */
    static final int MAX_WAIT_SECONDS = 120;

    private ServerJobTools() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("server_job_submit")
            .title("Start a background job")
            .description("Start a long write or read as a background job and return its id at once: kind "
                + "'apply_ops' (server_apply_ops' arguments; any length), 'set_blocks' (server_set_blocks' "
                + "arguments; cells, blocks or a fill of any size), 'get_blocks' (server_get_blocks' arguments "
                + "over a box of any size; results come back as pages), 'relight', or 'test' (server_run_test's "
                + "arguments; its verdict is in server_job_status). The server splits it into "
                + "ordinary calls and works through them at its own pace, pausing while it struggles. Poll "
                + "server_job_status; the job survives your script or client restarting, and its chunk holds are "
                + "its own.")
            .schema(JsonSchema.object()
                .enumeration("kind", "What to run.", "apply_ops", "set_blocks", "get_blocks", "relight", "test")
                .property("args", Json.obj("type", "object"))
                .string("label", "A name to know it by in lists and the orchestrator.")
                .required("kind", "args")
                .build())
            .serverOnly()
            .destructive()
            .offGameThread()
            .handler(ServerJobTools::submit)
            .build());

        McpRegistry.registerTool(McpTool.named("server_job_status")
            .title("Read a job's status")
            .description("A background job's state (queued, running, paused, done, failed, cancelled, "
                + "interrupted), parts done of parts, percent, eta, totals summed over its parts, and for an "
                + "op replay 'lastOp', the last op finished: resume an interrupted job from lastOp + 1. A "
                + "get_blocks job's results are pages: pass 'page' to read one. 'wait' holds the call until "
                + "the job's state changes (it finishes, fails, pauses...) or that many seconds pass: one "
                + "call instead of a poll per few seconds.")
            .schema(JsonSchema.object()
                .string("id", "The job id.")
                .integer("page", "A get_blocks job's page to return, from 0.", 0, 1_000_000)
                .integer("wait", "Seconds to wait for the job's state to change before answering. Default 0.",
                    0, MAX_WAIT_SECONDS)
                .required("id")
                .build())
            .serverOnly()
            .readOnly()
            .offGameThread()
            .handler(ServerJobTools::status)
            .build());

        McpRegistry.registerTool(McpTool.named("server_job_list")
            .title("List jobs")
            .description("Your background jobs, newest last, with each one's state and progress. Jobs are kept "
                + "30 minutes after they end, and interrupted ones a day.")
            .schema(JsonSchema.object().build())
            .serverOnly()
            .readOnly()
            .offGameThread()
            .handler(ServerJobTools::list)
            .build());

        for (final String verb : new String[] {"cancel", "pause", "resume"}) {
            McpRegistry.registerTool(McpTool.named("server_job_" + verb)
                .title(Character.toUpperCase(verb.charAt(0)) + verb.substring(1) + " a job")
                .description("cancel".equals(verb)
                    ? "Cancel a background job. It stops at its next step; what it already wrote stays, with "
                        + "its undo points."
                    : "pause".equals(verb)
                        ? "Pause a background job at its next step, keeping its place."
                        : "Resume a job you paused, or one an emergency stop paused once the stop is lifted.")
                .schema(JsonSchema.object().string("id", "The job id.").required("id").build())
                .serverOnly()
                .idempotent()
                .offGameThread()
                .handler(context -> control(context, verb))
                .build());
        }

        McpRegistry.registerTool(McpTool.named("server_estop")
            .title("Emergency stop")
            .description("Stop everything MCMCP is doing to this server's world, at once: every call that "
                + "changes the world or moves a player is refused, running calls stop at their next step, every "
                + "job pauses, and connected clients' MCMCP stops driving their players. Use it when something "
                + "is going wrong faster than you can fix it. Only an operator can lift it (/mcmcp estop "
                + "reset); this tool cannot.")
            .schema(JsonSchema.object()
                .string("reason", "What went wrong, for the operator who lifts it.")
                .required("reason")
                .build())
            .serverOnly()
            .idempotent()
            .offGameThread()
            .handler(context -> {
                Principal principal = context.getPrincipal();
                String by = principal.isCompanion() ? principal.getPlayerName() + " (agent)" : "an agent";
                if (Estop.active()) {
                    return ToolResult.text("MCMCP is already emergency-stopped. " + Estop.refusal());
                }
                Estop.set(by, context.getString("reason", ""));
                Mcmcp.LOGGER.warn("MCMCP EMERGENCY STOP by " + by + ": " + context.getString("reason", ""));
                return ToolResult.text("Emergency stop set. An operator lifts it with /mcmcp estop reset.");
            })
            .build());
    }

    private static ToolResult submit(ToolContext context) {
        if (Estop.active()) {
            return ToolResult.error(Estop.refusal());
        }
        String kind = context.getString("kind", "");
        String toolName = JobParts.toolFor(kind);
        if (toolName == null) {
            return ToolResult.error("'kind' is one of apply_ops, set_blocks, get_blocks, relight, test.");
        }
        McpTool tool = McpRegistry.tool(toolName, McmcpSide.SERVER);
        if (tool == null) {
            return ToolResult.error(toolName + " is not available on this server.");
        }
        Principal principal = context.getPrincipal();
        if (principal.isCompanion()) {
            String callClass = CompanionPolicy.classOf(toolName);
            if (callClass == null || !principal.has(callClass)) {
                return ToolResult.error("A " + kind + " job runs " + toolName + ", and your companion access does not "
                    + "include '" + callClass + "' calls.");
            }
        }
        JsonObject args = Json.getObject(context.getArguments(), "args");
        if (args == null) {
            return ToolResult.error("'args' is the arguments " + toolName + " would take, as an object.");
        }
        String unknown = ArgumentNames.describeUnknown(toolName, tool.getInputSchema(), args);
        if (unknown != null) {
            return ToolResult.error(unknown);
        }
        int maxWrite = McmcpConfig.getMaxWriteCells();
        int volume = McmcpConfig.getMaxBlockVolume();
        // Write parts no bigger than the writes-per-minute budget, so a job paces to it a part at a
        // time (#131) rather than one oversized part waiting for the whole minute to clear.
        long perMinute = McmcpConfig.getWriteCellsPerMinutePerCaller();
        JobParts.Limits limits = new JobParts.Limits(Math.min(maxWrite, perMinute),
            Math.min((long) maxWrite * 4L, perMinute), (long) volume * 32L, (long) volume * 32L, volume, 4_194_304L);
        Job job;
        try {
            job = JobManager.submit(kind, tool, args, context.getString("label", null),
                principal.isCompanion() ? principal.getPlayerName() : "endpoint",
                ChunkLoadGovernor.callerOf(principal), context.getSession(), limits);
        }
        catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        JsonObject json = job.toJson(false);
        json.addProperty("note", "Poll server_job_status with this id.");
        return ToolResult.structured(json);
    }

    private static ToolResult status(ToolContext context) throws InterruptedException {
        Job job = owned(context);
        if (job == null) {
            return ToolResult.error("You have no job '" + context.getString("id", "") + "'.");
        }
        int wait = context.getBoundedInt("wait", 0, 0, MAX_WAIT_SECONDS);
        if (wait > 0 && !job.state().settled()) {
            // In one-second slices, so a cancelled call stops waiting within a second.
            Job.State from = job.state();
            long end = System.currentTimeMillis() + wait * 1000L;
            while (job.state() == from && System.currentTimeMillis() < end) {
                context.getCancellation().throwIfCancelled();
                job.awaitStateChange(from, Math.min(1000L, end - System.currentTimeMillis()));
                context.reportProgress((System.currentTimeMillis() - (end - wait * 1000L)) / 1000.0D, wait,
                    "Waiting on job " + job.id);
            }
        }
        JsonObject json = job.toJson(true);
        if (context.has("page")) {
            int index = context.getInt("page", 0);
            Job.Page page = job.page(index);
            if (page == null) {
                return ToolResult.error("Job " + job.id + " has " + job.pageCount() + " page(s) so far; page " + index
                    + " is not one of them.");
            }
            json.addProperty("page", index);
            json.add("content", page.content);
        }
        return ToolResult.structured(json);
    }

    private static ToolResult list(ToolContext context) {
        JsonArray jobs = new JsonArray();
        String key = ChunkLoadGovernor.callerOf(context.getPrincipal());
        boolean all = !context.getPrincipal().isCompanion();
        for (Job job : JobManager.jobs()) {
            if (all || job.ownerKey.equals(key)) {
                jobs.add(job.toJson(false));
            }
        }
        JsonObject json = new JsonObject();
        json.add("jobs", jobs);
        if (Estop.active()) {
            json.addProperty("estop", Estop.refusal());
        }
        return ToolResult.structured(json);
    }

    private static ToolResult control(ToolContext context, String verb) {
        Job job = owned(context);
        if (job == null) {
            return ToolResult.error("You have no job '" + context.getString("id", "") + "'.");
        }
        boolean ok;
        switch (verb) {
            case "cancel":
                ok = job.cancel();
                break;
            case "pause":
                ok = job.pause(Job.Hold.OWNER);
                break;
            default:
                if (Estop.active()) {
                    return ToolResult.error(Estop.refusal());
                }
                // An owner may lift what they and an emergency stop set, not an operator's pause.
                if (job.isPausedBy(Job.Hold.OPERATOR) && context.getPrincipal().isCompanion()) {
                    job.resume(Job.Hold.OWNER);
                    return ToolResult.error("An operator paused job " + job.id + "; an operator resumes it with "
                        + "/mcmcp jobs resume " + job.id + ".");
                }
                job.resume(Job.Hold.OWNER);
                ok = job.resume(Job.Hold.OPERATOR);
                break;
        }
        if (!ok) {
            return ToolResult.error("Job " + job.id + " has already ended (" + job.state().id() + ").");
        }
        return ToolResult.structured(job.toJson(false));
    }

    @Nullable
    private static Job owned(ToolContext context) {
        Job job = JobManager.get(context.getString("id", ""));
        if (job == null) {
            return null;
        }
        Principal principal = context.getPrincipal();
        return !principal.isCompanion() || job.ownerKey.equals(ChunkLoadGovernor.callerOf(principal)) ? job : null;
    }
}
