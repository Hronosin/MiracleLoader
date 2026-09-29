package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.rgct.Rgct;

/** 1.21.11: container screens draw their labels in renderLabels(GuiGraphics, int, int). */
final class Easel {

    static void install(Rgct rgct) {
        rgct.target("net.minecraft.client.gui.screens.inventory.AbstractContainerScreen")
                .method("renderLabels", "(Lnet/minecraft/client/gui/GuiGraphics;II)V")
                .interceptReturn(ctx -> Brush.caption(ctx.self(), ctx.arg(0)));
    }
}
