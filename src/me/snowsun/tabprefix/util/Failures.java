package me.snowsun.tabprefix.util;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public final class Failures {
  private Failures() {}

  public static Throwable root(Throwable error) {
    while ((error instanceof CompletionException || error instanceof ExecutionException)
        && error.getCause() != null) error = error.getCause();
    return error;
  }

  public static String message(Throwable error) {
    Throwable root = root(error);
    return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
  }
}
