package io.github.xxfast.kotlin.native.nuget.test

import io.github.xxfast.kotlin.native.nuget.test.issue50.Issue50Assignment
import io.github.xxfast.kotlin.native.nuget.test.issue50.Issue50Position

/**
 * Issue [#50](https://github.com/xxfast/kotlin-native-nuget/issues/50) fixture, root-package half.
 *
 * A sealed hierarchy in the root namespace (`TestLibrary`) whose subclass payloads come from the
 * `TestLibrary.Issue50` sub-namespace. #47 fixed this hop for top-level classes; the sealed
 * subclass renderer still wrote `IReadOnlyList<Issue50Assignment>`, `new List<Issue50Assignment>`,
 * `FromHandle<Issue50Assignment>` and `new Issue50Position(...)` by simple name, none of which
 * resolve from inside `namespace TestLibrary` (CS0246). The shape is `joreilly/PeopleInSpace#503`'s
 * `PersonListUiState.Success(val result: List<Assignment>)` and
 * `IssPositionUiState.Success(val position: IssPosition)`, folded into one hierarchy.
 *
 * Kept disjoint from #38/#39/#40/#41: no nullable payloads, no `Flow`/`StateFlow`, no supertypes.
 * The crew roster is who's aboard and where the station is: Oreo and Mylo, orbiting the kitchen.
 */
sealed class Issue50State {
  data object Loading : Issue50State()

  data class Success(
    val crew: List<Issue50Assignment>,
    val position: Issue50Position,
  ) : Issue50State()
}

/**
 * Hands a [Issue50State] to C# from a top-level function, the same producer shape as #39's
 * `Issue39Sample.loadedCats()`, so the sealed discriminator lands on the cross-namespace arm.
 */
fun issue50Loading(): Issue50State = Issue50State.Loading

fun issue50Loaded(): Issue50State = Issue50State.Success(
  crew = listOf(
    Issue50Assignment("Oreo", "Kitchen Station"),
    Issue50Assignment("Mylo", "Kitchen Station"),
  ),
  position = Issue50Position(latitude = -37.81, longitude = 144.96),
)
