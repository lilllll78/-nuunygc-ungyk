package fr.chestesp;

import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

/** Options modifiables depuis le menu. */
public final class Config {
    public static boolean chestEsp = true;
    public static boolean customEsp = true;
    /** Blocs du Custom ESP (identifiants separes par des virgules). */
    public static String customBlocks = "minecraft:respawn_anchor";
    public static boolean playerEsp = true;
    public static boolean tracers = true;
    public static boolean anchorFarm = false;
    /** Rotations fluides, delais de reaction, petites pauses. */
    public static boolean human = true;
    // --- PvP / HUD ---
    public static boolean hudFps = true;
    public static boolean hudCps = true;
    public static boolean hudCoords = false;
    public static boolean hudKeystrokes = false;
    public static boolean hudArmor = true;
    public static boolean toggleSprint = false;
    public static boolean zoom = true;

    /** Ramasse seul les ancres cassees qui tombent pres de toi. */
    public static boolean autoPickup = true;
    /** Plus d'ancres -> /rtp. */
    public static boolean autoRtp = true;
    /** Danger -> /home 1. */
    public static boolean autoHome = true;

    /** Portee d'affichage de l'ESP (blocs). */
    public static double espRange = 128.0;
    /** Si vrai, la portee ne compte que l'horizontale : on voit de la surface jusqu'a la bedrock. */
    public static boolean espUnlimitedY = true;
    /** Portee de recherche de l'AutoFarm (blocs). */
    public static double farmRange = 128.0;
    /** Vie (en demi-coeurs) en dessous de laquelle on envoie /home 1. */
    public static float homeHp = 6f;

    // --- Theme (RGB sans alpha) ---
    public static int accent = 0xA855F7;
    public static int chestColor = 0xFFA61A;
    public static int customColor = 0x1AF2FF;
    public static int playerColor = 0xFF3333;
    /** Opacite du fond des menus RoseClient (40..100). */
    public static double uiOpacity = 95.0;
    /** Halo anime sur les bordures. */
    public static boolean glow = true;
    /** Decor sakura sur l'ecran titre et le menu pause. */
    public static boolean sakuraDecor = true;

    private static final Set<String> NO_SAVE = Set.of("anchorFarm");

    private Config() {}

    /** Le point (dx, dy, dz) par rapport au joueur est-il dans la portee de l'ESP ? */
    public static boolean espInRange(double dx, double dy, double dz) {
        double h2 = dx * dx + dz * dz;
        double r2 = espRange * espRange;
        return espUnlimitedY ? h2 <= r2 : h2 + dy * dy <= r2;
    }

    public static void resetTheme() {
        accent = 0xA855F7;
        chestColor = 0xFFA61A;
        customColor = 0x1AF2FF;
        playerColor = 0xFF3333;
        uiOpacity = 95.0;
        glow = true;
        sakuraDecor = true;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("roseclient.properties");
    }

    public static void save() {
        try {
            Properties p = new Properties();
            for (Field f : Config.class.getDeclaredFields()) {
                int m = f.getModifiers();
                if (!Modifier.isStatic(m) || Modifier.isFinal(m) || !Modifier.isPublic(m)) continue;
                if (NO_SAVE.contains(f.getName())) continue;
                p.setProperty(f.getName(), String.valueOf(f.get(null)));
            }
            try (OutputStream o = Files.newOutputStream(file())) {
                p.store(o, "RoseClient");
            }
        } catch (Exception ignored) {
        }
    }

    public static void load() {
        try {
            if (!Files.exists(file())) return;
            Properties p = new Properties();
            try (InputStream i = Files.newInputStream(file())) {
                p.load(i);
            }
            for (Field f : Config.class.getDeclaredFields()) {
                int m = f.getModifiers();
                if (!Modifier.isStatic(m) || Modifier.isFinal(m) || !Modifier.isPublic(m)) continue;
                if (NO_SAVE.contains(f.getName())) continue;
                String v = p.getProperty(f.getName());
                if (v == null) continue;
                try {
                    Class<?> t = f.getType();
                    if (t == boolean.class) f.setBoolean(null, Boolean.parseBoolean(v));
                    else if (t == int.class) f.setInt(null, Integer.parseInt(v));
                    else if (t == double.class) f.setDouble(null, Double.parseDouble(v));
                    else if (t == float.class) f.setFloat(null, Float.parseFloat(v));
                    else if (t == String.class) f.set(null, v);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }
}
