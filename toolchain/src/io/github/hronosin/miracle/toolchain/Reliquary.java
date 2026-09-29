package io.github.hronosin.miracle.toolchain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A block entity with an inventory: one to six rows of nine slots, opened with a right click (on
 * a {@link Sanctuary}) in the chest screen everyone already knows. It saves its items with the
 * world, drops them when broken, works with hoppers, and shows the block's name as its title
 * (or the name given in an anvil).
 *
 * <pre>{@code
 * STASH = Creation.block("stash", p -> new Sanctuary(p.strength(2.5f)));
 * Creation.reliquary("stash", 3, STASH);   // a chest of your own, no other code needed
 * }</pre>
 *
 * <p>Extend it for more: keep {@link Vigil} to act on the items every tick, override
 * {@link #canPlaceItem} to say what hoppers may put in, or {@link #createMenu(int, Inventory)} for a
 * menu of your own ({@link Vision}). {@link #sync()} sends the items to nearby clients, for blocks
 * that show what they hold.
 */
public class Reliquary extends BaseContainerBlockEntity {

    private final int rows;
    private NonNullList<ItemStack> items;

    public Reliquary(BlockEntityType<?> type, BlockPos pos, BlockState state, int rows) {
        super(type, pos, state);
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException(rows + " rows: a reliquary has 1 to 6");
        }
        this.rows = rows;
        this.items = NonNullList.withSize(rows * 9, ItemStack.EMPTY);
    }

    /** Rows of nine slots. */
    public int rows() {
        return rows;
    }

    @Override
    public int getContainerSize() {
        return items.size();
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    protected Component getDefaultName() {
        return getBlockState().getBlock().getName();
    }

    /** The chest menu with {@link #rows()} rows. Override for a menu of your own. */
    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new ChestMenu(chest(rows), containerId, inventory, this, rows);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        ContainerHelper.saveAllItems(out, items);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        items = NonNullList.withSize(rows * 9, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(in, items);
    }

    /** Marks it to be saved and sends its items to the players who can see it. */
    public void sync() {
        Hallowed.sync(this);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    private static MenuType<ChestMenu> chest(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }
}
