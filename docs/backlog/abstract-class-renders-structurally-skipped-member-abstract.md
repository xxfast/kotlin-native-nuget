# An abstract class with a structurally-skipped inherited member renders it `public abstract`, so a further subclass fails CS0534

> Extracted verbatim from `ROADMAP.md` (Phase 3: Basic type support).

**An abstract exported class that does not itself implement an inherited concrete member the
planner declined to plan renders that member `public abstract` in the generated C#, so a further
C# subclass that does not also override it fails to compile (CS0534, "does not implement inherited
abstract member").** `CirClassTranslator.kt`'s abstract-method path (around line 497-501) computes
`hasImplementation = declaredInThisClass || method.modifiers.contains(Modifier.OVERRIDE)`, not
`!isAbstract`, so a member the class inherits without redeclaring or overriding is treated as
unimplemented and rendered `abstract`, even when the member has a perfectly good body somewhere in
the inheritance chain the C# side never sees (because that base is dropped, per ADR-101's interface
skip or its 2026-09-05 base-class amendment). Pre-existing for the interface case since ADR-009;
the base-class amendment widens the population that can trigger it, since dropping a base class now
also produces "inherited, not redeclared" members on an abstract subclass. Went unnoticed because
no shipped fixture pairs an abstract exported class with a structurally-skipped inherited member.
Inferred from reading only, no fixture. Discovered alongside
[ADR-101](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/101-unexported-supertype-skip.md)'s
2026-09-05 amendment.
