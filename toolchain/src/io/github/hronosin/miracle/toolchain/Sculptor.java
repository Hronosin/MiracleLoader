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
import java.util.List;
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
    static MobRenderer<Mob, LivingEntityRenderState, Sculpture> renderer(Being<?> b, EntityRendererProvider.Context ctx) {
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
        Sculpture model = new Sculpture(bake(clay));
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
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            for (int n; (n = r.read(buf)) > 0; ) {
                sb.append(buf, 0, n);
            }
            return Clay.read(sb.toString());
        }
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
     * A sculpted being's model. Moves by the names of its parts: one called {@code head} follows
     * the gaze, parts with {@code leg} in their name walk, parts with {@code arm} swing against
     * the legs; {@code left}/{@code right} in the name set which foot goes first.
     */
    static final class Sculpture extends EntityModel<LivingEntityRenderState> {

        private record Limb(ModelPart part, float phase, float amplitude) {
        }

        private final ModelPart head;
        private final List<Limb> limbs = new ArrayList<>();

        Sculpture(ModelPart root) {
            super(root);
            ModelPart found = null;
            List<ModelPart> all = new ArrayList<>();
            List<String> names = new ArrayList<>();
            collect(root, "", all, names);
            int legs = 0;
            int arms = 0;
            for (int i = 0; i < all.size(); i++) {
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
            if (head != null) {
                head.yRot += state.yRot * ((float) Math.PI / 180f);
                head.xRot += state.xRot * ((float) Math.PI / 180f);
            }
            float pos = state.walkAnimationPos;
            float speed = Math.min(1f, state.walkAnimationSpeed);
            for (Limb l : limbs) {
                l.part().xRot += (float) Math.cos(pos * 0.6662f + l.phase()) * l.amplitude() * speed;
            }
        }
    }

    /** Draws a sculpted being with its texture. */
    static final class Effigy extends MobRenderer<Mob, LivingEntityRenderState, Sculpture> {

        private final Identifier texture;

        Effigy(EntityRendererProvider.Context ctx, Sculpture model, float shadow, Identifier texture) {
            super(ctx, model, shadow);
            this.texture = texture;
        }

        @Override
        public LivingEntityRenderState createRenderState() {
            return new LivingEntityRenderState();
        }

        @Override
        public Identifier getTextureLocation(LivingEntityRenderState state) {
            return texture;
        }
    }
}
