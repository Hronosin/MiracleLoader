package io.github.hronosin.miracle.cli;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Dust to dust: deleting folders in a way Windows puts up with.
 *
 * <p>On Windows a deleted file lingers while anything else holds it open (OneDrive syncing,
 * the antivirus scanning, the search indexer, an Explorer window), so deleting a folder right after
 * its files fails as "not empty" for a moment. A folder is therefore first renamed aside, which
 * works at once, and the renamed one deleted with a little patience; if something still holds on,
 * it's left for the next time, and the work goes on in a fresh folder.
 */
final class Dust {

    private static final String ASIDE = ".old-";

    private Dust() {
    }

    /** Removes {@code dir} and everything in it; afterwards {@code dir} doesn't exist. */
    static void wipe(Path dir) throws IOException {
        sweep(dir);
        if (!Files.exists(dir)) {
            return;
        }
        Path aside = dir.resolveSibling(dir.getFileName() + ASIDE + Long.toHexString(System.nanoTime()));
        try {
            Files.move(dir, aside);
        } catch (IOException renameFailed) {
            delete(dir, true); // no way around it: in place, and a failure is an error
            return;
        }
        delete(aside, false);
    }

    /** Earlier wipes' leftovers ({@code <name>.old-*} next to it), if whatever held them has let go. */
    private static void sweep(Path dir) throws IOException {
        Path parent = dir.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(parent, dir.getFileName() + ASIDE + "*")) {
            for (Path old : ds) {
                delete(old, false);
            }
        }
    }

    private static void delete(Path root, boolean mustSucceed) throws IOException {
        List<Path> all;
        try (Stream<Path> s = Files.walk(root)) {
            all = s.sorted(Comparator.reverseOrder()).toList();
        } catch (NoSuchFileException gone) {
            return;
        }
        for (Path p : all) {
            try {
                deletePatiently(p);
            } catch (IOException e) {
                if (mustSucceed) {
                    throw e;
                }
                return; // left for the next sweep
            }
        }
    }

    /** Retries for up to about three seconds while Windows finishes letting go. */
    private static void deletePatiently(Path p) throws IOException {
        boolean madeWritable = false;
        for (int attempt = 1; ; attempt++) {
            try {
                Files.deleteIfExists(p);
                return;
            } catch (AccessDeniedException e) {
                if (!madeWritable) {
                    madeWritable = true;
                    DosFileAttributeView dos = Files.getFileAttributeView(p, DosFileAttributeView.class);
                    if (dos != null) {
                        try {
                            dos.setReadOnly(false); // read-only files can't be deleted on Windows
                            continue;
                        } catch (IOException ignored) {
                            // fall through to waiting
                        }
                    }
                }
                if (attempt >= 12) {
                    throw e;
                }
            } catch (DirectoryNotEmptyException e) {
                if (attempt >= 12) {
                    throw e;
                }
            }
            try {
                Thread.sleep(25L * attempt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while deleting " + p, ie);
            }
        }
    }

    /**
     * A file system error in words: Java's own messages for these are often just the path, which
     * is where the "heavens are silent" with nothing but a folder name came from.
     */
    static String explain(IOException e) {
        if (!(e instanceof FileSystemException fse)) {
            return e.getMessage();
        }
        String file = fse.getFile() != null ? fse.getFile() : "?";
        String other = fse.getOtherFile() != null ? " -> " + fse.getOtherFile() : "";
        String reason = fse.getReason() != null ? " (" + fse.getReason() + ")" : "";
        String what = switch (e) {
            case DirectoryNotEmptyException _ -> "couldn't delete a folder, something still has files open in it: " + file
                    + reason + ". OneDrive, an antivirus, an Explorer window or a running game? Close it and pray again.";
            case AccessDeniedException _ -> "access denied: " + file + other + reason
                    + ". Is it open in another program, or in a folder you can't write to?";
            case NoSuchFileException _ -> "no such file: " + file + other;
            default -> e.getClass().getSimpleName() + ": " + file + other + reason;
        };
        return what;
    }
}
