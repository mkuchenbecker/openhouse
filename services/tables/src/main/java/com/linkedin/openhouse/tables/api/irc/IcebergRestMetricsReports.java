package com.linkedin.openhouse.tables.api.irc;

import com.google.gson.JsonObject;
import com.linkedin.openhouse.common.audit.ServiceAuditAspect;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import javax.servlet.http.HttpServletRequest;
import org.apache.iceberg.metrics.CommitReport;
import org.apache.iceberg.metrics.MetricsReport;
import org.apache.iceberg.metrics.ScanReport;
import org.apache.iceberg.rest.requests.ReportMetricsRequest;

/**
 * The body of a metrics report ({@code POST .../tables/{table}/metrics}), read with a bound, and
 * what the service audit records of it.
 *
 * <p>A client posts a report after every scan and every commit, and its size is the client's: so
 * the body is read up to {@value #MAX_REPORT_BYTES} bytes, and a larger one is refused (400, the
 * spec's answer for a request the server does not accept) before more of it is read. And the
 * service audit, which records every request's body, records a report by what identifies it and its
 * size ({@code report-type}, {@code table-name}, {@code snapshot-id}, {@code bytes}), never its
 * content: a report is telemetry, and its metrics, projected field names and metadata map are the
 * client's.
 */
final class IcebergRestMetricsReports {

  /**
   * The largest report body read, in bytes. Iceberg's scan and commit reports are a few kilobytes:
   * a few dozen counters and timers, the projected field ids and names, a small metadata map.
   */
  static final int MAX_REPORT_BYTES = 1024 * 1024;

  private IcebergRestMetricsReports() {}

  /**
   * The body of {@code request}, read up to {@link #MAX_REPORT_BYTES} bytes; until it is read as a
   * report ({@link #audit}), audited as its size alone.
   *
   * @throws org.apache.iceberg.exceptions.BadRequestException if the body is larger
   */
  static byte[] read(HttpServletRequest request) {
    long declared = request.getContentLengthLong();
    audit(request, sized(declared));
    if (declared > MAX_REPORT_BYTES) {
      throw tooLarge(declared);
    }
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    try (InputStream in = request.getInputStream()) {
      int read;
      while ((read = in.read(chunk)) != -1) {
        body.write(chunk, 0, read);
        if (body.size() > MAX_REPORT_BYTES) {
          throw tooLarge(-1);
        }
      }
    } catch (IOException e) {
      throw new org.apache.iceberg.exceptions.BadRequestException(
          "Invalid ReportMetricsRequest: the body cannot be read: %s", e.getMessage());
    }
    audit(request, sized(body.size()));
    return body.toByteArray();
  }

  /** Records, for the service audit, what identifies {@code report} and its size. */
  static void audit(HttpServletRequest request, ReportMetricsRequest report, int bytes) {
    JsonObject audited = new JsonObject();
    // As the report spells it ("scan-report", "commit-report").
    audited.addProperty(
        "report-type",
        String.valueOf(report.reportType()).replace('_', '-').toLowerCase(Locale.ROOT));
    MetricsReport content = report.report();
    if (content instanceof ScanReport) {
      audited.addProperty("table-name", ((ScanReport) content).tableName());
      audited.addProperty("snapshot-id", ((ScanReport) content).snapshotId());
    } else if (content instanceof CommitReport) {
      audited.addProperty("table-name", ((CommitReport) content).tableName());
      audited.addProperty("snapshot-id", ((CommitReport) content).snapshotId());
    }
    audited.addProperty("bytes", bytes);
    audit(request, audited);
  }

  private static JsonObject sized(long bytes) {
    JsonObject audited = new JsonObject();
    if (bytes >= 0) {
      audited.addProperty("bytes", bytes);
    }
    return audited;
  }

  private static void audit(HttpServletRequest request, JsonObject audited) {
    request.setAttribute(ServiceAuditAspect.AUDITED_REQUEST_PAYLOAD, audited);
  }

  private static org.apache.iceberg.exceptions.BadRequestException tooLarge(long bytes) {
    return new org.apache.iceberg.exceptions.BadRequestException(
        "Invalid ReportMetricsRequest: the report is %s bytes, larger than the %d bytes this server"
            + " accepts",
        bytes >= 0 ? Long.toString(bytes) : "more than " + MAX_REPORT_BYTES, MAX_REPORT_BYTES);
  }
}
