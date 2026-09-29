package test.variant;

import io.github.hronosin.miracle.api.MiracleMod;

/** Stands in for what miracle-bake would produce for game version "fake-1". */
public final class VariantMod implements MiracleMod {
    @Override
    public void onLaunch() {
        System.out.println("[variant-mod] running the variant baked for fake-1");
    }
}
