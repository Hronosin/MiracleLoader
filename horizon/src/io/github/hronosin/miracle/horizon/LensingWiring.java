package io.github.hronosin.miracle.horizon;

import io.github.hronosin.miracle.api.Mods;
import io.github.hronosin.miracle.rgct.Rgct;

/** Lensing's hooks, registered in transform(): no game class is touched here, only named. */
final class LensingWiring {

    private LensingWiring() {
    }

    static void install(Rgct rgct) {
        Mods.Game game = Mods.game();
        if (!game.client()) {
            return;
        }
        if (Wormhole.compare(game.version(), "26.3") >= 0) {
            stacked(rgct);
        } else {
            classic(rgct);
        }
        // every version: a mod's broken effect doesn't stop the game, and uniforms come from code
        rgct.target("net.minecraft.client.renderer.ShaderManager").method("getPostChain").interceptHead(ctx -> LensingSafety.loading(ctx.arg(0)));
        rgct.target("net.minecraft.client.renderer.ShaderManager").method("getPostChain").interceptReturn(ctx -> LensingSafety.done());
        rgct.target("net.minecraft.client.renderer.ShaderManager").method("tryTriggerRecovery").interceptHead(ctx -> {
            if (LensingSafety.spare()) {
                ctx.cancel();
            }
        });
        rgct.target("net.minecraft.client.renderer.PostPass").method("addToFrame").atHead(self -> LensingUniforms.beforeFrame(self));
    }

    /** Up to 26.2: shaders and the includes they import come in one call. */
    private static void classic(Rgct rgct) {
        rgct.target("net.minecraft.client.renderer.ShaderManager").method("loadShader").interceptHead(ctx -> {
            Object r = LensingHooks.shader(ctx.arg(0), ctx.arg(1));
            if (r != null) {
                ctx.setArg(1, r);
            }
            Object includes = LensingHooks.includes(ctx.arg(3));
            if (includes != null) {
                ctx.setArg(3, includes);
            }
        });
    }

    /**
     * 26.3 on: includes load on their own, and the player keeps a list of post effects. The two
     * methods only 26.3 has are named at run time, so baking doesn't look for them in versions
     * that never get here.
     */
    private static void stacked(Rgct rgct) {
        rgct.target("net.minecraft.client.renderer.ShaderManager").method("loadShader").interceptHead(ctx -> {
            Object r = LensingHooks.shader(ctx.arg(0), ctx.arg(1));
            if (r != null) {
                ctx.setArg(1, r);
            }
        });
        rgct.target("net.minecraft.client.renderer.ShaderManager").method(only263("loadInclude")).interceptHead(ctx -> {
            Object r = LensingHooks.shader(ctx.arg(0), ctx.arg(1));
            if (r != null) {
                ctx.setArg(1, r);
            }
        });
        rgct.target(only263("net.minecraft.client.player.LocalPlayer")).method(only263("setActivePostEffects")).interceptHead(ctx -> {
            Object merged = LensingClient.merge(ctx.arg(0));
            if (merged != null) {
                ctx.setArg(0, merged);
            }
        });
    }

    /** A name only 26.3 and later have: passed through, out of baking's sight. */
    private static String only263(String name) {
        return new StringBuilder(name).toString();
    }
}
