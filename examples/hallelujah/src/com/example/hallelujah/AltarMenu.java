package com.example.hallelujah;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;

/**
 * The altar's menu: a one-row chest, plus the blessing's progress. A ChestMenu, so it gets the
 * chest screen for free; the progress shows as the Vision's caption.
 */
public final class AltarMenu extends ChestMenu {

    private final ContainerData data;

    /** The client's half: empty until the server fills it in. */
    public AltarMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, new SimpleContainer(9), new SimpleContainerData(1));
    }

    /** The server's half, with the real altar. */
    public AltarMenu(int containerId, Inventory inventory, Container altar, ContainerData data) {
        super(Hallelujah.altarMenu.get(), containerId, inventory, altar, 1);
        this.data = data;
        addDataSlots(data);
    }

    /** Percent of the current bottle's blessing. */
    public int progress() {
        return data.get(0);
    }
}
