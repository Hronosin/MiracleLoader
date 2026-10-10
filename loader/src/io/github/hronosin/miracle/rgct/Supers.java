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

    /** A class file, as far as this needs it: its direct supertypes and its methods (name + descriptor). */
    private record Shape(List<String> supers, java.util.Set<String> methods) {
        static final Shape UNKNOWN = new Shape(NONE, java.util.Set.of());
    }

    /** Internal name to shape, per loader: the same game, the same answers. */
    private static final Map<ClassLoader, Map<String, Shape>> CACHE = new ConcurrentHashMap<>();

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

    /**
     * Whether {@code owner} has the method, declared or inherited: what a call named by strings
     * ({@link Rgct#call}) must name, as a method reference would. True when it can't tell (a class
     * file it can't read), so a doubt never hides a call.
     */
    boolean has(String owner, String name, String desc) {
        if (loader == null) {
            return true;
        }
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(List.of(owner));
        java.util.Set<String> seen = new java.util.HashSet<>();
        String key = name + desc;
        while (!queue.isEmpty()) {
            String t = queue.poll();
            if (!seen.add(t)) {
                continue;
            }
            Shape sh = shape(t);
            if (sh == Shape.UNKNOWN) {
                return true;
            }
            if (sh.methods().contains(key)) {
                return true;
            }
            queue.addAll(sh.supers());
        }
        return false;
    }

    private List<String> direct(String type) {
        if (loader == null || type.startsWith("java/")) {
            return NONE;                // nothing of ours, or of the game's, is up there
        }
        return shape(type).supers();
    }

    private Shape shape(String type) {
        return CACHE.computeIfAbsent(loader, l -> new ConcurrentHashMap<>()).computeIfAbsent(type, this::read);
    }

    private Shape read(String type) {
        try (InputStream in = loader.getResourceAsStream(type + ".class")) {
            if (in == null) {
                return Shape.UNKNOWN;
            }
            ClassModel m = ClassFile.of().parse(in.readAllBytes());
            List<String> out = new ArrayList<>();
            m.superclass().map(ClassEntry::asInternalName).ifPresent(out::add);
            for (ClassEntry i : m.interfaces()) {
                out.add(i.asInternalName());
            }
            java.util.Set<String> methods = new java.util.HashSet<>();
            m.methods().forEach(mm -> methods.add(mm.methodName().stringValue() + mm.methodType().stringValue()));
            return new Shape(List.copyOf(out), java.util.Set.copyOf(methods));
        } catch (IOException | IllegalArgumentException e) {
            return Shape.UNKNOWN;
        }
    }
}
