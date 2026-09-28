package com.example.chaos;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;

import java.lang.classfile.ClassTransform;
import java.lang.classfile.instruction.ConstantInstruction;

/**
 * The "evil Pinocchio" path: a raw ClassFile API transform. Replaces the string " jumps" with
 * " levitates" in every method of Player. RGCT applies it but logs who did it.
 */
public final class ChaosMod implements MiracleMod {

    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player")
                .raw(ClassTransform.transformingMethodBodies((code, element) -> {
                    if (element instanceof ConstantInstruction.LoadConstantInstruction ldc
                            && " jumps".equals(ldc.constantValue())) {
                        code.loadConstant(" levitates");
                    } else {
                        code.with(element);
                    }
                }));
    }
}
