package ru.ssau.bibliography;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MetadataClientTest {
  HttpServer server;
  MetadataClient client;
  URI uri;
  AtomicInteger calls;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    calls = new AtomicInteger();
    uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/metadata");
    client = new MetadataClient(new ObjectMapper(), "");
  }

  void respond(int status, String body) {
    server.createContext(
        "/metadata",
        exchange -> {
          calls.incrementAndGet();
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void successfulResponsesCachedAndIndependent() {
    respond(200, "{\"title\":\"test\"}");
    var first = client.get(uri);
    ((com.fasterxml.jackson.databind.node.ObjectNode) first).put("title", "mutated");
    assertThat(client.get(uri).path("title").asText()).isEqualTo("test");
    assertThat(calls.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @CsvSource({"404,404", "429,503", "500,503"})
  void providerStatuses(int incoming, int expected) {
    respond(incoming, "{}");
    for (int i = 0; i < 2; i++)
      assertThatThrownBy(() -> client.get(uri))
          .isInstanceOfSatisfying(
              ApiProblem.class, e -> assertThat(e.status()).isEqualTo(expected));
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void redirectsNotFollowed() {
    server.createContext(
        "/metadata",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "/secret");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/secret",
        exchange -> {
          calls.incrementAndGet();
          exchange.sendResponseHeaders(200, 2);
          exchange.getResponseBody().write("{}".getBytes());
          exchange.close();
        });
    server.start();
    assertThatThrownBy(() -> client.get(uri))
        .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.status()).isEqualTo(502));
    assertThat(calls.get()).isZero();
  }

  @Test
  void oversizedResponseRejected() {
    respond(200, "{\"data\":\"" + "x".repeat(2_000_001) + "\"}");
    assertThatThrownBy(() -> client.get(uri))
        .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.status()).isEqualTo(503));
  }

  @Test
  void nonObjectRejected() {
    respond(200, "[]");
    assertThatThrownBy(() -> client.get(uri))
        .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.status()).isEqualTo(502));
  }

  @Test
  void malformedJsonNotCached() {
    respond(200, "not json");
    for (int i = 0; i < 2; i++)
      assertThatThrownBy(() -> client.get(uri)).isInstanceOf(ApiProblem.class);
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void timeoutMappedTo503() {
    HttpClient http = mock(HttpClient.class);
    when(http.sendAsync(
            any(HttpRequest.class),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
        .thenReturn(CompletableFuture.failedFuture(new HttpTimeoutException("test")));
    var timed = new MetadataClient(new ObjectMapper(), "", http);
    assertThatThrownBy(() -> timed.get(uri))
        .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.status()).isEqualTo(503));
  }
}
