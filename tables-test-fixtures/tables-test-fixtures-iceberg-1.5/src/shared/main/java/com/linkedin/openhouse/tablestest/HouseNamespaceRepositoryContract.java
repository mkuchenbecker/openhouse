package com.linkedin.openhouse.tablestest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.exception.checked.DuplicateCreationException;
import com.linkedin.openhouse.common.exception.checked.NotFoundException;
import com.linkedin.openhouse.internal.catalog.model.NamespaceMetadata;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * What the tables service relies on from a {@link HouseNamespaceRepository}, run against the
 * embedded stand-in ({@code HouseNamespacesInMemoryRepositoryTest}) and against the real repository
 * over a real House Tables service (in the tables service's namespace integration tests), so that
 * the stand-in cannot drift from the store: a case-insensitive unique name that keeps the spelling
 * it was created with, a revision that starts at "0" and moves on every replace (an unchanged
 * document included), a replace conditional on the uuid and revision read, and the same exceptions
 * for a duplicate, a missing record, a stale document and an invalid one.
 *
 * <p>Every name carries a suffix of its own per test, so that the tests can share one store.
 */
public abstract class HouseNamespaceRepositoryContract {

  private static final AtomicInteger TESTS = new AtomicInteger();

  private String suffix;

  /** The repository under test. */
  protected abstract HouseNamespaceRepository repository();

  @BeforeEach
  public void nameThisTest() {
    suffix = "_c" + TESTS.incrementAndGet();
  }

  /** The name, spelled as given, with this test's suffix. */
  protected String n(String name) {
    return name + suffix;
  }

  @Test
  public void createKeepsTheSpellingAndStartsAtRevisionZero() throws Exception {
    NamespaceMetadata created =
        repository().create(n("Sales_Db"), Collections.singletonMap("k", "v"));
    assertEquals(n("Sales_Db"), created.getNamespaceName());
    assertEquals("0", created.getRevision());
    assertEquals(Collections.singletonMap("k", "v"), created.getProperties());
    assertEquals(created, repository().getByName(n("sales_db")));
    assertEquals(created, repository().getByName(n("SALES_DB")));
  }

  @Test
  public void createOfACaseVariantIsADuplicate() throws Exception {
    repository().create(n("orders"), Collections.emptyMap());
    assertThrows(
        DuplicateCreationException.class,
        () -> repository().create(n("ORDERS"), Collections.singletonMap("k", "v")));
    assertEquals(n("orders"), repository().getByName(n("Orders")).getNamespaceName());
    assertEquals(Collections.emptyMap(), repository().getByName(n("orders")).getProperties());
  }

  @Test
  public void missingRecordsAreNotFound() {
    assertThrows(NotFoundException.class, () -> repository().getByName(n("nothing")));
    assertThrows(NotFoundException.class, () -> repository().delete(n("nothing")));
    assertThrows(
        NotFoundException.class,
        () ->
            repository()
                .update(
                    n("nothing"),
                    new NamespaceMetadata("uuid", n("nothing"), Collections.emptyMap(), "0")));
  }

  @Test
  public void replaceIsConditionalOnUuidAndRevision() throws Exception {
    NamespaceMetadata created = repository().create(n("ns"), Collections.singletonMap("a", "1"));
    NamespaceMetadata replaced =
        repository()
            .update(
                n("NS"),
                new NamespaceMetadata(
                    created.getNamespaceUuid(), n("ns"), Collections.singletonMap("b", "2"), "0"));
    assertEquals("1", replaced.getRevision());
    assertEquals(Collections.singletonMap("b", "2"), replaced.getProperties());
    assertEquals(created.getNamespaceUuid(), replaced.getNamespaceUuid());
    assertEquals(replaced, repository().getByName(n("ns")));

    // The revision read before the replace is stale now.
    assertThrows(
        EntityConcurrentModificationException.class,
        () ->
            repository()
                .update(
                    n("ns"),
                    new NamespaceMetadata(
                        created.getNamespaceUuid(),
                        n("ns"),
                        Collections.singletonMap("c", "3"),
                        "0")));
    // A document of another record with the same name (dropped and created again) is stale too.
    assertThrows(
        EntityConcurrentModificationException.class,
        () ->
            repository()
                .update(
                    n("ns"),
                    new NamespaceMetadata(
                        "another-uuid", n("ns"), Collections.singletonMap("c", "3"), "1")));
    assertEquals(replaced, repository().getByName(n("ns")));
  }

  @Test
  public void anUnchangedDocumentStillMovesTheRevision() throws Exception {
    NamespaceMetadata created = repository().create(n("ns"), Collections.singletonMap("a", "1"));
    NamespaceMetadata replaced = repository().update(n("ns"), created);
    assertEquals("1", replaced.getRevision());
    assertEquals(created.getProperties(), replaced.getProperties());
  }

  @Test
  public void aDocumentWithAnotherSpellingOrNoPropertiesIsRefusedAndChangesNothing()
      throws Exception {
    NamespaceMetadata created = repository().create(n("Ns"), Collections.singletonMap("a", "1"));
    assertThrows(
        WebClientResponseException.BadRequest.class,
        () ->
            repository()
                .update(
                    n("ns"),
                    new NamespaceMetadata(
                        created.getNamespaceUuid(), n("ns"), Collections.emptyMap(), "0")));
    assertThrows(
        WebClientResponseException.BadRequest.class,
        () ->
            repository()
                .update(
                    n("ns"),
                    new NamespaceMetadata(created.getNamespaceUuid(), n("Ns"), null, "0")));
    assertEquals(created, repository().getByName(n("ns")));
  }

  @Test
  public void deleteRemovesTheRecordAndACreateAfterItIsANewRecord() throws Exception {
    NamespaceMetadata first = repository().create(n("ns"), Collections.singletonMap("a", "1"));
    repository().delete(n("NS"));
    assertThrows(NotFoundException.class, () -> repository().getByName(n("ns")));
    NamespaceMetadata second = repository().create(n("NS"), Collections.emptyMap());
    assertNotEquals(first.getNamespaceUuid(), second.getNamespaceUuid());
    assertEquals(n("NS"), second.getNamespaceName());
    assertEquals("0", second.getRevision());
  }

  @Test
  public void storedPropertiesAreCopiesAndAnswersCannotBeChanged() throws Exception {
    Map<String, String> properties = new HashMap<>(Collections.singletonMap("a", "1"));
    NamespaceMetadata created = repository().create(n("ns"), properties);
    properties.put("b", "2");
    assertEquals(
        Collections.singletonMap("a", "1"), repository().getByName(n("ns")).getProperties());
    assertThrows(UnsupportedOperationException.class, () -> created.getProperties().put("c", "3"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> repository().getByName(n("ns")).getProperties().put("c", "3"));
  }
}
