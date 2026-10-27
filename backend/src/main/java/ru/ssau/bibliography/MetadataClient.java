package ru.ssau.bibliography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MetadataClient {
  private static final int MAX_BYTES = 2_000_000;
  private final ObjectMapper mapper;
  private final HttpClient http;
  private final String userAgent;
  private final Cache<URI, JsonNode> cache =
      Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofMinutes(15)).build();

  @org.springframework.beans.factory.annotation.Autowired
  public MetadataClient(
      ObjectMapper mapper, @Value("${bibliography.crossref-mailto:}") String mailto) {
    this(
        mapper,
        mailto,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  MetadataClient(ObjectMapper mapper, String mailto, HttpClient http) {
    this.mapper = mapper;
    this.http = http;
    this.userAgent = "SSAU-Bibliography/1.0" + (mailto.isBlank() ? "" : " (mailto:" + mailto + ")");
  }

  public JsonNode get(URI uri) {
    // URIs are constructed by MetadataImporter, never taken directly from user input.
    JsonNode cached = cache.getIfPresent(uri);
    if (cached != null) return cached.deepCopy();
    var request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .GET()
            .build();
    var pending = http.sendAsync(request, info -> new LimitedBodySubscriber());
    try {
      var response = pending.get(25, TimeUnit.SECONDS);
      int status = response.statusCode();
      if (status == 404)
        throw new ApiProblem(404, "Не удалось найти источник. Проверьте идентификатор.");
      if (status == 429)
        throw new ApiProblem(503, "Источник данных временно ограничил запросы. Попробуйте позже.");
      if (status >= 300 && status < 400)
        throw new ApiProblem(502, "Источник данных вернул неожиданный ответ. Попробуйте позже.");
      if (status < 200 || status >= 300) throw unavailable();
      JsonNode data = mapper.readTree(response.body());
      if (data == null || !data.isObject())
        throw new ApiProblem(502, "Источник данных вернул некорректный ответ.");
      cache.put(uri, data.deepCopy());
      return data;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      pending.cancel(true);
      throw unavailable();
    } catch (ExecutionException | TimeoutException | IOException e) {
      pending.cancel(true);
      throw unavailable();
    }
  }

  private ApiProblem unavailable() {
    return new ApiProblem(
        503, "Источник данных недоступен. Повторите запрос позже или заполните поля вручную.");
  }

  private static class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate =
        HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription subscription;
    private int size;

    @Override
    public CompletionStage<byte[]> getBody() {
      return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        size += buffer.remaining();
        if (size > MAX_BYTES) {
          subscription.cancel();
          delegate.onError(new IOException("Metadata response too large"));
          return;
        }
      }
      delegate.onNext(buffers);
    }

    @Override
    public void onError(Throwable error) {
      delegate.onError(error);
    }

    @Override
    public void onComplete() {
      delegate.onComplete();
    }
  }
}
