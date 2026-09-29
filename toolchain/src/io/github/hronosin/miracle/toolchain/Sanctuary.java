package io.github.hronosin.miracle.toolchain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A block that holds a {@link Shrine}'s block entity, and does the chores every such block needs:
 * makes the block entity, ticks it if it keeps {@link Vigil}, and opens its menu on a right click
 * if it has one (a {@link Reliquary} does). Use it as is, or extend it for shapes, facing and the
 * rest of what blocks do.
 *
 * <pre>{@code
 * ALTAR = Creation.block("altar", p -> new Sanctuary(p.strength(2f)));
 * ALTAR_ENTITY = Creation.shrine("altar", AltarEntity::new, ALTAR);
 * }</pre>
 *
 * <p>It finds its shrine by itself: the one that names this block. A Sanctuary no shrine names
 * is an ordinary block, and the log says so once.
 */
public class Sanctuary extends Block implements EntityBlock {

    public Sanctuary(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /** The shrine that names this block, or null. */
    public Shrine<?> shrine() {
        return Creation.shrineOf(this);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        Shrine<?> shrine = shrine();
        if (shrine == null) {
            return null;
        }
        return shrine.create(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        Shrine<?> shrine = shrine();
        if (shrine == null || !shrine.exists() || shrine.get() != type || !Boolean.TRUE.equals(shrine.vigilant)) {
            return null;
        }
        return level.isClientSide() ? Sanctuary::clientTick : Sanctuary::serverTick;
    }

    private static void serverTick(Level level, BlockPos pos, BlockState state, BlockEntity be) {
        if (be instanceof Vigil v) {
            v.serverTick();
        }
    }

    private static void clientTick(Level level, BlockPos pos, BlockState state, BlockEntity be) {
        if (be instanceof Vigil v) {
            v.clientTick();
        }
    }

    /** A right click with an empty hand (or an item that does nothing here) opens the menu, if there is one. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        MenuProvider menu = getMenuProvider(state, level, pos);
        if (menu == null) {
            return super.useWithoutItem(state, level, pos, player, hit);
        }
        if (!level.isClientSide()) {
            player.openMenu(menu);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof MenuProvider menu ? menu : null;
    }
}
