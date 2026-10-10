package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.toolchain.Liturgy.Lexicon;
import io.github.hronosin.miracle.toolchain.Liturgy.Molang;
import io.github.hronosin.miracle.toolchain.Liturgy.Rite;
import io.github.hronosin.miracle.toolchain.Liturgy.Scene;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Animation controllers in Bedrock's format ({@code .animation_controllers.json}, what Blockbench
 * exports): state machines that decide which animations play, and how much. Each controller is in
 * one state at a time; a state plays its animations (each with a weight, a number or Molang),
 * moves to another state when one of its transitions' conditions holds (the first that does, one
 * move per frame), runs Molang {@code on_entry} and {@code on_exit} (to set variables), and
 * crossfades over {@code blend_transition} seconds. Several controllers play together, like a choir.
 * Pure data and arithmetic, no game classes: {@link Sculptor} feeds it frames.
 */
final class Choir {

    record Weighted(String animation, Molang weight) {
    }

    record Transition(String target, Molang condition) {
    }

    record State(String name, List<Weighted> animations, List<Transition> transitions, Molang onEntry, Molang onExit,
                 double blend) {
    }

    record Controller(String name, String initial, Map<String, State> states) {
    }

    /** One animation to play this frame: {@code seconds} since it started, at {@code weight}. */
    record Voice(Rite rite, double seconds, double weight) {
    }

    private Choir() {
    }

    /** Every controller in a {@code .animation_controllers.json}, in file order. */
    static List<Controller> read(String json, List<String> notes, Lexicon lex) {
        Object root = Clay.Json.parse(json);
        if (!(Clay.Json.get(root, "animation_controllers") instanceof Map<?, ?> all)) {
            throw new IllegalArgumentException("no \"animation_controllers\" in it (a Bedrock controller file from Blockbench?)");
        }
        List<Controller> out = new ArrayList<>();
        for (var e : all.entrySet()) {
            String name = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof Map<?, ?> c) || !(c.get("states") instanceof Map<?, ?> states) || states.isEmpty()) {
                notes.add(name + ": no states; skipped");
                continue;
            }
            Map<String, State> byName = new LinkedHashMap<>();
            for (var st : states.entrySet()) {
                String sn = String.valueOf(st.getKey());
                String where = name + " / " + sn;
                Map<?, ?> m = st.getValue() instanceof Map<?, ?> mm ? mm : Map.of();
                List<Weighted> anims = new ArrayList<>();
                if (m.get("animations") instanceof List<?> list) {
                    for (Object a : list) {
                        if (a instanceof String an) {
                            anims.add(new Weighted(an, Molang.constant(1)));
                        } else if (a instanceof Map<?, ?> am) {
                            for (var w : am.entrySet()) {
                                anims.add(new Weighted(String.valueOf(w.getKey()),
                                        Molang.of(w.getValue(), where + " weight of " + w.getKey(), notes, lex)));
                            }
                        }
                    }
                }
                List<Transition> transitions = new ArrayList<>();
                if (m.get("transitions") instanceof List<?> list) {
                    for (Object t : list) {
                        if (t instanceof Map<?, ?> tm) {
                            for (var w : tm.entrySet()) {
                                transitions.add(new Transition(String.valueOf(w.getKey()),
                                        Molang.of(w.getValue(), where + " to " + w.getKey(), notes, lex)));
                            }
                        }
                    }
                }
                for (String ignored : List.of("particle_effects", "sound_effects")) {
                    if (m.containsKey(ignored)) {
                        notes.add(where + ": " + ignored + " aren't played (animations only)");
                    }
                }
                byName.put(sn, new State(sn, List.copyOf(anims), List.copyOf(transitions),
                        script(m.get("on_entry"), where + " on_entry", notes, lex),
                        script(m.get("on_exit"), where + " on_exit", notes, lex), blend(m.get("blend_transition"))));
            }
            String initial = c.get("initial_state") instanceof String i ? i : byName.containsKey("default") ? "default"
                    : byName.keySet().iterator().next();
            if (!byName.containsKey(initial)) {
                notes.add(name + ": initial_state '" + initial + "' isn't one of its states; starts in the first");
                initial = byName.keySet().iterator().next();
            }
            for (State s : byName.values()) {
                for (Transition t : s.transitions()) {
                    if (!byName.containsKey(t.target())) {
                        notes.add(name + " / " + s.name() + ": goes to '" + t.target() + "', which isn't one of its states");
                    }
                }
            }
            out.add(new Controller(name, initial, byName));
        }
        return out;
    }

    /** {@code on_entry}: a list of Molang statements (or one string), run in order. */
    private static Molang script(Object v, String where, List<String> notes, Lexicon lex) {
        if (v == null) {
            return null;
        }
        List<Molang> parts = new ArrayList<>();
        for (Object o : v instanceof List<?> l ? l : List.of(v)) {
            parts.add(Molang.of(o, where, notes, lex));
        }
        Molang[] all = parts.toArray(Molang[]::new);
        return sc -> {
            for (Molang m : all) {
                m.eval(sc);
            }
            return 0;
        };
    }

    /** A number of seconds; Bedrock also allows a curve ({"0.0": 1, "0.5": 0}): its last time is the length. */
    private static double blend(Object v) {
        if (v instanceof Number n) {
            return Math.max(0, n.doubleValue());
        }
        if (v instanceof Map<?, ?> m) {
            double last = 0;
            for (Object k : m.keySet()) {
                try {
                    last = Math.max(last, Double.parseDouble(String.valueOf(k)));
                } catch (NumberFormatException ignored) {
                    // not a time
                }
            }
            return last;
        }
        return 0;
    }

    /**
     * One entity's part in it: its variables and queries ({@link #scene}), where each controller
     * is, and the animations code asked for ({@link Rites#play}).
     */
    static final class Soul {
        final Scene scene = new Scene();
        /** When the last frame was, in seconds: for {@code query.delta_time}. */
        double last = Double.NaN;
        /** What changed since it was last read: state moves and rites, for -Dmiracle.animations=trace. */
        final List<String> events = new ArrayList<>();
        private final Lane[] lanes;
        private final List<Played> played = new ArrayList<>();

        Soul(int controllers) {
            lanes = new Lane[controllers];
            for (int i = 0; i < controllers; i++) {
                lanes[i] = new Lane();
            }
        }

        /** Code asked: plays {@code rite} from {@code now} (again from the start, if it was playing). */
        void play(Rite rite, double now) {
            played.removeIf(p -> p.rite() == rite);
            played.add(new Played(rite, now));
            events.add("plays " + rite.name());
        }

        void stop(Rite rite) {
            if (played.removeIf(p -> p.rite() == rite)) {
                events.add("stops " + rite.name());
            }
        }

        boolean playing(Rite rite) {
            return played.stream().anyMatch(p -> p.rite() == rite);
        }

        int lanes() {
            return lanes.length;
        }

        /** The state controller {@code i} is in, or null before the first frame. For tests and the curious. */
        String state(int i) {
            State s = lanes[i].current;
            return s == null ? null : s.name();
        }
    }

    private record Played(Rite rite, double start) {
    }

    /** One controller, for one entity. */
    private static final class Lane {
        State current;
        double since;
        State previous;
        double previousSince;
        double switchedAt = Double.NEGATIVE_INFINITY;
    }

    /**
     * Moves every controller on to {@code now} (seconds, in the entity's own time) and says what
     * plays: each state's animations (crossfading after a move), then what code asked for. The
     * scene's queries must be filled for this frame; its variables are the soul's own.
     */
    static List<Voice> step(List<Controller> controllers, Map<String, Rite> rites, Soul soul, double now, List<String> notes) {
        List<Voice> out = new ArrayList<>();
        Scene sc = soul.scene;
        for (int i = 0; i < controllers.size() && i < soul.lanes.length; i++) {
            Controller c = controllers.get(i);
            Lane lane = soul.lanes[i];
            if (lane.current == null) {
                enter(lane, c.states().get(c.initial()), now, sc);
            } else {
                sc.animTime = now - lane.since;
                for (Transition t : lane.current.transitions()) {
                    State next = c.states().get(t.target());
                    if (next != null && t.condition().eval(sc) != 0) {
                        if (lane.current.onExit() != null) {
                            lane.current.onExit().eval(sc);
                        }
                        soul.events.add(c.name() + ": " + lane.current.name() + " -> " + next.name());
                        lane.previous = lane.current;
                        lane.previousSince = lane.since;
                        lane.switchedAt = now;
                        enter(lane, next, now, sc);
                        break;
                    }
                }
            }
            double blend = lane.current.blend();
            double fade = blend > 0 && now - lane.switchedAt < blend ? (now - lane.switchedAt) / blend : 1;
            voices(lane.current, lane.since, fade, now, rites, sc, out, notes);
            if (fade < 1 && lane.previous != null) {
                voices(lane.previous, lane.previousSince, 1 - fade, now, rites, sc, out, notes);
            }
        }
        for (Iterator<Played> it = soul.played.iterator(); it.hasNext(); ) {
            Played p = it.next();
            double seconds = now - p.start();
            if (p.rite().loop() == Liturgy.Loop.ONCE && seconds > p.rite().length()) {
                it.remove();
                soul.events.add("done " + p.rite().name());
                continue;
            }
            out.add(new Voice(p.rite(), seconds, 1));
        }
        return out;
    }

    private static void enter(Lane lane, State s, double now, Scene sc) {
        lane.current = s;
        lane.since = now;
        if (s.onEntry() != null) {
            sc.animTime = 0;
            s.onEntry().eval(sc);
        }
    }

    private static void voices(State s, double since, double fade, double now, Map<String, Rite> rites, Scene sc,
                               List<Voice> out, List<String> notes) {
        for (Weighted w : s.animations()) {
            Rite r = find(rites, w.animation());
            if (r == null) {
                String note = s.name() + " plays '" + w.animation() + "', which isn't in the animation file";
                if (!notes.contains(note)) {
                    notes.add(note);
                }
                continue;
            }
            sc.animTime = now - since;
            double weight = w.weight().eval(sc) * fade;
            if (weight != 0) {
                out.add(new Voice(r, now - since, weight));
            }
        }
    }

    /** By full name ({@code animation.heretic.walk}) or by its last part ({@code walk}), any case. */
    static Rite find(Map<String, Rite> rites, String name) {
        Rite r = rites.get(name);
        if (r != null) {
            return r;
        }
        String n = name.toLowerCase(Locale.ROOT);
        for (Rite x : rites.values()) {
            if (x.name().equalsIgnoreCase(n) || x.kind().equals(n)) {
                return x;
            }
        }
        return null;
    }
}
