package ru.ssau.bibliography;

import java.util.Map;

public class ApiProblem extends RuntimeException {
  private final int status;
  private final Map<String, ?> body;

  public ApiProblem(int status, String message) {
    this(status, Map.of("detail", message));
  }

  public ApiProblem(int status, Map<String, ?> body) {
    super(String.valueOf(body.getOrDefault("detail", null)));
    this.status = status;
    this.body = body;
  }

  public int status() {
    return status;
  }

  public Map<String, ?> body() {
    return body;
  }

  public static ApiProblem fields(Map<String, String> errors) {
    return new ApiProblem(400, Map.of("fields", errors));
  }
}
