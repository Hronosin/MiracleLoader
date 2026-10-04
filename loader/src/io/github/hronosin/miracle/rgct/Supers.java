package io.github.hronosin.miracle.rgct;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who a class extends and implements, read from its class file without loading it: a class being
 * transformed can't make the loader load its relatives. Asked only when a call site matches a
 * redirect by name and descriptor but names another class, which is rare, so nothing is read
 * otherwise.
 */
final class Supers {

    private static final List<String> NONE = List.of();

    /** Internal name to direct supertypes, per loader: the same game, the same answers. */
    private static final Map<ClassLoader, Map<String, List<String>>> CACHE = new ConcurrentHashMap<>();

    private final ClassLoader loader;

    Supers(ClassLoader loader) {
        this.loader = loader;
    }

    /** Whether {@code type} is {@code sup} or extends or implements it, directly or not. */
    boolean isSubtype(String type, String sup) {
        if (type.equals(sup)) {
            return true;
        }
        for (String s : direct(type)) {
            if (isSubtype(s, sup)) {
                return true;
            }
        }
        return false;
    }

    private List<String> direct(String type) {
        if (loader == null || type.startsWith("java/")) {
            return NONE;                // nothing of ours, or of the game's, is up there
        }
        return CACHE.computeIfAbsent(loader, l -> new ConcurrentHashMap<>()).computeIfAbsent(type, this::read);
    }

    private List<String> read(String type) {
        try (InputStream in = loader.getResourceAsStream(type + ".class")) {
            if (in == null) {
                return NONE;
            }
            ClassModel m = ClassFile.of().parse(in.readAllBytes());
            List<String> out = new ArrayList<>();
            m.superclass().map(ClassEntry::asInternalName).ifPresent(out::add);
            for (ClassEntry i : m.interfaces()) {
                out.add(i.asInternalName());
            }
            return List.copyOf(out);
        } catch (IOException | IllegalArgumentException e) {
            return NONE;
        }
    }
}
