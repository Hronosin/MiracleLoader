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
        if (!ctx.hasEffects()) {
            return HookDispatch.PROCEED; // the common case: every hook only looked
        }
        List<Effect> cancels = new ArrayList<>();
        long touched = 0; // argument slots some effect is about (bit i = argument i, up to 64)
        boolean many = false;
        for (Effect e : ctx.effects()) {
            if (e.op() == Op.CANCEL) {
                cancels.add(e);
            } else if (e.slot() < 64) {
                touched |= 1L << e.slot();
            } else {
                many = true;
            }
        }
        if (!cancels.isEmpty()) {
            if (ctx.returnKind() == 'V') {
                return null; // any value but PROCEED; the patched code pops it and returns
            }
            return pick(lastPerHook(cancels), ctx.methodLabel(), "cancel value", "cancels with");
        }
        Object[] merged = args.clone();
        for (int i = 0; i < args.length; i++) {
            if (many || (i < 64 && (touched & (1L << i)) != 0)) {
                merged[i] = slot(ctx, i, ctx.argKinds().charAt(i), args[i], "argument " + i);
            }
        }
        System.arraycopy(merged, 0, args, 0, args.length);
        return HookDispatch.PROCEED;
    }

    /** Return: the value the method finally returns. */
    static Object ret(HookContext ctx, Object original) {
        if (ctx.returnKind() == 'V' || !ctx.hasEffects()) {
            return ctx.returnKind() == 'V' ? null : original;
        }
        return slot(ctx, HookContext.RETURN, ctx.returnKind(), original, "return value");
    }

    private static Object slot(HookContext ctx, int slot, char kind, Object original, String what) {
        List<Effect> sets = null;
        double add = 0;
        double mul = 1;
        double lo = Double.NEGATIVE_INFINITY;
        double hi = Double.POSITIVE_INFINITY;
        boolean clamped = false;
        boolean numeric = false;
        List<Effect> effects = ctx.effects();
        for (int i = 0; i < effects.size(); i++) {
            Effect e = effects.get(i);
            if (e.slot() != slot) {
                continue;
            }
            switch (e.op()) {
                case SET -> {
                    if (sets == null) {
                        sets = new ArrayList<>(2);
                    }
                    sets.add(e);
                }
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
                        lo = Math.max(lo, ((Number) e.a()).doubleValue());
                    }
                    if (e.b() != null) {
                        hi = Math.min(hi, ((Number) e.b()).doubleValue());
                    }
                    clamped = true;
                    numeric = true;
                }
                default -> {
                }
            }
        }
        Object base = sets == null ? original : pick(lastPerHook(sets), ctx.methodLabel(), what, "sets");
        if (!numeric) {
            return base;
        }
        if (!(base instanceof Number n)) {
            throw new IllegalStateException(ctx.methodLabel() + ": " + what + " is " + base
                    + ", cannot add to or multiply it");
        }
        if (clamped && lo > hi) {
            String who = effects.stream().filter(e -> e.slot() == slot && e.op() == Op.CLAMP)
                    .map(e -> "'" + e.modId() + "' [" + bound(e.a()) + ", " + bound(e.b()) + "]")
                    .collect(Collectors.joining(", "));
            throw new RgctConflictException("RGCT conflict at " + ctx.methodLabel() + ", " + what
                    + ": the clamp ranges don't overlap: " + who + ". There is no value that satisfies all of them.");
        }
        double v = (n.doubleValue() + add) * mul;
        if (clamped) {
            v = Math.min(Math.max(v, lo), hi);
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
