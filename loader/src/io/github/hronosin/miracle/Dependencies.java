package io.github.hronosin.miracle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code depends = ["miracle-toolchain", "other-mod>=1.2"]} in {@code miracle.mod.toml}: every
 * mod listed must be there (and new enough), and runs before the mod that needs it. The id
 * {@code miracle} means the loader itself.
 */
final class Dependencies {

    /** One entry of {@code depends}: an id, and optionally a minimum version. */
    record Requirement(String id, String atLeast) {

        static Requirement parse(String s) {
            String t = s.strip();
            int ge = t.indexOf(">=");
            String id = (ge < 0 ? t : t.substring(0, ge)).strip();
            String min = ge < 0 ? null : t.substring(ge + 2).strip();
            if (!id.matches("[a-z][a-z0-9_-]{1,63}")) {
                throw new IllegalArgumentException("'" + s + "' is not a mod id (optionally followed by >=version)");
            }
            if (min != null && !min.matches("\\d+(\\.\\d+)*([-+].*)?")) {
                throw new IllegalArgumentException("'" + s + "': the version after >= should look like 1.2.3");
            }
            return new Requirement(id, min);
        }

        @Override
        public String toString() {
            return atLeast == null ? id : id + " >= " + atLeast;
        }
    }

    private Dependencies() {
    }

    /**
     * Checks every requirement and returns the mods in load order: dependencies first,
     * otherwise alphabetical (the order discovery already produced).
     */
    static List<ModDiscovery.ModInfo> resolve(List<ModDiscovery.ModInfo> mods, String loaderVersion) {
        Map<String, ModDiscovery.ModInfo> byId = new LinkedHashMap<>();
        mods.forEach(m -> byId.put(m.id(), m));

        List<String> problems = new ArrayList<>();
        for (ModDiscovery.ModInfo m : mods) {
            for (Requirement r : m.depends()) {
                String have;
                if (r.id().equals("miracle")) {
                    have = loaderVersion;
                } else if (byId.containsKey(r.id())) {
                    have = byId.get(r.id()).version();
                } else {
                    problems.add(m.id() + " needs " + r + ", which is not in the mods folder."
                            + hint(r.id()));
                    continue;
                }
                if (r.atLeast() != null && compare(have, r.atLeast()) < 0) {
                    problems.add(m.id() + " needs " + r + ", but " + r.id() + " " + have + " is here. Update it.");
                }
                if (r.id().equals(m.id())) {
                    problems.add(m.id() + " depends on itself. Bold, but no.");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new MiracleFailure("Some mods came without what they need:\n    " + String.join("\n    ", problems));
        }

        List<ModDiscovery.ModInfo> order = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        for (ModDiscovery.ModInfo m : mods) {
            visit(m, byId, done, new ArrayList<>(), order);
        }
        return order;
    }

    private static void visit(ModDiscovery.ModInfo m, Map<String, ModDiscovery.ModInfo> byId, Set<String> done,
                              List<String> path, List<ModDiscovery.ModInfo> order) {
        if (done.contains(m.id())) {
            return;
        }
        if (path.contains(m.id())) {
            List<String> cycle = new ArrayList<>(path.subList(path.indexOf(m.id()), path.size()));
            cycle.add(m.id());
            throw new MiracleFailure("These mods depend on each other in a circle: " + String.join(" -> ", cycle)
                    + ". Someone has to go first, and none of them will.");
        }
        path.add(m.id());
        for (Requirement r : m.depends()) {
            ModDiscovery.ModInfo dep = byId.get(r.id());
            if (dep != null) {
                visit(dep, byId, done, path, order);
            }
        }
        path.removeLast();
        done.add(m.id());
        order.add(m);
    }

    private static String hint(String id) {
        return id.equals("miracle-toolchain")
                ? " It's the MiracleToolChain library: miracle-toolchain-*.jar, from the same place you got the loader."
                : "";
    }

    /** 1.10 > 1.9; missing parts count as 0; anything after - or + is ignored. */
    static int compare(String a, String b) {
        String[] x = a.split("[-+]", 2)[0].split("\\.");
        String[] y = b.split("[-+]", 2)[0].split("\\.");
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
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
