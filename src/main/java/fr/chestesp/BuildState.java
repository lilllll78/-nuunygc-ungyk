package fr.chestesp;

import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Etat partage : schema charge, origine, rotation, selection, plan fantome. */
public final class BuildState {

    public static Schematic schem = null;
    public static String schemName = "";
    public static BlockPos origin = null;
    public static int rot = 0; // 0, 1=90, 2=180, 3=270
    public static boolean showGhost = true;

    public static BlockPos pos1 = null;
    public static BlockPos pos2 = null;

    public static volatile List<BlockPos> missing = List.of();
    public static volatile List<BlockPos> wrong = List.of();

    private static BlockState[][] rotCache = new BlockState[4][];

    private BuildState() {}

    // ------------------------------------------------------------ schema

    public static void setSchematic(Schematic s, String name) {
        schem = s;
        schemName = name;
        rotCache = new BlockState[4][];
        missing = List.of();
        wrong = List.of();
    }

    public static void setRotation(int r) {
        rot = ((r % 4) + 4) % 4;
        missing = List.of();
        wrong = List.of();
    }

    public static int worldW() {
        return schem == null ? 0 : (rot % 2 == 1 ? schem.sz : schem.sx);
    }

    public static int worldD() {
        return schem == null ? 0 : (rot % 2 == 1 ? schem.sx : schem.sz);
    }

    /** Palette tournee ; null = ne rien poser (air, moitie haute de porte, tete de lit). */
    private static BlockState[] rotated() {
        BlockState[] c = rotCache[rot];
        if (c == null) {
            BlockRotation br = switch (rot) {
                case 1 -> BlockRotation.CLOCKWISE_90;
                case 2 -> BlockRotation.CLOCKWISE_180;
                case 3 -> BlockRotation.COUNTERCLOCKWISE_90;
                default -> BlockRotation.NONE;
            };
            c = new BlockState[schem.palette.length];
            for (int i = 0; i < c.length; i++) {
                BlockState st = schem.palette[i];
                if (st == null || st.isAir()) continue;
                if (st.contains(Properties.DOUBLE_BLOCK_HALF)
                        && st.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) continue;
                if (st.contains(Properties.BED_PART) && st.get(Properties.BED_PART) == BedPart.HEAD) continue;
                c[i] = st.rotate(br);
            }
            rotCache[rot] = c;
        }
        return c;
    }

    /** Etat voulu a cette position du monde, ou null. */
    public static BlockState targetAt(int x, int y, int z) {
        if (schem == null || origin == null) return null;
        int dx = x - origin.getX(), dy = y - origin.getY(), dz = z - origin.getZ();
        if (dy < 0 || dy >= schem.sy) return null;
        int lx, lz;
        switch (rot) {
            case 1 -> {
                if (dx < 0 || dx >= schem.sz || dz < 0 || dz >= schem.sx) return null;
                lx = dz;
                lz = schem.sz - 1 - dx;
            }
            case 2 -> {
                if (dx < 0 || dx >= schem.sx || dz < 0 || dz >= schem.sz) return null;
                lx = schem.sx - 1 - dx;
                lz = schem.sz - 1 - dz;
            }
            case 3 -> {
                if (dx < 0 || dx >= schem.sz || dz < 0 || dz >= schem.sx) return null;
                lx = schem.sx - 1 - dz;
                lz = dx;
            }
            default -> {
                if (dx < 0 || dx >= schem.sx || dz < 0 || dz >= schem.sz) return null;
                lx = dx;
                lz = dz;
            }
        }
        int id = schem.data[(dy * schem.sz + lz) * schem.sx + lx];
        BlockState[] pal = rotated();
        return id >= 0 && id < pal.length ? pal[id] : null;
    }

    public static BlockState targetAt(BlockPos p) {
        return targetAt(p.getX(), p.getY(), p.getZ());
    }

    /** Boite monde du schema : {minX, minY, minZ, maxX, maxY, maxZ} inclusif, ou null. */
    public static int[] schemBounds() {
        if (schem == null || origin == null) return null;
        return new int[]{origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + worldW() - 1, origin.getY() + schem.sy - 1, origin.getZ() + worldD() - 1};
    }

    // --------------------------------------------------------- selection

    public static boolean hasSelection() {
        return pos1 != null && pos2 != null;
    }

    /** {minX, minY, minZ, maxX, maxY, maxZ} inclusif, ou null. */
    public static int[] selBounds() {
        if (!hasSelection()) return null;
        return new int[]{Math.min(pos1.getX(), pos2.getX()), Math.min(pos1.getY(), pos2.getY()), Math.min(pos1.getZ(), pos2.getZ()),
                Math.max(pos1.getX(), pos2.getX()), Math.max(pos1.getY(), pos2.getY()), Math.max(pos1.getZ(), pos2.getZ())};
    }

    public static boolean inSelection(BlockPos p) {
        int[] b = selBounds();
        return b != null && p.getX() >= b[0] && p.getX() <= b[3] && p.getY() >= b[1] && p.getY() <= b[4]
                && p.getZ() >= b[2] && p.getZ() <= b[5];
    }

    public static void setCorner(MinecraftClient mc, int which) {
        BlockPos p = null;
        if (mc.crosshairTarget instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) {
            p = b.getBlockPos();
        } else if (mc.player != null) {
            p = mc.player.getBlockPos();
        }
        if (p == null) return;
        if (which == 1) pos1 = p; else pos2 = p;
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("Coin " + which + " : " + p.getX() + " " + p.getY() + " " + p.getZ()), true);
        }
    }

    // ------------------------------------------------------ plan fantome

    private static final int GHOST_RADIUS = 24;

    public static void scanGhost(ClientWorld world, ClientPlayerEntity player) {
        int[] sb = schemBounds();
        if (!showGhost || sb == null) {
            missing = List.of();
            wrong = List.of();
            return;
        }
        int px = player.getBlockX(), py = player.getBlockY(), pz = player.getBlockZ();
        int minX = Math.max(sb[0], px - GHOST_RADIUS), maxX = Math.min(sb[3], px + GHOST_RADIUS);
        int minY = Math.max(Math.max(sb[1], py - GHOST_RADIUS), world.getBottomY());
        int maxY = Math.min(Math.min(sb[4], py + GHOST_RADIUS), world.getBottomY() + world.getHeight() - 1);
        int minZ = Math.max(sb[2], pz - GHOST_RADIUS), maxZ = Math.min(sb[5], pz + GHOST_RADIUS);

        List<BlockPos> m = new ArrayList<>();
        List<BlockPos> w = new ArrayList<>();
        BlockPos.Mutable mp = new BlockPos.Mutable();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockState t = targetAt(x, y, z);
                    if (t == null) continue;
                    BlockState cur = world.getBlockState(mp.set(x, y, z));
                    if (cur.getBlock() == t.getBlock()) continue;
                    if (cur.isAir() || cur.isReplaceable()) m.add(new BlockPos(x, y, z));
                    else w.add(new BlockPos(x, y, z));
                }
            }
        }
        missing = cap(m, player, 3000);
        wrong = cap(w, player, 1500);
    }

    private static List<BlockPos> cap(List<BlockPos> l, ClientPlayerEntity player, int max) {
        if (l.size() <= max) return l;
        BlockPos pp = player.getBlockPos();
        l.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(pp)));
        return new ArrayList<>(l.subList(0, max));
    }
}
