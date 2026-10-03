package io.github.xxfast.kotlin.native.nuget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith

class CsharpContractTokensTest {
  @Test fun `comments and whitespace are trivia but token boundaries remain`() {
    assertEquals(csharpContractTokens("int /* doc */ value=1;// doc\n"), csharpContractTokens(" int value = 1 ; "))
    assertNotEquals(csharpContractTokens("a++b"), csharpContractTokens("a + + b"))
    assertNotEquals(csharpContractTokens("ab"), csharpContractTokens("a b"))
    assertNotEquals(csharpContractTokens("1.2"), csharpContractTokens("1 . 2"))
  }
  @Test fun `string character verbatim raw and interpolated literal contents are retained`() {
    val literals = listOf("\"a // /* b */\"", "@\$\"a {value}\"", "@\"a \"\" // b\"", "' '", "\"a\\\"b\"", "\"\"\"a // b\"\"\"", "\$\"a {value} b\"", "\$\"a {\"nested\"} b\"")
    literals.forEach { literal ->
      assertEquals(listOf(literal), csharpContractTokens(literal))
      assertNotEquals(csharpContractTokens(literal), csharpContractTokens(literal.replace("a", "z").replace(" ", "_ ")))
    }
    assertNotEquals(csharpContractTokens("\"a b\""), csharpContractTokens("\"ab\""))
    assertNotEquals(csharpContractTokens("\"//a\""), csharpContractTokens("\"//b\""))
  }
  @Test fun `directives and declaration order remain conservative`() {
    assertNotEquals(csharpContractTokens("#if X\nint a;\n#endif"), csharpContractTokens("#if Y\nint a;\n#endif"))
    assertNotEquals(csharpContractTokens("int a;int b;"), csharpContractTokens("int b;int a;"))
  }
  @Test fun `unsupported and unterminated syntax fails clearly`() {
    listOf("\$\"{\$@\"nested {value}\"}\"", "\$\"{/* comment */ value}\"", "\"unfinished", "/* unfinished", "\"\"\"unfinished", "\\u1234", "\$\"\"\"raw {value}\"\"\"").forEach { source ->
      assertFailsWith<IllegalArgumentException> { csharpContractTokens(source) }
    }
  }
}
