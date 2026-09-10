package io.github.xxfast.kotlin.native.nuget.processor

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallableCatalogEntry
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPlanSkipReason
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardSkipPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForwardSkippedCallableWarningTest {
  private class RecordingLogger : KSPLogger {
    val warnings: MutableList<String> = mutableListOf()
    override fun logging(message: String, symbol: KSNode?) = Unit
    override fun info(message: String, symbol: KSNode?) = Unit
    override fun warn(message: String, symbol: KSNode?) {
      warnings.add(message)
    }

    override fun error(message: String, symbol: KSNode?) = Unit
    override fun exception(e: Throwable) = Unit
  }

  @Test
  fun `a dropped skip warns with the symbol and reason`() {
    val logger = RecordingLogger()
    val catalog = ForwardCallablePlanCatalog(
      entries = listOf(
        ForwardCallableCatalogEntry.Skipped(
          symbol = "com.example.Api.consume",
          reason = ForwardPlanSkipReason.COLLECTION,
        ),
      ),
    )

    warnDroppedForwardCallables(catalog, logger)

    assertEquals(1, logger.warnings.size)
    val warning: String = logger.warnings.single()
    assertTrue(warning.contains("com.example.Api.consume"), "warning names the symbol: $warning")
    assertTrue(warning.contains("COLLECTION"), "warning names the reason: $warning")
  }

  // ROADMAP Phase 3 / ADR-064's 2026-09-10 amendment: this reason is "out of scope", not
  // "unsupported", and its own hint already says "skipped by design". The generic sentence
  // contradicted both.
  @Test
  fun `an excluded dependency type reads as excluded, not as unsupported`() {
    val logger = RecordingLogger()
    val catalog = ForwardCallablePlanCatalog(
      entries = listOf(
        ForwardCallableCatalogEntry.Skipped(
          symbol = "com.example.Api.sponsor",
          reason = ForwardPlanSkipReason.EXCLUDED_DEPENDENCY_TYPE,
          detail = "dep.models.TopStory",
        ),
      ),
    )

    warnDroppedForwardCallables(catalog, logger, scope = listOf("com.example"))

    val warning: String = logger.warnings.single()
    assertTrue(
      warning.contains(
        "its type `dep.models.TopStory` is excluded from the export scope by your own exclude(...)",
      ),
      "warning says the type is out of scope: $warning",
    )
    assertFalse(
      warning.contains("type combination is not supported"),
      "warning does not also call it unsupported: $warning",
    )
  }

  // The sentences that moved from the `if` chain onto `diagnosticReason()`, pinned verbatim:
  // the move is a refactor, so every one of these is byte-identical to its pre-refactor text.
  @Test
  fun `every reason that owns a sentence keeps its shipped wording`() {
    val expected: List<Pair<ForwardCallableCatalogEntry.Skipped, String>> = listOf(
      ForwardCallableCatalogEntry.Skipped(
        symbol = "com.example.Api.preview",
        reason = ForwardPlanSkipReason.OPT_IN_MARKER,
        detail = "com.example.Experimental",
      ) to "it is marked with the opt-in marker `com.example.Experimental`",
      ForwardCallableCatalogEntry.Skipped(
        symbol = "com.example.Api.draft",
        reason = ForwardPlanSkipReason.OPT_IN_MARKER_TYPE,
        detail = "com.example.Draft->com.example.Experimental",
      ) to "its type `com.example.Draft` is marked with an opt-in marker",
      ForwardCallableCatalogEntry.Skipped(
        symbol = "com.example.Shape.Circle.observe",
        reason = ForwardPlanSkipReason.SEALED_SUBCLASS_UNROUTED,
        detail = "suspend",
      ) to "it is a suspend member of a sealed subclass, which has no route yet (ADR-116)",
      ForwardCallableCatalogEntry.Skipped(
        symbol = "com.example.Shape.observe",
        reason = ForwardPlanSkipReason.SEALED_BASE_UNROUTED,
        detail = "suspend",
      ) to "it is a suspend member of a sealed base class, which has no route yet (ADR-116)",
      ForwardCallableCatalogEntry.Skipped(
        symbol = "com.example.Api.rename",
        reason = ForwardPlanSkipReason.NULLABLE,
        position = ForwardSkipPosition.INPUT,
        parameter = "label",
      ) to "its parameter `label` has a nullable type with no supported wire",
      ForwardCallableCatalogEntry.Skipped(
        symbol = "com.example.Api.consume",
        reason = ForwardPlanSkipReason.COLLECTION,
      ) to "its COLLECTION type combination is not supported",
      // A nullable skip with no parameter name (a return position) keeps the generic sentence.
      ForwardCallableCatalogEntry.Skipped(
        symbol = "com.example.Api.find",
        reason = ForwardPlanSkipReason.NULLABLE,
      ) to "its NULLABLE type combination is not supported",
    )

    val logger = RecordingLogger()
    warnDroppedForwardCallables(
      ForwardCallablePlanCatalog(entries = expected.map { (skipped, _) -> skipped }),
      logger,
    )

    assertEquals(expected.size, logger.warnings.size, "one warning per skip: ${logger.warnings}")
    expected.forEachIndexed { index, (skipped, sentence) ->
      assertTrue(
        logger.warnings[index].contains(sentence),
        "${skipped.symbol} keeps its shipped wording: ${logger.warnings[index]}",
      )
    }
  }

  // ROADMAP Phase 3 / ADR-064's 2026-09-11 amendment: eleven reasons that are about scope,
  // position or nesting, not about an unsupported type combination. Each now owns a sentence that
  // agrees with the hint following it. The reason constant is kept only in the three UNDECLARED_*
  // sentences, whose diagnostic kind is the shared SKIPPED_UNSUPPORTED_TYPE.
  @Test
  fun `a scope, position or nesting skip reads as itself, not as an unsupported combination`() {
    val expected: List<Pair<ForwardCallableCatalogEntry.Skipped, String>> = listOf(
      ForwardCallableCatalogEntry.Skipped(
        symbol = "com.example.Newsroom.sponsor",
        reason = ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE,
        detail = "dev.other.core.Advertisement",
      ) to "its type `dev.other.core.Advertisement` is declared in a dependency module outside " +
          "the export scope",
      ForwardCallableCatalogEntry.Skipped(
        symbol = "com.example.Api.platform",
        reason = ForwardPlanSkipReason.EXPECT_DEPENDENCY_TYPE,
        detail = "dev.other.core.Platform",
      ) to "its type `dev.other.core.Platform` is an `expect` declaration in a dependency " +
          "module, which no export scope of this module can reach",
      ForwardCallableCatalogEntry.Skipped(
        symbol = "com.example.Api.tally",
        reason = ForwardPlanSkipReason.CROSS_MODULE_DISABLED_DEPENDENCY_TYPE,
        detail = "dev.other.core.Tally",
      ) to "its type `dev.other.core.Tally` is declared in a dependency module and cross-module " +
          "export is off",
      ForwardCallableCatalogEntry.Skipped(
        symbol = "com.example.Api.handle",
        reason = ForwardPlanSkipReason.ACTUAL_TYPEALIAS_TARGET,
        detail = "com.example.Handle->platform.NativeHandle",
      ) to "its type `com.example.Handle` is an `actual typealias` to `platform.NativeHandle`, " +
          "which is not exported",
      ForwardCallableCatalogEntry.Skipped(
        symbol = "com.example.NestedModeOwner.set",
        reason = ForwardPlanSkipReason.UNDECLARED_ENUM,
        detail = "com.example.NestedModeOwner.Mode",
      ) to "its enum type `com.example.NestedModeOwner.Mode` is never declared as a C# enum " +
          "(UNDECLARED_ENUM)",
      ForwardCallableCatalogEntry.Skipped(
        symbol = "com.example.NestedListenerOwner.attach",
        reason = ForwardPlanSkipReason.UNDECLARED_INTERFACE,
        detail = "com.example.NestedListenerOwner.Listener",
      ) to "its interface type `com.example.NestedListenerOwner.Listener` is nested and never " +
          "declared as a C# interface (UNDECLARED_INTERFACE)",
      ForwardCallableCatalogEntry.Skipped(
        symbol = "com.example.Newsroom.schedule",
        reason = ForwardPlanSkipReason.UNDECLARED_CLASS,
        detail = "com.example.Newsroom.Schedule",
      ) to "its type `com.example.Newsroom.Schedule` is a nested class or object never declared " +
          "in C# (UNDECLARED_CLASS)",
      ForwardCallableCatalogEntry.Skipped(
        symbol = "com.example.StoryUri.length",
        reason = ForwardPlanSkipReason.INHERITED_MEMBER,
      ) to "it is a value class member that a supertype declares",
      ForwardCallableCatalogEntry.Skipped(
        symbol = "com.example.Api.pick",
        reason = ForwardPlanSkipReason.SEALED_POSITION,
        detail = "com.example.Job",
      ) to "its sealed type `com.example.Job` has no generated C# discriminator",
      ForwardCallableCatalogEntry.Skipped(
        symbol = "com.example.Api.listen",
        reason = ForwardPlanSkipReason.BOUND_INTERFACE_POSITION,
      ) to "a bound C# interface is not marshalled at this position",
      ForwardCallableCatalogEntry.Skipped(
        symbol = "com.example.Api.provide",
        reason = ForwardPlanSkipReason.UNIMPLEMENTABLE_BOUND_INTERFACE,
      ) to "it returns a bound C# interface that Kotlin cannot implement",
    )

    val logger = RecordingLogger()
    warnDroppedForwardCallables(
      ForwardCallablePlanCatalog(entries = expected.map { (skipped, _) -> skipped }),
      logger,
    )

    assertEquals(expected.size, logger.warnings.size, "one warning per skip: ${logger.warnings}")
    expected.forEachIndexed { index, (skipped, sentence) ->
      val warning: String = logger.warnings[index]
      assertTrue(warning.contains(sentence), "${skipped.reason} names itself: $warning")
      assertFalse(
        warning.contains("type combination is not supported"),
        "${skipped.reason} is not also called unsupported: $warning",
      )
    }
  }

  @Test
  fun `a legacy-routed skip produces no warning`() {
    val logger = RecordingLogger()
    val catalog = ForwardCallablePlanCatalog(
      entries = listOf(
        ForwardCallableCatalogEntry.Skipped(
          symbol = "com.example.Api.observe",
          reason = ForwardPlanSkipReason.SUSPEND,
        ),
        ForwardCallableCatalogEntry.Skipped(
          symbol = "com.example.Api.stream",
          reason = ForwardPlanSkipReason.FLOW_PROTOCOL,
        ),
        ForwardCallableCatalogEntry.Skipped(
          symbol = "com.example.Api.onEach",
          reason = ForwardPlanSkipReason.CALLBACK_PROTOCOL,
        ),
      ),
    )

    warnDroppedForwardCallables(catalog, logger)

    assertTrue(logger.warnings.isEmpty(), "legacy-routed skips stay silent: ${logger.warnings}")
  }

  @Test
  fun `only dropped reasons are classified as dropped from C#`() {
    val dropped: Set<ForwardPlanSkipReason> = ForwardPlanSkipReason.entries
      .filter { it.droppedFromCSharp }
      .toSet()

    assertEquals(
      setOf(
        ForwardPlanSkipReason.CHAR,
        ForwardPlanSkipReason.COLLECTION,
        ForwardPlanSkipReason.ENUM,
        ForwardPlanSkipReason.HANDLE,
        // ADR-076: same defensive classification as CHAR/STRING/ENUM/HANDLE/OBJECT.
        ForwardPlanSkipReason.INSTANT,
        // ADR-103: the same, for Duration.
        ForwardPlanSkipReason.DURATION,
        // ADR-107: a genuine drop -- v1 binds a Throwable only at a property getter, so a
        // callable carrying one has no legacy route to defer to.
        ForwardPlanSkipReason.THROWABLE,
        // ADR-106: defensive, like INSTANT/DURATION.
        ForwardPlanSkipReason.UUID,
        ForwardPlanSkipReason.NULLABLE,
        ForwardPlanSkipReason.OBJECT,
        ForwardPlanSkipReason.STRING,
        ForwardPlanSkipReason.UNSUPPORTED,
        ForwardPlanSkipReason.VALUE_CLASS,
        // ROADMAP Phase 3: a secondary constructor of a reference-underlying value class. ADR-035
        // keeps only the positional record-struct constructor, and no legacy route re-emits one.
        // ADR-064: genuine drops with their own named diagnostic kind (cell 23's combination,
        // and a value-class member inherited via interface delegation).
        ForwardPlanSkipReason.UNSUPPORTED_COMBINATION,
        ForwardPlanSkipReason.INHERITED_MEMBER,
        // ADR-066: a reachable dependency-module type the closure did not admit.
        ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE,
        // The same drop, refused for a reason the `include(...)` hint does not fix: the author's
        // own `exclude(...)`, an `expect` in a dependency klib, or cross-module admission being
        // off entirely. Same diagnostic kind, different remedy.
        ForwardPlanSkipReason.EXCLUDED_DEPENDENCY_TYPE,
        ForwardPlanSkipReason.EXPECT_DEPENDENCY_TYPE,
        ForwardPlanSkipReason.CROSS_MODULE_DISABLED_DEPENDENCY_TYPE,
        // ADR-074: an actual typealias target the forward direction does not export.
        ForwardPlanSkipReason.ACTUAL_TYPEALIAS_TARGET,
        // An enum no route declares as a C# enum (nested, or top-level out of scope): a genuine
        // drop, because spelling it produced a dangling C# reference instead.
        ForwardPlanSkipReason.UNDECLARED_ENUM,
        // Issue #54: the nested-interface twin of UNDECLARED_ENUM, dropped for the same reason.
        ForwardPlanSkipReason.UNDECLARED_INTERFACE,
        // ...and the nested class/object twin of both.
        ForwardPlanSkipReason.UNDECLARED_CLASS,
        // ADR-088: a bound C# interface at a position v1 does not marshal, and one that cannot be
        // implemented in Kotlin at a return position. Both are real drops with their own kinds.
        ForwardPlanSkipReason.BOUND_INTERFACE_POSITION,
        ForwardPlanSkipReason.UNIMPLEMENTABLE_BOUND_INTERFACE,
        // ROADMAP Phase 3: a sealed base at an input position. Since ADR-105 no route re-emits
        // one, so the deferral it used to claim was a silent drop.
        ForwardPlanSkipReason.SEALED_POSITION,
        // ADR-115: the author's own opt-in marker, on the declaration and on a member's type.
        // Real drops: no legacy route re-emits a marked declaration, by design.
        ForwardPlanSkipReason.OPT_IN_MARKER,
        ForwardPlanSkipReason.OPT_IN_MARKER_TYPE,
        // ADR-116: a suspend/Flow/generic/callback member of a sealed subclass. The reasons above
        // that stay silent do so because a legacy route re-emits them for an ordinary class; no
        // legacy route is keyed to a sealed subclass, so the same member kinds are real drops
        // there and are reclassified into this reason by the planner.
        ForwardPlanSkipReason.SEALED_SUBCLASS_UNROUTED,
        // ADR-116 amendment (2026-09-11): the same, one level up, for a member the sealed *base*
        // declares. The base carries its ordinary members now, but no legacy route is keyed to it
        // at all, so `Job.rest` (an `open suspend fun`) is a real drop and finally names itself.
        ForwardPlanSkipReason.SEALED_BASE_UNROUTED,
      ),
      dropped,
    )
  }
}
