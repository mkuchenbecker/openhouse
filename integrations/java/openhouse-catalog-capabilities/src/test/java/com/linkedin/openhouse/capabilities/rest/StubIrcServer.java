package com.linkedin.openhouse.capabilities.rest;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A local HTTP server that records every request and answers each {@code METHOD path} with a
 * programmed status and body (204 with no body when nothing is programmed). It stands in for the
 * tables service's IRC base path, so the capability client's requests can be checked byte for byte.
 */
final class StubIrcServer implements AutoCloseable {

  /** One request as the server received it. */
  static final class Request {
    final String method;
    final String path;
    final Map<String, String> headers;
    final String body;

    Request(String method, String path, Map<String, String> headers, String body) {
      this.method = method;
      this.path = path;
      this.headers = headers;
      this.body = body;
    }

    /** A header by name, case-insensitively, or null. */
    String header(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
      return method + " " + path;
    }
  }

  private static final class Answer {
    final int status;
    final String body;

    Answer(int status, String body) {
      this.status = status;
      this.body = body;
    }
  }

  private final HttpServer server;
  private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
  private final Map<String, Answer> answers = Collections.synchronizedMap(new HashMap<>());

  StubIrcServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] received = readAll(exchange.getRequestBody());
          Map<String, String> headers = new TreeMap<>();
          exchange
              .getRequestHeaders()
              .forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
          String path = exchange.getRequestURI().getRawPath();
          requests.add(
              new Request(
                  exchange.getRequestMethod(),
                  path,
                  headers,
                  new String(received, StandardCharsets.UTF_8)));
          Answer answer = answers.get(exchange.getRequestMethod() + " " + path);
          if (answer == null || answer.body == null) {
            exchange.sendResponseHeaders(answer == null ? 204 : answer.status, -1);
          } else {
            byte[] bytes = answer.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(bytes);
            }
          }
          exchange.close();
        });
    server.start();
  }

  private static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int n;
    while ((n = in.read(buffer)) != -1) {
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }

  /**
   * The catalog URI a client would be configured with, for example {@code http://host:p/iceberg}.
   */
  String uri() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/iceberg";
  }

  /** Answers {@code method path} (the path below the host, for example /iceberg/v1/...). */
  void answer(String method, String path, int status, String body) {
    answers.put(method + " " + path, new Answer(status, body));
  }

  /** Answers {@code method path} with an IRC error body. */
  void error(String method, String path, int status, String type, String message) {
    answer(
        method,
        path,
        status,
        "{\"error\":{\"message\":\""
            + message
            + "\",\"type\":\""
            + type
            + "\",\"code\":"
            + status
            + "}}");
  }

  List<Request> requests() {
    synchronized (requests) {
      return new ArrayList<>(requests);
    }
  }

  void clear() {
    requests.clear();
    answers.clear();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
