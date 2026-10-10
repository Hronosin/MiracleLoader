import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Holds a world's session.lock the way the game does, until killed: java LockHolder.java <file>. */
public class LockHolder {
    public static void main(String[] args) throws Exception {
        try (FileChannel ch = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = ch.tryLock()) {
            System.out.println(lock != null ? "holding" : "taken");
            Thread.sleep(60_000);
        }
    }
}
