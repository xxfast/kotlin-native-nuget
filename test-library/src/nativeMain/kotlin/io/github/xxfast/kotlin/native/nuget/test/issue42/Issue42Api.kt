package io.github.xxfast.kotlin.native.nuget.test.issue42

import dev.other.core.Issue42Component

/**
 * Issue #42 fixture: the `PeopleInSpaceApi : KoinComponent` shape, minus Koin. This class *is* in
 * `rootPackage`, so KSP sees it directly and exports it; its supertype is not, so the supertype
 * must be dropped with `SKIPPED_UNEXPORTED_SUPERTYPE` while `port` and `describe()` keep
 * exporting.
 *
 * No base class, on purpose and load-bearing: `CirClassTranslator` only populates its `interfaces`
 * list when `forwardSuperClass()` is null, so a superclass here would mask the defect entirely.
 * Oreo mans port 8080; Mylo naps on the router.
 */
class Issue42Api(val port: Int) : Issue42Component {
  fun describe(): String = "api:$port"
}
