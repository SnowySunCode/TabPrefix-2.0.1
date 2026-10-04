package me.snowsun.tabprefix.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class SafePaths {
  private SafePaths() {}

  public static Path inside(Path root, String relative) throws IOException {
    Path input = Paths.get(relative);
    Path base = root.toAbsolutePath().normalize();
    Path target = base.resolve(input).normalize();
    if (input.isAbsolute() || target.equals(base) || !target.startsWith(base)) {
      throw new IOException("Path must point inside the plugin data folder: " + relative);
    }
    Path current = base;
    for (Path segment : base.relativize(target)) {
      current = current.resolve(segment);
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
        throw new IOException("Symbolic links are not allowed in storage paths: " + relative);
      }
    }
    return target;
  }
}
