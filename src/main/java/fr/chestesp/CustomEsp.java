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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** ESP sur les blocs choisis par le joueur (liste stockee dans Config.customBlocks). */
public final class CustomEsp {

    private static final int MAX_RESULTS = 6000;

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
    }

    public static void clear() {
        Config.customBlocks = "";
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

    // ------------------------------------------------- scan progressif par chunk

    private static final long BUDGET_NANOS = 4_000_000L;   // 4 ms max par tick
    private static final int RESCAN_TICKS = 300;           // un chunk est re-analyse toutes les 15 s
    private static final int PER_CHUNK_CAP = 1500;         // garde-fou pour les blocs tres courants
    private static final int MAX_CACHED = 60000;

    private record Entry(int cx, int cz, List<BlockPos> list, int at) {}

    private static final Map<Long, Entry> cache = new HashMap<>();
    private static int cachedCount = 0;
    private static int tickNo = 0;
    private static String lastKey = null;
    private static ClientWorld lastWorld = null;

    /** Etat affiche dans le menu. */
    public static volatile int scannedChunks = 0;
    public static volatile int totalChunks = 0;
    public static volatile int found = 0;

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    private static void reset() {
        cache.clear();
        cachedCount = 0;
        positions = new ArrayList<>();
        scannedChunks = 0;
        found = 0;
    }

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

    /** A appeler a chaque tick : analyse les chunks (du plus proche au plus lointain), toutes les hauteurs. */
    public static void tick(MinecraftClient mc) {
        ClientWorld world = mc.world;
        if (world == null || mc.player == null) return;

        String k = Config.customBlocks == null ? "" : Config.customBlocks;
        if (world != lastWorld || !k.equals(lastKey)) {
            lastWorld = world;
            lastKey = k;
            reset();
        }
        Set<Block> wanted = blocks();
        if (wanted.isEmpty()) {
            positions = new ArrayList<>();
            return;
        }

        tickNo++;
        int r = Math.min(mc.options.getViewDistance().getValue(), (int) Math.ceil(Config.espRange / 16.0) + 1);
        r = Math.max(2, Math.min(24, r));
        buildOffsets(r);

        int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z;
        long start = System.nanoTime();
        int done = 0;

        for (int[] o : offsets) {
            int cx = pcx + o[0], cz = pcz + o[1];
            long ck = key(cx, cz);
            WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
            if (chunk == null) {
                Entry old = cache.remove(ck);
                if (old != null) cachedCount -= old.list().size();
                continue;
            }
            Entry e = cache.get(ck);
            if (e != null && tickNo - e.at() < RESCAN_TICKS) { done++; continue; }
            if (System.nanoTime() - start > BUDGET_NANOS || cachedCount > MAX_CACHED) {
                if (e != null) done++;
                continue; // on reprend au tick suivant
            }
            List<BlockPos> list = scanChunk(world, chunk, wanted);
            if (e != null) cachedCount -= e.list().size();
            cache.put(ck, new Entry(cx, cz, list, tickNo));
            cachedCount += list.size();
            done++;
        }
        scannedChunks = done;
        totalChunks = offsets.size();

        if (tickNo % 100 == 0) { // oublier les chunks devenus trop loin
            final int rr = r;
            cache.values().removeIf(e -> {
                boolean far = Math.abs(e.cx() - pcx) > rr + 1 || Math.abs(e.cz() - pcz) > rr + 1;
                if (far) cachedCount -= e.list().size();
                return far;
            });
        }
        if (tickNo % 10 == 0) publish(mc);
    }

    private static List<BlockPos> scanChunk(ClientWorld world, WorldChunk chunk, Set<Block> wanted) {
        List<BlockPos> out = new ArrayList<>();
        ChunkSection[] sections = chunk.getSectionArray();
        int sx = chunk.getPos().getStartX(), sz = chunk.getPos().getStartZ();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection s = sections[i];
            if (s == null || s.isEmpty()) continue;
            if (!s.hasAny(st -> wanted.contains(st.getBlock()))) continue;
            int baseY = world.getBottomY() + i * 16;
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        if (wanted.contains(s.getBlockState(lx, ly, lz).getBlock())) {
                            out.add(new BlockPos(sx + lx, baseY + ly, sz + lz));
                            if (out.size() >= PER_CHUNK_CAP) return out;
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Rassemble tout ce qui est en portee, les plus proches en premier. */
    private static void publish(MinecraftClient mc) {
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        List<BlockPos> all = new ArrayList<>();
        for (Entry e : cache.values()) {
            for (BlockPos p : e.list()) {
                if (Config.espInRange(p.getX() + 0.5 - px, p.getY() + 0.5 - py, p.getZ() + 0.5 - pz)) all.add(p);
            }
        }
        found = all.size();
        all.sort(Comparator.comparingDouble(p -> {
            double dx = p.getX() + 0.5 - px, dz = p.getZ() + 0.5 - pz;
            return dx * dx + dz * dz;
        }));
        positions = all.size() > MAX_RESULTS ? new ArrayList<>(all.subList(0, MAX_RESULTS)) : all;
    }
}
