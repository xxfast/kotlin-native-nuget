package io.github.xxfast.kotlin.native.nuget.test.catcam.lens

/**
 * Issue #111, the *expressible* half of a lambda type argument: an exported class that C# can
 * genuinely name.
 *
 * It lives one package below [io.github.xxfast.kotlin.native.nuget.test.catcam] on purpose, so it
 * lands in the C# namespace `TestLibrary.Catcam.Lens` while every declaration that mentions it
 * lives in `TestLibrary.Catcam`. A lambda type argument spelled by *simple name* only compiles
 * when those two namespaces coincide, which is precisely the assumption issue #111 is about, so a
 * same-package fixture would go green while proving nothing.
 *
 * Oreo is the black one with the white bib, so the cam calls his lens `oreo-front`.
 */
class CamId(val value: String)
