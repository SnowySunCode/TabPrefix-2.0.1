package me.snowsun.tabprefix.util;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

public final class AtomicFiles {
  private AtomicFiles() {}

  public static void move(Path from, Path to) throws IOException {
    try {
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  public static void write(Path file, byte[] bytes) throws IOException {
    Files.createDirectories(file.getParent());
    Path temporary = Files.createTempFile(file.getParent(), ".writing-", ".tmp");
    try {
      Files.write(temporary, bytes);
      move(temporary, file);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public static void deleteTree(Path directory) throws IOException {
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
    Files.walkFileTree(
        directory,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException error)
              throws IOException {
            if (error != null) throw error;
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
