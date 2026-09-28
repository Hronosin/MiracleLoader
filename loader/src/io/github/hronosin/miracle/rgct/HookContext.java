package io.github.hronosin.miracle.rgct;

/**
 * What an intercepting hook sees and can change.
 *
 * <p>Primitive values travel boxed: an {@code int} argument is an {@link Integer}, a
 * {@code float} return value is a {@link Float}. Java's casts do the unboxing for you:
 * {@code (float) ctx.returnValue()}. Setting a value of the wrong box type fails immediately
 * with an error naming your mod, instead of a confusing crash inside the game.
 *
 * <p>When several mods intercept the same method, their hooks run in mod-id order and each one
 * sees what the previous ones changed. (That is the plan for today; layered merging comes later
 * and will keep this API.)
 */
public final class HookContext {

    private final Object self;
    private final Object[] args;
    private final String argKinds;
    private final char returnKind;
    private final boolean atHead;
    private final String methodLabel;

    private Object returnValue;
    private boolean cancelled;

    HookContext(Object self, Object[] args, String argKinds, char returnKind, boolean atHead,
                Object returnValue, String methodLabel) {
        this.self = self;
        this.args = args;
        this.argKinds = argKinds;
        this.returnKind = returnKind;
        this.atHead = atHead;
        this.returnValue = returnValue;
        this.methodLabel = methodLabel;
    }

    /**
     * The object the method was called on; {@code null} for static methods.
     */
    public Object self() {
        return self;
    }

    public int argCount() {
        return args.length;
    }

    /** Argument {@code i} (0-based, {@code this} not counted). Primitives come boxed. */
    public Object arg(int i) {
        checkIndex(i);
        return args[i];
    }

    /**
     * Replaces argument {@code i}. At the head this changes what the method body sees. At a
     * return it only changes what later hooks see.
     */
    public void setArg(int i, Object value) {
        checkIndex(i);
        checkType(argKinds.charAt(i), value, "argument " + i);
        args[i] = value;
    }

    /** True at the method head, false at a return. */
    public boolean isHead() {
        return atHead;
    }

    /** The value the method is about to return. Only available at a return. */
    public Object returnValue() {
        if (atHead) {
            throw new IllegalStateException(methodLabel + ": there is no return value at the head yet. "
                    + "Use interceptReturn, or cancel(value) to return early.");
        }
        return returnValue;
    }

    /** Replaces the value the method returns. Only available at a return. */
    public void setReturnValue(Object value) {
        if (atHead) {
            throw new IllegalStateException(methodLabel + ": setReturnValue() only works at a return. "
                    + "To skip the method from its head, use cancel(value).");
        }
        if (returnKind == 'V') {
            throw new IllegalStateException(methodLabel + " returns void, there is nothing to set");
        }
        checkType(returnKind, value, "return value");
        returnValue = value;
    }

    /** Skips the rest of a void method. Only at the head. */
    public void cancel() {
        if (returnKind != 'V') {
            throw new IllegalStateException(methodLabel + " returns a value: use cancel(value)");
        }
        cancelAt();
    }

    /** Skips the rest of the method and returns {@code value} instead. Only at the head. */
    public void cancel(Object value) {
        if (returnKind == 'V') {
            throw new IllegalStateException(methodLabel + " returns void: use cancel()");
        }
        checkType(returnKind, value, "return value");
        cancelAt();
        returnValue = value;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    private void cancelAt() {
        if (!atHead) {
            throw new IllegalStateException(methodLabel + ": cancel() only works at the head, "
                    + "the method has already run by the time it returns");
        }
        cancelled = true;
    }

    // Called from patched bytecode after a cancelled head.
    public Object cancelReturnValue() {
        return returnValue;
    }

    Object currentReturnValue() {
        return returnValue;
    }

    private void checkIndex(int i) {
        if (i < 0 || i >= args.length) {
            throw new IndexOutOfBoundsException(methodLabel + " has " + args.length + " argument(s), asked for " + i);
        }
    }

    private void checkType(char kind, Object value, String what) {
        Class<?> box = switch (kind) {
            case 'Z' -> Boolean.class;
            case 'B' -> Byte.class;
            case 'C' -> Character.class;
            case 'S' -> Short.class;
            case 'I' -> Integer.class;
            case 'J' -> Long.class;
            case 'F' -> Float.class;
            case 'D' -> Double.class;
            default -> null; // reference: checked by a checkcast when it goes back into the method
        };
        if (box == null) {
            return;
        }
        if (value == null || value.getClass() != box) {
            throw new IllegalArgumentException(methodLabel + ": " + what + " must be a " + box.getSimpleName()
                    + " (primitive " + primitiveName(kind) + "), got "
                    + (value == null ? "null" : value.getClass().getName() + " " + value)
                    + ". Hint: 2 is an Integer, 2f a Float, 2.0 a Double, 2L a Long.");
        }
    }

    private static String primitiveName(char kind) {
        return switch (kind) {
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'S' -> "short";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'F' -> "float";
            case 'D' -> "double";
            default -> "reference";
        };
    }
}
