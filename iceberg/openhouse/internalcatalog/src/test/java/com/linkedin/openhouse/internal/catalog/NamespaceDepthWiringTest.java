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
import org.springframework.mock.env.MockEnvironment;
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

  /**
   * The placeholder resolves against a real Spring {@link org.springframework.core.env.Environment}
   * — the property name is spelled correctly, the default syntax is well formed, and a set value
   * wins. String equality with {@link ClusterProperties} alone would not catch a placeholder that
   * both fields spell wrong in the same way.
   */
  @Test
  public void thePlaceholderResolvesAgainstARealEnvironment() throws NoSuchFieldException {
    String placeholder =
        OpenHouseInternalCatalog.class
            .getDeclaredField("maxNamespaceDepth")
            .getAnnotation(Value.class)
            .value();

    MockEnvironment unset = new MockEnvironment();
    assertThat(unset.resolveRequiredPlaceholders(placeholder))
        .isEqualTo(NamespaceUtil.DEFAULT_MAX_NAMESPACE_DEPTH_LITERAL);

    MockEnvironment raised = new MockEnvironment();
    raised.setProperty("cluster.tables.namespace.max-depth", "3");
    assertThat(raised.resolveRequiredPlaceholders(placeholder)).isEqualTo("3");
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
   * Raising the property widens both predicates. This is the whole point of the wiring, and the
   * assertion that would go green on its own if the constant were still hardcoded.
   *
   * <p>{@code max-depth} is a ceiling, not a required depth: at 2 a table may live in a one-level
   * namespace or a two-level one, and only three levels is out of range. The one-level assertion
   * below used to expect {@code false}, on the reading that a table lives at the deepest level the
   * catalog admits. That reading is not Iceberg's — a namespace may be any depth and a table lives
   * in whichever namespace holds it — and it made raising a cluster's max-depth a catalog-wide
   * breakage, because every database that already existed is one level and would have stopped being
   * able to host tables the moment the property moved. The empty namespace stays out: there is no
   * database under which to place the table.
   */
  @Test
  public void raisingTheDepthWidensBothPredicatesToARange() {
    OpenHouseInternalCatalog catalog = catalogAtDepth(2);
    assertThat(catalog.isValidIdentifier(TableIdentifier.of(Namespace.of("a", "b"), "t"))).isTrue();
    assertThat(catalog.isValidIdentifier(TableIdentifier.of("db", "t"))).isTrue();
    assertThat(catalog.isValidIdentifier(TableIdentifier.of(Namespace.of("a", "b", "c"), "t")))
        .isFalse();
    assertThat(catalog.isValidIdentifier(TableIdentifier.of(Namespace.empty(), "t"))).isFalse();

    assertThatThrownBy(() -> catalog.listHouseTables(Namespace.of("a", "b", "c"), null))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> catalog.listHouseTables(Namespace.of("a", "b"), null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> catalog.listHouseTables(Namespace.of("db"), null))
        .isInstanceOf(NullPointerException.class);
  }

  /**
   * The range holds with room to spare above and below. At depth 3 there are two admitted depths
   * under the ceiling, which is where an "exactly the configured depth" rule stops being merely
   * restrictive and starts making namespaces unaddressable: a table in {@code a.b} could not be
   * named at all on a cluster configured for three.
   */
  @Test
  public void everyDepthUpToTheMaximumHostsTables() {
    OpenHouseInternalCatalog catalog = catalogAtDepth(3);
    assertThat(catalog.isValidIdentifier(TableIdentifier.of("db", "t"))).isTrue();
    assertThat(catalog.isValidIdentifier(TableIdentifier.of(Namespace.of("a", "b"), "t"))).isTrue();
    assertThat(catalog.isValidIdentifier(TableIdentifier.of(Namespace.of("a", "b", "c"), "t")))
        .isTrue();
    assertThat(catalog.isValidIdentifier(TableIdentifier.of(Namespace.of("a", "b", "c", "d"), "t")))
        .isFalse();
  }

  /**
   * Widening the base-table range does not widen it over identifiers Iceberg reads as metadata
   * tables. At depth 2 the two clauses of {@code isValidIdentifier} now overlap — {@code db.tbl} is
   * both a legal table namespace and deep enough for the discriminator — and the discriminator is
   * what decides. Without it, raising the depth would silently change what {@code db.tbl.history}
   * resolves to.
   */
  @Test
  public void theMetadataTableDiscriminatorStillWinsInsideTheRange() {
    OpenHouseInternalCatalog catalog = catalogAtDepth(2);
    assertThat(catalog.isValidIdentifier(TableIdentifier.of(Namespace.of("db", "tbl"), "history")))
        .isFalse();
    assertThat(catalog.isValidIdentifier(TableIdentifier.of(Namespace.of("db", "tbl"), "t")))
        .isTrue();
    // One level up it is a base table called "history", as it has always been.
    assertThat(catalog.isValidIdentifier(TableIdentifier.of("db", "history"))).isTrue();
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
