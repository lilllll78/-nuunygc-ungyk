package fr.chestesp;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Auto farm des Respawn Anchors avec deplacement par pathfinding et comportement "humain" :
 * rotations fluides, delais de reaction, pauses aleatoires, point de visee variable.
 */
public final class AnchorFarm {

    private static final double REACH = 3.4;
    private static final double MAX_RANGE = 128.0;
    private static final double ITEM_RANGE = 16.0;
    private static final Random rng = new Random();

    private enum Nav { MOVING, ARRIVED, FAILED }
    private record Goal(Vec3d center, double slack, Predicate<BlockPos> test) {}

    // --- etat ---
    private static int tick = 0;
    private static int pause = 0;
    private static int wait = 0;
    private static boolean keysHeld = false;

    private static BlockPos anchorTarget = null;
    private static int itemTarget = -1;
    private static int itemTicks = 0;
    private static int arrivedFails = 0;
    private static BlockPos brokenPos = null;
    private static int brokenTick = 0;
    private static int rejects = 0;
    private static String lastInfo = "";
    public static int mined = 0;

    // --- /rtp et securite ---
    private static int idleTicks = 0;
    private static int nextRtpTick = 0;
    private static int rtpWait = 0;
    private static int rtpFails = 0;
    private static Vec3d rtpFrom = null;
    private static float healthRef = 20f;
    private static int dangerTicks = 0;
    private static double arrivedDist = 1e9;
    private static int aimMisses = 0;

    private static List<BlockPos> path = null;
    private static int pathIdx = 0;
    private static int repath = 0;

    private static Vec3d stuckRef = null;
    private static int stuckCount = 0;

    private static Vec3d aimOffset = Vec3d.ZERO;
    private static float aimEase = 0.25f;

    private static final Set<Integer> ignoredItems = new HashSet<>();
    private static final Map<Long, Integer> unreachable = new HashMap<>();

    private AnchorFarm() {}

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity player = mc.player;
        ClientWorld world = mc.world;

        if (!Config.anchorFarm || player == null || world == null
                || mc.interactionManager == null || mc.currentScreen != null) {
            releaseKeys(mc);
            if (!Config.anchorFarm) resetState();
            return;
        }

        tick++;
        releaseKeys(mc);

        // Le bloc est-il revenu apres qu'on l'a casse ? => le serveur a refuse le cassage
        if (brokenPos != null) {
            if (world.getBlockState(brokenPos).isOf(Blocks.RESPAWN_ANCHOR)) {
                // Zone probablement protegee : on ignore toutes les ancres dans 16 blocs
                for (BlockPos a : ChestEspClient.anchors) {
                    if (a.getSquaredDistance(brokenPos) <= 16.0 * 16.0) unreachable.put(a.asLong(), tick + 12000);
                }
                unreachable.put(brokenPos.asLong(), tick + 12000);
                brokenPos = null;
                rejects++;
                path = null;
                if (rejects >= 5) {
                    Config.anchorFarm = false;
                    player.sendMessage(Text.literal("AutoFarm arrêté : le serveur remet les ancres (cassage refusé 5 fois)"), false);
                    return;
                }
                player.sendMessage(Text.literal("Le serveur a remis l'ancre (" + rejects + "/5) [" + lastInfo + "]"), false);
            } else if (tick - brokenTick > 40) {
                brokenPos = null;
                rejects = 0;
                mined++;
            }
        }

        if (checkDanger(mc, player, world)) return;
        if (rtpWait > 0) {
            handleRtp(player);
            return;
        }

        if (pause > 0) { pause--; return; }
        if (wait > 0) { wait--; return; }
        if (Config.human && rng.nextInt(900) == 0) {
            pause = 10 + rng.nextInt(40);
            return;
        }

        ItemEntity item = findItem(world, player);
        if (item != null) {
            doItem(mc, player, world, item);
            return;
        }
        if (itemTarget != -1) { // item ramasse
            itemTarget = -1;
            path = null;
            wait = delay(6, 16);
            return;
        }
        doAnchor(mc, player, world);
    }

    // ------------------------------------------------------------- items

    private static void doItem(MinecraftClient mc, ClientPlayerEntity player, ClientWorld world, ItemEntity item) {
        idleTicks = 0;
        if (item.getId() != itemTarget) {
            itemTarget = item.getId();
            anchorTarget = null;
            itemTicks = 0;
            newTarget();
            wait = delay(4, 12);
            return;
        }
        if (++itemTicks > 500) {
            ignoredItems.add(item.getId());
            itemTarget = -1;
            path = null;
            return;
        }

        Vec3d ip = item.getPos();
        Goal goal = new Goal(ip, 0.8, feet ->
                Math.abs(feet.getX() + 0.5 - ip.x) < 1.0
                        && Math.abs(feet.getZ() + 0.5 - ip.z) < 1.0
                        && Math.abs(feet.getY() - ip.y) <= 1.5);

        Nav n = navigate(mc, player, world, goal);
        if (n == Nav.FAILED) {
            ignoredItems.add(item.getId());
            itemTarget = -1;
            path = null;
        } else if (n == Nav.ARRIVED) {
            double dh = Math.hypot(ip.x - player.getX(), ip.z - player.getZ());
            if (dh > 1.3) {
                path = null; // l'item a bouge
            } else {
                aim(player, yawTo(player.getEyePos(), ip), walkPitch());
                mc.options.forwardKey.setPressed(true);
                keysHeld = true;
            }
        }
    }

    // ----------------------------------------------------------- ancres

    private static void doAnchor(MinecraftClient mc, ClientPlayerEntity player, ClientWorld world) {
        BlockPos target = anchorTarget;

        if (target != null && !world.getBlockState(target).isOf(Blocks.RESPAWN_ANCHOR)) {
            brokenPos = target;
            brokenTick = tick;
            anchorTarget = null; // minee
            path = null;
            wait = delay(8, 20);
            return;
        }
        if (target == null) {
            target = pickAnchor(world, player);
            if (target == null) {
                idleTicks++;
                if (Config.autoRtp && idleTicks >= 100 && tick >= nextRtpTick) startRtp(player);
                return;
            }
            idleTicks = 0;
            anchorTarget = target;
            newTarget();
            wait = delay(5, 14);
            return;
        }

        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(target);

        if (eye.distanceTo(center) <= REACH && lineOfSight(world, player, eye, center, target)) {
            mine(mc, player, world, target, center);
            return;
        }

        final BlockPos anchorPos = target;
        Goal goal = new Goal(center, REACH - 0.6, feet -> {
            Vec3d e = new Vec3d(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
            return e.distanceTo(center) <= REACH - 0.4 && lineOfSight(world, player, e, center, anchorPos);
        });

        Nav n = navigate(mc, player, world, goal);
        if (n == Nav.ARRIVED) { // chemin partiel : on compte un echec seulement si on ne se rapproche pas
            double dNow = player.getPos().distanceTo(center);
            if (dNow < arrivedDist - 2.0) arrivedFails = 0;
            arrivedDist = dNow;
        }
        if (n == Nav.FAILED || (n == Nav.ARRIVED && ++arrivedFails > 3)) {
            unreachable.put(target.asLong(), tick + 1200);
            anchorTarget = null;
            path = null;
            wait = delay(10, 25);
        } else if (n == Nav.ARRIVED) {
            path = null;
        }
    }

    private static void mine(MinecraftClient mc, ClientPlayerEntity player, ClientWorld world,
                             BlockPos target, Vec3d center) {
        BlockState state = world.getBlockState(target);
        int slot = findToolSlot(player, state);
        if (slot < 0) {
            player.sendMessage(Text.literal("AutoFarm : il faut une pioche diamant/netherite dans la hotbar"), true);
            Config.anchorFarm = false;
            return;
        }
        if (player.getInventory().selectedSlot != slot) {
            player.getInventory().selectedSlot = slot;
            wait = delay(3, 8);
            return;
        }

        // Comme a la main : on s'arrete completement avant de commencer a taper
        player.setSprinting(false);
        if (player.getVelocity().horizontalLengthSquared() > 0.003) {
            return;
        }

        Vec3d eye = player.getEyePos();
        Vec3d aimPoint = center.add(aimOffset);
        aim(player, yawTo(eye, aimPoint), pitchTo(eye, aimPoint));
        lastInfo = String.format("dist=%.2f outil=%s sol=%s", eye.distanceTo(center),
                player.getInventory().getStack(slot).getItem().toString(), player.isOnGround());

        // On ne tape que si le viseur (rayon reel) touche bien l'ancre, et on envoie
        // la vraie face touchee, exactement comme quand on mine a la main.
        HitResult hit = player.raycast(REACH + 0.3, 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK
                || !((BlockHitResult) hit).getBlockPos().equals(target)) {
            aimMisses++;
            if (aimMisses == 20) aimOffset = Vec3d.ZERO; // viser le centre
            if (aimMisses > 100) {
                unreachable.put(target.asLong(), tick + 1200);
                anchorTarget = null;
                path = null;
            }
            return;
        }
        aimMisses = 0;

        // On maintient le clic gauche : c'est le jeu lui-meme qui mine (comme a la main).
        // Appeler directement le gestionnaire de minage ne marche pas : le jeu annule
        // le minage a chaque tick si le clic n'est pas reellement enfonce.
        mc.options.attackKey.setPressed(true);
        keysHeld = true;
    }

    // -------------------------------------------------------- navigation

    private static Nav navigate(MinecraftClient mc, ClientPlayerEntity player, ClientWorld world, Goal goal) {
        if (repath > 0) repath--;

        if (path == null) {
            if (repath > 0) return Nav.MOVING;
            path = Pathfinder.find(world, player, goal.center(), goal.slack(), goal.test());
            repath = 20;
            pathIdx = 0;
            if (path == null) return Nav.FAILED;
        }

        Vec3d pos = player.getPos();

        // Detection de blocage
        if (tick % 30 == 0) {
            if (stuckRef != null && pos.squaredDistanceTo(stuckRef) < 0.25) {
                stuckCount++;
                if (stuckCount == 1) { path = null; repath = 0; return Nav.MOVING; }
                if (stuckCount >= 3) return Nav.FAILED;
            } else {
                stuckCount = 0;
            }
            stuckRef = pos;
        }

        // Avancer dans le chemin
        while (pathIdx < path.size()) {
            BlockPos w = path.get(pathIdx);
            double dh = Math.hypot(w.getX() + 0.5 - pos.x, w.getZ() + 0.5 - pos.z);
            if (dh < 0.5 && Math.abs(w.getY() - pos.y) < 1.5) pathIdx++;
            else break;
        }
        if (pathIdx >= path.size()) return Nav.ARRIVED;

        BlockPos wp = path.get(pathIdx);
        double wx = wp.getX() + 0.5, wz = wp.getZ() + 0.5;
        double dh = Math.hypot(wx - pos.x, wz - pos.z);
        if (dh > 3.5) { // trop loin du chemin
            path = null;
            return Nav.MOVING;
        }

        float ty = (float) (Math.toDegrees(Math.atan2(wz - pos.z, wx - pos.x)) - 90.0);
        aim(player, ty, walkPitch());
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
        return Nav.MOVING;
    }

    // ----------------------------------------------------------- visee

    private static void aim(ClientPlayerEntity p, float targetYaw, float targetPitch) {
        if (!Config.human) {
            p.setYaw(targetYaw);
            p.setPitch(MathHelper.clamp(targetPitch, -90f, 90f));
            return;
        }
        float dy = MathHelper.wrapDegrees(targetYaw - p.getYaw());
        float dp = targetPitch - p.getPitch();
        float dist = (float) Math.hypot(dy, dp);

        float sy = Math.abs(dy) < 0.5f ? dy : MathHelper.clamp(dy * aimEase, -22f, 22f);
        float sp = Math.abs(dp) < 0.5f ? dp : MathHelper.clamp(dp * aimEase, -16f, 16f);
        if (dist > 2f) {
            sy += (float) (rng.nextGaussian() * 0.12);
            sp += (float) (rng.nextGaussian() * 0.10);
        }
        p.setYaw(p.getYaw() + sy);
        p.setPitch(MathHelper.clamp(p.getPitch() + sp, -90f, 90f));
    }

    private static float yawTo(Vec3d from, Vec3d to) {
        return (float) (Math.toDegrees(Math.atan2(to.z - from.z, to.x - from.x)) - 90.0);
    }

    private static float pitchTo(Vec3d from, Vec3d to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        return (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }

    private static float walkPitch() {
        return Config.human ? 8f + 5f * (float) Math.sin(tick * 0.04) : 0f;
    }

    // ----------------------------------------------------------- utilitaires

    private static BlockPos pickAnchor(ClientWorld world, ClientPlayerEntity player) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        unreachable.values().removeIf(exp -> exp <= tick);
        for (BlockPos p : ChestEspClient.anchors) {
            if (unreachable.containsKey(p.asLong())) continue;
            if (!world.getBlockState(p).isOf(Blocks.RESPAWN_ANCHOR)) continue;
            double d = p.toCenterPos().distanceTo(player.getPos());
            if (d < bd && d <= Config.farmRange) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private static ItemEntity findItem(ClientWorld world, ClientPlayerEntity player) {
        Box box = player.getBoundingBox().expand(ITEM_RANGE);
        List<ItemEntity> items = world.getEntitiesByClass(ItemEntity.class, box,
                e -> e.getStack().isOf(Items.RESPAWN_ANCHOR) && !ignoredItems.contains(e.getId()));
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

    private static int findToolSlot(ClientPlayerEntity player, BlockState state) {
        int bestSlot = -1;
        float bestSpeed = 0f;
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.isEmpty() || !s.isSuitableFor(state)) continue;
            float sp = s.getMiningSpeedMultiplier(state);
            if (sp > bestSpeed) {
                bestSpeed = sp;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    // ------------------------------------------------------------ /rtp

    private static void startRtp(ClientPlayerEntity player) {
        player.networkHandler.sendChatCommand("rtp");
        rtpFrom = player.getPos();
        rtpWait = 300;               // on attend 15 s sans bouger (la tp annule parfois si on bouge)
        nextRtpTick = tick + 1200;   // pas plus d'un /rtp par minute
        idleTicks = 0;
        player.sendMessage(Text.literal("AutoFarm : plus d'ancres, /rtp envoyé"), true);
    }

    private static void handleRtp(ClientPlayerEntity player) {
        rtpWait--;
        if (rtpFrom != null && player.getPos().distanceTo(rtpFrom) > 30.0) {
            rtpWait = 0;
            rtpFails = 0;
            wait = 80; // laisser charger les chunks
            unreachable.clear();
            ignoredItems.clear();
            anchorTarget = null;
            path = null;
            ChestEspClient.anchors = new ArrayList<>();
            player.sendMessage(Text.literal("AutoFarm : téléporté, recherche d'ancres..."), true);
            return;
        }
        if (rtpWait == 0) {
            rtpFails++;
            if (rtpFails >= 3) {
                Config.anchorFarm = false;
                player.sendMessage(Text.literal("AutoFarm arrêté : le /rtp ne téléporte pas (3 essais)"), false);
            }
        }
    }

    // ---------------------------------------------------------- securite

    /** @return true si on a declenche la fuite (/home 1). */
    private static boolean checkDanger(MinecraftClient mc, ClientPlayerEntity player, ClientWorld world) {
        if (!Config.autoHome) return false;

        float hp = player.getHealth();
        if (tick % 40 == 0) healthRef = hp;

        // Danger immediat
        if (hp <= Config.homeHp) { escape(mc, player, "vie basse"); return true; }
        if (player.isOnFire() || player.isInLava()) { escape(mc, player, "feu / lave"); return true; }
        if (healthRef - hp >= 8f) { escape(mc, player, "gros dégâts"); return true; }

        // Danger durable (verifie toutes les 5 ticks)
        if (tick % 5 == 0) {
            int players = 0;
            for (AbstractClientPlayerEntity p : world.getPlayers()) {
                if (p != player && !p.isSpectator() && p.squaredDistanceTo(player) < 25.0 * 25.0) players++;
            }
            int mobs = world.getEntitiesByClass(HostileEntity.class, player.getBoundingBox().expand(10.0), e -> true).size();
            double score = players * 4.0 + mobs * 1.5 + (hp <= 12f ? 3.0 : 0.0);
            if (score >= 7.0) dangerTicks += 5;
            else dangerTicks = 0;
            if (dangerTicks >= 60) { // 3 secondes de suite
                escape(mc, player, players + " joueur(s), " + mobs + " monstre(s) proches");
                return true;
            }
        }
        return false;
    }

    private static void escape(MinecraftClient mc, ClientPlayerEntity player, String reason) {
        player.networkHandler.sendChatCommand("home 1");
        Config.anchorFarm = false;
        releaseKeys(mc);
        resetState();
        player.sendMessage(Text.literal("DANGER (" + reason + ") : /home 1 envoyé, AutoFarm arrêté"), false);
    }

    private static boolean lineOfSight(ClientWorld world, ClientPlayerEntity player,
                                       Vec3d from, Vec3d to, BlockPos anchor) {
        BlockHitResult r = world.raycast(new RaycastContext(from, to,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
        return r.getType() == HitResult.Type.MISS || r.getBlockPos().equals(anchor);
    }

    private static int delay(int min, int max) {
        return Config.human ? min + rng.nextInt(max - min + 1) : 0;
    }

    /** Reinitialise ce qui depend de la cible courante. */
    private static void newTarget() {
        path = null;
        pathIdx = 0;
        repath = 0;
        stuckRef = null;
        stuckCount = 0;
        arrivedFails = 0;
        arrivedDist = 1e9;
        aimMisses = 0;
        aimEase = 0.18f + rng.nextFloat() * 0.15f;
        aimOffset = new Vec3d((rng.nextDouble() - 0.5) * 0.5,
                (rng.nextDouble() - 0.5) * 0.5,
                (rng.nextDouble() - 0.5) * 0.5);
    }

    private static void resetState() {
        anchorTarget = null;
        itemTarget = -1;
        path = null;
        brokenPos = null;
        rejects = 0;
        pause = 0;
        wait = 0;
        stuckRef = null;
        stuckCount = 0;
        idleTicks = 0;
        rtpWait = 0;
        rtpFails = 0;
        dangerTicks = 0;
    }

    private static void releaseKeys(MinecraftClient mc) {
        if (!keysHeld) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.attackKey.setPressed(false);
        keysHeld = false;
    }
}
