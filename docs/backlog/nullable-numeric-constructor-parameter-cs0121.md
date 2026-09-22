# A single nullable-numeric constructor parameter collides with the internal handle constructor (CS0121)

Found while shipping the boundary-nullability-gaps item (`Char?` at every ordinary position).

`new Tag('O')` is `CS0121`: a bare `char` literal converts implicitly to both `char?` (a lifted
conversion) and `nint` (`char` widens to `int`, then to `nint` numerically), and the public
`Tag(char?)` constructor and the internal `Tag(nint)` handle constructor are equally applicable, so
neither overload is better. `new Tag(null)` is unambiguous, since `null` has no conversion to `nint`
at all, which is why the fixture (`IntegrationTests/CharPositionMarshallingTests.cs`) casts the
non-null literal (`new Tag((char?)'O')`) rather than fixing the ambiguity.

This is not specific to `Char?`: any class whose public constructor takes a single nullable numeric
parameter (`Box(int?)`, `Tag(char?)`, ...) has the same collision against
`CirClassRenderer`'s emitted `internal X(IntPtr handle)`.

Not fixed here (the fixture works around it with a cast). Candidate fixes: a marker parameter on the
internal handle constructor so it is never reachable by an implicit numeric conversion, or an
internal static `FromHandle` factory in place of a constructor overload. Verified by compile (the
`CS0121` and the cast workaround are both real, reproduced in
`IntegrationTests/CharPositionMarshallingTests.cs`).
