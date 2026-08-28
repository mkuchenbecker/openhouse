package com.linkedin.openhouse.common.exception.handler;

import com.linkedin.openhouse.common.api.spec.ErrorResponseBody;
import com.linkedin.openhouse.common.exception.CorruptEntityTypeException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public class OpenHouseExceptionHandlerTest {

  private static final String CORRUPT_MSG =
      "Column user_table_row.entity_type holds unrecognized value [TÁBLE]; "
          + "only TABLE, VIEW (in any case) and NULL are valid";

  private final OpenHouseExceptionHandler handler = new OpenHouseExceptionHandler();

  /**
   * The corruption response is a stable generic 500: the column, stored value, and converter stack
   * are operator material that goes to the server log under a correlation id, and the body names
   * only that id. Persistence detail must not become part of the public error contract.
   */
  @Test
  public void testCorruptEntityTypeIsStableServerErrorWithoutDiagnostic() {
    CorruptEntityTypeException corrupt =
        new CorruptEntityTypeException(CORRUPT_MSG, new IllegalArgumentException("TÁBLE"));

    ResponseEntity<ErrorResponseBody> response = handler.handleCorruptEntityTypeException(corrupt);

    Assertions.assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    ErrorResponseBody body = response.getBody();
    Assertions.assertNotNull(body);
    Assertions.assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, body.getStatus());
    Assertions.assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(), body.getError());

    // The stable message points at the log, not at the data.
    Assertions.assertTrue(body.getMessage().contains("correlationId="));
    Assertions.assertFalse(body.getMessage().contains("user_table_row.entity_type"));
    Assertions.assertFalse(body.getMessage().contains("TÁBLE"));

    // No stack trace and no cause detail leave the process.
    Assertions.assertNull(body.getStacktrace());
    Assertions.assertEquals("Not Available", body.getCause());
  }

  /** Each response carries its own correlation id, so two failures are distinguishable in logs. */
  @Test
  public void testCorruptEntityTypeCorrelationIdsAreUniquePerResponse() {
    CorruptEntityTypeException corrupt = new CorruptEntityTypeException(CORRUPT_MSG);

    String first = handler.handleCorruptEntityTypeException(corrupt).getBody().getMessage();
    String second = handler.handleCorruptEntityTypeException(corrupt).getBody().getMessage();

    Assertions.assertNotEquals(first, second);
  }

  /**
   * The corruption type deliberately shares no ancestry with {@link IllegalArgumentException}, so
   * it can never fall through to the 400-shaped client-input advice.
   */
  @Test
  public void testCorruptEntityTypeIsNotCatchCompatibleWithClientInputFailures() {
    Assertions.assertFalse(
        IllegalArgumentException.class.isAssignableFrom(CorruptEntityTypeException.class));
  }

  /** The generic path keeps its existing shape; corruption hygiene changes it not at all. */
  @Test
  public void testGenericExceptionKeepsItsShape() {
    RuntimeException unrelated = new RuntimeException("connection reset");

    ResponseEntity<ErrorResponseBody> response = handler.handleGenericException(unrelated);

    Assertions.assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    Assertions.assertEquals(unrelated.toString(), response.getBody().getMessage());
    Assertions.assertNotNull(response.getBody().getStacktrace());
  }
}
