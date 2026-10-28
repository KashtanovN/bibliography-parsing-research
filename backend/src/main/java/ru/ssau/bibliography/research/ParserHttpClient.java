package ru.ssau.bibliography.research;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class ParserHttpClient {
  private static final int MAX_RESPONSE = 2 * 1024 * 1024;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final URI base;
  private final Duration timeout;

  ParserHttpClient(URI base, Duration timeout) {
    if (!java.util.Set.of("http", "https").contains(base.getScheme())
        || base.getHost() == null
        || base.getUserInfo() != null
        || base.getQuery() != null
        || base.getFragment() != null)
      throw new IllegalArgumentException(
          "Адрес сервиса должен быть HTTP(S) URL без логина, query и fragment");
    if (timeout.isNegative() || timeout.isZero())
      throw new IllegalArgumentException("Тайм-аут должен быть положительным");
    this.base = URI.create(base.toString().replaceAll("/+$", "") + "/");
    this.timeout = timeout;
  }

  HttpResponse<byte[]> post(String path, String type, String accept, String body)
      throws IOException, InterruptedException {
    return send(
        HttpRequest.newBuilder(base.resolve(path))
            .timeout(timeout)
            .header("Content-Type", type)
            .header("Accept", accept)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build());
  }

  String get(String path) throws IOException, InterruptedException {
    var response =
        send(
            HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build());
    if (response.statusCode() != 200)
      throw new IOException("HTTP " + response.statusCode() + " при запросе " + path);
    return body(response);
  }

  private HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
    var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    if (response.body().length > MAX_RESPONSE)
      throw new CitationParser.ParseFailure("Ответ сервиса больше 2 МБ", null);
    return response;
  }

  static String body(HttpResponse<byte[]> response) {
    return new String(response.body(), StandardCharsets.UTF_8);
  }
}
