package io.github.hronosin.miracle.rgct;

import java.util.ArrayList;
import java.util.List;

/**
 * What an intercepting hook sees, and the effects it asks for.
 *
 * <h2>Layers</h2>
 * Hooks don't change the game directly. Every hook on a method sees the same snapshot (the
 * arguments and return value as vanilla produced them) and declares <em>effects</em>. Once all
 * hooks have run, RGCT merges the effects by fixed rules and applies the result once:
 *
 * <ol>
 *   <li><b>set</b> replaces the base value. Several mods may set it only if they agree, or if one
 *       has a higher {@code priority}. Otherwise: a conflict error naming both mods.</li>
 *   <li><b>addTo</b> amounts are summed onto the base.</li>
 *   <li><b>multiply</b> factors are all multiplied in.</li>
 *   <li><b>clamp</b> ranges are intersected and applied last.</li>
 *   <li><b>cancel</b> (at the head) wins if any mod asks for it.</li>
 * </ol>
 *
 * So the result never depends on which mod happens to load first: two mods that multiply jump
 * power by 1.5 and 2 give 3x, whatever their order.
 *
 * <p>Primitive values travel boxed: an {@code int} argument is an {@link Integer}, a
 * {@code float} return value is a {@link Float}. Java's casts unbox them:
 * {@code (float) ctx.returnValue()}. {@code set} needs exactly the right box type;
 * {@code addTo}/{@code multiply}/{@code clamp} take any {@link Number}. Integral results are
 * rounded to the nearest value.
 */
public final class HookContext {

    enum Op { SET, ADD, MULTIPLY, CLAMP, CANCEL }

    /** One requested change. {@code slot} is an argument index or {@link #RETURN}. */
    record Effect(Op op, int slot, Object a, Object b, int hookId, String modId, int priority) {
    }

    static final int RETURN = -1;

    private final Object self;
    private final Object[] args;
    private final String argKinds;
    private final char returnKind;
    private final boolean atHead;
    private final Object returnValue;
    private final String methodLabel;

    final List<Effect> effects = new ArrayList<>();

    private int hookId = -1;
    private String modId = "?";
    private int priority;

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

    /** Called by the dispatcher before each hook, so effects know whose they are. */
    void enter(int hookId, String modId, int priority) {
        this.hookId = hookId;
        this.modId = modId;
        this.priority = priority;
    }

    // --- the snapshot ---------------------------------------------------------------------------

    /** The object the method was called on; {@code null} for static methods. */
    public Object self() {
        return self;
    }

    public int argCount() {
        return args.length;
    }

    /** Argument {@code i} (0-based, {@code this} not counted), as the method received it. */
    public Object arg(int i) {
        checkIndex(i);
        return args[i];
    }

    /** True at the method head, false at a return. */
    public boolean isHead() {
        return atHead;
    }

    /** The value vanilla is about to return, before any mod's effects. Only at a return. */
    public Object returnValue() {
        if (atHead) {
            throw new IllegalStateException(methodLabel + ": there is no return value at the head yet. "
                    + "Use interceptReturn, or cancel(value) to return early.");
        }
        return returnValue;
    }

    // --- effects on arguments (head only) --------------------------------------------------------

    /** Replaces argument {@code i}. */
    public void setArg(int i, Object value) {
        requireHead("setArg");
        checkIndex(i);
        checkType(argKinds.charAt(i), value, "argument " + i);
        add(Op.SET, i, value, null);
    }

    /** Adds {@code amount} to numeric argument {@code i}. Stacks with other mods. */
    public void addToArg(int i, Number amount) {
        requireHead("addToArg");
        checkIndex(i);
        requireNumeric(argKinds.charAt(i), "argument " + i, amount);
        add(Op.ADD, i, amount, null);
    }

    /** Multiplies numeric argument {@code i} by {@code factor}. Stacks with other mods. */
    public void multiplyArg(int i, Number factor) {
        requireHead("multiplyArg");
        checkIndex(i);
        requireNumeric(argKinds.charAt(i), "argument " + i, factor);
        add(Op.MULTIPLY, i, factor, null);
    }

    /** Keeps numeric argument {@code i} within [min, max]; either bound may be null. */
    public void clampArg(int i, Number min, Number max) {
        requireHead("clampArg");
        checkIndex(i);
        checkClamp(argKinds.charAt(i), "argument " + i, min, max);
        add(Op.CLAMP, i, min, max);
    }

    // --- effects on the return value (return only) ------------------------------------------------

    /** Replaces the return value. */
    public void setReturnValue(Object value) {
        requireReturn("setReturnValue");
        checkType(returnKind, value, "return value");
        add(Op.SET, RETURN, value, null);
    }

    /** Adds {@code amount} to a numeric return value. Stacks with other mods. */
    public void addToReturnValue(Number amount) {
        requireReturn("addToReturnValue");
        requireNumeric(returnKind, "return value", amount);
        add(Op.ADD, RETURN, amount, null);
    }

    /** Multiplies a numeric return value by {@code factor}. Stacks with other mods. */
    public void multiplyReturnValue(Number factor) {
        requireReturn("multiplyReturnValue");
        requireNumeric(returnKind, "return value", factor);
        add(Op.MULTIPLY, RETURN, factor, null);
    }

    /** Keeps a numeric return value within [min, max]; either bound may be null. */
    public void clampReturnValue(Number min, Number max) {
        requireReturn("clampReturnValue");
        checkClamp(returnKind, "return value", min, max);
        add(Op.CLAMP, RETURN, min, max);
    }

    // --- cancelling (head only) -----------------------------------------------------------------

    /** Skips a void method. If any mod cancels, the method doesn't run. */
    public void cancel() {
        requireHead("cancel");
        if (returnKind != 'V') {
            throw new IllegalStateException(methodLabel + " returns a value: use cancel(value)");
        }
        add(Op.CANCEL, RETURN, null, null);
    }

    /**
     * Skips the method and returns {@code value} instead. If several mods cancel with different
     * values, priority decides, and a tie is a conflict.
     */
    public void cancel(Object value) {
        requireHead("cancel");
        if (returnKind == 'V') {
            throw new IllegalStateException(methodLabel + " returns void: use cancel()");
        }
        checkType(returnKind, value, "return value");
        add(Op.CANCEL, RETURN, value, null);
    }

    // --- internals --------------------------------------------------------------------------------

    String methodLabel() {
        return methodLabel;
    }

    char returnKind() {
        return returnKind;
    }

    String argKinds() {
        return argKinds;
    }

    private void add(Op op, int slot, Object a, Object b) {
        effects.add(new Effect(op, slot, a, b, hookId, modId, priority));
    }

    private void requireHead(String what) {
        if (!atHead) {
            throw new IllegalStateException(methodLabel + ": " + what + "() only works in interceptHead, "
                    + "the method has already run by the time it returns");
        }
    }

    private void requireReturn(String what) {
        if (atHead) {
            throw new IllegalStateException(methodLabel + ": " + what + "() only works in interceptReturn. "
                    + "To skip the method from its head, use cancel(value).");
        }
        if (returnKind == 'V') {
            throw new IllegalStateException(methodLabel + " returns void, there is no return value to change");
        }
    }

    private void checkIndex(int i) {
        if (i < 0 || i >= args.length) {
            throw new IndexOutOfBoundsException(methodLabel + " has " + args.length + " argument(s), asked for " + i);
        }
    }

    static boolean isNumeric(char kind) {
        return kind == 'I' || kind == 'J' || kind == 'F' || kind == 'D' || kind == 'S' || kind == 'B';
    }

    private void requireNumeric(char kind, String what, Number n) {
        if (!isNumeric(kind)) {
            throw new IllegalStateException(methodLabel + ": " + what + " is a " + typeName(kind)
                    + ", not a number. Only set can change it.");
        }
        if (n == null) {
            throw new IllegalArgumentException(methodLabel + ": null amount for " + what);
        }
    }

    private void checkClamp(char kind, String what, Number min, Number max) {
        if (min == null && max == null) {
            throw new IllegalArgumentException(methodLabel + ": clamp on " + what + " needs at least one bound");
        }
        requireNumeric(kind, what, min != null ? min : max);
        if (min != null && max != null && min.doubleValue() > max.doubleValue()) {
            throw new IllegalArgumentException(methodLabel + ": clamp on " + what + " has min " + min + " > max " + max);
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
                    + " (primitive " + typeName(kind) + "), got "
                    + (value == null ? "null" : value.getClass().getName() + " " + value)
                    + ". Hint: 2 is an Integer, 2f a Float, 2.0 a Double, 2L a Long.");
        }
    }

    static String typeName(char kind) {
        return switch (kind) {
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'S' -> "short";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'V' -> "void";
            default -> "reference";
        };
    }
}
