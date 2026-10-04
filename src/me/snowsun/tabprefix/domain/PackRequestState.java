package me.snowsun.tabprefix.domain;

/** 1.16 status events have no pack ID: never overlap requests. */
public final class PackRequestState {
  public enum Offer {
    SEND,
    QUEUED,
    SAME
  }

  private PackRevision pending, loaded, queued;
  private long sentAt;
  private boolean timedOut;

  public synchronized Offer offer(PackRevision revision, long now) {
    if (pending != null) {
      if (pending.hash.equals(revision.hash)) return Offer.SAME;
      queued = revision;
      return Offer.QUEUED;
    }
    if (loaded != null && loaded.hash.equals(revision.hash)) return Offer.SAME;
    pending = revision;
    sentAt = now;
    timedOut = false;
    return Offer.SEND;
  }

  public synchronized PackRevision terminal(boolean success) {
    if (pending == null) return null;
    loaded = success ? pending : null;
    pending = null;
    PackRevision next = success ? queued : null;
    queued = null;
    return next;
  }

  public synchronized boolean timeout(long now) {
    if (pending == null || timedOut || now - sentAt < 120000) return false;
    timedOut = true;
    loaded = null;
    return true;
  }

  public synchronized PackRevision loaded() {
    return loaded;
  }

  public synchronized boolean pending() {
    return pending != null;
  }
}
