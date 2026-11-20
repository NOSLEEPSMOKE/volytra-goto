package dev.volytragoto.brain.pathing;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Weighted A* over block cells for a flying player (elytra control flight can move in any direction, so every one of
 * the 26 neighbours is a move). A node is the block the player's feet are in; it needs that block and the one above
 * it to be open air. Runs in time slices on the client thread ({@link #step}) so the game never freezes.
 * <p>
 * Chunks that aren't loaded are unknown: a node there is a "frontier" - the search may end on it (a partial path up to
 * the edge of what we can see), and the caller plans again once more has loaded. While the goal itself isn't loaded
 * the search also ends on any node {@code horizon} blocks away from the start (horizontally), picking the one that
 * looks best for getting to the goal.
 */
public class FlightPathfinder {
    public enum Status { RUNNING, FOUND, PARTIAL, FAILED }

    // LOW = only the feet block is open; MINE = cell: solid but diggable / node: its feet block has to be dug out first
    private static final byte UNSET = 0, FREE = 1, BLOCKED = 2, UNLOADED = 3, LOW = 4, MINE = 5;
    private static final double LOW_COST = 3;
    private static final double HEURISTIC_WEIGHT = 1.4;
    // goal not loaded yet: the path only has to get us further on, so be greedier (far fewer nodes in big cave systems)
    private static final double HEURISTIC_WEIGHT_PARTIAL = 2.5;
    private final double hWeight;

    /** Blocks without a collision box that we still never want to fly through. */
    private static final Set<Block> AVOID = Set.of(
        Blocks.COBWEB, Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.WITHER_ROSE,
        Blocks.NETHER_PORTAL, Blocks.END_PORTAL, Blocks.END_GATEWAY, Blocks.BUBBLE_COLUMN, Blocks.SCAFFOLDING
    );

    private final World world;
    private final BlockPos start;
    private final BlockPos goal;      // exact node to reach, or null while it's unknown (not loaded yet)
    private final double tx, tz;      // where we're ultimately going
    private final double desiredY;    // height to prefer while the goal is unknown
    private final double yWeight;
    private final int horizon;
    private final boolean unloadedIsFrontier;
    private final int minY, maxY;
    private final int maxNodes;
    private final double wallCost;
    private final boolean allowLowGaps;
    private final double mineCost; // > 0: may dig 1x1 holes, each costing this much
    private final LongSet noMine;
    private boolean lavaOk; // fire resistance with time to spare: lava is just slow air

    /** Allow flying through lava (fire resistance). Call before the first step(). */
    public FlightPathfinder lavaOk(boolean ok) {
        this.lavaOk = ok;
        return this;
    }

    /**
     * Everything cached for one 16x16x16 chunk section, indexed by {@link #idx} (local x/z/y), so a lookup is one array
     * read once the section is in hand - and {@link #sec} keeps the sections around the node being worked on in a tiny
     * direct-mapped table, so the hash map is only hit when the search moves somewhere new.
     * <p>
     * The search is limited by memory speed far more than by arithmetic, so everything known about a block sits in one
     * short (the cell state, what it means for lava, the node state, the closed flag) and a column of blocks is 16
     * shorts in a row: scanning up or down, and a node's feet + head, stay in one cache line.
     */
    private static final class Sec {
        final int sx, sy, sz;
        final short[] info = new short[4096]; // the I_* bit fields below; 0 = nothing known yet
        Node[] nodes;

        Sec(int sx, int sy, int sz) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
        }
    }

    // Sec.info bit fields
    private static final int I_CELL = 7;                                // bits 0-2: cell() state, UNSET until looked at
    private static final int I_CLASS_SHIFT = 3, I_CLASS = 3 << 3;       // bits 3-4: CL_* class of the block, 0 = not read yet
    private static final int I_FALL_SHIFT = 5, I_FALL = 31 << 5;        // bits 5-9: lava fall value, see lavaFall()
    private static final int I_NODE_SHIFT = 10, I_NODE = 7 << 10;       // bits 10-12: nodeState(), UNSET until looked at
    private static final int I_CLOSED = 1 << 13;                        // bit 13: the node here is closed (same as Node.closed)

    private final Long2ObjectOpenHashMap<Sec> secs = new Long2ObjectOpenHashMap<>();
    /** Last sections used, indexed by the low bit of each section coordinate: any 2x2x2 block of sections fits without clashing. */
    private final Sec[] hot = new Sec[8];
    // open list: see openAdd()
    private double[] openF = new double[1024];
    private Node[] openNode = new Node[1024];
    private int openSize;
    private final BlockPos.Mutable mpos = new BlockPos.Mutable();
    private final BlockPos.Mutable qpos = new BlockPos.Mutable(); // lava lookups (mpos may be in use by cell())
    private final byte[] cube = new byte[27];                     // expand(): states of the 3x3x3 around the node

    private Node startNode, best;
    private int expanded;
    private Status status = Status.RUNNING;
    private List<BlockPos> result = List.of();
    private boolean endsAtFrontier;

    private static final class Node {
        final int x, y, z;
        double g, h, f;
        Node parent;
        boolean closed, frontier;
        // what moving INTO this node costs for a step along 1 / 2 / 3 axes (length 1, sqrt 2, sqrt 3) - only depends on
        // the node itself, so it's worked out once (costed) instead of for every neighbour that looks at it
        boolean costed;
        double cost1, cost2, cost3;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    // the 26 moves (and the centre, index 13), index (dx+1)*9 + (dy+1)*3 + (dz+1): offsets, how many axes the move
    // uses, and for diagonal moves the cube cells it passes next to (must be open: no cutting corners)
    private static final int[] NDX = new int[27], NDY = new int[27], NDZ = new int[27], AXES = new int[27];
    private static final int[][] CORNERS = new int[27][];
    /** Step lengths for a move along 1, 2, 3 axes (index 0 unused). */
    private static final double[] STEP = {0, Math.sqrt(1), Math.sqrt(2), Math.sqrt(3)};

    static {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int i = (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1);
                    NDX[i] = dx;
                    NDY[i] = dy;
                    NDZ[i] = dz;
                    AXES[i] = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);

                    int[] c = new int[6];
                    int count = 0;
                    for (int mask = 1; mask < 7; mask++) {
                        int mx = (mask & 1) != 0 ? dx : 0, my = (mask & 2) != 0 ? dy : 0, mz = (mask & 4) != 0 ? dz : 0;
                        if ((mx | my | mz) == 0 || (mx == dx && my == dy && mz == dz)) continue;
                        int ci = (mx + 1) * 9 + (my + 1) * 3 + (mz + 1);
                        boolean seen = false;
                        for (int j = 0; j < count; j++) seen |= c[j] == ci;
                        if (!seen) c[count++] = ci;
                    }
                    CORNERS[i] = java.util.Arrays.copyOf(c, count);
                }
            }
        }
    }

    /**
     * @param goal               exact goal node (must be open), or null when only the direction is known
     * @param desiredY           height to prefer while {@code goal} is null
     * @param yWeight            how much being away from {@code desiredY} counts (per block) while {@code goal} is null
     * @param horizon            while {@code goal} is null: stop at a node this many blocks (horizontally) from the start
     * @param unloadedIsFrontier true: unloaded chunks end the path (wait for them); false: treat them as open air
     */
    public FlightPathfinder(World world, BlockPos start, BlockPos goal, double tx, double tz, double desiredY, double yWeight,
                            int horizon, boolean unloadedIsFrontier, int minY, int maxY, int maxNodes, double wallCost,
                            boolean allowLowGaps, double mineCost, LongSet noMine) {
        this.world = world;
        this.start = start.toImmutable();
        this.goal = goal == null ? null : goal.toImmutable();
        this.tx = tx;
        this.tz = tz;
        this.desiredY = desiredY;
        this.yWeight = yWeight;
        this.horizon = horizon;
        this.unloadedIsFrontier = unloadedIsFrontier;
        this.minY = minY;
        this.maxY = maxY;
        this.maxNodes = maxNodes;
        this.wallCost = wallCost;
        this.allowLowGaps = allowLowGaps;
        this.hWeight = goal == null ? HEURISTIC_WEIGHT_PARTIAL : HEURISTIC_WEIGHT;
        this.mineCost = mineCost;
        this.noMine = noMine;

        startNode = node(start.getX(), start.getY(), start.getZ());
        startNode.g = 0;
        startNode.h = heuristic(startNode.x, startNode.y, startNode.z);
        startNode.f = hWeight * startNode.h;
        best = startNode;
        openAdd(startNode, startNode.f);
    }

    public Status status() {
        return status;
    }

    /** Blocks from the start to the end of the path (start included). */
    public List<BlockPos> path() {
        return result;
    }

    /** True if the path stops because what's next isn't loaded / is beyond the planning radius (not at the goal). */
    /** True if this cell of the result was planned as "dig it out" (not as open air). */
    public boolean plannedMine(BlockPos b) {
        return mineCost > 0 && nodeState(b.getX(), b.getY(), b.getZ()) == MINE;
    }

    public boolean endsAtFrontier() {
        return endsAtFrontier;
    }

    public int expanded() {
        return expanded;
    }

    /** Run the search for up to {@code budgetNanos}. */
    public Status step(long budgetNanos) {
        if (status != Status.RUNNING) return status;
        long end = System.nanoTime() + budgetNanos;
        int i = 0;

        while (openSize > 0) {
            if ((++i & 31) == 0 && System.nanoTime() > end) return status;

            double f = openF[0];
            Node n = openPoll();
            if (n.closed || f != n.f) continue;
            n.closed = true;
            sec(n.x >> 4, n.y >> 4, n.z >> 4).info[idx(n.x, n.y, n.z)] |= I_CLOSED;
            expanded++;

            if (n.h < best.h) best = n;

            if (goal != null && n.x == goal.getX() && n.y == goal.getY() && n.z == goal.getZ()) return finish(n, Status.FOUND, false);
            if (n.frontier) return finish(n, Status.PARTIAL, true);
            if (goal == null && n != startNode && horizontalDist(n.x, n.z, start.getX(), start.getZ()) >= horizon) {
                return finish(n, Status.PARTIAL, true);
            }
            if (expanded >= maxNodes) break;

            expand(n);
        }

        // ran out of nodes (or there's nowhere to go): take whatever got closest
        if (best != startNode) return finish(best, Status.PARTIAL, false);
        status = Status.FAILED;
        return status;
    }

    /** How much closer (heuristic) the best node so far is than the start. */
    public double bestGain() {
        return startNode.h - best.h;
    }

    /** Out of time: stop now with a path to the node that got closest so far (FAILED if none). */
    public Status finishNow() {
        if (status != Status.RUNNING) return status;
        if (best != startNode) return finish(best, Status.PARTIAL, false);
        status = Status.FAILED;
        return status;
    }

    private Status finish(Node n, Status s, boolean frontier) {
        ArrayList<BlockPos> list = new ArrayList<>();
        for (Node c = n; c != null; c = c.parent) list.add(new BlockPos(c.x, c.y, c.z));
        Collections.reverse(list);
        result = list;
        endsAtFrontier = frontier;
        status = s;
        return s;
    }

    private void expand(Node n) {
        final int x = n.x, y = n.y, z = n.z;
        boolean fromMine = mineCost > 0 && nodeState(x, y, z) == MINE;
        // passability of the 3x3x3 cube around n (index (dx+1)*9 + (dy+1)*3 + (dz+1)), and which of those are closed
        final byte[] cube = this.cube;
        int closed = 0;
        for (int i = 0; i < 27; i++) {
            if (i == 13) {
                cube[i] = FREE;
                continue;
            }
            int v = nodeInfo(x + NDX[i], y + NDY[i], z + NDZ[i]);
            cube[i] = (byte) (v & 7);
            closed |= (v >> 3) << i;
        }

        // same order as three nested dx / dy / dz loops
        next:
        for (int i = 0; i < 27; i++) {
            // closed neighbours (about half of them, behind the search front) are dropped here, without touching their Node
            if (i == 13 || (closed >> i & 1) != 0) continue;
            byte s = cube[i];
            if (s == BLOCKED) continue;
            int axes = AXES[i];
            if ((s == MINE || fromMine) && axes != 1) continue;

            // no cutting corners: every cell the move passes next to must be open too
            for (int ci : CORNERS[i]) {
                byte c = cube[ci];
                if (c == BLOCKED || c == MINE) continue next;
            }

            int nx = x + NDX[i], ny = y + NDY[i], nz = z + NDZ[i];
            Node m = node(nx, ny, nz);
            if (!m.costed) cost(m, s);
            double g = n.g + (axes == 1 ? m.cost1 : axes == 2 ? m.cost2 : m.cost3);
            if (m.parent != null || m == startNode) {
                if (g >= m.g) continue;
            }
            else {
                m.h = heuristic(nx, ny, nz);
            }
            m.g = g;
            m.parent = n;
            m.frontier = s == UNLOADED;
            m.f = g + hWeight * m.h;
            openAdd(m, m.f);
        }
    }

    // The open list: a binary heap of (f, node) pairs kept in two plain arrays. It does exactly what a
    // java.util.PriorityQueue of entries ordered by Double.compare(f) does - the same sift up / sift down, so equal f
    // values come out in the same order - without an object per entry (comparing goes through one array of doubles).

    private void openAdd(Node node, double f) {
        int k = openSize;
        if (k >= openF.length) {
            openF = java.util.Arrays.copyOf(openF, k * 2);
            openNode = java.util.Arrays.copyOf(openNode, k * 2);
        }
        openSize = k + 1;
        final double[] fs = openF;
        final Node[] ns = openNode;
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            double pf = fs[parent];
            if (Double.compare(f, pf) >= 0) break;
            fs[k] = pf;
            ns[k] = ns[parent];
            k = parent;
        }
        fs[k] = f;
        ns[k] = node;
    }

    /** Removes and returns the node with the lowest f (which is {@code openF[0]} before the call). */
    private Node openPoll() {
        final double[] fs = openF;
        final Node[] ns = openNode;
        Node result = ns[0];
        int n = --openSize;
        double f = fs[n];
        Node node = ns[n];
        ns[n] = null;
        if (n > 0) {
            int k = 0, half = n >>> 1;
            while (k < half) {
                int child = (k << 1) + 1, right = child + 1;
                double cf = fs[child];
                if (right < n && Double.compare(cf, fs[right]) > 0) cf = fs[child = right];
                if (Double.compare(f, cf) <= 0) break;
                fs[k] = cf;
                ns[k] = ns[child];
                k = child;
            }
            fs[k] = f;
            ns[k] = node;
        }
        return result;
    }

    /**
     * Cost of a move into {@code m} (whose node state is {@code s}, never BLOCKED), for each of the three step lengths.
     * The sums are built up in the same order every time (length, then mine / low / wall, then lava, then height) so
     * the totals come out bit for bit the same as adding them up per move.
     */
    private void cost(Node m, byte s) {
        final int x = m.x, y = m.y, z = m.z;
        double c1 = STEP[1], c2 = STEP[2], c3 = STEP[3];
        if (s == MINE) {
            c1 += mineCost;
            c2 += mineCost;
            c3 += mineCost;
        }
        else if (s == LOW) {
            c1 += LOW_COST;
            c2 += LOW_COST;
            c3 += LOW_COST;
        }
        else if (s == FREE && wallCost > 0) {
            double p = wallCost * (walls(x, y, z) + nearWall(x, y, z));
            c1 += p;
            c2 += p;
            c3 += p;
        }
        if (!lavaOk && nearLava(x, y, z)) {
            c1 += LAVA_COST;
            c2 += LAVA_COST;
            c3 += LAVA_COST;
        }
        // hostile mobs seen when this search started: keep out of their reach where there's a way round (they only land
        // hits when we pass slowly within a few blocks - 2 to 4 hp each time)
        final double[] dg = DANGERS;
        if (dg.length > 0 && DANGER_COST > 0) {
            final double r2 = DANGER_RADIUS * DANGER_RADIUS;
            for (int i = 0; i + 2 < dg.length; i += 3) {
                double ddx = x + 0.5 - dg[i], ddy = y + 0.3 - dg[i + 1], ddz = z + 0.5 - dg[i + 2];
                if (ddx * ddx + ddy * ddy + ddz * ddz < r2) {
                    c1 += DANGER_COST;
                    c2 += DANGER_COST;
                    c3 += DANGER_COST;
                    break;
                }
            }
        }
        // fire resistance: lava can be flown through - but as a short cut, not as a place to be (the potion runs out, and
        // 15 s of burning follow whenever it does): every cell of it costs extra, so the route only dips in when the way
        // round is much longer and never goes for a swim in the lava sea
        if (lavaOk && LAVA_OK_COST > 0 && (isLava(x, y, z) || isLava(x, y + 1, z))) {
            c1 += LAVA_OK_COST;
            c2 += LAVA_OK_COST;
            c3 += LAVA_OK_COST;
        }
        if (s == FREE && wallCost > 0) {
            double p = wallCost * heightPenalty(x, y, z);
            c1 += p;
            c2 += p;
            c3 += p;
        }
        m.cost1 = c1;
        m.cost2 = c2;
        m.cost3 = c3;
        m.costed = true;
    }

    private Node node(int x, int y, int z) {
        Sec sec = sec(x >> 4, y >> 4, z >> 4);
        Node[] a = sec.nodes;
        if (a == null) a = sec.nodes = new Node[4096];
        int i = idx(x, y, z);
        Node n = a[i];
        if (n == null) a[i] = n = new Node(x, y, z);
        return n;
    }

    /** Index into a section's arrays: y in the low bits, so a column of blocks is contiguous. */
    private static int idx(int x, int y, int z) {
        return ((x & 15) << 8) | ((z & 15) << 4) | (y & 15);
    }

    private Sec sec(int sx, int sy, int sz) {
        Sec s = hot[((sx & 1) << 2) | ((sy & 1) << 1) | (sz & 1)];
        if (s != null && s.sx == sx && s.sy == sy && s.sz == sz) return s;
        return secSlow(sx, sy, sz);
    }

    private Sec secSlow(int sx, int sy, int sz) {
        long k = ((long) sx & 0x3FFFFF) << 42 | ((long) sz & 0x3FFFFF) << 20 | ((long) sy & 0xFFFFF);
        Sec s = secs.get(k);
        if (s == null) {
            s = new Sec(sx, sy, sz);
            secs.put(k, s);
        }
        hot[((sx & 1) << 2) | ((sy & 1) << 1) | (sz & 1)] = s;
        return s;
    }

    private double heuristic(int x, int y, int z) {
        if (goal != null) {
            double dx = x - goal.getX(), dy = y - goal.getY(), dz = z - goal.getZ();
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return horizontalDist(x + 0.5, z + 0.5, tx, tz) + yWeight * Math.abs(y - desiredY);
    }

    /** Extra cost of a cell that touches lava or fire (live-tunable: path.lava.cost). */
    public static double LAVA_COST = 20;
    /** With fire resistance: extra cost of each cell that IS lava (live-tunable: path.lavaok.cost). */
    public static double LAVA_OK_COST = 6;
    /** Hostile mobs near the player when the search started (x, y, z triples); nodes within DANGER_RADIUS cost DANGER_COST more. */
    public static double[] DANGERS = new double[0];
    public static double DANGER_RADIUS = 5, DANGER_COST = 12;
    /** Prefer this many open blocks under the path (terrain or lava below costs extra, more the closer it is). */
    public static int FLOOR_CLEARANCE = 10;
    public static double FLOOR_WEIGHT = 0.5, CEILING_WEIGHT = 0.3;
    /** Extra cost (times the wall cost) of a cell with something solid directly under it; a third of it one block higher. */
    public static double FLOOR_TOUCH = 6;

    // heightPenalty(), nearLava(), walls() and nearWall() are only asked once per node (cost() keeps the result on the
    // node), so they don't cache anything themselves

    /** 0 in open space; grows as the floor (or lava) below gets closer than FLOOR_CLEARANCE, or the ceiling closer than 4. */
    private double heightPenalty(int x, int y, int z) {
        int clearance = FLOOR_CLEARANCE;
        int below = clearance;
        for (int d = 1; d <= clearance; d++) {
            byte s = cell(x, y - d, z);
            if (s == BLOCKED || s == MINE) {
                below = d - 1;
                break;
            }
        }
        int above = 4;
        for (int d = 2; d <= 5; d++) {
            byte s = cell(x, y + d, z);
            if (s == BLOCKED || s == MINE) {
                above = d - 2;
                break;
            }
        }
        byte c = (byte) (below * 8 + above); // packed into a byte like it always was (matters only for a clearance over 15)
        below = c / 8;
        above = c % 8;
        // right on top of something (tree tops, crests): touching it closes the elytra - worth a few blocks' detour upwards
        return FLOOR_WEIGHT * (clearance - below) + CEILING_WEIGHT * (4 - above) + (below == 0 ? FLOOR_TOUCH : below == 1 ? FLOOR_TOUCH / 3 : 0);
    }

    // offsets (x, y, z) from a node's feet block: the 4 sides of feet and head, below the feet, above the head
    // (...and up to 3 below: skimming the lava sea one block up is one hover away from a bath)
    private static final int[] LAVA_NEAR = {1, 0, 0, -1, 0, 0, 0, 0, 1, 0, 0, -1, 0, -1, 0, 1, 1, 0, -1, 1, 0, 0, 1, 1, 0, 1, -1, 0, 2, 0, 0, -2, 0, 0, -3, 0,
        // the diagonals at feet level and one below (fire half a block off to the side and below still burned us)
        1, 0, 1, 1, 0, -1, -1, 0, 1, -1, 0, -1, 1, -1, 0, -1, -1, 0, 0, -1, 1, 0, -1, -1, 1, -1, 1, 1, -1, -1, -1, -1, 1, -1, -1, -1};

    /** Lava touching the feet or head block of this node (any of the 6 sides, or above the head). */
    private boolean nearLava(int x, int y, int z) {
        final int[] o = LAVA_NEAR;
        for (int i = 0; i < o.length; i += 3) {
            if (isLava(x + o[i], y + o[i + 1], z + o[i + 2])) return true;
        }
        return false;
    }

    // what a block means to lava: it IS lava, it stops a lava fall (anything solid or liquid), or lava falls through it
    private static final int CL_PASS = 1, CL_STOP = 2, CL_LAVA = 3;
    private static final int LAVA_RANGE = 24;            // how far up cell() looks for lava that could fall down
    private static final int FALL_NONE = LAVA_RANGE + 1; // lava fall value: 1..LAVA_RANGE = lava that many blocks up, this = none

    /** Same three tests as {@link #lavaAbove} makes on each block, in the same order. */
    private int lavaClass(BlockState st, BlockPos pos) {
        if (st.isAir()) return CL_PASS; // no fluid, no collision box
        // fire burns like lava when we brush past it: give the cells next to it the same extra cost (it always sits on a
        // solid block, so it never counts as a "lava fall" for anything below)
        if (st.isOf(Blocks.FIRE) || st.isOf(Blocks.SOUL_FIRE)) return CL_LAVA;
        if (st.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA)) return CL_LAVA;
        if (!st.getCollisionShape(world, pos).isEmpty() || !st.getFluidState().isEmpty()) return CL_STOP;
        return CL_PASS;
    }

    /** Is this block lava (false while its chunk isn't loaded)? Read from the world once per block. */
    private boolean isLava(int x, int y, int z) {
        short[] a = sec(x >> 4, y >> 4, z >> 4).info;
        int i = idx(x, y, z);
        int b = a[i], cls = (b & I_CLASS) >> I_CLASS_SHIFT;
        if (cls == 0) {
            if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) return false;
            qpos.set(x, y, z);
            cls = lavaClass(world.getBlockState(qpos), qpos);
            a[i] = (short) (b | cls << I_CLASS_SHIFT);
        }
        return cls == CL_LAVA;
    }

    /**
     * The same answer as {@code lavaAbove(world, x, y, z, LAVA_RANGE)}, without reading up to 24 blocks every time:
     * each block's class is read once (shared with {@link #isLava}), and every cell the scan passes on the way up
     * remembers how far above it the lava is (or that there is none), so the next scan from below stops as soon as it
     * meets one of those. Only for cells in a loaded chunk.
     */
    private boolean lavaFall(int x, int y, int z) {
        final int top = world.getTopYInclusive(), sx = x >> 4, sz = z >> 4, col = idx(x, 0, z);
        Sec sec = null;
        short[] a = null;

        // look up: v = the fall value of the cell at y + j - 1 when the loop stops early
        int v = FALL_NONE, j = 1;
        for (; j <= LAVA_RANGE; j++) {
            int yy = y + j;
            if (yy > top) break; // nothing but the sky left: no lava above any of the cells passed
            if (sec == null || (yy & 15) == 0) {
                sec = sec(sx, yy >> 4, sz);
                a = sec.info;
            }
            int i = col | (yy & 15);
            int b = a[i], cls = (b & I_CLASS) >> I_CLASS_SHIFT;
            if (cls == 0) {
                qpos.set(x, yy, z);
                cls = lavaClass(world.getBlockState(qpos), qpos);
                a[i] = (short) (b | cls << I_CLASS_SHIFT);
            }
            if (cls == CL_LAVA) {
                v = 1;
                break;
            }
            if (cls == CL_STOP) break;
            int f = (b & I_FALL) >> I_FALL_SHIFT; // open block: has a scan from here been done before?
            if (f != 0) {
                v = f >= LAVA_RANGE ? FALL_NONE : f + 1;
                break;
            }
        }
        // LAVA_RANGE open blocks and nothing known beyond them: that settles this cell only
        if (j > LAVA_RANGE) j = 1;

        // back down: the cells passed are all open, so each one's value follows from the one above it
        for (int yy = y + j - 1; ; yy--) {
            if (sec == null || sec.sy != yy >> 4) {
                sec = sec(sx, yy >> 4, sz);
                a = sec.info;
            }
            int i = col | (yy & 15);
            a[i] = (short) ((a[i] & ~I_FALL) | (v << I_FALL_SHIFT));
            if (yy == y) return v != FALL_NONE;
            if (v != FALL_NONE && ++v > LAVA_RANGE) v = FALL_NONE;
        }
    }

    private static double horizontalDist(double x1, double z1, double x2, double z2) {
        return Math.hypot(x1 - x2, z1 - z2);
    }

    /** Feet + head cells: FREE, BLOCKED, or UNLOADED (only when unloaded chunks end the path). */
    private byte nodeState(int x, int y, int z) {
        return (byte) (nodeInfo(x, y, z) & 7);
    }

    /** {@link #nodeState} in bits 0-2, plus bit 3 (value 8) set if the node here is closed. */
    private int nodeInfo(int x, int y, int z) {
        short[] a = sec(x >> 4, y >> 4, z >> 4).info;
        int i = idx(x, y, z);
        int v = a[i] >> I_NODE_SHIFT;
        if ((v & 7) != UNSET) return v;

        byte s;
        byte feet = cell(x, y, z), head = cell(x, y + 1, z);
        if (feet == BLOCKED) s = BLOCKED;
        else if (feet == MINE) s = MINE; // dig the feet block out: a 1x1 hole is enough
        else if (head == BLOCKED || head == MINE) s = (allowLowGaps || mineCost > 0) && feet == FREE ? LOW : BLOCKED; // a gliding player is only 0.6 tall
        else if (feet == UNLOADED || head == UNLOADED) s = unloadedIsFrontier ? UNLOADED : FREE;
        else s = FREE;

        a[i] |= s << I_NODE_SHIFT; // (cell() has just written to the same short)
        return v | s;
    }

    /** How many of the cells right around a node's feet + head are solid (0 in open air). */
    private int walls(int x, int y, int z) {
        int c = 0;
        for (int dy = 0; dy <= 1; dy++) {
            if (solid(cell(x + 1, y + dy, z))) c++;
            if (solid(cell(x - 1, y + dy, z))) c++;
            if (solid(cell(x, y + dy, z + 1))) c++;
            if (solid(cell(x, y + dy, z - 1))) c++;
        }
        if (solid(cell(x, y - 1, z))) c++;
        if (solid(cell(x, y + 2, z))) c++;
        return c;
    }

    /**
     * Extra wall penalty for blocks 2-3 away (walls() only sees the ones touching): look out along the 26 directions.
     * 1.5 = something 2 blocks away, 0.5 = 3 away, 0 = open. Keeps paths off walls when there's room; in a tunnel
     * every cell gets the same penalty, so it changes nothing there.
     */
    private double nearWall(int x, int y, int z) {
        for (int r = 2; r <= 3; r++) {
            for (int i = 0; i < 27; i++) { // same order as nested dx / dy / dz loops
                if (i == 13) continue;
                int dy = NDY[i];
                int sy = (dy > 0 ? y + 1 : y) + dy * r; // up: measured from the head block
                if (solid(cell(x + NDX[i] * r, sy, z + NDZ[i] * r))) return r == 2 ? 1.5 : 0.5;
            }
        }
        return 0;
    }

    private byte cell(int x, int y, int z) {
        if (y < minY || y > maxY) return BLOCKED;
        short[] a = sec(x >> 4, y >> 4, z >> 4).info;
        int i = idx(x, y, z);
        int s = a[i] & I_CELL;
        if (s != UNSET) return (byte) s;
        return readCell(a, i, x, y, z);
    }

    /** A cell that hasn't been looked at yet: work out its state from the world and remember it. */
    private byte readCell(short[] a, int i, int x, int y, int z) {
        byte s;
        if (!world.getWorldBorder().contains(x + 0.5, z + 0.5)) s = BLOCKED;
        else if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) s = UNLOADED;
        else {
            mpos.set(x, y, z);
            BlockState st = world.getBlockState(mpos);
            if (!lavaOk) {
                // the block is in hand: note what it is for the lava lookups too (they'd otherwise read it again)
                int b = a[i];
                if ((b & I_CLASS) == 0) a[i] = (short) (b | lavaClass(st, mpos) << I_CLASS_SHIFT);
            }
            if (lavaOk && st.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA) && st.getCollisionShape(world, mpos).isEmpty()) s = FREE;
            else if (!blocks(world, mpos, st)) s = !lavaOk && lavaFall(x, y, z) ? BLOCKED : FREE; // lava falls come down here
            else s = mineCost > 0 && !noMine.contains(BlockPos.asLong(x, y, z)) && minable(world, mpos.toImmutable(), st) ? MINE : BLOCKED;
        }
        a[i] |= s; // (lavaFall() may have written to the same short)
        return s;
    }

    /** Can't (or shouldn't) fly through this block: anything solid, any liquid, cobwebs, fire, portals... */
    public static boolean blocks(World world, BlockPos pos, BlockState state) {
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return true;
        if (AVOID.contains(state.getBlock())) return true;
        return !state.getCollisionShape(world, pos).isEmpty();
    }

    /**
     * Lava somewhere above (within range) with nothing but air/non-solid in between: a lava fall comes (or will come)
     * down through this cell. Lava resting on a solid block doesn't count.
     */
    public static boolean lavaAbove(World world, int x, int y, int z, int range) {
        BlockPos.Mutable q = new BlockPos.Mutable();
        int top = Math.min(world.getTopYInclusive(), y + range);
        for (int yy = y + 1; yy <= top; yy++) {
            q.set(x, yy, z);
            BlockState st = world.getBlockState(q);
            if (st.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA)) return true;
            if (!st.getCollisionShape(world, q).isEmpty() || !st.getFluidState().isEmpty()) return false;
        }
        return false;
    }

    private static boolean solid(byte c) {
        return c == BLOCKED || c == MINE;
    }

    /**
     * Safe to dig out for a 1x1 hole: breakable and not obsidian-hard (hardness under 20), no water/lava touching it
     * (would flood in), nothing that falls (sand, gravel) on top.
     */
    public static boolean minable(World world, BlockPos pos, BlockState state) {
        if (!state.getFluidState().isEmpty() || AVOID.contains(state.getBlock()) || state.hasBlockEntity()) return false;
        float h = state.getHardness(world, pos);
        if (h < 0 || h >= 20) return false;
        for (Direction d : Direction.values()) {
            if (!world.getBlockState(pos.offset(d)).getFluidState().isEmpty()) return false;
        }
        return !(world.getBlockState(pos.up()).getBlock() instanceof FallingBlock);
    }

    /** A player could be here: this block and the one above are open (and loaded). */
    public static boolean open(World world, int x, int y, int z) {
        if (y < world.getBottomY() || y + 1 > world.getTopYInclusive()) return false;
        if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4) || !world.getWorldBorder().contains(x + 0.5, z + 0.5)) return false;
        BlockPos.Mutable p = new BlockPos.Mutable(x, y, z);
        if (blocks(world, p, world.getBlockState(p))) return false;
        p.setY(y + 1);
        return !blocks(world, p, world.getBlockState(p));
    }
}
