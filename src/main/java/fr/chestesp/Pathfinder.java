package fr.chestesp;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * A* simple sur la grille de blocs : marche, saut de 1 bloc, chute jusqu'a 3 blocs.
 * Evite eau, lave, feu, cactus, toiles, magma, etc. Pas de diagonales.
 */
public final class Pathfinder {

    private static final int MAX_NODES = 8000;
    private static final double MAX_RADIUS = 64.0;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private record Node(BlockPos pos, double g, double f, Node parent) {}

    private Pathfinder() {}

    /**
     * @param goalCenter point vise (pour l'heuristique)
     * @param slack      distance acceptable autour du point (portee de minage...)
     * @param goalTest   test d'arrivee sur la position des pieds
     * @return liste de positions (pieds) sans le depart, vide si deja arrive, null si introuvable
     */
    public static List<BlockPos> find(ClientWorld world, ClientPlayerEntity player,
                                      Vec3d goalCenter, double slack, Predicate<BlockPos> goalTest) {
        BlockPos start = player.getBlockPos();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        Map<Long, Double> best = new HashMap<>();

        open.add(new Node(start, 0, h(start, goalCenter, slack), null));
        best.put(start.asLong(), 0.0);

        int expanded = 0;
        Node bestNode = null;
        double bestH = Double.MAX_VALUE;
        while (!open.isEmpty() && expanded < MAX_NODES) {
            Node n = open.poll();
            if (n.g() > best.getOrDefault(n.pos().asLong(), Double.MAX_VALUE)) continue;
            expanded++;

            if (goalTest.test(n.pos())) return build(n);
            double hh = h(n.pos(), goalCenter, slack);
            if (hh < bestH) {
                bestH = hh;
                bestNode = n;
            }

            int x = n.pos().getX(), y = n.pos().getY(), z = n.pos().getZ();
            for (int[] d : DIRS) {
                int nx = x + d[0], nz = z + d[1];
                if (Math.hypot(nx - start.getX(), nz - start.getZ()) > MAX_RADIUS) continue;

                // Meme niveau ou descente (jusqu'a 3 blocs)
                if (passable(world, nx, y, nz) && passable(world, nx, y + 1, nz)) {
                    for (int drop = 0; drop <= 3; drop++) {
                        int fy = y - drop;
                        if (drop > 0 && !passable(world, nx, fy, nz)) break;
                        if (solidFloor(world, nx, fy - 1, nz)) {
                            push(world, open, best, n, new BlockPos(nx, fy, nz), 1.0 + drop * 0.6, goalCenter, slack);
                            break;
                        }
                    }
                }

                // Montee d'un bloc (saut)
                if (passable(world, x, y + 2, z)
                        && solidFloor(world, nx, y, nz)
                        && passable(world, nx, y + 1, nz)
                        && passable(world, nx, y + 2, nz)) {
                    push(world, open, best, n, new BlockPos(nx, y + 1, nz), 1.8, goalCenter, slack);
                }
            }
        }
        // Limite atteinte : on renvoie le chemin partiel vers le point le plus proche du but
        if (expanded >= MAX_NODES && bestNode != null && bestNode.parent() != null) {
            return build(bestNode);
        }
        return null;
    }

    /** Surcout pour les cases a cote de lave / feu / cactus / magma : le chemin les evite si possible. */
    private static double hazardCost(ClientWorld world, BlockPos pos) {
        for (int[] d : DIRS) {
            for (int dy = -1; dy <= 0; dy++) {
                BlockPos n = pos.add(d[0], dy, d[1]);
                BlockState s = world.getBlockState(n);
                if (s.getFluidState().getFluid() == Fluids.LAVA || s.getFluidState().getFluid() == Fluids.FLOWING_LAVA
                        || danger(s)) {
                    return 6.0;
                }
            }
        }
        return 0.0;
    }

    private static void push(ClientWorld world, PriorityQueue<Node> open, Map<Long, Double> best, Node parent,
                             BlockPos pos, double cost, Vec3d goal, double slack) {
        double ng = parent.g() + cost + hazardCost(world, pos);
        long key = pos.asLong();
        if (ng < best.getOrDefault(key, Double.MAX_VALUE)) {
            best.put(key, ng);
            open.add(new Node(pos, ng, ng + 1.2 * h(pos, goal, slack), parent));
        }
    }

    private static double h(BlockPos p, Vec3d goal, double slack) {
        Vec3d c = new Vec3d(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
        return Math.max(0.0, c.distanceTo(goal) - slack);
    }

    private static List<BlockPos> build(Node end) {
        List<BlockPos> path = new ArrayList<>();
        for (Node n = end; n != null && n.parent() != null; n = n.parent()) {
            path.add(n.pos());
        }
        Collections.reverse(path);
        return path;
    }

    private static boolean passable(ClientWorld world, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = world.getBlockState(p);
        if (!s.getFluidState().isEmpty()) return false;
        if (!s.getCollisionShape(world, p).isEmpty()) return false;
        return !danger(s);
    }

    private static boolean solidFloor(ClientWorld world, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = world.getBlockState(p);
        if (!s.getFluidState().isEmpty()) return false;
        return s.isSideSolidFullSquare(world, p, Direction.UP) && !danger(s);
    }

    private static boolean danger(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.CACTUS
                || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.COBWEB || b == Blocks.POWDER_SNOW
                || b == Blocks.MAGMA_BLOCK || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE
                || b == Blocks.WITHER_ROSE;
    }
}
