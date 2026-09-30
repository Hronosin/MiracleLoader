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
        Map<String, Liturgy.Rite> rites = Map.of();
        Optional<Resource> animations = Minecraft.getInstance().getResourceManager().getResource(animFile);
        if (animations.isPresent()) {
            List<String> notes = new ArrayList<>();
            try (Reader r = animations.get().openAsReader()) {
                rites = Liturgy.read(text(r), notes);
            } catch (IOException | RuntimeException e) {
                Log.error("MiracleToolChain: " + b + "'s animations in " + animFile + " couldn't be read: " + e.getMessage()
                        + ". It moves by part names instead.");
            }
            notes.forEach(n -> Log.warn("MiracleToolChain: " + b + "'s animations: " + n));
        }
        Sculpture model = new Sculpture(bake(clay), rites);
        Identifier texture = Identifier.parse(b.texture);
        float shadow = Math.max(0.1f, b.get().getWidth() * 0.5f);
        return new Effigy(ctx, model, shadow, texture);
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
     * A sculpted being's model. Plays the animations whose names end in {@code idle},
     * {@code walk}, {@code attack} and {@code death}, if it has them; otherwise moves by the names
     * of its parts: one called {@code head} follows the gaze (always), parts with {@code leg} in
     * their name walk, parts with {@code arm} swing against the legs; {@code left}/{@code right}
     * in the name set which foot goes first.
     */
    static final class Sculpture extends EntityModel<LivingEntityRenderState> {

        /** The render state, plus how far along an attack swing is. */
        static final class State extends LivingEntityRenderState {
            float attack;
        }

        private record Limb(ModelPart part, float phase, float amplitude) {
        }

        private static final float DEG = (float) Math.PI / 180f;

        private final ModelPart head;
        private final List<Limb> limbs = new ArrayList<>();
        private final Map<String, ModelPart> parts = new HashMap<>();
        private final List<Liturgy.Rite> idle = new ArrayList<>();
        private final List<Liturgy.Rite> walk = new ArrayList<>();
        private final List<Liturgy.Rite> attack = new ArrayList<>();
        private final List<Liturgy.Rite> death = new ArrayList<>();

        Sculpture(ModelPart root, Map<String, Liturgy.Rite> rites) {
            super(root);
            for (Liturgy.Rite r : rites.values()) {
                switch (r.kind()) {
                    case "idle" -> idle.add(r);
                    case "walk", "walking", "move", "run" -> walk.add(r);
                    case "attack", "swing" -> attack.add(r);
                    case "death", "die" -> death.add(r);
                    default -> Log.warn("MiracleToolChain: animation " + r.name() + " plays at no time: names that end in"
                            + " idle, walk, attack or death do.");
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

        @Override
        public void setupAnim(LivingEntityRenderState state) {
            resetPose();
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
                play(r, life, 1 - walking, state);
            }
            for (Liturgy.Rite r : walk) {
                play(r, life, walking, state);
            }
            float swing = state instanceof State s ? s.attack : 0;
            if (swing > 0) {
                for (Liturgy.Rite r : attack) {
                    play(r, swing * r.length(), 1, state);
                }
            }
            if (state.deathTime > 0) {
                for (Liturgy.Rite r : death) {
                    play(r, state.deathTime / 20.0, 1, state);
                }
            }
            if (head != null) {
                head.yRot += state.yRot * DEG;
                head.xRot += state.xRot * DEG;
            }
        }

        /** Adds one animation, at {@code seconds} into it, weighted by {@code weight}. */
        private void play(Liturgy.Rite rite, double seconds, float weight, LivingEntityRenderState state) {
            if (weight <= 0) {
                return;
            }
            double t = rite.at(seconds);
            Liturgy.Scene scene = new Liturgy.Scene(t, state.ageInTicks / 20.0, state.walkAnimationSpeed,
                    state.walkAnimationPos, state.xRot, state.yRot);
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
                    double[] r = Liturgy.sample(b.rotation(), t, scene);
                    part.xRot += (float) r[0] * DEG * weight;
                    part.yRot += (float) r[1] * DEG * weight;
                    part.zRot += (float) r[2] * DEG * weight;
                }
                if (b.position() != null) {
                    double[] p = Liturgy.sample(b.position(), t, scene);
                    part.x += (float) p[0] * weight;
                    part.y -= (float) p[1] * weight;
                    part.z += (float) p[2] * weight;
                }
                if (b.scale() != null) {
                    double[] sc = Liturgy.sample(b.scale(), t, scene);
                    part.xScale *= 1 + ((float) sc[0] - 1) * weight;
                    part.yScale *= 1 + ((float) sc[1] - 1) * weight;
                    part.zScale *= 1 + ((float) sc[2] - 1) * weight;
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
        }

        @Override
        public Identifier getTextureLocation(Sculpture.State state) {
            return texture;
        }
    }
}
