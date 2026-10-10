package me.snowsun.tabprefix.domain;

public final class SaveCodeException extends IllegalArgumentException {
  public enum Reason {
    INVALID,
    EXPIRED,
    USED,
    OWNER,
    GROUP,
    CAPACITY
  }

  public final Reason reason;

  public SaveCodeException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public String messageKey() {
    switch (reason) {
      case EXPIRED:
        return "save-code.expired";
      case USED:
        return "save-code.already-used";
      case OWNER:
        return "save-code.wrong-player";
      case GROUP:
        return "save-code.wrong-group";
      case CAPACITY:
        return "save-code.glyph-limit";
      default:
        return "save-code.invalid";
    }
  }
}
