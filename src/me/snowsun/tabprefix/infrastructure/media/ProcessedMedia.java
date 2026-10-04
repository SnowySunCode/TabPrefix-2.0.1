package me.snowsun.tabprefix.infrastructure.media;

import java.awt.image.BufferedImage;
import java.util.*;

public final class ProcessedMedia {
  public final String format;
  public final int sourceWidth, sourceHeight;
  public final List<BufferedImage> frames;
  public final int[] delays;

  public ProcessedMedia(
      String format, int width, int height, List<BufferedImage> frames, int[] delays) {
    this.format = format;
    sourceWidth = width;
    sourceHeight = height;
    this.frames = Collections.unmodifiableList(new ArrayList<>(frames));
    this.delays = delays.clone();
  }
}
