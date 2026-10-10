package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.Mob;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;

/**
 * The client-only half of {@link Being#sculpted}: reads the being's geometry from the resources,
 * builds the game's model from it ({@link Clay} does the reading), and draws it with its texture,
 * walking and looking around. Loaded only on a client, once the game runs.
 */
final class Sculptor {

    private Sculptor() {
    }

    /** The renderer for a sculpted being. Made again on every resource reload, so edits show after F3+T. */
    static MobRenderer<Mob, Sculpture.State, Sculpture> renderer(Being<?> b, EntityRendererProvider.Context ctx) {
        Identifier geometry = Identifier.parse(b.geometry);
        Identifier file = Identifier.fromNamespaceAndPath(geometry.getNamespace(), "geo/" + geometry.getPath() + ".geo.json");
        Clay.Model clay;
        try {
            clay = read(file);
            clay.notes().forEach(n -> Log.warn("MiracleToolChain: " + b + "'s model: " + n));
        } catch (IOException | RuntimeException e) {
            Log.error("MiracleToolChain: " + b + " is sculpted from " + file + ", which "
                    + (e instanceof java.io.FileNotFoundException ? "isn't in any resource pack (assets/"
                    + geometry.getNamespace() + "/geo/" + geometry.getPath() + ".geo.json)" : "couldn't be read: " + e.getMessage())
                    + ". It's a pink block until that's fixed.");
            clay = unformed();
        }
        Identifier animFile = Identifier.fromNamespaceAndPath(geometry.getNamespace(),
                "animations/" + geometry.getPath() + ".animation.json");
        Liturgy.Lexicon lex = new Liturgy.Lexicon(b.queryNames);
        Map<String, Liturgy.Rite> rites = Map.of();
        Optional<Resource> animations = Minecraft.getInstance().getResourceManager().getResource(animFile);
        if (animations.isPresent()) {
            List<String> notes = new ArrayList<>();
            try (Reader r = animations.get().openAsReader()) {
                rites = Liturgy.read(text(r), notes, lex);
            } catch (IOException | RuntimeException e) {
                Log.error("MiracleToolChain: " + b + "'s animations in " + animFile + " couldn't be read: " + e.getMessage()
                        + ". It moves by part names instead.");
            }
            notes.forEach(n -> Log.warn("MiracleToolChain: " + b + "'s animations: " + n));
        }
        List<Choir.Controller> controllers = List.of();
        for (String suffix : List.of(".animation_controllers.json", ".animation_controller.json")) {
            Identifier ctlFile = Identifier.fromNamespaceAndPath(geometry.getNamespace(),
                    "animation_controllers/" + geometry.getPath() + suffix);
            Optional<Resource> ctl = Minecraft.getInstance().getResourceManager().getResource(ctlFile);
            if (ctl.isEmpty()) {
                continue;
            }
            List<String> notes = new ArrayList<>();
            try (Reader r = ctl.get().openAsReader()) {
                controllers = Choir.read(text(r), notes, lex);
            } catch (IOException | RuntimeException e) {
                Log.error("MiracleToolChain: " + b + "'s animation controllers in " + ctlFile + " couldn't be read: "
                        + e.getMessage() + ". It plays by animation names instead.");
            }
            notes.forEach(n -> Log.warn("MiracleToolChain: " + b + "'s animation controllers: " + n));
            break;
        }
        Sculpture model = new Sculpture(bake(clay), rites, controllers, b);
        SCULPTURES.put(b.get(), model);
        Identifier texture = Identifier.parse(b.texture);
        float shadow = Math.max(0.1f, b.get().getWidth() * 0.5f);
        return new Effigy(ctx, model, shadow, texture);
    }

    // --- souls: each entity's variables, controller states and rites in play -------------------

    /** The sculpture each being is drawn with now (made again on every resource reload). */
    private static final Map<net.minecraft.world.entity.EntityType<?>, Sculpture> SCULPTURES = new java.util.concurrent.ConcurrentHashMap<>();

    /** One soul per entity drawn, for as long as the entity exists on this client. Render thread only. */
    private static final Map<net.minecraft.world.entity.Entity, Choir.Soul> SOULS = new java.util.WeakHashMap<>();

    private static final java.util.Set<String> SAID = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** -Dmiracle.animations=trace: every controller move and rite, in the log. */
    static final boolean TRACE = "trace".equals(System.getProperty("miracle.animations"));

    static Choir.Soul soul(net.minecraft.world.entity.Entity e, int controllers) {
        Choir.Soul s = SOULS.get(e);
        if (s == null || s.lanes() != controllers) {
            s = new Choir.Soul(controllers);
            SOULS.put(e, s);
        }
        return s;
    }

    /** {@link Rites#play}/{@code stop} for an entity on this client. */
    static void rite(net.minecraft.world.entity.Entity e, String name, boolean play) {
        Sculpture sc = SCULPTURES.get(e.getType());
        if (sc == null) {
            say(net.minecraft.world.entity.EntityType.getKey(e.getType()) + " isn't a sculpted being (or isn't drawn yet),"
                    + " so Rites can't play '" + name + "' on it");
            return;
        }
        Liturgy.Rite rite = Choir.find(sc.rites, name);
        if (rite == null) {
            say(sc.being + " has no animation '" + name + "' (its animations: " + sc.rites.keySet() + ")");
            return;
        }
        Choir.Soul soul = soul(e, sc.controllers.size());
        if (play) {
            soul.play(rite, e.tickCount / 20.0);
        } else {
            soul.stop(rite);
        }
    }

    /** What the server said: Rites on entity {@code id}, if this client has it. */
    static void received(int id, String name, boolean play) {
        var level = Minecraft.getInstance().level;
        net.minecraft.world.entity.Entity e = level == null ? null : level.getEntity(id);
        if (e != null) {
            rite(e, name, play);
        }
    }

    private static void say(String what) {
        if (SAID.add(what)) {
            Log.warn("MiracleToolChain: " + what);
        }
    }

    private static Clay.Model read(Identifier file) throws IOException {
        Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(file);
        if (res.isEmpty()) {
            throw new java.io.FileNotFoundException(file.toString());
        }
        try (Reader r = res.get().openAsReader()) {
            return Clay.read(text(r));
        }
    }

    private static String text(Reader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        for (int n; (n = r.read(buf)) > 0; ) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    /** What a being without a readable geometry looks like: one block, so it's at least there. */
    private static Clay.Model unformed() {
        return new Clay.Model(64, 64, List.of(new Clay.Part("unformed", 0, 24, 0, 0, 0, 0,
                List.of(new Clay.Box(0, 0, -8, -16, -8, 16, 16, 16, 0, false)), List.of())), List.of());
    }

    static ModelPart bake(Clay.Model clay) {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        for (Clay.Part p : clay.parts()) {
            add(root, p);
        }
        return LayerDefinition.create(mesh, clay.textureWidth(), clay.textureHeight()).bakeRoot();
    }

    private static void add(PartDefinition parent, Clay.Part p) {
        CubeListBuilder cubes = CubeListBuilder.create();
        for (Clay.Box b : p.boxes()) {
            cubes.texOffs(b.u(), b.v()).mirror(b.mirror())
                    .addBox(b.x(), b.y(), b.z(), b.w(), b.h(), b.d(), new CubeDeformation(b.inflate()));
        }
        PartDefinition me = parent.addOrReplaceChild(p.name(), cubes,
                PartPose.offsetAndRotation(p.x(), p.y(), p.z(), p.xRot(), p.yRot(), p.zRot()));
        for (Clay.Part c : p.children()) {
            add(me, c);
        }
    }

    /**
     * A sculpted being's model. With animation controllers, they decide what plays. Without, it
     * plays the animations whose names end in {@code idle}, {@code walk}, {@code attack} and
     * {@code death}, if it has them, or else moves by the names of its parts: one called
     * {@code head} follows the gaze (always), parts with {@code leg} in their name walk, parts with
     * {@code arm} swing against the legs; {@code left}/{@code right} in the name set which foot
     * goes first. What code plays ({@link Rites}) plays on top, either way.
     */
    static final class Sculpture extends EntityModel<LivingEntityRenderState> {

        /** The render state, plus how far along an attack swing is, and what plays this frame. */
        static final class State extends LivingEntityRenderState {
            float attack;
            Liturgy.Scene scene;
            List<Choir.Voice> voices = List.of();
        }

        private record Limb(ModelPart part, float phase, float amplitude) {
        }

        private static final float DEG = (float) Math.PI / 180f;

        final Map<String, Liturgy.Rite> rites;
        final List<Choir.Controller> controllers;
        final Being<?> being;
        final List<String> notes = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final ModelPart head;
        private final List<Limb> limbs = new ArrayList<>();
        private final Map<String, ModelPart> parts = new HashMap<>();
        private final List<Liturgy.Rite> idle = new ArrayList<>();
        private final List<Liturgy.Rite> walk = new ArrayList<>();
        private final List<Liturgy.Rite> attack = new ArrayList<>();
        private final List<Liturgy.Rite> death = new ArrayList<>();

        Sculpture(ModelPart root, Map<String, Liturgy.Rite> rites, List<Choir.Controller> controllers, Being<?> being) {
            super(root);
            this.rites = rites;
            this.controllers = controllers;
            this.being = being;
            if (controllers.isEmpty()) {
                for (Liturgy.Rite r : rites.values()) {
                    switch (r.kind()) {
                        case "idle" -> idle.add(r);
                        case "walk", "walking", "move", "run" -> walk.add(r);
                        case "attack", "swing" -> attack.add(r);
                        case "death", "die" -> death.add(r);
                        default -> {
                            // played by name from code (Rites), or by nothing
                        }
                    }
                }
            }
            ModelPart found = null;
            List<ModelPart> all = new ArrayList<>();
            List<String> names = new ArrayList<>();
            collect(root, "", all, names);
            int legs = 0;
            int arms = 0;
            for (int i = 0; i < all.size(); i++) {
                parts.putIfAbsent(names.get(i), all.get(i));
                parts.putIfAbsent(names.get(i).toLowerCase(Locale.ROOT), all.get(i));
                String n = names.get(i).toLowerCase(Locale.ROOT);
                ModelPart part = all.get(i);
                if (found == null && n.equals("head")) {
                    found = part;
                } else if (n.contains("leg")) {
                    limbs.add(new Limb(part, side(n, legs++), 1.4f));
                } else if (n.contains("arm")) {
                    limbs.add(new Limb(part, side(n, arms++) + (float) Math.PI, 1.0f));
                }
            }
            head = found;
        }

        /** Right goes first, left follows; unnamed sides alternate. */
        private static float side(String name, int index) {
            if (name.contains("right")) {
                return 0;
            }
            if (name.contains("left")) {
                return (float) Math.PI;
            }
            return index % 2 == 0 ? 0 : (float) Math.PI;
        }

        private static void collect(ModelPart part, String name, List<ModelPart> all, List<String> names) {
            all.add(part);
            names.add(name);
            for (var e : children(part)) {
                collect(e.part(), e.name(), all, names);
            }
        }

        private record Named(String name, ModelPart part) {
        }

        /** A part's named children: ModelPart keeps them in a private map, found by type. */
        @SuppressWarnings("unchecked")
        private static List<Named> children(ModelPart part) {
            List<Named> out = new ArrayList<>();
            for (java.lang.reflect.Field f : ModelPart.class.getDeclaredFields()) {
                if (java.util.Map.class.isAssignableFrom(f.getType())
                        && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    try {
                        f.setAccessible(true);
                        for (var e : ((java.util.Map<String, ModelPart>) f.get(part)).entrySet()) {
                            out.add(new Named(e.getKey(), e.getValue()));
                        }
                    } catch (IllegalAccessException | ClassCastException ignored) {
                        // not the children map
                    }
                }
            }
            return out;
        }

        /** This frame for one entity: its scene filled in, its controllers moved on, its voices chosen. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        void extract(Mob mob, State state, float partialTick) {
            Choir.Soul soul = soul(mob, controllers.size());
            Liturgy.Scene sc = soul.scene;
            double now = (mob.tickCount + partialTick) / 20.0;
            sc.deltaTime = Double.isNaN(soul.last) ? 0 : Math.max(0, now - soul.last);
            soul.last = now;
            sc.lifeTime = now;
            sc.groundSpeed = state.walkAnimationSpeed;
            sc.distanceMoved = state.walkAnimationPos;
            sc.headX = state.xRot;
            sc.headY = state.yRot;
            sc.onGround = mob.onGround() ? 1 : 0;
            sc.inWater = mob.isInWater() ? 1 : 0;
            sc.moving = state.walkAnimationSpeed > 0.01f || mob.getDeltaMovement().horizontalDistanceSqr() > 1e-6 ? 1 : 0;
            sc.baby = state.isBaby ? 1 : 0;
            sc.health = mob.getHealth();
            sc.maxHealth = mob.getMaxHealth();
            sc.attackTime = state.attack;
            sc.alive = mob.isAlive() && state.deathTime <= 0 ? 1 : 0;
            int n = being.queryValues.size();
            if (sc.custom.length != n) {
                sc.custom = new double[n];
            }
            for (int i = 0; i < n; i++) {
                try {
                    sc.custom[i] = ((java.util.function.ToDoubleFunction) being.queryValues.get(i)).applyAsDouble(mob);
                } catch (RuntimeException e) {
                    sc.custom[i] = 0;
                    say(being + "'s query " + being.queryNames.get(i) + " threw " + e + "; read as 0");
                }
            }
            state.scene = sc;
            state.voices = Choir.step(controllers, rites, soul, now, notes);
            if (!soul.events.isEmpty()) {
                if (TRACE) {
                    for (String ev : soul.events) {
                        Log.info("MiracleToolChain: " + being + " #" + mob.getId() + " " + ev);
                    }
                }
                soul.events.clear();
            }
            if (!notes.isEmpty()) {
                notes.forEach(note -> say(being + "'s animation controllers: " + note));
                notes.clear();
            }
        }

        @Override
        public void setupAnim(LivingEntityRenderState state) {
            resetPose();
            State st = state instanceof State s ? s : null;
            Liturgy.Scene sc = st != null && st.scene != null ? st.scene : new Liturgy.Scene();
            if (controllers.isEmpty()) {
                float pos = state.walkAnimationPos;
                float speed = Math.min(1f, state.walkAnimationSpeed);
                double life = state.ageInTicks / 20.0;
                if (walk.isEmpty()) {
                    for (Limb l : limbs) {
                        l.part().xRot += (float) Math.cos(pos * 0.6662f + l.phase()) * l.amplitude() * speed;
                    }
                }
                float walking = walk.isEmpty() ? 0 : Math.min(1f, speed * 1.5f);
                for (Liturgy.Rite r : idle) {
                    play(r, life, 1 - walking, sc);
                }
                for (Liturgy.Rite r : walk) {
                    play(r, life, walking, sc);
                }
                float swing = st != null ? st.attack : 0;
                if (swing > 0) {
                    for (Liturgy.Rite r : attack) {
                        play(r, swing * r.length(), 1, sc);
                    }
                }
                if (state.deathTime > 0) {
                    for (Liturgy.Rite r : death) {
                        play(r, state.deathTime / 20.0, 1, sc);
                    }
                }
            }
            if (st != null) {
                for (Choir.Voice v : st.voices) {
                    play(v.rite(), v.seconds(), v.weight(), sc);
                }
            }
            if (head != null) {
                head.yRot += state.yRot * DEG;
                head.xRot += state.xRot * DEG;
            }
        }

        /** Adds one animation, at {@code seconds} into it, weighted by {@code weight}. */
        private void play(Liturgy.Rite rite, double seconds, double weight, Liturgy.Scene sc) {
            if (weight == 0) {
                return;
            }
            float w = (float) weight;
            double t = rite.at(seconds);
            sc.animTime = t;
            for (var e : rite.bones().entrySet()) {
                ModelPart part = parts.get(e.getKey());
                if (part == null) {
                    part = parts.get(e.getKey().toLowerCase(Locale.ROOT));
                }
                if (part == null) {
                    continue;
                }
                Liturgy.Bone b = e.getValue();
                if (b.rotation() != null) {
                    double[] r = Liturgy.sample(b.rotation(), t, sc);
                    part.xRot += (float) r[0] * DEG * w;
                    part.yRot += (float) r[1] * DEG * w;
                    part.zRot += (float) r[2] * DEG * w;
                }
                if (b.position() != null) {
                    double[] p = Liturgy.sample(b.position(), t, sc);
                    part.x += (float) p[0] * w;
                    part.y -= (float) p[1] * w;
                    part.z += (float) p[2] * w;
                }
                if (b.scale() != null) {
                    double[] scale = Liturgy.sample(b.scale(), t, sc);
                    part.xScale *= 1 + ((float) scale[0] - 1) * w;
                    part.yScale *= 1 + ((float) scale[1] - 1) * w;
                    part.zScale *= 1 + ((float) scale[2] - 1) * w;
                }
            }
        }
    }

    /** Draws a sculpted being with its texture. */
    static final class Effigy extends MobRenderer<Mob, Sculpture.State, Sculpture> {

        private final Identifier texture;

        Effigy(EntityRendererProvider.Context ctx, Sculpture model, float shadow, Identifier texture) {
            super(ctx, model, shadow);
            this.texture = texture;
        }

        @Override
        public Sculpture.State createRenderState() {
            return new Sculpture.State();
        }

        @Override
        public void extractRenderState(Mob mob, Sculpture.State state, float partialTick) {
            super.extractRenderState(mob, state, partialTick);
            state.attack = Swing.progress(mob, partialTick);
            getModel().extract(mob, state, partialTick);
        }

        @Override
        public Identifier getTextureLocation(Sculpture.State state) {
            return texture;
        }
    }
}
