package ru.ssau.bibliography;

import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ApiProblem.class)
  ResponseEntity<?> problem(ApiProblem e) {
    return ResponseEntity.status(e.status()).body(e.body());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<?> invalidJson(Exception e) {
    return ResponseEntity.badRequest().body(Map.of("detail", "Ожидается корректный JSON."));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<?> method(Exception e) {
    return ResponseEntity.status(405).body(Map.of("detail", "Метод запроса не поддерживается."));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<?> media(Exception e) {
    return ResponseEntity.status(415)
        .body(Map.of("detail", "Используйте Content-Type: application/json."));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<?> missing(Exception e) {
    return ResponseEntity.status(404).body(Map.of("detail", "Адрес не найден."));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e) {
    LoggerFactory.getLogger(getClass()).error("Unhandled API error", e);
    return ResponseEntity.internalServerError()
        .body(Map.of("detail", "Внутренняя ошибка сервера. Повторите запрос позже."));
  }
}
