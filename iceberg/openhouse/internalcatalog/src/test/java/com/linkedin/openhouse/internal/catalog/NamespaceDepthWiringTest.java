package com.linkedin.openhouse.internal.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.utils.NamespaceUtil;
import java.lang.reflect.Field;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The catalog's namespace depth comes from {@code cluster.tables.namespace.max-depth} rather than
 * from a constant compiled into {@link NamespaceUtil}.
 *
 * <p>Deliberately unit-level. A Spring context with the property raised cannot boot — {@code
 * NamespacesServiceImpl.rejectUnimplementedNamespaceDepth} refuses at startup, and it is meant to,
 * because the remaining seams for nesting do not exist yet. This asserts the wiring under that
 * guard rather than around it.
 */
public class NamespaceDepthWiringTest {

  /**
   * A directly-constructed catalog behaves as it always has. Spring overwrites the field; anything
   * that does not go through Spring must not silently get a depth of zero, which is what an
   * uninitialised int would give and which would make every identifier invalid.
   */
  @Test
  public void theDepthDefaultsToTheShippedValueWithoutSpring() {
    assertThat(new OpenHouseInternalCatalog().maxNamespaceDepth)
        .isEqualTo(NamespaceUtil.DEFAULT_MAX_NAMESPACE_DEPTH)
        .isEqualTo(1);
  }

  /**
   * The placeholder names the same property {@link ClusterProperties} reads, with the same default.
   * A typo here would bind nothing, fall back to the default and look exactly like success.
   */
  @Test
  public void thePlaceholderNamesTheClusterProperty() throws NoSuchFieldException {
    Field catalogField = OpenHouseInternalCatalog.class.getDeclaredField("maxNamespaceDepth");
    Field clusterField = ClusterProperties.class.getDeclaredField("clusterTablesNamespaceMaxDepth");
    assertThat(catalogField.getAnnotation(Value.class).value())
        .isEqualTo(clusterField.getAnnotation(Value.class).value())
        .isEqualTo("${cluster.tables.namespace.max-depth:1}");
  }

  /** At the shipped depth, every answer is the one the hardcoded constant used to give. */
  @Test
  public void atTheShippedDepthTheCatalogAnswersExactlyAsBefore() {
    OpenHouseInternalCatalog catalog = catalogAtDepth(1);
    assertThat(catalog.isValidIdentifier(TableIdentifier.of("db", "t"))).isTrue();
    assertThat(catalog.isValidIdentifier(TableIdentifier.of(Namespace.of("a", "b"), "t")))
        .isFalse();
    assertThat(catalog.isValidIdentifier(TableIdentifier.of(Namespace.empty(), "t"))).isFalse();
    assertThat(catalog.isValidIdentifier(null)).isFalse();

    assertThatThrownBy(() -> catalog.listHouseTables(Namespace.of("a", "b"), null))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Input namespace has more than one levels a.b");
    assertThatThrownBy(() -> catalog.listHouseTables(Namespace.of("db"), null))
        .isInstanceOf(NullPointerException.class);
  }

  /**
   * Raising the property moves both predicates. This is the whole point of the wiring, and the
   * assertion that would go green on its own if the constant were still hardcoded.
   */
  @Test
  public void raisingTheDepthMovesBothPredicates() {
    OpenHouseInternalCatalog catalog = catalogAtDepth(2);
    assertThat(catalog.isValidIdentifier(TableIdentifier.of(Namespace.of("a", "b"), "t"))).isTrue();
    assertThat(catalog.isValidIdentifier(TableIdentifier.of("db", "t"))).isFalse();

    assertThatThrownBy(() -> catalog.listHouseTables(Namespace.of("a", "b", "c"), null))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> catalog.listHouseTables(Namespace.of("a", "b"), null))
        .isInstanceOf(NullPointerException.class);
  }

  /**
   * The catalog is exercised without its collaborators. The depth check runs before anything is
   * dereferenced, so a rejected namespace throws {@link ValidationException} and an accepted one
   * gets as far as the unset repository and throws {@link NullPointerException} — which is how
   * "accepted" is told apart from "rejected" on the listing paths above without stubbing HTS.
   */
  private static OpenHouseInternalCatalog catalogAtDepth(int depth) {
    OpenHouseInternalCatalog catalog = new OpenHouseInternalCatalog();
    ReflectionTestUtils.setField(catalog, "maxNamespaceDepth", depth);
    return catalog;
  }
}
