package fr.chestesp;

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** ESP sur les blocs choisis par le joueur (liste stockee dans Config.customBlocks). */
public final class CustomEsp {

    private static final int MAX_RESULTS = 4000;
    private static final long BUDGET_NANOS = 8_000_000L; // 8 ms max par scan

    /** Positions trouvees (rafraichies toutes les 20 ticks). */
    public static volatile List<BlockPos> positions = new ArrayList<>();

    private static String cachedKey = null;
    private static Set<Block> cached = new HashSet<>();
    private static int cachedRadius = -1;
    private static List<int[]> offsets = new ArrayList<>();

    private CustomEsp() {}

    // ------------------------------------------------------------ selection

    public static Set<Block> blocks() {
        String key = Config.customBlocks == null ? "" : Config.customBlocks;
        if (!key.equals(cachedKey)) {
            Set<Block> set = new HashSet<>();
            for (String id : key.split(",")) {
                id = id.trim();
                if (id.isEmpty()) continue;
                Identifier ident = Identifier.tryParse(id);
                if (ident != null && Registries.BLOCK.containsId(ident)) set.add(Registries.BLOCK.get(ident));
            }
            cached = set;
            cachedKey = key;
        }
        return cached;
    }

    public static boolean selected(Block b) {
        return blocks().contains(b);
    }

    public static int count() {
        return blocks().size();
    }

    public static void toggle(Block b) {
        String id = Registries.BLOCK.getId(b).toString();
        Set<String> ids = new LinkedHashSet<>();
        for (String s : (Config.customBlocks == null ? "" : Config.customBlocks).split(",")) {
            if (!s.trim().isEmpty()) ids.add(s.trim());
        }
        if (!ids.remove(id)) ids.add(id);
        Config.customBlocks = String.join(",", ids);
        positions = new ArrayList<>();
    }

    public static void clear() {
        Config.customBlocks = "";
        positions = new ArrayList<>();
    }

    public static String summary() {
        Set<Block> set = blocks();
        if (set.isEmpty()) return "Aucun bloc choisi";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Block b : set) {
            if (n > 0) sb.append(", ");
            if (n == 3) { sb.append("+").append(set.size() - 3); break; }
            sb.append(b.getName().getString());
            n++;
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------- scan

    private static void buildOffsets(int r) {
        if (r == cachedRadius) return;
        List<int[]> list = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) list.add(new int[]{dx, dz});
        }
        list.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        offsets = list;
        cachedRadius = r;
    }

    public static void scan(MinecraftClient mc) {
        ClientWorld world = mc.world;
        Set<Block> wanted = blocks();
        if (world == null || mc.player == null || wanted.isEmpty()) {
            positions = new ArrayList<>();
            return;
        }

        int r = Math.min(mc.options.getViewDistance().getValue(), (int) Math.ceil(Config.espRange / 16.0) + 1);
        r = Math.max(2, Math.min(24, r));
        buildOffsets(r);

        long start = System.nanoTime();
        List<BlockPos> found = new ArrayList<>();
        int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z;
        double maxD = Config.espRange;
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();

        for (int[] o : offsets) {
            if (found.size() >= MAX_RESULTS || System.nanoTime() - start > BUDGET_NANOS) break;
            WorldChunk chunk = world.getChunkManager().getWorldChunk(pcx + o[0], pcz + o[1]);
            if (chunk == null) continue;
            ChunkSection[] sections = chunk.getSectionArray();
            int sx = chunk.getPos().getStartX(), sz = chunk.getPos().getStartZ();
            for (int i = 0; i < sections.length; i++) {
                ChunkSection s = sections[i];
                if (s == null || s.isEmpty()) continue;
                if (!s.hasAny(st -> wanted.contains(st.getBlock()))) continue;
                int baseY = world.getBottomY() + i * 16;
                for (int lx = 0; lx < 16; lx++) {
                    for (int ly = 0; ly < 16; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                            if (!wanted.contains(s.getBlockState(lx, ly, lz).getBlock())) continue;
                            double dx = sx + lx + 0.5 - px, dy = baseY + ly + 0.5 - py, dz = sz + lz + 0.5 - pz;
                            if (dx * dx + dy * dy + dz * dz > maxD * maxD) continue;
                            found.add(new BlockPos(sx + lx, baseY + ly, sz + lz));
                            if (found.size() >= MAX_RESULTS) break;
                        }
                        if (found.size() >= MAX_RESULTS) break;
                    }
                    if (found.size() >= MAX_RESULTS) break;
                }
            }
        }
        positions = found;
    }
}
