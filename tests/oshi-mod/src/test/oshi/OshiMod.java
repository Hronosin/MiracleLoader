package test.oshi;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.instruction.ConstantInstruction;

/**
 * Old school: gets the class as bytes and rewrites them with its "own" bytecode library. A real
 * mod would shade ASM; the JDK's ClassFile API stands in for it here.
 */
public final class OshiMod implements MiracleMod {
    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player").rawBytes(bytes -> {
            ClassFile cf = ClassFile.of();
            return cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies((code, e) -> {
                if (e instanceof ConstantInstruction.LoadConstantInstruction ldc && "vanilla".equals(ldc.constantValue())) {
                    code.loadConstant("old school");
                } else {
                    code.with(e);
                }
            }));
        });
    }
}
