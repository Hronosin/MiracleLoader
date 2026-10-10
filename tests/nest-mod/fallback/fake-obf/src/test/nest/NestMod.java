package test.nest;

/**
 * Fallback for fake-obf: say() without flying, with an anonymous class of its own (renamed, and a
 * nestmate: it reads the real class's private field), and Feathers replaced whole, without Wings.
 */
final class NestMod {

    private static String mood;

    static String say() {
        Object anon = new Object() {
            @Override
            public String toString() {
                return "a fallback's anonymous walk, " + mood + " " + new Step().how();
            }
        };
        return anon.toString();
    }

    static final class Feathers {
        public String flap() {
            return "no feathers in this version";
        }
    }

    /** Only in the fallback: added. */
    static final class Step {
        String how() {
            return "step by step";
        }
    }
}
