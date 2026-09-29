package io.github.hronosin.miracle.cli;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Downloads with a cache: a file whose SHA-1 already matches is never fetched again. */
final class Http {

    record Job(String url, Path dest, String sha1) {
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private Http() {
    }

    static String text(String url) throws IOException {
        return new String(bytes(url), StandardCharsets.UTF_8);
    }

    static byte[] bytes(String url) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpResponse<byte[]> r = CLIENT.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(5)).header("User-Agent", "miracle-toolchain").build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200) {
                    throw new IOException("HTTP " + r.statusCode() + " for " + url);
                }
                return r.body();
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
        throw last;
    }

    /** Fetches one file unless it's already there with the right hash. Returns true if downloaded. */
    static boolean fetch(Job job) throws IOException {
        if (Files.isRegularFile(job.dest()) && (job.sha1() == null || job.sha1().equals(sha1(job.dest())))) {
            return false;
        }
        byte[] data = bytes(job.url());
        if (job.sha1() != null && !job.sha1().equals(sha1(data))) {
            throw new IOException("checksum mismatch for " + job.url());
        }
        Files.createDirectories(job.dest().getParent());
        Path tmp = job.dest().resolveSibling(job.dest().getFileName() + ".part");
        Files.write(tmp, data);
        Files.move(tmp, job.dest(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    /** Many files at once, with one progress line. */
    static void fetchAll(String what, List<Job> jobs) throws IOException {
        if (jobs.isEmpty()) {
            return;
        }
        AtomicInteger done = new AtomicInteger();
        AtomicInteger downloaded = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Future<Object>> futures = jobs.stream().map(j -> pool.submit((java.util.concurrent.Callable<Object>) () -> {
                if (fetch(j)) {
                    downloaded.incrementAndGet();
                }
                int n = done.incrementAndGet();
                if (n % 200 == 0 || n == jobs.size()) {
                    System.out.print("\r  " + what + ": " + n + "/" + jobs.size());
                }
                return null;
            })).toList();
            for (Future<Object> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
        }
        System.out.println(downloaded.get() == 0 ? " (cached)" : " (" + downloaded.get() + " downloaded)");
    }

    static String sha1(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
