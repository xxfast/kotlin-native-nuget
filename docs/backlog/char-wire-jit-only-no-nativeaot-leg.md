# The `Char?` wire is measured on JIT only; no NativeAOT leg on the dev box

Found while shipping the boundary-nullability-gaps item (`Char?` at every ordinary position).

The evidence that `out ushort` plus a cast round-trips non-ASCII correctly, that
`[MarshalAs(UnmanagedType.U2)] out char` is also correct, and that a bare `out char` silently
corrupts every non-ASCII character (`'é'` to U+FFFD, `'한'` to a truncated low byte), was gathered
from a scratch Kotlin/Native DLL and a C# console app run on the JIT, win-x64, .NET 10.0.9. None of
it was re-run with `PublishAot=true`.

The shipped fix uses `out ushort`, which is blittable by construction and needs no AOT-specific
verification for that reason. It is `[MarshalAs(UnmanagedType.U2)] out char`, the alternative
narrower shape an implementer might reach for on ADR-069 symmetry grounds, that carries the open
question: whether NativeAOT accepts and marshals that attribute on an `out char` the same way JIT
does is unverified. Not fixed or measured here. Candidate check: extend the existing `AotSmokeTest`
project (ADR-102) with one `Char?` round-trip cell and run it through the `PublishAot=true` lane
already used for the callback thunks.
