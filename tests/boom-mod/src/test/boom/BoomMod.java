package test.boom;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;

/** Explodes during the transform phase. The crash banner must name it. */
public final class BoomMod implements MiracleMod {
    @Override
    public void transform(Rgct rgct) {
        throw new IllegalStateException("kaboom");
    }
}
