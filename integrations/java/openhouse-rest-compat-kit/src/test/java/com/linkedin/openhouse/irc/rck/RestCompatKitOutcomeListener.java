package com.linkedin.openhouse.irc.rck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Writes the outcome of every kit test, with the cause of each test that does not pass, for the
 * build's comparison with {@code expected-outcomes.txt}: one JSON object per line in the file the
 * system property {@code rck.outcomes.file} names, written when the test plan finishes.
 *
 * <p>Each object has {@code name} (the test's class simple name and its name as Gradle reports it,
 * {@code <method>(<parameter types>)} and {@code [<invocation>]} for a parameterized one), {@code
 * outcome} ({@code PASSED}, {@code FAILED} or {@code SKIPPED}), and for a test that does not pass
 * {@code type} and {@code message}: the class and message of the throwable that ended the test (its
 * first failing assertion, or the exception the client threw), or for a skip the kit's assumption
 * that did not hold, or {@code disabled} and the reason of a test the kit disabled. A failure of a
 * class or of the plan itself (a container) is written with {@code container: true}.
 *
 * <p>Gradle's own test events say only that a test failed or was skipped, not why; the build needs
 * the why to tell a listed failure from one that fails for another reason.
 */
public final class RestCompatKitOutcomeListener implements TestExecutionListener {

  static final String OUTCOMES_FILE_PROPERTY = "rck.outcomes.file";

  private final ObjectMapper mapper = new ObjectMapper();
  private final List<ObjectNode> outcomes = new ArrayList<>();
  private TestPlan plan;

  @Override
  public void testPlanExecutionStarted(TestPlan testPlan) {
    plan = testPlan;
  }

  @Override
  public void executionSkipped(TestIdentifier test, String reason) {
    if (!test.isTest()) {
      // A skipped class skips its tests without an event for each: their absence is the signal.
      outcomes.add(container(test, "SKIPPED", "disabled", reason));
      return;
    }
    outcomes.add(outcome(test, "SKIPPED", "disabled", reason));
  }

  @Override
  public void executionFinished(TestIdentifier test, TestExecutionResult result) {
    Optional<Throwable> thrown = result.getThrowable();
    String type = thrown.map(t -> t.getClass().getName()).orElse(null);
    String message = thrown.map(t -> String.valueOf(t.getMessage())).orElse(null);
    String outcome;
    switch (result.getStatus()) {
      case SUCCESSFUL:
        outcome = "PASSED";
        break;
      case ABORTED:
        outcome = "SKIPPED";
        break;
      default:
        outcome = "FAILED";
        break;
    }
    if (test.isTest()) {
      outcomes.add(outcome(test, outcome, type, message));
    } else if (!"PASSED".equals(outcome)) {
      outcomes.add(container(test, outcome, type, message));
    }
  }

  @Override
  public void testPlanExecutionFinished(TestPlan testPlan) {
    String file = System.getProperty(OUTCOMES_FILE_PROPERTY);
    if (file == null) {
      // Not the kit's test task (an IDE run): nothing to compare with.
      return;
    }
    Path path = Paths.get(file);
    try {
      Files.createDirectories(path.toAbsolutePath().getParent());
      try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
        for (ObjectNode node : outcomes) {
          writer.write(mapper.writeValueAsString(node));
          writer.write('\n');
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Could not write the kit's outcomes to " + path, e);
    }
  }

  private ObjectNode outcome(TestIdentifier test, String outcome, String type, String message) {
    ObjectNode node = mapper.createObjectNode();
    node.put("name", name(test));
    node.put("outcome", outcome);
    if (type != null) {
      node.put("type", type);
      node.put("message", message);
    }
    return node;
  }

  private ObjectNode container(TestIdentifier test, String outcome, String type, String message) {
    ObjectNode node = outcome(test, outcome, type, message);
    node.put("name", test.getUniqueId());
    node.put("container", true);
    return node;
  }

  /**
   * The name Gradle gives the test: {@code <class simple name>.<method>(<parameter simple types>)},
   * and {@code [<n>]} after it for the n-th invocation of a parameterized test.
   */
  private String name(TestIdentifier test) {
    Optional<TestSource> source = test.getSource();
    if (!source.isPresent() || !(source.get() instanceof MethodSource)) {
      return test.getUniqueId();
    }
    MethodSource method = (MethodSource) source.get();
    String className = method.getClassName();
    // An inherited test's source names the method's declaring class; the test class is the
    // nearest ancestor with a class source.
    Optional<TestIdentifier> parent = plan == null ? Optional.empty() : plan.getParent(test);
    while (parent.isPresent()) {
      Optional<TestSource> parentSource = parent.get().getSource();
      if (parentSource.isPresent() && parentSource.get() instanceof ClassSource) {
        className = ((ClassSource) parentSource.get()).getClassName();
        break;
      }
      parent = plan.getParent(parent.get());
    }
    String parameters =
        Stream.of(method.getMethodParameterTypes().split(","))
            .map(String::trim)
            .filter(p -> !p.isEmpty())
            .map(p -> p.substring(p.lastIndexOf('.') + 1))
            .collect(Collectors.joining(", "));
    StringBuilder name =
        new StringBuilder()
            .append(className.substring(className.lastIndexOf('.') + 1))
            .append('.')
            .append(method.getMethodName())
            .append('(')
            .append(parameters)
            .append(')');
    List<UniqueId.Segment> segments = UniqueId.parse(test.getUniqueId()).getSegments();
    UniqueId.Segment last = segments.get(segments.size() - 1);
    if ("test-template-invocation".equals(last.getType())) {
      name.append('[').append(last.getValue().replace("#", "")).append(']');
    }
    return name.toString();
  }
}
