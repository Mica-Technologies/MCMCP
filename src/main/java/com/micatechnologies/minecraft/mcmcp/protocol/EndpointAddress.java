package com.micatechnologies.minecraft.mcmcp.protocol;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Who this endpoint is, as the orchestrator would name it, and whether an orchestrator is linked.
 *
 * <h2>Why a direct endpoint needs to know its orchestrator name</h2>
 *
 * The orchestrator requires {@code instance} on destructive calls, and a game's own HTTP endpoint
 * used to refuse it as an unknown argument. One helper could not talk to both: the arguments an
 * agent learned against the orchestrator failed against the game it was addressing. A direct
 * endpoint therefore accepts an {@code instance} that names itself and drops it before the tool
 * sees it, and refuses one that names a different game — that call was meant for somewhere else,
 * and running it here would act on the wrong world.
 *
 * <p>It also carries the one fact the direct endpoint cannot otherwise tell a caller: that the same
 * game is reachable through an orchestrator, which is usually the better door.
 */
public final class EndpointAddress {

    /** The orchestrator's "every instance" value, which trivially includes this one. */
    public static final String ALL_INSTANCES = "*";

    /** Mirrors the orchestrator's {@code ENDPOINT_SEPARATOR}: {@code <game id>.<side>}. */
    public static final char ENDPOINT_SEPARATOR = '.';

    /** An address for an endpoint with no identity — tests, and anything built before config loads. */
    public static final EndpointAddress NONE = new EndpointAddress(null, null, () -> null, () -> false);

    @Nullable
    private final String instanceId;
    @Nullable
    private final String sideId;
    private final Supplier<String> assignedName;
    private final BooleanSupplier orchestratorLinked;

    /**
     * @param instanceId         the game's id, shared by both sides of a singleplayer game
     * @param sideId             {@code client} or {@code server}
     * @param assignedName       the label a human gave this instance in the orchestrator, or null
     * @param orchestratorLinked whether the orchestrator link is connected right now
     */
    public EndpointAddress(@Nullable String instanceId, @Nullable String sideId,
                           Supplier<String> assignedName, BooleanSupplier orchestratorLinked) {
        this.instanceId = instanceId;
        this.sideId = sideId;
        this.assignedName = assignedName;
        this.orchestratorLinked = orchestratorLinked;
    }

    /** The id the orchestrator addresses this endpoint by, e.g. {@code atm9-3f2a1c.client}. */
    @Nullable
    public String addressableId() {
        if (instanceId == null || instanceId.isEmpty() || sideId == null) {
            return null;
        }
        return instanceId + ENDPOINT_SEPARATOR + sideId;
    }

    /**
     * Whether an {@code instance} argument names this endpoint.
     *
     * <p>Accepts what the orchestrator would route here: the full addressable id, {@code *}, the
     * bare game id (on a direct endpoint there is only one side to mean), and the label a human
     * assigned. Case-insensitive, because the ids are lower-case slugs and labels are typed by hand.
     */
    public boolean answersTo(@Nullable String requested) {
        if (requested == null) {
            return false;
        }
        String wanted = requested.trim();
        if (wanted.equals(ALL_INSTANCES)) {
            return true;
        }
        String self = addressableId();
        if (self != null && (wanted.equalsIgnoreCase(self) || wanted.equalsIgnoreCase(instanceId))) {
            return true;
        }
        String label = assignedName.get();
        return label != null && !label.isEmpty()
            && wanted.toLowerCase(Locale.ROOT).equals(label.trim().toLowerCase(Locale.ROOT));
    }

    /** The refusal for an {@code instance} naming some other game. */
    public String describeMismatch(String requested) {
        String self = addressableId();
        return "This is " + (self == null ? "a single game's own MCMCP endpoint" : "the endpoint of '" + self + "'")
            + ", but 'instance' names '" + requested + "'. Nothing was done. Call that game's own "
            + "endpoint, or the MCMCP orchestrator, which routes by 'instance'.";
    }

    /**
     * The reminder a direct client gets while an orchestrator link is up, or null when there is
     * nothing to steer towards.
     */
    @Nullable
    public String steeringNote() {
        String self = addressableId();
        if (self == null || !orchestratorLinked.getAsBoolean()) {
            return null;
        }
        return "Note: this game is also connected to the MCMCP orchestrator as instance '" + self + "'. "
            + "If your MCP client has the orchestrator configured (usually the server named "
            + "'minecraft'), prefer it: one connection reaches every running game and each result says "
            + "which game produced it. Pass instance: '" + self + "' there to address this game. This "
            + "direct endpoint keeps working, and accepts that same 'instance' argument.";
    }
}
