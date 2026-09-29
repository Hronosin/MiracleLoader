package io.github.hronosin.miracle.rgct;

import io.github.hronosin.miracle.rgct.HookContext.Effect;
import io.github.hronosin.miracle.rgct.HookContext.Op;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Merges the effects every hook on a method asked for into one result. The rules are fixed, so the
 * result doesn't depend on the order hooks ran in. See {@link HookContext} for the rules.
 */
final class Layers {

    private Layers() {
    }

    /** Head: returns {@link HookDispatch#PROCEED}, or the value to return if the method was cancelled. */
    static Object head(HookContext ctx, Object[] args) {
        List<Effect> cancels = ctx.effects.stream().filter(e -> e.op() == Op.CANCEL).toList();
        if (!cancels.isEmpty()) {
            if (ctx.returnKind() == 'V') {
                return null; // any value but PROCEED; the patched code pops it and returns
            }
            return pick(lastPerHook(cancels), ctx.methodLabel(), "cancel value", "cancels with");
        }
        Object[] merged = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            merged[i] = slot(ctx, i, ctx.argKinds().charAt(i), args[i], "argument " + i);
        }
        System.arraycopy(merged, 0, args, 0, args.length);
        return HookDispatch.PROCEED;
    }

    /** Return: the value the method finally returns. */
    static Object ret(HookContext ctx, Object original) {
        if (ctx.returnKind() == 'V') {
            return null;
        }
        return slot(ctx, HookContext.RETURN, ctx.returnKind(), original, "return value");
    }

    private static Object slot(HookContext ctx, int slot, char kind, Object original, String what) {
        List<Effect> mine = new ArrayList<>();
        for (Effect e : ctx.effects) {
            if (e.slot() == slot && e.op() != Op.CANCEL) {
                mine.add(e);
            }
        }
        if (mine.isEmpty()) {
            return original;
        }

        List<Effect> sets = mine.stream().filter(e -> e.op() == Op.SET).toList();
        Object base = sets.isEmpty() ? original : pick(lastPerHook(sets), ctx.methodLabel(), what, "sets");

        double add = 0;
        double mul = 1;
        Double lo = null;
        Double hi = null;
        boolean numeric = false;
        for (Effect e : mine) {
            switch (e.op()) {
                case ADD -> {
                    add += ((Number) e.a()).doubleValue();
                    numeric = true;
                }
                case MULTIPLY -> {
                    mul *= ((Number) e.a()).doubleValue();
                    numeric = true;
                }
                case CLAMP -> {
                    if (e.a() != null) {
                        double v = ((Number) e.a()).doubleValue();
                        lo = lo == null ? v : Math.max(lo, v);
                    }
                    if (e.b() != null) {
                        double v = ((Number) e.b()).doubleValue();
                        hi = hi == null ? v : Math.min(hi, v);
                    }
                    numeric = true;
                }
                default -> {
                }
            }
        }
        if (!numeric) {
            return base;
        }
        if (!(base instanceof Number n)) {
            throw new IllegalStateException(ctx.methodLabel() + ": " + what + " is " + base
                    + ", cannot add to or multiply it");
        }
        if (lo != null && hi != null && lo > hi) {
            String who = mine.stream().filter(e -> e.op() == Op.CLAMP)
                    .map(e -> "'" + e.modId() + "' [" + bound(e.a()) + ", " + bound(e.b()) + "]")
                    .collect(Collectors.joining(", "));
            throw new RgctConflictException("RGCT conflict at " + ctx.methodLabel() + ", " + what
                    + ": the clamp ranges don't overlap: " + who + ". There is no value that satisfies all of them.");
        }

        double v = (n.doubleValue() + add) * mul;
        if (lo != null) {
            v = Math.max(v, lo);
        }
        if (hi != null) {
            v = Math.min(v, hi);
        }
        return convert(kind, v);
    }

    /** A hook that calls set twice meant the second one; don't report it as disagreeing with itself. */
    private static Collection<Effect> lastPerHook(List<Effect> effects) {
        Map<Integer, Effect> byHook = new LinkedHashMap<>();
        for (Effect e : effects) {
            byHook.put(e.hookId(), e);
        }
        return byHook.values();
    }

    /** Highest priority wins; at that priority everyone must agree. */
    private static Object pick(Collection<Effect> candidates, String label, String what, String verb) {
        int top = candidates.stream().mapToInt(Effect::priority).max().orElseThrow();
        List<Effect> atTop = candidates.stream().filter(e -> e.priority() == top).toList();
        Object value = atTop.getFirst().a();
        for (Effect e : atTop) {
            if (!Objects.equals(e.a(), value)) {
                String who = atTop.stream()
                        .map(x -> "'" + x.modId() + "' " + verb + " " + show(x.a()))
                        .collect(Collectors.joining(", "));
                throw new RgctConflictException("RGCT conflict at " + label + ", " + what
                        + " (priority " + top + "): " + who + ". Mods that set the same value must agree, "
                        + "or one of them needs a higher priority: .method(...).priority(10).intercept...(...)");
            }
        }
        return value;
    }

    private static Object convert(char kind, double v) {
        return switch (kind) {
            case 'I' -> (int) Math.round(v);
            case 'J' -> Math.round(v);
            case 'S' -> (short) Math.round(v);
            case 'B' -> (byte) Math.round(v);
            case 'F' -> (float) v;
            case 'D' -> v;
            default -> throw new IllegalStateException("not numeric: " + kind);
        };
    }

    private static String bound(Object b) {
        return b == null ? "-" : b.toString();
    }

    private static String show(Object v) {
        return v instanceof String s ? "\"" + s + "\"" : String.valueOf(v);
    }
}
