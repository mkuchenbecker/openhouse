package com.linkedin.openhouse.tables.audit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.linkedin.openhouse.common.audit.ServiceAuditPayloadRedactor;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

/**
 * Keeps view definitions out of service audit events.
 *
 * <p>{@link com.linkedin.openhouse.common.audit.ServiceAuditAspect} audits the complete cached
 * request body of every controller call, which for the view create and replace routes would retain
 * the caller's full SQL text and schema documents. This walks the request tree and replaces every
 * {@code schema} value and every {@code sql} value with {@link #REDACTED_VALUE} before the event is
 * built. The keys are kept, so an auditor still sees that the fields were sent.
 *
 * <p>The walk is deliberately structural rather than path-based: the definition-bearing subtrees
 * sit at different depths in the two request shapes — {@code schema} and {@code
 * view-version.representations[*].sql} in a {@code CreateViewRequest}; {@code updates[*].schema}
 * ({@code add-schema}) and {@code updates[*].view-version .representations[*].sql} ({@code
 * add-view-version}) in a {@code CommitViewRequest} — and a recursive key match cannot silently
 * miss a new nesting. Everything that is not a {@code schema} or {@code sql} value (identifiers,
 * dialects, property maps, requirements) stays intact, so an audit event still identifies what was
 * operated on and by whom.
 *
 * <p>Scoped by request URI rather than by field name on purpose: {@code
 * CreateUpdateTableRequestBody} also carries a {@code schema}, and redacting by name alone would
 * silently change table, database and snapshot audit payloads. Matching the two view write routes
 * leaves every other route's payload exactly as it was.
 */
@Component
public class ViewRequestPayloadRedactor implements ServiceAuditPayloadRedactor {

  static final String SCHEMA_FIELD = "schema";
  static final String SQL_FIELD = "sql";

  /** The view collection route, which POST creates against. */
  private static final String VIEW_COLLECTION_PATTERN = "/v1/namespaces/*/views";

  /** The view item route, which POST replaces against. */
  private static final String VIEW_ITEM_PATTERN = "/v1/namespaces/*/views/*";

  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

  @Override
  public boolean appliesTo(HttpServletRequest request) {
    String uri = request.getRequestURI();
    return uri != null
        && (PATH_MATCHER.match(VIEW_COLLECTION_PATTERN, uri)
            || PATH_MATCHER.match(VIEW_ITEM_PATTERN, uri));
  }

  @Override
  public JsonElement redact(JsonElement requestPayload) {
    if (requestPayload == null || !requestPayload.isJsonObject()) {
      // A bodyless request parses to JsonNull, and a malformed body can be any other element.
      // Neither carries a view definition, so there is nothing to remove.
      return requestPayload;
    }
    JsonObject redacted = requestPayload.deepCopy().getAsJsonObject();
    redactInPlace(redacted);
    return redacted;
  }

  /** Recursively replaces every {@code schema} and {@code sql} value under {@code element}. */
  private static void redactInPlace(JsonElement element) {
    if (element.isJsonObject()) {
      JsonObject object = element.getAsJsonObject();
      for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
        String key = entry.getKey();
        if (SCHEMA_FIELD.equals(key) || SQL_FIELD.equals(key)) {
          object.add(key, new JsonPrimitive(REDACTED_VALUE));
        } else {
          redactInPlace(entry.getValue());
        }
      }
    } else if (element.isJsonArray()) {
      JsonArray array = element.getAsJsonArray();
      for (JsonElement item : array) {
        redactInPlace(item);
      }
    }
  }
}
