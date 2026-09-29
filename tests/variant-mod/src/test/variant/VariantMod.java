package test.variant;

import io.github.hronosin.miracle.api.MiracleMod;

public final class VariantMod implements MiracleMod {
    @Override
    public void onLaunch() {
        System.out.println("[variant-mod] running the plain classes");
    }
}
