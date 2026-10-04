package me.snowsun.tabprefix.application.port;

import java.util.Map;

@FunctionalInterface
public interface AuditSink {
  void record(String event, Map<String, String> values);
}
