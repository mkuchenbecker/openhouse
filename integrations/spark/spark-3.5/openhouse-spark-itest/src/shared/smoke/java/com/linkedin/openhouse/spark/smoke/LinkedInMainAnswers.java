package com.linkedin.openhouse.spark.smoke;

import com.linkedin.openhouse.spark.smoke.LinkedInMainComparison.MainDifference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

/**
 * Compares what the tests of a class observe with what they observed on LinkedIn main, for test
 * classes whose expectations are written in their source ({@code CatalogPluginContractTest}): each
 * answer a test passes to {@link #answer} (an exception's class, status and message, a value) and
 * whether the test passed are recorded per test as {@code <Class>.<method> <what>} in {@code
 * linkedin-main/<suite>.tsv} ({@link LinkedInMainRecording}). A test whose answers are not main's,
 * or that passes here where it failed on main (so its expectation is not main's behavior), fails
 * unless the difference is declared; a declared difference must still differ.
 *
 * <p>A class opts in with {@code @RegisterExtension}, naming its suite and its declared
 * differences. Answers given by a test of another class (a subclass that runs the same tests on
 * another path) are ignored unless that class registers its own.
 */
public final class LinkedInMainAnswers implements InvocationInterceptor, AfterAllCallback {

  private static final ThreadLocal<Map<String, List<String>>> CURRENT = new ThreadLocal<>();

  /** One recorder for every class of a suite when the run records: they share one file. */
  private static final Map<String, LinkedInMainComparison> RECORDERS = new LinkedHashMap<>();

  private final String suite;
  private final String path;
  private final Class<?> owner;
  private final Supplier<List<MainDifference>> differences;
  private final Map<String, String> absent;
  private LinkedInMainComparison comparison;

  /**
   * Compares the tests of {@code owner} itself (not of its subclasses) with {@code suite}'s
   * recording; {@code path} says, in a recording's header, what the tests ran over; {@code absent}
   * names, with the reason, tests recorded on main that the class no longer runs.
   */
  public LinkedInMainAnswers(
      String suite,
      String path,
      Class<?> owner,
      Supplier<List<MainDifference>> differences,
      Map<String, String> absent) {
    this.absent = absent;
    this.suite = suite;
    this.path = path;
    this.owner = owner;
    this.differences = differences;
  }

  /**
   * What a test observed: {@code what} names the observation within the test (say {@code thrown
   * #2}); several answers to one name are kept as a sorted list.
   */
  public static void answer(String what, String outcome) {
    Map<String, List<String>> answers = CURRENT.get();
    if (answers != null) {
      answers
          .computeIfAbsent(
              LinkedInMainRecording.normalizeValues(what),
              k -> Collections.synchronizedList(new ArrayList<>()))
          .add(outcome);
    }
  }

  /**
   * What a test observed, numbered in the order the test observes it: {@code what #1}, {@code what
   * #2} and so on.
   */
  public static void answerInOrder(String what, String outcome) {
    Map<String, List<String>> answers = CURRENT.get();
    if (answers != null) {
      int number = 1;
      while (answers.containsKey(what + " #" + number)) {
        number++;
      }
      answer(what + " #" + number, outcome);
    }
  }

  /** What a failure was: its class and message, as an answer records it. */
  public static String failure(Throwable t) {
    return t.getClass().getName() + ": " + t.getMessage();
  }

  private synchronized LinkedInMainComparison comparison() throws Exception {
    if (comparison == null) {
      if (LinkedInMainRecording.recordDirectory() != null) {
        synchronized (RECORDERS) {
          if (!RECORDERS.containsKey(suite)) {
            RECORDERS.put(suite, new LinkedInMainComparison(suite, Collections.emptyList()));
          }
          comparison = RECORDERS.get(suite);
        }
      } else {
        comparison = new LinkedInMainComparison(suite, differences.get());
      }
    }
    return comparison;
  }

  @Override
  public void interceptTestMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    if (extensionContext.getRequiredTestClass() != owner) {
      invocation.proceed();
      return;
    }
    String test = owner.getSimpleName() + "." + invocationContext.getExecutable().getName();
    Map<String, List<String>> answers = Collections.synchronizedMap(new LinkedHashMap<>());
    CURRENT.set(answers);
    Throwable failed = null;
    try {
      invocation.proceed();
    } catch (Throwable t) {
      failed = t;
    } finally {
      CURRENT.remove();
    }
    LinkedInMainComparison main = comparison();
    synchronized (answers) {
      for (Map.Entry<String, List<String>> answer : answers.entrySet()) {
        main.observe(
            test, test + " " + answer.getKey(), LinkedInMainRecording.answers(answer.getValue()));
      }
    }
    main.observe(
        test,
        test + " verdict",
        failed == null ? "passes" : "fails: " + failure(failed).split("\n", 2)[0]);
    List<String> problems = main.endTest();
    if (failed != null) {
      if (!problems.isEmpty()) {
        failed.addSuppressed(
            new AssertionError("answers not LinkedIn main's:\n" + String.join("\n", problems)));
      }
      throw failed;
    }
    if (!problems.isEmpty()) {
      Assertions.fail(
          problems.size() + " answer(s) not LinkedIn main's:\n" + String.join("\n", problems));
    }
  }

  @Override
  public void afterAll(ExtensionContext context) throws Exception {
    if (context.getRequiredTestClass() != owner || comparison == null) {
      return;
    }
    List<String> wrong =
        new ArrayList<>(comparison.endSuite(LinkedInMainRecording.header(path), null));
    wrong.addAll(
        comparison.recordedTestsNotDeclared(
            LinkedInMainComparison.enabledTests(owner, m -> owner.getSimpleName() + "." + m),
            owner.getSimpleName() + ".",
            absent));
    Assertions.assertEquals(Collections.emptyList(), wrong);
  }
}
