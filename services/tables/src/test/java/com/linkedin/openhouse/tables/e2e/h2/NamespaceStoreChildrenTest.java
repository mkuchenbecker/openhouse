package com.linkedin.openhouse.tables.e2e.h2;

import static org.assertj.core.api.Assertions.assertThat;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.model.HouseNamespace;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

/**
 * {@code childrenOf} against the JPA-backed namespace store, which is a second implementation of
 * the same contract and expresses the subtree as a real SQL range rather than in Java.
 *
 * <p>At the shipped namespace depth of 1 nothing can be a child of anything, so this method has no
 * production callers yet. It is pinned here directly rather than left to the slice that turns
 * nesting on, because the range being right is the part that is hard to notice being wrong.
 */
@SpringBootTest(classes = SpringH2Application.class)
@ContextConfiguration(initializers = PropertyOverrideContextInitializer.class)
public class NamespaceStoreChildrenTest {

  @Autowired HouseNamespaceRepository houseNamespaceRepository;

  @AfterEach
  public void tearDown() {
    houseNamespaceRepository.deleteAll();
  }

  /**
   * Calibration: dropping the {@code isDirectChild} filter adds {@code a.b.c} to the first
   * assertion; replacing the range's upper bound with the end of the key space adds {@code ab} and
   * {@code b}; making the lower bound inclusive of the parent itself adds {@code a}.
   */
  @Test
  public void childrenOfReturnsDirectChildrenOnly() {
    for (String namespaceId :
        new String[] {"a", "a.b", "a.c", "a.b.c", "ab", "ab.d", "b", "a_x", "a_x.y"}) {
      store(namespaceId);
    }

    assertThat(encodedChildrenOf("a")).containsExactly("a.b", "a.c");
    assertThat(encodedChildrenOf("a.b")).containsExactly("a.b.c");
    assertThat(encodedChildrenOf("a.c")).isEmpty();
    assertThat(encodedChildrenOf("b")).isEmpty();
  }

  /**
   * SQL {@code LIKE} reads {@code _} as a single-character wildcard and the identifier charset
   * admits {@code _}, so a prefix pattern would answer this with {@code myXdb.child}. A range does
   * not, which is why the store expresses the subtree as one.
   */
  @Test
  public void anUnderscoreInTheParentNameIsALiteral() {
    store("my_db");
    store("my_db.child");
    store("myXdb");
    store("myXdb.child");

    assertThat(encodedChildrenOf("my_db")).containsExactly("my_db.child");
  }

  /** A parent with no row has no children, rather than an error the caller has to distinguish. */
  @Test
  public void childrenOfAnAbsentParentIsEmpty() {
    store("something_else");
    assertThat(encodedChildrenOf("nothing_here")).isEmpty();
  }

  private java.util.List<String> encodedChildrenOf(String encodedParent) {
    return houseNamespaceRepository.childrenOf(encodedParent).stream()
        .map(HouseNamespace::getNamespaceId)
        .sorted()
        .collect(Collectors.toList());
  }

  private void store(String namespaceId) {
    houseNamespaceRepository.save(
        HouseNamespace.builder()
            .namespaceId(namespaceId)
            .properties(new LinkedHashMap<>())
            .creationTime(1L)
            .lastModifiedTime(1L)
            .build());
  }
}
