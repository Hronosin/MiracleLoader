package io.github.hronosin.miracle.toolchain;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * How far along a mob's arm swing (an attack) is, 0 to 1. The method is {@code getAttackAnim}
 * until 26.2 and {@code getSwingAnimation} from 26.3; found by name on 26.x, and called directly
 * by the 1.21.11 fallback.
 */
final class Swing {

    private static volatile MethodHandle swing;
    private static volatile boolean missing;

    private Swing() {
    }

    static float progress(Object entity, float partialTick) {
        if (missing) {
            return 0;
        }
        try {
            MethodHandle h = swing;
            if (h == null) {
                Class<?> living = Class.forName("net.minecraft.world.entity.LivingEntity");
                MethodType t = MethodType.methodType(float.class, float.class);
                try {
                    h = MethodHandles.publicLookup().findVirtual(living, "getSwingAnimation", t);
                } catch (NoSuchMethodException e) {
                    h = MethodHandles.publicLookup().findVirtual(living, "getAttackAnim", t);
                }
                swing = h;
            }
            return (float) h.invoke(entity, partialTick);
        } catch (Throwable e) {
            missing = true;
            return 0;
        }
    }
}
