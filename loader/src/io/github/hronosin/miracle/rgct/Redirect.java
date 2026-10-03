package io.github.hronosin.miracle.rgct;

/**
 * The shapes of a call RGCT can redirect, by arity: {@code CallN} for calls that return a value,
 * {@code DoN} for void ones. For an instance method the receiver is the first argument, so
 * {@code Foo::bar} with one parameter is a {@code Call2<Foo, A, R>}. Up to nine, receiver included.
 *
 * <p>Both sides of {@link Rgct.MethodTarget#redirect} have the same shape, so the compiler checks
 * that the replacement fits the call: it takes what the call passes, and its result goes where the
 * call's did. A replacement may take wider types or return a narrower one; one that returns
 * {@code Object} where the call returned something else is cast when it runs.
 *
 * <pre>{@code
 * rgct.target("net.minecraft.client.renderer.LevelRenderer")
 *     .method("extractSectionDrawGroups")
 *     .redirect(ChunkSectionLayer::values, MyMod::layers);   // MyMod.layers() returns ChunkSectionLayer[]
 * }</pre>
 *
 * <p>Write both sides as method references, or the call as a lambda making exactly that call. RGCT
 * reads them by name as the mod class loads (see {@link Rgct.MethodTarget#redirect}), so naming
 * game classes here loads none of them early.
 */
public final class Redirect {

    private Redirect() {
    }

    @FunctionalInterface
    public interface Call0<R> {
        R call();
    }

    @FunctionalInterface
    public interface Call1<A, R> {
        R call(A a);
    }

    @FunctionalInterface
    public interface Call2<A, B, R> {
        R call(A a, B b);
    }

    @FunctionalInterface
    public interface Call3<A, B, C, R> {
        R call(A a, B b, C c);
    }

    @FunctionalInterface
    public interface Call4<A, B, C, D, R> {
        R call(A a, B b, C c, D d);
    }

    @FunctionalInterface
    public interface Call5<A, B, C, D, E, R> {
        R call(A a, B b, C c, D d, E e);
    }

    @FunctionalInterface
    public interface Call6<A, B, C, D, E, F, R> {
        R call(A a, B b, C c, D d, E e, F f);
    }

    @FunctionalInterface
    public interface Call7<A, B, C, D, E, F, G, R> {
        R call(A a, B b, C c, D d, E e, F f, G g);
    }

    @FunctionalInterface
    public interface Call8<A, B, C, D, E, F, G, H, R> {
        R call(A a, B b, C c, D d, E e, F f, G g, H h);
    }

    @FunctionalInterface
    public interface Call9<A, B, C, D, E, F, G, H, I, R> {
        R call(A a, B b, C c, D d, E e, F f, G g, H h, I i);
    }

    @FunctionalInterface
    public interface Do0 {
        void call();
    }

    @FunctionalInterface
    public interface Do1<A> {
        void call(A a);
    }

    @FunctionalInterface
    public interface Do2<A, B> {
        void call(A a, B b);
    }

    @FunctionalInterface
    public interface Do3<A, B, C> {
        void call(A a, B b, C c);
    }

    @FunctionalInterface
    public interface Do4<A, B, C, D> {
        void call(A a, B b, C c, D d);
    }

    @FunctionalInterface
    public interface Do5<A, B, C, D, E> {
        void call(A a, B b, C c, D d, E e);
    }

    @FunctionalInterface
    public interface Do6<A, B, C, D, E, F> {
        void call(A a, B b, C c, D d, E e, F f);
    }

    @FunctionalInterface
    public interface Do7<A, B, C, D, E, F, G> {
        void call(A a, B b, C c, D d, E e, F f, G g);
    }

    @FunctionalInterface
    public interface Do8<A, B, C, D, E, F, G, H> {
        void call(A a, B b, C c, D d, E e, F f, G g, H h);
    }

    @FunctionalInterface
    public interface Do9<A, B, C, D, E, F, G, H, I> {
        void call(A a, B b, C c, D d, E e, F f, G g, H h, I i);
    }
}
