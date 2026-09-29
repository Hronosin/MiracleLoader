package io.github.hronosin.miracle.bake;

import io.github.hronosin.miracle.bake.VersionDict.ClassInfo;

import java.io.IOException;
import java.lang.classfile.Attribute;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.FieldElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.Signature;
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
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Turns an obfuscated game jar into a readable API jar, using the version's dictionary:
 * classes, fields, methods, generics and nested classes all get their Mojang names back. Method
 * bodies are replaced by {@code throw null}: the jar is for javac and your IDE, never for running.
 *
 * <p>This is what fallback code for an obfuscated version is compiled against. It stays in your
 * local cache next to the mappings it was made from, and like them is never redistributed.
 */
final class ApiJar {

    private final VersionDict d;
    /** jar class -> (jar name + jar descriptor -> readable name) */
    private final Map<String, Map<String, String>> methods = new HashMap<>();
    private final Map<String, Map<String, String>> fields = new HashMap<>();

    private ApiJar(VersionDict d) {
        this.d = d;
        for (ClassInfo c : d.classes.values()) {
            Map<String, String> ms = methods.computeIfAbsent(c.obf, k -> new HashMap<>());
            c.methods.forEach((key, jarName) -> {
                int paren = key.indexOf('(');
                ms.put(jarName + toJarDesc(key.substring(paren)), key.substring(0, paren));
            });
            Map<String, String> fs = fields.computeIfAbsent(c.obf, k -> new HashMap<>());
            c.fields.forEach((key, jarName) -> {
                int colon = key.indexOf(':');
                fs.put(jarName + ":" + toJarDesc(key.substring(colon + 1)), key.substring(0, colon));
            });
        }
    }

    static int write(VersionDict d, Path out) throws IOException {
        return new ApiJar(d).writeTo(out);
    }

    private int writeTo(Path out) throws IOException {
        ClassFile cf = ClassFile.of();
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        Files.createDirectories(out.toAbsolutePath().getParent());
        int[] count = {0};
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(tmp))) {
            VersionDict.forEachClass(d.gameJar, cm -> {
                String named = named(cm.thisClass().asInternalName());
                byte[] bytes = cf.build(ClassDesc.ofInternalName(named), clb -> stub(cm, clb));
                jar.putNextEntry(new JarEntry(named + ".class"));
                jar.write(bytes);
                jar.closeEntry();
                count[0]++;
            });
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        return count[0];
    }

    private void stub(ClassModel cm, java.lang.classfile.ClassBuilder clb) {
        String jn = cm.thisClass().asInternalName();
        clb.withVersion(cm.majorVersion(), cm.minorVersion());
        clb.withFlags(cm.flags().flagsMask());
        cm.superclass().ifPresent(s -> clb.withSuperclass(cd(s.asInternalName())));
        clb.withInterfaceSymbols(cm.interfaces().stream().map(i -> cd(i.asInternalName())).toList());
        Map<String, String> ms = methods.getOrDefault(jn, Map.of());
        Map<String, String> fs = fields.getOrDefault(jn, Map.of());
        for (ClassElement e : cm) {
            switch (e) {
                case FieldModel f -> {
                    String desc = f.fieldType().stringValue();
                    String name = fs.getOrDefault(f.fieldName().stringValue() + ":" + desc, f.fieldName().stringValue());
                    clb.withField(name, ClassDesc.ofDescriptor(toNamedDesc(desc)), fb -> {
                        fb.withFlags(f.flags().flagsMask());
                        for (FieldElement fe : f) {
                            if (fe instanceof SignatureAttribute sa) {
                                fb.with(SignatureAttribute.of(Signature.parseFrom(sig(sa))));
                            } else if (fe instanceof Attribute<?>) {
                                fb.with(fe);
                            }
                        }
                    });
                }
                case MethodModel m -> {
                    String desc = m.methodType().stringValue();
                    String name = ms.getOrDefault(m.methodName().stringValue() + desc, m.methodName().stringValue());
                    clb.withMethod(name, MethodTypeDesc.ofDescriptor(toNamedDesc(desc)), m.flags().flagsMask(), mb -> {
                        for (MethodElement me : m) {
                            switch (me) {
                                case SignatureAttribute sa -> mb.with(SignatureAttribute.of(MethodSignature.parseFrom(sig(sa))));
                                case ExceptionsAttribute ex -> mb.with(ExceptionsAttribute.ofSymbols(
                                        ex.exceptions().stream().map(c -> cd(c.asInternalName())).toList()));
                                case java.lang.classfile.CodeModel ignored -> {
                                }
                                case Attribute<?> a -> mb.with((MethodElement) a);
                                default -> {
                                }
                            }
                        }
                        if (m.code().isPresent()) {
                            mb.withCode(cb -> {
                                cb.aconst_null();
                                cb.athrow();
                            });
                        }
                    });
                }
                case SignatureAttribute sa -> clb.with(SignatureAttribute.of(ClassSignature.parseFrom(sig(sa))));
                case InnerClassesAttribute a -> clb.with(InnerClassesAttribute.of(a.classes().stream().map(this::inner).toList()));
                case NestHostAttribute a -> clb.with(NestHostAttribute.of(cd(a.nestHost().asInternalName())));
                case NestMembersAttribute a -> clb.with(NestMembersAttribute.ofSymbols(
                        a.nestMembers().stream().map(c -> cd(c.asInternalName())).toList()));
                case PermittedSubclassesAttribute a -> clb.with(PermittedSubclassesAttribute.ofSymbols(
                        a.permittedSubclasses().stream().map(c -> cd(c.asInternalName())).toList()));
                case RecordAttribute a -> clb.with(RecordAttribute.of(a.components().stream().map(rc -> {
                    String desc = rc.descriptor().stringValue();
                    String name = fs.getOrDefault(rc.name().stringValue() + ":" + desc, rc.name().stringValue());
                    return RecordComponentInfo.of(name, ClassDesc.ofDescriptor(toNamedDesc(desc)));
                }).toList()));
                case EnclosingMethodAttribute ignored -> {
                    // local and anonymous classes: irrelevant for compiling against the API
                }
                case java.lang.classfile.attribute.SourceFileAttribute ignored -> {
                    // "a.java" would make javac think the class is an auxiliary one
                }
                case Attribute<?> a -> clb.with((ClassElement) a);
                default -> {
                }
            }
        }
    }

    private InnerClassInfo inner(InnerClassInfo i) {
        String inner = named(i.innerClass().asInternalName());
        Optional<ClassDesc> outer = i.outerClass().map(o -> cd(o.asInternalName()));
        Optional<String> simple = i.innerName().map(n -> {
            int dollar = inner.lastIndexOf('$');
            return dollar >= 0 ? inner.substring(dollar + 1) : n.stringValue();
        });
        return InnerClassInfo.of(ClassDesc.ofInternalName(inner), outer, simple, i.flagsMask());
    }

    private String sig(SignatureAttribute sa) {
        return SignatureRemapper.remap(sa.signature().stringValue(), this::named);
    }

    private String named(String jarName) {
        return d.jarToNamed.getOrDefault(jarName, jarName);
    }

    private ClassDesc cd(String jarName) {
        return ClassDesc.ofInternalName(named(jarName));
    }

    private String toNamedDesc(String jarDesc) {
        return rewrite(jarDesc, this::named);
    }

    private String toJarDesc(String namedDesc) {
        return rewrite(namedDesc, n -> {
            String j = d.jarName(n);
            return j == null ? n : j;
        });
    }

    private static String rewrite(String desc, SignatureRemapper.Names names) {
        StringBuilder out = new StringBuilder(desc.length());
        for (int i = 0; i < desc.length(); i++) {
            char ch = desc.charAt(i);
            out.append(ch);
            if (ch == 'L') {
                int end = desc.indexOf(';', i);
                out.append(names.map(desc.substring(i + 1, end))).append(';');
                i = end;
            }
        }
        return out.toString();
    }
}
