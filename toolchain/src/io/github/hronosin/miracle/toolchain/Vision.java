package io.github.hronosin.miracle.toolchain;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A new kind of menu, made through {@link Creation#vision}: slots and numbers the server and one
 * player share while a screen is open, and the screen that shows them.
 *
 * <pre>{@code
 * ALTAR_MENU = Creation.vision("altar", AltarMenu::new)
 *         .caption(menu -> Component.literal(menu.progress() + "%"));
 *
 * class AltarMenu extends ChestMenu {
 *     AltarMenu(int id, Inventory inv) { this(id, inv, new SimpleContainer(9), new SimpleContainerData(1)); }  // the client's
 *     AltarMenu(int id, Inventory inv, Container altar, ContainerData data) {                                // the server's
 *         super(ALTAR_MENU.get(), id, inv, altar, 1);
 *         addDataSlots(data);
 *     }
 * }
 * }</pre>
 *
 * <p>The factory given to {@link Creation#vision} makes the client's half, from nothing but an id
 * and the player's inventory: the server fills it in as the screen opens. The server's half is
 * made by whoever opens it, with the real container: return it from a {@link Reliquary}'s
 * {@code createMenu}, or call {@link #open}.
 *
 * <p><b>Screens.</b> A dedicated server has none, so the screen is named, not passed: one jar
 * serves both sides. A menu that extends {@code ChestMenu} gets the chest screen without asking.
 * Any other menu needs {@link #screen(String)}: the class name of an
 * {@code AbstractContainerScreen} with a constructor taking (your menu, {@code Inventory},
 * {@code Component}). Screen code differs between game versions more than anything else (26.x
 * draws through {@code GuiGraphicsExtractor}, 1.21.11 through {@code GuiGraphics}), so a screen of
 * your own may need a fallback per version; {@link #caption} is the portable way to show a line of
 * text, and works on any screen.
 *
 * @param <M> the menu's class
 */
public final class Vision<M extends AbstractContainerMenu> implements Supplier<MenuType<M>> {

    /** Makes the client's half of the menu: usually a constructor, {@code AltarMenu::new}. */
    @FunctionalInterface
    public interface Factory<M extends AbstractContainerMenu> {
        M create(int containerId, Inventory inventory);
    }

    private final String namespace;
    private final String path;
    final Factory<M> factory;
    private volatile MenuType<M> value;
    volatile String screen;
    volatile ClassLoader loader;
    volatile Function<? super M, ? extends Component> caption;

    Vision(String namespace, String path, Factory<M> factory) {
        this.namespace = namespace;
        this.path = path;
        this.factory = factory;
    }

    /** The menu type itself. Throws before the game has built its registries. */
    @Override
    public MenuType<M> get() {
        MenuType<M> v = value;
        if (v == null) {
            throw new IllegalStateException(this + " doesn't exist yet: the game makes it when it builds its registries,"
                    + " just after the loader hands over. Use it from game code, not from onLaunch().");
        }
        return v;
    }

    /** True once the game has made it. */
    public boolean exists() {
        return value != null;
    }

    /** {@code namespace:path}, e.g. {@code hallelujah:altar}. */
    public Identifier id() {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    /**
     * Shown by your own screen: the class name of an {@code AbstractContainerScreen} with a
     * constructor taking (your menu, {@code Inventory}, {@code Component}). By name, so a dedicated
     * server never loads it.
     */
    public Vision<M> screen(String screenClass) {
        Creation.checkOpen("Vision.screen");
        this.screen = screenClass;
        this.loader = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass().getClassLoader();
        return this;
    }

    /**
     * A line of text at the right end of the screen's title row, asked for every frame while the
     * screen is open: a progress, a count, a mood. Runs on the client, with the client's half of
     * the menu, so show what the menu's data slots carry. Null or empty shows nothing.
     */
    public Vision<M> caption(Function<? super M, ? extends Component> caption) {
        this.caption = caption;
        return this;
    }

    /**
     * Opens it for a player, from the server: {@code menu} makes the server's half, with the real
     * container. The title is the screen's. Empty if the player couldn't open it (a spectator,
     * say). Does nothing on a client.
     */
    public OptionalInt open(Player player, Component title, Factory<? extends M> menu) {
        if (player.level().isClientSide()) {
            return OptionalInt.empty();
        }
        return player.openMenu(new SimpleMenuProvider((id, inventory, p) -> menu.create(id, inventory), title));
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }

    String namespace() {
        return namespace;
    }

    String path() {
        return path;
    }

    void set(MenuType<M> v) {
        value = v;
    }
}
