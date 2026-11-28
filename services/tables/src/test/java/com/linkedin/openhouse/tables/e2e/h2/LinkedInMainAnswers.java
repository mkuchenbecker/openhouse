package com.linkedin.openhouse.tables.e2e.h2;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

/**
 * Compares what the commit-path tests observe over HTTP with what they observed on LinkedIn main
 * ({@code linkedin-main/commit-path.tsv}): every answer a test gets through {@link CommitPathHttp}
 * (status, and the message of an error or the body of a read, see {@link #answer}), and whether the
 * test passed, per test as {@code <Class>.<method> <request>}. Several answers to one request in a
 * test are kept as a sorted list, so concurrent requests compare whatever order they ran in. A test
 * whose answers are not main's, or that passes here where it failed on main (so its expectation is
 * not main's behavior), fails unless {@link CommitPathLinkedInMainDifferences} declares the
 * difference with its cause; a declared difference must still differ.
 *
 * <p>The Spark 3.5 itest has the same extension for the catalog plugin contract; this module cannot
 * see it.
 */
public final class LinkedInMainAnswers implements InvocationInterceptor, AfterAllCallback {

  /** The suite every commit-path class belongs to; they share one recording. */
  static final String SUITE = "commit-path";

  private static final ThreadLocal<Map<String, List<String>>> CURRENT =
      new InheritableThreadLocal<>();

  /** One recorder for every class when the run records: they share one file. */
  private static LinkedInMainComparison recorder;

  private LinkedInMainComparison comparison;

  /** Set on the thread that runs the test method; requests from other threads are concurrent. */
  private static final ThreadLocal<Boolean> TEST_THREAD = new ThreadLocal<>();

  /**
   * What a test observed: several answers to one {@code what} are kept as a sorted list. An answer
   * to a request a test sends from another thread (a race it starts) keeps only its status: which
   * of two refusals a losing request gets depends on the threads' timing, on main as here.
   */
  static void answer(String what, String outcome) {
    Map<String, List<String>> answers = CURRENT.get();
    if (answers != null) {
      if (TEST_THREAD.get() == null) {
        outcome = outcome.split(" ", 2)[0] + " (sent from another thread: status only)";
      }
      synchronized (answers) {
        answers
            .computeIfAbsent(LinkedInMainRecording.normalizeValues(what), k -> new ArrayList<>())
            .add(outcome);
      }
    }
  }

  private synchronized LinkedInMainComparison comparison() throws Exception {
    if (comparison == null) {
      if (LinkedInMainRecording.recordDirectory() != null) {
        synchronized (LinkedInMainAnswers.class) {
          if (recorder == null) {
            recorder = new LinkedInMainComparison(SUITE, Collections.emptyList());
          }
          comparison = recorder;
        }
      } else {
        comparison =
            new LinkedInMainComparison(SUITE, CommitPathLinkedInMainDifferences.differences());
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
    String test =
        extensionContext.getRequiredTestClass().getSimpleName()
            + "."
            + invocationContext.getExecutable().getName();
    Map<String, List<String>> answers = new LinkedHashMap<>();
    CURRENT.set(answers);
    TEST_THREAD.set(Boolean.TRUE);
    Throwable failed = null;
    try {
      invocation.proceed();
    } catch (Throwable t) {
      failed = t;
    } finally {
      CURRENT.remove();
      TEST_THREAD.remove();
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
        failed == null
            ? "passes"
            : "fails: "
                + (failed.getClass().getName() + ": " + failed.getMessage()).split("\n", 2)[0]);
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
    if (comparison == null) {
      return;
    }
    List<String> wrong =
        new ArrayList<>(
            comparison.endSuite(
                LinkedInMainRecording.header("the native tables API over MockMvc"), null));
    Class<?> testClass = context.getRequiredTestClass();
    wrong.addAll(
        comparison.recordedTestsNotDeclared(
            LinkedInMainComparison.enabledTests(
                testClass, m -> testClass.getSimpleName() + "." + m),
            testClass.getSimpleName() + ".",
            CommitPathLinkedInMainDifferences.absentTests()));
    Assertions.assertEquals(Collections.emptyList(), wrong);
  }
}
