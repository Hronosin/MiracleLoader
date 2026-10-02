package org.test.rawgl;

import io.github.hronosin.miracle.api.MiracleMod;

public final class RawGl implements MiracleMod {
    @Override
    public void onLaunch() {
        System.out.println("[raw-gl] launched");
    }

    /** Never called: it's enough that the code names it. */
    static void draw() {
        org.lwjgl.opengl.GL11.glFinish();
    }
}
