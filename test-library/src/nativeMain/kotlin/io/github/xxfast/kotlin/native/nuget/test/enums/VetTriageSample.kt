package io.github.xxfast.kotlin.native.nuget.test.enums

import test.enums.VetTriage
import test.enums.VetTriageDesk

/**
 * ADR-006 2026-10-02 amendment: a C# enum whose members carry acronym runs binds with one
 * SCREAMING_SNAKE word per run. Naming every entry here is the compile check; the round trip
 * through [VetTriageDesk.escalate] is the ordinal check.
 */
fun escalateTriage(triage: VetTriage): VetTriage {
  val expected: VetTriage = when (triage) {
    VetTriage.OK -> VetTriage.HTTP_TIMEOUT
    VetTriage.HTTP_TIMEOUT -> VetTriage.IO_ERROR
    VetTriage.IO_ERROR -> VetTriage.WIN32_NT
    VetTriage.WIN32_NT -> VetTriage.OK
  }
  val actual: VetTriage = VetTriageDesk().escalate(triage)
  check(actual == expected) {
    "VetTriageDesk.escalate($triage) returned $actual, expected $expected"
  }
  return actual
}
