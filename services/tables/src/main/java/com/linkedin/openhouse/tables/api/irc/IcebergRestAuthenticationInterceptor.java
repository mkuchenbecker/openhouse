package com.linkedin.openhouse.tables.api.irc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.rest.responses.ErrorResponseParser;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * Runs the cluster's token interceptor, the same one the native routes run, on the IRC routes, and
 * turns its refusal into the spec's {@code ErrorResponse}. The token interceptor decides alone: a
 * request it admits is admitted with the principal it set, and a request it refuses keeps the
 * status it chose. Only the body changes, from whatever the interceptor wrote (the dummy one writes
 * nothing) to an {@code ErrorResponse} whose message is that body, or the status phrase when it is
 * empty. The native routes keep the token interceptor unwrapped.
 */
public class IcebergRestAuthenticationInterceptor implements HandlerInterceptor {

  private final HandlerInterceptor tokenInterceptor;

  public IcebergRestAuthenticationInterceptor(HandlerInterceptor tokenInterceptor) {
    this.tokenInterceptor = tokenInterceptor;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    BufferedResponse buffered = new BufferedResponse(response);
    boolean admitted = tokenInterceptor.preHandle(request, buffered, handler);
    byte[] written = buffered.body();
    if (admitted) {
      if (written.length > 0) {
        response.getOutputStream().write(written);
      }
      return true;
    }
    HttpStatus status = HttpStatus.resolve(buffered.getStatus());
    if (status == null || !status.isError()) {
      status = HttpStatus.UNAUTHORIZED;
    }
    String message =
        written.length > 0
            ? new String(written, StandardCharsets.UTF_8)
            : buffered.errorMessage != null ? buffered.errorMessage : status.getReasonPhrase();
    ErrorResponse error = IcebergRestErrors.authenticationFailure(status, message);
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getOutputStream()
        .write(ErrorResponseParser.toJson(error).getBytes(StandardCharsets.UTF_8));
    return false;
  }

  @Override
  public void postHandle(
      HttpServletRequest request,
      HttpServletResponse response,
      Object handler,
      ModelAndView modelAndView)
      throws Exception {
    tokenInterceptor.postHandle(request, response, handler, modelAndView);
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
      throws Exception {
    tokenInterceptor.afterCompletion(request, response, handler, ex);
  }

  /** Passes headers and status through, but keeps the body and {@code sendError} to itself. */
  private static final class BufferedResponse extends HttpServletResponseWrapper {
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private int status = HttpServletResponse.SC_OK;
    private String errorMessage;
    private PrintWriter writer;

    BufferedResponse(HttpServletResponse response) {
      super(response);
    }

    byte[] body() {
      if (writer != null) {
        writer.flush();
      }
      return body.toByteArray();
    }

    @Override
    public void setStatus(int sc) {
      this.status = sc;
    }

    @Override
    public int getStatus() {
      return status;
    }

    @Override
    public void sendError(int sc) {
      this.status = sc;
    }

    @Override
    public void sendError(int sc, String msg) {
      this.status = sc;
      this.errorMessage = msg;
    }

    @Override
    public ServletOutputStream getOutputStream() {
      return new ServletOutputStream() {
        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {}

        @Override
        public void write(int b) {
          body.write(b);
        }
      };
    }

    @Override
    public PrintWriter getWriter() {
      if (writer == null) {
        writer = new PrintWriter(new OutputStreamWriter(body, StandardCharsets.UTF_8));
      }
      return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
      // Nothing is committed until the outer interceptor decides what to write.
    }
  }
}
