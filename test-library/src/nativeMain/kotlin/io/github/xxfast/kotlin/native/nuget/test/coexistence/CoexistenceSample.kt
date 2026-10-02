package io.github.xxfast.kotlin.native.nuget.test.coexistence

import test.text.Template

/** A single owned handle, with no cleaner or nested wrapper to disturb exact counter deltas. */
class Probe(val name: String)

/** Erased generic ingress must reject another publisher's wrapper before native dispatch. */
class Envelope<T>(val value: T)

private class DinnerComplaint(message: String, cause: Throwable) : Exception(message, cause)

fun failCustom() {
  throw DinnerComplaint("Oreo wants dinner", IllegalArgumentException("empty bowl"))
}

fun failMapped() {
  throw IllegalArgumentException("Oreo refuses this bowl")
}

fun describe(owner: String? = "nobody"): String = owner ?: "(none)"

fun treats(count: Int? = 7): String = count?.toString() ?: "(none)"

/** Each independent registration calls the same managed dependency from its own native runtime. */
fun reverseRoundTrip(name: String): String {
  val template: Template = Template("Oreo meets {name}")
  return template.use { Template.render(it, name) }
}
