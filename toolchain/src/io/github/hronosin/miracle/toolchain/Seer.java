package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;

import java.lang.reflect.Constructor;

/**
 * The client-only half of {@link Vision}s: when the server opens one of our menus, makes the
 * client's half and its screen. Loaded only on a client, once the game runs.
 */
final class Seer {

    /** The menu of the vision on screen now, for captions. */
    private static volatile AbstractContainerMenu shown;
    private static volatile Vision<?> shownVision;

    private Seer() {
    }

    /** What MenuScreens.create does for vanilla's menus, for ours. */
    static void show(Object type, Object containerId, Object title) {
        Vision<?> v = Creation.visionOf(type);
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (v == null || player == null) {
            return;
        }
        try {
            AbstractContainerMenu menu = ((MenuType<?>) type).create((Integer) containerId, player.getInventory());
            Screen screen = screen(v, menu, player.getInventory(), (Component) title);
            if (screen == null) {
                return;
            }
            player.containerMenu = menu;
            shown = menu;
            shownVision = v;
            display(mc, screen);
        } catch (RuntimeException | LinkageError e) {
            Log.error("MiracleToolChain: couldn't open " + v + ": " + e);
        }
    }

    private static volatile java.lang.reflect.Method setScreen;
    private static volatile Object setScreenOn;

    /**
     * Minecraft.setScreen until 26.1.2, Gui.setScreen from 26.2: found by name, which 26.x keeps
     * readable. 1.21.11 has a fallback that calls it directly.
     */
    static void display(Minecraft mc, Screen screen) {
        try {
            if (setScreen == null) {
                try {
                    setScreen = mc.gui.getClass().getMethod("setScreen", Screen.class);
                    setScreenOn = mc.gui;
                } catch (NoSuchMethodException e) {
                    setScreen = Minecraft.class.getMethod("setScreen", Screen.class);
                    setScreenOn = mc;
                }
            }
            setScreen.invoke(setScreenOn, screen);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't show a screen in this version: " + e, e);
        }
    }

    /** The vision whose menu this is, if it's the one on screen. */
    static Vision<?> visionOf(AbstractContainerMenu menu) {
        return menu != null && menu == shown ? shownVision : null;
    }

    private static Screen screen(Vision<?> v, AbstractContainerMenu menu, Inventory inventory, Component title) {
        if (v.screen == null) {
            if (menu instanceof ChestMenu chest) {
                return new ContainerScreen(chest, inventory, title);
            }
            Log.error("MiracleToolChain: " + v + " has no screen: its menu (" + menu.getClass().getName() + ") isn't a"
                    + " ChestMenu, so name one with Vision.screen(\"your.ScreenClass\").");
            return null;
        }
        try {
            Class<?> cls = Class.forName(v.screen, true, v.loader);
            for (Constructor<?> c : cls.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 3 && p[0].isInstance(menu) && p[1] == Inventory.class && p[2] == Component.class) {
                    return (Screen) c.newInstance(menu, inventory, title);
                }
            }
            Log.error("MiracleToolChain: " + v + " is shown by " + v.screen + ", which has no public constructor taking ("
                    + menu.getClass().getSimpleName() + ", Inventory, Component).");
        } catch (ReflectiveOperationException | ClassCastException e) {
            Throwable why = e instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : e;
            Log.error("MiracleToolChain: " + v + "'s screen " + v.screen + " couldn't be made: " + why);
        }
        return null;
    }
}
