package io.github.xxfast.kotlin.native.nuget

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File

internal data class ForwardPackageProducer(val rid: String, val directory: File)

internal fun forwardPackageProducers(
  locals: Map<String, String>, contracts: Map<String, String>, prebuilt: File?,
): List<ForwardPackageProducer> {
  val producers = locals.keys.sorted().map { rid ->
    val path = contracts[rid]
    require(path != null) {
      "[nuget] RID '$rid' has no producer contract directory. " +
        "Rebuild with the updated plugin to produce ForwardAbi.json and Interop.cs."
    }
    ForwardPackageProducer(rid, File(path))
  }
  if (prebuilt == null) return producers
  val dirs = prebuilt.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
  require(dirs.isNotEmpty()) {
    "prebuiltRuntimes directory $prebuilt has no RID subdirectory. " +
      "Expected <rid>/native/ and producer contract sidecars."
  }
  dirs.forEach { dir ->
    require(dir.name !in locals) {
      "RID '${dir.name}' is both linked locally (from ${locals[dir.name]}) and supplied as a " +
        "prebuilt runtime (from $dir). Pick one producer per RID."
    }
  }
  return producers + dirs.map { ForwardPackageProducer(it.name, it) }
}

internal fun validateForwardPackageContracts(producers: List<ForwardPackageProducer>) {
  val contracts = producers.map { producer ->
    val manifest = File(producer.directory, "ForwardAbi.json")
    val source = File(producer.directory, "Interop.cs")
    listOf(manifest, source).forEach { file ->
      require(file.isFile) {
        "[nuget] RID '${producer.rid}' is missing producer contract ${file.absolutePath}. " +
          "Rebuild the original producer with the updated plugin and transfer the complete " +
          "runtimes tree with ForwardAbi.json and Interop.cs."
      }
    }
    val json = try { Json.parseToJsonElement(manifest.readText()) } catch (error: SerializationException) {
      throw IllegalArgumentException("[nuget] RID '${producer.rid}' has malformed manifest $manifest: ${error.message}", error)
    }
    require(json is JsonObject && (json["schemaVersion"] as? JsonPrimitive)?.intOrNull == 1 && (json["schemaVersion"] as JsonPrimitive).isString.not()) {
      "[nuget] RID '${producer.rid}' has unsupported or malformed ForwardAbi.json schema at $manifest; expected schemaVersion 1. Rebuild its original producer."
    }
    val values = json["abi"]
    require(values is JsonArray) { "[nuget] RID '${producer.rid}' has malformed ABI array at $manifest" }
    val abi = values.map { value ->
      require(value is JsonPrimitive && value.isString && ABI_SIGNATURE.matches(value.content)) {
        "[nuget] RID '${producer.rid}' has malformed ABI signature $value at $manifest"
      }
      value.content
    }
    require(abi == abi.sorted() && abi.map { it.substringBefore('(') }.distinct().size == abi.size) {
      "[nuget] RID '${producer.rid}' has unsorted or duplicate ABI symbols at $manifest"
    }
    Triple(producer, abi, csharpContractTokens(source.readText(), "RID '${producer.rid}' $source"))
  }
  val baseline = contracts.firstOrNull() ?: return
  contracts.drop(1).forEach { other ->
    val context =
      "[nuget] Forward package contract differs between RID '${baseline.first.rid}' " +
        "(${baseline.first.directory}) and RID '${other.first.rid}' (${other.first.directory}). " +
        "Align exported declarations or package incompatible targets separately."
    require(baseline.second == other.second) {
      val expected = baseline.second.associateBy { it.substringBefore('(') }
      val actual = other.second.associateBy { it.substringBefore('(') }
      context + "\nABI differences:\n" + (expected.keys + actual.keys).sorted().filter { expected[it] != actual[it] }
        .joinToString("\n") { "  $it: expected ${expected[it] ?: "<missing>"}; actual ${actual[it] ?: "<missing>"}" }
    }
    if (baseline.third != other.third) {
      val index = (0 until maxOf(baseline.third.size, other.third.size)).first {
        baseline.third.getOrNull(it) != other.third.getOrNull(it)
      }
      fun region(tokens: List<String>): String =
        tokens.subList(maxOf(0, index - 12), minOf(tokens.size, index + 12)).joinToString(" ")
      throw IllegalArgumentException(
        context + "\nManaged Interop.cs tokens differ at token $index:\n" +
          "  expected: ${region(baseline.third)}\n  actual: ${region(other.third)}"
      )
    }
  }
}

private val ABI_SIGNATURE = Regex("[^\\s()]+\\((?:(?:in|out) (?:bool|byte|short|int|long|float|double|pointer|string)(?:, (?:in|out) (?:bool|byte|short|int|long|float|double|pointer|string))*)?\\) -> (?:void|bool|byte|short|int|long|float|double|pointer|string)")

/** Conservative generated-source lexer: literals are opaque tokens, never trivia-stripped. */
internal fun csharpContractTokens(source: String, context: String = "Interop.cs"): List<String> {
  val tokens = mutableListOf<String>()
  var index = 0
  fun fail(): Nothing = throw IllegalArgumentException(
    "[nuget] Unsupported or unterminated C# syntax at offset $index in $context"
  )
  fun quoted(start: Int, quote: Char, verbatim: Boolean, interpolated: Boolean): Int {
    var cursor = start + 1
    var braces = 0
    while (cursor < source.length) {
      val char = source[cursor]
      if (!verbatim && char == '\\') {
        cursor += 2
        continue
      }
      if (interpolated && char == '{') {
        if (braces == 0 && source.getOrNull(cursor + 1) == '{') {
          cursor += 2
          continue
        }
        braces++
        cursor++
        continue
      }
      if (interpolated && char == '}' && braces > 0) {
        braces--
        cursor++
        continue
      }
      if (braces > 0 && (source.startsWith("//", cursor) || source.startsWith("/*", cursor))) fail()
      if (braces > 0 && (char == '"' || char == '\'')) {
        val startsNestedInterpolation: Boolean =
          source.getOrNull(cursor - 1) == '$' ||
            (source.getOrNull(cursor - 1) == '@' && source.getOrNull(cursor - 2) == '$')
        if (startsNestedInterpolation || source.startsWith("\"\"\"", cursor)) fail()
        cursor = quoted(cursor, char, source.getOrNull(cursor - 1) == '@', false)
        continue
      }
      if (char == quote && braces == 0) {
        if (verbatim && source.getOrNull(cursor + 1) == quote) {
          cursor += 2
          continue
        }
        return cursor + 1
      }
      if (!verbatim && braces == 0 && (char == '\n' || char == '\r')) fail()
      cursor++
    }
    fail()
  }
  val operators: List<String> = listOf(
    ">>>=", "<<=", ">>=", "??=", "=>", "==", "!=", "<=", ">=", "++", "--", "&&", "||",
    "??", "?.", "::", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>", "..",
  )
  while (index < source.length) {
    val start = index
    val char = source[index]
    if (char.isWhitespace()) {
      index++
      continue
    }
    if (source.startsWith("//", index)) {
      index = source.indexOf('\n', index).let { if (it < 0) source.length else it }
      continue
    }
    if (source.startsWith("/*", index)) {
      val end = source.indexOf("*/", index + 2)
      if (end < 0) fail()
      index = end + 2
      continue
    }
    if (char == '#') {
      index = source.indexOf('\n', index).let { if (it < 0) source.length else it }
      tokens.add("DIRECTIVE:" + source.substring(start, index).trim())
      continue
    }
    var quote = index
    while (source.getOrNull(quote) == '$') quote++
    if (source.getOrNull(quote) == '@') quote++
    if (char == '@' && source.getOrNull(index + 1) == '$') quote = index + 2
    if (source.getOrNull(quote) == '"' || (quote == index && char == '\'')) {
      var count = 0
      while (source.getOrNull(quote + count) == '"') count++
      if (count >= 3) {
        require(!source.substring(start, quote).contains('$')) {
          "[nuget] Unsupported raw interpolated C# literal at offset $index in $context; use the producer's supported generated source syntax."
        }
        val delimiter = "\"".repeat(count)
        val end = source.indexOf(delimiter, quote + count)
        if (end < 0) fail()
        index = end + count
      } else {
        index = quoted(
          quote,
          source[quote],
          source.substring(start, quote).contains('@'),
          source.substring(start, quote).contains('$'),
        )
      }
      tokens.add(source.substring(start, index))
      continue
    }
    if (char.isDigit()) {
      index++
      while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_' ||
        (source[index] == '.' && source.getOrNull(index + 1)?.isDigit() == true) ||
        (source[index] in "+-" && source.getOrNull(index - 1) in listOf('e', 'E')))) index++
    } else if (char.isLetter() || char == '_' || char == '@') {
      index++
      while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_')) index++
    } else {
      val operator = operators.firstOrNull { source.startsWith(it, index) }
      index += operator?.length ?: 1
      require(char in "{}[]();,.?:~!%^&*+-=/<>|" || operator != null) { "[nuget] Unsupported C# token '$char' at $index in $context" }
    }
    tokens.add(source.substring(start, index))
  }
  return tokens
}
