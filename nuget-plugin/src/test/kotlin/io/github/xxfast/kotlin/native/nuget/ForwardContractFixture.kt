package io.github.xxfast.kotlin.native.nuget

import java.io.File

internal fun writeProducerContract(dir: File, source: String = "public enum Mode { A = 0 }") {
  dir.mkdirs()
  File(dir, "ForwardAbi.json").writeText("""{"schemaVersion":1,"abi":[]}""")
  File(dir, "Interop.cs").writeText(source)
}
