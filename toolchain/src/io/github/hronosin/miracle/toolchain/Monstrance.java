package io.github.hronosin.miracle.toolchain;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4f;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * The renderer behind {@link Shrine#enshrines} (one item from the block entity's container,
 * floating above the block and turning) and behind {@link Altarpiece}s. Client only.
 *
 * <p>Made as a {@link Proxy} of {@code BlockEntityRenderer}, with its methods told apart by
 * shape rather than name: {@code submit} takes a {@code CameraRenderState}, a class that moved
 * between 1.21.11 and 26.1, so a class implementing it directly couldn't serve both.
 */
final class Monstrance implements InvocationHandler {

    /** What one frame needs, taken from the block entity on the game thread. */
    static final class State extends BlockEntityRenderState {
        final ItemStackRenderState item = new ItemStackRenderState();
        float angle;
        float bob;
        BlockEntity blockEntity;
        float partialTick;
    }

    private final int slot;
    private final ItemModelResolver items;
    private final Altarpiece<BlockEntity> piece;

    private Monstrance(int slot, ItemModelResolver items, Altarpiece<BlockEntity> piece) {
        this.slot = slot;
        this.items = items;
        this.piece = piece;
    }

    @SuppressWarnings("rawtypes")
    static BlockEntityRenderer of(int slot, BlockEntityRendererProvider.Context ctx) {
        return proxy(new Monstrance(slot, ctx.itemModelResolver(), null));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static BlockEntityRenderer of(Altarpiece<?> piece) {
        return proxy(new Monstrance(-1, null, (Altarpiece<BlockEntity>) piece));
    }

    @SuppressWarnings("rawtypes")
    private static BlockEntityRenderer proxy(Monstrance handler) {
        return (BlockEntityRenderer) Proxy.newProxyInstance(BlockEntityRenderer.class.getClassLoader(),
                new Class<?>[]{BlockEntityRenderer.class}, handler);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> piece != null ? piece.getClass().getName() : "Monstrance (slot " + slot + ")";
            };
        }
        int n = method.getParameterCount();
        Class<?> ret = method.getReturnType();
        if (n == 0 && !method.isDefault()) {
            return new State(); // createRenderState
        }
        if (n == 5 && ret == void.class) { // extractRenderState: the base first, then ours
            InvocationHandler.invokeDefault(proxy, method, args);
            extract((BlockEntity) args[0], (State) args[1], (Float) args[2]);
            return null;
        }
        if (n == 4 && ret == void.class && !method.isDefault()) { // submit
            submit((State) args[0], (PoseStack) args[1], (SubmitNodeCollector) args[2]);
            return null;
        }
        if (piece != null && n == 0 && ret == int.class) { // getViewDistance
            return piece.viewDistance();
        }
        return InvocationHandler.invokeDefault(proxy, method, args); // view distance, off-screen, shouldRender
    }

    private void extract(BlockEntity be, State state, float partialTick) {
        if (piece != null) {
            state.blockEntity = be;
            state.partialTick = partialTick;
            return;
        }
        ItemStack stack = be instanceof Container c && slot < c.getContainerSize() ? c.getItem(slot) : ItemStack.EMPTY;
        Level level = be.getLevel();
        items.updateForTopItem(state.item, stack, ItemDisplayContext.GROUND, level, null,
                (int) be.getBlockPos().asLong());
        float time = (level == null ? 0 : level.getGameTime() % 72000) + partialTick;
        state.angle = time * 2f % 360f;
        state.bob = (float) Math.sin(time / 10.0) * 0.06f;
    }

    private void submit(State state, PoseStack pose, SubmitNodeCollector collector) {
        if (piece != null) {
            if (state.blockEntity != null) {
                pose.pushPose();
                try {
                    piece.render(state.blockEntity, pose, collector, state.lightCoords, state.partialTick);
                } finally {
                    pose.popPose();
                }
            }
            return;
        }
        if (state.item.isEmpty()) {
            return;
        }
        pose.pushPose();
        pose.translate(0.5f, 1.25f + state.bob, 0.5f);
        pose.mulPose(new Matrix4f().rotationY((float) Math.toRadians(state.angle)));
        pose.scale(0.75f, 0.75f, 0.75f);
        state.item.submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        pose.popPose();
    }
}
