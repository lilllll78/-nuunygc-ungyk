package fr.chestesp;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.AnvilBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.CraftingTableBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bot de construction : pose un schema, remplit ou vide une selection.
 * Marche avec le pathfinder, pose contre un bloc voisin, minage via le vrai rayon du viseur.
 * Prevu pour le solo / creatif.
 */
public final class BuildBot {

    public enum Mode { NONE, SCHEM, FILL, CLEAR }

    public static Mode mode = Mode.NONE;
    public static BlockState fillState = null;

    private static final double REACH = 4.2;
    private static final Direction[] SUPPORT_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};

    private record Support(BlockPos neighbor, Direction face) {}

    private static int tick = 0;
    private static int cooldown = 0;
    private static int align = 0;
    private static int misses = 0;
    private static int arrived = 0;
    private static int done = 0;
    private static float ease = 0.25f;

    private static List<BlockPos> tasks = new ArrayList<>();
    private static BlockPos current = null;

    private static List<BlockPos> path = null;
    private static int pathIdx = 0;
    private static int repath = 0;
    private static Vec3d stuckRef = null;
    private static int stuckCount = 0;
    private static boolean keysHeld = false;

    private static final Map<Long, Integer> skip = new HashMap<>();
    private static final Set<Item> warned = new HashSet<>();

    private BuildBot() {}

    // ----------------------------------------------------------- controle

    public static boolean start(MinecraftClient mc, Mode m) {
        ClientPlayerEntity p = mc.player;
        if (p == null) return false;

        if (m == Mode.SCHEM) {
            if (BuildState.schem == null || BuildState.origin == null) {
                msg("Charge un schéma et définis l'origine d'abord");
                return false;
            }
        } else {
            if (!BuildState.hasSelection()) {
                msg("Définis les 2 coins de la sélection d'abord");
                return false;
            }
            if (m == Mode.FILL) {
                ItemStack held = p.getMainHandStack();
                if (!(held.getItem() instanceof BlockItem bi)) {
                    msg("Mets le bloc à poser dans ta main");
                    return false;
                }
                fillState = bi.getBlock().getDefaultState();
            }
        }

        reset();
        mode = m;
        Config.anchorFarm = false;
        msg("Build : " + switch (m) {
            case SCHEM -> "schéma";
            case FILL -> "remplissage";
            case CLEAR -> "vidage";
            default -> "";
        } + " démarré");
        return true;
    }

    public static void stop(String message) {
        mode = Mode.NONE;
        current = null;
        tasks = new ArrayList<>();
        path = null;
        releaseKeys(MinecraftClient.getInstance());
        if (message != null) msg(message);
    }

    private static void reset() {
        tick = 0;
        cooldown = 0;
        done = 0;
        current = null;
        tasks = new ArrayList<>();
        path = null;
        skip.clear();
        warned.clear();
        newTaskReset();
    }

    private static void newTaskReset() {
        path = null;
        pathIdx = 0;
        repath = 0;
        align = 0;
        misses = 0;
        arrived = 0;
        stuckRef = null;
        stuckCount = 0;
        ease = 0.18f + Look.RNG.nextFloat() * 0.15f;
    }

    private static void msg(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal(s), true);
    }

    // --------------------------------------------------------------- tick

    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity player = mc.player;
        ClientWorld world = mc.world;

        if (mode == Mode.NONE || player == null || world == null
                || mc.interactionManager == null || mc.currentScreen != null) {
            releaseKeys(mc);
            return;
        }

        tick++;
        releaseKeys(mc);

        if (cooldown > 0) { cooldown--; return; }
        skip.values().removeIf(e -> e <= tick);

        if (current != null && !needed(world, current)) {
            current = null;
            done++;
            newTaskReset();
        }

        if (current == null) {
            current = pick(world);
            if (current == null) {
                tasks = collect(world, player, 24, 20000);
                if (tasks.isEmpty()) tasks = collect(world, player, 48, 20000);
                current = pick(world);
                if (current == null) {
                    stop(tasks.isEmpty() ? "Terminé : " + done + " blocs"
                            : "Plus rien de posable (support manquant, objet manquant ou inaccessible) : " + done + " blocs");
                    return;
                }
            }
            newTaskReset();
        }

        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(current);
        boolean placing = mode != Mode.CLEAR;
        boolean inReach = eye.distanceTo(center) <= REACH;
        boolean inTheWay = placing && player.getBoundingBox().intersects(new Box(current));

        if (inReach && !inTheWay) {
            if (placing) place(mc, player, world, current);
            else breakBlock(mc, player, world, current, center);
        } else {
            walk(mc, player, world, current);
        }
    }

    // -------------------------------------------------------------- taches

    private static BlockState targetFor(BlockPos p) {
        return mode == Mode.SCHEM ? BuildState.targetAt(p) : fillState;
    }

    private static boolean needed(ClientWorld world, BlockPos p) {
        BlockState cur = world.getBlockState(p);
        switch (mode) {
            case CLEAR:
                return !cur.isAir() && cur.getFluidState().isEmpty() && cur.getHardness(world, p) >= 0
                        && BuildState.inSelection(p);
            case FILL:
                return BuildState.inSelection(p) && (cur.isAir() || cur.isReplaceable());
            case SCHEM: {
                BlockState t = BuildState.targetAt(p);
                return t != null && cur.getBlock() != t.getBlock() && (cur.isAir() || cur.isReplaceable());
            }
            default:
                return false;
        }
    }

    private static BlockPos pick(ClientWorld world) {
        boolean placing = mode != Mode.CLEAR;
        for (BlockPos p : tasks) {
            if (skip.containsKey(p.asLong())) continue;
            if (!needed(world, p)) continue;
            if (placing && findSupport(world, p) == null) continue;
            return p;
        }
        return null;
    }

    private static List<BlockPos> collect(ClientWorld world, ClientPlayerEntity player, int radius, int cap) {
        int px = player.getBlockX(), py = player.getBlockY(), pz = player.getBlockZ();
        int minX = px - radius, maxX = px + radius, minZ = pz - radius, maxZ = pz + radius;
        int minY = Math.max(world.getBottomY(), py - radius), maxY = Math.min(world.getBottomY() + world.getHeight() - 1, py + radius);

        int[] b = mode == Mode.SCHEM ? BuildState.schemBounds() : BuildState.selBounds();
        List<BlockPos> out = new ArrayList<>();
        if (b == null) return out;
        minX = Math.max(minX, b[0]); minY = Math.max(minY, b[1]); minZ = Math.max(minZ, b[2]);
        maxX = Math.min(maxX, b[3]); maxY = Math.min(maxY, b[4]); maxZ = Math.min(maxZ, b[5]);

        BlockPos.Mutable mp = new BlockPos.Mutable();
        outer:
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    mp.set(x, y, z);
                    if (needed(world, mp)) {
                        out.add(new BlockPos(x, y, z));
                        if (out.size() >= cap) break outer;
                    }
                }
            }
        }

        BlockPos pp = player.getBlockPos();
        if (mode == Mode.CLEAR) {
            out.sort(Comparator.<BlockPos>comparingInt(p -> -p.getY())
                    .thenComparingDouble(p -> p.getSquaredDistance(pp)));
        } else {
            out.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY)
                    .thenComparingDouble(p -> p.getSquaredDistance(pp)));
        }
        return out;
    }

    // ---------------------------------------------------------------- pose

    private static void place(MinecraftClient mc, ClientPlayerEntity player, ClientWorld world, BlockPos pos) {
        BlockState target = targetFor(pos);
        if (target == null) { giveUp(pos); return; }
        Item item = target.getBlock().asItem();
        if (item == Items.AIR) { giveUp(pos); return; }

        int r = ensureItem(mc, player, item);
        if (r == 0) {
            if (warned.add(item)) msg("Manque : " + item.getName().getString());
            skip.put(pos.asLong(), tick + 1200);
            current = null;
            return;
        }
        if (r == 2) { cooldown = 2; return; }

        Support s = findSupport(world, pos);
        if (s == null) { giveUp(pos); return; }

        Direction f = s.face();
        Vec3d hit = Vec3d.ofCenter(s.neighbor()).add(f.getOffsetX() * 0.5, f.getOffsetY() * 0.5, f.getOffsetZ() * 0.5);
        if (f.getAxis().isHorizontal()) {
            hit = new Vec3d(hit.x, s.neighbor().getY() + (isTopHalf(target) ? 0.8 : 0.2), hit.z);
        }

        Vec3d eye = player.getEyePos();
        float ty = Look.yawTo(eye, hit);
        float tp = Look.pitchTo(eye, hit);
        Direction facing = desiredPlayerFacing(target);
        if (facing != null) ty = yawOf(facing);

        Look.aim(player, ty, tp, ease);
        if (Look.error(player, ty, tp) > 3f) { align = 0; return; }
        if (++align < 2) return; // laisser partir le paquet de rotation avant de poser

        mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, new BlockHitResult(hit, f, s.neighbor(), false));
        player.swingHand(Hand.MAIN_HAND);
        cooldown = Config.human ? 1 + Look.RNG.nextInt(3) : 0;
    }

    private static Support findSupport(ClientWorld world, BlockPos pos) {
        for (Direction d : SUPPORT_ORDER) {
            BlockPos n = pos.offset(d);
            BlockState ns = world.getBlockState(n);
            if (ns.isAir() || ns.isReplaceable() || !ns.getFluidState().isEmpty()) continue;
            if (ns.getCollisionShape(world, n).isEmpty()) continue;
            if (interactive(ns)) continue;
            return new Support(n, d.getOpposite());
        }
        return null;
    }

    /** Blocs qui ouvrent une interface / reagissent au clic droit : on ne pose pas contre eux. */
    private static boolean interactive(BlockState s) {
        Block b = s.getBlock();
        return s.hasBlockEntity() || b instanceof DoorBlock || b instanceof TrapdoorBlock
                || b instanceof FenceGateBlock || b instanceof CraftingTableBlock || b instanceof AnvilBlock
                || b instanceof BedBlock || b instanceof LeverBlock || b instanceof NoteBlock;
    }

    private static boolean isTopHalf(BlockState s) {
        if (s.contains(Properties.BLOCK_HALF)) return s.get(Properties.BLOCK_HALF) == BlockHalf.TOP;
        if (s.contains(Properties.SLAB_TYPE)) return s.get(Properties.SLAB_TYPE) == SlabType.TOP;
        return false;
    }

    private static float yawOf(Direction d) {
        return switch (d) {
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> 270f;
            default -> 0f; // SOUTH
        };
    }

    /** Direction vers laquelle le joueur doit regarder pour que le bloc soit pose oriente. */
    private static Direction desiredPlayerFacing(BlockState s) {
        if (!s.contains(Properties.HORIZONTAL_FACING)) return null;
        Direction f = s.get(Properties.HORIZONTAL_FACING);
        Block b = s.getBlock();
        boolean same = b instanceof StairsBlock || b instanceof DoorBlock || b instanceof FenceGateBlock;
        return same ? f : f.getOpposite();
    }

    /** 0 = introuvable, 1 = pret en main, 2 = on vient de changer (attendre). */
    private static int ensureItem(MinecraftClient mc, ClientPlayerEntity player, Item item) {
        int sel = player.getInventory().selectedSlot;
        if (player.getInventory().getStack(sel).isOf(item)) return 1;

        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).isOf(item)) {
                player.getInventory().selectedSlot = i;
                return 2;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (player.getInventory().getStack(i).isOf(item)) {
                mc.interactionManager.clickSlot(player.playerScreenHandler.syncId, i, sel, SlotActionType.SWAP, player);
                return 2;
            }
        }
        if (player.isCreative()) {
            ItemStack st = new ItemStack(item, 64);
            player.getInventory().setStack(sel, st);
            mc.interactionManager.clickCreativeStack(st, 36 + sel);
            return 2;
        }
        return 0;
    }

    // --------------------------------------------------------------- minage

    private static void breakBlock(MinecraftClient mc, ClientPlayerEntity player, ClientWorld world,
                                   BlockPos pos, Vec3d center) {
        BlockState cur = world.getBlockState(pos);

        int best = player.getInventory().selectedSlot;
        float bestSpeed = player.getInventory().getStack(best).getMiningSpeedMultiplier(cur);
        for (int i = 0; i < 9; i++) {
            float sp = player.getInventory().getStack(i).getMiningSpeedMultiplier(cur);
            if (sp > bestSpeed) { bestSpeed = sp; best = i; }
        }
        if (best != player.getInventory().selectedSlot) {
            player.getInventory().selectedSlot = best;
            cooldown = 1;
            return;
        }

        Vec3d eye = player.getEyePos();
        Look.aim(player, Look.yawTo(eye, center), Look.pitchTo(eye, center), ease);

        HitResult hit = player.raycast(REACH + 0.3, 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            if (++misses > 60) giveUp(pos);
            return;
        }
        BlockHitResult b = (BlockHitResult) hit;
        BlockPos hp = b.getBlockPos();
        BlockState hs = world.getBlockState(hp);
        if (!BuildState.inSelection(hp) || hs.getHardness(world, hp) < 0) {
            giveUp(pos); // un bloc hors selection bloque la vue
            return;
        }
        mc.options.attackKey.setPressed(true); // le jeu mine ce que le viseur touche
        keysHeld = true;
    }

    // ------------------------------------------------------------ marche

    private static void walk(MinecraftClient mc, ClientPlayerEntity player, ClientWorld world, BlockPos target) {
        if (repath > 0) repath--;
        boolean placing = mode != Mode.CLEAR;

        if (path == null) {
            if (repath > 0) return;
            Vec3d c = Vec3d.ofCenter(target);
            path = Pathfinder.find(world, player, c, REACH - 0.7, feet -> {
                Vec3d e = new Vec3d(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
                if (e.distanceTo(c) > REACH - 0.5) return false;
                return !(placing && (feet.equals(target) || feet.up().equals(target)));
            });
            repath = 20;
            pathIdx = 0;
            stuckRef = null;
            stuckCount = 0;
            if (path == null) { giveUp(target); return; }
        }

        Vec3d pos = player.getPos();

        if (tick % 30 == 0) {
            if (stuckRef != null && pos.squaredDistanceTo(stuckRef) < 0.25) {
                stuckCount++;
                if (stuckCount == 1) { path = null; repath = 0; return; }
                if (stuckCount >= 3) { giveUp(target); return; }
            } else {
                stuckCount = 0;
            }
            stuckRef = pos;
        }

        while (pathIdx < path.size()) {
            BlockPos w = path.get(pathIdx);
            double dh = Math.hypot(w.getX() + 0.5 - pos.x, w.getZ() + 0.5 - pos.z);
            if (dh < 0.5 && Math.abs(w.getY() - pos.y) < 1.5) pathIdx++;
            else break;
        }
        if (pathIdx >= path.size()) {
            path = null;
            if (++arrived > 3) giveUp(target);
            return;
        }

        BlockPos wp = path.get(pathIdx);
        double wx = wp.getX() + 0.5, wz = wp.getZ() + 0.5;
        double dh = Math.hypot(wx - pos.x, wz - pos.z);
        if (dh > 3.5) { path = null; return; }

        float ty = (float) (Math.toDegrees(Math.atan2(wz - pos.z, wx - pos.x)) - 90.0);
        float walkPitch = Config.human ? 8f + 5f * (float) Math.sin(tick * 0.04) : 0f;
        Look.aim(player, ty, walkPitch, ease);
        float yawErr = Math.abs(MathHelper.wrapDegrees(ty - player.getYaw()));

        if (yawErr < 40f) {
            mc.options.forwardKey.setPressed(true);
            if (yawErr < 15f && path.size() - pathIdx > 3) mc.options.sprintKey.setPressed(true);
            keysHeld = true;
        }
        boolean needJump = wp.getY() > pos.y + 0.6 && dh < 1.6;
        if ((needJump || player.horizontalCollision) && player.isOnGround()) {
            mc.options.jumpKey.setPressed(true);
            keysHeld = true;
        }
    }

    private static void giveUp(BlockPos t) {
        skip.put(t.asLong(), tick + 600);
        current = null;
        path = null;
    }

    private static void releaseKeys(MinecraftClient mc) {
        if (!keysHeld || mc == null || mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.attackKey.setPressed(false);
        keysHeld = false;
    }
}
