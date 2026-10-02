package org.test.nosy;

import io.github.hronosin.miracle.api.MiracleMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/** Names everything a label should notice; does none of it. */
public final class Nosy implements MiracleMod {
    @Override
    public void onLaunch() {
        System.out.println("[nosy] launched, did nothing nosy");
        System.out.println("[nosy] backend: " + io.github.hronosin.miracle.api.Mods.graphicsBackend());
    }

    static void phoneHome() throws Exception {
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("https://example.invalid")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static void runSomething() throws Exception {
        new ProcessBuilder("true").start();
        Files.writeString(Path.of("nosy.txt"), "hi");
    }

    static void leave() {
        System.exit(3);
    }
}
