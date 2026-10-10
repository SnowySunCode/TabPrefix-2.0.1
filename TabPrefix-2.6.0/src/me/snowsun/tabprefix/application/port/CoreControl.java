package me.snowsun.tabprefix.application.port;

import me.snowsun.tabprefix.domain.RuntimeStatus;

public interface CoreControl {
  boolean ready();

  RuntimeStatus status();

  void reload() throws Exception;

  void prefixesChanged();

  void error(String action, Throwable error);

  default void diagnostic(String report) {}

  default String webDiagnostic() {
    return "unavailable";
  }
}
