# Four reverse doc pages show pre-ADR-104 thunk signatures missing the trailing `errOut`

`docs/topics/structs.md`, `docs/topics/static-classes-and-methods.md`,
`docs/topics/generic-types.md`, and `docs/topics/instance-members.md` still show
`[UnmanagedCallersOnly]` thunk snippets (and their matching `CFunction`/call-site Kotlin, where
shown) copied before
[ADR-104](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/104-reverse-thunk-error-channel.md)
shipped. None of them carries the trailing `IntPtr* errOut` parameter, the `try`/`catch`, or the
regenerated `reverse_v2:`-tagged contract hash every real reverse thunk now has.

The omission is narrow and doesn't invalidate what these pages teach: `structs.md`'s out-pointer
component layout, `static-classes-and-methods.md`'s static routing, `generic-types.md`'s
per-instantiation witness dispatch, and `instance-members.md`'s receiver-handle-first parameter
order are all still correct shapes. This is accuracy rot (a missing parameter on an otherwise
faithful snippet), not a wrong explanation of a mechanism. The two canonical pages that own the
error-channel explanation itself, `docs/topics/reverse-overview.md` and
`docs/topics/bridgeable-subset.md` (`## Exceptions`), were updated alongside ADR-104 and are
byte-exact against real generated output.

Nothing catches this class of drift automatically: `scripts/verify-docs.sh` checks Writerside
build-time concerns (broken links, anchors, TOC membership) and passes regardless, since a fenced
code block's contents are opaque to it. Only a human or an agent diffing a snippet against a fresh
`build/`/snapshot output would notice.

Fix, when picked up: regenerate the relevant fixture output and re-derive each affected snippet
(there are on the order of a dozen across the four pages) rather than hand-patching parameter
lists, per the documenter agent's own "every snippet must come from code that compiles" rule.

Discovered alongside ADR-104's documentation pass. Flagged rather than fixed at the time: fixing
all four pages is its own pass, out of budget for the same session that shipped the channel.
