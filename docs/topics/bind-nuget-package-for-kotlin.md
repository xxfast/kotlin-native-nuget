# Bind a NuGet package for Kotlin

Select a C# API from a NuGet dependency, generate Kotlin bindings, and package the bridge with
`MyCatLib` so a .NET consumer can call through Kotlin to the bound package.

This guide continues the `MyCatLib` project from
[Publish a Kotlin/Native library as NuGet](publish-kotlin-library-as-nuget.md).

## 1. Select the package and C# namespaces

Add a dependency with `bind {}`. This example binds only the `MimeMapping` namespace and places its
generated Kotlin API in the `mimemapping` package:

```kotlin
nuget {
  dependencies {
    dependency("MimeMapping", version = "4.0.0") {
      bind {
        packageName = "mimemapping"
        include("MimeMapping")
      }
    }
  }
}
```

<note>
<p>Use <code>include</code>, <code>exclude</code>, and <code>alias</code> to keep the generated
surface focused. See <a href="declaring-dependencies.md">Declaring dependencies</a> for their
matching rules and <a href="bridgeable-subset.md">The bridgeable subset</a> before choosing an API
to bind.</p>
</note>

## 2. Generate the bindings

Run the IDE-sync umbrella task:

```bash
./gradlew nugetImport
```

The task restores the package, reads its public assembly metadata, and generates Kotlin stubs plus
C# registration shims. The Kotlin output is added to `nativeMain`, so regular source can import it.
Add this helper under `src/nativeMain/kotlin/com/example/cats/mime/MimeSample.kt`:

```kotlin
package com.example.cats.mime

import mimemapping.MimeUtility

fun catPhotoMimeType(fileName: String): String = MimeUtility.getMimeMapping(fileName)
```

## 3. Package the bridge and call it

Run `packNuget` again. When both `publish {}` and `dependencies {}` are present, the package
includes the C# registration shims and an exact dependency on the bound NuGet package. The .NET
consumer still references only the Kotlin library package.

The helper is exported from Kotlin under the `MyCatLib.Mime` namespace, completing a C# to Kotlin
to bound NuGet round trip:

```C#
using MyCatLib.Mime;

string mimeType = MimeSample.catPhotoMimeType("oreo.jpg");
Console.WriteLine(mimeType); // image/jpeg
```

If a generated binding reports that registrations did not fire, follow
[Registration diagnostics](registration-diagnostics.md). For the generated pipeline and supported
C# member shapes, continue with [Consuming C# in Kotlin](reverse-overview.md).

<seealso>
    <category ref="related">
        <a href="how-to-guide.md">How-to guides</a>
        <a href="publish-kotlin-library-as-nuget.md">Publish a Kotlin/Native library as NuGet</a>
        <a href="gradle-tasks.md">Gradle tasks</a>
        <a href="nuget-dsl.md">The nuget {} DSL</a>
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
    </category>
</seealso>
