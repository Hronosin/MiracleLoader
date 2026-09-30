package io.github.hronosin.miracle.horizon;

import java.util.Random;

/**
 * Smooth randomness: Perlin noise in one to three dimensions, the same for the same seed, and
 * fractal noise ({@link #fbm}) that adds finer detail on top. Values lie roughly between -1 and 1.
 * For wobbling particles, uneven terrain in a structure, flickering light, anything that should
 * vary without jumping.
 *
 * <pre>{@code
 * Noise noise = new Noise(42);
 * double height = noise.fbm(x * 0.05, z * 0.05, 4) * 8;
 * }</pre>
 */
public final class Noise {

    private final int[] p = new int[512];

    public Noise(long seed) {
        int[] perm = new int[256];
        for (int i = 0; i < 256; i++) {
            perm[i] = i;
        }
        Random r = new Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = perm[i];
            perm[i] = perm[j];
            perm[j] = t;
        }
        for (int i = 0; i < 512; i++) {
            p[i] = perm[i & 255];
        }
    }

    public double at(double x) {
        return at(x, 0, 0);
    }

    public double at(double x, double y) {
        return at(x, y, 0);
    }

    /** Perlin noise at a point: 0 on every whole-number point, smooth everywhere. */
    public double at(double x, double y, double z) {
        int xi = (int) Math.floor(x) & 255;
        int yi = (int) Math.floor(y) & 255;
        int zi = (int) Math.floor(z) & 255;
        x -= Math.floor(x);
        y -= Math.floor(y);
        z -= Math.floor(z);
        double u = fade(x);
        double v = fade(y);
        double w = fade(z);
        int a = p[xi] + yi;
        int aa = p[a] + zi;
        int ab = p[a + 1] + zi;
        int b = p[xi + 1] + yi;
        int ba = p[b] + zi;
        int bb = p[b + 1] + zi;
        return lerp(w,
                lerp(v, lerp(u, grad(p[aa], x, y, z), grad(p[ba], x - 1, y, z)),
                        lerp(u, grad(p[ab], x, y - 1, z), grad(p[bb], x - 1, y - 1, z))),
                lerp(v, lerp(u, grad(p[aa + 1], x, y, z - 1), grad(p[ba + 1], x - 1, y, z - 1)),
                        lerp(u, grad(p[ab + 1], x, y - 1, z - 1), grad(p[bb + 1], x - 1, y - 1, z - 1))));
    }

    public double at(Vec v) {
        return at(v.x(), v.y(), v.z());
    }

    /**
     * Fractal noise: {@code octaves} layers, each twice as fine and half as strong as the one
     * before, scaled back to roughly -1..1.
     */
    public double fbm(double x, double y, double z, int octaves) {
        double sum = 0;
        double amp = 1;
        double norm = 0;
        for (int i = 0; i < octaves; i++) {
            sum += at(x, y, z) * amp;
            norm += amp;
            amp *= 0.5;
            x *= 2;
            y *= 2;
            z *= 2;
        }
        return sum / norm;
    }

    public double fbm(double x, double z, int octaves) {
        return fbm(x, 0.5, z, octaves);
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v = h < 4 ? y : h == 12 || h == 14 ? x : z;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }
}
