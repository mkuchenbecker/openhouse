package com.linkedin.openhouse.spark.smoke;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A table as the service stored it, as a platform's test service shows it without the catalog path
 * under test: the schema, partition spec, properties, snapshots in commit order, refs and, per ref,
 * the rows a reader of that ref gets (deletes applied), each row as comma-joined values, sorted. A
 * snapshot no ref reaches (a staged write, or what a replace left behind) has its rows under {@code
 * snapshot <position>}.
 *
 * <p>Built from plain data with these keys, so that it needs nothing but the JDK: {@code columns}
 * (list of {@code name type}, with {@code not null} and the comment if the column has them), {@code
 * partitioning} (as Iceberg prints the fields), {@code properties} (map), {@code snapshots} (list
 * of maps with {@code id}, {@code operation}, {@code summary} and {@code reachable}), {@code refs}
 * (map of name to {@code branch} or {@code tag}) and {@code rows} (map of ref or snapshot to rows).
 */
public final class StoredTable {
  private final Map<String, Object> stored;

  public StoredTable(Map<String, Object> stored) {
    this.stored = stored;
  }

  /** Each column as {@code name type}, with {@code not null} and the comment if it has them. */
  public List<String> columns() {
    return strings(stored.get("columns"));
  }

  /** The partition fields, as Iceberg prints them. */
  public String partitioning() {
    return String.valueOf(stored.get("partitioning"));
  }

  public Map<String, String> properties() {
    Map<String, String> properties = new LinkedHashMap<>();
    map(stored.get("properties")).forEach((k, v) -> properties.put(k, String.valueOf(v)));
    return properties;
  }

  /** The operation of every snapshot, in commit order. */
  public List<String> operations() {
    List<String> operations = new ArrayList<>();
    for (Object snapshot : list(stored.get("snapshots"))) {
      operations.add(String.valueOf(map(snapshot).get("operation")));
    }
    return operations;
  }

  /** The summary of the snapshot at {@code position} (from 1, in commit order). */
  public Map<String, String> summary(int position) {
    Map<String, String> summary = new LinkedHashMap<>();
    map(snapshot(position).get("summary")).forEach((k, v) -> summary.put(k, String.valueOf(v)));
    return summary;
  }

  /** The id of the snapshot at {@code position} (from 1, in commit order). */
  public long snapshotId(int position) {
    return ((Number) snapshot(position).get("id")).longValue();
  }

  /** Whether a branch or tag reaches the snapshot at {@code position} (from 1). */
  public boolean reachable(int position) {
    return Boolean.TRUE.equals(snapshot(position).get("reachable"));
  }

  /** Each ref as {@code name=branch} or {@code name=tag}, sorted by name. */
  public List<String> refs() {
    List<String> refs = new ArrayList<>();
    map(stored.get("refs")).forEach((k, v) -> refs.add(k + "=" + v));
    Collections.sort(refs);
    return refs;
  }

  /** The rows a reader of {@code main} gets, comma-joined and sorted; none without a main. */
  public List<String> rows() {
    return rows("main");
  }

  /**
   * The rows a reader of {@code ref} gets, or of {@code snapshot <position>} for a snapshot no ref
   * reaches; an empty list if there is no such ref.
   */
  public List<String> rows(String ref) {
    Object rows = map(stored.get("rows")).get(ref);
    return rows == null ? new ArrayList<>() : strings(rows);
  }

  private Map<String, Object> snapshot(int position) {
    return map(list(stored.get("snapshots")).get(position - 1));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return value == null ? Collections.emptyMap() : (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return value == null ? Collections.emptyList() : (List<Object>) value;
  }

  private static List<String> strings(Object value) {
    List<String> strings = new ArrayList<>();
    for (Object v : list(value)) {
      strings.add(String.valueOf(v));
    }
    return strings;
  }

  @Override
  public String toString() {
    return stored.toString();
  }
}
