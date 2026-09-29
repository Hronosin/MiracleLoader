package io.github.hronosin.miracle.toolchain;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Draws a {@link Vision}'s caption. Client only; the drawing itself is {@link Ink}'s. */
final class Brush {

    private Brush() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void caption(Object screen, Object graphics) {
        if (!(screen instanceof AbstractContainerScreen<?> s)) {
            return;
        }
        AbstractContainerMenu menu = s.getMenu();
        Vision<?> v = Seer.visionOf(menu);
        if (v == null || v.caption == null) {
            return;
        }
        Component text;
        try {
            text = (Component) ((Function) v.caption).apply(menu);
        } catch (RuntimeException e) {
            text = Component.literal("caption: " + e.getClass().getSimpleName());
        }
        if (text == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int width = font.width(text);
        if (width == 0) {
            return;
        }
        int[] layout = layout(s);
        Ink.text(graphics, font, text, layout[0] - 8 - width, layout[1]);
    }

    /**
     * The screen's width and its title's y: the first and fourth int fields every
     * AbstractContainerScreen declares (imageWidth, imageHeight, titleLabelX, titleLabelY), read by
     * position because 1.21.11's names are obfuscated. Chest screen values if that fails.
     */
    private static int[] layout(AbstractContainerScreen<?> s) {
        List<Field> ints = INTS;
        try {
            int width = ints.get(0).getInt(s);
            int titleY = ints.get(3).getInt(s);
            if (width > 0 && width < 2000 && titleY >= 0 && titleY < 2000) {
                return new int[]{width, titleY};
            }
        } catch (IllegalAccessException | RuntimeException ignored) {
            // fall through
        }
        return new int[]{176, 6};
    }

    private static final List<Field> INTS = ints();

    private static List<Field> ints() {
        List<Field> out = new ArrayList<>();
        for (Field f : AbstractContainerScreen.class.getDeclaredFields()) {
            if (f.getType() == int.class && !Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                out.add(f);
            }
        }
        return out;
    }
}
