package io.github.hronosin.miracle.bake;

import java.lang.classfile.Attribute;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.FieldElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.attribute.EnclosingMethodAttribute;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.attribute.NestHostAttribute;
import java.lang.classfile.attribute.NestMembersAttribute;
import java.lang.classfile.attribute.PermittedSubclassesAttribute;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.LocalVariableType;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renames classes, and methods of given classes, inside one class file: what a fallback's
 * anonymous classes and lambda bodies need so they can't collide with the main code's. Names are
 * internal ({@code a/b/C$1}); methods are renamed by owner and old name, whatever the descriptor.
 * Generic signatures and local-variable tables are dropped, as in every baked variant.
 */
final class Renamer {

    private static final Pattern OBJECT = Pattern.compile("L([^;]+);");

    private final Map<String, String> classes;
    private final Map<String, Map<String, String>> methods;

    Renamer(Map<String, String> classes, Map<String, Map<String, String>> methods) {
        this.classes = classes;
        this.methods = methods;
    }

    boolean touches(ClassModel cm) {
        return !classes.isEmpty() || methods.containsKey(cm.thisClass().asInternalName());
    }

    String cls(String internal) {
        if (internal.startsWith("[")) {
            return desc(internal);
        }
        return classes.getOrDefault(internal, internal);
    }

    ClassDesc cd(ClassDesc cd) {
        if (cd.isPrimitive()) {
            return cd;
        }
        if (cd.isArray()) {
            return ClassDesc.ofDescriptor(desc(cd.descriptorString()));
        }
        return ClassDesc.ofInternalName(cls(Remapper.internal(cd)));
    }

    String desc(String d) {
        Matcher m = OBJECT.matcher(d);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("L" + classes.getOrDefault(m.group(1), m.group(1)) + ";"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    MethodTypeDesc mtd(MethodTypeDesc m) {
        return MethodTypeDesc.ofDescriptor(desc(m.descriptorString()));
    }

    String method(String owner, String name) {
        Map<String, String> of = methods.get(owner);
        return of == null ? name : of.getOrDefault(name, name);
    }

    byte[] rename(ClassFile cf, ClassModel cm) {
        String self = cm.thisClass().asInternalName();
        return cf.build(ClassDesc.ofInternalName(cls(self)), clb -> {
            clb.withVersion(cm.majorVersion(), cm.minorVersion());
            clb.withFlags(cm.flags().flagsMask());
            cm.superclass().ifPresent(s -> clb.withSuperclass(ClassDesc.ofInternalName(cls(s.asInternalName()))));
            clb.withInterfaceSymbols(cm.interfaces().stream()
                    .map(i -> ClassDesc.ofInternalName(cls(i.asInternalName()))).toList());
            for (ClassElement e : cm) {
                switch (e) {
                    case FieldModel f -> clb.withField(f.fieldName().stringValue(), cd(f.fieldTypeSymbol()), fb -> {
                        fb.withFlags(f.flags().flagsMask());
                        for (FieldElement fe : f) {
                            if (fe instanceof Attribute<?> && !(fe instanceof SignatureAttribute)) {
                                fb.with(fe);
                            }
                        }
                    });
                    case MethodModel m -> clb.withMethod(method(self, m.methodName().stringValue()), mtd(m.methodTypeSymbol()),
                            m.flags().flagsMask(), mb -> {
                                for (MethodElement me : m) {
                                    switch (me) {
                                        case CodeModel code -> mb.transformCode(code, code());
                                        case ExceptionsAttribute ex -> mb.with(ExceptionsAttribute.ofSymbols(ex.exceptions().stream()
                                                .map(c -> cd(c.asSymbol())).toList()));
                                        case SignatureAttribute ignored -> {
                                        }
                                        case Attribute<?> a -> mb.with((MethodElement) a);
                                        default -> {
                                        }
                                    }
                                }
                            });
                    case InnerClassesAttribute a -> clb.with(InnerClassesAttribute.of(a.classes().stream().map(this::inner).toList()));
                    case EnclosingMethodAttribute a -> {
                        String outer = a.enclosingClass().asInternalName();
                        clb.with(EnclosingMethodAttribute.of(ClassDesc.ofInternalName(cls(outer)),
                                a.enclosingMethodName().map(n -> method(outer, n.stringValue())),
                                a.enclosingMethodTypeSymbol().map(this::mtd)));
                    }
                    case NestHostAttribute a -> clb.with(NestHostAttribute.of(ClassDesc.ofInternalName(cls(a.nestHost().asInternalName()))));
                    case NestMembersAttribute a -> clb.with(NestMembersAttribute.ofSymbols(a.nestMembers().stream()
                            .map(c -> ClassDesc.ofInternalName(cls(c.asInternalName()))).toList()));
                    case PermittedSubclassesAttribute a -> clb.with(PermittedSubclassesAttribute.ofSymbols(a.permittedSubclasses().stream()
                            .map(c -> ClassDesc.ofInternalName(cls(c.asInternalName()))).toList()));
                    case RecordAttribute a -> clb.with(RecordAttribute.of(a.components().stream()
                            .map(c -> RecordComponentInfo.of(c.name().stringValue(), cd(c.descriptorSymbol()))).toList()));
                    case SignatureAttribute ignored -> {
                    }
                    case Attribute<?> a -> clb.with((ClassElement) a);
                    default -> {
                        // version, flags, superclass, interfaces: done above
                    }
                }
            }
        });
    }

    InnerClassInfo inner(InnerClassInfo i) {
        return InnerClassInfo.of(ClassDesc.ofInternalName(cls(i.innerClass().asInternalName())),
                i.outerClass().map(o -> ClassDesc.ofInternalName(cls(o.asInternalName()))),
                i.innerName().map(n -> n.stringValue()), i.flagsMask());
    }

    private CodeTransform code() {
        return (b, e) -> {
            switch (e) {
                case FieldInstruction fi -> b.fieldAccess(fi.opcode(), cd(fi.owner().asSymbol()), fi.name().stringValue(),
                        cd(fi.typeSymbol()));
                case InvokeInstruction ii -> b.invoke(ii.opcode(), cd(ii.owner().asSymbol()),
                        method(ii.owner().asInternalName(), ii.name().stringValue()), mtd(ii.typeSymbol()), ii.isInterface());
                case InvokeDynamicInstruction indy -> b.invokedynamic(DynamicCallSiteDesc.of(handle(indy.bootstrapMethod()),
                        indy.name().stringValue(), mtd(indy.typeSymbol()),
                        indy.bootstrapArgs().stream().map(this::constant).toArray(ConstantDesc[]::new)));
                case TypeCheckInstruction tc -> {
                    ClassDesc t = cd(tc.type().asSymbol());
                    if (tc.opcode() == Opcode.INSTANCEOF) {
                        b.instanceOf(t);
                    } else {
                        b.checkcast(t);
                    }
                }
                case NewObjectInstruction n -> b.new_(cd(n.className().asSymbol()));
                case NewReferenceArrayInstruction n -> b.anewarray(cd(n.componentType().asSymbol()));
                case NewMultiArrayInstruction n -> b.multianewarray(cd(n.arrayType().asSymbol()), n.dimensions());
                case ConstantInstruction.LoadConstantInstruction lc -> b.loadConstant(constant(lc.constantValue()));
                case ExceptionCatch ec -> {
                    Optional<ClassDesc> type = ec.catchType().map(c -> cd(c.asSymbol()));
                    if (type.isPresent()) {
                        b.exceptionCatch(ec.tryStart(), ec.tryEnd(), ec.handler(), type.get());
                    } else {
                        b.exceptionCatchAll(ec.tryStart(), ec.tryEnd(), ec.handler());
                    }
                }
                case LocalVariable ignored -> {
                }
                case LocalVariableType ignored -> {
                }
                default -> b.with(e);
            }
        };
    }

    private ConstantDesc constant(ConstantDesc c) {
        return switch (c) {
            case ClassDesc cd -> cd(cd);
            case MethodTypeDesc m -> mtd(m);
            case DirectMethodHandleDesc h -> handle(h);
            default -> c;
        };
    }

    private DirectMethodHandleDesc handle(DirectMethodHandleDesc h) {
        String owner = Remapper.internal(h.owner());
        return switch (h.kind()) {
            case GETTER, SETTER, STATIC_GETTER, STATIC_SETTER -> MethodHandleDesc.ofField(h.kind(), cd(h.owner()),
                    h.methodName(), ClassDesc.ofDescriptor(desc(h.lookupDescriptor())));
            default -> MethodHandleDesc.ofMethod(h.kind(), cd(h.owner()), method(owner, h.methodName()),
                    MethodTypeDesc.ofDescriptor(desc(h.lookupDescriptor())));
        };
    }
}
