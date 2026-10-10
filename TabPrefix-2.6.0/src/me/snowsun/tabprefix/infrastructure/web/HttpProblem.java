package me.snowsun.tabprefix.infrastructure.web;

public final class HttpProblem extends RuntimeException {
  public final int status;

  public HttpProblem(int status, String message) {
    super(message);
    this.status = status;
  }
}
