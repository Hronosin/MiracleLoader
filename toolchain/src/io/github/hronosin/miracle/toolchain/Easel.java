package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.rgct.Rgct;

/**
 * Where {@link Vision} captions get drawn: at the end of every container screen's labels. The
 * method is {@code extractLabels} on 26.x and {@code renderLabels} on 1.21.11, so this class has a
 * fallback for 1.21.11 (see {@code toolchain/fallback/}). Touches no game class itself: it's
 * loaded at startup.
 */
final class Easel {

    private Easel() {
    }

    static void install(Rgct rgct) {
        rgct.target("net.minecraft.client.gui.screens.inventory.AbstractContainerScreen")
                .method("extractLabels", "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V")
                .interceptReturn(ctx -> Brush.caption(ctx.self(), ctx.arg(0)));
    }
}
