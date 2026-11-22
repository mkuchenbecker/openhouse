package com.linkedin.openhouse.javaclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plain create ({@link Catalog.TableBuilder#create()}, which Spark's CREATE TABLE uses) against a
 * mock Tables service. The table service owns the table location: the create request carries no
 * location, and the created table reports the location the service assigned.
 */
public class OpenHouseCatalogCreateTableTest {

  private static final TableIdentifier ID = TableIdentifier.of("db", "t");
  private static final Schema SCHEMA =
      new Schema(
          Types.NestedField.optional(1, "id", Types.IntegerType.get()),
          Types.NestedField.optional(2, "name", Types.StringType.get()));

  @TempDir Path tempDir;

  @Test
  public void createSendsNoLocationAndTheTableHasTheServiceAssignedOne() throws Exception {
    String serviceLocation = tempDir.resolve("service-assigned").toUri().toString();

    Created withoutLocation = create(null, serviceLocation);
    Created withLocation = create("file:/tmp/client-chosen-location", serviceLocation);

    System.out.println("create request body: " + withoutLocation.requestBody);
    Assertions.assertEquals(
        withoutLocation.requestBody,
        withLocation.requestBody,
        "a client-side location must not change what the service is asked to create");
    Assertions.assertFalse(
        withoutLocation.requestBody.toLowerCase().contains("location"),
        "the create request must not carry a location: " + withoutLocation.requestBody);
    Assertions.assertEquals(serviceLocation, withoutLocation.tableLocation);
    Assertions.assertEquals(serviceLocation, withLocation.tableLocation);
  }

  private static final class Created {
    private final String requestBody;
    private final String tableLocation;

    private Created(String requestBody, String tableLocation) {
      this.requestBody = requestBody;
      this.tableLocation = tableLocation;
    }
  }

  /**
   * Creates {@link #ID} through the catalog, with {@code clientLocation} if non-null. The mock
   * service answers 404 until it receives the create (PUT), then serves a metadata file whose
   * location is {@code serviceLocation}, as the real service would after allocating storage.
   */
  private Created create(String clientLocation, String serviceLocation) throws IOException {
    Path metadataFile = Files.createTempFile(tempDir, "v1-", ".metadata.json");
    TableMetadataParser.overwrite(
        TableMetadata.newTableMetadata(
            SCHEMA, PartitionSpec.unpartitioned(), serviceLocation, Collections.emptyMap()),
        org.apache.iceberg.Files.localOutput(metadataFile.toFile()));
    String getTableBody =
        "{\"databaseId\":\"db\",\"tableId\":\"t\",\"tableLocation\":\""
            + metadataFile.toUri()
            + "\"}";

    AtomicReference<String> createBody = new AtomicReference<>();
    try (MockWebServer service = new MockWebServer()) {
      service.setDispatcher(
          new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
              if (!"/v1/databases/db/tables/t".equals(request.getPath())) {
                return new MockResponse().setResponseCode(500);
              }
              if ("PUT".equals(request.getMethod())) {
                createBody.set(request.getBody().readString(StandardCharsets.UTF_8));
                return json(201, getTableBody);
              }
              return createBody.get() == null
                  ? new MockResponse().setResponseCode(404)
                  : json(200, getTableBody);
            }
          });
      service.start();

      OpenHouseCatalog catalog = new OpenHouseCatalog();
      catalog.setConf(new Configuration());
      Map<String, String> properties = new HashMap<>();
      properties.put(
          CatalogProperties.URI,
          String.format("http://%s:%s", service.getHostName(), service.getPort()));
      catalog.initialize("openhouse", properties);

      Table table =
          catalog
              .buildTable(ID, SCHEMA)
              .withLocation(clientLocation)
              .withProperty("k", "v")
              .create();

      Assertions.assertNotNull(createBody.get(), "create must reach the service");
      return new Created(createBody.get(), table.location());
    }
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse()
        .setResponseCode(code)
        .addHeader("Content-Type", "application/json")
        .setBody(body);
  }
}
