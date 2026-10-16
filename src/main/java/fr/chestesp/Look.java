package fr.chestesp;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/** Utilitaires de visee (rotation fluide en mode humain). */
public final class Look {
    public static final Random RNG = new Random();

    private Look() {}

    public static void aim(ClientPlayerEntity p, float targetYaw, float targetPitch, float ease) {
        if (!Config.human) {
            p.setYaw(targetYaw);
            p.setPitch(MathHelper.clamp(targetPitch, -90f, 90f));
            return;
        }
        float dy = MathHelper.wrapDegrees(targetYaw - p.getYaw());
        float dp = targetPitch - p.getPitch();
        float dist = (float) Math.hypot(dy, dp);

        float sy = Math.abs(dy) < 0.5f ? dy : MathHelper.clamp(dy * ease, -22f, 22f);
        float sp = Math.abs(dp) < 0.5f ? dp : MathHelper.clamp(dp * ease, -16f, 16f);
        if (dist > 2f) {
            sy += (float) (RNG.nextGaussian() * 0.12);
            sp += (float) (RNG.nextGaussian() * 0.10);
        }
        p.setYaw(p.getYaw() + sy);
        p.setPitch(MathHelper.clamp(p.getPitch() + sp, -90f, 90f));
    }

    public static float error(ClientPlayerEntity p, float targetYaw, float targetPitch) {
        return (float) Math.hypot(MathHelper.wrapDegrees(targetYaw - p.getYaw()), targetPitch - p.getPitch());
    }

    public static float yawTo(Vec3d from, Vec3d to) {
        return (float) (Math.toDegrees(Math.atan2(to.z - from.z, to.x - from.x)) - 90.0);
    }

    public static float pitchTo(Vec3d from, Vec3d to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        return (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }
}
