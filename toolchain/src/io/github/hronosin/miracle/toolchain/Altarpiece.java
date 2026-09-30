package io.github.hronosin.miracle.toolchain;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * A block entity renderer that works on every supported version: extend it and name the class
 * in {@link Shrine#renderedBy}. The game's own {@code BlockEntityRenderer} interface differs
 * between 1.21.11 and 26.x in one parameter type, which a class implementing it can't paper
 * over; the library implements it for you and calls {@link #render} each frame.
 *
 * <pre>{@code
 * public class CandleFlame extends Altarpiece<Candle> {
 *     private final ItemModelResolver items;
 *     private final ItemStackRenderState flame = new ItemStackRenderState();
 *
 *     public CandleFlame(BlockEntityRendererProvider.Context ctx) {
 *         super(ctx);
 *         items = ctx.itemModelResolver();
 *     }
 *
 *     public void render(Candle candle, PoseStack pose, SubmitNodeCollector collector, int light, float partialTick) {
 *         items.updateForTopItem(flame, new ItemStack(Items.TORCH), ItemDisplayContext.GROUND, candle.getLevel(), null, 0);
 *         pose.translate(0.5f, 1.2f, 0.5f);
 *         flame.submit(pose, collector, light, OverlayTexture.NO_OVERLAY, 0);
 *     }
 * }
 * }</pre>
 *
 * <p>Runs on the render thread, on the client; a dedicated server never loads it. The pose
 * starts at the block's corner, one unit per block, and is restored after each call. Read the
 * block entity, don't change it: what the client shows comes from what the server
 * {@code sync()}ed.
 *
 * @param <T> the block entity's class
 */
public abstract class Altarpiece<T extends BlockEntity> {

    /** Made once per resource load, with the game's rendering context. */
    protected Altarpiece(BlockEntityRendererProvider.Context context) {
    }

    /**
     * Draws one block entity. {@code light} is its packed light, {@code partialTick} how far the
     * frame is between two ticks (for smooth motion).
     */
    public abstract void render(T blockEntity, PoseStack pose, SubmitNodeCollector collector, int light, float partialTick);

    /** How far away it's still drawn, in blocks. Vanilla draws most block entities up to 64. */
    public int viewDistance() {
        return 64;
    }
}
