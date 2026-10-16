package fr.chestesp;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ramassage automatique : quand une ancre cassee tombe pres de toi (que tu l'aies cassee a la main
 * ou non), le mod marche jusqu'a l'item. Il s'efface des que tu touches toi-meme aux touches de
 * deplacement. Inactif quand l'AutoFarm ou le Build tournent (ils ont leur propre gestion).
 */
public final class AutoPickup {

    private static final double RANGE = 12.0;
    private static final int TIMEOUT = 400;

    private static int tick = 0;
    private static int wait = 0;
    private static int ticks = 0;
    private static int target = -1;
    private static float ease = 0.25f;

    private static List<BlockPos> path = null;
    private static int pathIdx = 0;
    private static int repath = 0;
    private static Vec3d stuckRef = null;
    private static int stuckCount = 0;
    private static boolean keysHeld = false;

    private static final Set<Integer> ignored = new HashSet<>();

    private AutoPickup() {}

    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity player = mc.player;
        ClientWorld world = mc.world;

        if (!Config.autoPickup || Config.anchorFarm || BuildBot.mode != BuildBot.Mode.NONE
                || player == null || world == null || mc.currentScreen != null) {
            release(mc, false);
            target = -1;
            path = null;
            return;
        }
        if (userMoving(mc)) { // le joueur reprend la main
            release(mc, true);
            path = null;
            return;
        }

        tick++;
        release(mc, false);

        ItemEntity item = find(world, player);
        if (item == null) {
            target = -1;
            path = null;
            return;
        }

        if (item.getId() != target) {
            target = item.getId();
            ticks = 0;
            path = null;
            pathIdx = 0;
            repath = 0;
            stuckRef = null;
            stuckCount = 0;
            ease = 0.18f + Look.RNG.nextFloat() * 0.15f;
            wait = Config.human ? 6 + Look.RNG.nextInt(9) : 0;
            return;
        }
        if (wait > 0) { wait--; return; }
        if (++ticks > TIMEOUT) {
            ignored.add(target);
            target = -1;
            path = null;
            return;
        }

        Vec3d ip = item.getPos();
        double dh = Math.hypot(ip.x - player.getX(), ip.z - player.getZ());

        // Tout pres : on fonce dessus
        if (dh < 1.2 && Math.abs(ip.y - player.getY()) < 2.0) {
            float ty = Look.yawTo(player.getEyePos(), ip);
            Look.aim(player, ty, walkPitch(), ease);
            press(mc, player, false);
            return;
        }

        if (repath > 0) repath--;
        if (path == null) {
            if (repath > 0) return;
            path = Pathfinder.find(world, player, ip, 0.8, feet ->
                    Math.abs(feet.getX() + 0.5 - ip.x) < 1.0
                            && Math.abs(feet.getZ() + 0.5 - ip.z) < 1.0
                            && Math.abs(feet.getY() - ip.y) <= 1.5);
            repath = 20;
            pathIdx = 0;
            stuckRef = null;
            stuckCount = 0;
            if (path == null) { // inaccessible
                ignored.add(target);
                target = -1;
                return;
            }
        }

        Vec3d pos = player.getPos();

        if (tick % 30 == 0) {
            if (stuckRef != null && pos.squaredDistanceTo(stuckRef) < 0.25) {
                stuckCount++;
                if (stuckCount == 1) { path = null; repath = 0; return; }
                if (stuckCount >= 3) {
                    ignored.add(target);
                    target = -1;
                    path = null;
                    return;
                }
            } else {
                stuckCount = 0;
            }
            stuckRef = pos;
        }

        while (pathIdx < path.size()) {
            BlockPos w = path.get(pathIdx);
            double d = Math.hypot(w.getX() + 0.5 - pos.x, w.getZ() + 0.5 - pos.z);
            if (d < 0.5 && Math.abs(w.getY() - pos.y) < 1.5) pathIdx++;
            else break;
        }
        if (pathIdx >= path.size()) { // chemin fini mais item pas ramasse : on refait le chemin
            path = null;
            return;
        }

        BlockPos wp = path.get(pathIdx);
        double wx = wp.getX() + 0.5, wz = wp.getZ() + 0.5;
        double d = Math.hypot(wx - pos.x, wz - pos.z);
        if (d > 3.5) { path = null; return; }

        float ty = (float) (Math.toDegrees(Math.atan2(wz - pos.z, wx - pos.x)) - 90.0);
        Look.aim(player, ty, walkPitch(), ease);
        float yawErr = Math.abs(MathHelper.wrapDegrees(ty - player.getYaw()));
        if (yawErr < 40f) {
            press(mc, player, yawErr < 15f && path.size() - pathIdx > 3);
        }
        boolean needJump = wp.getY() > pos.y + 0.6 && d < 1.6;
        if ((needJump || player.horizontalCollision) && player.isOnGround()) {
            mc.options.jumpKey.setPressed(true);
            keysHeld = true;
        }
    }

    private static ItemEntity find(ClientWorld world, ClientPlayerEntity player) {
        Box box = player.getBoundingBox().expand(RANGE);
        List<ItemEntity> items = world.getEntitiesByClass(ItemEntity.class, box,
                e -> e.getStack().isOf(Items.RESPAWN_ANCHOR) && !ignored.contains(e.getId()));
        ItemEntity best = null;
        double bd = Double.MAX_VALUE;
        for (ItemEntity e : items) {
            double d = e.squaredDistanceTo(player);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    private static float walkPitch() {
        return Config.human ? 8f + 5f * (float) Math.sin(tick * 0.04) : 0f;
    }

    private static void press(MinecraftClient mc, ClientPlayerEntity player, boolean sprint) {
        mc.options.forwardKey.setPressed(true);
        if (sprint) mc.options.sprintKey.setPressed(true);
        keysHeld = true;
    }

    /** Le joueur appuie-t-il lui-meme sur une touche de deplacement (etat physique du clavier) ? */
    private static boolean userMoving(MinecraftClient mc) {
        long handle = mc.getWindow().getHandle();
        KeyBinding[] keys = {mc.options.forwardKey, mc.options.backKey, mc.options.leftKey, mc.options.rightKey};
        for (KeyBinding k : keys) {
            InputUtil.Key key = InputUtil.fromTranslationKey(k.getBoundKeyTranslationKey());
            if (key.getCategory() == InputUtil.Type.KEYSYM && InputUtil.isKeyPressed(handle, key.getCode())) {
                return true;
            }
        }
        return false;
    }

    private static void release(MinecraftClient mc, boolean resync) {
        if (!keysHeld) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        keysHeld = false;
        if (resync) KeyBinding.updatePressedStates(); // rend la main aux vraies touches du joueur
    }
}
