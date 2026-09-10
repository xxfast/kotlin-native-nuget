# `sealedInterfaceIneligibility()` names only the first refusing arm

**An ineligible sealed interface with two refusing arms only names one of them in
`SKIPPED_INELIGIBLE_SEALED_INTERFACE`.** If a sealed interface has, say, one arm that is an `enum
class` and another that extends a second superclass, the diagnostic's hint reports whichever of the
two `sealedInterfaceIneligibility()` reaches first and says nothing about the second; a reader who
fixes the named arm rebuilds only to hit the same diagnostic again for the other one.

Inferred from reading `sealedInterfaceIneligibility()`'s walk over `getSealedSubclasses()`, which
returns on the first refusing arm it finds rather than collecting every refusal. Not verified
against a real two-refusal fixture; no fixture in `test-library` declares a sealed interface with
two independently-refusing arms at once.

Fine for a single-arm warning (the common case), noted for completeness. Discovered alongside
[ADR-112](../adr/112-sealed-interface-mapping.md)'s 2026-09-10 amendment (nested-arm single
warning).
