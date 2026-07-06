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
