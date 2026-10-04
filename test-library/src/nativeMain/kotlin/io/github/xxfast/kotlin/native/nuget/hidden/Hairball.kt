package io.github.xxfast.kotlin.native.nuget.hidden

/**
 * ADR-201: a module-local `Throwable` subclass that is NOT exported. The package is outside
 * `rootPackage` and the `include(...)` list, and an exception class is never admitted by the
 * ADR-066 closure on its own, so [io.github.xxfast.kotlin.native.nuget.test.mishaps.MishapLog]
 * returning it used to skip as an undeclared type. It now binds as the ADR-107 envelope:
 * `System.Exception`, mapped through its stdlib base (`KotlinInvalidOperationException`) with its
 * own qualified name in `KotlinType`.
 *
 * Mochi, at 3am, on the good rug.
 */
class HairballError(message: String) : IllegalStateException(message)
