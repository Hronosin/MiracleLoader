package io.github.hronosin.miracle.horizon;

import java.util.function.DoubleUnaryOperator;

/**
 * Easing: how a change of 0 to 1 is paced. Every one maps 0 to 0 and 1 to 1; what happens
 * between is the character. {@code BACK} and {@code ELASTIC} overshoot on purpose.
 *
 * <pre>{@code
 * double size = Ease.OUT_BACK.between(1.0, 3.0, progress);
 * }</pre>
 */
public enum Ease implements DoubleUnaryOperator {
    LINEAR(t -> t),
    IN_QUAD(t -> t * t),
    OUT_QUAD(t -> 1 - (1 - t) * (1 - t)),
    IN_OUT_QUAD(t -> t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2),
    IN_CUBIC(t -> t * t * t),
    OUT_CUBIC(t -> 1 - Math.pow(1 - t, 3)),
    IN_OUT_CUBIC(t -> t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2),
    IN_SINE(t -> 1 - Math.cos(t * Math.PI / 2)),
    OUT_SINE(t -> Math.sin(t * Math.PI / 2)),
    IN_OUT_SINE(t -> -(Math.cos(Math.PI * t) - 1) / 2),
    IN_EXPO(t -> t == 0 ? 0 : Math.pow(2, 10 * t - 10)),
    OUT_EXPO(t -> t == 1 ? 1 : 1 - Math.pow(2, -10 * t)),
    IN_BACK(t -> 2.70158 * t * t * t - 1.70158 * t * t),
    OUT_BACK(t -> 1 + 2.70158 * Math.pow(t - 1, 3) + 1.70158 * Math.pow(t - 1, 2)),
    OUT_ELASTIC(t -> t == 0 ? 0 : t == 1 ? 1 : Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI / 3)) + 1),
    OUT_BOUNCE(Ease::bounce),
    IN_BOUNCE(t -> 1 - bounce(1 - t));

    private final DoubleUnaryOperator f;

    Ease(DoubleUnaryOperator f) {
        this.f = f;
    }

    /** The eased progress for {@code t}, clamped to 0..1 first. */
    @Override
    public double applyAsDouble(double t) {
        return f.applyAsDouble(Math.max(0, Math.min(1, t)));
    }

    /** From {@code a} to {@code b} at progress {@code t}. */
    public double between(double a, double b, double t) {
        return a + (b - a) * applyAsDouble(t);
    }

    /** From {@code a} to {@code b} at progress {@code t}. */
    public Vec between(Vec a, Vec b, double t) {
        return a.lerp(b, applyAsDouble(t));
    }

    private static double bounce(double t) {
        double n = 7.5625;
        double d = 2.75;
        if (t < 1 / d) {
            return n * t * t;
        } else if (t < 2 / d) {
            t -= 1.5 / d;
            return n * t * t + 0.75;
        } else if (t < 2.5 / d) {
            t -= 2.25 / d;
            return n * t * t + 0.9375;
        }
        t -= 2.625 / d;
        return n * t * t + 0.984375;
    }
}
