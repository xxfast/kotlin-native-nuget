package io.github.xxfast.kotlin.native.nuget.processor.tier1

import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ReflectiveInvocationContext
import org.opentest4j.TestAbortedException
import java.lang.reflect.Method

/**
 * ADR-060 Decision, "Strict xfail: how red assertions coexist with a green `verify.sh`". [reason]
 * must name the ADR-060 cell / ROADMAP item this pins. The annotated test body states the
 * **correct** expected behaviour, never the buggy one, so the assertion needs no edit when the
 * fix lands — the fix's commit deletes this one annotation and changes nothing else; the diff
 * *is* the evidence.
 *
 * Strict: an unexpected PASS fails the build (see [XFailExtension]), so a fix cannot land while
 * leaving its marker behind — the `@XFail` list stays a live, build-enforced inventory of the
 * forward direction's real capability ceiling rather than a hand-maintained one that rots.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(XFailExtension::class)
annotation class XFail(val reason: String)

/**
 * [InvocationInterceptor] backing [XFail]. Runs the test body: an [AssertionError] is this cell's
 * defect, confirmed still present through the real processor, so it is converted to
 * [TestAbortedException] — JUnit 5 reports that as *aborted* (green CI, listed as
 * skipped-with-reason). Anything else, including the body completing with **no** exception at
 * all, means the defect looks fixed without anyone deleting the marker, which must fail the
 * build rather than go quietly green.
 */
internal class XFailExtension : InvocationInterceptor {
  override fun interceptTestMethod(
    invocation: InvocationInterceptor.Invocation<Void>,
    invocationContext: ReflectiveInvocationContext<Method>,
    extensionContext: ExtensionContext,
  ) {
    val marker: XFail? = invocationContext.executable.getAnnotation(XFail::class.java)
    if (marker == null) {
      invocation.proceed()
      return
    }

    val stillFailing: AssertionError? = try {
      invocation.proceed()
      null
    } catch (failure: AssertionError) {
      failure
    }

    if (stillFailing == null) {
      throw AssertionError(
        "@XFail(\"${marker.reason}\") on ${invocationContext.executable} unexpectedly PASSED. " +
            "The defect this test pins appears to be fixed — remove this @XFail annotation " +
            "deliberately, do not leave the assertion silently green underneath it."
      )
    }

    throw TestAbortedException(
      "ADR-060 known-failing (${marker.reason}): ${stillFailing.message}",
      stillFailing,
    )
  }
}
