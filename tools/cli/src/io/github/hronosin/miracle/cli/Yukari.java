package io.github.hronosin.miracle.cli;

import java.util.List;

/**
 * The youkai of boundaries has opinions about your errors. She peeks out of a gap after the
 * plain explanation (never instead of it) with a remark of her own; the same error always gets the
 * same remark, so output stays repeatable. MIRACLE_YUKARI=0 (or -Dmiracle.yukari=false) closes the
 * gap, for scripts and for people who'd rather not be teased by a thousand-year-old youkai.
 */
final class Yukari {

    private static final List<String> HERESY = List.of(
            "Ara, ara. You've wandered to the wrong side of a boundary. It happens to the best of us. And to you.",
            "I could fix this with a single gap, but then you'd learn nothing. Read the line above, darling.",
            "Such a small mistake. I've slept through bigger ones. I've slept through most things, really.",
            "Ran would have noticed this already. Then again, Ran also does all my chores, so perhaps that's unfair.",
            "The line between 'working' and 'not working' is thinner than you think. I would know: I draw those lines.",
            "Even Reimu reads the error message. Eventually. After tea.",
            "Don't look at me like that. I only opened the gap; you're the one who walked through it.",
            "A thousand years of watching people type, and still nobody reads what the screen says. Charming.");

    private static final List<String> SILENT = List.of(
            "Files are only boundaries between bytes and nothing. Something on your machine is holding one shut.",
            "Your disk said no. Disks are like shrine maidens: stubborn, and usually right.");

    private static final List<String> DEATH = List.of(
            "Dying is only crossing a boundary, and this one has a way back: fix it and pray again. I'll be napping.",
            "Oh my, it fell over. The log above knows why; I only watched from a gap.",
            "Death in Gensokyo is rarely permanent. In Minecraft, even less. Read, fix, return.");

    private Yukari() {
    }

    static boolean awake() {
        String env = System.getenv("MIRACLE_YUKARI");
        return !"0".equals(env) && !"false".equalsIgnoreCase(env) && !"false".equalsIgnoreCase(System.getProperty("miracle.yukari"));
    }

    /** Her remark on a heresy (a mistake in how the toolchain was used). */
    static String onHeresy(String message) {
        return pick(HERESY, message);
    }

    /** Her remark on a file system error. */
    static String onFiles(java.io.IOException e) {
        if (e instanceof java.nio.file.DirectoryNotEmptyException) {
            return "Deleted and not deleted at the same time? That's my trick. On Windows, OneDrive does it without asking.";
        }
        if (e instanceof java.nio.file.AccessDeniedException) {
            return "A locked door. How quaint. I'd simply go around it, but you'll have to close whatever is holding it.";
        }
        return pick(SILENT, String.valueOf(e.getMessage()));
    }

    /** Her remark on a death: a known cause if there is one. */
    static String onDeath(Autopsy.Cause cause, int code) {
        if (cause == null) {
            return pick(DEATH, Integer.toString(code));
        }
        return switch (cause) {
            case NO_GRAPHICS -> "No OpenGL here. Are you in a box inside a box? A virtual machine, a remote desktop... I adore"
                    + " nested boundaries, but the game needs a real window to a real GPU.";
            case NO_DISPLAY -> "A window with no world to open onto. Even I need somewhere to put a gap.";
            case PORT_TAKEN -> "Someone is already sitting at that port. Another server, perhaps one you forgot. Look behind you.";
            case NO_MEMORY -> "It tried to carry more than it could hold. Relatable. Give it more memory; I'll give it my sympathy.";
        };
    }

    /** "  Yukari, from a gap: ..." ready to print, or null while she sleeps. */
    static String says(String remark) {
        return awake() && remark != null ? "  Yukari, from a gap: \"" + remark + "\"" : null;
    }

    private static String pick(List<String> pool, String seed) {
        return pool.get(Math.floorMod(seed.hashCode(), pool.size()));
    }
}
