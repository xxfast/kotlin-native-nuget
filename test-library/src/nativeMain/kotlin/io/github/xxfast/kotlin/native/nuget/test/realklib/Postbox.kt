package io.github.xxfast.kotlin.native.nuget.test.realklib

import co.touchlab.kermit.Severity
import io.ktor.http.Url

/**
 * ADR-154 against real Maven-published klibs: the in-root reacher for ktor's [Url] and kermit's
 * [Severity], both admitted by name in `test-library`'s publish block and in no include list.
 * Each type crosses both ways. Asserted by `IntegrationTests/RealKlibAdmissionTests.cs`.
 *
 * Oreo posts letters to every cat on the street. Mylo only ever posts complaints.
 */
class Postbox(val owner: String) {

  /** A ktor [Url], parsed in Kotlin: C# cannot construct one, the constructor is internal. */
  fun parse(address: String): Url = Url(address)

  /** The [Url] handed back to Kotlin as a parameter. */
  fun hostOf(url: Url): String = url.host

  /** A kermit [Severity] at a return position. */
  fun severity(): Severity = Severity.Warn

  /** A kermit [Severity] at a parameter position. */
  fun nameOf(severity: Severity): String = severity.name
}
