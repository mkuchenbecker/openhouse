package com.linkedin.openhouse.common.exception.handler;

import com.linkedin.openhouse.common.api.spec.ErrorResponseBody;
import com.linkedin.openhouse.common.exception.CorruptEntityTypeException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * The log half of the sealed-500 contract: the correlation id in the response body must actually
 * find the diagnostic in the server log. An orphaned correlation id would be a worse failure mode
 * than the leak the sealing fixed, so this half is pinned as hard as the response half.
 *
 * <p>Lives in housetables, not {@code services:common}: the capture reads log4j2's own {@code
 * LogEvent}s, which requires SLF4J bound to log4j2. {@code openhouse.springboot-conventions} gives
 * this module that binding — it swaps Boot's logback stack for {@code spring-boot-starter-log4j2}
 * plus {@code log4j-slf4j2-impl} — while {@code services:common} applies only {@code
 * openhouse.java-conventions} and gets logback transitively through its webflux and data-jpa
 * starters, under which this appender would never see an event. It sits in the handler's package
 * (from this module's test tree) because the advice methods are protected.
 */
public class CorruptionLogContractTest {

  private static final Pattern CORRELATION_ID = Pattern.compile("correlationId=([0-9a-f-]+)");

  private static final String CORRUPT_MSG =
      "Column user_table_row.entity_type holds unrecognized value ['TÁBLE']; "
          + "only TABLE, VIEW (in any case) and NULL are valid";

  private final OpenHouseExceptionHandler handler = new OpenHouseExceptionHandler();

  private Logger handlerLogger;
  private CapturingAppender appender;

  @BeforeEach
  public void attachAppender() {
    handlerLogger = (Logger) LogManager.getLogger(OpenHouseExceptionHandler.class);
    appender = new CapturingAppender();
    appender.start();
    handlerLogger.addAppender(appender);
  }

  @AfterEach
  public void detachAppender() {
    handlerLogger.removeAppender(appender);
    appender.stop();
  }

  @Test
  public void corruptionDetailIsLoggedExactlyOnceUnderTheReturnedCorrelationId() {
    CorruptEntityTypeException corrupt =
        new CorruptEntityTypeException(CORRUPT_MSG, new IllegalArgumentException("TÁBLE"));

    ResponseEntity<ErrorResponseBody> response = handler.handleCorruptEntityTypeException(corrupt);
    String correlationId = correlationIdOf(response);

    List<LogEvent> matching = appender.errorsNaming(correlationId);
    Assertions.assertEquals(
        1, matching.size(), "exactly one ERROR event must carry the returned correlation id");

    Throwable logged = matching.get(0).getThrown();
    Assertions.assertNotNull(logged, "the diagnostic exception must be attached to the event");
    Assertions.assertSame(corrupt, logged);
    Assertions.assertTrue(
        logged.getMessage().contains("user_table_row.entity_type"),
        "the logged diagnostic must name the column");
    Assertions.assertTrue(
        logged.getMessage().contains("TÁBLE"), "the logged diagnostic must carry the stored value");
  }

  /** Two renderings of the same failure stay distinguishable in the log. */
  @Test
  public void distinctResponsesLogUnderDistinctCorrelationIds() {
    CorruptEntityTypeException corrupt = new CorruptEntityTypeException(CORRUPT_MSG);

    String first = correlationIdOf(handler.handleCorruptEntityTypeException(corrupt));
    String second = correlationIdOf(handler.handleCorruptEntityTypeException(corrupt));

    Assertions.assertNotEquals(first, second);
    Assertions.assertEquals(1, appender.countNaming(first));
    Assertions.assertEquals(1, appender.countNaming(second));
  }

  private static String correlationIdOf(ResponseEntity<ErrorResponseBody> response) {
    ErrorResponseBody body = response.getBody();
    Assertions.assertNotNull(body, "the sealed 500 must carry a response body");
    Matcher matcher = CORRELATION_ID.matcher(body.getMessage());
    Assertions.assertTrue(matcher.find(), "the body must name a correlation id");
    return matcher.group(1);
  }

  /**
   * Collects immutable copies of every event the handler's logger emits, and answers the two
   * questions this contract asks of them, so no test reaches into the captured list itself.
   */
  private static final class CapturingAppender extends AbstractAppender {

    private final List<LogEvent> events = new ArrayList<>();

    private CapturingAppender() {
      super("corruption-log-contract", null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
      events.add(event.toImmutable());
    }

    /** How many captured events, at any level, name {@code correlationId}. */
    long countNaming(String correlationId) {
      return events.stream().filter(event -> names(event, correlationId)).count();
    }

    /** The captured ERROR events naming {@code correlationId}, in emission order. */
    List<LogEvent> errorsNaming(String correlationId) {
      return events.stream()
          .filter(event -> event.getLevel() == Level.ERROR)
          .filter(event -> names(event, correlationId))
          .collect(Collectors.toList());
    }

    private static boolean names(LogEvent event, String correlationId) {
      return event.getMessage().getFormattedMessage().contains(correlationId);
    }
  }
}
