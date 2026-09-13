package io.github.xxfast.kotlin.native.nuget.test

import dev.other.admitted.Billboard

/**
 * ADR-066 §5 amendment: the in-root root that pulls `dev.other.admitted.Billboard` through the
 * reachability closure, so the admitted out-of-root type is declared and referenced for real.
 *
 * A new class rather than another member on `Newsroom`, whose member set
 * `NewsroomReachabilityTests.cs` asserts exhaustively — this fixture has to be additive.
 *
 * Asserted by `IntegrationTests/OutOfRootNamespaceTests.cs`.
 */
class Billboards {

  /**
   * Reference site: the return type must be spelled
   * `global::TestLibrary.Dev.Other.Admitted.Billboard`.
   */
  fun current(): Billboard = Billboard("Oreo naps here. Mylo supervises.")
}
