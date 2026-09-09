# One unsupported trailing defaulted parameter drops every supported arity, not just the unsupported one

**What breaks**: a function or constructor with a trailing run of defaulted parameters is supposed
to get one omitting C# overload per suffix length ([ADR-096](docs/adr/096-function-default-parameters.md)
/ [ADR-091](docs/adr/091-constructor-default-parameters.md)). When the *last* parameter in that run
has an unsupported type, the whole declaration is skipped, including every shorter overload that
never touches the unsupported parameter. The original issue #131 report,

```kotlin
fun hub(
  settings: Settings = Settings(),
  logger: Logger? = null,
  events: Flow<Event>? = null,
): Hub = Hub(settings, logger, events)
```

loses `hub()` and `hub(settings)` along with `hub(settings, logger, events)`, even though neither
call reaches `events` at all.

**Root cause**: `ForwardCallablePlanner.kt`'s omitting-overload synthesis derives every shorter
arity from the *declared* entry (`entryFor(member, omitted + 1).synthesized()`, called from six
sites: lines 549, 564, 949, 1052, 1327, 1366). `synthesized()` itself (`:1206`) is a guarded copy,
`if (this is ForwardCallableCatalogEntry.Planned) copy(synthesized = true) else this`, so when the
declared entry is a `ForwardCallableCatalogEntry.Skipped` (because one parameter, anywhere in the
signature, has no wire), every synthesis call site passes that same `Skipped` entry through
unchanged instead of producing a shorter, fully-supported `Planned` entry. The omitting-overload
mechanism has no notion of "the defaulted suffix up to the unsupported parameter is fine, only the
declaration past it is not."

**Why it went unnoticed**: no existing fixture combined a trailing defaulted run with an unsupported
type on one of the later defaulted parameters; every prior unsupported-parameter fixture used a
required (non-defaulted) parameter, where losing the whole declaration is the only sane outcome
since there is no shorter arity to fall back to.

**Discovered alongside** issue [#131](https://github.com/xxfast/kotlin-native-nuget/issues/131) and
[ADR-064](docs/adr/064-forward-unsupported-declaration-diagnostics.md)'s 2026-09-09 amendment, which
fixed the diagnostic's wording but deliberately left this mapping question open. Fixing it needs a
decision ("does a partially unsupported signature bind at its supported arities?") plus an
ABI-numbering consequence (the omitting overloads' suffix numbering assumes one declared entry per
signature), so it needs its own ADR when picked up, not a quick patch alongside the diagnostic fix.
