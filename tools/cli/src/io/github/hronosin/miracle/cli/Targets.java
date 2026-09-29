package io.github.hronosin.miracle.cli;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What {@code targets} in miracle.project.toml can say, and what it means today:
 *
 * <pre>
 * "26.2"        that version
 * "26.*"        every release whose id starts with 26.  (26.1, 26.1.1, 26.1.2, 26.2, ...)
 * ">=1.21.11"   every release from 1.21.11 on
 * "latest"      the latest release
 * </pre>
 *
 * Patterns are expanded against Mojang's version list (releases only, no snapshots), so a
 * project picks up new versions as Mojang releases them, and bake fetches their dictionaries
 * itself. Nobody writes mappings by hand.
 */
final class Targets {

    private Targets() {
    }

    /** True if the entry is a pattern rather than one version. */
    static boolean isPattern(String t) {
        return t.equals("latest") || t.endsWith(".*") || t.startsWith(">=");
    }

    /** Every version the entries mean, newest first, each once. */
    static List<String> expand(List<String> entries) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        List<String> releases = null;
        for (String raw : entries) {
            String t = raw.strip();
            if (!isPattern(t)) {
                out.add(t);
                continue;
            }
            if (releases == null) {
                releases = releases();
            }
            List<String> hit = new ArrayList<>();
            if (t.equals("latest")) {
                hit.add(Mojang.latestRelease());
            } else if (t.endsWith(".*")) {
                String prefix = t.substring(0, t.length() - 1); // keeps the dot: "26." matches 26.1, not 260
                releases.stream().filter(v -> v.startsWith(prefix)).forEach(hit::add);
            } else {
                String min = t.substring(2).strip();
                releases.stream().filter(v -> compare(v, min) >= 0).forEach(hit::add);
            }
            if (hit.isEmpty()) {
                throw new Miracle.Heresy("targets: '" + t + "' matches no Minecraft release");
            }
            out.addAll(hit);
        }
        List<String> sorted = new ArrayList<>(out);
        sorted.sort(Comparator.comparing((String v) -> v, Targets::compare).reversed());
        return sorted;
    }

    /** Release ids from Mojang's list (cached for an hour, older if offline). */
    static List<String> releases() throws IOException {
        List<String> ids = new ArrayList<>();
        for (Object v : Json.arr(Json.get(Json.parse(Mojang.manifest()), "versions"))) {
            if ("release".equals(Json.str(v, "type"))) {
                ids.add(Json.str(v, "id"));
            }
        }
        return ids;
    }

    /** 1.21.11 < 26.1 < 26.1.2 < 26.2; missing parts count as 0. */
    static int compare(String a, String b) {
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? number(x[i]) : 0;
            long q = i < y.length ? number(y[i]) : 0;
            if (p != q) {
                return Long.compare(p, q);
            }
        }
        return 0;
    }

    private static long number(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
