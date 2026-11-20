package dev.volytragoto.brain;

import com.volytrafly.modules.movement.volytrafly.VolytraFly;
import dev.volytragoto.api.Brain;
import dev.volytragoto.brain.pathing.FlightPathfinder;
import dev.volytragoto.modules.VolytraGoto.Arrive;
import dev.volytragoto.modules.VolytraGoto.Rotate;
import dev.volytragoto.modules.VolytraGoto;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;

/**
 * Volytra Goto's brain: everything about flying (path search, steering, speed, digging, elytra, portals, logout).
 * The module ({@link VolytraGoto}) only holds the settings and forwards events here.
 */
public class BrainImpl implements Brain {
    /** Never land on these (or on anything with liquid in it). */
    private static final Set<Block> BAD_FLOOR = Set.of(Blocks.MAGMA_BLOCK, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.CACTUS,
        Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW, Blocks.POINTED_DRIPSTONE, Blocks.WITHER_ROSE, Blocks.FIRE, Blocks.SOUL_FIRE,
        Blocks.LAVA_CAULDRON, Blocks.NETHER_PORTAL, Blocks.END_PORTAL, Blocks.END_GATEWAY, Blocks.SCAFFOLDING, Blocks.COBWEB);

    private enum Phase { TakeOff, Flying, Landing }

    private static final Minecraft mc = Minecraft.getInstance();
    private final VolytraGoto m;

    private final Setting<Integer> targetX;
    private final Setting<Integer> targetZ;
    private final Setting<Arrive> arrive;
    private final Setting<Integer> targetY;
    private final Setting<Integer> cruiseHeight;
    private final Setting<Double> maxSpeed;
    private final Setting<Boolean> waitForChunks;
    private final Setting<Rotate> rotate;
    private final Setting<Integer> cruiseDistance;
    private final Setting<Double> hitboxPadding;
    private final Setting<Integer> setbackPause;
    private final Setting<Boolean> slowOnSetback;
    private final Setting<Boolean> enableVolytraFly;
    private final Setting<Boolean> logoutOnArrival;
    private final Setting<Boolean> portalStop;
    private final Setting<Integer> portalIgnoreRadius;
    private final Setting<Integer> minDurability;
    private final Setting<Boolean> swapElytra;
    private final Setting<Boolean> mendWithXp;
    private final Setting<Integer> mendAt;
    private final Setting<Boolean> emergencyLand;
    private final Setting<Integer> searchRadius;
    private final Setting<Integer> maxNodes;
    private final Setting<Integer> computeMs;
    private final Setting<Boolean> allowLowGaps;
    private final Setting<Double> wallAvoidance;
    private final Setting<Boolean> mineThrough;
    private final Setting<Integer> mineCost;
    private final Setting<Double> braking;
    private final Setting<Boolean> keepVfSink;
    private final Setting<Double> wallDistance;
    private final Setting<Boolean> followVfRamp;
    private final Setting<Double> acceleration;
    private final Setting<Boolean> renderPath;
    private final Setting<SettingColor> pathColor;
    private final Setting<SettingColor> goalColor;

    // --- state ---
    private VolytraFly vf;
    private ResourceKey<Level> dimension;
    private Phase phase;
    private boolean holdingForward;

    private FlightPathfinder search;
    private List<Vec3> path = new ArrayList<>();
    private boolean pathReachesGoal, pathEndsAtFrontier;
    private int pathIdx;
    private BlockPos goalNode;
    private int goalCheckAge, ticksSinceSearch, failures, retryIn, noCarrotTicks, waitMsgAge, takeoffWait, groundTicks;
    private boolean replanNow, landingWarned, hoverOnly, emergencyLanding;
    private int setbackTicks, swapCooldown, jumps, lowCeilingWarned;
    private double setbackCap = Double.POSITIVE_INFINITY, setbackCapV = Double.POSITIVE_INFINITY;
    private double curV;
    private static final double MIN_V = 0.5;
    private static final DebugLog LOG = new DebugLog();
    private Vec3 dbgVfMove = Vec3.ZERO, dbgOurMove = Vec3.ZERO, dbgCarrot;
    private double dbgSH;
    private int dbgSetbacks;
    private int ticksSinceSetback, setbackMsgAge = -10000;
    private double bestProgress;
    private int noProgressTicks, stuckStrikes, exemptTicks, lineupTicks;
    // what the SERVER says about our glide (from its entity-flags updates): -1 unknown, 0 not gliding, 1 gliding
    private volatile int srvGlide = -1;
    private volatile boolean srvSeenTrue;
    private int pendingStartAge = -1, startSentAge = -100000, desyncFixes, unconfirmedTicks;
    private boolean glidingLastTick;
    /**
     * How high in its cell the route runs. It was 0.1 - a tenth of a block over whatever is underneath, so over the tops
     * of the Nether's big fungi (and every crest) the glide kept touching the block below; the server takes that for
     * landing, closes the elytra and pulls us back. 0.25 still fits a 1-high gap (the gliding hitbox is 0.6 tall).
     */
    // (0.18, not 0.25: when the server closes the elytra it stands us up, 1.8 tall - from 0.25 that pokes 0.05 into the
    // ceiling of a 2-high passage, an "in wall" tick of damage each time; 0.18 + 1.8 fits)
    private static final double NODE_Y = 0.18;
    private int loopLogAge = -10000, tunnelEndAge = -100000;
    private float camYaw = Float.NaN, camPitch;
    private long lastTickNano;
    private double tickDtMs = 50;
    private final float[] dtRing = new float[60];
    private int dtIdx, catchups, pairedWarnAge = -100000;
    private boolean pairedTicks;
    private final Vec3[] posRing = new Vec3[30];
    private java.lang.reflect.Field minimizedField;
    private boolean minimizedLookupFailed;
    private int minimizedLogAge = -100000;

    /**
     * A minimised Minecraft window throttles the whole game to 10 frames a second, and at 10 FPS two ticks run per frame:
     * our movement packets leave in pairs and the server pulls us back about 11 times a minute (38 of 40 pull-backs in
     * one bad stretch were at exactly 10 FPS). While a flight is running, tell the game's frame limiter the window is
     * not minimised: nothing is drawn for a minimised window anyway, the loop just keeps its normal pace. The real
     * state is put back when the flight stops (and by the game itself whenever the window is restored or minimised).
     */
    private void keepFrameRate() {
        try {
            com.mojang.blaze3d.platform.Window w = mc.getWindow();
            if (!w.isIconified() || minimizedLookupFailed) return;
            if (minimizedField == null) {
                for (String n : new String[] {"minimized"}) {
                    try {
                        java.lang.reflect.Field f = com.mojang.blaze3d.platform.Window.class.getDeclaredField(n);
                        if (f.getType() != boolean.class) continue;
                        f.setAccessible(true);
                        minimizedField = f;
                        break;
                    }
                    catch (NoSuchFieldException ignored) {
                    }
                }
                if (minimizedField == null) {
                    minimizedLookupFailed = true;
                    LOG.warn("[VG] can't find the window's minimised flag - the game will run at 10 FPS while minimised");
                    return;
                }
            }
            if (!minimizedField.getBoolean(w)) return;
            minimizedField.setBoolean(w, false);
            if (w.isIconified()) { // not the flag isMinimized() reads after all: put it back and leave it alone
                minimizedField.setBoolean(w, true);
                minimizedLookupFailed = true;
                return;
            }
            if (mc.player.tickCount - minimizedLogAge > 6000) {
                minimizedLogAge = mc.player.tickCount;
                LOG.info("[VG] window minimised: keeping the game at its normal tick pace for the flight (no 10 FPS throttle)");
            }
        }
        catch (Throwable t) {
            minimizedLookupFailed = true;
            LOG.warn("[VG] couldn't lift the minimised-window throttle", t);
        }
    }

    /** Give the frame limiter the window's real state back. */
    private void restoreFrameRate() {
        try {
            if (minimizedField == null) return;
            com.mojang.blaze3d.platform.Window w = mc.getWindow();
            boolean real = org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(w.handle(), org.lwjgl.glfw.GLFW.GLFW_ICONIFIED) == org.lwjgl.glfw.GLFW.GLFW_TRUE;
            minimizedField.setBoolean(w, real);
        }
        catch (Throwable ignored) {
        }
    }
    private int posIdx;

    /**
     * Burn damage shows up several ticks after its cause, by which time we're 5-10 blocks on: look back along the last
     * 30 positions for the nearest lava / fire, and say how near it was and where (so we know whether the route brushed
     * it, or it was overhead where only a standing-height hitbox would reach).
     */
    private void logHeatSource() {
        double best = Double.MAX_VALUE;
        String what = null;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int k = 0; k < posRing.length; k++) {
            Vec3 pp = posRing[((posIdx - 1 - k) % posRing.length + posRing.length) % posRing.length];
            if (pp == null) continue;
            int bx = Mth.floor(pp.x), by = Mth.floor(pp.y), bz = Mth.floor(pp.z);
            for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 3; dy++) for (int dz = -2; dz <= 2; dz++) {
                q.set(bx + dx, by + dy, bz + dz);
                BlockState st = mc.level.getBlockState(q);
                boolean lava = st.getFluidState().is(FluidTags.LAVA), fire = st.is(Blocks.FIRE) || st.is(Blocks.SOUL_FIRE);
                if (!lava && !fire) continue;
                // gap between the block and our gliding hitbox (0.6 x 0.6 x 0.6 at pp)
                double gx = Math.max(0, Math.max(q.getX() - (pp.x + 0.3), (pp.x - 0.3) - (q.getX() + 1)));
                double gy = Math.max(0, Math.max(q.getY() - (pp.y + 0.6), pp.y - (q.getY() + 1)));
                double gz = Math.max(0, Math.max(q.getZ() - (pp.z + 0.3), (pp.z - 0.3) - (q.getZ() + 1)));
                double g = Math.sqrt(gx * gx + gy * gy + gz * gz);
                if (g < best) {
                    best = g;
                    what = String.format("%s at %d %d %d, %.2f from the hitbox %d ticks ago (side %.2f, above %.2f, below %.2f)", lava ? "lava" : "fire",
                        q.getX(), q.getY(), q.getZ(), g, k, Math.hypot(gx, gz), Math.max(0, q.getY() - (pp.y + 0.6)), Math.max(0, pp.y - (q.getY() + 1)));
                }
            }
        }
        LOG.info("[VG] heat source: {}", what == null ? "no lava or fire within 2 blocks of the last 30 positions" : what);
    }
    private Vec3 takeoffAnchor;
    private int takeoffStill, takeoffForce, footSetbacks, footSetbackAge = -100000;
    private boolean lowTakeoff;
    private int footTicks;
    private String takeoffDoing = "nothing";
    private final LongOpenHashSet plannedMine = new LongOpenHashSet(); // path cells the search meant to dig out
    private int unplannedLogAge = -10000;
    private boolean instantOff, ahSuspended;
    private int lastGroundDigAge = -100000;

    /** Anti-Hunger and Speed belong to a flight: turn them on if they're off (once, when the flight starts). */
    private void enableHelpers() {
        if (!Tuning.b("helpers.enable", true)) return;
        try {
            var ah = Modules.get().get(meteordevelopment.meteorclient.systems.modules.player.AntiHunger.class);
            if (ah != null && !ah.isActive()) {
                ah.toggle();
                m.info("Turned Anti-Hunger on.");
            }
            var sp = Modules.get().get(meteordevelopment.meteorclient.systems.modules.movement.speed.Speed.class);
            if (sp != null && !sp.isActive()) {
                sp.toggle();
                m.info("Turned Speed on.");
            }
        }
        catch (Throwable t) {
            LOG.warn("[VG] couldn't turn Anti-Hunger / Speed on", t);
        }
    }

    /** Walking speed on foot (blocks/tick): the Speed module's own speed while it's on. */
    private double walkSpeed() {
        double def = Tuning.d("tunnel.walk.speed", 0.2);
        try {
            var sp = Modules.get().get(meteordevelopment.meteorclient.systems.modules.movement.speed.Speed.class);
            if (sp == null || !sp.isActive() || !Tuning.b("walk.use.speed", true)) return def;
            Setting<?> v = sp.settings.get("vanilla-speed");
            if (v != null && v.get() instanceof Number n) return Mth.clamp(n.doubleValue() / 20.0, def, Tuning.d("tunnel.walk.max", 0.4));
        }
        catch (Throwable ignored) {
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    private Setting<Boolean> antiHungerOnGround() {
        try {
            var ah = Modules.get().get(meteordevelopment.meteorclient.systems.modules.player.AntiHunger.class);
            Setting<?> on = ah == null ? null : ah.settings.get("on-ground");
            return on != null && on.get() instanceof Boolean ? (Setting<Boolean>) on : null;
        }
        catch (Throwable t) {
            return null;
        }
    }

    /**
     * Digging while standing: Anti-Hunger's "on-ground" trick would make the server count it as digging in the air (5x
     * slower, one-hit breaks refused). Pause just that option while we dig on foot and tell the server we're standing.
     */
    private void groundTruthForDigging() {
        lastGroundDigAge = mc.player.tickCount;
        if (!groundSpoofed()) return;
        Setting<Boolean> on = antiHungerOnGround();
        if (on == null) return;
        on.set(false);
        ahSuspended = true;
        mc.getConnection().send(new ServerboundMovePlayerPacket.StatusOnly(true, mc.player.horizontalCollision));
        LOG.info("[VG] digging on foot: Anti-Hunger's on-ground trick paused");
    }

    private void restoreAntiHunger() {
        if (!ahSuspended) return;
        ahSuspended = false;
        Setting<Boolean> on = antiHungerOnGround();
        if (on != null) on.set(true);
        LOG.info("[VG] Anti-Hunger's on-ground trick back on");
    }
    private int instantFails, instantBreaks, instantOffAge = -100000;

    /**
     * Anti-Hunger's "on-ground" option tells the server we're never on the ground: mining then counts as "in the air"
     * (5x slower, and one-hit breaks are refused). True while that's happening.
     */
    private boolean groundSpoofed() {
        try {
            var ah = Modules.get().get(meteordevelopment.meteorclient.systems.modules.player.AntiHunger.class);
            if (ah == null || !ah.isActive()) return false;
            Setting<?> on = ah.settings.get("on-ground");
            return on == null || Boolean.TRUE.equals(on.get());
        }
        catch (Throwable t) {
            return true;
        }
    }
    private float travelYaw = Float.NaN;
    private int mineModeTicks, stallTicks, creepTicks, mineTicks, searchTicks;
    private int carrotIdx = -1;
    private boolean carrotTight;
    private boolean mending;
    private final java.util.ArrayDeque<String> history = new java.util.ArrayDeque<>(); // last 40 ticks, for snapshots
    private int snapAge = -10000;
    private int lastMoveEventAge = -1;
    private double lastCmdSpeed; // length of the move we sent last tick (speed changes are limited per tick)
    private Vec3 lastCmd = Vec3.ZERO; // the move we sent last tick (direction changes are limited per tick)
    private int slowAfterSetback, dvLimited, lavaTicks, takeoffFails, hardBrakes, creepEscapes, wallClips;
    private boolean lavaOkNow; // fire resistance with enough time left: lava may be flown through
    private Object hpWorld;
    private static final Vec3[] AXES6 = {new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, -1, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1)};

    private int fireResTicks() {
        var e = mc.player.getEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE);
        if (e == null) return 0;
        return e.isInfiniteDuration() ? Integer.MAX_VALUE : e.getDuration();
    }
    private float lastHp = -1;
    private volatile String lastDamageType = "?";
    private volatile long lastDamageAt;
    private long damageTotalMilli; // total damage taken this flight, in thousandths of a heart-half
    private int lastHurtAge = -10000;
    private BlockPos fixedGoal, portalFound;
    private boolean divertedToPortal;
    private Vec3 flightStart;
    private final LongOpenHashSet scannedChunks = new LongOpenHashSet();
    private final List<BlockPos> ignoredPortals = new ArrayList<>();
    private int mendTicks, mendCooldown, throwCooldown, mendBelow = -1;
    private BlockPos digOutTarget, digging;
    private Vec3 stillAnchor;
    private int stillTicks, digTicks;
    private final java.util.ArrayDeque<double[]> progress = new java.util.ArrayDeque<>(); // {age, distance left}
    private double curSpeed, dbgTargetSpeed, dbgClearAhead;
    private Vec3 lastMovePos, lastWant = Vec3.ZERO;
    private BlockPos mineTarget;
    private Vec3 mineFrom;
    private final LongOpenHashSet noMine = new LongOpenHashSet();

    public BrainImpl(VolytraGoto m) {
        this.m = m;
        this.targetX = m.targetX;
        this.targetZ = m.targetZ;
        this.arrive = m.arrive;
        this.targetY = m.targetY;
        this.cruiseHeight = m.cruiseHeight;
        this.maxSpeed = m.maxSpeed;
        this.waitForChunks = m.waitForChunks;
        this.rotate = m.rotate;
        this.cruiseDistance = m.cruiseDistance;
        this.hitboxPadding = m.hitboxPadding;
        this.setbackPause = m.setbackPause;
        this.slowOnSetback = m.slowOnSetback;
        this.enableVolytraFly = m.enableVolytraFly;
        this.logoutOnArrival = m.logoutOnArrival;
        this.portalStop = m.portalStop;
        this.portalIgnoreRadius = m.portalIgnoreRadius;
        this.minDurability = m.minDurability;
        this.swapElytra = m.swapElytra;
        this.mendWithXp = m.mendWithXp;
        this.mendAt = m.mendAt;
        this.emergencyLand = m.emergencyLand;
        this.searchRadius = m.searchRadius;
        this.maxNodes = m.maxNodes;
        this.computeMs = m.computeMs;
        this.allowLowGaps = m.allowLowGaps;
        this.wallAvoidance = m.wallAvoidance;
        this.mineThrough = m.mineThrough;
        this.mineCost = m.mineCost;
        this.braking = m.braking;
        this.keepVfSink = m.keepVfSink;
        this.wallDistance = m.wallDistance;
        this.followVfRamp = m.followVfRamp;
        this.acceleration = m.acceleration;
        this.renderPath = m.renderPath;
        this.pathColor = m.pathColor;
        this.goalColor = m.goalColor;
    }

    /** Start (or redirect) a goto. y == null: land at x z. */
    @Override
    public void go(int x, Integer y, int z) {
        if (mc.level != null && !mc.level.getWorldBorder().isWithinBounds(x + 0.5, z + 0.5)) {
            m.error("%d %d is outside the world border.", x, z);
            return;
        }
        if (y != null && mc.level != null) {
            int clamped = Mth.clamp(y, mc.level.getMinY() + 1, mc.level.getMaxY() - 2);
            if (clamped != y) m.warning("Y %d is outside the world, using %d.", y, clamped);
            y = clamped;
        }
        fixedGoal = null;
        portalFound = null;
        divertedToPortal = false;
        targetX.set(x);
        targetZ.set(z);
        if (y != null) {
            arrive.set(Arrive.Hover);
            targetY.set(y);
        }
        else arrive.set(Arrive.Land);

        if (m.isActive()) {
            resetRoute();
            // a new destination while flying: look at the portals around us again (the chunk of a portal we were already
            // heading for was marked "looked at", so giving the command again made the bot forget it for good)
            scannedChunks.clear();
            ignoredPortals.clear();
            if (mc.player != null) flightStart = mc.player.position();
            m.info("Now flying to %s.", targetString());
        }
        else m.toggle();
    }

    private String targetString() {
        return arrive.get() == Arrive.Hover ? targetX.get() + " " + targetY.get() + " " + targetZ.get() : targetX.get() + " " + targetZ.get();
    }

    @Override
    public void activate() {
        if (mc.player == null || mc.level == null) {
            m.toggle();
            return;
        }
        vf = Modules.get().get(VolytraFly.class);
        if (vf == null) {
            m.error("VolytraFly isn't loaded.");
            m.toggle();
            return;
        }
        resetAgeStamps();
        if (!hasElytra()) {
            FindItemResult packed = InvUtils.find(st -> LivingEntity.canGlideUsing(st, EquipmentSlot.CHEST), 0, 35);
            if (packed.found()) {
                InvUtils.move().from(packed.slot()).toArmor(2);
                m.info("Put the elytra from your inventory on.");
            }
        }
        if (!hasElytra() && !InvUtils.find(st -> LivingEntity.canGlideUsing(st, EquipmentSlot.CHEST), 0, 35).found()) {
            m.error("Put an elytra on first.");
            m.toggle();
            return;
        }
        m.weEnabledVf = false;
        if (!vf.isActive()) {
            if (!enableVolytraFly.get()) {
                m.error("VolytraFly is off (turn it on, or turn on enable-volytrafly).");
                m.toggle();
                return;
            }
            vf.toggle();
            m.weEnabledVf = true;
        }
        enableHelpers();
        LOG.info("[VG] flight settings: portal-stop={} (tuning override {}), portal-ignore-radius={}, logout-on-arrival={}, mine-through-walls={}",
            portalStop.get(), Tuning.b("portal.stop", false), portalIgnoreRadius.get(), logoutOnArrival.get(), mineThrough.get());

        dimension = mc.level.dimension();
        phase = mc.player.isFallFlying() ? Phase.Flying : Phase.TakeOff;
        takeoffWait = 0;
        groundTicks = 0;
        jumps = 0;
        lowCeilingWarned = 0;
        setbackTicks = 0;
        setbackCap = Double.POSITIVE_INFINITY;
        setbackCapV = Double.POSITIVE_INFINITY;
        ticksSinceSetback = 0;
        swapCooldown = 0;
        emergencyLanding = false;
        fixedGoal = null;
        portalFound = null;
        divertedToPortal = false;
        flightStart = mc.player.position();
        scannedChunks.clear();
        ignoredPortals.clear();
        travelYaw = Float.NaN;
        resetRoute();

        Vec3 p = mc.player.position();
        double dist = Math.hypot(targetX.get() + 0.5 - p.x, targetZ.get() + 0.5 - p.z);
        m.info("Flying to %s (%.0f blocks).", targetString(), dist);
        if (mc.level.dimension() == Level.NETHER && dist > 20000) {
            m.warning("You're in the Nether. If those are Overworld coords, use .vgoto ow %d %d instead (flies to %d %d here).",
                targetX.get(), targetZ.get(), Math.floorDiv(targetX.get(), 8), Math.floorDiv(targetZ.get(), 8));
        }
        progress.clear();
    }

    private void resetRoute() {
        search = null;
        path = new ArrayList<>();
        pathReachesGoal = pathEndsAtFrontier = false;
        pathIdx = 0;
        goalNode = null;
        goalCheckAge = 0;
        ticksSinceSearch = 0;
        failures = 0;
        retryIn = 0;
        noCarrotTicks = 0;
        replanNow = true;
        landingWarned = false;
        hoverOnly = false;
        bestProgress = Double.MAX_VALUE;
        noProgressTicks = 0;
        stuckStrikes = 0;
        mineModeTicks = 0;
        tunneling = false;
        stallTicks = 0;
        creepTicks = 0;
        mineTicks = 0;
        mineTarget = null;
        lastMovePos = null;
        digOutTarget = null;
        digging = null;
        mending = false;
        stillAnchor = null;
        stillTicks = 0;
        noMine.clear();
        curSpeed = 0;
        curV = 0;
        if (phase == Phase.Landing) phase = Phase.Flying;
    }

    @Override
    public void deactivate() {
        restoreFrameRate();
        restoreAntiHunger();
        releaseSwimKeys();
        if (stowedElytraSlot >= 0 && mc.player != null && !mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)
            && mc.player.getInventory().getItem(stowedElytraSlot).is(Items.ELYTRA)) InvUtils.move().from(stowedElytraSlot).toArmor(2);
        stowedElytraSlot = -1;
        releaseForward();
        search = null;
        path = new ArrayList<>();
        if (m.weEnabledVf && vf != null && vf.isActive() && mc.player != null && mc.player.onGround()) vf.toggle();
        m.weEnabledVf = false;
    }

    private void releaseForward() {
        if (!holdingForward) return;
        holdingForward = false;
        KeyMapping fwd = mc.options.keyUp;
        fwd.setDown(Input.isPressed(fwd));
    }

    @Override
    public void packet(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundDamageEventPacket dp && mc.player != null && dp.entityId() == mc.player.getId()) {
            lastDamageType = dp.sourceType().getRegisteredName() + (dp.sourcePosition().isPresent() ? "@" + v(dp.sourcePosition().get()) : "")
                + (dp.sourceCauseId() > 0 ? " cause#" + dp.sourceCauseId() : "");
            lastDamageAt = System.currentTimeMillis();
        }
        if (event.packet instanceof net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket tp && mc.player != null && tp.id() == mc.player.getId()) {
            for (var e : tp.packedItems()) {
                if (e.id() == 0 && e.value() instanceof Byte fb) {
                    int g = (fb & 0x80) != 0 ? 1 : 0;
                    if (g != srvGlide) LOG.info("[VG] server says gliding={} (flags {}, client gliding={}, {} ticks after asking)", g == 1, Integer.toHexString(fb & 0xff), mc.player.isFallFlying(), mc.player.tickCount - startSentAge);
                    srvGlide = g;
                    if (g == 1) srvSeenTrue = true;
                }
            }
        }
        if (!(event.packet instanceof ClientboundPlayerPositionPacket pk) || mc.player == null || vf == null) return;
        Vec3 toPos = pk.change().position();
        String rel = String.valueOf(pk.relatives());
        mc.execute(() -> {
            try {
                if (m.isActive() && mc.player != null && vf != null) onSetback(toPos, rel);
            }
            catch (Throwable t) {
                LOG.error("[VG] setback handling failed", t);
            }
        });
    }

    private void onSetback(Vec3 to, String relatives) {
        Vec3 at = mc.player.position();
        dbgSetbacks++;
        snapshot("server setback #" + dbgSetbacks + " -> server pos " + v(to));
        {
            StringBuilder sb = new StringBuilder();
            int fast = 0, slow = 0;
            for (int i = 0; i < dtRing.length; i++) {
                float d = dtRing[(dtIdx + i) % dtRing.length]; // oldest first
                if (d < 30) fast++;
                if (d > 80) slow++;
                sb.append(Math.round(d)).append(' ');
            }
            LOG.info("[VG] tick spacing before setback #{} (ms, oldest first; {} short, {} long, paired={}, fps {}, server tps {}, {} s since its last tick): {}", dbgSetbacks, fast, slow, pairedTicks, mc.getFps(),
                String.format("%.1f", meteordevelopment.meteorclient.utils.world.TickRate.INSTANCE.getTickRate()),
                String.format("%.1f", meteordevelopment.meteorclient.utils.world.TickRate.INSTANCE.getTimeSinceLastTick()), sb.toString().trim());
        }
        LOG.info("[VG] SETBACK #{} phase={} gliding={} ticksSinceLast={} at=({}) server=({}) dist={} relatives={} vfMove=({}) ourMove=({}) sH={} cap={} vfStart={} vfH={} vfV={} yaw={} pitch={} travelYaw={} carrot=({}) setbackTicksLeft={} pathSize={} idx={} srvGlide={}",
            dbgSetbacks, phase, mc.player.isFallFlying(), ticksSinceSetback, v(at), v(to), String.format("%.2f", at.distanceTo(to)), relatives,
            v(dbgVfMove), v(dbgOurMove), String.format("%.2f", dbgSH), String.format("%.2f", setbackCap),
            vf.startSpeed.get(), vf.horizontalSpeed.get(), vf.verticalSpeed.get(),
            String.format("%.1f", mc.player.getYRot()), String.format("%.1f", mc.player.getXRot()), String.format("%.1f", travelYaw),
            dbgCarrot == null ? "none" : v(dbgCarrot), setbackTicks, path.size(), pathIdx, srvGlide);
        // the server pulled us back: hover a moment, then carry on from wherever we are now (slower)
        setbackTicks = setbackPause.get();
        ticksSinceSetback = 0;
        mc.player.setDeltaMovement(0, 0, 0);
        if (!mc.player.isFallFlying()) {
            // pulled back while on foot: the server doesn't accept where we're walking (a block it has that we don't see)
            if (mc.player.tickCount - footSetbackAge > 100) footSetbacks = 0;
            footSetbackAge = mc.player.tickCount;
            if (++footSetbacks == Tuning.i("foot.setbacks.max", 5)) {
                LOG.info("[VG] {} pull-backs on foot at ({}) - not walking on, taking off from here", footSetbacks, v(mc.player.position()));
                m.warning("The server won't let the bot walk on here - taking off instead.");
                takeoffForce = Tuning.i("takeoff.force.ticks", 100) * 2;
                tunneling = false;
                noRoomTicks = 0;
                groundNudge = null;
                replanNow = true;
                footSetbacks = 0;
            }
        }
        // the pull-back also turns the camera to where the SERVER has us looking - straight down, because that's what we
        // tell it while gliding. Put the player's own view back.
        if (!Float.isNaN(camYaw) && Tuning.b("setback.keep.camera", true)) {
            mc.player.setYRot(camYaw);
            mc.player.setXRot(camPitch);
        }
        lastMovePos = null;
        curSpeed = 0;
        curV = 0;
        lastCmdSpeed = 0;
        lastCmd = Vec3.ZERO;
        slowAfterSetback = Tuning.i("setback.slow.ticks", 0);
        if (slowOnSetback.get() && mc.player.isFallFlying()) {
            double floor = Math.max(1.0, vf.startSpeed.get());
            double now = Math.min(setbackCap, Math.max(vf.startSpeed.get(), vf.horizontalSpeed.get()));
            setbackCap = Math.max(floor, now * Tuning.d("setback.cut", 0.8));
            double nowV = Math.min(setbackCapV, Math.max(MIN_V, vf.verticalSpeed.get()));
            setbackCapV = Math.max(MIN_V, nowV * Tuning.d("setback.cut", 0.8));
        }
        if (mc.player.tickCount - setbackMsgAge > 200) {
            setbackMsgAge = mc.player.tickCount;
            if (setbackCap < 100) m.warning("Server pulled you back - slowing to %.1f across, %.1f up/down (blocks/tick).", setbackCap, setbackCapV);
            else m.warning("Server pulled you back.");
        }
    }

    /** "123456 blocks left, 9.8 blocks/s, ~3h 29m" (speed over the last minute). */
    @Override
    public String statusLine() {
        if (mc.player == null) return "";
        Vec3 p = mc.player.position();
        double left = Math.hypot(targetX.get() + 0.5 - p.x, targetZ.get() + 0.5 - p.z);
        String s = String.format("%.0f blocks left", left);
        if (phase == Phase.Landing) return s + ", landing";
        double[] first = progress.peekFirst();
        if (first != null && mc.player.tickCount - first[0] >= 200) {
            double perSec = (first[1] - left) / ((mc.player.tickCount - first[0]) / 20.0);
            if (perSec > 0.1) {
                long secs = (long) (left / perSec);
                s += String.format(", %.1f blocks/s, ~%s", perSec, secs >= 3600 ? (secs / 3600) + "h " + (secs % 3600 / 60) + "m" : (secs / 60) + "m " + (secs % 60) + "s");
            }
            else s += ", not getting closer right now";
        }
        if (mineTarget != null) s += ", digging";
        return s;
    }

    @Override
    public String infoString() {
        if (mc.player == null) return null;
        if (phase == Phase.Landing) return "landing";
        Vec3 p = mc.player.position();
        return String.format("%.0fm", Math.hypot(targetX.get() + 0.5 - p.x, targetZ.get() + 0.5 - p.z));
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        camYaw = mc.player.getYRot();
        camPitch = mc.player.getXRot();
        if (Tuning.b("fps.keep.minimized", true)) keepFrameRate();
        // how evenly are OUR ticks spaced? After a hitch the game runs the missed ticks back to back, and their movement
        // packets reach the server in one bunch - to the server that's several ticks' worth of movement in one
        long nowNano = System.nanoTime();
        tickDtMs = lastTickNano == 0 ? 50 : (nowNano - lastTickNano) / 1e6;
        lastTickNano = nowNano;
        dtRing[dtIdx++ % dtRing.length] = (float) tickDtMs;
        posRing[posIdx++ % posRing.length] = mc.player.position();
        // The game at ~10 FPS (minimised window) runs two ticks per frame: the movement packets go out in pairs, and to the
        // server each pair is one tick moving twice as far / changing speed twice as fast. Fly at half pace while it lasts.
        int shortTicks = 0;
        for (int i = 1; i <= 20; i++) if (dtRing[((dtIdx - i) % dtRing.length + dtRing.length) % dtRing.length] < 30) shortTicks++;
        boolean nowPaired = shortTicks >= (pairedTicks ? 3 : 7) && Tuning.b("paired.adapt", true);
        if (nowPaired != pairedTicks) {
            pairedTicks = nowPaired;
            boolean minimised = false;
            try {
                minimised = mc.getWindow().isIconified();
            }
            catch (Throwable ignored) {
            }
            LOG.info("[VG] ticks {} (fps {}, window minimised={})", nowPaired ? "are coming in pairs - flying at half pace" : "are even again - normal pace", mc.getFps(), minimised);
            if (nowPaired && mc.player.tickCount - pairedWarnAge > 6000) {
                pairedWarnAge = mc.player.tickCount;
                m.warning("The game is running at ~%d FPS%s: packets go out in bunches and the server pulls back more. Flying at half pace until the frame rate is back - keep the Minecraft window open (it can be behind other windows) for full speed.", mc.getFps(), minimised ? " (window minimised)" : "");
            }
        }
        if (ahSuspended && mc.player.tickCount - lastGroundDigAge > Tuning.i("dig.truth.hold", 60)) restoreAntiHunger();

        if (mc.level.dimension() != dimension) {
            m.info("Changed dimension - stopping.");
            m.toggle();
            return;
        }
        if (!vf.isActive()) {
            m.info("VolytraFly was turned off - stopping.");
            m.weEnabledVf = false;
            m.toggle();
            return;
        }
        // ---- damage: log every hit (type, where), and log out before dying
        // ---- fire resistance: lava allowed while there's time to spare; replan when that changes
        int fr = fireResTicks();
        boolean ok = Tuning.b("fireres.lava.paths", false) && fr > Tuning.i("fireres.path.ticks", 1500);
        if (ok != lavaOkNow) {
            lavaOkNow = ok;
            replanNow = true;
            LOG.info("[VG] fire resistance {} ({} ticks left) - lava {}", ok ? "on" : "off/ending", fr == Integer.MAX_VALUE ? "inf" : fr, ok ? "allowed" : "avoided");
        }

        float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (hpWorld != mc.level) {
            hpWorld = mc.level;
            lastHp = -1;
            resetAgeStamps();
        }
        if (lastHp >= 0 && hp < lastHp - 0.01f && System.currentTimeMillis() - lastDamageAt > 1500) {
            LOG.info("[VG] hp {} -> {} without a damage packet (absorption ended / resync) - not counted", String.format("%.1f", lastHp), String.format("%.1f", hp));
            lastHp = hp;
        }
        if (lastHp >= 0 && hp < lastHp - 0.01f) {
            float lost = lastHp - hp;
            damageTotalMilli += Math.round(lost * 1000);
            boolean fresh = System.currentTimeMillis() - lastDamageAt < 1500;
            LOG.info("[VG] DAMAGE -{} hp (now {}) type={} fire={} lava={} fall={} gliding={} hCollide={} vCollide={} speed={} total={}",
                String.format("%.1f", lost), String.format("%.1f", hp), fresh ? lastDamageType : "unknown(" + lastDamageType + ")",
                mc.player.isOnFire(), mc.player.isInLava(), String.format("%.1f", mc.player.fallDistance), mc.player.isFallFlying(),
                mc.player.horizontalCollision, mc.player.verticalCollision, String.format("%.2f", lastCmdSpeed),
                String.format("%.1f", damageTotalMilli / 1000.0));
            snapAge = -10000;
            snapshot("damage -" + String.format("%.1f", lost) + " " + (fresh ? lastDamageType : "?"));
            lastHurtAge = mc.player.tickCount;
            if (fresh && (lastDamageType.contains("lava") || lastDamageType.contains("fire"))) logHeatSource();
        }
        lastHp = hp;
        if (hp <= Tuning.d("logout.hp", 8) && mc.player.tickCount - lastHurtAge < 40 && !mc.player.isCreative()) {
            logout(String.format("Health down to %.1f and still taking damage (%s) - logged out to stay alive.", hp, lastDamageType));
            m.toggle();
            return;
        }

        if (lavaSwim()) {
            if (lavaTicks == 0 && mc.player.isInLava()) {
                m.warning("In lava - swimming out.");
                snapAge = -10000;
                snapshot("in lava");
            }
            if (mc.player.isInLava()) lavaTicks++;
            if (phase != Phase.TakeOff) phase = Phase.TakeOff;
            search = null;
            lastHpKeep();
            return;
        }
        if (lavaTicks > 0 && !mc.player.isInLava()) {
            LOG.info("[VG] out of lava after {} ticks", lavaTicks);
            lavaTicks = 0;
            resetRoute();
        }
        if (!hasElytra()) {
            // not on - but is there one in the inventory? Put that on rather than calling the elytra gone.
            FindItemResult packed = InvUtils.find(st -> LivingEntity.canGlideUsing(st, EquipmentSlot.CHEST), 0, 35);
            if (packed.found()) {
                InvUtils.move().from(packed.slot()).toArmor(2);
                stowedElytraSlot = -1;
                LOG.info("[VG] elytra wasn't on - put the one from slot {} back on", packed.slot());
                lastHpKeep();
                return;
            }
            if (!mc.player.onGround()) {
                logout("Elytra gone mid-air - logged out before the fall.");
                m.toggle();
                return;
            }
            m.error("No elytra on - stopping.");
            m.toggle();
            return;
        }
        // ---- lava: never just stop - climb out (up, or back along the path), keep trying
        if (mc.player.isInLava()) {
            if (lavaTicks == 0) {
                m.warning("In lava - climbing out.");
                snapAge = -10000;
                snapshot("in lava");
            }
            lavaTicks++;
            if (lavaTicks % 20 == 0) replanNow = true;
        }
        else if (lavaTicks > 0) {
            LOG.info("[VG] out of lava after {} ticks", lavaTicks);
            lavaTicks = 0;
            replanNow = true;
        }
        if (mc.player.isInWater()) {
            m.warning("In water - stopping (the elytra can't fly in water).");
            m.toggle();
            return;
        }

        if (mc.player.tickCount % 20 == 0) {
            if (mc.player.tickCount % 20 == 0) LOG.info("[VG] dig mode={} creep={} stall={} mineTarget={} mineTicks={} speed={} target={} clearAhead={} vSpeed={} capV={} dvLimited={} hardBrakes={} wallClips={}",
            mineModeTicks, creepTicks, stallTicks, mineTarget, mineTicks, String.format("%.2f", curSpeed),
            String.format("%.2f", dbgTargetSpeed), String.format("%.1f", dbgClearAhead), String.format("%.2f", curV), String.format("%.2f", setbackCapV), dvLimited, hardBrakes, wallClips);
        if (mc.player.tickCount % 20 == 0) LOG.info("[VG] tick phase={} gliding={} onGround={} pos=({}) vel=({}) vfMove=({}) ourMove=({}) sH={} cap={} setbackTicks={} path={}/{} reachesGoal={} frontier={} search={} noProgress={} strikes={} fwdHeld={} vfOn={}",
                phase, mc.player.isFallFlying(), mc.player.onGround(), v(mc.player.position()), v(mc.player.getDeltaMovement()),
                v(dbgVfMove), v(dbgOurMove), String.format("%.2f", dbgSH), String.format("%.2f", setbackCap), setbackTicks,
                pathIdx, path.size(), pathReachesGoal, pathEndsAtFrontier, search != null, noProgressTicks, stuckStrikes,
                mc.options.keyUp.isDown(), vf.isActive());
        }
        if (mc.player.tickCount % 20 == 0) {
            Vec3 pp = mc.player.position();
            progress.addLast(new double[] {mc.player.tickCount, Math.hypot(targetX.get() + 0.5 - pp.x, targetZ.get() + 0.5 - pp.z)});
            while (progress.size() > 61) progress.pollFirst();
        }
        if (setbackTicks > 0) setbackTicks--;
        if (mineModeTicks > 0) mineModeTicks--;
        if (creepTicks > 0) creepTicks--;
        int recoverEvery = Math.max(1, Tuning.i("setback.recover.ticks", 100));
        if (++ticksSinceSetback % recoverEvery == 0 && setbackCapV != Double.POSITIVE_INFINITY) {
            setbackCapV *= Tuning.d("setback.recover", 1.15);
            if (setbackCapV >= Math.max(vf.verticalSpeed.get(), MIN_V)) setbackCapV = Double.POSITIVE_INFINITY;
        }
        if (ticksSinceSetback % recoverEvery == 0 && setbackCap != Double.POSITIVE_INFINITY) {
            setbackCap *= Tuning.d("setback.recover", 1.15); // no rubberbands for a while: creep back up
            if (setbackCap >= Math.max(vf.horizontalSpeed.get(), 1)) setbackCap = Double.POSITIVE_INFINITY;
        }
        if (swapCooldown > 0) swapCooldown--;
        if (!checkElytra()) return;

        updateGoal();

        // The elytra just closed in mid-air (the server closes it with every pull-back): our velocity is whatever the
        // glide physics left in it while we were steering by hand - several blocks a tick. Left alone it flings us away
        // from where the server has us before the elytra is open again; the server rejects that, opens the elytra at ITS
        // position, and pulls us back again as soon as we move. Fall from rest instead.
        boolean glNow = mc.player.isFallFlying();
        if (!glNow && !mc.player.onGround() && !mc.player.isInLava() && !mc.player.isInWater() && stowedElytraSlot < 0 && Tuning.b("air.drop.velocity", true)) {
            Vec3 vel = mc.player.getDeltaMovement();
            double hv = Math.hypot(vel.x, vel.z);
            if (glidingLastTick || hv > Tuning.d("air.drift.max", 0.05)) {
                if (glidingLastTick && (hv > 0.3 || vel.y < -0.5)) LOG.info("[VG] elytra closed at ({}) - dropped the leftover velocity ({})", v(mc.player.position()), v(vel));
                mc.player.setDeltaMovement(0, glidingLastTick ? Mth.clamp(vel.y, -0.1, 0) : vel.y, 0);
            }
        }
        glidingLastTick = glNow;
        // opening the elytra is two steps: the tick before, we started gliding on our side only, so that the movement
        // packet in between told the server the truth about "on ground" (NoFall says "on ground" while falling fast,
        // and the server refuses to open an elytra for someone on the ground - without telling us)
        if (pendingStartAge >= 0 && mc.player.tickCount > pendingStartAge) {
            if (mc.player.isFallFlying() && mc.player.isInLava()) {
                mc.player.stopFallFlying(); // dropped into lava in the tick between: never ask the server to open it in there
                LOG.info("[VG] elytra: not opening - in lava");
            }
            else if (mc.player.isFallFlying()) {
                mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                startSentAge = mc.player.tickCount;
                if (srvGlide != 1) srvGlide = 0;
                LOG.info("[VG] elytra: open sent at ({}) vy={} fall={}", v(mc.player.position()), String.format("%.2f", mc.player.getDeltaMovement().y), String.format("%.1f", mc.player.fallDistance));
            }
            pendingStartAge = -1;
        }
        // we think we're gliding but the server never confirmed it: every move would be pulled back. Close it here too
        // and open it again properly.
        if (mc.player.isFallFlying() && srvGlide == 0 && srvSeenTrue && pendingStartAge < 0 && Tuning.b("glide.desync.fix", true)
            && mc.player.tickCount - startSentAge > Tuning.i("glide.confirm.ticks", 20)) {
            desyncFixes++;
            LOG.info("[VG] glide desync #{} at ({}): the server doesn't have us gliding - closing the elytra here and opening it again", desyncFixes, v(mc.player.position()));
            mc.player.stopFallFlying();
            startSentAge = mc.player.tickCount;
        }

        boolean gliding = mc.player.isFallFlying();
        Vec3 pos = mc.player.position();

        // portals are looked for on every flight (and logged); we only divert to one when portal-stop is on - in the
        // module's settings, or forced with "portal.stop = true" in the tuning file
        boolean portalStopOn = portalStop.get() || Tuning.b("portal.stop", false);
        if (!divertedToPortal && !emergencyLanding && (phase == Phase.Flying || phase == Phase.TakeOff)) scanForPortals(pos, portalStopOn);
        if (gliding && phase == Phase.Flying && lastMoveEventAge >= 0 && mc.player.tickCount - lastMoveEventAge > 1) {
            history.addLast("t" + mc.player.tickCount + " NO MOVE EVENT since t" + lastMoveEventAge + " pos(" + v(pos) + ")");
            while (history.size() > 40) history.pollFirst();
        }
        if (mending) updateMend(pos, gliding);
        if (digging != null && !FlightPathfinder.blocks(mc.level, digging, mc.level.getBlockState(digging))) {
            boolean byServer = pendingBreak.containsKey(digging.asLong());
            brokeAge.put(digging.asLong(), byServer ? mc.player.tickCount - 1000 : mc.player.tickCount);
            if (!byServer) lastBrokeAge = mc.player.tickCount;
            digging = null;
            if (pendingBreak.size() > 200) pendingBreak.clear();
        }
        if (brokeAge.size() > 400) brokeAge.clear();
        updateMining(pos, gliding);
        updateDigOut(pos, gliding);
        if (rotate.get() == Rotate.ServerOnly && gliding) {
            // the server simulates a vanilla glide from our look direction and deals fly-into-wall damage when THAT hits
            // something: looking straight up/down gives it no forward speed. Every gliding tick - also while hovering to
            // dig or mend (hovering with the camera's own direction cost 4 hp every 4 s).
            double simPitch = Tuning.d("sim.pitch", 90);
            Rotations.rotate(Float.isNaN(travelYaw) ? mc.player.getYRot() : travelYaw, simPitch < -90.5 ? mc.player.getXRot() : simPitch);
        }
        if (gliding && phase == Phase.Flying && !watchdog(pos)) return;

        // arrived?
        if (phase == Phase.Flying && pathReachesGoal && !path.isEmpty() && pos.distanceTo(path.getLast()) < 0.9) {
            if (arrive.get() == Arrive.Hover || hoverOnly) {
                if (hoverOnly) m.warning("Hovering above %s - nowhere safe to land there.", targetString());
                m.info("Arrived at %s.", targetString());
                m.toggle();
                if (logoutOnArrival.get() && !emergencyLanding) logout("Arrived at " + targetString() + ".");
                return;
            }
            phase = Phase.Landing;
        }
        if (phase == Phase.Landing) {
            groundTicks = mc.player.onGround() ? groundTicks + 1 : 0;
            if (groundTicks >= 2) {
                BlockPos b = mc.player.blockPosition();
                m.info("Landed at %d %d %d.", b.getX(), b.getY(), b.getZ());
                m.toggle();
                if (emergencyLanding) logout(String.format("Elytra worn out, no spare - landed at %d %d %d and logged out.", b.getX(), b.getY(), b.getZ()));
                else if (divertedToPortal) logout(String.format("Landed next to a Nether portal at %d %d %d.", portalFound.getX(), portalFound.getY(), portalFound.getZ()));
                else if (logoutOnArrival.get() && !emergencyLanding) logout(String.format("Arrived at %d %d %d.", b.getX(), b.getY(), b.getZ()));
                return;
            }
        }

        // keep VolytraFly's forward held while we're flying a path; release it otherwise (or we'd walk)
        boolean wantForward = gliding && phase == Phase.Flying;
        if (wantForward) {
            holdingForward = true;
            mc.options.keyUp.setDown(true);
        }
        else releaseForward();

        if (!gliding && phase != Phase.Landing) takeOff();
        else if (gliding && phase == Phase.TakeOff) {
            phase = Phase.Flying;
            jumps = 0;
            takeoffFails = 0;
        }

        // path searching
        ticksSinceSearch++;
        if (retryIn > 0) retryIn--;
        if (search != null) {
            // hovering / about to run out of path: we're not flying anyway, so search harder
            boolean idle = path.isEmpty() || pathIdx >= path.size() - 3 || dbgOurMove.lengthSqr() < 1e-4;
            int ms = idle ? Math.max(computeMs.get(), Tuning.i("search.ms.idle", 20)) : computeMs.get();
            FlightPathfinder.Status s = search.step(ms * 1_000_000L);
            // don't sit there searching every cave: after 1.5 s (5 s when the destination is loaded) take the best so far
            // ...but only settle for "closest so far" if that actually gets us somewhere (else it's a dead-end pocket)
            if (s == FlightPathfinder.Status.RUNNING && ++searchTicks > (goalNode == null ? Tuning.i("search.ticks.partial", 30) : Tuning.i("search.ticks.goal", 100))
                && (search.bestGain() >= Tuning.d("search.min.gain", 16) || searchTicks > Tuning.i("search.ticks.max", 160))) {
                s = search.finishNow();
                LOG.info("[VG] search out of time after {} ticks ({} nodes) - using the closest so far", searchTicks, search.expanded());
            }
            if (s != FlightPathfinder.Status.RUNNING) searchDone(s);
        }
        else if (phase != Phase.Landing && retryIn == 0 && needReplan(pos)) startSearch();
    }

    private static int durability(ItemStack s) {
        return s.getMaxDamage() - s.getDamageValue();
    }

    /** Swap a worn-out elytra / land before it breaks. False = stopped. */
    private boolean checkElytra() {
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (mendCooldown > 0) mendCooldown--;
        if (mending) return true;
        if (!chest.is(Items.ELYTRA) || !chest.isDamageableItem()) return true;
        int dur = durability(chest);
        if (mendWithXp.get() && dur <= mendAt.get() && mendCooldown == 0 && phase == Phase.Flying && mc.player.isFallFlying()
            && EnchantmentHelper.has(chest, EnchantmentEffectComponents.REPAIR_WITH_XP)
            && InvUtils.find(Items.EXPERIENCE_BOTTLE).found()) {
            mending = true;
            mendTicks = 0;
            throwCooldown = 0;
            m.info("Elytra at %d durability - mending it with XP bottles.", dur);
            return true;
        }
        if (dur > minDurability.get()) return true;
        if (swapCooldown > 0) return true;

        if (swapElytra.get()) {
            // the freshest spare (unbreakable ones count as best)
            int min = minDurability.get(), best = -1, bestDur = min * 2;
            for (int i = 0; i < 36; i++) {
                ItemStack st = mc.player.getInventory().getItem(i);
                if (!st.is(Items.ELYTRA)) continue;
                int d = st.isDamageableItem() ? durability(st) : Integer.MAX_VALUE;
                if (d > bestDur) {
                    best = i;
                    bestDur = d;
                }
            }
            if (best >= 0) {
                InvUtils.move().from(best).toArmor(2);
                swapCooldown = 20;
                m.info("Elytra at %d durability - put on a fresh one.", dur);
                return true;
            }
        }

        if (!mc.player.isFallFlying() && phase == Phase.TakeOff) {
            m.error("Elytra is at %d durability and there's no spare - not taking off.", durability(chest));
            m.toggle();
            return false;
        }
        if (emergencyLand.get() && !emergencyLanding && phase != Phase.Landing) {
            emergencyLanding = true;
            String was = targetString();
            Vec3 p = mc.player.position();
            m.warning("Elytra at %d durability and no spare - landing here. Use .vgoto %s to carry on afterwards.", durability(chest), was);
            go(Mth.floor(p.x), null, Mth.floor(p.z));
        }
        return true;
    }

    /** No real progress for 10 s: plan again; keeps happening with nothing to wait for = give up. */
    private boolean watchdog(Vec3 pos) {
        double d = goalNode != null ? pos.distanceTo(Vec3.atBottomCenterOf(goalNode)) : Math.hypot(targetX.get() + 0.5 - pos.x, targetZ.get() + 0.5 - pos.z);
        if (d < bestProgress - 2) {
            bestProgress = d;
            noProgressTicks = 0;
            stuckStrikes = 0;
            exemptTicks = 0;
            return true;
        }
        if (mending) return true;
        if (mineTarget != null || digOutTarget != null || tunneling) { // digging has its own time limits - but never hang on them
            if (++exemptTicks < Tuning.i("watchdog.exempt.ticks", 500)) return true;
            exemptTicks = 0;
            snapAge = -10000;
            snapshot("no progress for 25 s while digging - starting the route over");
            m.warning("No progress for 25 seconds while digging - starting the route over.");
            BlockPos bad = mineTarget != null ? mineTarget : digOutTarget;
            resetRoute();
            if (bad != null) noMine.add(bad.asLong());
            return true;
        }
        exemptTicks = 0;
        if (++noProgressTicks < Tuning.i("watchdog.ticks", 200)) return true;
        noProgressTicks = 0;
        replanNow = true;
        boolean waitingForChunks = pathEndsAtFrontier && !path.isEmpty() && pos.distanceTo(path.getLast()) < 4;
        if (waitingForChunks || setbackTicks > 0) return true;
        if (++stuckStrikes >= 4) {
            snapAge = -10000;
            snapshot("stuck 40 s - starting over");
            m.warning("Stuck for 40 seconds - starting the route over (digging allowed).");
            stuckStrikes = 0;
            resetRoute();
            if (mineThrough.get()) startDigging("Digging allowed while getting unstuck.");
            return true;
        }
        if (mineThrough.get() && mineModeTicks == 0) {
            startDigging("Not getting any closer - planning a new route (digging through if there's no way around).");
            return true;
        }
        snapshot("not getting any closer (strike " + stuckStrikes + ")");
        m.warning("Not getting any closer - planning a new route.");
        return true;
    }

    private boolean hasElytra() {
        return LivingEntity.canGlideUsing(mc.player.getItemBySlot(EquipmentSlot.CHEST), EquipmentSlot.CHEST);
    }

    private boolean swimKeys;
    private boolean tunneling;
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap brokeAge = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private int lastBrokeAge = -10000;
    private Vec3 digHold; // wait here: the block ahead was only just broken (the server may not have agreed yet)
    private Vec3 groundNudge; // a small walking step to apply in the move event (after other modules, e.g. Speed)
    private int centerTicks;
    private List<Vec3> extendPath; // the path a running search is extending from its end (null: planning from where we are)
    private int extendIdx;
    private int trappedSearches;
    private double takeoffGroundY = Double.NaN, lastAirY = Double.NaN;
    private int airStuckTicks, noRoomTicks;
    private int stowedElytraSlot = -1, outOfLavaTicks;

    private void releaseSwimKeys() {
        if (!swimKeys) return;
        swimKeys = false;
        mc.options.keyJump.setDown(meteordevelopment.meteorclient.utils.misc.input.Input.isPressed(mc.options.keyJump));
        mc.options.keyUp.setDown(meteordevelopment.meteorclient.utils.misc.input.Input.isPressed(mc.options.keyUp));
    }

    private boolean glideBugged, wasGliding, exitStandable;
    private Vec3 bugAnchor;
    private int bugTicks, bugAnchorSetbacks, stowFailAge = -10000;

    /**
     * Everything we remember as "the player's age when...": the age starts again from 0 after a relog, and old stamps
     * from before it then lie about the past (dig() thought it had just broken a block - for ever; the low-health logout
     * thought we'd just been hurt). Called for a new world and when a flight starts.
     */
    private void resetAgeStamps() {
        lastHurtAge = lastBrokeAge = glideCloseAge = lavaLogAge = startSentAge = snapAge = setbackMsgAge = loopLogAge = tunnelEndAge = footSetbackAge
            = unplannedLogAge = pairedWarnAge = exitCacheAge = -100000;
        pendingStartAge = -1;
        srvGlide = -1;
        srvSeenTrue = false; // (unknown again: trust our own glide flag until the server next says something)
        brokeAge.clear();
        pendingBreak.clear();
        pmTarget = null;
        digging = null;
        takeoffAnchor = null;
        takeoffStill = takeoffForce = footSetbacks = 0;
        wasGliding = glidingLastTick = mc.player != null && mc.player.isFallFlying();
        // back in mid-trouble (a relog while burning): the low-health logout must not wait for another hit
        if (mc.player != null && (mc.player.isOnFire() || mc.player.isInLava())
            && mc.player.getHealth() + mc.player.getAbsorptionAmount() <= Tuning.d("logout.hp", 8)) lastHurtAge = mc.player.tickCount;
    }

    private Vec3 lavaAnchor;
    private int lavaStillTicks, glideCloseAge = -10000, lavaLogAge = -10000;

    /**
     * Lava without fire resistance, every tick. The rules, all of them:
     *  1. The elytra STAYS ON. (Taking it off to "reset" it made a full inventory and a relog each dangerous: the
     *     elytra could end up packed away with nothing to put it back on.)
     *  2. The elytra is never OPENED in lava - not by us (take-off checks it), not by VolytraFly's auto take-off (jump is
     *     let go one tick in six so its 8-tick timer never fires).
     *  3. Gliding and moving: nothing to do here - the move code flies to the nearest way out ("escaping").
     *  4. Gliding but stuck in the lava (a glide that was opened in here goes nowhere): CLOSE the glide the way VolytraFly
     *     does - a second "start" makes the server toggle it off - and swim.
     *  5. Not gliding: swim (jump + forward) to the nearest place to stand, digging what's in the way - unless something
     *     is being eaten: Auto Gap / Auto Eat go first.
     * True while the swim is in charge of this tick.
     */
    private boolean lavaSwim() {
        boolean inLava = mc.player.isInLava(), gliding = mc.player.isFallFlying();
        Vec3 pos = mc.player.position();
        stowedElytraSlot = -1; // (a packed elytra is put back on by the check in tick())

        if (!inLava || lavaOkNow) {
            wasGliding = gliding;
            lavaAnchor = null;
            lavaStillTicks = 0;
            // hopping out onto the shore: keep walking forward a moment, then hand over to the normal take-off. Jump comes
            // up AT ONCE: VolytraFly's auto take-off timer starts on a jump-key press and fires on the first tick, 8 or more
            // later, that finds the key down - left down across a hop it fired back in the lava.
            if (swimKeys) {
                mc.options.keyJump.setDown(false);
                if (mc.player.onGround() || gliding || lavaOkNow || ++outOfLavaTicks > Tuning.i("lava.hop.ticks", 8)) releaseSwimKeys();
            }
            return false;
        }
        outOfLavaTicks = 0;

        if (gliding) {
            boolean openedHere = !wasGliding;
            wasGliding = true;
            if (lavaAnchor == null || pos.distanceTo(lavaAnchor) > 1.0) {
                lavaAnchor = pos;
                lavaStillTicks = 0;
            }
            else lavaStillTicks++;
            if (!openedHere && lavaStillTicks <= Tuning.i("lava.still.ticks", 20)) {
                releaseSwimKeys();
                return false; // rule 3: flying out
            }
            // rule 4: always close it on our side; the server's side is closed below (rate-limited)
            if (mc.player.tickCount - lavaLogAge > 20) {
                lavaLogAge = mc.player.tickCount;
                LOG.info("[VG] lava: glide {} at ({}) - closing it and swimming (server gliding={})", openedHere ? "was opened in the lava" : "stuck for " + lavaStillTicks + " ticks", v(pos), srvGlide);
            }
            mc.player.stopFallFlying();
            pendingStartAge = -1;
            lavaAnchor = null;
            lavaStillTicks = 0;
        }
        wasGliding = false;
        // swimming, but the server still has a glide open (the one just closed here, or one VolytraFly opened): a second
        // "start" makes it toggle that off. Only when it really is open - to a server that has none, "start" would OPEN
        // one, in lava - and not more than once a second.
        if (srvGlide == 1 && mc.player.tickCount - glideCloseAge > 20) {
            glideCloseAge = mc.player.tickCount;
            mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            LOG.info("[VG] lava: asked the server to close its glide");
        }

        // rule 5: swim
        Vec3 exit = lavaExit(pos);
        Vec3 aim = exit;
        if (exit == null || !exitStandable) {
            // no shore in reach: swim up to the surface, then along it towards where we're going
            double dx = targetX.get() + 0.5 - pos.x, dz = targetZ.get() + 0.5 - pos.z, l = Math.hypot(dx, dz);
            if (exit != null && exit.y > pos.y + 1.2) aim = exit;
            else if (l > 1) aim = new Vec3(pos.x + dx / l * 6, pos.y, pos.z + dz / l * 6);
        }
        BlockPos dig = null;
        if (aim != null) {
            Vec3 d = aim.subtract(pos);
            double l = Math.hypot(d.x, d.z);
            if (l > 0.05) mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
            if (mc.player.horizontalCollision && l > 0.6) {
                for (double h : new double[] {1.0, 0.2}) {
                    BlockPos b = BlockPos.containing(pos.x + d.x / l * 0.8, pos.y + h, pos.z + d.z / l * 0.8);
                    if (exitStandable && exit != null && b.getY() < Mth.floor(exit.y)) continue; // that's the shore itself
                    if (swimDiggable(b)) {
                        dig = b;
                        break;
                    }
                }
            }
        }
        if (dig == null && (aim == null || aim.y > pos.y + 1.5)) {
            BlockPos above = BlockPos.containing(pos.x, pos.y + 1.9, pos.z);
            if (swimDiggable(above)) dig = above;
        }
        boolean eating = mc.player.isUsingItem() || mc.options.keyUse.isDown();
        if (dig != null && !eating) dig(dig);
        swimKeys = true;
        BlockPos feetCell = BlockPos.containing(pos.x, pos.y + 0.1, pos.z);
        boolean submerged = !mc.level.getBlockState(feetCell).getFluidState().isEmpty();
        mc.options.keyJump.setDown((submerged || aim == null || aim.y > pos.y + 0.6) && mc.player.tickCount % 6 != 0); // (rule 2)
        mc.options.keyUp.setDown(aim != null && Math.hypot(aim.x - pos.x, aim.z - pos.z) > 0.06);
        if (mc.player.tickCount % 20 == 0) LOG.info("[VG] lava swim: pos=({}) exit={} standable={} dig={} eating={} onGround={} hp={}", v(pos), exit == null ? "none" : v(exit),
            exitStandable, dig, eating, mc.player.onGround(), String.format("%.1f", mc.player.getHealth()));
        return true;
    }

    /** Lava or something solid within n blocks below. */
    private boolean surfaceBelow(Vec3 pos, int n) {
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= n; i++) {
            q.set(Mth.floor(pos.x), Mth.floor(pos.y) - i, Mth.floor(pos.z));
            BlockState st = mc.level.getBlockState(q);
            if (!st.getFluidState().isEmpty() || !st.getCollisionShape(mc.level, q).isEmpty()) return true;
        }
        return false;
    }

    private void lastHpKeep() {
    }

    /** Dig-out rule when wedged: breakable, and no fluid above it or level with it on any side (below is fine). */
    private boolean stuckDiggable(BlockPos b) {
        BlockState st = mc.level.getBlockState(b);
        if (st.getCollisionShape(mc.level, b).isEmpty() || st.hasBlockEntity() || noMine.contains(b.asLong())) return false;
        float h = st.getDestroySpeed(mc.level, b);
        if (h < 0 || h >= 20) return false;
        for (BlockPos n : new BlockPos[] {b.above(), b.north(), b.south(), b.east(), b.west()}) {
            if (!mc.level.getBlockState(n).getFluidState().isEmpty()) return false;
        }
        return !(mc.level.getBlockState(b.above()).getBlock() instanceof net.minecraft.world.level.block.FallingBlock);
    }

    /** Is there room to open the elytra here: 3 clear blocks above the floor, 2 clear ahead, no lava touching. */
    private boolean roomToTakeOff(BlockPos feet) {
        for (int i = 0; i < 3; i++) {
            BlockPos c = feet.above(i);
            if (FlightPathfinder.blocks(mc.level, c, mc.level.getBlockState(c))) return false;
        }
        int open = 0;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos a = feet.relative(d).above();
            if (!FlightPathfinder.blocks(mc.level, a, mc.level.getBlockState(a)) && !FlightPathfinder.blocks(mc.level, a.above(), mc.level.getBlockState(a.above()))) open++;
        }
        return open >= 1;
    }

    /** The next block to dig to get take-off room here (up first, then towards where we're going); null if none. */
    private BlockPos roomDigTarget(BlockPos feet) {
        for (int i = 1; i <= 2; i++) if (stuckDiggable(feet.above(i))) return feet.above(i);
        double dx = targetX.get() + 0.5 - mc.player.getX(), dz = targetZ.get() + 0.5 - mc.player.getZ();
        net.minecraft.core.Direction first = Math.abs(dx) > Math.abs(dz)
            ? (dx > 0 ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.WEST)
            : (dz > 0 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH);
        net.minecraft.core.Direction[] order = {first, first.getClockWise(), first.getCounterClockWise(), first.getOpposite()};
        for (net.minecraft.core.Direction d : order) {
            for (int i = 2; i >= 1; i--) {
                BlockPos c = feet.relative(d).above(i);
                if (stuckDiggable(c)) return c;
            }
        }
        return null;
    }

    private boolean swimDiggable(BlockPos b) {
        BlockState st = mc.level.getBlockState(b);
        if (st.getCollisionShape(mc.level, b).isEmpty() || st.hasBlockEntity()) return false;
        float h = st.getDestroySpeed(mc.level, b);
        return h >= 0 && h < 50 && !noMine.contains(b.asLong());
    }

    private Vec3 exitCache;
    private int exitCacheAge = -1000;

    /** Nearest open (no fluid, 2 high) spot within 12 blocks / 30 up, much preferring one you can stand on; null if none. */
    private Vec3 lavaExit(Vec3 pos) {
        if (mc.player.tickCount - exitCacheAge < 20 && mc.player.tickCount >= exitCacheAge) return exitCache;
        exitCacheAge = mc.player.tickCount;
        BlockPos f = BlockPos.containing(pos);
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        Vec3 best = null;
        boolean bestStand = false;
        double bestScore = Double.MAX_VALUE;
        // how high the lava goes above us: a shore counts only if it is no more than a block above that surface (a ledge
        // 3 up and 2 away used to win - the swim then burrowed into the cliff under it, for ever)
        int surfaceY = f.getY();
        for (int up = 0; up < 30; up++) {
            q.set(f.getX(), f.getY() + up, f.getZ());
            if (!mc.level.getBlockState(q).getFluidState().is(FluidTags.LAVA)) break;
            surfaceY = f.getY() + up + 1;
        }
        for (int dy = -2; dy <= 30; dy++) {
            int r = dy >= -1 && dy <= 4 ? 28 : 12; // look further along our own level (the shore of a lava lake)
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    double dist = Math.sqrt(dx * dx + dz * dz + dy * dy);
                    if (dist + 0 >= bestScore) continue;
                    int x = f.getX() + dx, y = f.getY() + dy, z = f.getZ() + dz;
                    q.set(x, y, z);
                    BlockState a = mc.level.getBlockState(q);
                    if (!a.getFluidState().isEmpty() || !a.getCollisionShape(mc.level, q).isEmpty()) continue;
                    q.set(x, y + 1, z);
                    BlockState h = mc.level.getBlockState(q);
                    if (!h.getFluidState().isEmpty() || !h.getCollisionShape(mc.level, q).isEmpty()) continue;
                    q.set(x, y - 1, z);
                    BlockState below = mc.level.getBlockState(q);
                    boolean stand = below.getFluidState().isEmpty() && !below.getCollisionShape(mc.level, q).isEmpty() && y <= surfaceY + 1;
                    double score = dist + (stand ? 0 : Tuning.d("lava.exit.air.penalty", 40)) + (dy < 0 ? 4 : 0);
                    if (stand) {
                        for (net.minecraft.core.Direction dd : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                            q.set(x + dd.getStepX(), y, z + dd.getStepZ());
                            if (!mc.level.getBlockState(q).getFluidState().isEmpty()) {
                                score += 3;
                                break;
                            }
                        }
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Vec3(x + 0.5, y + 0.1, z + 0.5);
                        bestStand = stand;
                    }
                }
            }
        }
        exitCache = best;
        exitStandable = bestStand;
        return best;
    }

    /** Like clearLine but only solid blocks count (we're already in a fluid). */
    private boolean clearSolid(Vec3 a, Vec3 b) {
        AABB box = new AABB(a.x - 0.3, a.y, a.z - 0.3, a.x + 0.3, a.y + 0.6, a.z + 0.3);
        Vec3 d = b.subtract(a);
        int n = Math.max(1, (int) Math.ceil(d.length() / 0.3));
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int i = 1; i <= n; i++) {
            AABB bb = box.move(d.scale(i / (double) n));
            for (int x = Mth.floor(bb.minX); x <= Mth.ceil(bb.maxX) - 1; x++)
                for (int y = Mth.floor(bb.minY); y <= Mth.ceil(bb.maxY) - 1; y++)
                    for (int z = Mth.floor(bb.minZ); z <= Mth.ceil(bb.maxZ) - 1; z++) {
                        q.set(x, y, z);
                        if (!mc.level.getBlockState(q).getCollisionShape(mc.level, q).isEmpty()) return false;
                    }
        }
        return true;
    }

    private void takeOff() {
        if (phase != Phase.TakeOff) phase = Phase.TakeOff;
        if (takeoffWait > 0) takeoffWait--;
        // never just stand there: on the ground in one spot for 5 s -> say what we were doing, then plain jump-and-open
        Vec3 tp = mc.player.position();
        // (1.5 blocks: shuffling half a block and being pulled back, three times a second for minutes, kept resetting a
        // 0.4-block anchor)
        if (takeoffAnchor != null && tp.distanceTo(takeoffAnchor) < Tuning.d("takeoff.stuck.radius", 1.5)) {
            if (mc.player.onGround()) takeoffStill++;
        }
        else {
            takeoffAnchor = tp;
            takeoffStill = 0;
        }
        if (takeoffStill > 0 && takeoffStill % 40 == 0 && mc.player.onGround()) LOG.info("[VG] takeoff: on the ground at ({}) for {} ticks - {}", v(tp), takeoffStill, takeoffDoing);
        if (takeoffStill == Tuning.i("takeoff.stuck.ticks", 60) && mc.player.onGround()) {
            takeoffStill++;
            snapAge = -10000;
            snapshot("standing still in take-off: " + takeoffDoing);
            m.warning("Standing still (%s) - just taking off.", takeoffDoing);
            takeoffForce = Tuning.i("takeoff.force.ticks", 100);
            tunneling = false;
            noRoomTicks = 0;
            replanNow = true;
        }
        if (takeoffForce > 0) takeoffForce--;
        boolean force = takeoffForce > 0;
        takeoffDoing = "nothing";
        // no headroom to take off here (low tunnel / cave): walk along the path, digging, until there is
        if (mc.player.onGround()) {
            BlockPos fc = BlockPos.containing(mc.player.getX(), mc.player.getY() + 0.2, mc.player.getZ());
            boolean roomHere = mc.level.noCollision(mc.player, mc.player.getBoundingBox().expandTowards(0, 1.3, 0)) && roomToTakeOff(fc);
            noRoomTicks = roomHere ? 0 : noRoomTicks + 1;
        }
        boolean crawlingNow = mc.player.getBoundingBox().getYsize() < 1.0;
        // On the ground with rock to dig within the next few steps of the route: stay on foot and dig through it (one hit
        // a block) rather than open 3 blocks of headroom, take off, dig one block in the air five times slower, have the
        // server close the elytra in the hole, land, and start again - which is what 20% of the time went on in solid
        // rock.
        boolean rockAhead = false;
        if (mc.player.onGround() && mineThrough.get() && !force && Tuning.b("walk.rock.ahead", false) && path.size() - pathIdx > 1) {
            int i0 = nearestIndex(tp, Math.max(0, pathIdx - 4), Math.min(path.size(), pathIdx + 8));
            for (int k = i0 + 1; k <= Math.min(path.size() - 1, i0 + Tuning.i("walk.rock.nodes", 4)); k++) {
                BlockPos c = BlockPos.containing(path.get(k));
                if (plannedMine.contains(c.asLong()) && FlightPathfinder.blocks(mc.level, c, mc.level.getBlockState(c))) {
                    rockAhead = true;
                    break;
                }
            }
        }
        // just dropped out of a glide onto the floor: stand still for a few ticks first. Setting off at once got every step
        // pulled back to where the glide had ended (a few centimetres up in the air) until the 5-pull-backs rule cut in.
        footTicks = mc.player.onGround() ? footTicks + 1 : 0;
        boolean settled = footTicks >= Tuning.i("walk.settle.ticks", 5);
        if (!settled && mc.player.onGround() && (tunneling || crawlingNow)) {
            takeoffDoing = "settling on the ground before walking";
            return;
        }
        if ((!force || crawlingNow) && (tunneling || crawlingNow || rockAhead || noRoomTicks > Tuning.i("walk.noroom.ticks", 20)) && mineThrough.get() && mc.player.onGround() && walkTunnel()) {
            takeoffDoing = "walking the tunnel (path " + pathIdx + "/" + path.size() + ", tunneling=" + tunneling + ", crawling=" + crawlingNow + ", noRoom=" + noRoomTicks + ")";
            return;
        }
        if (crawlingNow) {
            takeoffDoing = "crawling: opening the block above";
            // in a 1-high gap and nothing to crawl along (the path goes up/down or has ended): don't try to fly from
            // here - open the block above so we can stand, the normal logic takes over from there
            if (mc.player.onGround()) {
                // what keeps us from standing up isn't always the block straight above: standing off-centre, it's the one
                // over the NEXT column (7 s on the ground, "opening the block above" - which was air)
                double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
                BlockPos feetC = BlockPos.containing(px, py + 0.2, pz);
                BlockPos t = null;
                for (BlockPos b : BlockPos.betweenClosed(Mth.floor(px - 0.3), Mth.floor(py + 0.61), Mth.floor(pz - 0.3),
                    Mth.floor(px + 0.3), Mth.floor(py + 1.79), Mth.floor(pz + 0.3))) {
                    if (FlightPathfinder.blocks(mc.level, b, mc.level.getBlockState(b)) && stuckDiggable(b)) {
                        t = b.immutable();
                        break;
                    }
                }
                double cx = feetC.getX() + 0.5, cz = feetC.getZ() + 0.5, off = Math.hypot(cx - px, cz - pz);
                BlockPos over = feetC.above();
                if (!FlightPathfinder.blocks(mc.level, over, mc.level.getBlockState(over)) && off > 0.05) {
                    // our own column is open above: just get under it
                    double sp = Math.min(0.1, off);
                    groundNudge = new Vec3((cx - px) / off * sp, 0, (cz - pz) / off * sp);
                    takeoffDoing = "crawling: moving under the open column";
                }
                else if (t != null && mineThrough.get()) {
                    dig(t);
                    takeoffDoing = "crawling: digging " + t.toShortString() + " to stand up";
                }
                else takeoffDoing = "crawling: nothing overhead is safe to dig";
            }
            return;
        }

        if (mc.player.onGround()) {
            BlockPos feet = BlockPos.containing(mc.player.getX(), mc.player.getY() + 0.2, mc.player.getZ());
            takeoffGroundY = mc.player.getY();
            AABB body = mc.player.getBoundingBox();
            boolean headroom = mc.level.noCollision(mc.player, body.expandTowards(0, 1.3, 0));
            // A low ceiling is no reason to dig: the elytra only needs us OFF the ground, and a hop of a few centimetres
            // under a 2-high ceiling is that. (Digging headroom before every take-off was the biggest single cause of
            // standing still: 7% of flight time.) One failed round of jumps and we dig after all.
            boolean lowJump = (!headroom || !roomToTakeOff(feet)) && takeoffFails < 1 && Tuning.b("takeoff.low", false)
                && mc.level.noCollision(mc.player, body.expandTowards(0, 0.15, 0));
            if ((!headroom || !roomToTakeOff(feet)) && !lowJump) {
                // small gap / something overhead: don't jump or open the elytra here
                // 1) our own cell has room and we're just standing off-centre under an overhang: step to its centre
                double cx = feet.getX() + 0.5, cz = feet.getZ() + 0.5, off = Math.hypot(cx - mc.player.getX(), cz - mc.player.getZ());
                AABB centred = body.move(cx - mc.player.getX(), 0, cz - mc.player.getZ());
                if (off > 0.05 && centerTicks < 60 && roomToTakeOff(feet) && mc.level.noCollision(mc.player, centred.expandTowards(0, 1.3, 0))) {
                    double sp = Math.min(0.12, off);
                    groundNudge = new Vec3((cx - mc.player.getX()) / off * sp, 0, (cz - mc.player.getZ()) / off * sp);
                    centerTicks++;
                    takeoffDoing = "stepping to the middle of the block";
                    return;
                }
                // 2) dig what's over our head (any block the body would hit on a jump), then towards where we're going
                if (mineThrough.get()) {
                    BlockPos t = null;
                    AABB up = body.expandTowards(0, 1.3, 0);
                    for (BlockPos b : BlockPos.betweenClosed(Mth.floor(up.minX), Mth.floor(body.maxY), Mth.floor(up.minZ),
                        Mth.floor(up.maxX), Mth.floor(up.maxY), Mth.floor(up.maxZ))) {
                        if (stuckDiggable(b)) {
                            t = b.immutable();
                            break;
                        }
                    }
                    if (t == null) t = roomDigTarget(feet);
                    if (t != null && dig(t)) {
                        if (mc.player.tickCount % 40 == 0) LOG.info("[VG] takeoff: no room at {} - digging {}", feet.toShortString(), t.toShortString());
                        takeoffDoing = "no headroom: digging " + t.toShortString();
                        return;
                    }
                }
                if (mc.player.tickCount % 100 == 0) {
                    snapAge = -10000;
                    snapshot("no room to take off, nothing to dig");
                }
            }
            if (headroom) centerTicks = 0;
            if (takeoffWait == 0) {
                if (++jumps > 8) {
                    jumps = 0;
                    takeoffFails++;
                    m.warning("Couldn't take off after 8 tries (%d) - trying again%s.", takeoffFails, mineThrough.get() ? ", digging upwards" : "");
                    snapAge = -10000;
                    snapshot("takeoff failed x" + takeoffFails);
                }
                BlockPos head = mc.player.blockPosition().above(2);
                if (FlightPathfinder.blocks(mc.level, head, mc.level.getBlockState(head)) && lowCeilingWarned++ == 0) {
                    m.warning("Low ceiling - taking off may not work here.");
                }
                mc.player.jumpFromGround();
                takeoffWait = 10;
                lowTakeoff = lowJump && Tuning.b("takeoff.low", false);
                takeoffDoing = "jumped (headroom=" + headroom + (lowJump ? ", low hop" : "") + ")";
            }
            else takeoffDoing = "waiting to jump";
        }
        else {
            // "in the air" but our height isn't changing: the server is holding us here (a block it has under our feet
            // that we don't see) and won't open the elytra - take off from it like from the ground
            double yNow = mc.player.getY();
            airStuckTicks = !Double.isNaN(lastAirY) && Math.abs(yNow - lastAirY) < 0.02 ? airStuckTicks + 1 : 0;
            lastAirY = yNow;
            if (airStuckTicks >= Tuning.i("takeoff.airstuck.ticks", 10) && !mc.player.isInLava()
                && mc.level.noCollision(mc.player, new AABB(mc.player.getX() - 0.3, mc.player.getY(), mc.player.getZ() - 0.3,
                    mc.player.getX() + 0.3, mc.player.getY() + 3.0, mc.player.getZ() + 0.3))) {
                airStuckTicks = 0;
                takeoffGroundY = yNow;
                takeoffWait = 10;
                mc.player.jumpFromGround();
                LOG.info("[VG] takeoff: held in mid-air at ({}) - jumping off the unseen ground", v(mc.player.position()));
                return;
            }
            // standing height (1.8) plus a little - with 2.5 here and 0.8 of air wanted below, a 3.1-high cave could never
            // satisfy both and the bot jumped on the spot for minutes; a failed round of jumps relaxes both
            double spaceH = takeoffFails >= 1 ? 1.0 : lowTakeoff ? 1.8 : Tuning.d("takeoff.space", 1.85);
            AABB standing = new AABB(mc.player.getX() - 0.3, mc.player.getY(), mc.player.getZ() - 0.3, mc.player.getX() + 0.3, mc.player.getY() + spaceH, mc.player.getZ() + 0.3);
            boolean space = mc.level.noCollision(mc.player, standing);
            boolean falling = mc.player.getDeltaMovement().y < 0, lava = mc.player.isInLava();
            // real air under our feet: opening the elytra a few centimetres above the ground (falling after the server
            // closed it) just puts us down on it, and the server closes the elytra of anyone touching the ground
            double clearBelow = -net.minecraft.world.entity.Entity.collideBoundingBox(mc.player, new Vec3(0, -1.0, 0), mc.player.getBoundingBox(), mc.level, java.util.List.of()).y;
            // ...and never right over lava: lava doesn't stop a fall, so "clear below" was true just above a pool, the
            // elytra opened there and we sank in with it open (which bugs the glide)
            // (lava BELOW only - lavaIn() also counts lava anywhere overhead, which blocked every take-off under a lava
            // ceiling for two minutes; and give up on the rule after a failed round of jumps)
            boolean lavaUnder = false;
            if (takeoffFails < 1) {
                AABB bb = mc.player.getBoundingBox();
                BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
                int yTop = Mth.floor(bb.minY), yBot = Mth.floor(bb.minY - Tuning.d("takeoff.lava.below", 0));
                for (int lx = Mth.floor(bb.minX); lx <= Mth.floor(bb.maxX) && !lavaUnder; lx++) {
                    for (int lz = Mth.floor(bb.minZ); lz <= Mth.floor(bb.maxZ) && !lavaUnder; lz++) {
                        for (int ly = yTop; ly >= yBot; ly--) {
                            q.set(lx, ly, lz);
                            BlockState ls = mc.level.getBlockState(q);
                            if (ls.getFluidState().is(FluidTags.LAVA)) {
                                lavaUnder = true;
                                break;
                            }
                            if (!ls.getCollisionShape(mc.level, q).isEmpty()) break; // solid ground first: fine
                        }
                    }
                }
            }
            boolean room = clearBelow >= (takeoffFails >= 1 ? 0.4 : lowTakeoff ? Tuning.d("takeoff.low.air", 0.7) : Tuning.d("takeoff.min.air", 0.7)) && !lavaUnder;
            boolean tried = false, ok = false;
            if (falling && takeoffWait <= (lowTakeoff ? 9 : 7) && !lava && room && space) {
                // what vanilla does when you press jump in the air with an elytra on
                tried = true;
                if (srvGlide == 1 && Tuning.b("glide.desync.fix", true)) {
                    // the server still has us gliding: asking again would CLOSE it - just glide on our side too
                    mc.player.startFallFlying();
                    ok = true;
                    LOG.info("[VG] elytra: the server already has us gliding - joining it");
                }
                else {
                    ok = mc.player.tryToStartFallFlying();
                    if (ok && Tuning.b("takeoff.two.step", true)) pendingStartAge = mc.player.tickCount;
                    else if (ok) {
                        mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                        startSentAge = mc.player.tickCount;
                        if (srvGlide != 1) srvGlide = 0;
                    }
                }
            }
            if (Tuning.b("log.takeoff", false)) LOG.info("[VG] takeoff air: y={} vy={} wait={} lava={} room={} tried={} ok={} gliding={} chest={}",
                String.format("%.2f", mc.player.getY()), String.format("%.2f", mc.player.getDeltaMovement().y), takeoffWait, lava, room, tried, ok,
                mc.player.isFallFlying(), mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem());
        }
    }

    // ------------------------------------------------------------------ goal / search

    private void updateGoal() {
        if (--goalCheckAge > 0) return;
        goalCheckAge = 20;

        int tx = targetX.get(), tz = targetZ.get();
        BlockPos g = null;
        if (fixedGoal != null) {
            hoverOnly = false;
            g = fixedGoal;
        }
        else if (mc.level.getChunkSource().hasChunk(tx >> 4, tz >> 4)) {
            hoverOnly = false;
            if (arrive.get() == Arrive.Hover) g = nearestOpen(tx, targetY.get(), tz);
            else {
                g = safeLandingNear(tx, tz, 8);
                if (g == null) {
                    // water, lava, magma... - hover over it instead of landing on whatever is there
                    int y = mc.level.dimensionType().hasCeiling() ? mc.player.getBlockY() : mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, tx, tz) + 3;
                    g = nearestOpen(tx, y, tz);
                    hoverOnly = g != null;
                }
            }
            if (!landingWarned && (g == null || hoverOnly)) {
                landingWarned = true;
                if (g == null) m.warning(arrive.get() == Arrive.Hover ? "%s is inside blocks - pick another height." : "Nowhere to land or hover at %s.", targetString());
                else m.warning("Nowhere safe to land within 8 blocks of %s (water/lava?) - will hover above it.", targetString());
            }
        }
        if (g != null && !g.equals(goalNode)) {
            boolean first = goalNode == null;
            goalNode = g;
            replanNow = true;
            if (first && arrive.get() == Arrive.Land) m.info("Landing spot: %d %d %d.", g.getX(), g.getY(), g.getZ());
        }
    }

    private BlockPos nearestOpen(int x, int y, int z) {
        for (int r = 0; r <= 4; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dy), Math.max(Math.abs(dx), Math.abs(dz))) != r) continue;
                        if (FlightPathfinder.open(mc.level, x + dx, y + dy, z + dz)) return new BlockPos(x + dx, y + dy, z + dz);
                    }
                }
            }
        }
        return null;
    }

    /** Closest safe landing spot within r blocks (horizontally) of x z. */
    private BlockPos safeLandingNear(int x, int z, int r) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double dd = dx * dx + dz * dz;
                if (dd > r * r || dd >= bestD) continue;
                if (!mc.level.getChunkSource().hasChunk((x + dx) >> 4, (z + dz) >> 4)) continue;
                BlockPos s = landingSpot(x + dx, z + dz);
                if (s != null && safeFloor(s.below())) {
                    best = s;
                    bestD = dd;
                }
            }
        }
        return best;
    }

    private boolean safeFloor(BlockPos floor) {
        BlockState st = mc.level.getBlockState(floor);
        if (!st.getFluidState().isEmpty() || BAD_FLOOR.contains(st.getBlock())) return false;
        if (st.getCollisionShape(mc.level, floor).isEmpty()) return false;
        // not right next to lava / fire either
        BlockPos up = floor.above();
        for (BlockPos n : new BlockPos[] {up.north(), up.south(), up.east(), up.west()}) {
            BlockState ns = mc.level.getBlockState(n);
            FluidState fs = ns.getFluidState();
            if (fs.is(FluidTags.LAVA) || ns.is(Blocks.FIRE) || ns.is(Blocks.SOUL_FIRE)) return false;
        }
        return true;
    }

    /** First spot at x z where you can stand: open air with something solid under it. */
    private BlockPos landingSpot(int x, int z) {
        int bottom = mc.level.getMinY(), top = mc.level.getMaxY() - 1;
        if (!mc.level.dimensionType().hasCeiling()) {
            int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            return standable(x, y, z) ? new BlockPos(x, y, z) : null;
        }
        // Nether: the heightmap is the roof - look down from our height, then up
        int from = Mth.clamp(mc.player.getBlockY(), bottom + 1, top);
        for (int y = from; y > bottom; y--) if (standable(x, y, z)) return new BlockPos(x, y, z);
        for (int y = from + 1; y <= top; y++) if (standable(x, y, z)) return new BlockPos(x, y, z);
        return null;
    }

    private boolean standable(int x, int y, int z) {
        if (!FlightPathfinder.open(mc.level, x, y, z)) return false;
        BlockPos below = new BlockPos(x, y - 1, z);
        return !mc.level.getBlockState(below).getCollisionShape(mc.level, below).isEmpty();
    }

    private boolean needReplan(Vec3 pos) {
        if (tunneling && !path.isEmpty()) {
            if (pathIdx < path.size() - 2) return false; // digging our way out: don't swap the path
            tunneling = false;
            trappedSearches = 0;
            tunnelEndAge = mc.player.tickCount;
            return true;
        }
        if (replanNow || path.isEmpty()) return true;
        if (ticksSinceSearch >= Tuning.i("replan.ticks", 100)) return true; // new chunks / blocks since last time
        if (pathEndsAtFrontier && ticksSinceSearch >= Tuning.i("frontier.replan.ticks", 5) && pos.distanceTo(path.getLast()) < Tuning.d("frontier.replan.distance", 24)) return true;
        if (!pathReachesGoal) {
            // partial path: plan the next piece before we get to its end
            double left = 0;
            for (int i = Math.max(pathIdx, 0); i < path.size() - 1 && left < 64; i++) left += path.get(i).distanceTo(path.get(i + 1));
            if (left < Math.min(64, effectiveRadius() / 2.0) && ticksSinceSearch >= 10) return true;
        }
        return false;
    }

    private int effectiveRadius() {
        int view = Math.max(2, mc.options.getEffectiveRenderDistance()) * 16 - 16;
        return Math.max(32, Math.min(searchRadius.get(), view));
    }

    private void startSearch() {
        searchTicks = 0;
        boolean urgent = replanNow || path.isEmpty();
        replanNow = false;
        ticksSinceSearch = 0;

        BlockPos start = startBlock();
        // not urgent and still some way to fly: plan the NEXT piece from the end of the current path while we fly it,
        // instead of waiting at the end and planning from there
        extendPath = null;
        if (!urgent && Tuning.b("search.extend", true) && !tunneling && mineModeTicks == 0 && path.size() - pathIdx > 3) {
            for (int ei = path.size() - 1; ei >= Math.max(pathIdx + 2, path.size() - 4); ei--) {
                BlockPos c = BlockPos.containing(path.get(ei));
                if (FlightPathfinder.open(mc.level, c.getX(), c.getY(), c.getZ())) {
                    start = c;
                    extendPath = path;
                    extendIdx = ei;
                    break;
                }
            }
        }
        int bottom = mc.level.getMinY(), top = mc.level.getMaxY() - 1;
        if (vf.limitMaxHeight.get()) top = Math.min(top, (int) Math.floor(vf.maxHeight.get()) - 1);

        double desiredY;
        double yWeight;
        double tripLeft = Math.hypot(targetX.get() + 0.5 - (start.getX() + 0.5), targetZ.get() + 0.5 - (start.getZ() + 0.5));
        FlightPathfinder.FLOOR_CLEARANCE = Math.max(0, Tuning.i("path.floor.clearance", 10));
        FlightPathfinder.FLOOR_WEIGHT = Tuning.d("path.floor.weight", 0.5);
        FlightPathfinder.CEILING_WEIGHT = Tuning.d("path.ceiling.weight", 0.3);
        FlightPathfinder.LAVA_COST = Tuning.d("path.lava.cost", 20);
        FlightPathfinder.FLOOR_TOUCH = Tuning.d("path.floor.touch", 15);
        FlightPathfinder.LAVA_OK_COST = Tuning.d("path.lavaok.cost", 6);
        if (mc.level.dimensionType().hasCeiling() && tripLeft >= cruiseDistance.get()) {
            desiredY = Mth.clamp(Tuning.i("nether.cruise.y", 95), bottom + 1, top - 1);
            yWeight = Tuning.d("nether.cruise.weight", 0.3);
        }
        else if (mc.level.dimensionType().hasCeiling() || tripLeft < cruiseDistance.get()) {
            desiredY = Mth.clamp(start.getY(), bottom + 1, top - 1);
            yWeight = 0.25;
        }
        else {
            desiredY = Mth.clamp(cruiseHeight.get(), bottom + 1, top - 1);
            yWeight = 1.0;
        }

        // hostile mobs within 64 blocks (the nearest 40): the search steers clear of them where it can
        try {
            java.util.ArrayList<net.minecraft.world.entity.Entity> mobs = new java.util.ArrayList<>();
            for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
                if (e instanceof net.minecraft.world.entity.monster.Monster && e.isAlive() && e.distanceToSqr(mc.player) < 64 * 64) mobs.add(e);
            }
            mobs.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(mc.player)));
            int nMobs = Math.min(mobs.size(), Tuning.i("path.mob.max", 40));
            double[] dg = new double[nMobs * 3];
            for (int mi = 0; mi < nMobs; mi++) {
                net.minecraft.world.entity.Entity e = mobs.get(mi);
                dg[mi * 3] = e.getX();
                dg[mi * 3 + 1] = e.getY() + e.getBbHeight() / 2;
                dg[mi * 3 + 2] = e.getZ();
            }
            FlightPathfinder.DANGERS = dg;
            FlightPathfinder.DANGER_RADIUS = Tuning.d("path.mob.radius", 5);
            FlightPathfinder.DANGER_COST = Tuning.d("path.mob.cost", 12);
        }
        catch (Throwable t) {
            FlightPathfinder.DANGERS = new double[0];
        }
        search = new FlightPathfinder(mc.level, start, goalNode, targetX.get() + 0.5, targetZ.get() + 0.5, desiredY, yWeight,
            effectiveRadius(), waitForChunks.get(), bottom, top, maxNodes.get(), wallAvoidance.get(), allowLowGaps.get(),
            mineModeTicks > 0 ? mineCost.get() : 0, noMine).lavaOk(lavaOkNow);
    }

    /** The block the player's feet are in, or the nearest open one around it. */
    private BlockPos startBlock() {
        Vec3 p = mc.player.position();
        BlockPos b = BlockPos.containing(p.x, p.y + 0.05, p.z);
        if (FlightPathfinder.open(mc.level, b.getX(), b.getY(), b.getZ())) return b;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos q : BlockPos.betweenClosed(b.offset(-1, -1, -1), b.offset(1, 1, 1))) {
            if (!FlightPathfinder.open(mc.level, q.getX(), q.getY(), q.getZ())) continue;
            double d = Vec3.atBottomCenterOf(q).distanceToSqr(p);
            if (d < bestD) {
                bestD = d;
                best = q.immutable();
            }
        }
        return best != null ? best : b;
    }

    private void searchDone(FlightPathfinder.Status s) {
        FlightPathfinder done = search;
        search = null;
        LOG.info("[VG] search {} expanded={} pathLen={} from=({})", s, done.expanded(), done.path().size(), v(mc.player.position()));

        if (tunneling) return; // a search that was still running when we started tunnelling
        List<Vec3> ext = extendPath;
        extendPath = null;
        if (ext != null && (ext != path || s == FlightPathfinder.Status.FAILED || done.path().size() < 2
            || (s == FlightPathfinder.Status.PARTIAL && !done.endsAtFrontier() && done.bestGain() < Tuning.d("trapped.gain", 8)))) {
            if (ext == path && pathIdx >= path.size() - 6) replanNow = true; // nearly at the end and nothing beyond it: replan from here
            return;
        }
        boolean nowhere = s == FlightPathfinder.Status.FAILED || done.path().size() < 2
            || (s == FlightPathfinder.Status.PARTIAL && !done.endsAtFrontier() && done.bestGain() < Tuning.d("trapped.gain", 8));
        trappedSearches = nowhere ? trappedSearches + 1 : 0;
        if (nowhere && trappedSearches >= Tuning.i("trapped.searches", 2) && mineThrough.get() && startTunnel()) return;
        if (s == FlightPathfinder.Status.FAILED || done.path().size() < 2) {
            if (mineThrough.get() && mineModeTicks == 0) {
                startDigging("No way around - digging through.");
                return;
            }
            failures++;
            if (failures >= 8) {
                failures = 0;
                m.warning("Can't find a way from here yet - retrying%s.", mineThrough.get() ? " with digging" : "");
                if (mineThrough.get()) startDigging("Digging allowed: no route found.");
                retryIn = 40;
                return;
            }
            if (failures == 1 || failures % 3 == 0) m.warning("No way forward found yet (tried %d blocks), trying again...", done.expanded());
            retryIn = 20;
            replanNow = true;
            return;
        }

        // Just dug a tunnel to its end and the best route from here leads back the way we came (the end is still inside the
        // rock, so "closest reachable point" is somewhere behind us): don't walk the whole tunnel back - dig on. (40
        // blocks east in 33 s, then two minutes of walking back and forth in it.)
        if (mc.player.tickCount - tunnelEndAge < Tuning.i("tunnel.end.window", 200) && mineThrough.get()) {
            BlockPos lastB = done.path().get(done.path().size() - 1);
            double gx = targetX.get() + 0.5, gz = targetZ.get() + 0.5;
            double dEnd = Math.hypot(gx - (lastB.getX() + 0.5), gz - (lastB.getZ() + 0.5));
            double dHere = Math.hypot(gx - mc.player.getX(), gz - mc.player.getZ());
            if (dEnd > dHere - Tuning.d("tunnel.end.gain", 16)) {
                LOG.info("[VG] the route from the tunnel's end gets no nearer ({} vs {} blocks from the destination) - tunnelling on", String.format("%.0f", dEnd), String.format("%.0f", dHere));
                if (startTunnel()) return;
            }
        }
        failures = 0;
        ticksSinceSearch = 0;
        List<Vec3> oldPath = path;
        int oldIdx = pathIdx;
        ArrayList<Vec3> pts = new ArrayList<>(done.path().size());
        for (BlockPos b : done.path()) pts.add(new Vec3(b.getX() + 0.5, b.getY() + NODE_Y, b.getZ() + 0.5));
        if (plannedMine.size() > 20000) plannedMine.clear();
        for (BlockPos b : done.path()) if (done.plannedMine(b)) plannedMine.add(b.asLong());
        boolean extended = false;
        if (ext != null && extendIdx < oldPath.size()) {
            int from = Math.max(0, oldIdx - 8);
            ArrayList<Vec3> joined = new ArrayList<>(oldPath.subList(from, Math.max(from, extendIdx)));
            int keep = joined.size();
            // the new piece starts on the node the kept piece ends with: one node twice made "the next node" the spot we
            // were already on - nothing to fly to, and no stuck alarm either (10 s hover)
            while (!joined.isEmpty() && !pts.isEmpty() && joined.get(joined.size() - 1).distanceTo(pts.get(0)) < 0.01) pts.remove(0);
            joined.addAll(pts);
            pts = joined;
            extended = true;
            LOG.info("[VG] path extended: kept {} + {} new nodes", keep, pts.size() - keep);
        }
        path = pts;
        pathReachesGoal = s == FlightPathfinder.Status.FOUND;
        pathEndsAtFrontier = done.endsAtFrontier();
        pathIdx = 0;
        pathIdx = nearestIndex(mc.player.position(), 0, extended ? Math.min(path.size(), 56) : path.size());
        noCarrotTicks = 0;
        if (!extended && !oldPath.isEmpty() && oldPath.size() - oldIdx > 12 && path.size() - pathIdx > 12) {
            // compare where the two paths are ~12 nodes ahead
            Vec3 newAhead = path.get(Math.min(path.size() - 1, pathIdx + 12));
            double best = Double.MAX_VALUE;
            for (int i = oldIdx; i < Math.min(oldPath.size(), oldIdx + 40); i++) best = Math.min(best, oldPath.get(i).distanceTo(newAhead));
            if (best > Tuning.d("route.change.dist", 4)) {
                Vec3 oldAhead = oldPath.get(Math.min(oldPath.size() - 1, oldIdx + 12));
                LOG.info("[VG] ROUTE CHANGE at ({}): was heading to ({}), now ({}) - {} apart; search {} {} nodes",
                    v(mc.player.position()), v(oldAhead), v(newAhead), String.format("%.1f", best), s, done.expanded());
            }
        }
    }

    // ------------------------------------------------------------------ steering

    @Override
    public void move(PlayerMoveEvent event) {
        if (event.type == MoverType.SELF && mc.player != null && groundNudge != null && !mc.player.isFallFlying()) {
            ((IVec3) event.movement).meteor$set(groundNudge.x, event.movement.y, groundNudge.z);
            groundNudge = null;
            return;
        }
        if (event.type != MoverType.SELF || mc.player == null || !mc.player.isFallFlying() || vf == null || !vf.isActive()) return;
        // The elytra is open on our side but the server hasn't confirmed it (just opened, or the server closed it with a
        // pull-back): NEVER let VolytraFly's own move through here - that's its full speed from a standstill, the server
        // rejects it, stays where it was (on the ground) and refuses the elytra. Just sink until it's really open.
        boolean confirmed = srvGlide == 1 || !srvSeenTrue || !Tuning.b("glide.wait.confirm", true);
        if (phase == Phase.TakeOff || !confirmed) {
            // (hover: sinking here put us back on the ground after a jump take-off - and the server closes the elytra of
            // anyone touching the ground)
            Vec3 sinkMove = new Vec3(0, -Tuning.d("glide.wait.sink", 0.0), 0);
            Vec3 adj = net.minecraft.world.entity.Entity.collideBoundingBox(mc.player, sinkMove, mc.player.getBoundingBox(), mc.level, java.util.List.of());
            ((IVec3) event.movement).meteor$set(0, adj.y, 0);
            dbgOurMove = new Vec3(0, adj.y, 0);
            lastCmd = dbgOurMove;
            lastCmdSpeed = Math.abs(adj.y);
            curSpeed = 0;
            curV = 0;
            lastMovePos = null;
            lastMoveEventAge = mc.player.tickCount;
            unconfirmedTicks++;
            mc.player.setDeltaMovement(0, 0, 0);
            history.addLast("t" + mc.player.tickCount + " pos(" + v(mc.player.position()) + ") waiting for the server to open the elytra (srv=" + srvGlide + ")");
            while (history.size() > 40) history.pollFirst();
            return;
        }
        if (lastMoveEventAge != mc.player.tickCount - 1) {
            lastCmd = new Vec3(event.movement.x, event.movement.y, event.movement.z);
            if (lastCmd.length() > 1.0) lastCmd = lastCmd.scale(1.0 / lastCmd.length());
            lastCmdSpeed = lastCmd.length();
            lastMovePos = null;
            stallTicks = 0;
        }
        lastMoveEventAge = mc.player.tickCount;
        dbgVfMove = new Vec3(event.movement.x, event.movement.y, event.movement.z);
        double sink = keepVfSink.get() && dbgVfMove.y < 0 && dbgVfMove.y > -Tuning.d("sink.max", 0.5) ? dbgVfMove.y : 0;
        sink = Math.min(sink, -Tuning.d("sink.min", 0)); // optional forced minimum sink // VolytraFly's own glide sink
        if (setbackTicks > 0) {
            ((IVec3) event.movement).meteor$set(0, sink, 0);
            dbgOurMove = new Vec3(0, sink, 0);
            lastCmdSpeed = Math.abs(sink);
            lastCmd = new Vec3(0, sink, 0);
            history.addLast("t" + mc.player.tickCount + " pos(" + v(mc.player.position()) + ") setback pause" + (mc.player.horizontalCollision ? " HC" : ""));
            while (history.size() > 40) history.pollFirst();
            return;
        }

        Vec3 pos = mc.player.position();
        Vec3 mv = event.movement;

        // pushing but hardly moving (caught on a block corner): line up on the block grid and go one axis at a time
        if (lastMovePos != null && lastWant.length() > 0.3) {
            if (pos.distanceTo(lastMovePos) < 0.3 * lastWant.length()) {
                if (++stallTicks >= Tuning.i("stall.ticks", 4)) {
                    stallTicks = 0;
                    if (creepTicks == 0) {
                        LOG.info("[VG] caught on something at ({}) - lining up on the grid", v(pos));
                        snapshot("caught on something (pushing, not moving)");
                    }
                    creepTicks = Tuning.i("creep.ticks", 60);
                }
            }
            else stallTicks = 0;
        }

        // top speed: VolytraFly's horizontal speed (never below its minimum), max-speed, slower after setbacks
        double vMin = Math.max(0.5, vf.startSpeed.get());
        double vTop = Math.max(vMin, vf.horizontalSpeed.get());
        if (maxSpeed.get() > 0) vTop = Math.min(vTop, maxSpeed.get());
        vTop = Math.min(vTop, setbackCap);
        if (pairedTicks) vTop = Math.min(vTop, Tuning.d("paired.speed.cap", 1.5));
        double capT = Tuning.d("speed.cap", 0); // live experiment: hard cap (blocks/tick), 0 = none
        if (capT > 0) vTop = Math.min(vTop, capT);
        double sH = Mth.clamp(curSpeed, Math.min(Math.min(vMin, vTop), Tuning.d("speed.turn.min", 0.8)), vTop); // creep / landing / digging use this as a cap only
        double vTopV = Math.min(Math.max(MIN_V, vf.verticalSpeed.get()), setbackCapV);
        if (pairedTicks) vTopV = Math.min(vTopV, Tuning.d("paired.speed.cap", 1.5));
        double sV = Mth.clamp(curV, Math.min(MIN_V, vTopV), vTopV);

        Vec3 want;
        double creepSpeed = Math.min(sH, Tuning.d("creep.speed", 0.6));
        if (mineTarget != null || digOutTarget != null || creepTicks > 0 || mending) {
            curSpeed = Math.min(curSpeed, vMin);
            curV = Math.min(curV, MIN_V);
        }
        if (phase == Phase.Landing) curSpeed = Math.min(curSpeed, vMin);
        boolean escaping = mc.player.isInLava() && !lavaOkNow;
        if (escaping) {
            if (phase == Phase.Landing) {
                phase = Phase.Flying;
                replanNow = true;
            }
            double es = Tuning.d("lava.escape.speed", 0.8);
            Vec3 exit = lavaExit(pos);
            Vec3 up = new Vec3(0, es, 0);
            if (exit != null) {
                Vec3 d = exit.subtract(pos);
                Vec3 direct = d.length() > es ? d.scale(es / d.length()) : d;
                if (clearSolid(pos, pos.add(direct))) want = direct;
                else if (d.y > 0.2 && clearSolid(pos, pos.add(up))) want = up;
                else {
                    want = Vec3.ZERO;
                    double[] c = {d.x, d.y, d.z};
                    for (int a = 0; a < 3 && want == Vec3.ZERO; a++) {
                        double st = Mth.clamp(c[a], -es, es);
                        if (Math.abs(st) < 1e-3) continue;
                        Vec3 stepV = a == 0 ? new Vec3(st, 0, 0) : a == 1 ? new Vec3(0, st, 0) : new Vec3(0, 0, st);
                        if (clearSolid(pos, pos.add(stepV))) want = stepV;
                    }
                }
            }
            else want = clearSolid(pos, pos.add(up)) ? up : Vec3.ZERO;
        }
        else if (phase == Phase.Landing) {
            Vec3 spot = goalNode != null ? Vec3.atBottomCenterOf(goalNode) : (path.isEmpty() ? pos : path.getLast());
            sV = rampV(Math.abs(pos.y - spot.y), vTopV);
            want = landingMove(pos, sV);
        }
        else if (mending) {
            want = Vec3.ZERO;
            if (mendBelow > 3) {
                Vec3 down = new Vec3(0, -Math.min(sV, Math.max(0.2, (mendBelow - 2.5) * 0.4)), 0);
                if (clearLine(pos, pos.add(down), -0.01)) want = down;
            }
        }
        else if (digOutTarget != null) want = Vec3.ZERO;
        else if (mineTarget != null) want = pos.distanceTo(mineFrom) > 0.02 ? creep(pos, mineFrom, creepSpeed) : Vec3.ZERO;
        else if (digHold != null) want = pos.distanceTo(digHold) > 0.02 ? creep(pos, digHold, creepSpeed) : Vec3.ZERO;
        else if (creepTicks > 0) want = creepAlong(pos, creepSpeed);
        else {
            Vec3 carrot = pickCarrot(pos);
            dbgCarrot = carrot;
            if (carrot != null) {
                // brake for what's ahead: clear straight way = distance to the carrot + however far the path keeps going
                // the same way past it (ends at a turn, a wall in the way, or the end of the path)
                double toCarrot = carrot.distanceTo(pos);
                double ahead = toCarrot + straightPast(carrot.subtract(pos));
                double turnMin = Math.min(Math.min(vMin, vTop), Tuning.d("speed.turn.min", 0.8));
                double decelPlan = Tuning.d("decel.plan", 0.15);
                double target = Math.min(vTop, Math.min(turnMin + braking.get() * ahead, Math.sqrt(turnMin * turnMin + 2 * decelPlan * ahead)));
                double vfNow = Math.hypot(dbgVfMove.x, dbgVfMove.z); // what VolytraFly's own ramp is giving this tick
                if (followVfRamp.get() && vfNow > 1e-3) target = Math.min(target, Math.max(Math.min(vMin, vTop), vfNow));
                curSpeed = curSpeed < target ? Math.min(target, Math.max(curSpeed, turnMin) + acceleration.get()) : target; // brake at once
                sH = curSpeed;
                // up/down: same idea, on the vertical share of the straight way ahead
                double vShare = toCarrot > 1e-4 ? Math.abs(carrot.y - pos.y) / toCarrot : 0;
                sV = rampV(ahead * vShare, vTopV);
                dbgTargetSpeed = target;
                dbgClearAhead = ahead;
            }
            else {
                curSpeed = Math.min(curSpeed, vMin);
                curV = Math.min(curV, MIN_V);
            }
            if (carrot == null) {
                boolean atFrontierEnd = pathEndsAtFrontier && !path.isEmpty() && pos.distanceTo(path.getLast()) < 3;
                if (!atFrontierEnd && noCarrotTicks > 3) want = creepAlong(pos, creepSpeed); // nothing in a straight line: block by block
                else want = Vec3.ZERO;
                if (atFrontierEnd && mc.player.tickCount - waitMsgAge > 200) {
                    waitMsgAge = mc.player.tickCount;
                    m.info("Waiting for chunks ahead to load...");
                }
            }
            else if (carrotTight) want = creepAlong(pos, Math.min(sH, 1.0)); // tight spot: on the grid, one axis at a time
            else {
                Vec3 d = carrot.subtract(pos);
                double dh = Math.hypot(d.x, d.z), dy = Math.abs(d.y);
                double t = 1;
                if (dh > 1e-4) t = Math.min(t, sH / dh);
                if (dy > 1e-4) t = Math.min(t, sV / dy);
                want = d.scale(t);
            }
        }

        if (Math.hypot(want.x, want.z) > 0.05) {
            travelYaw = (float) Math.toDegrees(Math.atan2(-want.x, want.z));
            if (rotate.get() == Rotate.Camera) mc.player.setYRot(travelYaw);
        }
        if (!escaping && sink < 0 && Math.abs(want.y) < -sink) want = new Vec3(want.x, sink, want.z); // level / hovering: sink like VolytraFly
        // ...but never sink towards lava, and don't sit right on top of it: hovering at the lava sea's surface (waiting for
        // a route) sank the bot into it - 6 hp in 2 s. Keep 1.5 blocks of air over lava while hovering/slow.
        // (only when really hovering - applied to slow descents too, it fought a route that had to go down past a lava
        // pocket and the bot bobbed on the spot)
        if (!lavaOkNow && Math.abs(want.y) <= 0.05 && Math.hypot(want.x, want.z) < 0.05) {
            AABB under = mc.player.getBoundingBox();
            boolean lavaUnder = false;
            BlockPos.MutableBlockPos lq = new BlockPos.MutableBlockPos();
            for (int lx = Mth.floor(under.minX); lx <= Mth.floor(under.maxX) && !lavaUnder; lx++) {
                for (int lz = Mth.floor(under.minZ); lz <= Mth.floor(under.maxZ) && !lavaUnder; lz++) {
                    for (int ly = Mth.floor(under.minY); ly >= Mth.floor(under.minY - Tuning.d("hover.lava.clear", 1.5)); ly--) {
                        lq.set(lx, ly, lz);
                        BlockState ls = mc.level.getBlockState(lq);
                        if (ls.getFluidState().is(FluidTags.LAVA)) {
                            lavaUnder = true;
                            break;
                        }
                        if (!ls.getCollisionShape(mc.level, lq).isEmpty()) break;
                    }
                }
            }
            if (lavaUnder) want = new Vec3(want.x, Tuning.d("hover.lava.rise", 0.15), want.z);
        }
        double len = want.length();
        // the server rejects a move whose speed rose too much over the previous one, and the faster we are the smaller
        // the rise it accepts (it looks like a limit on the rise of speed SQUARED): quick at low speed, gentle when fast
        // measured on 6b6t: below ~3 blocks/tick any speed-up is accepted; above it only small steps (~0.1/tick, what
        // VolytraFly's own ramp does) - bigger ones get set back
        double ps = pairedTicks ? 0.5 : 1.0; // packets going out in pairs: every per-tick change counts double on the server
        double freeSpeed = Tuning.d("accel.free.speed", 2.95), accHigh = Tuning.d("accel.high", 0.1) * ps;
        double lim = lastCmdSpeed < freeSpeed
            ? Math.min(lastCmdSpeed + Tuning.d("accel.max", 0.1) * ps, Math.max(freeSpeed, lastCmdSpeed + accHigh))
            : lastCmdSpeed + accHigh;
        if (slowAfterSetback > 0) {
            slowAfterSetback--;
            lim = Math.min(lim, Tuning.d("setback.slow.speed", 1.5));
        }
        lim = Math.max(lim, Tuning.d("accel.floor", 0.2));
        if (len > lim && len > 1e-6) want = want.scale(lim / len);
        // the same limit on the HORIZONTAL speed alone: levelling out of a climb kept the total speed steady while the
        // horizontal part jumped 2.84 -> 3.16 in one tick, and the server pulled us back
        double hPrev = Math.hypot(lastCmd.x, lastCmd.z), hWantNow = Math.hypot(want.x, want.z);
        double limH = hPrev < freeSpeed
            ? Math.min(hPrev + Tuning.d("accel.max", 0.1) * ps, Math.max(freeSpeed, hPrev + accHigh))
            : hPrev + accHigh;
        limH = Math.max(limH, Tuning.d("accel.floor", 0.2));
        if (hWantNow > limH && hWantNow > 1e-6 && Tuning.b("accel.horizontal", true)) want = new Vec3(want.x * limH / hWantNow, want.y, want.z * limH / hWantNow);
        double dvMax = Tuning.d("dv.max", 0.2) * ps;
        Vec3 dv = want.subtract(lastCmd);
        if (!escaping && dvMax > 0 && dv.length() > dvMax && lastCmd.length() > Tuning.d("dv.free.below", 0.25)) { // crawling: free to line up
            Vec3 limited = lastCmd.add(dv.scale(dvMax / dv.length()));
            // if the smooth version would run into something, brake instead (straight towards stopping)
            if (!clearLine(pos, pos.add(limited), -0.01)) {
                double ls = lastCmd.length(), dec = Math.min(dvMax, Tuning.d("decel.max", 0.2));
                limited = ls > dec ? lastCmd.scale((ls - dec) / ls) : Vec3.ZERO;
            }
            want = limited;
            dvLimited++;
        }
        double hNow = Math.hypot(want.x, want.z), hLast = Math.hypot(lastCmd.x, lastCmd.z);
        double maxDrop = Tuning.d("decel.max", 0.2) * ps;
        if (!escaping && hLast - hNow > maxDrop && hLast > 1e-6) {
            double hNew = hLast - maxDrop;
            boolean reversing = want.x * lastCmd.x + want.z * lastCmd.z < 0;
            double ux = hNow > 1e-4 && !reversing ? want.x / hNow : lastCmd.x / hLast, uz = hNow > 1e-4 && !reversing ? want.z / hNow : lastCmd.z / hLast;
            Vec3 cand = new Vec3(ux * hNew, want.y, uz * hNew);
            if (clearLine(pos, pos.add(cand), -0.01)) want = cand;
            else {
                // keeping speed would hit something: try the old direction, else accept the hit (logged)
                Vec3 old = new Vec3(lastCmd.x / hLast * hNew, want.y, lastCmd.z / hLast * hNew);
                if (clearLine(pos, pos.add(old), -0.01)) want = old;
                else hardBrakes++;
            }
        }
        if (Tuning.b("collide.resolve", true) && want.lengthSqr() > 1e-10) {
            Vec3 adj = net.minecraft.world.entity.Entity.collideBoundingBox(mc.player, want, mc.player.getBoundingBox(), mc.level, java.util.List.of());
            if (Math.abs(adj.x - want.x) > 1e-6 || Math.abs(adj.z - want.z) > 1e-6) {
                wallClips++;
                double lost = Math.hypot(want.x, want.z) - Math.hypot(adj.x, adj.z);
                if (lost > 0.3) LOG.info("[VG] clip avoided at ({}): wanted ({}) -> ({}), {} b/t lost", v(pos), v(want), v(adj), String.format("%.2f", lost));
                // stop a hair short so the game's own collision pass doesn't flag a hit either
                // (a floor in the way of a descent: stop short of it, don't touch down - see NODE_Y)
                double fy = adj.y > want.y + 1e-6 && want.y < 0 && phase != Phase.Landing ? adj.y + Tuning.d("floor.gap", 0.12) : adj.y;
                want = new Vec3(adj.x * 0.98, fy, adj.z * 0.98);
                creepTicks = Math.max(creepTicks, 10); // we were about to hit something: line up on the path
            }
            else if (Math.abs(adj.y - want.y) > 1e-6) {
                double fy = adj.y > want.y && want.y < 0 && phase != Phase.Landing ? adj.y + Tuning.d("floor.gap", 0.12) : adj.y;
                want = new Vec3(want.x, fy, want.z);
            }
        }
        // The line to the carrot was checked against lava - the move that the speed / turn limits and the collision pass
        // made of it was not (clipped a lava block in a wall). Last check: don't END a move in lava.
        if (!lavaOkNow && !escaping && want.lengthSqr() > 1e-8 && !mc.player.isInLava() && lavaTouches(mc.player.getBoundingBox().move(want))) {
            Vec3 pick = Vec3.ZERO;
            for (Vec3 alt : new Vec3[] {new Vec3(want.x * 0.5, Math.max(want.y, 0), want.z * 0.5), new Vec3(0, Math.max(want.y, 0), 0), new Vec3(0, 0.15, 0)}) {
                Vec3 adj2 = net.minecraft.world.entity.Entity.collideBoundingBox(mc.player, alt, mc.player.getBoundingBox(), mc.level, java.util.List.of());
                if (!lavaTouches(mc.player.getBoundingBox().move(adj2))) {
                    pick = adj2;
                    break;
                }
            }
            if (mc.player.tickCount - lavaLogAge > 20) {
                lavaLogAge = mc.player.tickCount;
                LOG.info("[VG] lava dodge at ({}): ({}) would have ended in lava -> ({})", v(pos), v(want), v(pick));
            }
            want = pick;
            creepTicks = Math.max(creepTicks, 10);
            replanNow = true;
        }
        lastCmdSpeed = want.length();
        lastCmd = want;
        // a tick run right after the previous one (catching up after a hitch): its packet lands on the server together
        // with the last one - move only as far as the time that really passed
        if (Tuning.b("tps.sync", false)) {
            double tps = meteordevelopment.meteorclient.utils.world.TickRate.INSTANCE.getTickRate();
            if (tps > 1 && tps < Tuning.d("tps.sync.below", 19.0)) want = want.scale(Math.max(Tuning.d("tps.sync.min", 0.5), tps / 20.0));
        }
        if (tickDtMs < Tuning.d("tick.catchup.ms", 30) && Tuning.b("tick.catchup.scale", false)) {
            want = want.scale(Math.max(Tuning.d("tick.catchup.min", 0.0), tickDtMs / 50.0));
            catchups++;
        }
        ((IVec3) event.movement).meteor$set(want.x, want.y, want.z);
        history.addLast(String.format("t%d pos(%.2f %.2f %.2f) want(%.2f %.4f %.2f) vf(%.2f %.4f %.2f) sH=%.2f sV=%.2f%s%s%s",
            mc.player.tickCount, pos.x, pos.y, pos.z, want.x, want.y, want.z, dbgVfMove.x, dbgVfMove.y, dbgVfMove.z, sH, sV,
            creepTicks > 0 ? " creep" : "", carrotTight ? " tight" : "", mineTarget != null ? " mine" : "")
            + (mc.player.horizontalCollision ? " HC" : "") + (mc.player.verticalCollision ? " VC" : "") + (setbackTicks > 0 ? " sb" : ""));
        while (history.size() > 40) history.pollFirst();
        lastMovePos = pos;
        lastWant = want;
        dbgOurMove = want;
        dbgSH = sH;
    }

    /** Vertical speed for this tick: speeds up by acceleration, brakes at once to what the vertical way ahead allows. */
    private double rampV(double verticalAhead, double vTopV) {
        double lo = Math.min(MIN_V, vTopV);
        double target = Math.min(vTopV, lo + braking.get() * verticalAhead);
        curV = curV < target ? Math.min(target, Math.max(curV, lo) + acceleration.get()) : target;
        return Math.max(curV, lo);
    }

    /** How far the path keeps going roughly in direction dir after the carrot (up to 160 blocks). */
    private double straightPast(Vec3 dir) {
        if (carrotIdx < 0 || dir.lengthSqr() < 1e-6) return 0;
        Vec3 u = dir.normalize();
        double run = 0;
        Vec3 from = path.get(Math.min(carrotIdx, path.size() - 1));
        // compare against the overall heading from the carrot (grid paths zig-zag a bit on diagonals)
        for (int i = carrotIdx + 1; i < path.size() && run < 160; i++) {
            Vec3 d = path.get(i).subtract(from);
            double len = d.length();
            if (len < 1e-6) continue;
            if (d.scale(1 / len).dot(u) < 0.94) break; // turns more than ~20 degrees
            run = len;
        }
        return run;
    }

    /** Follow the path node by node: onto the nearest node's centre, then to the next one, one axis at a time. */
    private Vec3 creepAlong(Vec3 pos, double speed) {
        if (path.isEmpty()) return Vec3.ZERO;
        pathIdx = nearestIndex(pos, Math.max(0, pathIdx - 16), Math.min(path.size(), pathIdx + 96));
        Vec3 node = path.get(pathIdx);
        if (pathIdx + 1 >= path.size()) return creep(pos, node, speed);
        int ni = pathIdx + 1;
        while (ni + 1 < path.size() && path.get(ni).distanceTo(node) < 0.05) ni++; // the same node twice: skip
        Vec3 next = path.get(ni);
        // already on the way from this node to the next (short steps may not get past halfway in one go): keep going
        if (pos.distanceTo(node) <= 0.08 || segDist(pos, node, next) <= 0.08) return creep(pos, next, speed);
        if (pathIdx > 0 && segDist(pos, path.get(pathIdx - 1), node) <= 0.08) return creep(pos, node, speed);
        return creep(pos, node, speed);
    }

    private static double segDist(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double l2 = ab.lengthSqr();
        if (l2 < 1e-9) return p.distanceTo(a);
        double t = Mth.clamp(p.subtract(a).dot(ab) / l2, 0, 1);
        return p.distanceTo(a.add(ab.scale(t)));
    }

    /** A step towards target that doesn't touch anything: straight if clear, else along one axis (biggest first). */
    private Vec3 creep(Vec3 pos, Vec3 target, double speed) {
        Vec3 d = target.subtract(pos);
        double len = d.length();
        if (len < 1e-4) return Vec3.ZERO;
        Vec3 direct = len > speed ? d.scale(speed / len) : d;
        if (clearLine(pos, pos.add(direct), -0.01)) return direct;

        double[] c = {d.x, d.y, d.z};
        Integer[] order = {0, 1, 2};
        java.util.Arrays.sort(order, (a, b) -> Double.compare(Math.abs(c[b]), Math.abs(c[a])));
        for (double scale : new double[] {1, 0.25}) {
            for (int a : order) {
                double st = Mth.clamp(c[a], -speed, speed) * scale;
                if (Math.abs(st) < 1e-4) continue;
                Vec3 mv = a == 0 ? new Vec3(st, 0, 0) : a == 1 ? new Vec3(0, st, 0) : new Vec3(0, 0, st);
                if (clearLine(pos, pos.add(mv), -0.01)) return mv;
            }
        }
        AABB here = new AABB(pos.x - 0.3, pos.y, pos.z - 0.3, pos.x + 0.3, pos.y + 0.6, pos.z + 0.3);
        Vec3 best = Vec3.ZERO;
        double bestD = Double.MAX_VALUE;
        for (Vec3 ax : AXES6) {
            Vec3 mv = ax.scale(0.25);
            if (!clearBox(here.move(mv).deflate(0.01))) continue;
            double dd = pos.add(mv).distanceTo(target);
            if (dd >= len - 0.02) continue; // only steps that get closer
            if (lastCmd.lengthSqr() > 1e-4 && mv.dot(lastCmd) < -0.9 * mv.length() * lastCmd.length()) continue; // never straight back
            if (dd < bestD) {
                bestD = dd;
                best = mv;
            }
        }
        if (best != Vec3.ZERO) creepEscapes++;
        return best;
    }

    private void startDigging(String msg) {
        mineModeTicks = Tuning.i("dig.mode.ticks", 600); // then plain flying again
        retryIn = 0;
        replanNow = true;
        m.warning(msg);
        LOG.info("[VG] dig mode on at ({})", v(mc.player.position()));
    }

    /**
     * Dump what's around us to latest.log ("[VG] snap" lines): state, the last 40 ticks of movement, the next path
     * points and a block map 7 wide x 7 deep, from 2 below the feet to 3 above. At most one every 2 s.
     */
    private void snapshot(String why) {
        if (mc.player == null || mc.level == null || mc.player.tickCount - snapAge < Tuning.i("snapshot.interval", 40)) return;
        snapAge = mc.player.tickCount;
        Vec3 pos = mc.player.position();
        BlockPos f = BlockPos.containing(pos.x, pos.y + 0.05, pos.z);
        StringBuilder sb = new StringBuilder();
        sb.append("\n[VG] snap ===== ").append(why).append(" =====");
        sb.append(String.format("\n[VG] snap pos(%.2f %.2f %.2f) feetBlock=%s vel(%s) gliding=%s onGround=%s hCollide=%s vCollide=%s phase=%s",
            pos.x, pos.y, pos.z, f.toShortString(), v(mc.player.getDeltaMovement()), mc.player.isFallFlying(), mc.player.onGround(),
            mc.player.horizontalCollision, mc.player.verticalCollision, phase));
        sb.append(String.format("\n[VG] snap speed=%.2f vSpeed=%.2f cap=%.2f capV=%.2f vfH=%.2f vfStart=%.2f vfV=%.2f creep=%d stall=%d tight=%s noCarrot=%d setbackTicks=%d dig=%d mine=%s digOut=%s mending=%s",
            curSpeed, curV, setbackCap, setbackCapV, vf.horizontalSpeed.get(), vf.startSpeed.get(), vf.verticalSpeed.get(),
            creepTicks, stallTicks, carrotTight, noCarrotTicks, setbackTicks, mineModeTicks, mineTarget, digOutTarget, mending));
        sb.append(String.format("\n[VG] snap carrot=%s path=%d/%d reachesGoal=%s frontier=%s search=%s goal=%s",
            dbgCarrot == null ? "none" : v(dbgCarrot), pathIdx, path.size(), pathReachesGoal, pathEndsAtFrontier, search != null, goalNode));
        StringBuilder next = new StringBuilder();
        java.util.HashSet<Long> pathCells = new java.util.HashSet<>();
        for (int i = Math.max(0, pathIdx - 2); i < Math.min(path.size(), pathIdx + 12); i++) {
            BlockPos b = BlockPos.containing(path.get(i));
            pathCells.add(b.asLong());
            next.append(i == pathIdx ? " [" : " ").append(b.getX() - f.getX()).append(',').append(b.getY() - f.getY()).append(',').append(b.getZ() - f.getZ()).append(i == pathIdx ? "]" : "");
        }
        sb.append("\n[VG] snap path near (relative to feet block, [current]):").append(next);
        sb.append("\n[VG] snap last ticks:");
        for (String h : history) sb.append("\n[VG] snap   ").append(h);
        // block map: rows = z (north at top), columns = x (west at left)
        sb.append("\n[VG] snap blocks (x -3..3 across, z -3..3 down) @=you o=path X=dig #=solid _=partial .=air ~=water L=lava P=portal F=fire W=web ?=unloaded");
        for (int dy = 3; dy >= -2; dy--) {
            sb.append("\n[VG] snap  y").append(dy >= 0 ? "+" : "").append(dy).append(" (").append(f.getY() + dy).append("):");
            for (int dz = -3; dz <= 3; dz++) {
                sb.append("\n[VG] snap     ");
                for (int dx = -3; dx <= 3; dx++) {
                    BlockPos b = f.offset(dx, dy, dz);
                    sb.append(snapChar(b, dx == 0 && dz == 0 && (dy == 0 || dy == 1 && !mc.player.isFallFlying()), pathCells.contains(b.asLong())));
                }
            }
        }
        LOG.info(sb.toString());
    }

    private char snapChar(BlockPos b, boolean you, boolean onPath) {
        if (!mc.level.getChunkSource().hasChunk(b.getX() >> 4, b.getZ() >> 4)) return '?';
        if (b.equals(mineTarget) || b.equals(digOutTarget)) return 'X';
        BlockState st = mc.level.getBlockState(b);
        if (st.getFluidState().is(FluidTags.LAVA)) return 'L';
        if (!st.getFluidState().isEmpty()) return '~';
        if (st.is(Blocks.NETHER_PORTAL)) return 'P';
        if (st.is(Blocks.FIRE) || st.is(Blocks.SOUL_FIRE)) return 'F';
        if (st.is(Blocks.COBWEB)) return 'W';
        net.minecraft.world.phys.shapes.VoxelShape shape = st.getCollisionShape(mc.level, b);
        if (!shape.isEmpty()) {
            if (you) return '!'; // we're inside a block?!
            AABB bb = shape.bounds();
            boolean full = bb.minX <= 0 && bb.minY <= 0 && bb.minZ <= 0 && bb.maxX >= 1 && bb.maxY >= 1 && bb.maxZ >= 1;
            return full ? '#' : '_';
        }
        if (you) return '@';
        return onPath ? 'o' : '.';
    }

    private void logout(String why) {
        if (mc.player == null) return;
        AutoReconnect ar = Modules.get().get(AutoReconnect.class);
        if (ar != null && ar.isActive()) ar.toggle();
        LOG.info("[VG] logging out: {}", why);
        mc.player.connection.handleDisconnect(new ClientboundDisconnectPacket(Component.literal("[Volytra Goto] " + why)));
    }

    /** Look through a few not-yet-checked chunks around us (4 per tick) for a Nether portal. */
    private void scanForPortals(Vec3 pos, boolean divert) {
        int pcx = Mth.floor(pos.x) >> 4, pcz = Mth.floor(pos.z) >> 4, r = Tuning.i("portal.scan.chunks", 6), budget = Tuning.i("portal.scan.budget", 6);
        for (int dx = -r; dx <= r && budget > 0; dx++) {
            for (int dz = -r; dz <= r && budget > 0; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
                if (scannedChunks.contains(key) || !mc.level.getChunkSource().hasChunk(cx, cz)) continue;
                scannedChunks.add(key);
                budget--;
                BlockPos portal = findPortal(mc.level.getChunk(cx, cz));
                if (portal != null) considerPortal(portal, divert);
                if (divertedToPortal) return;
            }
        }
    }

    private BlockPos findPortal(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection sec = sections[i];
            if (sec == null || sec.hasOnlyAir() || !sec.getStates().maybeHas(st -> st.is(Blocks.NETHER_PORTAL))) continue;
            int baseY = mc.level.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (sec.getBlockState(x, y, z).is(Blocks.NETHER_PORTAL)) {
                            return new BlockPos(chunk.getPos().getMinBlockX() + x, baseY + y, chunk.getPos().getMinBlockZ() + z);
                        }
                    }
                }
            }
        }
        return null;
    }

    private void considerPortal(BlockPos portal, boolean divert) {
        // bottom of the portal
        BlockPos.MutableBlockPos b = portal.mutable();
        while (mc.level.getBlockState(b.below()).is(Blocks.NETHER_PORTAL)) b.move(0, -1, 0);
        BlockPos bottom = b.immutable();
        for (BlockPos ig : ignoredPortals) if (ig.closerThan(bottom, 12)) return;
        if (flightStart != null && Math.hypot(bottom.getX() + 0.5 - flightStart.x, bottom.getZ() + 0.5 - flightStart.z) < portalIgnoreRadius.get()) {
            ignoredPortals.add(bottom);
            LOG.info("[VG] portal at {} - within {} blocks of where the flight started, ignored", bottom.toShortString(), portalIgnoreRadius.get());
            return;
        }
        if (!divert) {
            ignoredPortals.add(bottom);
            LOG.info("[VG] portal seen at {} ({} blocks away) - not stopping: portal-stop is off", bottom.toShortString(), String.format("%.0f", Math.sqrt(bottom.distSqr(mc.player.blockPosition()))));
            m.info("Passed a Nether portal at %d %d %d (portal-stop is off).", bottom.getX(), bottom.getY(), bottom.getZ());
            return;
        }

        // closest safe spot to stand on next to it, at about the portal's floor height
        BlockPos spot = null;
        search:
        for (int r = 1; r <= 5; r++) {
            for (int dy : new int[] {0, 1, -1}) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        int x = bottom.getX() + dx, y = bottom.getY() + dy, z = bottom.getZ() + dz;
                        if (!standable(x, y, z) || !safeFloor(new BlockPos(x, y - 1, z))) continue;
                        spot = new BlockPos(x, y, z);
                        break search;
                    }
                }
            }
        }
        if (spot == null) {
            ignoredPortals.add(bottom);
            LOG.info("[VG] portal at {} but nowhere safe to land next to it", bottom);
            return;
        }

        divertedToPortal = true;
        portalFound = bottom;
        fixedGoal = spot;
        targetX.set(spot.getX());
        targetZ.set(spot.getZ());
        arrive.set(Arrive.Land);
        resetRoute();
        m.warning("Found a Nether portal at %d %d %d - landing next to it (then logging out).", bottom.getX(), bottom.getY(), bottom.getZ());
    }

    /** Blocks between our feet and the first solid block below (up to max); -1 if nothing / lava first. */
    private int groundBelow(Vec3 pos, int max) {
        int fy = Mth.floor(pos.y);
        BlockPos.MutableBlockPos b = new BlockPos.MutableBlockPos();
        for (int i = 1; i <= max; i++) {
            b.set(Mth.floor(pos.x), fy - i, Mth.floor(pos.z));
            BlockState st = mc.level.getBlockState(b);
            if (st.getFluidState().is(FluidTags.LAVA) || st.is(Blocks.FIRE) || st.is(Blocks.SOUL_FIRE)) return -1; // orbs burn
            if (!st.getFluidState().isEmpty() || !st.getCollisionShape(mc.level, b).isEmpty()) return i - 1;
        }
        return -1;
    }

    /** Hover a few blocks over the ground and throw XP bottles straight down until the elytra is mended. */
    private void updateMend(Vec3 pos, boolean gliding) {
        if (mc.player.isUsingItem() || mc.options.keyUse.isDown()) return; // eating first (see mineTick)
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        FindItemResult xp = InvUtils.find(Items.EXPERIENCE_BOTTLE);
        boolean repaired = chest.is(Items.ELYTRA) && chest.isDamageableItem() && durability(chest) >= chest.getMaxDamage() * 0.95;
        // still wearing down (the XP going to other Mending gear): give up at min-durability so a spare goes on
        boolean worn = chest.is(Items.ELYTRA) && chest.isDamageableItem() && durability(chest) <= minDurability.get();
        if (repaired || worn || !chest.is(Items.ELYTRA) || !xp.found() || !gliding || ++mendTicks > 1200) {
            mending = false;
            mendCooldown = repaired ? 100 : 600;
            InvUtils.swapBack();
            replanNow = true;
            if (chest.is(Items.ELYTRA) && chest.isDamageableItem()) {
                if (repaired) m.info("Elytra mended (%d durability).", durability(chest));
                else m.warning("Stopped mending at %d durability (%s).", durability(chest), !xp.found() ? "out of XP bottles" : mendTicks > 1200 ? "took too long" : "not flying");
            }
            return;
        }

        mendBelow = groundBelow(pos, 48);
        if (mendBelow < 0) {
            // no ground under us (or lava): the orbs would land too far away - keep flying, try again in 10 s
            mending = false;
            mendCooldown = 200;
            InvUtils.swapBack();
            LOG.info("[VG] mend: no ground below ({}) - later", v(pos));
            return;
        }
        if (mendBelow > 4) return; // still going down (onMove)

        if (throwCooldown > 0) {
            throwCooldown--;
            return;
        }
        if (!xp.isHotbar()) {
            InvUtils.move().from(xp.slot()).toHotbar(8);
            throwCooldown = 4;
            return;
        }
        InvUtils.swap(xp.slot(), true);
        throwCooldown = 2;
        Rotations.rotate(mc.player.getYRot(), 90, 60, () -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
    }

    /**
     * On foot in a tunnel (the elytra closed in there): dig the next step 2 blocks high and walk into it, until the
     * tunnel path ends. Mining while standing is 5x faster than mining in the air. False = not handling it (take off
     * normally).
     */
    private boolean walkTunnel() {
        if (path.size() < 2) return false;
        Vec3 pos = mc.player.position();
        pathIdx = nearestIndex(pos, Math.max(0, pathIdx - 4), Math.min(path.size(), pathIdx + 8));
        if (pathIdx >= path.size() - 1) {
            if (tunneling) tunnelEndAge = mc.player.tickCount;
            tunneling = false;
            replanNow = true;
            return false;
        }
        Vec3 next = path.get(pathIdx + 1);
        if (next.y > path.get(pathIdx).y + 0.5) return false; // going up: not on foot
        if (next.y < path.get(pathIdx).y - 0.5 && Math.hypot(next.x - path.get(pathIdx).x, next.z - path.get(pathIdx).z) < 0.5) {
            // straight down: stand in the middle of the block, dig the one under our feet, drop, repeat
            Vec3 node = path.get(pathIdx);
            double cdx = node.x - pos.x, cdz = node.z - pos.z, cl = Math.hypot(cdx, cdz);
            if (cl > 0.12) {
                double sp = Math.min(0.15, cl);
                groundNudge = new Vec3(cdx / cl * sp, 0, cdz / cl * sp);
                return true;
            }
            BlockPos below = BlockPos.containing(pos.x, pos.y - 0.5, pos.z);
            BlockState bs = mc.level.getBlockState(below);
            if (!FlightPathfinder.blocks(mc.level, below, bs)) return true; // already open: we're about to drop
            if (pendingBreak.containsKey(below.asLong()) && mc.player.tickCount - pendingBreak.get(below.asLong()) < Tuning.i("dig.pending.ticks", 16)) return true;
            if (noMine.contains(below.asLong()) || !FlightPathfinder.minable(mc.level, below, bs)) {
                LOG.info("[VG] digging down: {} isn't safe - turning", below.toShortString());
                noMine.add(below.asLong());
                if (tunneling && startTunnel()) return true;
                tunneling = false;
                replanNow = true;
                return false;
            }
            dig(below);
            return true;
        }
        if (Math.hypot(next.x - pos.x, next.z - pos.z) < 0.15 && pathIdx + 2 < path.size()) next = path.get(pathIdx + 2);
        BlockPos here = BlockPos.containing(pos.x, pos.y + 0.2, pos.z);
        BlockPos step = BlockPos.containing(next.x, pos.y + 0.2, next.z);
        int confirm = Tuning.i("dig.confirm.ticks", 8);
        boolean crawling = mc.player.getBoundingBox().getYsize() < 1.0; // in a 1-high gap the game makes us crawl
        boolean waiting = false;
        // Crawling through a 1-high tunnel tops out at 2 blocks a second; walking a 2-high one does 4, and now that rock
        // breaks in one hit the second block per step costs one tick. So: stand up (open the block above us) and walk -
        // as long as the blocks above are safe to open.
        boolean standUp = false;
        if (crawling && !instantOff && Tuning.b("tunnel.stand", false)) { // off: tunnels are 1x1, crawled
            standUp = true;
            for (BlockPos c : new BlockPos[] {here.above(), step.above()}) {
                BlockState cs = mc.level.getBlockState(c);
                if (FlightPathfinder.blocks(mc.level, c, cs) && (noMine.contains(c.asLong()) || !FlightPathfinder.minable(mc.level, c, cs))) standUp = false;
            }
        }
        boolean low = crawling && !standUp;
        for (BlockPos c : low ? new BlockPos[] {step} : new BlockPos[] {here.above(), step.above(), step}) {
            BlockState st = mc.level.getBlockState(c);
            if (!FlightPathfinder.blocks(mc.level, c, st)) {
                if (brokeAge.containsKey(c.asLong()) && mc.player.tickCount - brokeAge.get(c.asLong()) < confirm) return true; // just broken: wait
                continue;
            }
            brokeAge.remove(c.asLong());
            if (pendingBreak.containsKey(c.asLong()) && mc.player.tickCount - pendingBreak.get(c.asLong()) < Tuning.i("dig.pending.ticks", 16)) {
                waiting = true; // break sent: the server will remove it shortly
                continue;
            }
            if (noMine.contains(c.asLong()) || !FlightPathfinder.minable(mc.level, c, st)) {
                LOG.info("[VG] tunnel on foot: {} isn't safe to dig - turning", c.toShortString());
                noMine.add(c.asLong());
                if (tunneling && startTunnel()) return true;
                tunneling = false;
                replanNow = true;
                return false;
            }
            dig(c);
            return true;
        }
        // The step in front is open (or its break is on its way to the server): use this tick to open the NEXT steps too,
        // as far as we can reach - so the walk never has to stand and wait for a block (it used to dig one step, wait
        // for the server, walk, repeat: 1.3 blocks a second through rock).
        int aheadSteps = Tuning.i("dig.ahead.steps", 5);
        if (aheadSteps > 1 && !mc.player.isUsingItem()) {
            Vec3 eye = mc.player.getEyePosition();
            ahead:
            for (int k = 2; k <= aheadSteps; k++) {
                if (pathIdx + k >= path.size()) break;
                Vec3 nk = path.get(pathIdx + k);
                if (Math.abs(nk.y - path.get(pathIdx).y) > 0.5) break; // the tunnel turns up or down there
                BlockPos sk = BlockPos.containing(nk.x, pos.y + 0.2, nk.z);
                for (BlockPos c : low ? new BlockPos[] {sk} : new BlockPos[] {sk.above(), sk}) {
                    BlockState st = mc.level.getBlockState(c);
                    if (!FlightPathfinder.blocks(mc.level, c, st)) continue;
                    if (pendingBreak.containsKey(c.asLong()) && mc.player.tickCount - pendingBreak.get(c.asLong()) < Tuning.i("dig.pending.ticks", 16)) continue;
                    if (eye.distanceToSqr(Vec3.atCenterOf(c)) > 20.25 || noMine.contains(c.asLong()) || !FlightPathfinder.minable(mc.level, c, st)) break ahead;
                    dig(c);
                    break ahead;
                }
            }
        }
        if (waiting) return true;
        double dx = next.x - pos.x, dz = next.z - pos.z, l = Math.hypot(dx, dz);
        if (l > 0.05) {
            // crawling goes at the Speed module's pace too (with Speed on, a crawl is as fast as a run)
            double sp = Math.min(crawling && !Tuning.b("walk.use.speed", true) ? Tuning.d("tunnel.crawl.speed", 0.1) : walkSpeed(), l);
            groundNudge = new Vec3(dx / l * sp, 0, dz / l * sp);
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        }
        if (mc.player.tickCount % 40 == 0) LOG.info("[VG] tunnel on foot: at node {}/{} pos=({})", pathIdx, path.size(), v(pos));
        return true;
    }

    /**
     * Trapped in a pocket: lay a straight 1x1 path from here towards the destination (the compass direction closest
     * to it first, then the others, then up/down), through rock that's safe to dig (never next to lava/water), until
     * it comes out into open space. The normal digging then follows it. False if no direction works.
     */
    private boolean startTunnel() {
        BlockPos start = startBlock();
        double tdx = targetX.get() + 0.5 - mc.player.getX(), tdz = targetZ.get() + 0.5 - mc.player.getZ();
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}, {0, 1, 0}};
        Integer[] order = {0, 1, 2, 3, 4, 5};
        java.util.Arrays.sort(order, (a, b) -> Double.compare(dirs[b][0] * tdx + dirs[b][2] * tdz - Math.abs(dirs[b][1]) * 1e6,
            dirs[a][0] * tdx + dirs[a][2] * tdz - Math.abs(dirs[a][1]) * 1e6));
        int maxLen = Tuning.i("tunnel.max", 40);
        List<Vec3> best = null;
        int bestDir = -1;
        for (int oi : order) {
            int[] d = dirs[oi];
            ArrayList<Vec3> pts = new ArrayList<>();
            pts.add(new Vec3(start.getX() + 0.5, start.getY() + NODE_Y, start.getZ() + 0.5));
            int rock = 0, airRun = 0;
            boolean ok = false;
            for (int i = 1; i <= maxLen; i++) {
                BlockPos c = start.offset(d[0] * i, d[1] * i, d[2] * i);
                if (c.getY() <= mc.level.getMinY() + 2 || c.getY() >= mc.level.getMaxY() - 2) break;
                if (!mc.level.getChunkSource().hasChunk(c.getX() >> 4, c.getZ() >> 4)) {
                    ok = rock > 0;
                    break;
                }
                BlockState st = mc.level.getBlockState(c);
                if (!st.getFluidState().isEmpty()) break; // lava / water in the way: not this direction
                if (d[1] == 0) {
                    BlockPos head = c.above();
                    BlockState hs = mc.level.getBlockState(head);
                    if (!hs.getFluidState().isEmpty()) break;
                    if (FlightPathfinder.blocks(mc.level, head, hs) && (noMine.contains(head.asLong()) || !FlightPathfinder.minable(mc.level, head, hs))) break;
                }
                if (FlightPathfinder.blocks(mc.level, c, st)) {
                    if (noMine.contains(c.asLong()) || !FlightPathfinder.minable(mc.level, c, st)) break;
                    rock++;
                    airRun = 0;
                }
                else if (FlightPathfinder.lavaAbove(mc.level, c.getX(), c.getY(), c.getZ(), 24)) break;
                else airRun++;
                pts.add(new Vec3(c.getX() + 0.5, c.getY() + NODE_Y, c.getZ() + 0.5));
                if (rock > 0 && airRun >= 3) {
                    ok = true; // through the rock and out the other side
                    break;
                }
                if (i == maxLen) ok = rock > 0;
            }
            if (ok && pts.size() > 3) {
                best = pts;
                bestDir = oi;
                break;
            }
        }
        if (best == null) {
            LOG.info("[VG] trapped at ({}) but no direction is safe to tunnel", v(mc.player.position()));
            return false;
        }
        path = best;
        for (Vec3 node : best) plannedMine.add(BlockPos.containing(node).asLong());
        pathIdx = 0;
        pathReachesGoal = false;
        pathEndsAtFrontier = false;
        noCarrotTicks = 0;
        tunneling = true;
        mineModeTicks = Math.max(mineModeTicks, 1200);
        search = null;
        ticksSinceSearch = 0;
        int[] d = dirs[bestDir];
        m.warning("No way out of here - tunnelling %d blocks %s.", best.size() - 1,
            d[1] > 0 ? "up" : d[1] < 0 ? "down" : d[0] > 0 ? "east" : d[0] < 0 ? "west" : d[2] > 0 ? "south" : "north");
        LOG.info("[VG] TUNNEL from {} dir=({},{},{}) len={}", start.toShortString(), d[0], d[1], d[2], best.size() - 1);
        return true;
    }

    private BlockPos pmTarget;
    private int pmStart;
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap pendingBreak = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(); // STOP sent, waiting for the server

    /**
     * One tick of mining a block with the best hotbar tool. Blocks that would break instantly (netherrack with a good
     * pickaxe while standing) are refused by 6b6t when broken in a single tick - those are mined by packets over a few
     * ticks instead, and we wait for the server to remove the block (no client-side guess, so no ghost blocks).
     */
    private void mineTick(BlockPos t) {
        // eating (Auto Gap / Auto Eat) comes first: switching to the pickaxe every tick cancels it - which is how the bot
        // burned from 18.8 to 7.7 hp in a lava pocket without the golden apple ever being eaten
        if (mc.player.isUsingItem() || mc.options.keyUse.isDown()) return;
        BlockState st = mc.level.getBlockState(t);
        FindItemResult tool = InvUtils.findFastestTool(st);
        if (tool.found() && tool.isHotbar()) InvUtils.swap(tool.slot(), false);
        float delta = st.getDestroyProgress(mc.player, mc.level, t);
        // standing on the ground: make sure the server knows it before we dig (see onSend)
        if (mc.player.onGround() && Tuning.b("dig.ground.truth", true)) groundTruthForDigging();
        boolean truth = mc.player.onGround() && !groundSpoofed() && Tuning.b("dig.ground.truth", true);
        if (delta < Tuning.d("dig.packet.above", 0.5)) {
            pmTarget = null;
            lookDo(t, () -> BlockUtils.breakBlock(t, true));
            return;
        }
        // breaks in one hit (netherrack with a good pickaxe) and the server knows we're standing: one packet, like vanilla
        if (instantOff && mc.player.tickCount - instantOffAge > 1200) instantOff = false;
        boolean instant = truth && delta >= 1.0f && !instantOff && Tuning.b("dig.instant", true);
        int need = instant ? 0 : Math.max(Tuning.i("dig.min.ticks", 4), (int) Math.ceil(1.0 / Math.max(delta, 0.01)) + 1);
        int retry = instant ? Tuning.i("dig.instant.retry", 8) : need + 20;
        BlockPos target = t.immutable();
        if (!target.equals(pmTarget) || mc.player.tickCount - pmStart > retry) {
            // (retries of the SAME block in a row, and only for a minute: four scattered retries over a whole flight -
            // most of them during a pull-back storm, when the server takes nothing - switched one-hit breaks off for good)
            if (instant && !target.equals(pmTarget)) instantFails = 0;
            if (instant && target.equals(pmTarget) && ++instantFails >= Tuning.i("dig.instant.fails", 4)) {
                instantOff = true;
                instantOffAge = mc.player.tickCount;
                instantFails = 0;
                LOG.info("[VG] the server isn't taking one-hit breaks ({} sent) - timed breaks for a minute", instantBreaks);
            }
            if (instant) {
                instantBreaks++;
                pendingBreak.put(target.asLong(), mc.player.tickCount);
                if (instantBreaks == 1 || instantBreaks % 200 == 0) LOG.info("[VG] one-hit breaks: {} sent, {} retried", instantBreaks, instantFails);
            }
            pmTarget = target;
            pmStart = mc.player.tickCount;
            lookDo(target, () -> {
                mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
                    net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, target, net.minecraft.core.Direction.UP));
                mc.player.swing(InteractionHand.MAIN_HAND);
            });
        }
        else if (!instant && mc.player.tickCount - pmStart == need) {
            lookDo(target, () -> {
                mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
                    net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, target, net.minecraft.core.Direction.UP));
                mc.player.swing(InteractionHand.MAIN_HAND);
            });
            pendingBreak.put(target.asLong(), mc.player.tickCount);
        }
    }

    /**
     * Look at the block and do it - except while gliding: the server's glide simulation flies where we look, and a look
     * at a wall next to us flies it into that wall (fly-into-wall damage while hovering to dig).
     */
    private void lookDo(BlockPos t, Runnable r) {
        if (mc.player.isFallFlying() && !Tuning.b("dig.rotate.gliding", false)) r.run();
        else Rotations.rotate(Rotations.getYaw(t), Rotations.getPitch(t), 50, r);
    }

    /** The nearest diggable block in the way between us and a spot (our body swept along the straight line to it). */
    private BlockPos blockerTowards(Vec3 to) {
        Vec3 pos = mc.player.position();
        AABB box = mc.player.getBoundingBox().expandTowards(to.subtract(pos)).inflate(0.05);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos b : BlockPos.betweenClosed(Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
            Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ))) {
            BlockState st = mc.level.getBlockState(b);
            if (!FlightPathfinder.blocks(mc.level, b, st) || !stuckDiggable(b) || noMine.contains(b.asLong())) continue;
            double d = Vec3.atCenterOf(b).distanceToSqr(pos);
            if (d < bestD) {
                bestD = d;
                best = b.immutable();
            }
        }
        return best;
    }

    /** Mine pos for one tick (best hotbar tool, look at it). False once it's taken 20 s (then it's left alone). */
    private boolean dig(BlockPos target) {
        if (mc.player.tickCount - lastBrokeAge < Tuning.i("dig.gap.ticks", 1)) return true;
        if (!target.equals(digging)) {
            digging = target.immutable();
            digTicks = 0;
            LOG.info("[VG] digging out {} ({}) at ({})", digging, mc.level.getBlockState(digging).getBlock(), v(mc.player.position()));
        }
        if (++digTicks > 400) {
            m.warning("Couldn't break %d %d %d in 20 seconds - leaving it.", target.getX(), target.getY(), target.getZ());
            noMine.add(target.asLong());
            digging = null;
            return false;
        }
        mineTick(digging);
        return true;
    }

    /**
     * Last resort: flying, should be moving, but hasn't got more than a block anywhere in 3 s - dig out whatever's
     * around us that's in the way, the block nearest to where we want to go first.
     */
    private void updateDigOut(Vec3 pos, boolean gliding) {
        if (digOutTarget != null && (mineTarget != null || mending || phase != Phase.Flying)) {
            digOutTarget = null; // the path digger has a block of its own: two targets at once cancel each other's progress
            stillTicks = 0;
            return;
        }
        if (digOutTarget != null) {
            BlockState st = mc.level.getBlockState(digOutTarget);
            if (!FlightPathfinder.blocks(mc.level, digOutTarget, st)) { // broken: try moving again
                digOutTarget = null;
                stillTicks = 20;
                replanNow = true;
            }
            else if (!dig(digOutTarget)) {
                digOutTarget = null;
                stillTicks = 20;
            }
            return;
        }

        boolean atFrontierEnd = pathEndsAtFrontier && !path.isEmpty() && pos.distanceTo(path.getLast()) < 4;
        boolean tryingToMove = dbgOurMove.length() > 0.05 || (!path.isEmpty() && noCarrotTicks > 3);
        if (!gliding || phase != Phase.Flying || mineTarget != null || mending || setbackTicks > 0 || path.isEmpty() || atFrontierEnd || !tryingToMove) {
            stillAnchor = pos;
            stillTicks = 0;
            return;
        }
        if (stillAnchor == null || pos.distanceTo(stillAnchor) > 1) {
            stillAnchor = pos;
            stillTicks = 0;
            return;
        }
        ++stillTicks;
        if (stillTicks == Tuning.i("still.creep.ticks", 20) && creepTicks == 0) {
            snapshot("not moving for 1 s while trying to");
            creepTicks = Tuning.i("creep.ticks", 60);
            replanNow = true;
        }
        if (stillTicks < Tuning.i("still.dig.ticks", 60) || !mineThrough.get()) return;
        stillTicks = 0;

        Vec3 toward = path.get(Math.min(path.size() - 1, pathIdx + 1));
        if (toward.distanceTo(pos) < 0.3) toward = path.get(Math.min(path.size() - 1, pathIdx + 2));
        Vec3 dir = toward.subtract(pos);
        if (dir.lengthSqr() > 1e-6) dir = dir.normalize().scale(0.6);
        AABB box = mc.player.getBoundingBox().inflate(0.2).expandTowards(dir);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos b : BlockPos.betweenClosed(Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
            Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ))) {
            BlockState st = mc.level.getBlockState(b);
            if (!FlightPathfinder.blocks(mc.level, b, st) || !stuckDiggable(b)) continue;
            double d = Vec3.atCenterOf(b).distanceToSqr(toward);
            if (d < bestD) {
                bestD = d;
                best = b.immutable();
            }
        }
        if (best == null) {
            LOG.info("[VG] really stuck at ({}) but nothing around is safe to dig", v(pos));
            snapshot("really stuck, nothing safe to dig");
            return;
        }
        snapshot("really stuck, digging out " + best.toShortString());
        digOutTarget = best;
        m.warning("Really stuck - digging out %d %d %d.", best.getX(), best.getY(), best.getZ());
    }

    /** The next path node or two has a block in it (a hole to dig): fly to the node before it, then mine it. */
    private void updateMining(Vec3 pos, boolean gliding) {
        BlockPos prev = mineTarget;
        mineTarget = null;
        digHold = null;
        if (path.isEmpty() || phase != Phase.Flying || !gliding || mending) {
            mineTicks = 0;
            return;
        }
        int last = path.size() - 1;
        for (int k = Math.max(1, pathIdx); k <= Math.min(last, pathIdx + 2); k++) {
            BlockPos feet = BlockPos.containing(path.get(k));
            BlockState st = mc.level.getBlockState(feet);
            // lava on a route planned under fire resistance is not "a block in the way": it used to trigger a new plan
            // every tick, and the bot wandered back and forth inside the lava sea
            if (lavaOkNow && st.getFluidState().is(FluidTags.LAVA) && st.getCollisionShape(mc.level, feet).isEmpty()) continue;
            if (!FlightPathfinder.blocks(mc.level, feet, st)) {
                if (brokeAge.containsKey(feet.asLong()) && mc.player.tickCount - brokeAge.get(feet.asLong()) < Tuning.i("dig.confirm.ticks", 8)) {
                    digHold = path.get(k - 1);
                    break;
                }
                continue;
            }
            brokeAge.remove(feet.asLong()); // solid (again): the server put it back, dig it once more
            if (!tunneling && !plannedMine.contains(feet.asLong()) && Tuning.b("mine.planned.only", true)) {
                // solid, but the route was planned through here as open air (planned before this chunk had loaded): don't
                // dig along a route that was never meant to be dug (it goes diagonally, which a 1x1 hole can't) - plan again
                if (mc.player.tickCount - unplannedLogAge > 40) {
                    unplannedLogAge = mc.player.tickCount;
                    LOG.info("[VG] {} on the route is solid but was planned as open air - planning again", feet.toShortString());
                }
                replanNow = true;
                return;
            }
            if (!mineThrough.get() || noMine.contains(feet.asLong()) || !FlightPathfinder.minable(mc.level, feet, st)) {
                replanNow = true; // something's in the way that we won't dig: plan around it
                tunneling = false;
                return;
            }
            mineTarget = feet.immutable();
            mineFrom = path.get(k - 1);
            break;
        }
        if (mineTarget == null) {
            mineTicks = 0;
            lineupTicks = 0;
            return;
        }
        // Digging is done on foot: once we're inside the hole (floor right under us, no room to stand), close the elytra
        // and crawl - a crawl breaks rock in one hit and goes at the Speed module's pace; digging while gliding is five
        // times slower per block (a 35-block tunnel took 27 s in the air). So: tunnels are 1x1, crawled.
        if (Tuning.b("dig.on.foot", true) && !mc.player.isInLava() && mc.player.tickCount - glideCloseAge > 40) {
            double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
            double drop = -net.minecraft.world.entity.Entity.collideBoundingBox(mc.player, new Vec3(0, -0.5, 0), mc.player.getBoundingBox(), mc.level, java.util.List.of()).y;
            boolean cantStand = !mc.level.noCollision(mc.player, new AABB(px - 0.3, py, pz - 0.3, px + 0.3, py + 1.8, pz + 0.3));
            if (drop < 0.49 && cantStand) {
                glideCloseAge = mc.player.tickCount;
                if (srvGlide == 1) mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                mc.player.stopFallFlying();
                pendingStartAge = -1;
                LOG.info("[VG] in the hole at ({}) - elytra closed, digging on foot from here", v(pos));
                return;
            }
        }
        if (!mineTarget.equals(prev)) {
            mineTicks = 0;
            lineupTicks = 0;
            LOG.info("[VG] digging {} ({}) from ({})", mineTarget, mc.level.getBlockState(mineTarget).getBlock(), v(mineFrom));
        }
        if (pos.distanceTo(mineFrom) > 0.3) { // get lined up first (onMove)
            // ...but if we can't get there (a diagonal step between two 1x1 holes, a block the server put back): dig
            // what's between us and that spot instead of hovering in front of it
            if (++lineupTicks > Tuning.i("mine.lineup.ticks", 40)) {
                BlockPos blk = blockerTowards(mineFrom);
                if (lineupTicks == Tuning.i("mine.lineup.ticks", 40) + 1) {
                    LOG.info("[VG] can't line up at ({}) to dig {} from ({}) - clearing {}", v(pos), mineTarget.toShortString(), v(mineFrom), blk == null ? "nothing (no diggable block in between)" : blk.toShortString());
                    snapshot("can't line up to dig");
                }
                if (blk != null && lineupTicks < 400) {
                    if (!dig(blk)) noMine.add(blk.asLong());
                }
                else if (blk == null && lineupTicks < Tuning.i("mine.lineup.giveup", 120)) { /* give the move a little longer */ }
                else {
                    m.warning("Can't get to %d %d %d to dig - looking for another way.", mineTarget.getX(), mineTarget.getY(), mineTarget.getZ());
                    noMine.add(mineTarget.asLong());
                    mineTarget = null;
                    mineTicks = 0;
                    lineupTicks = 0;
                    replanNow = true;
                    tunneling = false;
                }
            }
            return;
        }
        lineupTicks = 0;

        if (++mineTicks > 400) {
            m.warning("Couldn't dig through %d %d %d in 20 seconds - looking for another way.", mineTarget.getX(), mineTarget.getY(), mineTarget.getZ());
            noMine.add(mineTarget.asLong());
            mineTarget = null;
            mineTicks = 0;
            replanNow = true;
            return;
        }
        BlockPos target = mineTarget;
        mineTick(target);
    }

    private static String v(Vec3 a) {
        return String.format("%.2f %.2f %.2f", a.x, a.y, a.z);
    }

    /** Straight down onto the landing spot, slowing near the ground. */
    private Vec3 landingMove(Vec3 pos, double sV) {
        Vec3 spot = goalNode != null ? Vec3.atBottomCenterOf(goalNode) : (path.isEmpty() ? pos : path.getLast());
        double dx = Mth.clamp(spot.x - pos.x, -0.5, 0.5), dz = Mth.clamp(spot.z - pos.z, -0.5, 0.5);
        double ground = Math.max(0, pos.y - spot.y);
        double down = Mth.clamp(ground * 0.4, 0.1, sV);
        return new Vec3(dx, -Math.min(down, ground + 0.05), dz);
    }

    /** The furthest point along the path we can fly to in a straight line from here. */
    private Vec3 pickCarrot(Vec3 pos) {
        if (path.isEmpty()) return null;
        pathIdx = nearestIndex(pos, Math.max(0, pathIdx - 16), Math.min(path.size(), pathIdx + 96));

        int last = path.size() - 1;
        int[] ahead = {80, 64, 48, 32, 24, 16, 12, 8, 6, 4, 3, 2, 1};

        // with room to spare: furthest point we can get to keeping wall-distance from every block
        double roomyPad = Math.max(hitboxPadding.get(), wallDistance.get());
        if (roomyPad > hitboxPadding.get() + 1e-3) {
            int t = -1;
            for (int a : ahead) {
                int j = Math.min(last, pathIdx + a);
                if (j == t) continue;
                t = j;
                if (a < 2) break; // right next to us: not worth a wide check, use the normal one below
                Vec3 p = path.get(j);
                if (p.distanceTo(pos) > 96) continue;
                if (j < last && p.distanceTo(pos) < 0.05) continue; // we're on it: not somewhere to go
                if (clearLine(pos, p, roomyPad)) {
                    noCarrotTicks = 0;
                    carrotIdx = j;
                    carrotTight = false;
                    return p;
                }
            }
        }

        int tried = -1;
        for (int a : ahead) {
            int j = Math.min(last, pathIdx + a);
            if (j == tried) continue;
            tried = j;
            Vec3 p = path.get(j);
            if (p.distanceTo(pos) > 96) continue;
            if (j < last && p.distanceTo(pos) < 0.05) continue; // we're on it: not somewhere to go
            boolean roomy = clearLine(pos, p, hitboxPadding.get());
            if (roomy || (a <= 4 && clearLine(pos, p, 0.05))) {
                noCarrotTicks = 0;
                carrotIdx = j;
                carrotTight = !roomy;
                return p;
            }
        }

        // nothing in sight: the world changed or we got knocked off the path
        noCarrotTicks++;
        if (noCarrotTicks == 6) replanNow = true;
        return null;
    }

    private int nearestIndex(Vec3 pos, int from, int to) {
        int best = from;
        double bestD = Double.MAX_VALUE;
        for (int i = from; i < to; i++) {
            double d = path.get(i).distanceToSqr(pos);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        // the route runs into a dead end and comes back out past the same spot (a piece added at its end): take the LATER
        // pass, i.e. skip the trip into the dead end - otherwise "nearest" flips between the two and we go back and forth
        double lim = (Math.sqrt(bestD) + 0.3) * (Math.sqrt(bestD) + 0.3);
        for (int i = to - 1; i > best; i--) {
            if (path.get(i).distanceToSqr(pos) <= lim) {
                if (i - best > 3 && mc.player.tickCount - loopLogAge > 100) {
                    loopLogAge = mc.player.tickCount;
                    LOG.info("[VG] route passes here twice (nodes {} and {}) - skipping the loop", best, i);
                }
                return i;
            }
        }
        return best;
    }

    /** Can our gliding hitbox go straight from a to b without touching anything? */
    private boolean clearLine(Vec3 a, Vec3 b, double pad) {
        double below = Math.min(pad, Tuning.d("pad.below.max", 0.05));
        AABB box = new AABB(a.x - 0.3 - pad, a.y - below, a.z - 0.3 - pad, a.x + 0.3 + pad, a.y + 0.6 + pad, a.z + 0.3 + pad);
        Vec3 d = b.subtract(a);
        if (d.lengthSqr() < 1e-8) return true;
        int n = Math.max(1, (int) Math.ceil(d.length() / 0.3));
        // margin from lava for choosing where to fly; creeping / final-move checks (pad < 0) only avoid the lava itself
        double lm = lavaOkNow ? 0 : pad < 0 ? Tuning.d("lava.margin.creep", 0.0) : pad <= 0.051 ? Tuning.d("lava.margin.tight", 0.7) : Tuning.d("lava.margin", 1.2);
        AABB lavaBox = new AABB(a.x - 0.3 - lm, a.y - lm, a.z - 0.3 - lm, a.x + 0.3 + lm, a.y + 0.6 + lm, a.z + 0.3 + lm);
        for (int i = 1; i <= n; i++) {
            Vec3 off = d.scale(i / (double) n);
            if (!clearBox(box.move(off))) return false;
            if (lm > 0 && lavaIn(lavaBox.move(off))) return false;
        }
        return true;
    }

    /** Any lava block the box overlaps (just the lava itself - no margins, no lava falls). */
    private boolean lavaTouches(AABB b) {
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int x = Mth.floor(b.minX + 1e-3); x <= Mth.floor(b.maxX - 1e-3); x++) {
            for (int y = Mth.floor(b.minY + 1e-3); y <= Mth.floor(b.maxY - 1e-3); y++) {
                for (int z = Mth.floor(b.minZ + 1e-3); z <= Mth.floor(b.maxZ - 1e-3); z++) {
                    q.set(x, y, z);
                    if (mc.level.getBlockState(q).getFluidState().is(FluidTags.LAVA)) return true;
                }
            }
        }
        return false;
    }

    private boolean lavaIn(AABB b) {
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int x = Mth.floor(b.minX); x <= Mth.ceil(b.maxX) - 1; x++) {
            for (int z = Mth.floor(b.minZ); z <= Mth.ceil(b.maxZ) - 1; z++) {
                for (int y = Mth.floor(b.minY); y <= Mth.ceil(b.maxY) - 1; y++) {
                    q.set(x, y, z);
                    if (mc.level.getBlockState(q).getFluidState().is(FluidTags.LAVA)) return true;
                }
                // a lava fall coming down onto this column?
                if (FlightPathfinder.lavaAbove(mc.level, x, Mth.ceil(b.maxY) - 1, z, Tuning.i("lava.fall.range", 24))) return true;
            }
        }
        return false;
    }

    private final BlockPos.MutableBlockPos cpos = new BlockPos.MutableBlockPos();

    private boolean clearBox(AABB b) {
        int x1 = Mth.floor(b.minX), y1 = Mth.floor(b.minY), z1 = Mth.floor(b.minZ);
        int x2 = Mth.ceil(b.maxX) - 1, y2 = Mth.ceil(b.maxY) - 1, z2 = Mth.ceil(b.maxZ) - 1; // touching isn't overlapping
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                if (waitForChunks.get() && !mc.level.getChunkSource().hasChunk(x >> 4, z >> 4)) return false;
                for (int y = y1; y <= y2; y++) {
                    cpos.set(x, y, z);
                    BlockState cs = mc.level.getBlockState(cpos);
                    if (lavaOkNow && cs.getFluidState().is(FluidTags.LAVA) && cs.getCollisionShape(mc.level, cpos).isEmpty()) continue;
                    if (FlightPathfinder.blocks(mc.level, cpos, cs)) return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(Render3DEvent event) {
        if (!renderPath.get() || path.isEmpty()) return;
        int end = Math.min(path.size() - 1, pathIdx + 600);
        for (int i = Math.max(0, pathIdx); i < end; i++) {
            Vec3 a = path.get(i), b = path.get(i + 1);
            event.renderer.line(a.x, a.y + 0.2, a.z, b.x, b.y + 0.2, b.z, pathColor.get());
        }
        if (goalNode != null) event.renderer.box(goalNode, goalColor.get(), goalColor.get(), ShapeMode.Lines, 0);
        if (mineTarget != null) event.renderer.box(mineTarget, pathColor.get(), pathColor.get(), ShapeMode.Lines, 0);
        if (digOutTarget != null) event.renderer.box(digOutTarget, pathColor.get(), pathColor.get(), ShapeMode.Lines, 0);
        if (portalFound != null) event.renderer.box(portalFound, goalColor.get(), goalColor.get(), ShapeMode.Lines, 0);
    }

    @Override
    public boolean holdingKey(KeyMapping key) {
        return holdingForward && key == mc.options.keyUp;
    }

}
