package io.github.xxfast.kotlin.native.nuget.companion.coexistence

import test.text.Template

/** A second publisher's single owned handle. */
class Probe(val name: String)

/** Must accept its own wrapper but reject a TestLibrary wrapper. */
class Envelope<T>(val value: T)

private class DinnerComplaint(message: String, cause: Throwable) : Exception(message, cause)

fun failCustom() {
  throw DinnerComplaint("Mylo wants dinner", IllegalArgumentException("empty bowl"))
}

fun failMapped() {
  throw IllegalArgumentException("Mylo refuses this bowl")
}

fun describe(owner: String? = "nobody"): String = owner ?: "(none)"

fun treats(count: Int? = 7): String = count?.toString() ?: "(none)"

fun reverseRoundTrip(name: String): String {
  val template: Template = Template("Mylo meets {name}")
  return template.use { Template.render(it, name) }
}
