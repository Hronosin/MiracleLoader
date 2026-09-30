package io.github.hronosin.miracle;

import io.github.hronosin.miracle.rgct.TransformRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.CodeSource;

/**
 * Loads the game and the mods, and runs every game class through RGCT on the way in.
 *
 * <p>Child-first: classes found in this loader's own jars are defined here (transformed), even
 * if the same jar also sits on the JVM class path. That lets launchers keep their usual class
 * path and only swap the main class. Only the JDK and the loader itself are delegated upward,
 * so mods and the game share exactly one copy of the Miracle API.
 */
final class MiracleClassLoader extends URLClassLoader implements Host {

    static {
        registerAsParallelCapable();
    }

    private static final String[] PARENT_FIRST = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "io.github.hronosin.miracle.",
    };

    private final TransformRegistry rgct;
    private final Path dumpDir;

    MiracleClassLoader(URL[] gameUrls, ClassLoader parent, TransformRegistry rgct, Path dumpDir) {
        super("miracle", gameUrls, parent);
        this.rgct = rgct;
        this.dumpDir = dumpDir;
    }

    @Override
    public ClassLoader loader() {
        return this;
    }

    @Override
    public void addMod(Path jar) {
        try {
            addURL(jar.toUri().toURL());
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** A folder inside the jar: {@code jar:file:/x.jar!/META-INF/miracle/baked/1.21.11/}. */
    @Override
    public void addVariant(Path jar, String dir) throws MalformedURLException {
        addURL(URI.create("jar:" + jar.toUri() + "!/" + dir).toURL());
    }

    /** True if the class was already defined by this loader. */
    @Override
    public boolean isAlreadyLoaded(String className) {
        return findLoadedClass(className) != null;
    }

    private static boolean parentFirst(String name) {
        for (String p : PARENT_FIRST) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (parentFirst(name)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                try {
                    c = findClass(name);
                } catch (ClassNotFoundException notOurs) {
                    c = getParent().loadClass(name);
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/') + ".class";
        URL url = findResource(path);
        if (url == null) {
            throw new ClassNotFoundException(name);
        }

        byte[] bytes;
        try (InputStream in = url.openStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }

        byte[] patched;
        try {
            patched = rgct.transform(name, bytes, this);
        } catch (RuntimeException e) {
            throw new MiracleFailure("RGCT could not transform " + name
                    + " (patched by: " + String.join(", ", rgct.modsTargeting(name)) + ")", e);
        }
        if (patched != bytes) {
            dump(path, patched);
        }

        definePackageFor(name);
        CodeSource source = new CodeSource(sourceOf(url, path), (CodeSigner[]) null);
        return defineClass(name, patched, 0, patched.length, source);
    }

    // Child-first for resources too, so the game reads its own assets, not a stale copy.
    @Override
    public URL getResource(String name) {
        URL url = findResource(name);
        return url != null ? url : super.getResource(name);
    }

    private void definePackageFor(String className) {
        int dot = className.lastIndexOf('.');
        if (dot < 0) {
            return;
        }
        String pkg = className.substring(0, dot);
        if (getDefinedPackage(pkg) == null) {
            try {
                definePackage(pkg, null, null, null, null, null, null, null);
            } catch (IllegalArgumentException alreadyDefinedByAnotherThread) {
                // fine
            }
        }
    }

    /** jar:file:/x.jar!/a/B.class -> file:/x.jar ; file:/dir/a/B.class -> file:/dir/ */
    private static URL sourceOf(URL url, String path) {
        try {
            String s = url.toString();
            if ("jar".equals(url.getProtocol())) {
                int bang = s.indexOf("!/");
                return URI.create(s.substring(4, bang)).toURL();
            }
            if (s.endsWith(path)) {
                return URI.create(s.substring(0, s.length() - path.length())).toURL();
            }
        } catch (MalformedURLException | IllegalArgumentException ignored) {
            // fall through
        }
        return url;
    }

    private void dump(String path, byte[] bytes) {
        if (dumpDir == null) {
            return;
        }
        try {
            Path out = dumpDir.resolve(path);
            Files.createDirectories(out.getParent());
            Files.write(out, bytes);
        } catch (IOException e) {
            Log.warn("Could not dump " + path + ": " + e.getMessage());
        }
    }
}
