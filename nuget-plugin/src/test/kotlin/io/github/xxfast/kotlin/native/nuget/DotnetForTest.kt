package io.github.xxfast.kotlin.native.nuget

import org.junit.jupiter.api.Assumptions
import kotlin.test.fail

/**
 * The `dotnet` command for a test that needs the .NET SDK. Under CI (GitHub sets `CI=true` on
 * every runner) a missing SDK fails the test, so a leg that never runs these tests cannot look
 * green. Locally it aborts the test through JUnit's assumptions, which reports it as skipped
 * rather than passed.
 */
internal fun dotnetForTest(
  dotnet: String? = RealPackageFixture.findDotnet(),
  ci: String? = System.getenv("CI"),
): String {
  if (dotnet != null) return dotnet
  if (!ci.isNullOrEmpty()) {
    fail("this test needs the .NET SDK, and `dotnet` is not on PATH while CI is set")
  }
  Assumptions.abort<Unit>("requires the .NET SDK (`dotnet` on PATH)")
  error("unreachable: Assumptions.abort always throws")
}
