package me.snowsun.tabprefix.application.port;

@FunctionalInterface
public interface MainThreadExecutor {
  void execute(Runnable action);
}
