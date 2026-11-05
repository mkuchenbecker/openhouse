package com.linkedin.openhouse.javaclient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.linkedin.openhouse.javaclient.exception.WebClientRequestWithMessageException;
import com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException;
import com.linkedin.openhouse.tables.client.api.DatabaseApi;
import com.linkedin.openhouse.tables.client.api.TableApi;
import com.linkedin.openhouse.tables.client.model.GetDatabaseResponseBody;
import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * {@link OpenHouseCatalog#getEffectiveTableProperties(Table)} and its resolver, with a mocked
 * {@link DatabaseApi} (the pattern the jobs {@code TablesClientTest} uses).
 */
public class EffectiveTablePropertiesTest {

  private DatabaseApi databaseApi;

  private TableApi tableApi;

  private OpenHouseCatalog catalog;

  @BeforeEach
  public void setup() throws Exception {
    databaseApi = mock(DatabaseApi.class);
    tableApi = mock(TableApi.class);
    catalog = new OpenHouseCatalog();
    setField(catalog, "databaseApi", databaseApi);
    setField(catalog, "tableApi", tableApi);
    setField(catalog, "name", "openhouse");
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = OpenHouseCatalog.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Map<String, String> props(String... kv) {
    Map<String, String> map = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      map.put(kv[i], kv[i + 1]);
    }
    return map;
  }

  private static Table table(String name, Map<String, String> properties) {
    Table table = mock(Table.class);
    when(table.name()).thenReturn(name);
    when(table.properties()).thenReturn(properties);
    return table;
  }

  private static GetDatabaseResponseBody database(Map<String, String> properties) {
    // The generated model marks these read-only (no setters); populate them the way Jackson does.
    GetDatabaseResponseBody body = new GetDatabaseResponseBody();
    setDeclaredField(body, "databaseId", "d1");
    setDeclaredField(body, "properties", properties);
    return body;
  }

  private static void setDeclaredField(Object target, String name, Object value) {
    try {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static WebClientResponseException httpError(int status) {
    return WebClientResponseException.create(
        status, "reason", HttpHeaders.EMPTY, "{}".getBytes(StandardCharsets.UTF_8), null);
  }

  @Test
  public void testTablePropertiesOverrideInheritedDatabaseProperties() {
    Map<String, String> tableProps = props("a", "1", "openhouse.tableUUID", "u");
    Map<String, String> tablePropsCopy = new HashMap<>(tableProps);
    Table table = table("openhouse.d1.t1", tableProps);
    when(databaseApi.getDatabaseV1("d1"))
        .thenReturn(Mono.just(database(props("a", "9", "b", "2", "openhouse.tableUUID", "x"))));

    Map<String, String> effective = catalog.getEffectiveTableProperties(table);

    Assertions.assertEquals(props("a", "1", "b", "2", "openhouse.tableUUID", "u"), effective);
    // the table's actual properties are untouched
    Assertions.assertEquals(tablePropsCopy, table.properties());
    Mockito.verify(databaseApi, Mockito.times(1)).getDatabaseV1("d1");
    Mockito.verifyNoMoreInteractions(databaseApi);
    Mockito.verifyNoInteractions(tableApi);
  }

  @Test
  public void testDatabaseIdIsTakenFromServerStampedPropertyFirst() {
    Table table = table("openhouse.other_db.t1", props("openhouse.databaseId", "d1", "a", "1"));
    when(databaseApi.getDatabaseV1("d1")).thenReturn(Mono.just(database(props("b", "2"))));

    Assertions.assertEquals(
        props("openhouse.databaseId", "d1", "a", "1", "b", "2"),
        catalog.getEffectiveTableProperties(table));
    Mockito.verify(databaseApi).getDatabaseV1("d1");
    Mockito.verify(databaseApi, Mockito.never()).getDatabaseV1("other_db");
  }

  @Test
  public void testDatabaseIdFallsBackToTableNameWhenPropertyAbsent() {
    when(databaseApi.getDatabaseV1("d1")).thenReturn(Mono.just(database(props("b", "2"))));

    Assertions.assertEquals(
        props("a", "1", "b", "2"),
        catalog.getEffectiveTableProperties(table("openhouse.d1.t1", props("a", "1"))));
    Assertions.assertEquals(
        props("a", "1", "b", "2"),
        catalog.getEffectiveTableProperties(table("d1.t1", props("a", "1"))));
    Assertions.assertEquals(
        "d1", EffectiveTablePropertiesResolver.resolveDatabaseId(table("d1.t1", null)));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> EffectiveTablePropertiesResolver.resolveDatabaseId(table("t1", props())));
  }

  @Test
  public void testAbsentDatabaseYieldsTablePropertiesOnly() {
    Map<String, String> tableProps = props("a", "1", "policies", "{}");
    when(databaseApi.getDatabaseV1("d1")).thenReturn(Mono.error(httpError(404)));

    Map<String, String> effective =
        catalog.getEffectiveTableProperties(table("openhouse.d1.t1", tableProps));
    Assertions.assertEquals(tableProps, effective);
    Assertions.assertNotSame(tableProps, effective);
  }

  @Test
  public void testDatabaseWithNullPropertiesYieldsTablePropertiesOnly() {
    when(databaseApi.getDatabaseV1("d1")).thenReturn(Mono.just(database(null)));
    Assertions.assertEquals(
        props("a", "1"),
        catalog.getEffectiveTableProperties(table("openhouse.d1.t1", props("a", "1"))));
  }

  @Test
  public void testServerAndConnectionFailuresAreWrappedLikeTableLoads() {
    when(databaseApi.getDatabaseV1("d1")).thenReturn(Mono.error(httpError(500)));
    Assertions.assertThrows(
        WebClientResponseWithMessageException.class,
        () -> catalog.getEffectiveTableProperties(table("openhouse.d1.t1", props("a", "1"))));

    when(databaseApi.getDatabaseV1("d1")).thenReturn(Mono.error(httpError(403)));
    Assertions.assertThrows(
        WebClientResponseWithMessageException.class,
        () -> catalog.getEffectiveTableProperties(table("openhouse.d1.t1", props("a", "1"))));

    when(databaseApi.getDatabaseV1("d1"))
        .thenReturn(
            Mono.error(
                new WebClientRequestException(
                    new ConnectException("Connection refused"),
                    HttpMethod.GET,
                    URI.create("http://tables/v1/databases/d1"),
                    HttpHeaders.EMPTY)));
    Assertions.assertThrows(
        WebClientRequestWithMessageException.class,
        () -> catalog.getEffectiveTableProperties(table("openhouse.d1.t1", props("a", "1"))));
  }

  @Test
  public void testResultIsUnmodifiable() {
    when(databaseApi.getDatabaseV1("d1")).thenReturn(Mono.just(database(props("b", "2"))));
    Map<String, String> effective =
        catalog.getEffectiveTableProperties(table("openhouse.d1.t1", props("a", "1")));
    Assertions.assertThrows(UnsupportedOperationException.class, () -> effective.put("c", "3"));
    Assertions.assertThrows(UnsupportedOperationException.class, () -> effective.remove("a"));
  }

  @Test
  public void testMergeIsPure() {
    Map<String, String> db = props("a", "9", "b", "2");
    Map<String, String> tbl = props("a", "1");
    Map<String, String> merged = EffectiveTablePropertiesResolver.merge(db, tbl);
    Assertions.assertEquals(props("a", "1", "b", "2"), merged);
    Assertions.assertEquals(props("a", "9", "b", "2"), db);
    Assertions.assertEquals(props("a", "1"), tbl);
    Assertions.assertEquals(props("a", "1"), EffectiveTablePropertiesResolver.merge(null, tbl));
    Assertions.assertEquals(
        props("b", "2"), EffectiveTablePropertiesResolver.merge(props("b", "2"), null));
    Assertions.assertTrue(EffectiveTablePropertiesResolver.merge(null, null).isEmpty());
  }

  @Test
  public void testLoadTableIssuesNoDatabaseCall() {
    // doRefresh is unchanged: a table load never consults the database entity, before or after.
    when(databaseApi.getDatabaseV1(anyString()))
        .thenThrow(new AssertionError("loadTable must not call DatabaseApi"));
    when(tableApi.getTableV1(any(), any())).thenReturn(Mono.error(httpError(404)));

    Assertions.assertThrows(
        NoSuchTableException.class, () -> catalog.loadTable(TableIdentifier.of("d1", "t1")));
    Mockito.verify(tableApi).getTableV1("d1", "t1");
    Mockito.verifyNoInteractions(databaseApi);
  }
}
