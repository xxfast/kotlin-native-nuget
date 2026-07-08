package io.github.xxfast.kotlin.native.nuget.sample

import sample.text.Template

/**
 * Renders a personalised greeting using the reverse-bridge Template type (ADR-051 round-trip
 * fixture): C# → Kotlin opaque handle → C# Render call → string result.
 */
fun greet(name: String): String {
  val template: Template = requireNotNull(Template.parse("Hello, {name}")) {
    "Template.parse returned null — expected a non-null Template handle"
  }
  return template.use { Template.render(it, name) }
}

/**
 * Same round trip as [greet], but builds the Template using the ADR-052 mapped Kotlin
 * secondary constructor (`Template(source)`) instead of the ADR-051 `Template.parse` factory:
 * C# → Kotlin constructor → C# ctor thunk → opaque handle → C# Render call → string
 * result.
 */
fun greetViaConstructor(name: String): String {
  val template: Template = Template("Hello, {name}")
  return template.use { Template.render(it, name) }
}
