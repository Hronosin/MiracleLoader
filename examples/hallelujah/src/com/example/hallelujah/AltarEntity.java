package com.example.hallelujah;

import io.github.hronosin.miracle.toolchain.Reliquary;
import io.github.hronosin.miracle.toolchain.Vigil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The altar's insides: nine slots, and patience. Empty bottles placed on it are filled with holy
 * water, one every five seconds. Saved with the world, progress included.
 */
public class AltarEntity extends Reliquary implements Vigil {

    /** Ticks to bless one bottle. */
    static final int BLESSING_TIME = 100;

    private int progress;

    /** What the menu shows: the blessing's progress, in percent. Synced to the viewer by the menu. */
    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return progress * 100 / BLESSING_TIME;
        }

        @Override
        public void set(int index, int value) {
            // the server's copy is read-only; the client's lives in AltarMenu
        }

        @Override
        public int getCount() {
            return 1;
        }
    };

    public AltarEntity(BlockPos pos, BlockState state) {
        super(Hallelujah.altarEntity.get(), pos, state, 1);
    }

    @Override
    public void serverTick() {
        int bottle = find(Items.GLASS_BOTTLE.getDefaultInstance());
        if (bottle < 0) {
            progress = 0;
            return;
        }
        if (++progress < BLESSING_TIME) {
            return;
        }
        ItemStack water = new ItemStack(Hallelujah.holyWater.get());
        int into = find(water);
        if (into < 0 || getItem(into).getCount() >= getItem(into).getMaxStackSize()) {
            into = getItem(bottle).getCount() == 1 ? bottle : firstEmpty();
        }
        if (into < 0) {
            progress = BLESSING_TIME - 1; // no room: wait, ready
            return;
        }
        progress = 0;
        getItem(bottle).shrink(1);
        if (getItem(into).isEmpty()) {
            setItem(into, water);
        } else {
            getItem(into).grow(1);
        }
        setChanged();
    }

    private int find(ItemStack like) {
        for (int i = 0; i < getContainerSize(); i++) {
            if (!getItem(i).isEmpty() && ItemStack.isSameItem(getItem(i), like)) {
                return i;
            }
        }
        return -1;
    }

    private int firstEmpty() {
        for (int i = 0; i < getContainerSize(); i++) {
            if (getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new AltarMenu(containerId, inventory, this, data);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putInt("blessing", progress);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        progress = in.getIntOr("blessing", 0);
    }
}
