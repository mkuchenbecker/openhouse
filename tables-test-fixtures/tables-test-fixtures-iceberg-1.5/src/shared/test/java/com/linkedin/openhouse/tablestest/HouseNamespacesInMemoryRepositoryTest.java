package com.linkedin.openhouse.tablestest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.exception.checked.DuplicateCreationException;
import com.linkedin.openhouse.common.exception.checked.NotFoundException;
import com.linkedin.openhouse.internal.catalog.model.NamespaceMetadata;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The namespace stand-in answers as the house tables service's namespace store does through {@link
 * com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepositoryImpl}: a
 * case-insensitive unique name that keeps the spelling it was created with, a revision that starts
 * at "0" and moves on every replace (an unchanged document included), a replace conditional on the
 * uuid and revision read, and the same exceptions for a duplicate, a missing record and a stale
 * document.
 */
public class HouseNamespacesInMemoryRepositoryTest {

  private final HouseNamespacesInMemoryRepository repository =
      new HouseNamespacesInMemoryRepository();

  @Test
  public void createKeepsTheSpellingAndStartsAtRevisionZero() throws Exception {
    NamespaceMetadata created = repository.create("Sales_Db", Collections.singletonMap("k", "v"));
    assertEquals("Sales_Db", created.getNamespaceName());
    assertEquals("0", created.getRevision());
    assertEquals(Collections.singletonMap("k", "v"), created.getProperties());
    assertEquals(created, repository.getByName("sales_db"));
    assertEquals(created, repository.getByName("SALES_DB"));
  }

  @Test
  public void createOfACaseVariantIsADuplicate() throws Exception {
    repository.create("orders", Collections.emptyMap());
    assertThrows(
        DuplicateCreationException.class,
        () -> repository.create("ORDERS", Collections.singletonMap("k", "v")));
    assertEquals("orders", repository.getByName("Orders").getNamespaceName());
    assertEquals(Collections.emptyMap(), repository.getByName("orders").getProperties());
  }

  @Test
  public void missingRecordsAreNotFound() {
    assertThrows(NotFoundException.class, () -> repository.getByName("nothing"));
    assertThrows(NotFoundException.class, () -> repository.delete("nothing"));
    assertThrows(
        NotFoundException.class,
        () ->
            repository.update(
                "nothing", new NamespaceMetadata("uuid", "nothing", Collections.emptyMap(), "0")));
  }

  @Test
  public void replaceIsConditionalOnUuidAndRevision() throws Exception {
    NamespaceMetadata created = repository.create("ns", Collections.singletonMap("a", "1"));
    NamespaceMetadata replaced =
        repository.update(
            "NS",
            new NamespaceMetadata(
                created.getNamespaceUuid(), "ns", Collections.singletonMap("b", "2"), "0"));
    assertEquals("1", replaced.getRevision());
    assertEquals(Collections.singletonMap("b", "2"), replaced.getProperties());
    assertEquals(created.getNamespaceUuid(), replaced.getNamespaceUuid());
    assertEquals(replaced, repository.getByName("ns"));

    // The revision read before the replace is stale now.
    assertThrows(
        EntityConcurrentModificationException.class,
        () ->
            repository.update(
                "ns",
                new NamespaceMetadata(
                    created.getNamespaceUuid(), "ns", Collections.singletonMap("c", "3"), "0")));
    // A document of another record with the same name (dropped and created again) is stale too.
    assertThrows(
        EntityConcurrentModificationException.class,
        () ->
            repository.update(
                "ns",
                new NamespaceMetadata(
                    "another-uuid", "ns", Collections.singletonMap("c", "3"), "1")));
    assertEquals(replaced, repository.getByName("ns"));
  }

  @Test
  public void anUnchangedDocumentStillMovesTheRevision() throws Exception {
    NamespaceMetadata created = repository.create("ns", Collections.singletonMap("a", "1"));
    NamespaceMetadata replaced = repository.update("ns", created);
    assertEquals("1", replaced.getRevision());
    assertEquals(created.getProperties(), replaced.getProperties());
  }

  @Test
  public void aDocumentWithAnotherSpellingOrNoPropertiesIsRefusedAndChangesNothing()
      throws Exception {
    NamespaceMetadata created = repository.create("Ns", Collections.singletonMap("a", "1"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            repository.update(
                "ns",
                new NamespaceMetadata(
                    created.getNamespaceUuid(), "ns", Collections.emptyMap(), "0")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            repository.update(
                "ns", new NamespaceMetadata(created.getNamespaceUuid(), "Ns", null, "0")));
    assertEquals(created, repository.getByName("ns"));
  }

  @Test
  public void deleteRemovesTheRecordAndACreateAfterItIsANewRecord() throws Exception {
    NamespaceMetadata first = repository.create("ns", Collections.singletonMap("a", "1"));
    repository.delete("NS");
    assertThrows(NotFoundException.class, () -> repository.getByName("ns"));
    NamespaceMetadata second = repository.create("NS", Collections.emptyMap());
    assertNotEquals(first.getNamespaceUuid(), second.getNamespaceUuid());
    assertEquals("NS", second.getNamespaceName());
    assertEquals("0", second.getRevision());
  }

  @Test
  public void storedPropertiesAreCopiesAndAnswersCannotBeChanged() throws Exception {
    Map<String, String> properties = new HashMap<>(Collections.singletonMap("a", "1"));
    NamespaceMetadata created = repository.create("ns", properties);
    properties.put("b", "2");
    assertEquals(Collections.singletonMap("a", "1"), repository.getByName("ns").getProperties());
    assertThrows(UnsupportedOperationException.class, () -> created.getProperties().put("c", "3"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> repository.getByName("ns").getProperties().put("c", "3"));
  }
}
