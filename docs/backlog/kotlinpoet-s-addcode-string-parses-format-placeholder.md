# KotlinPoet's `addCode(String)` parses `%` as a format placeholder even on the no-varargs overload.

> Extracted verbatim from `ROADMAP.md` (Tooling & Test Integrity) in the 2026-08-31 roadmap slim-down.

**KotlinPoet's `addCode(String)` parses `%` as a format placeholder even on the no-varargs overload.** Any generated Kotlin containing a literal `%` (e.g. a modulo operator) must escape it as `%%` or codegen breaks. **Verified**: hit and fixed in `exports/GenericClassExports.kt` while emitting `instantFromDotNetTicks`, whose `sinceEpoch % 10_000_000L` needed escaping ([ADR-076](docs/adr/076-instant-mapping.md)). Worth recording because the next feature emitting arithmetic through KotlinPoet will hit it blind; check whether other `addCode(String)` call sites in the processor are exposed to a literal `%` in generated source.
