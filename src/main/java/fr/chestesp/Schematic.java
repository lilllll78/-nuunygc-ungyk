package fr.chestesp;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Schema charge en memoire. Formats : .litematic, .schem (Sponge), .nbt (structure vanilla). */
public final class Schematic {

    public final int sx, sy, sz;
    public final BlockState[] palette;
    public final int[] data; // index = (y * sz + z) * sx + x

    public Schematic(int sx, int sy, int sz, BlockState[] palette, int[] data) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.palette = palette;
        this.data = data;
    }

    // ------------------------------------------------------------------ chargement

    public static Schematic load(Path file) throws IOException {
        String n = file.getFileName().toString().toLowerCase();
        NbtCompound root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
        if (n.endsWith(".litematic")) return fromLitematic(root);
        if (n.endsWith(".schem")) return fromSponge(root);
        if (n.endsWith(".nbt")) return fromStructure(root);
        throw new IOException("Format non supporté (.litematic, .schem, .nbt)");
    }

    // ---- Litematica

    private record Region(NbtCompound c, int ox, int oy, int oz, int ax, int ay, int az) {}

    private static Schematic fromLitematic(NbtCompound root) throws IOException {
        NbtCompound regions = root.getCompound("Regions");
        List<Region> list = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (String key : regions.getKeys()) {
            NbtCompound c = regions.getCompound(key);
            NbtCompound pos = c.getCompound("Position");
            NbtCompound size = c.getCompound("Size");
            int sx = size.getInt("x"), sy = size.getInt("y"), sz = size.getInt("z");
            int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
            if (ax == 0 || ay == 0 || az == 0) continue;
            int ox = pos.getInt("x") + (sx < 0 ? sx + 1 : 0);
            int oy = pos.getInt("y") + (sy < 0 ? sy + 1 : 0);
            int oz = pos.getInt("z") + (sz < 0 ? sz + 1 : 0);
            list.add(new Region(c, ox, oy, oz, ax, ay, az));
            minX = Math.min(minX, ox); minY = Math.min(minY, oy); minZ = Math.min(minZ, oz);
            maxX = Math.max(maxX, ox + ax); maxY = Math.max(maxY, oy + ay); maxZ = Math.max(maxZ, oz + az);
        }
        if (list.isEmpty()) throw new IOException("Schéma vide");

        int gx = maxX - minX, gy = maxY - minY, gz = maxZ - minZ;
        if ((long) gx * gy * gz > 60_000_000L) throw new IOException("Schéma trop grand");

        List<BlockState> global = new ArrayList<>();
        Map<BlockState, Integer> index = new HashMap<>();
        BlockState air = Blocks.AIR.getDefaultState();
        global.add(air);
        index.put(air, 0);

        int[] data = new int[gx * gy * gz];

        for (Region r : list) {
            NbtList pal = r.c().getList("BlockStatePalette", NbtElement.COMPOUND_TYPE);
            int[] map = new int[pal.size()];
            for (int i = 0; i < pal.size(); i++) {
                BlockState st = fromPaletteEntry(pal.getCompound(i));
                Integer id = index.get(st);
                if (id == null) {
                    id = global.size();
                    global.add(st);
                    index.put(st, id);
                }
                map[i] = id;
            }
            long[] arr = r.c().getLongArray("BlockStates");
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, pal.size() - 1)));
            long mask = (1L << bits) - 1L;
            int volume = r.ax() * r.ay() * r.az();

            for (int i = 0; i < volume; i++) {
                long bitIdx = (long) i * bits;
                int start = (int) (bitIdx >> 6);
                int end = (int) (((long) (i + 1) * bits - 1) >> 6);
                int sh = (int) (bitIdx & 63);
                if (end >= arr.length) break;
                long v;
                if (start == end) v = (arr[start] >>> sh) & mask;
                else v = ((arr[start] >>> sh) | (arr[end] << (64 - sh))) & mask;
                int pid = (int) v;
                if (pid >= map.length) continue;

                int lx = i % r.ax();
                int lz = (i / r.ax()) % r.az();
                int ly = i / (r.ax() * r.az());
                int x = r.ox() - minX + lx, y = r.oy() - minY + ly, z = r.oz() - minZ + lz;
                data[(y * gz + z) * gx + x] = map[pid];
            }
        }
        return new Schematic(gx, gy, gz, global.toArray(new BlockState[0]), data);
    }

    // ---- Sponge (.schem v2 / v3)

    private static Schematic fromSponge(NbtCompound root) throws IOException {
        NbtCompound r = root.contains("Schematic") ? root.getCompound("Schematic") : root;
        int w = r.getShort("Width") & 0xFFFF;
        int h = r.getShort("Height") & 0xFFFF;
        int l = r.getShort("Length") & 0xFFFF;
        if (w == 0 || h == 0 || l == 0) throw new IOException("Dimensions invalides");

        NbtCompound blocks = r.contains("Blocks") ? r.getCompound("Blocks") : r;
        NbtCompound pal = blocks.getCompound("Palette");
        byte[] bd = blocks.contains("BlockData") ? blocks.getByteArray("BlockData") : blocks.getByteArray("Data");

        int max = 0;
        for (String k : pal.getKeys()) max = Math.max(max, pal.getInt(k));
        BlockState[] palette = new BlockState[max + 1];
        for (String k : pal.getKeys()) palette[pal.getInt(k)] = parseString(k);
        for (int i = 0; i < palette.length; i++) if (palette[i] == null) palette[i] = Blocks.AIR.getDefaultState();

        int[] data = new int[w * h * l];
        int idx = 0, i = 0;
        while (i < bd.length && idx < data.length) {
            int val = 0, shift = 0;
            while (i < bd.length) {
                int b = bd[i++] & 0xFF;
                val |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            data[idx++] = val;
        }
        return new Schematic(w, h, l, palette, data);
    }

    // ---- Structure vanilla (.nbt)

    private static Schematic fromStructure(NbtCompound root) throws IOException {
        NbtList size = root.getList("size", NbtElement.INT_TYPE);
        int sx = size.getInt(0), sy = size.getInt(1), sz = size.getInt(2);
        if (sx <= 0 || sy <= 0 || sz <= 0) throw new IOException("Dimensions invalides");

        NbtList pal = root.getList("palette", NbtElement.COMPOUND_TYPE);
        if (pal.isEmpty() && root.contains("palettes")) {
            pal = root.getList("palettes", NbtElement.LIST_TYPE).getList(0);
        }
        BlockState[] palette = new BlockState[pal.size() + 1];
        for (int i = 0; i < pal.size(); i++) palette[i] = fromPaletteEntry(pal.getCompound(i));
        int airIdx = pal.size();
        palette[airIdx] = Blocks.AIR.getDefaultState();

        int[] data = new int[sx * sy * sz];
        java.util.Arrays.fill(data, airIdx);

        NbtList blocks = root.getList("blocks", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < blocks.size(); i++) {
            NbtCompound b = blocks.getCompound(i);
            NbtList p = b.getList("pos", NbtElement.INT_TYPE);
            int x = p.getInt(0), y = p.getInt(1), z = p.getInt(2);
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) continue;
            data[(y * sz + z) * sx + x] = b.getInt("state");
        }
        return new Schematic(sx, sy, sz, palette, data);
    }

    // ------------------------------------------------------------------ etats

    private static BlockState fromPaletteEntry(NbtCompound c) {
        Map<String, String> props = new HashMap<>();
        if (c.contains("Properties")) {
            NbtCompound p = c.getCompound("Properties");
            for (String k : p.getKeys()) props.put(k, p.getString(k));
        }
        return state(c.getString("Name"), props);
    }

    private static BlockState parseString(String s) {
        int i = s.indexOf('[');
        String name = i < 0 ? s : s.substring(0, i);
        Map<String, String> props = new HashMap<>();
        if (i >= 0 && s.endsWith("]")) {
            String body = s.substring(i + 1, s.length() - 1);
            for (String kv : body.split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) props.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        return state(name, props);
    }

    private static BlockState state(String name, Map<String, String> props) {
        try {
            Block b = Registries.BLOCK.get(Identifier.of(name));
            BlockState st = b.getDefaultState();
            for (Map.Entry<String, String> e : props.entrySet()) {
                Property<?> p = b.getStateManager().getProperty(e.getKey());
                if (p != null) st = apply(st, p, e.getValue());
            }
            return st;
        } catch (Exception ex) {
            return Blocks.AIR.getDefaultState();
        }
    }

    private static <T extends Comparable<T>> BlockState apply(BlockState st, Property<T> p, String v) {
        Optional<T> o = p.parse(v);
        return o.isPresent() ? st.with(p, o.get()) : st;
    }
}
