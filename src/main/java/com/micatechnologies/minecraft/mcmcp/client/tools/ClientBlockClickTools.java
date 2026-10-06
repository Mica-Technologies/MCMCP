package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.client.ClientFrameClock;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.tools.GameJson;
import java.util.Locale;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.CPacketEntityAction;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Clicking a named block face, without aiming the camera at it first.
 *
 * <h2>Why this exists</h2>
 *
 * {@code client_interact} acts on whatever the crosshair is on, so using an item on a block was
 * always two calls: {@code client_look} at a computed point, then the click, and a read-back to see
 * whether the ray hit the box that was meant (issue #45). Where on the block the click lands is not a
 * detail for some mods: Immersive Engineering picks the wire connector from the hit point. And
 * hand placement is the only way some blocks get set up at all — {@code /setblock} leaves a
 * directional tile entity's saved facing at its default.
 *
 * <h2>The same path a right-click takes</h2>
 *
 * {@code client_use_on_block} runs what {@code Minecraft.rightClickMouse} runs for a block hit:
 * {@code PlayerControllerMP.processRightClickBlock}, which fires Forge's event, calls the item's
 * {@code onItemUseFirst} and the block's {@code onBlockActivated} on the client, and sends the use
 * packet. The server then applies its own reach check and placement rules, as it would for a person.
 * What is skipped is the ray trace that picks the target, which is the point.
 *
 * <h2>Rotation and sneak travel as packets, in order</h2>
 *
 * A placed block's facing is decided on the server, from the server's copy of the player's
 * rotation, and whether a click sneaks is decided from the server's copy of the sneak state. Both
 * normally reach it from {@code onUpdateWalkingPlayer} once a tick, so a rotation set and restored
 * inside one task would never be sent at all, and the block would face wherever the player did.
 *
 * <p>So a requested yaw, pitch or sneak is sent as the packets the player's own update would send —
 * a rotation, a start-sneaking action — immediately before the use packet, and the originals
 * immediately after. A connection delivers in order and the server applies them in order, so the
 * use sees the requested state and nothing after it does. The client's own fields are set for the
 * same span, so its local prediction of the click agrees; nothing renders in between, so the
 * camera never visibly moves. {@code lastReportedYaw} and {@code serverSneakState} are left alone:
 * they still describe what the server holds once the restoring packets land, so the next tick
 * sends nothing extra.
 */
@SideOnly(Side.CLIENT)
public final class ClientBlockClickTools {

    /** Ticks given to the server to answer before the block is read back. */
    private static final int SETTLE_TICKS = 2;

    private static final long SETTLE_TIMEOUT_MILLIS = 1000L;

    private static final String[] FACES = {"down", "up", "north", "south", "west", "east"};

    private ClientBlockClickTools() {
    }

    public static void register() {
        registerUseOnBlock();
        registerAttackBlock();
    }

    // ------------------------------------------------------------------
    // Shared
    // ------------------------------------------------------------------

    /** What a block-click tool was asked to click, resolved and checked on the client thread. */
    private static final class Target {

        final BlockPos pos;
        final EnumFacing face;
        final Vec3d hit;

        Target(BlockPos pos, EnumFacing face, Vec3d hit) {
            this.pos = pos;
            this.face = face;
            this.hit = hit;
        }
    }

    @Nullable
    private static EnumFacing parseFace(String name) {
        return EnumFacing.byName(name.toLowerCase(Locale.ROOT));
    }

    /**
     * The default hit point for a face: its centre. Within the block that is 0.5 on the two axes the
     * face spans, and 0 or 1 on the axis it faces along.
     */
    private static double defaultHit(EnumFacing face, EnumFacing.Axis axis) {
        if (face.getAxis() != axis) {
            return 0.5D;
        }
        return face.getAxisDirection() == EnumFacing.AxisDirection.POSITIVE ? 1.0D : 0.0D;
    }

    /**
     * Reads x, y, z, face and the hit fractions, and checks the target the way a player's own click
     * is limited: loaded, not air, within reach of the eyes.
     *
     * @throws IllegalStateException with a message a model can act on, when the click could not
     *                               happen for a player either
     */
    private static Target resolve(Minecraft mc, ToolContext context, EnumFacing face) {
        BlockPos pos = new BlockPos(context.requireInt("x"), context.requireInt("y"),
            context.requireInt("z"));
        double hitX = context.getDouble("hitX", defaultHit(face, EnumFacing.Axis.X));
        double hitY = context.getDouble("hitY", defaultHit(face, EnumFacing.Axis.Y));
        double hitZ = context.getDouble("hitZ", defaultHit(face, EnumFacing.Axis.Z));
        for (double fraction : new double[]{hitX, hitY, hitZ}) {
            if (fraction < 0.0D || fraction > 1.0D) {
                throw new IllegalArgumentException("hitX, hitY and hitZ are fractions of the block, "
                    + "0 to 1; got " + fraction + ".");
            }
        }

        if (!GameJson.isLoaded(mc.world, pos)) {
            throw new IllegalStateException("The chunk holding " + describe(pos) + " is not loaded "
                + "on this client. Move closer, or wait with client_wait waitFor 'chunksLoaded'.");
        }
        if (mc.world.getBlockState(pos).getMaterial() == Material.AIR) {
            throw new IllegalStateException(describe(pos) + " is air, and a player cannot click "
                + "air. To place a block there, click the face of a neighbouring block that "
                + "touches it: for example x, y-1, z with face 'up'.");
        }

        // Absolute, because processRightClickBlock subtracts the position itself to get the
        // fractions the packet carries.
        Vec3d hit = new Vec3d(pos.getX() + hitX, pos.getY() + hitY, pos.getZ() + hitZ);
        double reach = mc.playerController.getBlockReachDistance();
        double distance = mc.player.getPositionEyes(1.0F).distanceTo(hit);
        if (distance > reach) {
            throw new IllegalStateException("That point is " + round(distance) + " blocks from the "
                + "player's eyes, and this player reaches " + round(reach) + ". Move or teleport "
                + "closer first.");
        }
        return new Target(pos, face, hit);
    }

    /** A block as a short object: its id, and its state when it has one. */
    private static JsonObject brief(World world, BlockPos pos) {
        JsonObject full = GameJson.block(world, pos);
        JsonObject json = new JsonObject();
        json.add("position", full.get("position"));
        if (full.has("block")) {
            json.add("block", full.get("block"));
        }
        if (full.has("state") && full.getAsJsonObject("state").size() > 0) {
            json.add("state", full.get("state"));
        }
        if (full.has("actualState")) {
            json.add("actualState", full.get("actualState"));
        }
        return json;
    }

    private static String describe(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private static void settle() throws InterruptedException {
        // On the worker, not the client thread: the ticks being waited for are the client's own.
        ClientFrameClock.await(0, SETTLE_TICKS, SETTLE_TIMEOUT_MILLIS);
    }

    // ------------------------------------------------------------------
    // Use
    // ------------------------------------------------------------------

    private static void registerUseOnBlock() {
        McpRegistry.registerTool(McpTool.named("client_use_on_block")
            .title("Use the held item on a block")
            .description("Right-click a given face of a given block with the held item, without "
                + "aiming the camera: place a block against it, open it, click a wire coil onto a "
                + "connector, use a wrench on it.\n\n"
                + "Runs the same client path a real right-click on that block runs, so the item's "
                + "and block's own handlers and the server's placement rules all apply. The block "
                + "must be in reach of the player's eyes (about 4.5 blocks in survival, 5 in "
                + "creative) and must not be air: to place a block at P, click the face of a "
                + "neighbour of P that touches it.\n\n"
                + "hitX/hitY/hitZ say where on the block the click lands, as fractions 0-1 of the "
                + "block; they default to the centre of the face. Some mods pick what was clicked "
                + "from this point.\n\n"
                + "A placed block's facing comes from the player's rotation. Pass yaw (and pitch) to "
                + "place it as if the player faced that way; the camera does not move. Yaw 0 faces "
                + "south, 90 west, 180 north, 270 east.\n\n"
                + "Returns the clicked block and the block on the clicked face's side, before and "
                + "after, and the held items afterwards.")
            .schema(JsonSchema.object()
                .integer("x", "Block X.")
                .integer("y", "Block Y.")
                .integer("z", "Block Z.")
                .enumeration("face", "Which face of the block is clicked.", FACES)
                .number("hitX", "Where on the block, as a fraction of it along X, 0-1. Defaults to "
                    + "the centre of the face.", 0.0D, 1.0D)
                .number("hitY", "As hitX, along Y.", 0.0D, 1.0D)
                .number("hitZ", "As hitX, along Z.", 0.0D, 1.0D)
                .enumeration("hand", "Which hand's item to use. Omit to try the main hand and then "
                    + "the off hand, as a real right-click does.", "main", "off")
                .bool("sneak", "Click as a sneaking player: places against a block that would "
                    + "otherwise open, and is how many wrenches pick up instead of rotate.")
                .number("yaw", "Player yaw the server sees for this click, for a placed block's "
                    + "facing. Omit to use the current yaw.")
                .number("pitch", "Player pitch the server sees for this click, -90 to 90. Omit to "
                    + "use the current pitch.", -90.0D, 90.0D)
                .required("x", "y", "z", "face")
                .build())
            .clientOnly()
            .destructive()
            .handler(context -> {
                if (!McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("Player control is disabled by "
                        + "permissions.allowPlayerControl in the MCMCP config.");
                }
                final EnumFacing face = parseFace(context.requireString("face"));
                if (face == null) {
                    return ToolResult.error("Unknown face '" + context.requireString("face")
                        + "'; use one of " + String.join(", ", FACES) + ".");
                }
                final String handName = context.getString("hand", null);
                final boolean sneak = context.getBoolean("sneak", false);
                final boolean turn = context.has("yaw") || context.has("pitch");

                final JsonObject before = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = ClientStateTools.requireInWorld();
                        Target target = resolve(mc, context, face);
                        EntityPlayerSP player = mc.player;

                        JsonObject json = new JsonObject();
                        json.add("target", brief(mc.world, target.pos));
                        json.add("beside", brief(mc.world, target.pos.offset(face)));

                        float yaw = player.rotationYaw;
                        float pitch = player.rotationPitch;
                        boolean wasSneaking = player.movementInput.sneak;
                        float useYaw = turn ? MathHelper.wrapDegrees(
                            (float) context.getDouble("yaw", yaw)) : yaw;
                        float usePitch = turn ? MathHelper.clamp(
                            (float) context.getDouble("pitch", pitch), -90.0F, 90.0F) : pitch;

                        if (turn) {
                            player.connection.sendPacket(
                                new CPacketPlayer.Rotation(useYaw, usePitch, player.onGround));
                            player.rotationYaw = useYaw;
                            player.rotationPitch = usePitch;
                        }
                        if (sneak && !player.isSneaking()) {
                            player.connection.sendPacket(new CPacketEntityAction(player,
                                CPacketEntityAction.Action.START_SNEAKING));
                        }
                        player.movementInput.sneak = sneak || wasSneaking;

                        EnumActionResult outcome = EnumActionResult.PASS;
                        EnumHand used = null;
                        try {
                            for (EnumHand hand : EnumHand.values()) {
                                if (handName != null && hand != ("off".equals(handName)
                                    ? EnumHand.OFF_HAND : EnumHand.MAIN_HAND)) {
                                    continue;
                                }
                                outcome = useOn(mc, target, hand);
                                if (outcome == EnumActionResult.SUCCESS) {
                                    used = hand;
                                    break;
                                }
                            }
                        }
                        finally {
                            player.movementInput.sneak = wasSneaking;
                            if (sneak && !wasSneaking) {
                                player.connection.sendPacket(new CPacketEntityAction(player,
                                    CPacketEntityAction.Action.STOP_SNEAKING));
                            }
                            if (turn) {
                                player.rotationYaw = yaw;
                                player.rotationPitch = pitch;
                                player.connection.sendPacket(
                                    new CPacketPlayer.Rotation(yaw, pitch, player.onGround));
                            }
                        }

                        json.addProperty("result", outcome.name().toLowerCase(Locale.ROOT));
                        if (used != null) {
                            json.addProperty("hand", used == EnumHand.MAIN_HAND ? "main" : "off");
                        }
                        JsonObject clicked = new JsonObject();
                        clicked.add("position", GameJson.blockPos(target.pos));
                        clicked.addProperty("face", face.getName());
                        clicked.add("hit", GameJson.vec(target.hit.x - target.pos.getX(),
                            target.hit.y - target.pos.getY(), target.hit.z - target.pos.getZ()));
                        if (turn) {
                            clicked.addProperty("yaw", round(useYaw));
                            clicked.addProperty("pitch", round(usePitch));
                        }
                        if (sneak) {
                            clicked.addProperty("sneak", true);
                        }
                        json.add("clicked", clicked);
                        return json;
                    }
                });

                settle();

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = ClientStateTools.requireInWorld();
                        BlockPos pos = new BlockPos(context.requireInt("x"), context.requireInt("y"),
                            context.requireInt("z"));
                        JsonObject json = new JsonObject();
                        json.add("clicked", before.get("clicked"));
                        json.add("result", before.get("result"));
                        if (before.has("hand")) {
                            json.add("hand", before.get("hand"));
                        }
                        json.add("targetBefore", before.get("target"));
                        json.add("targetAfter", brief(mc.world, pos));
                        json.add("besideBefore", before.get("beside"));
                        json.add("besideAfter", brief(mc.world, pos.offset(face)));
                        json.add("mainHand", GameJson.itemStack(mc.player.getHeldItemMainhand()));
                        ItemStack off = mc.player.getHeldItemOffhand();
                        if (!off.isEmpty()) {
                            json.add("offHand", GameJson.itemStack(off));
                        }
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    /**
     * One hand's attempt, as {@code Minecraft.rightClickMouse} makes it for a block hit: the block
     * first, then the item on its own if the block did not take the click.
     */
    private static EnumActionResult useOn(Minecraft mc, Target target, EnumHand hand) {
        EntityPlayerSP player = mc.player;
        ItemStack stack = player.getHeldItem(hand);
        int count = stack.getCount();
        EnumActionResult result = mc.playerController.processRightClickBlock(player, mc.world,
            target.pos, target.face, target.hit, hand);
        if (result == EnumActionResult.SUCCESS) {
            player.swingArm(hand);
            if (!stack.isEmpty() && (stack.getCount() != count
                || mc.playerController.isInCreativeMode())) {
                mc.entityRenderer.itemRenderer.resetEquippedProgress(hand);
            }
            return result;
        }
        if (!stack.isEmpty()
            && mc.playerController.processRightClick(player, mc.world, hand) == EnumActionResult.SUCCESS) {
            mc.entityRenderer.itemRenderer.resetEquippedProgress(hand);
            return EnumActionResult.SUCCESS;
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Attack
    // ------------------------------------------------------------------

    private static void registerAttackBlock() {
        McpRegistry.registerTool(McpTool.named("client_attack_block")
            .title("Left-click a block")
            .description("Left-click a given face of a given block once, without aiming the camera.\n\n"
                + "One click: in creative it breaks the block, in survival it breaks only a block "
                + "that breaks instantly and otherwise just starts breaking it, which stops again "
                + "next tick. To mine in survival, aim with client_look and hold attack with "
                + "client_interact.\n\n"
                + "The block must be within the player's reach. Returns the block before and after.")
            .schema(JsonSchema.object()
                .integer("x", "Block X.")
                .integer("y", "Block Y.")
                .integer("z", "Block Z.")
                .enumeration("face", "Which face is clicked. Defaults to 'up'.", FACES)
                .required("x", "y", "z")
                .build())
            .clientOnly()
            .destructive()
            .handler(context -> {
                if (!McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("Player control is disabled by "
                        + "permissions.allowPlayerControl in the MCMCP config.");
                }
                final EnumFacing face = parseFace(context.getString("face", "up"));
                if (face == null) {
                    return ToolResult.error("Unknown face '" + context.getString("face", "")
                        + "'; use one of " + String.join(", ", FACES) + ".");
                }

                final JsonObject before = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = ClientStateTools.requireInWorld();
                        Target target = resolve(mc, context, face);
                        JsonObject json = new JsonObject();
                        json.add("target", brief(mc.world, target.pos));
                        // What a left click on a block runs: clickBlock, then the swing.
                        boolean started = mc.playerController.clickBlock(target.pos, face);
                        mc.player.swingArm(EnumHand.MAIN_HAND);
                        json.addProperty("clickAccepted", started);
                        return json;
                    }
                });

                settle();

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = ClientStateTools.requireInWorld();
                        BlockPos pos = new BlockPos(context.requireInt("x"), context.requireInt("y"),
                            context.requireInt("z"));
                        JsonObject json = new JsonObject();
                        json.addProperty("face", face.getName());
                        json.add("clickAccepted", before.get("clickAccepted"));
                        json.add("targetBefore", before.get("target"));
                        json.add("targetAfter", brief(mc.world, pos));
                        json.addProperty("broken", mc.world.isAirBlock(pos));
                        json.add("mainHand", GameJson.itemStack(mc.player.getHeldItemMainhand()));
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }
}
