# Kotlin Style Guide

Based on [style guide](https://github.com/msi-mdc/KotlinStyleGuide)

## Naming

- Variable names are `camelCase`
- Type names are `CapitalCamelCase`
- Function names are `camelCase`
- Constants are `SCREAMING_SNAKE_CASE`
- Enum entries are `SCREAMING_SNAKE_CASE`

```kotlin
const val GREETING = "Hello, world!"

class Foo {
  private val secret = "shhh"

  fun greetTheWorld() {
    println(GREETING)
  }
}

enum Bar { FIRST_BAR, SECOND_BAR }
```

### Hungarian notation

Do not use [Hungarian notation](http://jakewharton.com/just-say-no-to-hungarian-notation/).

### Acronyms

When naming things, treat acroyms as full words.
```kotlin
// OK
val request = XmlHttpRequest()
// Not OK
val request = XMLHTTPRequest()
```

## Formatting

### Line length

Lines may be no more than 100 characters in length.
- Preserves readability for individual lines
- Ensures that two files can be displayed side-by-side on most displays

### Line breaking

The General white-spacing rule is to keep a line break between blocks of code

```kotlin
fun foo() {
  val bar = Bar()
  // line-break here
  if (bar.isFoo()) {
    val foo: Bar = bar
    // line-break here
    when {
      foo.isBar -> { .. }
      // line-break here
      foo.isReallyBar() -> { .. }
      // line-break here
      foo.isReallyReallyBar() -> { .. }
    }
    // line-break here
    val bar = Foo()
  }
}
```

### Indentation

Use 2 space characters for indentation. 

```kotlin
fun foo() {
  val bar = flowOf(1)
    .map { it.toString() }
}
```

- Using spaces instead of tabs means code must look good regardless of tab-length settings
- Using 2 spaces helps maximise the use of our limited horizontal real estate


#### Trailing commas

Since Kotlin 1.4, for the reasons outlined [here](https://kotlinlang.org/docs/coding-conventions.html#trailing-commas), we encourage the use of trailing commas
```kotlin
listOf(
  bar1, 
  bar2,
  bar3, 
  bar4,
)
```

### Aligning invocations

When methods are invoked in a chain they must be aligned.
```kotlin
// OK
val foo: List<String> = list
  .filter {
    it > 10
  }
  .map {
    it.toString()
  }

// Not OK
val bar: List<String> = list.filter {
  it > 10
}.map {
  it.toString()
}
```

### Short things

Short methods and classes may be declared on a single line.

```kotlin
data class Foo(val foo1: Int, val foo2: String)

fun bar(): String = "What would you like to drink?"
```

### Colons

Use a space either side of a colon when declaring inheritance. Use only a space after a colon
when declaring a variable/method type.
```kotlin
interface Foo<out T : Any> : Bar {
  fun foo(a: Int): T
}
```

### Blank lines

Lines that are intentionally blank for formatting purposes should contain no characters at all.
Never use more than one blank line in a row.

### Imports

Always use explicit imports. Never use wildcard imports.
```kotlin
// OK
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

// Not OK
import android.view.*
```

## Type Inference

Type inference is powerful but can be easily abused. As a general rule, type inference should
only be used when it's explicitly clear what the resulting type will be.

```kotlin
// OK
fun foo() = "Hello."
val bar = Bar()

// Not OK
val baz = generateBaz()
```

Under any other circumstances the type must be explicitly declared, even if it's not necessary for the compiler.

## Lambdas

### Avoiding parenthesis

Whenever possible lambdas should be placed outside of parentheses.
```kotlin
// OK
list.filter { it > 10 }
// Not OK
list.filter({ it > 10 })
```

### Using `it`

The implicit `it` parameter given to lambdas should be used judiciously. Unless a lambda is very
short, consider overriding `it` with a more meaningful name.
```kotlin
// Specifying the lambda parameter of `user` rather than `it` helps this code.
webServices.getUsers()
    .flatMapIterable { it }
    .map { user ->
      when {
        user.isAdmin() -> Admin(user)
        user.isManager() -> Manager(user)
      }
    }
```

### Unused parameters

Unused lambda parameters should be replaced with an underscore.
```kotlin
foo { _, _, bar -> println(bar) }
```

### Return statements

Return values inside of lambdas have the potential to be confusing to readers. If a lambda returns a
value and has more than a few lines then a return tag must be explicitly provided.
```kotlin
// OK
list.filter { it > 3 }
    .map {
      val x = it * 4
      if (x > 10) return@map it % 10
      y = x
      return@map x + 7
    }

// Not OK
list.filter { it > 3 }
    .map {
      val x = it * 4
      if (x > 10) it % 10
      y = x
      x + 7
    }
```

## Control Flow

### Prefer an early exit

Whenever possible return from a method rather than creating additional levels of indentation with
conditions.
```kotlin
// OK
fun foo(bar: Int) {
  if (bar < 3) return

  // Do other stuff.
}

// Not OK
fun foo(bar: Int) {
  if (bar >= 3) {
    // Do other stuff.
  }
}
```

### `If` statements

Short `if` statements may be declared on a single line.
```kotlin
fun foo(bar: Bar) {
  if (bar.qualifiesForThing()) bar.doThing()
  else bar.doSomethingElse()
}
```

However, if either the `if` or the `else` section take up multiple lines then both must make use of
curly braces.
```kotlin
// OK
fun foo(bar: Bar): Int {
  if (bar.qualifiesForThing()) {
    val baz = bar.getThing()
    return baz.calcSomeInt()
  } else {
    return 3
  }
}

// Not OK
fun foo(bar: Bar): Int {
  if (bar.qualifiesForThing()) {
    val baz = bar.getThing()
    return baz.calcSomeInt()
  } else return 3
}
```

If a variable is set as the result of an `if` statement then prefer the formatting:
```kotlin
val foo: String = if (something()) bar else baz
```
If the condition is long then prefer:
```kotlin
val foo: String =
    if (someCalculationThatIsLong() > someOtherLongCalculation()) bar
    else baz
```

## Comments

It is not mandatory to comment every section of code written. People often forget that when writing
comments, they commit to maintaining not only their code but also their documentation. Comments that
are out-of-date with their corresponding code are potentially dangerous.

Methods such as `size()` on a collection don't need comments, as their purpose is immediately apparent
to the user. Adding a commment only adds noise.

### API documentation

When documenting a class, function, or property to be used by others
[KDoc style](https://kotlinlang.org/docs/reference/kotlin-doc.html) should be used.
```kotlin
/**
 * Calculates bounding box that contains both provided [Rect]s
 */
fun getEnclosingBounds(first: Rect, second: Rect): Rect
```
It is often not necessary to provide `@param`,`@return` tags, as they can result in needless duplication.

### Inline documentation

Comments intended to aid someone who is reading code should use C++ style `// comments`. For formatting
purposes there should be a space between the slashes and the content.
```kotlin
// This is a helpful one line comment.
```

### TODO comments

`TODO` Comments should either be in the format of
```kotlin
// TODO Some task that needs to be done.
```
or make use of Kotlin's [TODO()](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-t-o-d-o.html)
function
```kotlin
TODO("Some task that needs to be done.")
```

### Extract complex boolean expressions

When a boolean expression combines multiple conditions, extract each part into a named variable.
```kotlin
// OK
val classesHaveLists: Boolean = (classes + genericClasses)
  .any { cls -> cls.getAllProperties().any { prop -> prop.type.resolve().isListType() } }

val functionsReturnLists: Boolean = (functions + genericFunctions)
  .any { func -> func.returnType?.resolve()?.isListType() == true }

val needsListSupport: Boolean = classesHaveLists || functionsReturnLists

// Not OK
val needsListSupport: Boolean = (classes + genericClasses).any { cls ->
  cls.getAllProperties().any { prop -> prop.type.resolve().isListType() }
} || (functions + genericFunctions).any { func ->
  func.returnType?.resolve()?.isListType() == true
}
```

## Function Declarations

Many of the assertions below are based on
[John Carmack's thoughts](http://number-none.com/blow/john_carmack_on_inlined_code.html).

### Length

If a function has explicit inputs, outputs, and doesn't modify external state then don't be afraid of
its length. These are the easiest functions to test and often a long section of linear processing is
neater than declaring many "helper" functions that are only used once.

### Avoid helper functions

If a function is only ever called by one other function, that function should most likely become a
[local function](https://kotlinlang.org/docs/reference/functions.html#local-functions).

If a function is only ever called in one place consider inlining it. This doesn't mean appending
Kotlin's `inline` keyword but rather refers to taking the contents of the method and replacing them
where the function was originally invoked. If that section of code could later be used as a function
in many places then consider pulling it out again, but only when necessary.

### Prefer `.forEach` over `for (in)`

For simple iteration, prefer `.forEach` as it reads as a one-liner and chains cleanly.
```kotlin
// OK
classes.forEach { addClassExports(it) }
enums.forEach { addEnumExports(it) }

// OK - when the body needs a named parameter
functions.forEach { func ->
  addImport(func.packageName.asString(), func.simpleName.asString())
  addFunctionExports(func)
}

// Not preferred
for (cls in classes) {
  addClassExports(cls)
}
```

### Avoid indirection

Do not create wrapper functions that simply delegate to another function without adding value.
```kotlin
// Not OK - pointless indirection
private fun cNameFor(func: KSFunctionDeclaration): String =
    toCName(func.simpleName.asString())

// OK - just call it directly
val cname: String = toCName(func.simpleName.asString())
```

### Prefer `kotlin.time.Duration` over raw numeric durations

When dealing with time durations, always use Kotlin's native `Duration` type instead of raw `Long` or `Int` milliseconds.
```kotlin
// OK
delay(10.seconds)
delay(500.milliseconds)

// Not OK
delay(10000)
delay(500L)
```
