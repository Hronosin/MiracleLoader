package io.github.hronosin.miracle.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code miracle scriptorium} (alias {@code ide}): the room where scribes work. Writes project
 * files for IntelliJ IDEA ({@code .idea/}) and for VS Code and Eclipse ({@code .project},
 * {@code .classpath}, {@code .vscode/}), with the same class path {@code bake} compiles against: the
 * loader, the library, Minecraft and its libraries, with sources attached where there are any. In
 * IntelliJ each {@code fallback/<version>} is a module of its own, against that version's readable
 * API. The files hold absolute paths into this machine's caches, so they're for this machine only
 * (and go into .gitignore); run it again after changing {@code minecraft} or moving the toolchain.
 */
final class Scriptorium {

    private static final List<String> IGNORED = List.of(".idea/", "*.iml", ".project", ".classpath", ".settings/", ".vscode/");

    private Scriptorium() {
    }

    /** A jar on the class path, and its sources if they're known. */
    record Lib(String name, Path jar, Path sources) {
    }

    static int run(Project p, boolean idea, boolean eclipse) throws IOException {
        if (!idea && !eclipse) {
            idea = true;
            eclipse = true;
        }
        Mojang.Version primary = Mojang.version(p.minecraft());
        if (primary.obfuscated()) {
            throw new Miracle.Heresy("minecraft = \"" + p.minecraft() + "\" is obfuscated; write against 26.1 or newer.");
        }
        List<Lib> libs = new ArrayList<>();
        libs.add(lib("MiracleLoader", Miracle.loaderJar()));
        if (p.usesToolchain()) {
            libs.add(lib("MiracleToolChain library", Miracle.toolchainJar()));
        }
        libs.add(new Lib("Minecraft " + primary.id(), Mojang.clientJar(primary), null));
        for (Path l : Mojang.compileLibraries(primary)) {
            libs.add(lib(l.getFileName().toString().replaceFirst("\\.jar$", ""), l));
        }
        Map<String, Path> fallbacks = new LinkedHashMap<>();
        Path fb = p.dir().resolve("fallback");
        if (Files.isDirectory(fb)) {
            try (Stream<Path> dirs = Files.list(fb)) {
                for (Path d : dirs.filter(Files::isDirectory).sorted().toList()) {
                    fallbacks.put(d.getFileName().toString(), Builder.api(Mojang.version(d.getFileName().toString())));
                }
            }
        }
        Path self = Miracle.selfJar();
        if (idea) {
            idea(p, libs, fallbacks, self);
        }
        if (eclipse) {
            eclipse(p, libs, self);
        }
        ignore(p.dir());

        System.out.println("The scriptorium is ready for " + p.name() + " (Minecraft " + primary.id() + ", " + libs.size()
                + " jars on the class path).");
        if (idea) {
            System.out.println("  IntelliJ IDEA : open this folder. Run configurations: Bake, Pray (client), Pray (server).");
            if (!fallbacks.isEmpty()) {
                System.out.println("                  fallbacks are modules of their own: " + String.join(", ", fallbacks.keySet()));
            }
        }
        if (eclipse) {
            System.out.println("  VS Code       : open this folder with the Extension Pack for Java. Tasks: miracle: bake, pray client, pray server.");
            System.out.println("  Eclipse       : File > Import > Existing Projects into Workspace.");
            if (!fallbacks.isEmpty()) {
                System.out.println("                  (fallbacks aren't part of this project there; IntelliJ has them)");
            }
        }
        System.out.println("These files point into this machine's caches: they're in .gitignore. Run 'miracle scriptorium' again after"
                + " changing minecraft or moving the toolchain.");
        return 0;
    }

    private static Lib lib(String name, Path jar) {
        Path sources = jar.resolveSibling(jar.getFileName().toString().replaceFirst("\\.jar$", "-sources.jar"));
        return new Lib(name, jar, Files.isRegularFile(sources) ? sources : null);
    }

    // --- IntelliJ IDEA ----------------------------------------------------------------------

    private static void idea(Project p, List<Lib> libs, Map<String, Path> fallbacks, Path self) throws IOException {
        Path idea = p.dir().resolve(".idea");
        Files.createDirectories(idea.resolve("runConfigurations"));
        String main = p.id();
        write(idea.resolve("misc.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project version="4">
                  <component name="ProjectRootManager" version="2" languageLevel="JDK_25" project-jdk-name="25" project-jdk-type="JavaSDK">
                    <output url="%s" />
                  </component>
                </project>
                """.formatted(attr(file(p.dir().resolve("build").resolve("idea")))));
        StringBuilder modules = new StringBuilder();
        modules.append(moduleLine(idea.resolve(main + ".iml")));
        for (String v : fallbacks.keySet()) {
            modules.append(moduleLine(idea.resolve("fallback-" + v + ".iml")));
        }
        write(idea.resolve("modules.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project version="4">
                  <component name="ProjectModuleManager">
                    <modules>
                %s    </modules>
                  </component>
                </project>
                """.formatted(modules));

        StringBuilder content = new StringBuilder();
        content.append("    <content url=\"").append(attr(file(p.dir()))).append("\">\n");
        content.append(sourceFolder(p.dir().resolve("src"), false));
        content.append(sourceFolder(p.dir().resolve("resources"), true));
        for (String ex : List.of("build", "run", ".idea")) {
            content.append("      <excludeFolder url=\"").append(attr(file(p.dir().resolve(ex)))).append("\" />\n");
        }
        content.append("    </content>\n");
        write(idea.resolve(main + ".iml"), module(content.toString(), libs, List.of()));

        for (Map.Entry<String, Path> f : fallbacks.entrySet()) {
            Path dir = p.dir().resolve("fallback").resolve(f.getKey());
            String c = "    <content url=\"" + attr(file(dir)) + "\">\n" + sourceFolder(dir.resolve("src"), false) + "    </content>\n";
            List<Lib> fl = new ArrayList<>();
            for (Lib l : libs) {
                if (!l.name().startsWith("Minecraft ")) {
                    fl.add(l); // the fallback sees its own version's API instead of the primary's
                }
            }
            fl.add(new Lib("Minecraft " + f.getKey() + " (readable API)", f.getValue(), null));
            write(idea.resolve("fallback-" + f.getKey() + ".iml"), module(c, fl, List.of(main)));
        }

        runConfig(idea, "Bake", self, "bake");
        runConfig(idea, "Pray (client)", self, "pray client");
        runConfig(idea, "Pray (server)", self, "pray server");
    }

    private static String moduleLine(Path iml) {
        String f = attr(slashes(iml));
        return "      <module fileurl=\"file://" + f + "\" filepath=\"" + f + "\" />\n";
    }

    private static String sourceFolder(Path dir, boolean resources) {
        return "      <sourceFolder url=\"" + attr(file(dir)) + "\"" + (resources ? " type=\"java-resource\"" : " isTestSource=\"false\"")
                + " />\n";
    }

    private static String module(String content, List<Lib> libs, List<String> dependsOn) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<module type=\"JAVA_MODULE\" version=\"4\">\n");
        sb.append("  <component name=\"NewModuleRootManager\" inherit-compiler-output=\"true\">\n    <exclude-output />\n");
        sb.append(content);
        sb.append("    <orderEntry type=\"inheritedJdk\" />\n    <orderEntry type=\"sourceFolder\" forTests=\"false\" />\n");
        for (String m : dependsOn) {
            sb.append("    <orderEntry type=\"module\" module-name=\"").append(attr(m)).append("\" />\n");
        }
        for (Lib l : libs) {
            sb.append("    <orderEntry type=\"module-library\">\n      <library name=\"").append(attr(l.name())).append("\">\n");
            sb.append("        <CLASSES>\n          <root url=\"").append(attr(jar(l.jar()))).append("\" />\n        </CLASSES>\n");
            sb.append("        <JAVADOC />\n");
            if (l.sources() != null) {
                sb.append("        <SOURCES>\n          <root url=\"").append(attr(jar(l.sources()))).append("\" />\n        </SOURCES>\n");
            } else {
                sb.append("        <SOURCES />\n");
            }
            sb.append("      </library>\n    </orderEntry>\n");
        }
        sb.append("  </component>\n</module>\n");
        return sb.toString();
    }

    private static void runConfig(Path idea, String name, Path self, String args) throws IOException {
        write(idea.resolve("runConfigurations").resolve(name.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "") + ".xml"), """
                <component name="ProjectRunConfigurationManager">
                  <configuration default="false" name="%s" type="JarApplication">
                    <option name="JAR_PATH" value="%s" />
                    <option name="PROGRAM_PARAMETERS" value="%s" />
                    <option name="WORKING_DIRECTORY" value="$PROJECT_DIR$" />
                    <option name="ALTERNATIVE_JRE_PATH_ENABLED" value="false" />
                    <method v="2" />
                  </configuration>
                </component>
                """.formatted(attr(name), attr(slashes(self)), attr(args)));
    }

    // --- VS Code and Eclipse ----------------------------------------------------------------

    private static void eclipse(Project p, List<Lib> libs, Path self) throws IOException {
        write(p.dir().resolve(".project"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <projectDescription>
                  <name>%s</name>
                  <comment>Made by miracle scriptorium</comment>
                  <projects></projects>
                  <buildSpec>
                    <buildCommand>
                      <name>org.eclipse.jdt.core.javabuilder</name>
                      <arguments></arguments>
                    </buildCommand>
                  </buildSpec>
                  <natures>
                    <nature>org.eclipse.jdt.core.javanature</nature>
                  </natures>
                </projectDescription>
                """.formatted(attr(p.id())));
        StringBuilder cp = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<classpath>\n");
        cp.append("  <classpathentry kind=\"src\" path=\"src\"/>\n");
        if (Files.isDirectory(p.dir().resolve("resources"))) {
            cp.append("  <classpathentry kind=\"src\" path=\"resources\"/>\n");
        }
        cp.append("  <classpathentry kind=\"con\" path=\"org.eclipse.jdt.launching.JRE_CONTAINER/"
                + "org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-25\"/>\n");
        for (Lib l : libs) {
            cp.append("  <classpathentry kind=\"lib\" path=\"").append(attr(slashes(l.jar()))).append('"');
            if (l.sources() != null) {
                cp.append(" sourcepath=\"").append(attr(slashes(l.sources()))).append('"');
            }
            cp.append("/>\n");
        }
        cp.append("  <classpathentry kind=\"output\" path=\"build/eclipse\"/>\n</classpath>\n");
        write(p.dir().resolve(".classpath"), cp.toString());
        Files.createDirectories(p.dir().resolve(".settings"));
        write(p.dir().resolve(".settings").resolve("org.eclipse.jdt.core.prefs"), """
                eclipse.preferences.version=1
                org.eclipse.jdt.core.compiler.codegen.targetPlatform=25
                org.eclipse.jdt.core.compiler.compliance=25
                org.eclipse.jdt.core.compiler.release=enabled
                org.eclipse.jdt.core.compiler.source=25
                """);

        Path vscode = p.dir().resolve(".vscode");
        Files.createDirectories(vscode);
        write(vscode.resolve("extensions.json"), Json.write(Map.of("recommendations", List.of("vscjava.vscode-java-pack"))));
        List<Object> tasks = new ArrayList<>();
        for (String[] t : new String[][] {{"bake", "bake"}, {"pray client", "pray client"}, {"pray server", "pray server"}}) {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("label", "miracle: " + t[0]);
            task.put("type", "process");
            task.put("command", Miracle.javaExecutable());
            List<Object> args = new ArrayList<>(List.of("-jar", self.toString()));
            args.addAll(List.of(t[1].split(" ")));
            task.put("args", args);
            task.put("options", Map.of("cwd", "${workspaceFolder}"));
            task.put("problemMatcher", List.of());
            if (t[0].equals("bake")) {
                task.put("group", Map.of("kind", "build", "isDefault", true));
            }
            tasks.add(task);
        }
        Map<String, Object> tasksJson = new LinkedHashMap<>();
        tasksJson.put("version", "2.0.0");
        tasksJson.put("tasks", tasks);
        write(vscode.resolve("tasks.json"), Json.write(tasksJson));
    }

    // --- helpers ----------------------------------------------------------------------------

    /** Adds the IDE files to the project's .gitignore: they hold this machine's paths. */
    private static void ignore(Path dir) throws IOException {
        Path gi = dir.resolve(".gitignore");
        List<String> have = Files.isRegularFile(gi) ? Files.readAllLines(gi) : List.of();
        StringBuilder add = new StringBuilder();
        for (String i : IGNORED) {
            if (!have.contains(i)) {
                add.append(i).append('\n');
            }
        }
        if (add.length() > 0) {
            String old = Files.isRegularFile(gi) ? Files.readString(gi) : "";
            Files.writeString(gi, old + (old.isEmpty() || old.endsWith("\n") ? "" : "\n") + "# IDE files (miracle scriptorium)\n" + add);
        }
    }

    private static String slashes(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    /** IntelliJ's file URL: file:// followed by the path as it is (file:///home/.. or file://C:/..). */
    private static String file(Path p) {
        return "file://" + slashes(p);
    }

    private static String jar(Path p) {
        return "jar://" + slashes(p) + "!/";
    }

    private static String attr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void write(Path file, String text) throws IOException {
        Files.writeString(file, text);
    }
}
