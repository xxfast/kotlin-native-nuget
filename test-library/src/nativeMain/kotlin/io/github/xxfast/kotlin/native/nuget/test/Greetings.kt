package io.github.xxfast.kotlin.native.nuget.test

import test.text.Template

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

/**
 * Same round trip family as [greet]/[greetViaConstructor], but exercises the instance-member
 * surface added for Phase 9 line 151: a settable property (`Name`), a read-only property
 * (`Source`), an instance method with a string in/out (`Apply`), and an instance method that
 * returns a fresh handle to the same bound type (`Clone`) — layered on top of the mapped
 * Kotlin secondary constructor from ADR-052.
 */
fun greetViaInstanceMembers(name: String): String {
  val template: Template = Template("Hello, {name}")
  template.name = name

  val copy: Template = requireNotNull(template.clone()) {
    "Template.clone returned null — expected a non-null Template handle"
  }
  check(copy.name == template.name) { "Template.clone did not carry over the Name property" }
  check(copy.source == template.source) { "Template.clone did not carry over the source" }

  return template.use { copy.use { c -> c.apply(name) } }
}

/**
 * Exercises Phase 9 static C# properties through [Template]'s Kotlin companion object:
 * a write/read of `DefaultName` and a read of the primitive `RenderCount`.
 */
fun setDefaultTemplateCatName(name: String): String {
  Template.defaultName = name
  return Template.defaultName
}

/** Returns the read-only static render counter exposed by [Template]. */
fun templateRenderCount(): Int = Template.renderCount
